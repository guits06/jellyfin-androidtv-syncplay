package org.jellyfin.androidtv.syncplay

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.PlaybackRequestType
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import timber.log.Timber
import java.util.UUID
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@Suppress("LongParameterList", "TooManyFunctions")
class SyncPlayPlaybackIntegration(
	private val context: Context,
	val syncPlayManager: SyncPlayManager,
	private val clockSync: SyncPlayClockSync,
	private val playbackControllerContainer: PlaybackControllerContainer,
	private val playbackHelper: PlaybackHelper,
	private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
	private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
	private val elapsedRealtimeMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
	val isInGroup: Boolean get() = syncPlayManager.isInGroup.value

	private var lastAnchorPositionTicks: Long = 0L
	private var lastAnchorLocalTimeMs: Long = 0L
	private var lastSeekCorrectionTimeMs: Long = 0L
	private var currentPlaybackSpeed: Float = SPEED_NORMAL
	private var speedCorrectionAttempted: Boolean = false
	private var speedCorrectionUnsupported: Boolean = false
	private var driftJob: Job? = null

	// Explicit state machine fields replacing fragile single-call booleans
	private var scheduledCommandJob: Job? = null
	private var internalTransitionCount: Int = 0
	private var internalTransitionReason: PlaybackTransitionReason = PlaybackTransitionReason.NONE
	private var pendingPreSwitchPlaying: Boolean? = null
	private var remoteSuppressionUntilElapsedMs: Long = 0L
	private var pendingQueueSwitchItemId: UUID? = null
	private var pendingQueueSwitchUntilElapsedMs: Long = 0L
	private var hasSentBufferingThisStall: Boolean = false
	private var lastBufferingSentElapsedMs: Long = 0L
	private var desiredPlayWhenReady: Boolean? = null

	init {
		observeCommands()
		observePlayQueue()
		observePlaybackSession()
		observeGroupState()
		observeReconnects()
	}

	private fun log(): Timber.Tree = Timber.tag(TAG)

	private fun observeCommands() {
		syncPlayManager.commands
			.onEach { command ->
				executeRemoteCommand(command)
			}
			.launchIn(coroutineScope)
	}

	private fun observePlayQueue() {
		combine(
			syncPlayManager.isInGroup,
			syncPlayManager.playQueue,
		) { inGroup, playQueue ->
			if (inGroup && playQueue != null) {
				handlePlayQueueUpdate(playQueue)
			}
		}.launchIn(coroutineScope)
	}

	private fun observePlaybackSession() {
		playbackControllerContainer.playbackControllerFlow
			.onEach { controller ->
				if (controller != null) {
					speedCorrectionAttempted = false
					speedCorrectionUnsupported = false
					hasSentBufferingThisStall = false
					startDriftMonitor()
				} else {
					stopDriftMonitor()
				}
			}
			.launchIn(coroutineScope)
	}

	private fun observeGroupState() {
		syncPlayManager.groupState
			.onEach { state ->
				if (!syncPlayManager.isInGroup.value) return@onEach
				val controller = playbackControllerContainer.playbackController ?: return@onEach
				if (isInternalTransitionActive() || isRemoteCommandSuppressed()) return@onEach

				when (state) {
					GroupStateType.PAUSED,
					GroupStateType.WAITING -> {
						desiredPlayWhenReady = false
						if (controller.isPlaying && scheduledCommandJob?.isActive != true) {
							log().i("Group state -> %s while locally playing; pausing locally", state)
							suppressRemoteSideEffectsFor(ASYNC_PLAYER_SUPPRESSION_MS)
							withContext(mainDispatcher) {
								controller.pauseRemote()
							}
						}
					}

					GroupStateType.PLAYING -> {
						// Do NOT unpause here: UNPAUSE SendCommand schedules exact playback start at `When`.
						desiredPlayWhenReady = true
					}

					GroupStateType.IDLE -> {
						desiredPlayWhenReady = false
					}
				}
			}
			.launchIn(coroutineScope)
	}

	private fun observeReconnects() {
		syncPlayManager.reconnected
			.onEach {
				val controller = playbackControllerContainer.playbackController ?: return@onEach
				if (!syncPlayManager.isInGroup.value) return@onEach

				val groupState = syncPlayManager.groupState.value
				val positionTicks = controller.currentPosition * TICKS_PER_MS
				val playlistItemId = getCurrentPlaylistItemId()
				log().i("Reconnect resync: groupState=%s pos=%dms", groupState, controller.currentPosition)
				when (groupState) {
					GroupStateType.PAUSED,
					GroupStateType.WAITING -> {
						if (controller.isPlaying) {
							suppressRemoteSideEffectsFor(ASYNC_PLAYER_SUPPRESSION_MS)
							withContext(mainDispatcher) {
								controller.pauseRemote()
							}
						}
						if (playlistItemId != null) {
							syncPlayManager.sendReady(positionTicks, false, playlistItemId)
						}
					}

					GroupStateType.PLAYING -> {
						if (playlistItemId != null) {
							syncPlayManager.sendReady(positionTicks, controller.isPlaying, playlistItemId)
						}
					}

					GroupStateType.IDLE -> Unit
				}
			}
			.launchIn(coroutineScope)
	}

	/**
	 * Marks the start of an internal player rebuild/track switch so ExoPlayer
	 * teardown/seek/buffer callbacks never leak as local user SyncPlay commands.
	 */
	fun beginInternalTransition(
		reason: PlaybackTransitionReason,
		wasPlaying: Boolean? = null,
	) {
		internalTransitionCount++
		internalTransitionReason = reason
		if (wasPlaying != null) {
			pendingPreSwitchPlaying = wasPlaying
		}
		log().i("beginInternalTransition reason=%s depth=%d wasPlaying=%s", reason, internalTransitionCount, wasPlaying)
	}

	fun endInternalTransition() {
		if (internalTransitionCount > 0) {
			internalTransitionCount--
		}
		if (internalTransitionCount == 0) {
			internalTransitionReason = PlaybackTransitionReason.NONE
			pendingPreSwitchPlaying = null
		}
		log().i("endInternalTransition depth=%d", internalTransitionCount)
	}

	fun isInternalTransitionActive(): Boolean = internalTransitionCount > 0

	/**
	 * Tells PlaybackController whether initial stream start should hold paused
	 * rather than auto-playing immediately.
	 */
	fun shouldStartPaused(): Boolean {
		if (!syncPlayManager.isInGroup.value) return false
		val state = syncPlayManager.groupState.value
		if (state == GroupStateType.PAUSED || state == GroupStateType.WAITING || state == GroupStateType.IDLE) {
			return true
		}
		return desiredPlayWhenReady == false
	}

	private fun isRemoteCommandSuppressed(): Boolean =
		syncPlayManager.isHandlingRemoteCommand || elapsedRealtimeMs() < remoteSuppressionUntilElapsedMs

	private fun isUserActionBlocked(
		origin: SyncPlayEventOrigin,
		ignoreAsyncSuppressionWindow: Boolean = false,
	): Boolean {
		if (!syncPlayManager.isInGroup.value) return true
		return when (origin) {
			SyncPlayEventOrigin.REMOTE_SYNCPLAY,
			SyncPlayEventOrigin.INTERNAL_PLAYER_TRANSITION -> true

			SyncPlayEventOrigin.USER_LOCAL -> {
				val suppressed = if (ignoreAsyncSuppressionWindow) {
					syncPlayManager.isHandlingRemoteCommand
				} else {
					isRemoteCommandSuppressed()
				}
				isInternalTransitionActive() || suppressed
			}
		}
	}

	private fun suppressRemoteSideEffectsFor(durationMs: Long) {
		val target = elapsedRealtimeMs() + durationMs
		if (target > remoteSuppressionUntilElapsedMs) {
			remoteSuppressionUntilElapsedMs = target
		}
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber")
	internal fun executeRemoteCommand(command: SendCommand) {
		scheduledCommandJob?.cancel()
		scheduledCommandJob = coroutineScope.launch {
			var currentGroup = syncPlayManager.currentGroup.value
			if (currentGroup == null) {
				log().i("Received command %s without cached group; fetching %s", command.command, command.groupId)
				syncPlayManager.getGroup(command.groupId)
				currentGroup = syncPlayManager.currentGroup.value
			}

			if (currentGroup == null || command.groupId != currentGroup.groupId) {
				log().d("Ignoring command for non-active group %s", command.groupId)
				return@launch
			}

			val rawDelayMs = clockSync.calculateDelayMs(command.`when`)
			val targetTicks = command.positionTicks ?: 0L
			val rawTargetMs = targetTicks / TICKS_PER_MS

			// Pre-seek while paused during wait window for smoother UNPAUSE frame readiness
			if (command.command == SendCommandType.UNPAUSE && rawDelayMs > PRE_SEEK_MIN_DELAY_MS) {
				val preSeekController = playbackControllerContainer.playbackController
				if (preSeekController != null) {
					suppressRemoteSideEffectsFor(rawDelayMs + ASYNC_PLAYER_SUPPRESSION_MS)
					withContext(mainDispatcher) {
						preSeekController.pauseRemote()
						if (abs(preSeekController.currentPosition - rawTargetMs) > PRE_SEEK_TOLERANCE_MS) {
							preSeekController.seekRemote(rawTargetMs, false)
						}
					}
				}
			}

			if (rawDelayMs > 0) {
				delay(rawDelayMs)
			}

			val controller = playbackControllerContainer.playbackController
			if (controller == null) {
				log().w("No active PlaybackController to execute remote %s", command.command)
				return@launch
			}

			syncPlayManager.isHandlingRemoteCommand = true
			suppressRemoteSideEffectsFor(ASYNC_PLAYER_SUPPRESSION_MS)
			try {
				withContext(mainDispatcher) {
					when (command.command) {
						SendCommandType.PAUSE -> {
							desiredPlayWhenReady = false
							lastAnchorLocalTimeMs = 0L
							if (command.positionTicks != null) {
								lastAnchorPositionTicks = command.positionTicks!!
							}
							log().i("Applying remote PAUSE at %dms", rawTargetMs)
							controller.pauseRemote()
							if (command.positionTicks != null &&
								abs(controller.currentPosition - rawTargetMs) > PAUSED_SEEK_TOLERANCE_MS
							) {
								controller.seekRemote(rawTargetMs, false)
							}
						}

						SendCommandType.UNPAUSE -> {
							desiredPlayWhenReady = true
							val elapsedMs = maxOf(0L, -rawDelayMs)
							val baseTicks = command.positionTicks ?: (controller.currentPosition * TICKS_PER_MS)
							val targetPosMs = (baseTicks / TICKS_PER_MS) + elapsedMs
							log().i("Applying remote UNPAUSE at %dms (delay=%dms)", targetPosMs, rawDelayMs)

							lastAnchorPositionTicks = targetPosMs * TICKS_PER_MS
							lastAnchorLocalTimeMs = elapsedRealtimeMs()
							lastSeekCorrectionTimeMs = lastAnchorLocalTimeMs
							hasSentBufferingThisStall = false
							resetPlaybackSpeedIfNeeded(controller)

							if (abs(controller.currentPosition - targetPosMs) > UNPAUSE_SEEK_TOLERANCE_MS) {
								controller.seekRemote(targetPosMs, true)
							} else {
								controller.playRemote(targetPosMs)
							}
						}

						SendCommandType.SEEK -> {
							// After Seek, Jellyfin server moves group to WAITING until all Ready -> Unpause/Pause.
							desiredPlayWhenReady = false
							lastAnchorPositionTicks = rawTargetMs * TICKS_PER_MS
							lastAnchorLocalTimeMs = 0L
							lastSeekCorrectionTimeMs = elapsedRealtimeMs()
							hasSentBufferingThisStall = false
							resetPlaybackSpeedIfNeeded(controller)
							log().i("Applying remote SEEK to %dms (awaiting Ready -> Unpause/Pause)", rawTargetMs)
							controller.seekRemote(rawTargetMs, false)
						}

						SendCommandType.STOP -> {
							desiredPlayWhenReady = false
							lastAnchorPositionTicks = 0L
							lastAnchorLocalTimeMs = 0L
							pendingQueueSwitchItemId = null
							log().i("Applying remote STOP")
							controller.stopRemote()
						}
					}
				}
			} finally {
				syncPlayManager.isHandlingRemoteCommand = false
			}
		}
	}

	private fun resetPlaybackSpeedIfNeeded(controller: PlaybackController) {
		if (currentPlaybackSpeed != SPEED_NORMAL) {
			currentPlaybackSpeed = SPEED_NORMAL
			controller.setPlaybackSpeed(SPEED_NORMAL)
		}
	}

	private fun handlePlayQueueUpdate(playQueue: PlayQueueUpdate?) {
		if (playQueue == null || !syncPlayManager.isInGroup.value) return

		val currentItem = playQueue.playlist.getOrNull(playQueue.playingItemIndex) ?: return
		val controller = playbackControllerContainer.playbackController
		val playingItem = controller?.currentlyPlayingItem

		desiredPlayWhenReady = playQueue.isPlaying

		if (playingItem == null || playingItem.id != currentItem.itemId) {
			log().i("PlayQueue switching item to %s (index=%d startTicks=%d)", currentItem.itemId, playQueue.playingItemIndex, playQueue.startPositionTicks)
			pendingQueueSwitchItemId = playingItem?.id
			pendingQueueSwitchUntilElapsedMs = elapsedRealtimeMs() + QUEUE_SWITCH_SUPPRESSION_MS
			suppressRemoteSideEffectsFor(ASYNC_PLAYER_SUPPRESSION_MS)
			coroutineScope.launch(mainDispatcher) {
				playbackHelper.retrieveAndPlay(
					listOf(currentItem.itemId),
					false,
					playQueue.startPositionTicks,
					0,
					context,
				)
			}
		}
	}

	private fun startDriftMonitor() {
		driftJob?.cancel()
		driftJob = coroutineScope.launch {
			while (isActive) {
				delay(DRIFT_CHECK_INTERVAL_MS)
				val controller = playbackControllerContainer.playbackController
				if (controller != null &&
					controller.isPlaying &&
					syncPlayManager.isInGroup.value &&
					!isInternalTransitionActive()
				) {
					checkAndCorrectDrift(controller)
				}
			}
		}
	}

	private fun stopDriftMonitor() {
		driftJob?.cancel()
		driftJob = null
		lastAnchorPositionTicks = 0L
		lastAnchorLocalTimeMs = 0L
		lastSeekCorrectionTimeMs = 0L
		currentPlaybackSpeed = SPEED_NORMAL
		speedCorrectionAttempted = false
		speedCorrectionUnsupported = false
	}

	internal fun checkAndCorrectDrift(controller: PlaybackController) {
		if (syncPlayManager.groupState.value != GroupStateType.PLAYING) return
		if (desiredPlayWhenReady != true) return
		if (!controller.isPlaying) return
		if (lastAnchorLocalTimeMs == 0L) return

		val now = elapsedRealtimeMs()
		if (now - lastSeekCorrectionTimeMs < DRIFT_SEEK_COOLDOWN_MS) return

		val expectedPosMs = computeExpectedPositionMs(now)
		val actualPosMs = controller.currentPosition
		val diffMs = actualPosMs - expectedPosMs

		if (abs(diffMs) > DRIFT_SEEK_THRESHOLD_MS) {
			log().i("Drift %dms exceeds seek threshold -> seeking to %dms", diffMs, expectedPosMs)
			lastSeekCorrectionTimeMs = now
			suppressRemoteSideEffectsFor(ASYNC_PLAYER_SUPPRESSION_MS)
			syncPlayManager.isHandlingRemoteCommand = true
			try {
				controller.seekRemote(expectedPosMs, true)
				lastAnchorPositionTicks = expectedPosMs * TICKS_PER_MS
				lastAnchorLocalTimeMs = now
				resetPlaybackSpeedIfNeeded(controller)
			} finally {
				syncPlayManager.isHandlingRemoteCommand = false
			}
		} else if (!speedCorrectionUnsupported && abs(diffMs) > DRIFT_SPEED_THRESHOLD_MS) {
			val targetSpeed = if (diffMs > 0) SPEED_SLOW_DOWN else SPEED_SPEED_UP
			// Detect whether ExoPlayer actually honored a prior speed adjustment (e.g. audio offload/passthrough ignores speed)
			if (speedCorrectionAttempted && currentPlaybackSpeed != SPEED_NORMAL &&
				abs(controller.playbackSpeed - SPEED_NORMAL) < 0.01f
			) {
				log().i("Playback speed control unsupported by current audio pipeline; disabling speed nudging for stream")
				speedCorrectionUnsupported = true
				currentPlaybackSpeed = SPEED_NORMAL
				controller.setPlaybackSpeed(SPEED_NORMAL)
				return
			}
			if (currentPlaybackSpeed != targetSpeed) {
				currentPlaybackSpeed = targetSpeed
				speedCorrectionAttempted = true
				log().d("Minor drift %dms -> setting speed %.2f", diffMs, targetSpeed)
				controller.setPlaybackSpeed(targetSpeed)
			}
		} else if (abs(diffMs) < DRIFT_SPEED_RESTORE_THRESHOLD_MS) {
			if (currentPlaybackSpeed != SPEED_NORMAL) {
				resetPlaybackSpeedIfNeeded(controller)
			}
		}
	}

	private fun computeExpectedPositionMs(nowElapsedMs: Long = elapsedRealtimeMs()): Long {
		val elapsedLocalMs = maxOf(0L, nowElapsedMs - lastAnchorLocalTimeMs)
		return (lastAnchorPositionTicks / TICKS_PER_MS) + elapsedLocalMs
	}

	fun getCurrentPlaylistItemId(): UUID? {
		val queue = syncPlayManager.playQueue.value ?: return null
		val controller = playbackControllerContainer.playbackController
		val playingItemId = controller?.currentlyPlayingItem?.id
		if (playingItemId != null) {
			val match = queue.playlist.find { it.itemId == playingItemId }
			if (match != null) return match.playlistItemId
		}
		val index = queue.playingItemIndex
		return queue.playlist.getOrNull(index)?.playlistItemId
	}

	@JvmOverloads
	fun onUserStartPlayback(
		itemIds: List<UUID>,
		playingItemIndex: Int,
		startPositionTicks: Long,
		origin: SyncPlayEventOrigin = SyncPlayEventOrigin.USER_LOCAL,
	) {
		if (isUserActionBlocked(origin, ignoreAsyncSuppressionWindow = true)) {
			log().d("Blocked onUserStartPlayback (origin=%s, internal=%s)", origin, internalTransitionReason)
			return
		}
		val currentQueue = syncPlayManager.playQueue.value
		val currentPlayingItem = currentQueue?.playlist?.getOrNull(currentQueue.playingItemIndex)
		val targetItemId = itemIds.getOrNull(playingItemIndex)
		if (currentPlayingItem?.itemId == targetItemId) {
			log().d("Item %s already active in SyncPlay queue, skipping setNewQueue", targetItemId)
			return
		}
		coroutineScope.launch {
			log().i("Sending setNewQueue (%d items, index=%d, startTicks=%d)", itemIds.size, playingItemIndex, startPositionTicks)
			syncPlayManager.setNewQueue(itemIds, playingItemIndex, startPositionTicks)
		}
	}

	@JvmOverloads
	fun onUserPause(origin: SyncPlayEventOrigin = SyncPlayEventOrigin.USER_LOCAL) {
		if (isUserActionBlocked(origin)) return
		desiredPlayWhenReady = false
		coroutineScope.launch {
			log().i("User local PAUSE -> sending /SyncPlay/Pause")
			syncPlayManager.sendPause()
		}
	}

	@JvmOverloads
	fun onUserPlay(origin: SyncPlayEventOrigin = SyncPlayEventOrigin.USER_LOCAL) {
		if (isUserActionBlocked(origin)) return
		desiredPlayWhenReady = true
		coroutineScope.launch {
			log().i("User local PLAY -> sending /SyncPlay/Unpause")
			syncPlayManager.sendUnpause()
		}
	}

	@JvmOverloads
	fun onUserSeek(positionMs: Long, origin: SyncPlayEventOrigin = SyncPlayEventOrigin.USER_LOCAL) {
		if (isUserActionBlocked(origin)) return
		coroutineScope.launch {
			log().i("User local SEEK -> %dms", positionMs)
			syncPlayManager.sendSeek(positionMs * TICKS_PER_MS)
		}
	}

	@JvmOverloads
	fun onUserStop(origin: SyncPlayEventOrigin = SyncPlayEventOrigin.USER_LOCAL) {
		val currentControllerItemId = playbackControllerContainer.playbackController?.currentlyPlayingItem?.id
		if (pendingQueueSwitchItemId != null &&
			elapsedRealtimeMs() < pendingQueueSwitchUntilElapsedMs &&
			(currentControllerItemId == null || currentControllerItemId == pendingQueueSwitchItemId)
		) {
			log().i("Ignoring tear-down stop for item %s during SyncPlay queue switch", currentControllerItemId)
			pendingQueueSwitchItemId = null
			return
		}
		if (isUserActionBlocked(origin)) return
		desiredPlayWhenReady = false
		coroutineScope.launch {
			log().i("User local STOP -> sending /SyncPlay/Stop")
			syncPlayManager.sendStop()
		}
	}

	fun onUserNext() {
		if (isUserActionBlocked(SyncPlayEventOrigin.USER_LOCAL)) return
		val playlistItemId = getCurrentPlaylistItemId() ?: return
		coroutineScope.launch {
			log().i("User NEXT -> playlistItemId=%s", playlistItemId)
			syncPlayManager.nextItem(playlistItemId)
		}
	}

	fun onUserPrevious() {
		if (isUserActionBlocked(SyncPlayEventOrigin.USER_LOCAL)) return
		val playlistItemId = getCurrentPlaylistItemId() ?: return
		coroutineScope.launch {
			log().i("User PREVIOUS -> playlistItemId=%s", playlistItemId)
			syncPlayManager.previousItem(playlistItemId)
		}
	}

	/**
	 * Called only when mid-playback network stalling occurs AFTER the stream was already prepared.
	 * Track switches, burn-in rebuilds, and remote seeks suppress spurious Buffering pings.
	 */
	fun onPlayerBuffering() {
		if (!syncPlayManager.isInGroup.value) return
		if (isInternalTransitionActive() || isRemoteCommandSuppressed()) {
			log().d("Ignoring buffering during transition/suppression")
			return
		}
		if (syncPlayManager.groupState.value != GroupStateType.PLAYING) {
			// Avoid repeatedly nudging Waiting/Paused group deadlines on decoder stalls
			return
		}
		val now = elapsedRealtimeMs()
		if (hasSentBufferingThisStall || now - lastBufferingSentElapsedMs < BUFFERING_DEBOUNCE_MS) return

		val playlistItemId = getCurrentPlaylistItemId() ?: return
		val controller = playbackControllerContainer.playbackController ?: return
		val positionTicks = controller.currentPosition * TICKS_PER_MS
		hasSentBufferingThisStall = true
		lastBufferingSentElapsedMs = now

		coroutineScope.launch {
			log().i("Mid-playback buffering at %dms -> /SyncPlay/Buffering", controller.currentPosition)
			syncPlayManager.sendBuffering(positionTicks, false, playlistItemId)
		}
	}

	fun onPlayerBufferingEnded() {
		if (!syncPlayManager.isInGroup.value) return
		if (!hasSentBufferingThisStall) return
		hasSentBufferingThisStall = false
		if (isInternalTransitionActive()) return

		val controller = playbackControllerContainer.playbackController ?: return
		val playlistItemId = getCurrentPlaylistItemId() ?: return
		val positionTicks = controller.currentPosition * TICKS_PER_MS
		coroutineScope.launch {
			log().i("Recovered from stall at %dms -> /SyncPlay/Ready", controller.currentPosition)
			syncPlayManager.sendReady(positionTicks, controller.isPlaying, playlistItemId)
		}
	}

	fun onPlayerPrepared() {
		val controller = playbackControllerContainer.playbackController
		onStreamReady(
			positionMs = controller?.currentPosition ?: 0L,
			isPlaying = controller?.isPlaying ?: false,
		)
	}

	fun onStreamReady(positionMs: Long, isPlaying: Boolean) {
		val wasInternalTransition = isInternalTransitionActive()
		val previousReason = internalTransitionReason
		val wasPlayingBeforeSwitch = pendingPreSwitchPlaying

		if (wasInternalTransition) {
			endInternalTransition()
		}

		if (!syncPlayManager.isInGroup.value) return
		val controller = playbackControllerContainer.playbackController

		resetPlaybackSpeedIfNeededIfPresent(controller)
		lastSeekCorrectionTimeMs = elapsedRealtimeMs()

		if (wasInternalTransition &&
			(previousReason == PlaybackTransitionReason.TRACK_SWITCH || previousReason == PlaybackTransitionReason.BURN_IN_REBUILD)
		) {
			log().i(
				"Internal %s complete at %dms (groupState=%s wasPlayingBefore=%s)",
				previousReason,
				positionMs,
				syncPlayManager.groupState.value,
				wasPlayingBeforeSwitch,
			)
			val groupState = syncPlayManager.groupState.value
			if (groupState == GroupStateType.PAUSED || groupState == GroupStateType.WAITING || desiredPlayWhenReady == false) {
				desiredPlayWhenReady = false
				suppressRemoteSideEffectsFor(ASYNC_PLAYER_SUPPRESSION_MS)
				if (controller != null && controller.isPlaying) {
					controller.pauseRemote()
				}
			} else if (groupState == GroupStateType.PLAYING && lastAnchorLocalTimeMs > 0L && controller != null) {
				val expectedPosMs = computeExpectedPositionMs()
				if (abs(controller.currentPosition - expectedPosMs) > REBUILD_CATCHUP_THRESHOLD_MS) {
					log().i("Catching up after %s from %dms to live anchor %dms", previousReason, controller.currentPosition, expectedPosMs)
					suppressRemoteSideEffectsFor(ASYNC_PLAYER_SUPPRESSION_MS)
					controller.seekRemote(expectedPosMs, true)
				} else if (!controller.isPlaying) {
					suppressRemoteSideEffectsFor(ASYNC_PLAYER_SUPPRESSION_MS)
					controller.playRemote(expectedPosMs)
				}
			}
			return
		}

		val shouldPause = shouldStartPaused()
		var effectiveIsPlaying = isPlaying

		if (shouldPause && controller != null) {
			log().i("Group state is %s on stream ready; enforcing paused state", syncPlayManager.groupState.value)
			suppressRemoteSideEffectsFor(ASYNC_PLAYER_SUPPRESSION_MS)
			syncPlayManager.isHandlingRemoteCommand = true
			try {
				controller.pauseRemote()
			} finally {
				syncPlayManager.isHandlingRemoteCommand = false
			}
			effectiveIsPlaying = false
		}

		val positionTicks = positionMs * TICKS_PER_MS
		lastAnchorPositionTicks = positionTicks
		lastAnchorLocalTimeMs = if (effectiveIsPlaying) elapsedRealtimeMs() else 0L
		hasSentBufferingThisStall = false

		coroutineScope.launch {
			var playlistItemId = getCurrentPlaylistItemId()
			if (playlistItemId == null) {
				withTimeoutOrNull(2000.milliseconds) {
					syncPlayManager.playQueue.first { it != null && it.playlist.isNotEmpty() }
				}
				playlistItemId = getCurrentPlaylistItemId()
			}

			if (playlistItemId != null) {
				log().i("Sending /SyncPlay/Ready pos=%dms isPlaying=%s item=%s", positionMs, effectiveIsPlaying, playlistItemId)
				syncPlayManager.sendReady(positionTicks, effectiveIsPlaying, playlistItemId)
			} else {
				log().w("Ready not sent: no valid playlistItemId resolved")
			}
		}
	}

	private fun resetPlaybackSpeedIfNeededIfPresent(controller: PlaybackController?) {
		if (controller != null && currentPlaybackSpeed != SPEED_NORMAL) {
			currentPlaybackSpeed = SPEED_NORMAL
			controller.setPlaybackSpeed(SPEED_NORMAL)
		}
	}

	companion object {
		private const val TAG = "SyncPlay"
		private const val NANOS_PER_MS = 1_000_000L
		const val TICKS_PER_MS = 10000L
		const val DRIFT_CHECK_INTERVAL_MS = 3000L
		const val DRIFT_SEEK_THRESHOLD_MS = 3000L
		const val DRIFT_SEEK_COOLDOWN_MS = 8000L
		const val DRIFT_SPEED_THRESHOLD_MS = 600L
		const val DRIFT_SPEED_RESTORE_THRESHOLD_MS = 200L
		const val SPEED_SLOW_DOWN = 0.96f
		const val SPEED_SPEED_UP = 1.04f
		const val SPEED_NORMAL = 1.0f

		const val ASYNC_PLAYER_SUPPRESSION_MS = 1500L
		const val QUEUE_SWITCH_SUPPRESSION_MS = 5000L
		const val PRE_SEEK_MIN_DELAY_MS = 80L
		const val PRE_SEEK_TOLERANCE_MS = 500L
		const val UNPAUSE_SEEK_TOLERANCE_MS = 1200L
		const val PAUSED_SEEK_TOLERANCE_MS = 1500L
		const val REBUILD_CATCHUP_THRESHOLD_MS = 1000L
		const val BUFFERING_DEBOUNCE_MS = 1000L
	}
}
