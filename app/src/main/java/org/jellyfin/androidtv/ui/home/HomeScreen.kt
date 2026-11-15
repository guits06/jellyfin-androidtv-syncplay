package org.jellyfin.androidtv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.syncplay.SyncPlayManager
import org.jellyfin.androidtv.ui.design.CinemaActionButton
import org.jellyfin.androidtv.ui.design.CinemaAmbientCanvas
import org.jellyfin.androidtv.ui.design.CinemaAsyncImage
import org.jellyfin.androidtv.ui.design.CinemaHeroScrim
import org.jellyfin.androidtv.ui.design.CinemaMediaCard
import org.jellyfin.androidtv.ui.design.CinemaNavDestination
import org.jellyfin.androidtv.ui.design.CinemaNavigationRail
import org.jellyfin.androidtv.ui.design.CinemaSectionRow
import org.jellyfin.androidtv.ui.design.CinemaTheme
import org.jellyfin.androidtv.ui.design.CinemaSpacing
import org.jellyfin.androidtv.ui.design.SyncPlayStatusPill
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.rememberAmbientPalette
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinActivityViewModel
import java.util.Locale

@OptIn(FlowPreview::class)
@Composable
fun HomeScreen(
	viewModel: HomeViewModel = koinViewModel(),
) {
	val uiState by viewModel.uiState.collectAsState()
	val syncPlayManager = koinInject<SyncPlayManager>()
	val itemLauncher = koinInject<ItemLauncher>()
	val navigationRepository = koinInject<NavigationRepository>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val settingsViewModel = koinActivityViewModel<SettingsViewModel>()
	val context = LocalContext.current

	val currentGroup by syncPlayManager.currentGroup.collectAsState()
	val groupState by syncPlayManager.groupState.collectAsState()
	val palette = CinemaTheme.palette

	var pendingFocusHero by remember { mutableStateOf<HomeMediaItem?>(null) }
	var displayedHero by remember { mutableStateOf<HomeMediaItem?>(null) }
	val initialFocusRequester = remember { FocusRequester() }
	val listState = rememberLazyListState()
	val coroutineScope = rememberCoroutineScope()
	val backdropDimmed by remember {
		derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 120 }
	}

	LaunchedEffect(uiState.heroDefault) {
		if (displayedHero == null && uiState.heroDefault != null) {
			displayedHero = uiState.heroDefault
		}
	}

	// Debounce rapid D-pad navigation by 350ms to prevent backdrop image decode churn on low-end TVs
	LaunchedEffect(Unit) {
		snapshotFlow { pendingFocusHero }
			.distinctUntilChanged { old, new -> old?.id == new?.id }
			.debounce(350L)
			.collect { settled ->
				if (settled != null && !settled.backdropUrl.isNullOrBlank()) {
					displayedHero = settled
				}
			}
	}

	LaunchedEffect(uiState.sections.isNotEmpty()) {
		if (uiState.sections.isNotEmpty()) {
			runCatching { initialFocusRequester.requestFocus() }
			listState.scrollToItem(0, 0)
		}
	}

	val hero = displayedHero ?: uiState.heroDefault
	val ambientPalette = rememberAmbientPalette(hero?.backdropUrl ?: hero?.cardImageUrl)

	Box(modifier = Modifier.fillMaxSize()) {
		CinemaAmbientCanvas(
			ambient = ambientPalette,
			modifier = Modifier.fillMaxSize(),
		)

		// Single compact backdrop canvas without offscreen RenderNode alpha layers
		HeroBackdropLayer(
			backdropUrl = hero?.backdropUrl,
			dimmed = backdropDimmed,
			modifier = Modifier
				.fillMaxWidth(0.76f)
				.fillMaxHeight(0.62f)
				.align(Alignment.TopEnd),
		)

		Box(modifier = Modifier.fillMaxSize()) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.padding(start = CinemaSpacing.RailCollapsedWidth)
					.focusGroup()
			) {
				if (uiState.isLoading && uiState.sections.isEmpty()) {
					HomeSkeletonLoading(
						modifier = Modifier
							.fillMaxSize()
							.padding(start = 28.dp, top = 48.dp, end = 48.dp)
					)
				} else {
					LazyColumn(
						state = listState,
						contentPadding = PaddingValues(bottom = 36.dp),
						verticalArrangement = Arrangement.spacedBy(18.dp),
						modifier = Modifier.fillMaxSize(),
					) {
						if (hero != null) {
							item(key = "hero_billboard", contentType = "hero") {
								HeroBillboardContent(
									item = hero,
									initialFocusRequester = initialFocusRequester,
									onHeroFocused = {
										coroutineScope.launch {
											listState.animateScrollToItem(0, 0)
										}
									},
									onPlayClick = {
										val dto = hero.dto
										if (dto.type == BaseItemKind.SERIES) {
											navigationRepository.navigate(Destinations.itemDetails(dto.id))
										} else {
											val startMs = ((dto.userData?.playbackPositionTicks ?: 0L) / 10_000L).toInt()
											playbackHelper.retrieveAndPlay(listOf(dto.id), false, startMs.toLong(), 0, context)
										}
									},
									onDetailsClick = {
										if (hero.dto.type == BaseItemKind.COLLECTION_FOLDER || hero.dto.type == BaseItemKind.USER_VIEW) {
											itemLauncher.launchUserView(hero.dto)
										} else {
											navigationRepository.navigate(Destinations.itemDetails(hero.id))
										}
									},
								)
							}
						}

						items(
							items = uiState.sections,
							key = { it.id },
							contentType = { "section_row" },
						) { section ->
							CinemaSectionRow(
								title = section.title,
								items = section.items,
								key = { item -> "${section.id}_${item.id}" },
							) { item ->
								val cardW = if (section.isLandscape) 244.dp else 148.dp
								val cardH = if (section.isLandscape) 137.dp else 222.dp
								CinemaMediaCard(
									title = item.title,
									subtitle = item.subtitle,
									imageUrl = item.cardImageUrl,
									cardWidth = cardW,
									cardHeight = cardH,
									progressFraction = item.progressFraction,
									isWatched = item.isWatched,
									unplayedCount = item.unplayedCount,
									communityRating = item.communityRating,
									onFocused = {
										if (item.dto.type != BaseItemKind.COLLECTION_FOLDER && item.dto.type != BaseItemKind.USER_VIEW) {
											pendingFocusHero = item
										}
									},
									onClick = {
										itemLauncher.launch(BaseItemDtoBaseRowItem(item.dto), null, context)
									},
								)
							}
						}
					}
				}

				if (currentGroup != null) {
					val group = currentGroup!!
					SyncPlayStatusPill(
						groupName = group.groupName,
						groupState = groupState,
						participantCount = group.participants.size,
						onClick = { settingsViewModel.show(Routes.PLAYBACK_SYNCPLAY) },
						modifier = Modifier
							.align(Alignment.TopEnd)
							.padding(top = 24.dp, end = 36.dp),
					)
				}
			}

			CinemaNavigationRail(
				activeDestination = CinemaNavDestination.HOME,
				modifier = Modifier.align(Alignment.CenterStart),
			)
		}
	}
}

@Composable
private fun HeroBackdropLayer(
	backdropUrl: String?,
	dimmed: Boolean,
	modifier: Modifier = Modifier,
) {
	val palette = CinemaTheme.palette
	Box(modifier = modifier) {
		CinemaAsyncImage(
			url = backdropUrl,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			targetWidthDp = 480.dp,
			targetHeightDp = 270.dp,
			crossfadeMs = 0,
			fallbackColor = palette.canvas,
			modifier = Modifier.fillMaxSize(),
		)

		// Single-pass C-rendered scrim texture over hero backdrop (avoids 3 gradient shaders)
		CinemaHeroScrim(
			dimmed = dimmed,
			modifier = Modifier.fillMaxSize(),
		)
	}
}

@Composable
private fun HeroBillboardContent(
	item: HomeMediaItem,
	initialFocusRequester: FocusRequester,
	onHeroFocused: () -> Unit,
	onPlayClick: () -> Unit,
	onDetailsClick: () -> Unit,
) {
	val palette = CinemaTheme.palette

	Column(
		verticalArrangement = Arrangement.Top,
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 270.dp)
			.padding(start = 28.dp, end = 52.dp, top = 28.dp, bottom = 10.dp),
	) {
		Text(
			text = stringResource(R.string.lbl_featured).uppercase(),
			style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
			color = palette.accentPrimary,
		)

		Spacer(modifier = Modifier.height(6.dp))

		Text(
			text = item.title,
			style = MaterialTheme.typography.displayLarge,
			color = palette.textPrimary,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.fillMaxWidth(0.68f),
		)

		if (!item.subtitle.isNullOrBlank()) {
			Spacer(modifier = Modifier.height(4.dp))
			Text(
				text = item.subtitle,
				style = MaterialTheme.typography.titleLarge,
				color = palette.accentSecondary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}

		Spacer(modifier = Modifier.height(10.dp))

		// Metadata chips row
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			item.communityRating?.takeIf { it > 0f }?.let { rating ->
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(4.dp),
					modifier = Modifier
						.clip(RoundedCornerShape(8.dp))
						.background(Color(0x991E1E2A))
						.padding(horizontal = 8.dp, vertical = 4.dp),
				) {
					Icon(
						imageVector = Icons.Rounded.Star,
						contentDescription = null,
						tint = palette.ratingGold,
						modifier = Modifier.size(14.dp),
					)
					Text(
						text = String.format(Locale.US, "%.1f", rating),
						style = MaterialTheme.typography.labelMedium,
						color = palette.textPrimary,
					)
				}
			}

			item.year?.let { year ->
				MetaBadge(text = year)
			}

			item.runtimeLabel?.let { runtime ->
				MetaBadge(text = runtime)
			}

			item.officialRating?.takeIf { it.isNotBlank() }?.let { pg ->
				MetaBadge(text = pg)
			}
		}

		if (!item.overview.isNullOrBlank()) {
			Spacer(modifier = Modifier.height(10.dp))
			Text(
				text = item.overview,
				style = MaterialTheme.typography.bodyLarge,
				color = palette.textSecondary,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.fillMaxWidth(0.56f),
			)
		}

		Spacer(modifier = Modifier.height(16.dp))

		Row(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			modifier = Modifier.onFocusChanged { if (it.hasFocus) onHeroFocused() },
		) {
			CinemaActionButton(
				text = stringResource(if (item.canResume) R.string.action_resume else R.string.action_play),
				icon = Icons.Rounded.PlayArrow,
				primary = true,
				onClick = onPlayClick,
				modifier = Modifier.focusRequester(initialFocusRequester),
			)

			CinemaActionButton(
				text = stringResource(R.string.action_more_info),
				icon = Icons.Rounded.Info,
				primary = false,
				onClick = onDetailsClick,
			)
		}
	}
}

@Composable
private fun MetaBadge(text: String) {
	val palette = CinemaTheme.palette
	Box(
		modifier = Modifier
			.background(Color(0x991E1E2A), RoundedCornerShape(8.dp))
			.padding(horizontal = 9.dp, vertical = 4.dp),
	) {
		Text(
			text = text,
			style = MaterialTheme.typography.labelMedium,
			color = palette.textSecondary,
		)
	}
}

@Composable
private fun HomeSkeletonLoading(modifier: Modifier = Modifier) {
	val palette = CinemaTheme.palette
	Column(
		verticalArrangement = Arrangement.spacedBy(24.dp),
		modifier = modifier,
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth(0.55f)
				.height(220.dp)
				.clip(RoundedCornerShape(20.dp))
				.background(palette.surfaceElevated)
		)
		repeat(2) {
			Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
				repeat(4) {
					Box(
						modifier = Modifier
							.width(236.dp)
							.height(133.dp)
							.clip(RoundedCornerShape(14.dp))
							.background(palette.surfaceElevated)
					)
				}
			}
		}
	}
}
