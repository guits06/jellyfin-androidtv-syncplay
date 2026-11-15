package org.jellyfin.androidtv.syncplay

import android.content.Context
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupRepeatMode
import org.jellyfin.sdk.model.api.GroupShuffleMode
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdateReason
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayQueueItem
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class SyncPlayStateMachineTests : FunSpec({
	fun createMockManager(
		groupId: UUID,
		state: GroupStateType = GroupStateType.PLAYING,
		itemId: UUID = UUID.randomUUID(),
		playlistItemId: UUID = UUID.randomUUID(),
		isPlayingQueue: Boolean = true,
	): SyncPlayManager {
		val groupInfo = GroupInfoDto(
			groupId = groupId,
			groupName = "Test Group",
			state = state,
			participants = listOf("TV", "PC"),
			lastUpdatedAt = LocalDateTime.now(ZoneOffset.UTC),
		)
		val queue = PlayQueueUpdate(
			reason = PlayQueueUpdateReason.NEW_PLAYLIST,
			lastUpdate = LocalDateTime.now(ZoneOffset.UTC),
			playlist = listOf(SyncPlayQueueItem(itemId = itemId, playlistItemId = playlistItemId)),
			playingItemIndex = 0,
			startPositionTicks = 0L,
			isPlaying = isPlayingQueue,
			shuffleMode = GroupShuffleMode.SORTED,
			repeatMode = GroupRepeatMode.REPEAT_NONE,
		)
		return mockk<SyncPlayManager>(relaxed = true).also { mgr ->
			every { mgr.isInGroup } returns MutableStateFlow(true)
			every { mgr.currentGroup } returns MutableStateFlow(groupInfo)
			every { mgr.groupState } returns MutableStateFlow(state)
			every { mgr.playQueue } returns MutableStateFlow(queue)
			every { mgr.commands } returns MutableSharedFlow(extraBufferCapacity = 16)
			every { mgr.reconnected } returns MutableSharedFlow(extraBufferCapacity = 8)
			every { mgr.isHandlingRemoteCommand } returns false
		}
	}

	test("New PAUSE command cancels pending delayed UNPAUSE so player never resumes unexpectedly") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		var nowMs = 1000L

		val groupId = UUID.randomUUID()
		val manager = createMockManager(groupId, state = GroupStateType.PAUSED, isPlayingQueue = false)
		val clockSync = mockk<SyncPlayClockSync>(relaxed = true)
		val container = mockk<PlaybackControllerContainer>(relaxed = true)
		val controller = mockk<PlaybackController>(relaxed = true)
		every { container.playbackController } returns controller
		every { container.playbackControllerFlow } returns MutableStateFlow(controller)
		every { controller.currentPosition } returns 12000L
		every { controller.isPlaying } returns false

		val integration = SyncPlayPlaybackIntegration(
			context = mockk<Context>(relaxed = true),
			syncPlayManager = manager,
			clockSync = clockSync,
			playbackControllerContainer = container,
			playbackHelper = mockk<PlaybackHelper>(relaxed = true),
			coroutineScope = scope,
			mainDispatcher = Dispatchers.Unconfined,
			elapsedRealtimeMs = { nowMs },
		)

		every { clockSync.calculateDelayMs(any()) } returns 250L
		integration.executeRemoteCommand(
			SendCommand(
				groupId = groupId,
				playlistItemId = UUID.randomUUID(),
				`when` = LocalDateTime.now(ZoneOffset.UTC),
				positionTicks = 12000L * SyncPlayPlaybackIntegration.TICKS_PER_MS,
				command = SendCommandType.UNPAUSE,
				emittedAt = LocalDateTime.now(ZoneOffset.UTC),
			),
		)

		delay(60L)
		nowMs += 60L

		// Now PAUSE arrives immediately and supersedes delayed UNPAUSE
		every { clockSync.calculateDelayMs(any()) } returns 0L
		integration.executeRemoteCommand(
			SendCommand(
				groupId = groupId,
				playlistItemId = UUID.randomUUID(),
				`when` = LocalDateTime.now(ZoneOffset.UTC),
				positionTicks = 12000L * SyncPlayPlaybackIntegration.TICKS_PER_MS,
				command = SendCommandType.PAUSE,
				emittedAt = LocalDateTime.now(ZoneOffset.UTC),
			),
		)

		delay(350L)

		verify(atLeast = 1) { controller.pauseRemote() }
		verify(exactly = 0) { controller.playRemote(any()) }
		scope.cancel()
	}

	test("Remote SEEK keeps autoPlay=false so paused group stays paused after seek") {
		val groupId = UUID.randomUUID()
		val manager = createMockManager(groupId, state = GroupStateType.PAUSED, isPlayingQueue = false)
		val clockSync = mockk<SyncPlayClockSync>(relaxed = true)
		every { clockSync.calculateDelayMs(any()) } returns 0L
		val container = mockk<PlaybackControllerContainer>(relaxed = true)
		val controller = mockk<PlaybackController>(relaxed = true)
		every { container.playbackController } returns controller
		every { container.playbackControllerFlow } returns MutableStateFlow(controller)

		val integration = SyncPlayPlaybackIntegration(
			context = mockk(relaxed = true),
			syncPlayManager = manager,
			clockSync = clockSync,
			playbackControllerContainer = container,
			playbackHelper = mockk(relaxed = true),
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
			mainDispatcher = Dispatchers.Unconfined,
		)

		integration.executeRemoteCommand(
			SendCommand(
				groupId = groupId,
				playlistItemId = UUID.randomUUID(),
				`when` = LocalDateTime.now(ZoneOffset.UTC),
				positionTicks = 45000L * SyncPlayPlaybackIntegration.TICKS_PER_MS,
				command = SendCommandType.SEEK,
				emittedAt = LocalDateTime.now(ZoneOffset.UTC),
			),
		)

		verify(exactly = 1) { controller.seekRemote(45000L, false) }
		verify(exactly = 0) { controller.playRemote(any()) }
	}

	test("Internal burn-in subtitle rebuild suppresses user Stop, StartPlayback, and Buffering") {
		var nowMs = 10_000L
		val groupId = UUID.randomUUID()
		val itemId = UUID.randomUUID()
		val manager = createMockManager(groupId, state = GroupStateType.PLAYING, itemId = itemId, isPlayingQueue = true)
		val clockSync = mockk<SyncPlayClockSync>(relaxed = true)
		every { clockSync.calculateDelayMs(any()) } returns 0L
		val container = mockk<PlaybackControllerContainer>(relaxed = true)
		val controller = mockk<PlaybackController>(relaxed = true)
		every { container.playbackController } returns controller
		every { container.playbackControllerFlow } returns MutableStateFlow(controller)
		every { controller.currentPosition } returns 20000L
		every { controller.isPlaying } returns true

		val integration = SyncPlayPlaybackIntegration(
			context = mockk(relaxed = true),
			syncPlayManager = manager,
			clockSync = clockSync,
			playbackControllerContainer = container,
			playbackHelper = mockk(relaxed = true),
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
			mainDispatcher = Dispatchers.Unconfined,
			elapsedRealtimeMs = { nowMs },
		)

		// Establish anchor via UNPAUSE at 20s when nowMs = 10_000
		integration.executeRemoteCommand(
			SendCommand(
				groupId = groupId,
				playlistItemId = UUID.randomUUID(),
				`when` = LocalDateTime.now(ZoneOffset.UTC),
				positionTicks = 20000L * SyncPlayPlaybackIntegration.TICKS_PER_MS,
				command = SendCommandType.UNPAUSE,
				emittedAt = LocalDateTime.now(ZoneOffset.UTC),
			),
		)

		// Simulate user switching to burned-in Spanish subtitles (takes 4s)
		integration.beginInternalTransition(PlaybackTransitionReason.BURN_IN_REBUILD, wasPlaying = true)
		integration.onUserStop()
		integration.onPlayerBuffering()
		integration.onUserStartPlayback(listOf(itemId), 0, 20000L * SyncPlayPlaybackIntegration.TICKS_PER_MS)

		coVerify(exactly = 0) { manager.sendStop() }
		coVerify(exactly = 0) { manager.sendBuffering(any(), any(), any()) }
		coVerify(exactly = 0) { manager.setNewQueue(any(), any(), any()) }

		// 4 seconds elapsed while transcode rebuilt; rebuilt stream finishes at 20s while group is at 24s
		nowMs += 4000L
		integration.onStreamReady(20000L, true)

		integration.isInternalTransitionActive() shouldBe false
		verify(exactly = 1) { controller.seekRemote(24000L, true) }
	}

	test("Burn-in rebuild while group is PAUSED keeps player paused on ready") {
		val groupId = UUID.randomUUID()
		val manager = createMockManager(groupId, state = GroupStateType.PAUSED, isPlayingQueue = false)
		val container = mockk<PlaybackControllerContainer>(relaxed = true)
		val controller = mockk<PlaybackController>(relaxed = true)
		every { container.playbackController } returns controller
		every { container.playbackControllerFlow } returns MutableStateFlow(controller)
		every { controller.isPlaying } returns true

		val integration = SyncPlayPlaybackIntegration(
			context = mockk(relaxed = true),
			syncPlayManager = manager,
			clockSync = mockk(relaxed = true),
			playbackControllerContainer = container,
			playbackHelper = mockk(relaxed = true),
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
			mainDispatcher = Dispatchers.Unconfined,
		)

		integration.beginInternalTransition(PlaybackTransitionReason.BURN_IN_REBUILD, wasPlaying = false)
		integration.onStreamReady(15000L, true)

		verify(atLeast = 1) { controller.pauseRemote() }
		verify(exactly = 0) { controller.playRemote(any()) }
		coVerify(exactly = 0) { manager.sendReady(any(), any(), any()) }
	}

	test("PlayQueueUpdate switching to next item suppresses old fragment tear-down stop") {
		val groupId = UUID.randomUUID()
		val item1 = UUID.randomUUID()
		val item2 = UUID.randomUUID()
		val playlistFlow = MutableStateFlow<PlayQueueUpdate?>(null)

		val manager = createMockManager(groupId, state = GroupStateType.WAITING, itemId = item1)
		every { manager.playQueue } returns playlistFlow

		val container = mockk<PlaybackControllerContainer>(relaxed = true)
		val controller = mockk<PlaybackController>(relaxed = true)
		val playingDto = mockk<BaseItemDto>(relaxed = true)
		every { playingDto.id } returns item1
		every { controller.currentlyPlayingItem } returns playingDto
		every { container.playbackController } returns controller
		every { container.playbackControllerFlow } returns MutableStateFlow(controller)

		val helper = mockk<PlaybackHelper>(relaxed = true)
		val integration = SyncPlayPlaybackIntegration(
			context = mockk(relaxed = true),
			syncPlayManager = manager,
			clockSync = mockk(relaxed = true),
			playbackControllerContainer = container,
			playbackHelper = helper,
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
			mainDispatcher = Dispatchers.Unconfined,
		)

		playlistFlow.value = PlayQueueUpdate(
			reason = PlayQueueUpdateReason.NEXT_ITEM,
			lastUpdate = LocalDateTime.now(ZoneOffset.UTC),
			playlist = listOf(SyncPlayQueueItem(itemId = item2, playlistItemId = UUID.randomUUID())),
			playingItemIndex = 0,
			startPositionTicks = 0L,
			isPlaying = true,
			shuffleMode = GroupShuffleMode.SORTED,
			repeatMode = GroupRepeatMode.REPEAT_NONE,
		)

		// Old fragment tears down and invokes endPlayback -> onUserStop
		integration.onUserStop()

		coVerify(exactly = 0) { manager.sendStop() }
	}

	test("Drift speed correction stops retrying if audio pipeline ignores speed parameters") {
		var nowMs = 50_000L
		val groupId = UUID.randomUUID()
		val manager = createMockManager(groupId, state = GroupStateType.PLAYING, isPlayingQueue = true)
		val clockSync = mockk<SyncPlayClockSync>(relaxed = true)
		every { clockSync.calculateDelayMs(any()) } returns 0L
		val container = mockk<PlaybackControllerContainer>(relaxed = true)
		val controller = mockk<PlaybackController>(relaxed = true)
		every { container.playbackController } returns controller
		every { container.playbackControllerFlow } returns MutableStateFlow(controller)

		// Simulated passthrough: ExoPlayer keeps reporting 1.0f even after setPlaybackSpeed(0.96f)
		every { controller.playbackSpeed } returns 1.0f
		every { controller.isPlaying } returns true
		every { controller.currentPosition } returns 10_000L

		val integration = SyncPlayPlaybackIntegration(
			context = mockk(relaxed = true),
			syncPlayManager = manager,
			clockSync = clockSync,
			playbackControllerContainer = container,
			playbackHelper = mockk(relaxed = true),
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
			mainDispatcher = Dispatchers.Unconfined,
			elapsedRealtimeMs = { nowMs },
		)

		integration.executeRemoteCommand(
			SendCommand(
				groupId = groupId,
				playlistItemId = UUID.randomUUID(),
				`when` = LocalDateTime.now(ZoneOffset.UTC),
				positionTicks = 10_000L * SyncPlayPlaybackIntegration.TICKS_PER_MS,
				command = SendCommandType.UNPAUSE,
				emittedAt = LocalDateTime.now(ZoneOffset.UTC),
			),
		)

		// Drift = +1200ms after 9s cooldown
		nowMs += 9000L
		every { controller.currentPosition } returns 20_200L
		integration.checkAndCorrectDrift(controller)
		verify(exactly = 1) { controller.setPlaybackSpeed(SyncPlayPlaybackIntegration.SPEED_SLOW_DOWN) }

		// Next tick: detects speed stayed 1.0f -> resets once and disables speed nudging
		nowMs += 3000L
		every { controller.currentPosition } returns 23_200L
		integration.checkAndCorrectDrift(controller)
		verify(atLeast = 1) { controller.setPlaybackSpeed(SyncPlayPlaybackIntegration.SPEED_NORMAL) }

		// Third tick: does NOT call setPlaybackSpeed(0.96f) again
		nowMs += 3000L
		every { controller.currentPosition } returns 26_200L
		integration.checkAndCorrectDrift(controller)
		verify(exactly = 1) { controller.setPlaybackSpeed(SyncPlayPlaybackIntegration.SPEED_SLOW_DOWN) }
	}
})
