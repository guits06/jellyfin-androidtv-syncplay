package org.jellyfin.androidtv.ui.itemdetail

import android.content.ActivityNotFoundException
import android.content.Context
import android.view.View
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToQueue
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.syncplay.SyncPlayManager
import org.jellyfin.androidtv.ui.design.CinemaActionButton
import org.jellyfin.androidtv.ui.design.CinemaAmbientCanvas
import org.jellyfin.androidtv.ui.design.CinemaAsyncImage
import org.jellyfin.androidtv.ui.design.CinemaFilterChip
import org.jellyfin.androidtv.ui.design.CinemaHeroScrim
import org.jellyfin.androidtv.ui.design.CinemaMediaCard
import org.jellyfin.androidtv.ui.design.CinemaNavDestination
import org.jellyfin.androidtv.ui.design.CinemaNavigationRail
import org.jellyfin.androidtv.ui.design.CinemaSectionRow
import org.jellyfin.androidtv.ui.design.CinemaSpacing
import org.jellyfin.androidtv.ui.design.CinemaTheme
import org.jellyfin.androidtv.ui.design.SyncPlayStatusPill
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.rememberAmbientPalette
import org.jellyfin.androidtv.util.sdk.TrailerUtils.getExternalTrailerIntent
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinActivityViewModel
import java.util.UUID

fun createMediaDetailComposeView(
	context: Context,
	itemId: UUID,
): View = ComposeView(context).apply {
	setContent {
		CinemaTheme {
			val viewModel: MediaDetailViewModel = koinViewModel()
			LaunchedEffect(itemId) {
				viewModel.load(itemId)
			}
			MediaDetailScreen(itemId = itemId, viewModel = viewModel)
		}
	}
}

@Composable
fun MediaDetailScreen(
	itemId: UUID,
	viewModel: MediaDetailViewModel = koinViewModel(),
) {
	val uiState by viewModel.uiState.collectAsState()
	val syncPlayManager = koinInject<SyncPlayManager>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val playbackLauncher = koinInject<PlaybackLauncher>()
	val mediaManager = koinInject<MediaManager>()
	val itemLauncher = koinInject<ItemLauncher>()
	val navigationRepository = koinInject<NavigationRepository>()
	val settingsViewModel = koinActivityViewModel<SettingsViewModel>()
	val api = koinInject<ApiClient>()
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current
	val scope = rememberCoroutineScope()

	val currentGroup by syncPlayManager.currentGroup.collectAsState()
	val groupState by syncPlayManager.groupState.collectAsState()
	val palette = CinemaTheme.palette

	val primaryFocusRequester = remember { FocusRequester() }
	var showAudioPicker by remember { mutableStateOf(false) }
	var showSubtitlePicker by remember { mutableStateOf(false) }

	DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				viewModel.refreshIfNeeded()
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
	}

	LaunchedEffect(uiState.item?.id) {
		if (uiState.item != null) {
			runCatching { primaryFocusRequester.requestFocus() }
		}
	}

	val ambientPalette = rememberAmbientPalette(uiState.backdropUrl ?: uiState.posterUrl)

	Box(modifier = Modifier.fillMaxSize()) {
		CinemaAmbientCanvas(
			ambient = ambientPalette,
			modifier = Modifier.fillMaxSize(),
		)

		uiState.backdropUrl?.let { url ->
			Box(
				modifier = Modifier
					.fillMaxWidth(0.76f)
					.fillMaxHeight(0.66f)
					.align(Alignment.TopEnd),
			) {
				CinemaAsyncImage(
					url = url,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					targetWidthDp = 520.dp,
					targetHeightDp = 292.dp,
					crossfadeMs = 0,
					fallbackColor = palette.canvas,
					modifier = Modifier.fillMaxSize(),
				)
				CinemaHeroScrim(
					dimmed = false,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}

		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(start = CinemaSpacing.RailCollapsedWidth)
				.focusGroup(),
		) {
			when {
				uiState.isLoading && uiState.item == null -> {
					Column(
						verticalArrangement = Arrangement.spacedBy(16.dp),
						modifier = Modifier
							.fillMaxSize()
							.padding(start = 32.dp, top = 56.dp, end = 48.dp),
					) {
						Box(
							modifier = Modifier
								.width(340.dp)
								.height(44.dp)
								.clip(RoundedCornerShape(12.dp))
								.background(palette.surfaceElevated)
						)
						Box(
							modifier = Modifier
								.width(520.dp)
								.height(80.dp)
								.clip(RoundedCornerShape(14.dp))
								.background(palette.surface)
						)
						Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
							repeat(4) {
								Box(
									modifier = Modifier
										.width(136.dp)
										.height(44.dp)
										.clip(RoundedCornerShape(999.dp))
										.background(palette.surfaceElevated)
								)
							}
						}
					}
				}

				uiState.item != null -> {
					val item = uiState.item!!
					val canPlay = item.type != BaseItemKind.PERSON && item.type != BaseItemKind.MUSIC_ARTIST

					LazyColumn(
						contentPadding = PaddingValues(top = 34.dp, bottom = 42.dp),
						verticalArrangement = Arrangement.spacedBy(22.dp),
						modifier = Modifier.fillMaxSize(),
					) {
						// Hero header block (Poster/Artist photo + Logo/Title + Metadata + Overview + Action Buttons + Stream Selectors)
						item(key = "detail_hero", contentType = "detail_hero") {
							Column(
								modifier = Modifier
									.fillMaxWidth()
									.padding(start = 28.dp, end = 48.dp),
							) {
								Row(
									horizontalArrangement = Arrangement.spacedBy(26.dp),
									verticalAlignment = Alignment.Bottom,
								) {
									if (item.type == BaseItemKind.PERSON || item.type == BaseItemKind.MUSIC_ARTIST || uiState.backdropUrl == null) {
										uiState.posterUrl?.let { pUrl ->
											Box(
												modifier = Modifier
													.width(if (item.type == BaseItemKind.MUSIC_ARTIST) 190.dp else 156.dp)
													.height(if (item.type == BaseItemKind.MUSIC_ARTIST) 190.dp else 234.dp)
													.clip(RoundedCornerShape(18.dp))
													.background(palette.surface),
											) {
												AsyncImage(
													model = remember(pUrl) {
														ImageRequest.Builder(context)
															.data(pUrl)
															.allowRgb565(true)
															.build()
													},
													contentDescription = uiState.title,
													contentScale = ContentScale.Crop,
													modifier = Modifier.fillMaxSize(),
												)
											}
										}
									}

									Column(modifier = Modifier.weight(1f)) {
										uiState.eyebrow?.let { eyebrow ->
											Text(
												text = eyebrow.uppercase(),
												style = MaterialTheme.typography.labelLarge,
												color = palette.accentSecondary,
												maxLines = 1,
												overflow = TextOverflow.Ellipsis,
											)
											Spacer(Modifier.height(6.dp))
										}

										if (!uiState.logoUrl.isNullOrBlank()) {
											AsyncImage(
												model = remember(uiState.logoUrl) {
													ImageRequest.Builder(context)
														.data(uiState.logoUrl)
														.allowRgb565(false)
														.build()
												},
												contentDescription = uiState.title,
												contentScale = ContentScale.Fit,
												alignment = Alignment.CenterStart,
												modifier = Modifier
													.height(82.dp)
													.widthIn(max = 400.dp),
											)
										} else {
											Text(
												text = uiState.title,
												style = MaterialTheme.typography.displayLarge,
												color = palette.textPrimary,
												maxLines = 2,
												overflow = TextOverflow.Ellipsis,
											)
										}

										if (uiState.metadataChips.isNotEmpty()) {
											Spacer(Modifier.height(10.dp))
											Row(
												horizontalArrangement = Arrangement.spacedBy(8.dp),
												verticalAlignment = Alignment.CenterVertically,
											) {
												uiState.metadataChips.forEachIndexed { idx, chip ->
													Box(
														modifier = Modifier
															.clip(RoundedCornerShape(999.dp))
															.background(
																if (idx == 0 && chip.startsWith("★")) Color(0x38F5C518) else palette.surfaceElevated.copy(alpha = 0.85f)
															)
															.padding(horizontal = 10.dp, vertical = 4.dp),
													) {
														Text(
															text = chip,
															style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
															color = if (idx == 0 && chip.startsWith("★")) palette.ratingGold else palette.textPrimary,
														)
													}
												}
											}
										}

										uiState.genresLine?.let { genres ->
											Spacer(Modifier.height(6.dp))
											Text(
												text = genres,
												style = MaterialTheme.typography.bodyMedium,
												color = palette.textSecondary,
												maxLines = 1,
												overflow = TextOverflow.Ellipsis,
											)
										}
									}
								}

								uiState.overview?.takeIf { it.isNotBlank() }?.let { overview ->
									Spacer(Modifier.height(14.dp))
									Text(
										text = overview,
										style = MaterialTheme.typography.bodyLarge,
										color = palette.textSecondary,
										maxLines = 4,
										overflow = TextOverflow.Ellipsis,
										modifier = Modifier.widthIn(max = 560.dp),
									)
								}

								Spacer(Modifier.height(18.dp))

								// Primary actions row
								val resumeLabelFallback = stringResource(R.string.action_resume)
								val playLabel = stringResource(R.string.action_play)
								LazyRow(
									horizontalArrangement = Arrangement.spacedBy(12.dp),
									contentPadding = PaddingValues(vertical = 2.dp),
								) {
									if (canPlay) {
										val target = uiState.playableTarget ?: item
										val playText = if (uiState.canResume) {
											uiState.resumeLabel ?: resumeLabelFallback
										} else {
											playLabel
										}
										item("btn_play") {
											CinemaActionButton(
												text = playText,
												icon = Icons.Rounded.PlayArrow,
												primary = true,
												modifier = Modifier.focusRequester(primaryFocusRequester),
												onClick = {
													viewModel.applySelectedTracksBeforePlay()
													val startMs = if (uiState.canResume) uiState.resumePositionMs else 0L
													if (item.type == BaseItemKind.SERIES) {
														playbackHelper.retrieveAndPlay(listOf(target.id), false, startMs, 0, context)
													} else {
														playbackLauncher.launch(context, listOf(target), startMs.toInt())
													}
												},
											)
										}

										if (uiState.canResume) {
											item("btn_restart") {
												CinemaActionButton(
													text = stringResource(R.string.lbl_from_beginning),
													icon = Icons.Rounded.Replay,
													onClick = {
														viewModel.applySelectedTracksBeforePlay()
														if (item.type == BaseItemKind.SERIES) {
															playbackHelper.retrieveAndPlay(listOf(target.id), false, 0L, 0, context)
														} else {
															playbackLauncher.launch(context, listOf(target), 0)
														}
													},
												)
											}
										}

										item("btn_syncplay") {
											val syncText = currentGroup?.let {
												"${stringResource(R.string.action_watch_together)}: ${it.groupName}"
											} ?: stringResource(R.string.action_watch_syncplay)
											CinemaActionButton(
												text = syncText,
												icon = Icons.Rounded.Sync,
												accentColor = if (currentGroup != null) palette.syncPlayActive.copy(alpha = 0.25f) else null,
												onClick = {
													if (currentGroup != null) {
														viewModel.applySelectedTracksBeforePlay()
														val startMs = if (uiState.canResume) uiState.resumePositionMs else 0L
														playbackHelper.retrieveAndPlay(listOf(target.id), false, startMs, 0, context)
													} else {
														settingsViewModel.show(Routes.PLAYBACK_SYNCPLAY)
													}
												},
											)
										}
									}

									if (uiState.hasTrailer) {
										item("btn_trailer") {
											CinemaActionButton(
												text = stringResource(R.string.lbl_play_trailers),
												icon = Icons.Rounded.LiveTv,
												onClick = {
													if ((item.localTrailerCount ?: 0) > 0) {
														scope.launch {
															val trailers = withContext(Dispatchers.IO) {
																runCatching { api.userLibraryApi.getLocalTrailers(item.id).content }.getOrDefault(emptyList())
															}
															if (trailers.isNotEmpty()) {
																playbackLauncher.launch(context, trailers, 0)
															}
														}
													} else {
														try {
															getExternalTrailerIntent(context, item)?.let { context.startActivity(it) }
														} catch (_: ActivityNotFoundException) {
															Toast.makeText(context, R.string.no_player_message, Toast.LENGTH_SHORT).show()
														}
													}
												},
											)
										}
									}

									if (item.type == BaseItemKind.EPISODE && item.seriesId != null) {
										item("btn_goto_series") {
											CinemaActionButton(
												text = stringResource(R.string.lbl_goto_series),
												onClick = { navigationRepository.navigate(Destinations.itemDetails(item.seriesId!!)) },
											)
										}
									}

									item("btn_favorite") {
										CinemaActionButton(
											text = stringResource(if (uiState.isFavorite) R.string.lbl_remove_favorite else R.string.lbl_add_favorite),
											icon = if (uiState.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
											modifier = if (!canPlay) Modifier.focusRequester(primaryFocusRequester) else Modifier,
											onClick = { viewModel.toggleFavorite() },
										)
									}

									if (canPlay) {
										item("btn_watched") {
											CinemaActionButton(
												text = stringResource(if (uiState.isPlayed) R.string.lbl_watched else R.string.lbl_unwatched),
												icon = Icons.Rounded.Check,
												onClick = { viewModel.togglePlayed() },
											)
										}
									}

									if (canPlay && item.type != BaseItemKind.SERIES) {
										item("btn_queue") {
											CinemaActionButton(
												text = stringResource(R.string.lbl_add_to_queue),
												icon = Icons.Rounded.AddToQueue,
												onClick = {
													mediaManager.addToAudioQueue(listOf(item))
													Toast.makeText(context, R.string.lbl_add_to_queue, Toast.LENGTH_SHORT).show()
												},
											)
										}
									}
								}

								// Pre-playback Audio & Subtitle track selectors
								if (uiState.audioTracks.isNotEmpty() || uiState.subtitleTracks.isNotEmpty()) {
									Spacer(Modifier.height(12.dp))
									Row(
										horizontalArrangement = Arrangement.spacedBy(10.dp),
										verticalAlignment = Alignment.CenterVertically,
									) {
										if (uiState.audioTracks.isNotEmpty()) {
											val selectedAudioLabel = uiState.audioTracks
												.firstOrNull { it.index == uiState.selectedAudioIndex }?.label
												?: uiState.audioTracks.first().label
											CinemaFilterChip(
												text = "${stringResource(R.string.pref_audio)}: $selectedAudioLabel",
												icon = Icons.Rounded.Audiotrack,
												selected = showAudioPicker,
												onClick = {
													showAudioPicker = !showAudioPicker
													if (showAudioPicker) showSubtitlePicker = false
												},
											)
										}

										if (uiState.subtitleTracks.isNotEmpty()) {
											val selectedSubLabel = uiState.subtitleTracks
												.firstOrNull { it.index == uiState.selectedSubtitleIndex }?.label
												?: stringResource(R.string.lbl_subtitles_off)
											CinemaFilterChip(
												text = "${stringResource(R.string.lbl_subtitles)}: $selectedSubLabel",
												icon = Icons.Rounded.ClosedCaption,
												selected = showSubtitlePicker,
												onClick = {
													showSubtitlePicker = !showSubtitlePicker
													if (showSubtitlePicker) showAudioPicker = false
												},
											)
										}
									}

									if (showAudioPicker && uiState.audioTracks.isNotEmpty()) {
										Spacer(Modifier.height(8.dp))
										LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
											items(uiState.audioTracks, key = { "aud_${it.index}" }) { opt ->
												CinemaFilterChip(
													text = opt.label,
													selected = opt.index == uiState.selectedAudioIndex,
													onClick = {
														viewModel.selectAudioTrack(opt)
														showAudioPicker = false
													},
												)
											}
										}
									}

									if (showSubtitlePicker && uiState.subtitleTracks.isNotEmpty()) {
										Spacer(Modifier.height(8.dp))
										LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
											items(uiState.subtitleTracks, key = { "sub_${it.index}" }) { opt ->
												CinemaFilterChip(
													text = opt.label,
													selected = opt.index == uiState.selectedSubtitleIndex,
													onClick = {
														viewModel.selectSubtitleTrack(opt)
														showSubtitlePicker = false
													},
												)
											}
										}
									}
								}
							}
						}

						// Series Seasons Tabs + Episode Cards
						if (uiState.seasons.isNotEmpty()) {
							item(key = "seasons_tabs", contentType = "seasons") {
								Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
									LazyRow(
										horizontalArrangement = Arrangement.spacedBy(10.dp),
										contentPadding = PaddingValues(start = 28.dp, end = 48.dp),
									) {
										items(uiState.seasons, key = { "season_${it.id}" }) { season ->
											CinemaFilterChip(
												text = season.title,
												selected = season.id == uiState.selectedSeasonId,
												onClick = { viewModel.selectSeason(season.id) },
											)
										}
									}

									if (uiState.episodes.isNotEmpty()) {
										CinemaSectionRow(
											title = stringResource(R.string.lbl_episodes),
											items = uiState.episodes,
											key = { "ep_${it.id}" },
										) { ep ->
											CinemaMediaCard(
												title = ep.title,
												subtitle = ep.subtitle,
												imageUrl = ep.imageUrl,
												cardWidth = 240.dp,
												cardHeight = 135.dp,
												progressFraction = ep.progressFraction,
												isWatched = ep.isWatched,
												onClick = { itemLauncher.launch(BaseItemDtoBaseRowItem(ep.dto), null, context) },
											)
										}
									}
								}
							}
						}

						// Discography / Filmography / Collection items
						if (uiState.secondaryItems.isNotEmpty() && !uiState.secondaryItemsTitle.isNullOrBlank()) {
							item(key = "secondary_items", contentType = "row") {
								CinemaSectionRow(
									title = uiState.secondaryItemsTitle!!,
									items = uiState.secondaryItems,
									key = { "sec_${it.id}" },
								) { card ->
									val cardW = if (card.isSquare) 168.dp else 148.dp
									val cardH = if (card.isSquare) 168.dp else 222.dp
									CinemaMediaCard(
										title = card.title,
										subtitle = card.subtitle,
										imageUrl = card.imageUrl,
										cardWidth = cardW,
										cardHeight = cardH,
										progressFraction = card.progressFraction,
										isWatched = card.isWatched,
										unplayedCount = card.unplayedCount,
										communityRating = card.communityRating,
										onClick = { itemLauncher.launch(BaseItemDtoBaseRowItem(card.dto), null, context) },
									)
								}
							}
						}

						// Chapters
						if (uiState.chapters.isNotEmpty()) {
							item(key = "chapters", contentType = "row") {
								CinemaSectionRow(
									title = stringResource(R.string.lbl_chapters),
									items = uiState.chapters,
									key = { "ch_${it.index}" },
								) { chapter ->
									CinemaMediaCard(
										title = chapter.name,
										subtitle = chapter.timeLabel,
										imageUrl = chapter.imageUrl ?: uiState.backdropUrl,
										cardWidth = 220.dp,
										cardHeight = 124.dp,
										onClick = {
											viewModel.applySelectedTracksBeforePlay()
											val posMs = (chapter.startPositionTicks / 10_000L).toInt()
											playbackLauncher.launch(context, listOf(item), posMs)
										},
									)
								}
							}
						}

						// Cast & Crew
						if (uiState.castAndCrew.isNotEmpty()) {
							item(key = "cast_crew", contentType = "row") {
								CinemaSectionRow(
									title = stringResource(R.string.lbl_cast_crew),
									items = uiState.castAndCrew,
									key = { "person_${it.id}" },
								) { person ->
									CinemaMediaCard(
										title = person.name,
										subtitle = person.role,
										imageUrl = person.imageUrl,
										cardWidth = 136.dp,
										cardHeight = 196.dp,
										onClick = { navigationRepository.navigate(Destinations.itemDetails(person.id)) },
									)
								}
							}
						}

						// More like this
						if (uiState.similarItems.isNotEmpty()) {
							item(key = "similar", contentType = "row") {
								CinemaSectionRow(
									title = stringResource(R.string.lbl_more_like_this),
									items = uiState.similarItems,
									key = { "sim_${it.id}" },
								) { sim ->
									CinemaMediaCard(
										title = sim.title,
										subtitle = sim.subtitle,
										imageUrl = sim.imageUrl,
										cardWidth = 148.dp,
										cardHeight = 222.dp,
										isWatched = sim.isWatched,
										communityRating = sim.communityRating,
										onClick = { navigationRepository.navigate(Destinations.itemDetails(sim.id)) },
									)
								}
							}
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
