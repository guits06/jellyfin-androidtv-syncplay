package org.jellyfin.androidtv.syncplay

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.api.operations.SyncPlayApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BufferRequestDto
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupRepeatMode
import org.jellyfin.sdk.model.api.GroupShuffleMode
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.IgnoreWaitRequestDto
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import org.jellyfin.sdk.model.api.NextItemRequestDto
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.PlayRequestDto
import org.jellyfin.sdk.model.api.PreviousItemRequestDto
import org.jellyfin.sdk.model.api.ReadyRequestDto
import org.jellyfin.sdk.model.api.SeekRequestDto
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SetPlaylistItemRequestDto
import org.jellyfin.sdk.model.api.SetRepeatModeRequestDto
import org.jellyfin.sdk.model.api.SetShuffleModeRequestDto
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupDoesNotExistUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupLeftUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.api.SyncPlayLibraryAccessDeniedUpdate
import org.jellyfin.sdk.model.api.SyncPlayNotInGroupUpdate
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate
import org.jellyfin.sdk.model.api.SyncPlayStateUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserLeftUpdate
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Suppress("LongParameterList", "TooManyFunctions")
class SyncPlayManager(
	private val api: ApiClient,
	private val clockSync: SyncPlayClockSync,
	private val sessionRepository: SessionRepository,
	private val userRepository: UserRepository,
	private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
	private val syncPlayApi: SyncPlayApi? = null,
) {
	private val effectiveSyncPlayApi: SyncPlayApi get() = syncPlayApi ?: api.syncPlayApi

	private val _currentGroup = MutableStateFlow<GroupInfoDto?>(null)
	val currentGroup: StateFlow<GroupInfoDto?> = _currentGroup.asStateFlow()

	private val _playQueue = MutableStateFlow<PlayQueueUpdate?>(null)
	val playQueue: StateFlow<PlayQueueUpdate?> = _playQueue.asStateFlow()

	private val _groupState = MutableStateFlow(GroupStateType.IDLE)
	val groupState: StateFlow<GroupStateType> = _groupState.asStateFlow()

	val isInGroup: StateFlow<Boolean> = _currentGroup
		.map { it != null }
		.stateIn(coroutineScope, SharingStarted.Eagerly, false)

	private val _commands = MutableSharedFlow<SendCommand>(extraBufferCapacity = 64)
	val commands: SharedFlow<SendCommand> = _commands.asSharedFlow()

	@Volatile
	var isHandlingRemoteCommand: Boolean = false

	init {
		subscribeWebSocket()
		observeSession()
	}

	private fun subscribeWebSocket() {
		api.webSocket.subscribe<SyncPlayCommandMessage>()
			.onEach { message ->
				val command = message.data ?: return@onEach
				Timber.i("SyncPlayManager: Received command ${command.command} for group ${command.groupId}")
				_commands.emit(command)
			}
			.launchIn(coroutineScope)

		api.webSocket.subscribe<SyncPlayGroupUpdateMessage>()
			.onEach { message ->
				handleGroupUpdate(message.data)
			}
			.launchIn(coroutineScope)

		api.webSocket.state
			.onEach { state ->
				if (state is SocketApiState.Connected) {
					if (isInGroup.value) {
						Timber.i("SyncPlayManager: WebSocket reconnected while in group. Resyncing...")
						coroutineScope.launch {
							clockSync.synchronizeTime()
							val groupId = _currentGroup.value?.groupId ?: return@launch
							getGroup(groupId).onFailure {
								Timber.w("SyncPlayManager: Group no longer accessible after reconnect")
								clearGroupState()
							}
						}
					} else {
						coroutineScope.launch {
							checkActiveGroup()
						}
					}
				}
			}
			.launchIn(coroutineScope)
	}

	private fun observeSession() {
		sessionRepository.currentSession
			.onEach { session ->
				if (session == null) {
					if (isInGroup.value) {
						Timber.i("SyncPlayManager: User logged out/session ended. Leaving group.")
						clearGroupState()
					}
				} else {
					checkActiveGroup()
				}
			}
			.launchIn(coroutineScope)

		userRepository.currentUser
			.onEach { user ->
				if (user != null && !isInGroup.value) {
					checkActiveGroup()
				}
			}
			.launchIn(coroutineScope)
	}

	suspend fun checkActiveGroup(): Result<GroupInfoDto?> = withContext(ioDispatcher) {
		runCatching {
			val currentUser = userRepository.currentUser.value
			val userName = currentUser?.name
			val groups = effectiveSyncPlayApi.syncPlayGetGroups().content
			val activeGroup = if (userName != null) {
				groups.firstOrNull { group -> group.participants.contains(userName) }
			} else null

			if (activeGroup != null) {
				Timber.i("SyncPlayManager: Found active group ${activeGroup.groupName} (${activeGroup.groupId}) for user $userName")
				_currentGroup.value = activeGroup
				_groupState.value = activeGroup.state
				clockSync.synchronizeTime()
			}
			activeGroup
		}.onFailure {
			Timber.d(it, "Failed to check active group")
		}
	}

	@Suppress("CyclomaticComplexMethod", "ComplexCondition")
	fun handleGroupUpdate(update: org.jellyfin.sdk.model.api.GroupUpdate) {
		Timber.d("SyncPlayManager: Handling update type=${update.type}")
		if (_currentGroup.value == null &&
			update !is SyncPlayGroupLeftUpdate &&
			update !is SyncPlayNotInGroupUpdate &&
			update !is SyncPlayGroupDoesNotExistUpdate &&
			update !is SyncPlayLibraryAccessDeniedUpdate
		) {
			coroutineScope.launch {
				getGroup(update.groupId)
			}
		}

		when (update) {
			is SyncPlayGroupJoinedUpdate -> {
				_currentGroup.value = update.data
				_groupState.value = update.data.state
				Timber.i("SyncPlayManager: Joined group ${update.data.groupName} (${update.data.groupId})")
				coroutineScope.launch {
					clockSync.synchronizeTime()
				}
			}

			is SyncPlayGroupLeftUpdate -> {
				Timber.i("SyncPlayManager: Left group ${update.groupId}")
				clearGroupState()
			}

			is SyncPlayStateUpdate -> {
				_groupState.value = update.data.state
				Timber.i("SyncPlayManager: Group state updated to ${update.data.state}")
			}

			is SyncPlayPlayQueueUpdate -> {
				_playQueue.value = update.data
				Timber.i("SyncPlayManager: PlayQueue updated with ${update.data.playlist.size} items, playingIndex=${update.data.playingItemIndex}")
			}

			is SyncPlayUserJoinedUpdate -> {
				val group = _currentGroup.value
				if (group != null && !group.participants.contains(update.data)) {
					val updatedParticipants = group.participants + update.data
					_currentGroup.value = group.copy(participants = updatedParticipants)
				}
				Timber.i("SyncPlayManager: User ${update.data} joined group")
			}

			is SyncPlayUserLeftUpdate -> {
				val group = _currentGroup.value
				if (group != null) {
					val updatedParticipants = group.participants.filter { it != update.data }
					_currentGroup.value = group.copy(participants = updatedParticipants)
				}
				Timber.i("SyncPlayManager: User ${update.data} left group")
			}

			is SyncPlayNotInGroupUpdate,
			is SyncPlayGroupDoesNotExistUpdate,
			is SyncPlayLibraryAccessDeniedUpdate -> {
				Timber.w("SyncPlayManager: Received group exit error: ${update::class.simpleName}")
				clearGroupState()
			}
		}
	}

	fun clearGroupState() {
		_currentGroup.value = null
		_playQueue.value = null
		_groupState.value = GroupStateType.IDLE
	}

	private fun getCurrentServerDateTime(): LocalDateTime {
		val serverEpochMs = clockSync.localToServerEpochMs(System.currentTimeMillis())
		return clockSync.epochMilliToLocalDateTime(serverEpochMs)
	}

	suspend fun getGroups(): Result<List<GroupInfoDto>> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlayGetGroups().content
		}.onFailure { Timber.e(it, "Failed to get SyncPlay groups") }
	}

	suspend fun getGroup(groupId: UUID): Result<GroupInfoDto> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlayGetGroup(groupId).content
		}.onSuccess { groupInfo ->
			_currentGroup.value = groupInfo
			_groupState.value = groupInfo.state
		}.onFailure { Timber.e(it, "Failed to get group $groupId") }
	}

	suspend fun createGroup(name: String): Result<GroupInfoDto> = withContext(ioDispatcher) {
		runCatching {
			val response = effectiveSyncPlayApi.syncPlayCreateGroup(NewGroupRequestDto(groupName = name.trim()))
			val group = response.content
			_currentGroup.value = group
			_groupState.value = group.state
			clockSync.synchronizeTime()
			group
		}.onFailure { Timber.e(it, "Failed to create group $name") }
	}

	suspend fun joinGroup(groupId: UUID): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlayJoinGroup(JoinGroupRequestDto(groupId = groupId))
			clockSync.synchronizeTime()
			getGroup(groupId)
			Unit
		}.onFailure { Timber.e(it, "Failed to join group $groupId") }
	}

	suspend fun leaveGroup(): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlayLeaveGroup()
			clearGroupState()
			Unit
		}.onFailure {
			Timber.e(it, "Failed to leave group")
			clearGroupState()
		}
	}

	suspend fun sendPause(): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlayPause()
			Unit
		}.onFailure { Timber.e(it, "Failed to send SyncPlay Pause") }
	}

	suspend fun sendUnpause(): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlayUnpause()
			Unit
		}.onFailure { Timber.e(it, "Failed to send SyncPlay Unpause") }
	}

	suspend fun sendSeek(positionTicks: Long): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlaySeek(SeekRequestDto(positionTicks = positionTicks))
			Unit
		}.onFailure { Timber.e(it, "Failed to send SyncPlay Seek") }
	}

	suspend fun sendStop(): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlayStop()
			Unit
		}.onFailure { Timber.e(it, "Failed to send SyncPlay Stop") }
	}

	suspend fun sendBuffering(
		positionTicks: Long,
		isPlaying: Boolean,
		playlistItemId: UUID,
	): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			val now = getCurrentServerDateTime()
			effectiveSyncPlayApi.syncPlayBuffering(
				BufferRequestDto(
					`when` = now,
					positionTicks = positionTicks,
					isPlaying = isPlaying,
					playlistItemId = playlistItemId,
				)
			)
			Unit
		}.onFailure { Timber.e(it, "Failed to send Buffering") }
	}

	suspend fun sendReady(
		positionTicks: Long,
		isPlaying: Boolean,
		playlistItemId: UUID,
	): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			val now = getCurrentServerDateTime()
			effectiveSyncPlayApi.syncPlayReady(
				ReadyRequestDto(
					`when` = now,
					positionTicks = positionTicks,
					isPlaying = isPlaying,
					playlistItemId = playlistItemId,
				)
			)
			Unit
		}.onFailure { Timber.e(it, "Failed to send Ready") }
	}

	suspend fun setNewQueue(
		itemIds: List<UUID>,
		startIndex: Int,
		startPositionTicks: Long,
	): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlaySetNewQueue(
				PlayRequestDto(
					playingQueue = itemIds,
					playingItemPosition = startIndex,
					startPositionTicks = startPositionTicks,
				)
			)
			Unit
		}.onFailure { Timber.e(it, "Failed to set new SyncPlay queue") }
	}

	suspend fun setPlaylistItem(playlistItemId: UUID): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlaySetPlaylistItem(SetPlaylistItemRequestDto(playlistItemId = playlistItemId))
			Unit
		}.onFailure { Timber.e(it, "Failed to set playlist item $playlistItemId") }
	}

	suspend fun nextItem(playlistItemId: UUID): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlayNextItem(NextItemRequestDto(playlistItemId = playlistItemId))
			Unit
		}.onFailure { Timber.e(it, "Failed to request next item") }
	}

	suspend fun previousItem(playlistItemId: UUID): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlayPreviousItem(PreviousItemRequestDto(playlistItemId = playlistItemId))
			Unit
		}.onFailure { Timber.e(it, "Failed to request previous item") }
	}

	suspend fun setRepeatMode(mode: GroupRepeatMode): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlaySetRepeatMode(SetRepeatModeRequestDto(mode = mode))
			Unit
		}.onFailure { Timber.e(it, "Failed to set repeat mode $mode") }
	}

	suspend fun setShuffleMode(mode: GroupShuffleMode): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlaySetShuffleMode(SetShuffleModeRequestDto(mode = mode))
			Unit
		}.onFailure { Timber.e(it, "Failed to set shuffle mode $mode") }
	}

	suspend fun setIgnoreWait(ignore: Boolean): Result<Unit> = withContext(ioDispatcher) {
		runCatching {
			effectiveSyncPlayApi.syncPlaySetIgnoreWait(IgnoreWaitRequestDto(ignoreWait = ignore))
			Unit
		}.onFailure { Timber.e(it, "Failed to set ignore wait $ignore") }
	}
}

