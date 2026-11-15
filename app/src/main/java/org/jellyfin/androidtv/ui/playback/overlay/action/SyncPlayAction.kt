package org.jellyfin.androidtv.ui.playback.overlay.action

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.syncplay.SyncPlayManager
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.koin.java.KoinJavaComponent

class SyncPlayAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	private val syncPlayManager: SyncPlayManager = KoinJavaComponent.get(SyncPlayManager::class.java)
	private val coroutineScope = CoroutineScope(Dispatchers.Main)
	private var popup: PopupMenu? = null
	private var dialog: AlertDialog? = null

	init {
		initializeWithIcon(R.drawable.ic_syncplay)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		videoPlayerAdapter.leanbackOverlayFragment.setFading(false)
		dismissPopup()

		val currentGroup = syncPlayManager.currentGroup.value
		if (currentGroup != null) {
			showInGroupMenu(context, view, videoPlayerAdapter, currentGroup)
		} else {
			showNotInGroupMenu(context, view, videoPlayerAdapter, playbackController)
		}
	}

	private fun showInGroupMenu(
		context: Context,
		view: View,
		videoPlayerAdapter: VideoPlayerAdapter,
		group: GroupInfoDto,
	) {
		popup = PopupMenu(context, view, Gravity.END).apply {
			val groupTitle = context.getString(R.string.syncplay_in_group, group.groupName)
			menu.add(0, MENU_GROUP_INFO, 0, groupTitle).isEnabled = false

			val participantsText = context.getString(
				R.string.syncplay_participants,
				group.participants.size,
				group.participants.joinToString(", ")
			)
			menu.add(0, MENU_PARTICIPANTS, 1, participantsText).isEnabled = false

			menu.add(0, MENU_LEAVE_GROUP, 2, context.getString(R.string.syncplay_leave_group))

			setOnDismissListener {
				videoPlayerAdapter.leanbackOverlayFragment.setFading(true)
				popup = null
			}

			setOnMenuItemClickListener { menuItem ->
				when (menuItem.itemId) {
					MENU_LEAVE_GROUP -> {
						coroutineScope.launch {
							syncPlayManager.leaveGroup()
							Utils.showToast(context, R.string.syncplay_group_left)
						}
						dismiss()
						true
					}
					else -> false
				}
			}
		}
		popup?.show()
	}

	private fun showNotInGroupMenu(
		context: Context,
		view: View,
		videoPlayerAdapter: VideoPlayerAdapter,
		playbackController: PlaybackController,
	) {
		coroutineScope.launch {
			val groupsResult = syncPlayManager.getGroups()
			val groups = groupsResult.getOrNull().orEmpty()

			popup = PopupMenu(context, view, Gravity.END).apply {
				menu.add(0, MENU_CREATE_GROUP, 0, context.getString(R.string.syncplay_create_group))

				if (groups.isNotEmpty()) {
					groups.forEachIndexed { index, group ->
						val label = "${group.groupName} (${group.participants.size})"
						menu.add(1, index, index + 10, label)
					}
				} else {
					menu.add(0, MENU_NO_GROUPS, 1, context.getString(R.string.syncplay_no_groups)).isEnabled = false
				}

				setOnDismissListener {
					videoPlayerAdapter.leanbackOverlayFragment.setFading(true)
					popup = null
				}

				setOnMenuItemClickListener { menuItem ->
					if (menuItem.itemId == MENU_CREATE_GROUP) {
						showCreateGroupDialog(context, playbackController)
						dismiss()
						true
					} else if (menuItem.groupId == 1) {
						val selectedGroup = groups.getOrNull(menuItem.itemId)
						if (selectedGroup != null) {
							coroutineScope.launch {
								val result = syncPlayManager.joinGroup(selectedGroup.groupId)
								if (result.isSuccess) {
									Utils.showToast(
										context,
										context.getString(R.string.syncplay_group_joined, selectedGroup.groupName)
									)
								} else {
									Utils.showToast(context, R.string.syncplay_error_join)
								}
							}
						}
						dismiss()
						true
					} else {
						false
					}
				}
			}
			popup?.show()
		}
	}

	private fun showCreateGroupDialog(context: Context, playbackController: PlaybackController) {
		val input = EditText(context).apply {
			hint = context.getString(R.string.syncplay_group_name)
			isSingleLine = true
		}

		dialog = AlertDialog.Builder(context)
			.setTitle(R.string.syncplay_create_group)
			.setView(input)
			.setPositiveButton(R.string.syncplay_create_group) { _, _ ->
				val groupName = input.text.toString().trim()
				if (groupName.isNotEmpty()) {
					coroutineScope.launch {
						val result = syncPlayManager.createGroup(groupName)
						if (result.isSuccess) {
							val group = result.getOrThrow()
							Utils.showToast(
								context,
								context.getString(R.string.syncplay_group_joined, group.groupName)
							)
							val currentItem = playbackController.currentlyPlayingItem
							if (currentItem != null) {
								syncPlayManager.setNewQueue(
									listOf(currentItem.id),
									0,
									playbackController.currentPosition * 10000L
								)
							}
						} else {
							Utils.showToast(context, R.string.syncplay_error_create)
						}
					}
				}
			}
			.setNegativeButton(android.R.string.cancel, null)
			.setOnDismissListener { dialog = null }
			.create()
		dialog?.show()
	}

	fun dismissPopup() {
		popup?.dismiss()
		popup = null
		dialog?.dismiss()
		dialog = null
	}

	companion object {
		private const val MENU_GROUP_INFO = 1001
		private const val MENU_PARTICIPANTS = 1002
		private const val MENU_LEAVE_GROUP = 1003
		private const val MENU_CREATE_GROUP = 1004
		private const val MENU_NO_GROUPS = 1005
	}
}

