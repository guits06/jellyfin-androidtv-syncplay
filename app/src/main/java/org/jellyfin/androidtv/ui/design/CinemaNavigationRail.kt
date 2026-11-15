package org.jellyfin.androidtv.ui.design

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.syncplay.SyncPlayManager
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.sdk.model.api.GroupStateType
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinActivityViewModel

enum class CinemaNavDestination {
	HOME,
	SEARCH,
	LIBRARIES,
}

@Composable
fun CinemaNavigationRail(
	activeDestination: CinemaNavDestination,
	modifier: Modifier = Modifier,
) {
	val navigationRepository = koinInject<NavigationRepository>()
	val settingsViewModel = koinActivityViewModel<SettingsViewModel>()
	val syncPlayManager = koinInject<SyncPlayManager>()
	val mediaManager = koinInject<MediaManager>()
	val sessionRepository = koinInject<SessionRepository>()
	val activity = LocalActivity.current

	val currentGroup by syncPlayManager.currentGroup.collectAsState()
	val groupState by syncPlayManager.groupState.collectAsState()
	val palette = CinemaTheme.palette

	var railHasFocus by remember { mutableStateOf(false) }
	val railWidth = if (railHasFocus) 204.dp else CinemaSpacing.RailCollapsedWidth
	val railBg = if (railHasFocus) Color(0xED0D0E14) else Color(0xA60A0A0F)

	Column(
		verticalArrangement = Arrangement.SpaceBetween,
		horizontalAlignment = Alignment.Start,
		modifier = modifier
			.width(railWidth)
			.fillMaxHeight()
			.background(railBg)
			.padding(vertical = 28.dp, horizontal = 12.dp)
			.onFocusChanged { railHasFocus = it.hasFocus }
			.focusGroup(),
	) {
		// Brand emblem
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			modifier = Modifier.padding(horizontal = 8.dp),
		) {
			Box(
				modifier = Modifier
					.size(30.dp)
					.clip(RoundedCornerShape(9.dp))
					.background(palette.accentPrimary),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = "J",
					style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
					color = Color.White,
				)
			}
			if (railHasFocus) {
				Text(
					text = "Jellyfin",
					style = MaterialTheme.typography.titleLarge,
					color = palette.textPrimary,
					maxLines = 1,
				)
			}
		}

		// Primary destinations
		Column(
			verticalArrangement = Arrangement.spacedBy(10.dp),
			modifier = Modifier.fillMaxWidth(),
		) {
			RailItem(
				icon = Icons.Rounded.Home,
				label = stringResource(R.string.nav_home),
				selected = activeDestination == CinemaNavDestination.HOME,
				expanded = railHasFocus,
				onClick = {
					if (activeDestination != CinemaNavDestination.HOME) {
						navigationRepository.navigate(Destinations.home, replace = true)
					}
				},
			)

			RailItem(
				icon = Icons.Rounded.Search,
				label = stringResource(R.string.nav_search),
				selected = activeDestination == CinemaNavDestination.SEARCH,
				expanded = railHasFocus,
				onClick = {
					if (activeDestination != CinemaNavDestination.SEARCH) {
						navigationRepository.navigate(Destinations.search())
					}
				},
			)

			val syncDotColor = when {
				currentGroup == null -> null
				groupState == GroupStateType.PLAYING -> palette.syncPlayActive
				groupState == GroupStateType.WAITING -> palette.syncPlayWaiting
				else -> palette.accentSecondary
			}
			val syncLabel = currentGroup?.let { "${it.groupName} (${it.participants.size})" }
				?: stringResource(R.string.nav_syncplay)

			RailItem(
				icon = Icons.Rounded.Sync,
				label = syncLabel,
				selected = currentGroup != null,
				expanded = railHasFocus,
				indicatorDot = syncDotColor,
				onClick = { settingsViewModel.show(Routes.PLAYBACK_SYNCPLAY) },
			)

			RailItem(
				icon = Icons.Rounded.Settings,
				label = stringResource(R.string.nav_settings),
				selected = false,
				expanded = railHasFocus,
				onClick = { settingsViewModel.show(Routes.MAIN) },
			)
		}

		// User switch at bottom
		RailItem(
			icon = Icons.Rounded.Person,
			label = stringResource(R.string.nav_switch_user),
			selected = false,
			expanded = railHasFocus,
			onClick = {
				mediaManager.clearAudioQueue()
				sessionRepository.destroyCurrentSession()
				activity?.startActivity(ActivityDestinations.startup(activity))
				activity?.finishAfterTransition()
			},
		)
	}
}

@Composable
private fun RailItem(
	icon: ImageVector,
	label: String,
	selected: Boolean,
	expanded: Boolean,
	onClick: () -> Unit,
	indicatorDot: Color? = null,
) {
	val palette = CinemaTheme.palette
	var isFocused by remember { mutableStateOf(false) }

	Surface(
		onClick = onClick,
		scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f, pressedScale = 0.98f),
		shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(14.dp)),
		colors = ClickableSurfaceDefaults.colors(
			containerColor = if (selected) palette.surfaceElevated else Color.Transparent,
			focusedContainerColor = palette.textPrimary,
			contentColor = if (selected) palette.textPrimary else palette.textSecondary,
			focusedContentColor = palette.canvas,
		),
		border = ClickableSurfaceDefaults.border(
			border = Border.None,
			focusedBorder = Border(BorderStroke(2.dp, palette.accentPrimary)),
		),
		modifier = Modifier
			.fillMaxWidth()
			.height(46.dp)
			.onFocusChanged { isFocused = it.isFocused },
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			modifier = Modifier
				.fillMaxSize()
				.padding(horizontal = 11.dp),
		) {
			Box {
				Icon(
					imageVector = icon,
					contentDescription = label,
					tint = when {
						isFocused -> palette.canvas
						indicatorDot != null -> indicatorDot
						selected -> palette.accentPrimary
						else -> palette.textSecondary
					},
					modifier = Modifier.size(22.dp),
				)
				if (indicatorDot != null) {
					Box(
						modifier = Modifier
							.align(Alignment.TopEnd)
							.size(7.dp)
							.clip(CircleShape)
							.background(indicatorDot)
					)
				}
			}

			if (expanded) {
				Text(
					text = label,
					style = MaterialTheme.typography.titleMedium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Spacer(modifier = Modifier.weight(1f))
			}
		}
	}
}
