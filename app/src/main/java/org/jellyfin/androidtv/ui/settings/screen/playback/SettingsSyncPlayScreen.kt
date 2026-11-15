package org.jellyfin.androidtv.ui.settings.screen.playback

import android.app.AlertDialog
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.syncplay.SyncPlayManager
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.koin.compose.koinInject

@Composable
fun SettingsPlaybackSyncPlayScreen() {
	val context = LocalContext.current
	val syncPlayManager = koinInject<SyncPlayManager>()
	val coroutineScope = rememberCoroutineScope()

	val currentGroup by syncPlayManager.currentGroup.collectAsState()
	var availableGroups by remember { mutableStateOf<List<GroupInfoDto>>(emptyList()) }
	var isLoading by remember { mutableStateOf(false) }

	fun refreshGroups() {
		coroutineScope.launch {
			isLoading = true
			val result = syncPlayManager.getGroups()
			availableGroups = result.getOrNull().orEmpty()
			isLoading = false
		}
	}

	LaunchedEffect(Unit) {
		refreshGroups()
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_playback).uppercase()) },
				headingContent = { Text(stringResource(R.string.syncplay_title)) },
				captionContent = { Text(stringResource(R.string.syncplay_description)) },
			)
		}

		val group = currentGroup
		if (group != null) {
			item {
				ListSection(
					headingContent = { Text(stringResource(R.string.syncplay_in_group, group.groupName)) },
					captionContent = {
						Text(
							stringResource(
								R.string.syncplay_participants,
								group.participants.size,
								group.participants.joinToString(", ")
							)
						)
					},
				)
			}

			item {
				ListButton(
					leadingContent = { Icon(painterResource(R.drawable.ic_logout), contentDescription = null) },
					headingContent = { Text(stringResource(R.string.syncplay_leave_group)) },
					onClick = {
						coroutineScope.launch {
							syncPlayManager.leaveGroup()
							Utils.showToast(context, R.string.syncplay_group_left)
							refreshGroups()
						}
					},
					modifier = Modifier.focusKey("syncplay_leave")
				)
			}
		} else {
			item {
				ListButton(
					leadingContent = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
					headingContent = { Text(stringResource(R.string.syncplay_create_group)) },
					onClick = {
						val input = EditText(context).apply {
							hint = context.getString(R.string.syncplay_group_name)
							isSingleLine = true
						}

						AlertDialog.Builder(context)
							.setTitle(R.string.syncplay_create_group)
							.setView(input)
							.setPositiveButton(R.string.syncplay_create_group) { _, _ ->
								val groupName = input.text.toString().trim()
								if (groupName.isNotEmpty()) {
									coroutineScope.launch {
										val result = syncPlayManager.createGroup(groupName)
										if (result.isSuccess) {
											val newGroup = result.getOrThrow()
											Utils.showToast(
												context,
												context.getString(R.string.syncplay_group_joined, newGroup.groupName)
											)
											refreshGroups()
										} else {
											Utils.showToast(context, R.string.syncplay_error_create)
										}
									}
								}
							}
							.setNegativeButton(android.R.string.cancel, null)
							.create()
							.show()
					},
					modifier = Modifier.focusKey("syncplay_create")
				)
			}

			item {
				ListSection(
					headingContent = { Text(stringResource(R.string.syncplay_join_group)) },
				)
			}

			if (availableGroups.isNotEmpty()) {
				items(availableGroups.size) { index ->
					val availableGroup = availableGroups[index]
					ListButton(
						leadingContent = { Icon(painterResource(R.drawable.ic_syncplay), contentDescription = null) },
						headingContent = { Text(availableGroup.groupName) },
						captionContent = {
							Text(
								stringResource(
									R.string.syncplay_members_count,
									availableGroup.participants.size
								)
							)
						},
						onClick = {
							coroutineScope.launch {
								val result = syncPlayManager.joinGroup(availableGroup.groupId)
								if (result.isSuccess) {
									Utils.showToast(
										context,
										context.getString(R.string.syncplay_group_joined, availableGroup.groupName)
									)
									refreshGroups()
								} else {
									Utils.showToast(context, R.string.syncplay_error_join)
								}
							}
						},
						modifier = Modifier.focusKey("syncplay_group_$index")
					)
				}
			} else {
				item {
					ListButton(
						headingContent = { Text(stringResource(R.string.syncplay_no_groups)) },
						enabled = false,
						onClick = {},
					)
				}
			}
		}
	}
}
