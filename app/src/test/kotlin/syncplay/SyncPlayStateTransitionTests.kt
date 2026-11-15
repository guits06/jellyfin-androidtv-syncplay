package org.jellyfin.androidtv.syncplay

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.SyncPlayApi
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupRepeatMode
import org.jellyfin.sdk.model.api.GroupShuffleMode
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.GroupStateUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdateReason
import org.jellyfin.sdk.model.api.PlaybackRequestType
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupLeftUpdate
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate
import org.jellyfin.sdk.model.api.SyncPlayStateUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserLeftUpdate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class SyncPlayStateTransitionTests : FunSpec({
	test("GroupJoinedUpdate updates currentGroup, groupState, and isInGroup") {
		val mockApi = mockk<ApiClient>(relaxed = true)
		val mockClockSync = mockk<SyncPlayClockSync>(relaxed = true)
		val mockSessionRepository = mockk<SessionRepository>(relaxed = true)
		val mockUserRepository = mockk<UserRepository>(relaxed = true)

		val manager = SyncPlayManager(
			api = mockApi,
			clockSync = mockClockSync,
			sessionRepository = mockSessionRepository,
			userRepository = mockUserRepository,
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
		)

		manager.isInGroup.value shouldBe false
		manager.currentGroup.value shouldBe null

		val groupId = UUID.randomUUID()
		val groupInfo = GroupInfoDto(
			groupId = groupId,
			groupName = "Movie Night",
			state = GroupStateType.PLAYING,
			participants = listOf("Alice", "Bob"),
			lastUpdatedAt = LocalDateTime.now(ZoneOffset.UTC),
		)

		manager.handleGroupUpdate(SyncPlayGroupJoinedUpdate(groupId = groupId, data = groupInfo))

		manager.isInGroup.value shouldBe true
		manager.currentGroup.value shouldNotBe null
		manager.currentGroup.value?.groupName shouldBe "Movie Night"
		manager.groupState.value shouldBe GroupStateType.PLAYING
	}

	test("GroupLeftUpdate resets group state to idle and clears group") {
		val mockApi = mockk<ApiClient>(relaxed = true)
		val mockClockSync = mockk<SyncPlayClockSync>(relaxed = true)
		val mockSessionRepository = mockk<SessionRepository>(relaxed = true)
		val mockUserRepository = mockk<UserRepository>(relaxed = true)

		val manager = SyncPlayManager(
			api = mockApi,
			clockSync = mockClockSync,
			sessionRepository = mockSessionRepository,
			userRepository = mockUserRepository,
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
		)

		val groupId = UUID.randomUUID()
		val groupInfo = GroupInfoDto(
			groupId = groupId,
			groupName = "Anime Club",
			state = GroupStateType.PLAYING,
			participants = listOf("Alice"),
			lastUpdatedAt = LocalDateTime.now(ZoneOffset.UTC),
		)

		manager.handleGroupUpdate(SyncPlayGroupJoinedUpdate(groupId = groupId, data = groupInfo))
		manager.isInGroup.value shouldBe true

		manager.handleGroupUpdate(SyncPlayGroupLeftUpdate(groupId = groupId, data = "User left"))

		manager.isInGroup.value shouldBe false
		manager.currentGroup.value shouldBe null
		manager.groupState.value shouldBe GroupStateType.IDLE
		manager.playQueue.value shouldBe null
	}

	test("StateUpdate updates groupState without affecting group metadata") {
		val mockApi = mockk<ApiClient>(relaxed = true)
		val mockClockSync = mockk<SyncPlayClockSync>(relaxed = true)
		val mockSessionRepository = mockk<SessionRepository>(relaxed = true)
		val mockUserRepository = mockk<UserRepository>(relaxed = true)

		val manager = SyncPlayManager(
			api = mockApi,
			clockSync = mockClockSync,
			sessionRepository = mockSessionRepository,
			userRepository = mockUserRepository,
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
		)

		val groupId = UUID.randomUUID()
		val groupInfo = GroupInfoDto(
			groupId = groupId,
			groupName = "Watch Party",
			state = GroupStateType.PAUSED,
			participants = listOf("Alice"),
			lastUpdatedAt = LocalDateTime.now(ZoneOffset.UTC),
		)
		manager.handleGroupUpdate(SyncPlayGroupJoinedUpdate(groupId = groupId, data = groupInfo))
		manager.groupState.value shouldBe GroupStateType.PAUSED

		manager.handleGroupUpdate(
			SyncPlayStateUpdate(
				groupId = groupId,
				data = GroupStateUpdate(state = GroupStateType.WAITING, reason = PlaybackRequestType.PAUSE),
			),
		)
		manager.groupState.value shouldBe GroupStateType.WAITING
	}

	test("PlayQueueUpdate updates playQueue") {
		val mockApi = mockk<ApiClient>(relaxed = true)
		val mockClockSync = mockk<SyncPlayClockSync>(relaxed = true)
		val mockSessionRepository = mockk<SessionRepository>(relaxed = true)
		val mockUserRepository = mockk<UserRepository>(relaxed = true)

		val manager = SyncPlayManager(
			api = mockApi,
			clockSync = mockClockSync,
			sessionRepository = mockSessionRepository,
			userRepository = mockUserRepository,
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
		)

		val groupId = UUID.randomUUID()
		val queueUpdate = PlayQueueUpdate(
			reason = PlayQueueUpdateReason.SET_CURRENT_ITEM,
			lastUpdate = LocalDateTime.now(ZoneOffset.UTC),
			playlist = emptyList(),
			playingItemIndex = 2,
			startPositionTicks = 1000000L,
			isPlaying = true,
			shuffleMode = GroupShuffleMode.SORTED,
			repeatMode = GroupRepeatMode.REPEAT_NONE,
		)

		manager.handleGroupUpdate(SyncPlayPlayQueueUpdate(groupId = groupId, data = queueUpdate))
		manager.playQueue.value shouldBe queueUpdate
		manager.playQueue.value?.playingItemIndex shouldBe 2
	}

	test("UserJoinedUpdate and UserLeftUpdate modify participants list") {
		val mockApi = mockk<ApiClient>(relaxed = true)
		val mockClockSync = mockk<SyncPlayClockSync>(relaxed = true)
		val mockSessionRepository = mockk<SessionRepository>(relaxed = true)
		val mockUserRepository = mockk<UserRepository>(relaxed = true)

		val manager = SyncPlayManager(
			api = mockApi,
			clockSync = mockClockSync,
			sessionRepository = mockSessionRepository,
			userRepository = mockUserRepository,
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
		)

		val groupId = UUID.randomUUID()
		val groupInfo = GroupInfoDto(
			groupId = groupId,
			groupName = "Friends",
			state = GroupStateType.PAUSED,
			participants = listOf("Alice"),
			lastUpdatedAt = LocalDateTime.now(ZoneOffset.UTC),
		)
		manager.handleGroupUpdate(SyncPlayGroupJoinedUpdate(groupId = groupId, data = groupInfo))

		manager.handleGroupUpdate(SyncPlayUserJoinedUpdate(groupId = groupId, data = "Bob"))
		val participantsAfterJoin = manager.currentGroup.value?.participants
		participantsAfterJoin?.shouldContain("Bob")
		participantsAfterJoin?.shouldContain("Alice")

		manager.handleGroupUpdate(SyncPlayUserLeftUpdate(groupId = groupId, data = "Alice"))
		val participantsAfterLeave = manager.currentGroup.value?.participants
		participantsAfterLeave?.shouldNotContain("Alice")
		participantsAfterLeave?.shouldContain("Bob")
	}

	test("checkActiveGroup restores group when user is in group participants list") {
		val mockApi = mockk<ApiClient>(relaxed = true)
		val mockSyncPlayApi = mockk<SyncPlayApi>(relaxed = true)
		val mockClockSync = mockk<SyncPlayClockSync>(relaxed = true)
		val mockSessionRepository = mockk<SessionRepository>(relaxed = true)
		val mockUserRepository = mockk<UserRepository>(relaxed = true)

		val userDto = mockk<org.jellyfin.sdk.model.api.UserDto>(relaxed = true)
		io.mockk.every { userDto.name } returns "guille"
		io.mockk.every { mockUserRepository.currentUser } returns kotlinx.coroutines.flow.MutableStateFlow(userDto)

		val groupId = UUID.randomUUID()
		val activeGroup = GroupInfoDto(
			groupId = groupId,
			groupName = "Grupo de laura",
			state = GroupStateType.PLAYING,
			participants = listOf("laura", "guille"),
			lastUpdatedAt = LocalDateTime.now(ZoneOffset.UTC),
		)

		val groupsResponse = org.jellyfin.sdk.api.client.Response(
			content = listOf(activeGroup),
			status = 200,
			headers = emptyMap(),
		)
		io.mockk.coEvery { mockSyncPlayApi.syncPlayGetGroups() } returns groupsResponse

		val manager = SyncPlayManager(
			api = mockApi,
			clockSync = mockClockSync,
			sessionRepository = mockSessionRepository,
			userRepository = mockUserRepository,
			coroutineScope = CoroutineScope(Dispatchers.Unconfined),
			ioDispatcher = Dispatchers.Unconfined,
			syncPlayApi = mockSyncPlayApi,
		)

		manager.checkActiveGroup()
		manager.isInGroup.value shouldBe true
		manager.currentGroup.value?.groupName shouldBe "Grupo de laura"
		manager.currentGroup.value?.groupId shouldBe groupId
		manager.groupState.value shouldBe GroupStateType.PLAYING
		io.mockk.coVerify(atLeast = 1) { mockClockSync.synchronizeTime() }
	}
})
