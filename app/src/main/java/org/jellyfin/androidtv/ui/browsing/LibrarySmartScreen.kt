package org.jellyfin.androidtv.ui.browsing

import android.content.Context
import android.view.View
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.design.CinemaAmbientCanvas
import org.jellyfin.androidtv.ui.design.CinemaFilterChip
import org.jellyfin.androidtv.ui.design.CinemaMediaCard
import org.jellyfin.androidtv.ui.design.CinemaNavDestination
import org.jellyfin.androidtv.ui.design.CinemaNavigationRail
import org.jellyfin.androidtv.ui.design.CinemaSectionRow
import org.jellyfin.androidtv.ui.design.CinemaTheme
import org.jellyfin.androidtv.ui.design.CinemaSpacing
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.util.rememberAmbientPalette
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

fun createLibrarySmartComposeView(
	context: Context,
	folder: BaseItemDto,
): View = ComposeView(context).apply {
	setContent {
		CinemaTheme {
			val viewModel: LibrarySmartViewModel = koinViewModel()
			LaunchedEffect(folder.id) {
				viewModel.initialize(folder)
			}
			LibrarySmartScreen(folder = folder, viewModel = viewModel)
		}
	}
}

@OptIn(FlowPreview::class)
@Composable
fun LibrarySmartScreen(
	folder: BaseItemDto,
	viewModel: LibrarySmartViewModel = koinViewModel(),
) {
	val uiState by viewModel.uiState.collectAsState()
	val itemLauncher = koinInject<ItemLauncher>()
	val navigationRepository = koinInject<NavigationRepository>()
	val mediaManager = koinInject<MediaManager>()
	val playbackLauncher = koinInject<PlaybackLauncher>()
	val api = koinInject<ApiClient>()
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val palette = CinemaTheme.palette

	var pendingFocus by remember { mutableStateOf<SmartMediaItem?>(null) }
	var settledFocus by remember { mutableStateOf<SmartMediaItem?>(null) }
	val initialFocus = remember { FocusRequester() }

	LaunchedEffect(Unit) {
		snapshotFlow { pendingFocus }
			.distinctUntilChanged { a, b -> a?.id == b?.id }
			.debounce(360L)
			.collect { settled ->
				if (settled != null) settledFocus = settled
			}
	}

	LaunchedEffect(uiState.isLoading) {
		if (!uiState.isLoading) {
			runCatching { initialFocus.requestFocus() }
		}
	}

	val ambientSource = settledFocus?.backdropUrl
		?: settledFocus?.imageUrl
		?: uiState.sections.firstOrNull()?.items?.firstOrNull()?.imageUrl
	val ambientPalette = rememberAmbientPalette(ambientSource)

	Box(modifier = Modifier.fillMaxSize()) {
		CinemaAmbientCanvas(
			ambient = ambientPalette,
			modifier = Modifier.fillMaxSize(),
		)

		Box(modifier = Modifier.fillMaxSize()) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.padding(start = CinemaSpacing.RailCollapsedWidth)
					.focusGroup(),
			) {
				// Header + quick pills
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(start = 28.dp, end = 36.dp, top = 26.dp, bottom = 10.dp),
				) {
					Text(
						text = uiState.title,
						style = MaterialTheme.typography.displayMedium,
						color = palette.textPrimary,
					)
					settledFocus?.let { item ->
						Spacer(modifier = Modifier.height(2.dp))
						Text(
							text = listOfNotNull(item.title, item.subtitle).joinToString(" · "),
							style = MaterialTheme.typography.bodyLarge,
							color = palette.textSecondary,
							maxLines = 1,
						)
					}

					Spacer(modifier = Modifier.height(14.dp))

					val hasQueue = mediaManager.hasAudioQueueItems()
					LazyRow(
						horizontalArrangement = Arrangement.spacedBy(10.dp),
						modifier = Modifier.fillMaxWidth(),
					) {
						items(uiState.quickActions, key = { it.id }) { action ->
							val indexModifier = if (uiState.quickActions.firstOrNull()?.id == action.id) {
								Modifier.focusRequester(initialFocus)
							} else {
								Modifier
							}
							val icon = when (action.id) {
								"albums" -> Icons.Rounded.Album
								"album_artists", "artists" -> Icons.Rounded.Person
								"favorites" -> Icons.Rounded.Favorite
								"shuffle_songs", "random" -> Icons.Rounded.Shuffle
								else -> Icons.Rounded.LibraryMusic
							}
							CinemaFilterChip(
								text = action.label,
								icon = icon,
								selected = false,
								modifier = indexModifier,
								onClick = {
									when (action.id) {
										"albums", "all" -> navigationRepository.navigate(Destinations.libraryBrowser(folder, null))
										"album_artists" -> navigationRepository.navigate(Destinations.libraryBrowser(folder, "AlbumArtist"))
										"artists" -> navigationRepository.navigate(Destinations.libraryBrowser(folder, "Artist"))
										"favorites" -> navigationRepository.navigate(Destinations.musicFavorites(folder.id))
										"by_genre" -> {
											val kind = if (folder.collectionType == CollectionType.TVSHOWS) "Series" else "Movie"
											navigationRepository.navigate(Destinations.libraryByGenres(folder, kind))
										}
										"by_letter" -> {
											val kind = if (folder.collectionType == CollectionType.TVSHOWS) "Series" else "Movie"
											navigationRepository.navigate(Destinations.libraryByLetter(folder, kind))
										}
										"shuffle_songs" -> scope.launch {
											val tracks = withContext(Dispatchers.IO) {
												runCatching {
													api.itemsApi.getItems(
														parentId = folder.id,
														includeItemTypes = setOf(BaseItemKind.AUDIO),
														recursive = true,
														sortBy = setOf(ItemSortBy.RANDOM),
														limit = 100,
														fields = ItemRepository.browseFields,
													).content.items
												}.getOrDefault(emptyList())
											}
											if (tracks.isNotEmpty()) {
												mediaManager.playNow(context, tracks, 0, true)
											}
										}
										"random" -> scope.launch {
											val kind = if (folder.collectionType == CollectionType.TVSHOWS) BaseItemKind.SERIES else BaseItemKind.MOVIE
											val randomItem = withContext(Dispatchers.IO) {
												runCatching {
													api.itemsApi.getItems(
														parentId = folder.id,
														includeItemTypes = setOf(kind),
														recursive = true,
														sortBy = setOf(ItemSortBy.RANDOM),
														limit = 1,
													).content.items.firstOrNull()
												}.getOrNull()
											}
											if (randomItem != null) {
												if (kind == BaseItemKind.MOVIE) {
													playbackLauncher.launch(context, listOf(randomItem))
												} else {
													navigationRepository.navigate(Destinations.itemDetails(randomItem.id))
												}
											}
										}
									}
								},
							)
						}

						if (hasQueue && uiState.collectionType == CollectionType.MUSIC) {
							item(key = "now_playing") {
								CinemaFilterChip(
									text = stringResource(R.string.lbl_now_playing),
									icon = Icons.Rounded.GraphicEq,
									selected = true,
									onClick = { navigationRepository.navigate(Destinations.nowPlaying) },
								)
							}
						}
					}
				}

				if (uiState.isLoading && uiState.sections.isEmpty()) {
					Row(
						horizontalArrangement = Arrangement.spacedBy(14.dp),
						modifier = Modifier.padding(start = 28.dp, top = 16.dp),
					) {
						repeat(5) {
							Box(
								modifier = Modifier
									.width(172.dp)
									.height(172.dp)
									.clip(RoundedCornerShape(14.dp))
									.background(palette.surfaceElevated)
							)
						}
					}
				} else {
					LazyColumn(
						contentPadding = PaddingValues(top = 10.dp, bottom = 36.dp),
						verticalArrangement = Arrangement.spacedBy(20.dp),
						modifier = Modifier.fillMaxSize(),
					) {
						items(
							items = uiState.sections,
							key = { it.id },
							contentType = { "smart_row" },
						) { section ->
							CinemaSectionRow(
								title = section.title,
								items = section.items,
								key = { "${section.id}_${it.id}" },
							) { item ->
								val cardW = when {
									section.isLandscape -> 236.dp
									section.isSquare -> 174.dp
									else -> 152.dp
								}
								val cardH = when {
									section.isLandscape -> 133.dp
									section.isSquare -> 174.dp
									else -> 228.dp
								}
								CinemaMediaCard(
									title = item.title,
									subtitle = item.subtitle,
									imageUrl = item.imageUrl,
									cardWidth = cardW,
									cardHeight = cardH,
									progressFraction = item.progressFraction,
									isWatched = item.isWatched,
									unplayedCount = item.unplayedCount,
									communityRating = item.communityRating,
									onFocused = { pendingFocus = item },
									onClick = {
										if (item.dto.type == BaseItemKind.AUDIO && section.allSourceDtos.isNotEmpty()) {
											val idx = section.allSourceDtos.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
											mediaManager.playNow(context, section.allSourceDtos, idx, false)
										} else {
											itemLauncher.launch(BaseItemDtoBaseRowItem(item.dto), null, context)
										}
									},
								)
							}
						}
					}
				}
			}

			CinemaNavigationRail(
				activeDestination = CinemaNavDestination.HOME,
				modifier = Modifier.align(Alignment.CenterStart),
			)
		}
	}
}
