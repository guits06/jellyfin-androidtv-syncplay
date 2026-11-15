package org.jellyfin.androidtv.syncplay

import android.content.Context
import io.kotest.core.spec.style.FunSpec
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class SyncPlayLoopPreventionTests : FunSpec({
	test("User playback actions are ignored when isHandlingRemoteCommand is true") {
		val mockContext = mockk<Context>(relaxed = true)
		val mockManager = mockk<SyncPlayManager>(relaxed = true)
		val mockClockSync = mockk<SyncPlayClockSync>(relaxed = true)
		val mockContainer = mockk<PlaybackControllerContainer>(relaxed = true)
		val mockHelper = mockk<PlaybackHelper>(relaxed = true)

		val inGroupFlow = MutableStateFlow(true)
		every { mockManager.isInGroup } returns inGroupFlow
		every { mockManager.isHandlingRemoteCommand } returns true

		val integration = SyncPlayPlaybackIntegration(
			context = mockContext,
			syncPlayManager = mockManager,
			clockSync = mockClockSync,
			playbackControllerContainer = mockContainer,
			playbackHelper = mockHelper,
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
		)

		integration.onUserPause()
		integration.onUserPlay()
		integration.onUserSeek(5000L)
		integration.onUserStop()
		integration.onUserNext()
		integration.onUserPrevious()

		coVerify(exactly = 0) { mockManager.sendPause() }
		coVerify(exactly = 0) { mockManager.sendUnpause() }
		coVerify(exactly = 0) { mockManager.sendSeek(any()) }
		coVerify(exactly = 0) { mockManager.sendStop() }
		coVerify(exactly = 0) { mockManager.nextItem(any()) }
		coVerify(exactly = 0) { mockManager.previousItem(any()) }
	}

	test("User playback actions are dispatched when isHandlingRemoteCommand is false") {
		val mockContext = mockk<Context>(relaxed = true)
		val mockManager = mockk<SyncPlayManager>(relaxed = true)
		val mockClockSync = mockk<SyncPlayClockSync>(relaxed = true)
		val mockContainer = mockk<PlaybackControllerContainer>(relaxed = true)
		val mockHelper = mockk<PlaybackHelper>(relaxed = true)

		val inGroupFlow = MutableStateFlow(true)
		every { mockManager.isInGroup } returns inGroupFlow
		every { mockManager.isHandlingRemoteCommand } returns false

		val integration = SyncPlayPlaybackIntegration(
			context = mockContext,
			syncPlayManager = mockManager,
			clockSync = mockClockSync,
			playbackControllerContainer = mockContainer,
			playbackHelper = mockHelper,
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
		)

		integration.onUserPause()
		integration.onUserPlay()
		integration.onUserSeek(10000L)
		integration.onUserStop()

		coVerify(exactly = 1) { mockManager.sendPause() }
		coVerify(exactly = 1) { mockManager.sendUnpause() }
		coVerify(exactly = 1) { mockManager.sendSeek(10000L * SyncPlayPlaybackIntegration.TICKS_PER_MS) }
		coVerify(exactly = 1) { mockManager.sendStop() }
	}

	test("Remote command execution manages isHandlingRemoteCommand flag lifecycle") {
		val mockContext = mockk<Context>(relaxed = true)
		val mockManager = mockk<SyncPlayManager>(relaxed = true)
		val mockClockSync = mockk<SyncPlayClockSync>(relaxed = true)
		val mockContainer = mockk<PlaybackControllerContainer>(relaxed = true)
		val mockController = mockk<PlaybackController>(relaxed = true)
		val mockHelper = mockk<PlaybackHelper>(relaxed = true)

		val groupId = UUID.randomUUID()
		val groupInfo = GroupInfoDto(
			groupId = groupId,
			groupName = "Test Group",
			state = GroupStateType.PLAYING,
			participants = listOf("User"),
			lastUpdatedAt = LocalDateTime.now(ZoneOffset.UTC),
		)
		every { mockManager.currentGroup } returns MutableStateFlow(groupInfo)
		every { mockContainer.playbackController } returns mockController
		every { mockClockSync.calculateDelayMs(any()) } returns 0L

		val integration = SyncPlayPlaybackIntegration(
			context = mockContext,
			syncPlayManager = mockManager,
			clockSync = mockClockSync,
			playbackControllerContainer = mockContainer,
			playbackHelper = mockHelper,
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
			mainDispatcher = Dispatchers.Unconfined,
		)

		val command = SendCommand(
			groupId = groupId,
			playlistItemId = UUID.randomUUID(),
			`when` = LocalDateTime.now(ZoneOffset.UTC),
			positionTicks = 0L,
			command = SendCommandType.PAUSE,
			emittedAt = LocalDateTime.now(ZoneOffset.UTC),
		)

		integration.executeRemoteCommand(command)

		io.mockk.verify { mockController.pauseRemote() }
		io.mockk.verify { mockManager.isHandlingRemoteCommand = true }
		io.mockk.verify { mockManager.isHandlingRemoteCommand = false }
	}

	test("onUserStartPlayback calls setNewQueue only when item differs from current queue") {
		val mockContext = mockk<Context>(relaxed = true)
		val mockManager = mockk<SyncPlayManager>(relaxed = true)
		val mockClockSync = mockk<SyncPlayClockSync>(relaxed = true)
		val mockContainer = mockk<PlaybackControllerContainer>(relaxed = true)
		val mockHelper = mockk<PlaybackHelper>(relaxed = true)

		val existingItemId = UUID.randomUUID()
		val newItemId = UUID.randomUUID()

		val currentQueue = org.jellyfin.sdk.model.api.PlayQueueUpdate(
			reason = org.jellyfin.sdk.model.api.PlayQueueUpdateReason.SET_CURRENT_ITEM,
			lastUpdate = LocalDateTime.now(ZoneOffset.UTC),
			playlist = listOf(
				org.jellyfin.sdk.model.api.SyncPlayQueueItem(
					itemId = existingItemId,
					playlistItemId = UUID.randomUUID(),
				),
			),
			playingItemIndex = 0,
			startPositionTicks = 0L,
			isPlaying = true,
			shuffleMode = org.jellyfin.sdk.model.api.GroupShuffleMode.SORTED,
			repeatMode = org.jellyfin.sdk.model.api.GroupRepeatMode.REPEAT_NONE,
		)

		every { mockManager.isInGroup } returns MutableStateFlow(true)
		every { mockManager.playQueue } returns MutableStateFlow(currentQueue)
		every { mockManager.isHandlingRemoteCommand } returns false

		val integration = SyncPlayPlaybackIntegration(
			context = mockContext,
			syncPlayManager = mockManager,
			clockSync = mockClockSync,
			playbackControllerContainer = mockContainer,
			playbackHelper = mockHelper,
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
			mainDispatcher = Dispatchers.Unconfined,
		)

		// 1. Starting the same item should be ignored (avoid loop)
		integration.onUserStartPlayback(listOf(existingItemId), 0, 0L)
		coVerify(exactly = 0) { mockManager.setNewQueue(any(), any(), any()) }

		// 2. Starting a different item should trigger setNewQueue
		integration.onUserStartPlayback(listOf(newItemId), 0, 0L)
		coVerify(exactly = 1) { mockManager.setNewQueue(listOf(newItemId), 0, 0L) }
	}

	test("executeRemoteCommand attempts to fetch group if currentGroup is null") {
		val mockContext = mockk<Context>(relaxed = true)
		val mockManager = mockk<SyncPlayManager>(relaxed = true)
		val mockClockSync = mockk<SyncPlayClockSync>(relaxed = true)
		val mockContainer = mockk<PlaybackControllerContainer>(relaxed = true)
		val mockController = mockk<PlaybackController>(relaxed = true)
		val mockHelper = mockk<PlaybackHelper>(relaxed = true)

		val groupId = UUID.randomUUID()
		val groupInfo = GroupInfoDto(
			groupId = groupId,
			groupName = "Recovered Group",
			state = GroupStateType.PLAYING,
			participants = listOf("User"),
			lastUpdatedAt = LocalDateTime.now(ZoneOffset.UTC),
		)

		// Initially null, then recovered after getGroup
		val groupFlow = MutableStateFlow<GroupInfoDto?>(null)
		every { mockManager.currentGroup } returns groupFlow
		io.mockk.coEvery { mockManager.getGroup(groupId) } answers {
			groupFlow.value = groupInfo
			Result.success(groupInfo)
		}
		every { mockContainer.playbackController } returns mockController
		every { mockClockSync.calculateDelayMs(any()) } returns 0L

		val integration = SyncPlayPlaybackIntegration(
			context = mockContext,
			syncPlayManager = mockManager,
			clockSync = mockClockSync,
			playbackControllerContainer = mockContainer,
			playbackHelper = mockHelper,
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
			mainDispatcher = Dispatchers.Unconfined,
		)

		val command = SendCommand(
			groupId = groupId,
			playlistItemId = UUID.randomUUID(),
			`when` = LocalDateTime.now(ZoneOffset.UTC),
			positionTicks = 0L,
			command = SendCommandType.PAUSE,
			emittedAt = LocalDateTime.now(ZoneOffset.UTC),
		)

		integration.executeRemoteCommand(command)

		io.mockk.coVerify(exactly = 1) { mockManager.getGroup(groupId) }
		io.mockk.verify(exactly = 1) { mockController.pauseRemote() }
	}
})
