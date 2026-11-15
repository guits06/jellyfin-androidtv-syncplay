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
) {
	val isInGroup: Boolean get() = syncPlayManager.isInGroup.value
	private var lastAnchorPositionTicks: Long = 0L
	private var lastAnchorLocalTimeMs: Long = 0L
	private var lastSeekCorrectionTimeMs: Long = 0L
	private var currentPlaybackSpeed: Float = SPEED_NORMAL
	private var driftJob: Job? = null

	init {
		observeCommands()
		observePlayQueue()
		observePlaybackSession()
		observeGroupState()
	}

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
				if (syncPlayManager.isHandlingRemoteCommand) return@onEach

				when (state) {
					GroupStateType.PLAYING -> {
						if (!controller.isPlaying) {
							Timber.i("SyncPlayPlaybackIntegration: Group transitioned to PLAYING, resuming local playback at ${controller.currentPosition}ms")
							syncPlayManager.isHandlingRemoteCommand = true
							try {
								withContext(mainDispatcher) {
									controller.play(controller.currentPosition)
								}
							} finally {
								syncPlayManager.isHandlingRemoteCommand = false
							}
						}
					}

					GroupStateType.PAUSED -> {
						if (controller.isPlaying) {
							Timber.i("SyncPlayPlaybackIntegration: Group transitioned to PAUSED, pausing local player")
							syncPlayManager.isHandlingRemoteCommand = true
							try {
								withContext(mainDispatcher) {
									controller.pause()
								}
							} finally {
								syncPlayManager.isHandlingRemoteCommand = false
							}
						}
					}

					GroupStateType.WAITING -> {
						if (controller.isPlaying) {
							Timber.i("SyncPlayPlaybackIntegration: Group transitioned to WAITING, pausing local player")
							syncPlayManager.isHandlingRemoteCommand = true
							try {
								withContext(mainDispatcher) {
									controller.pause()
								}
							} finally {
								syncPlayManager.isHandlingRemoteCommand = false
							}
						}
					}

					GroupStateType.IDLE -> {
						// Group is idle, do nothing
					}
				}
			}
			.launchIn(coroutineScope)
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod", "MagicNumber")
	internal fun executeRemoteCommand(command: SendCommand) {
		coroutineScope.launch {
			var currentGroup = syncPlayManager.currentGroup.value
			if (currentGroup == null) {
				Timber.i("SyncPlayPlaybackIntegration: Received command ${command.command} but currentGroup is null. Fetching group ${command.groupId}")
				syncPlayManager.getGroup(command.groupId)
				currentGroup = syncPlayManager.currentGroup.value
			}

			if (currentGroup == null || command.groupId != currentGroup.groupId) {
				Timber.d("SyncPlayPlaybackIntegration: Ignoring command for different group ${command.groupId}")
				return@launch
			}

			val delayMs = clockSync.calculateDelayMs(command.`when`)
			if (delayMs > 0) {
				delay(delayMs)
			}

			val controller = playbackControllerContainer.playbackController
			if (controller == null) {
				Timber.w("SyncPlayPlaybackIntegration: No active PlaybackController to apply command ${command.command}")
				return@launch
			}

			syncPlayManager.isHandlingRemoteCommand = true
			try {
				withContext(mainDispatcher) {
					when (command.command) {
						SendCommandType.PAUSE -> {
							Timber.i("SyncPlayPlaybackIntegration: Applying remote PAUSE")
							controller.pause()
						}

						SendCommandType.UNPAUSE -> {
							val elapsedMs = maxOf(0L, -delayMs)
							val baseTicks = command.positionTicks ?: (controller.currentPosition * TICKS_PER_MS)
							val targetPosMs = (baseTicks / TICKS_PER_MS) + elapsedMs
							Timber.i("SyncPlayPlaybackIntegration: Applying remote UNPAUSE at ${targetPosMs}ms")

							lastAnchorPositionTicks = targetPosMs * TICKS_PER_MS
							lastAnchorLocalTimeMs = System.currentTimeMillis()
							lastSeekCorrectionTimeMs = System.currentTimeMillis()
							if (currentPlaybackSpeed != SPEED_NORMAL) {
								currentPlaybackSpeed = SPEED_NORMAL
								controller.setPlaybackSpeed(SPEED_NORMAL)
							}

							if (abs(controller.currentPosition - targetPosMs) > 1500L) {
								controller.seek(targetPosMs)
							}
							controller.play(targetPosMs)
						}

						SendCommandType.SEEK -> {
							val targetPosMs = (command.positionTicks ?: 0L) / TICKS_PER_MS
							Timber.i("SyncPlayPlaybackIntegration: Applying remote SEEK to ${targetPosMs}ms")

							lastAnchorPositionTicks = targetPosMs * TICKS_PER_MS
							lastAnchorLocalTimeMs = System.currentTimeMillis()
							lastSeekCorrectionTimeMs = System.currentTimeMillis()
							if (currentPlaybackSpeed != SPEED_NORMAL) {
								currentPlaybackSpeed = SPEED_NORMAL
								controller.setPlaybackSpeed(SPEED_NORMAL)
							}

							controller.seek(targetPosMs)
						}

						SendCommandType.STOP -> {
							Timber.i("SyncPlayPlaybackIntegration: Applying remote STOP")
							controller.stop()
						}
					}
				}
			} finally {
				syncPlayManager.isHandlingRemoteCommand = false
			}
		}
	}

	private fun handlePlayQueueUpdate(playQueue: PlayQueueUpdate?) {
		if (playQueue == null || !syncPlayManager.isInGroup.value) return

		val currentItem = playQueue.playlist.getOrNull(playQueue.playingItemIndex) ?: return
		val controller = playbackControllerContainer.playbackController
		val playingItem = controller?.currentlyPlayingItem

		if (playingItem == null || playingItem.id != currentItem.itemId) {
			Timber.i("SyncPlayPlaybackIntegration: Switching to playlist item ${currentItem.itemId} (index ${playQueue.playingItemIndex})")
			coroutineScope.launch(mainDispatcher) {
				val startPositionTicks = playQueue.startPositionTicks
				playbackHelper.retrieveAndPlay(
					listOf(currentItem.itemId),
					false,
					startPositionTicks,
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
				if (controller != null && controller.isPlaying && syncPlayManager.isInGroup.value) {
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
	}

	private fun checkAndCorrectDrift(controller: PlaybackController) {
		if (syncPlayManager.groupState.value != GroupStateType.PLAYING) return
		if (!controller.isPlaying) return
		if (lastAnchorLocalTimeMs == 0L) return

		val now = System.currentTimeMillis()
		if (now - lastSeekCorrectionTimeMs < DRIFT_SEEK_COOLDOWN_MS) return

		val elapsedLocalMs = now - lastAnchorLocalTimeMs
		val expectedTicks = lastAnchorPositionTicks + (elapsedLocalMs * TICKS_PER_MS)
		val actualTicks = controller.currentPosition * TICKS_PER_MS
		val diffMs = (actualTicks - expectedTicks) / TICKS_PER_MS

		if (abs(diffMs) > DRIFT_SEEK_THRESHOLD_MS) {
			Timber.i("SyncPlayPlaybackIntegration: Large drift of $diffMs ms detected, performing seek correction")
			lastSeekCorrectionTimeMs = now
			syncPlayManager.isHandlingRemoteCommand = true
			try {
				val targetPosMs = expectedTicks / TICKS_PER_MS
				controller.seek(targetPosMs)
				lastAnchorPositionTicks = targetPosMs * TICKS_PER_MS
				lastAnchorLocalTimeMs = System.currentTimeMillis()
				if (currentPlaybackSpeed != SPEED_NORMAL) {
					currentPlaybackSpeed = SPEED_NORMAL
					controller.setPlaybackSpeed(SPEED_NORMAL)
				}
			} finally {
				syncPlayManager.isHandlingRemoteCommand = false
			}
		} else if (abs(diffMs) > DRIFT_SPEED_THRESHOLD_MS) {
			val targetSpeed = if (diffMs > 0) SPEED_SLOW_DOWN else SPEED_SPEED_UP
			if (currentPlaybackSpeed != targetSpeed) {
				currentPlaybackSpeed = targetSpeed
				Timber.d("SyncPlayPlaybackIntegration: Minor drift of $diffMs ms, adjusting speed to $targetSpeed")
				controller.setPlaybackSpeed(targetSpeed)
			}
		} else if (abs(diffMs) < DRIFT_SPEED_RESTORE_THRESHOLD_MS) {
			if (currentPlaybackSpeed != SPEED_NORMAL) {
				currentPlaybackSpeed = SPEED_NORMAL
				Timber.d("SyncPlayPlaybackIntegration: Drift resolved, restoring normal speed")
				controller.setPlaybackSpeed(SPEED_NORMAL)
			}
		}
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

	fun onUserStartPlayback(
		itemIds: List<UUID>,
		playingItemIndex: Int,
		startPositionTicks: Long,
	) {
		if (syncPlayManager.isInGroup.value && !syncPlayManager.isHandlingRemoteCommand) {
			val currentQueue = syncPlayManager.playQueue.value
			val currentPlayingItem = currentQueue?.playlist?.getOrNull(currentQueue.playingItemIndex)
			val targetItemId = itemIds.getOrNull(playingItemIndex)
			if (currentPlayingItem?.itemId == targetItemId) {
				Timber.d("SyncPlayPlaybackIntegration: Item $targetItemId already active in SyncPlay queue, skipping setNewQueue")
				return
			}
			coroutineScope.launch {
				Timber.i("SyncPlayPlaybackIntegration: Sending setNewQueue with ${itemIds.size} items, index $playingItemIndex")
				syncPlayManager.setNewQueue(itemIds, playingItemIndex, startPositionTicks)
			}
		}
	}

	fun onUserPause() {
		if (syncPlayManager.isInGroup.value && !syncPlayManager.isHandlingRemoteCommand) {
			coroutineScope.launch {
				syncPlayManager.sendPause()
			}
		}
	}

	fun onUserPlay() {
		if (syncPlayManager.isInGroup.value && !syncPlayManager.isHandlingRemoteCommand) {
			coroutineScope.launch {
				syncPlayManager.sendUnpause()
			}
		}
	}

	fun onUserSeek(positionMs: Long) {
		if (syncPlayManager.isInGroup.value && !syncPlayManager.isHandlingRemoteCommand) {
			coroutineScope.launch {
				syncPlayManager.sendSeek(positionMs * TICKS_PER_MS)
			}
		}
	}

	fun onUserStop() {
		if (syncPlayManager.isInGroup.value && !syncPlayManager.isHandlingRemoteCommand) {
			coroutineScope.launch {
				syncPlayManager.sendStop()
			}
		}
	}

	fun onUserNext() {
		if (syncPlayManager.isInGroup.value && !syncPlayManager.isHandlingRemoteCommand) {
			val playlistItemId = getCurrentPlaylistItemId() ?: return
			coroutineScope.launch {
				syncPlayManager.nextItem(playlistItemId)
			}
		}
	}

	fun onUserPrevious() {
		if (syncPlayManager.isInGroup.value && !syncPlayManager.isHandlingRemoteCommand) {
			val playlistItemId = getCurrentPlaylistItemId() ?: return
			coroutineScope.launch {
				syncPlayManager.previousItem(playlistItemId)
			}
		}
	}

	fun onPlayerBuffering() {
		if (syncPlayManager.isInGroup.value) {
			val playlistItemId = getCurrentPlaylistItemId() ?: return
			val controller = playbackControllerContainer.playbackController
			val positionTicks = (controller?.currentPosition ?: 0L) * TICKS_PER_MS
			val isPlaying = controller?.isPlaying ?: false

			coroutineScope.launch {
				syncPlayManager.sendBuffering(positionTicks, isPlaying, playlistItemId)
			}
		}
	}

	fun onPlayerPrepared() {
		if (syncPlayManager.isInGroup.value) {
			val controller = playbackControllerContainer.playbackController
			val positionTicks = (controller?.currentPosition ?: 0L) * TICKS_PER_MS

			lastAnchorPositionTicks = positionTicks
			lastAnchorLocalTimeMs = System.currentTimeMillis()
			lastSeekCorrectionTimeMs = System.currentTimeMillis()
			if (currentPlaybackSpeed != SPEED_NORMAL) {
				currentPlaybackSpeed = SPEED_NORMAL
				controller?.setPlaybackSpeed(SPEED_NORMAL)
			}

			val shouldPause = syncPlayManager.groupState.value == GroupStateType.PAUSED ||
				syncPlayManager.groupState.value == GroupStateType.WAITING

			if (shouldPause) {
				Timber.i("SyncPlayPlaybackIntegration: Group is ${syncPlayManager.groupState.value}, pausing local player upon prepare")
				syncPlayManager.isHandlingRemoteCommand = true
				try {
					controller?.pause()
				} finally {
					syncPlayManager.isHandlingRemoteCommand = false
				}
			}

			val isPlaying = if (shouldPause) false else (controller?.isPlaying ?: false)

			coroutineScope.launch {
				var playlistItemId = getCurrentPlaylistItemId()
				if (playlistItemId == null) {
					withTimeoutOrNull(2000.milliseconds) {
						syncPlayManager.playQueue.first { it != null && it.playlist.isNotEmpty() }
					}
					playlistItemId = getCurrentPlaylistItemId()
				}

				if (playlistItemId != null) {
					syncPlayManager.sendReady(positionTicks, isPlaying, playlistItemId)
				} else {
					Timber.w("SyncPlayPlaybackIntegration: Ready not sent, no valid playlistItemId found")
				}
			}
		}
	}

	companion object {
		const val TICKS_PER_MS = 10000L
		const val DRIFT_CHECK_INTERVAL_MS = 3000L
		const val DRIFT_SEEK_THRESHOLD_MS = 3500L
		const val DRIFT_SEEK_COOLDOWN_MS = 8000L
		const val DRIFT_SPEED_THRESHOLD_MS = 600L
		const val DRIFT_SPEED_RESTORE_THRESHOLD_MS = 200L
		const val SPEED_SLOW_DOWN = 0.96f
		const val SPEED_SPEED_UP = 1.04f
		const val SPEED_NORMAL = 1.0f
	}
}
