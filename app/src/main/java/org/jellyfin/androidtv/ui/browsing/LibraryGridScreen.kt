package org.jellyfin.androidtv.ui.browsing

import android.content.Context
import android.view.View
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.fragment.app.Fragment
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ImageType as AppImageType
import org.jellyfin.androidtv.ui.design.CinemaAmbientCanvas
import org.jellyfin.androidtv.ui.design.CinemaFilterChip
import org.jellyfin.androidtv.ui.design.CinemaMediaCard
import org.jellyfin.androidtv.ui.design.CinemaNavDestination
import org.jellyfin.androidtv.ui.design.CinemaNavigationRail
import org.jellyfin.androidtv.ui.design.CinemaTheme
import org.jellyfin.androidtv.ui.design.CinemaSpacing
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes
import org.jellyfin.androidtv.util.rememberAmbientPalette
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

fun createLibraryGridComposeView(
	context: Context,
	folder: BaseItemDto,
	includeType: String?,
): View = ComposeView(context).apply {
	setContent {
		CinemaTheme {
			val viewModel: LibraryGridViewModel = koinViewModel()
			LaunchedEffect(folder.id, includeType) {
				viewModel.initialize(folder, includeType)
			}
			LibraryGridScreen(
				folder = folder,
				viewModel = viewModel,
			)
		}
	}
}

@OptIn(FlowPreview::class)
@Composable
fun LibraryGridScreen(
	folder: BaseItemDto,
	viewModel: LibraryGridViewModel = koinViewModel(),
) {
	val uiState by viewModel.uiState.collectAsState()
	val itemLauncher = koinInject<ItemLauncher>()
	val context = LocalContext.current
	val palette = CinemaTheme.palette

	var pendingFocusItem by remember { mutableStateOf<LibraryMediaItem?>(null) }
	var settledFocusItem by remember { mutableStateOf<LibraryMediaItem?>(null) }
	var settingsVisible by remember { mutableStateOf(false) }
	var genresExpanded by remember { mutableStateOf(false) }
	val gridFocusRequester = remember { FocusRequester() }
	val gridState = rememberLazyGridState()

	LaunchedEffect(Unit) {
		snapshotFlow { pendingFocusItem }
			.distinctUntilChanged { a, b -> a?.id == b?.id }
			.debounce(380L)
			.collect { settled ->
				if (settled != null) settledFocusItem = settled
			}
	}

	LaunchedEffect(uiState.isLoading, uiState.items.isNotEmpty()) {
		if (!uiState.isLoading && uiState.items.isNotEmpty()) {
			runCatching { gridFocusRequester.requestFocus() }
		}
	}

	val ambientSourceUrl = settledFocusItem?.backdropUrl ?: settledFocusItem?.imageUrl ?: uiState.items.firstOrNull()?.imageUrl
	val ambientPalette = rememberAmbientPalette(ambientSourceUrl)

	Box(modifier = Modifier.fillMaxSize()) {
		CinemaAmbientCanvas(
			ambient = ambientPalette,
			modifier = Modifier.fillMaxSize(),
		)

		Box(modifier = Modifier.fillMaxSize()) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.padding(start = CinemaSpacing.RailCollapsedWidth + 24.dp, end = 36.dp, top = 24.dp)
					.focusGroup(),
			) {
				// Top header: title + count + focused item metadata
				Row(
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween,
					modifier = Modifier.fillMaxWidth(),
				) {
					Column(modifier = Modifier.weight(1f)) {
						Row(
							verticalAlignment = Alignment.CenterVertically,
							horizontalArrangement = Arrangement.spacedBy(12.dp),
						) {
							Text(
								text = uiState.libraryTitle,
								style = MaterialTheme.typography.headlineLarge,
								color = palette.textPrimary,
							)
							if (uiState.totalCount > 0) {
								Box(
									modifier = Modifier
										.clip(RoundedCornerShape(999.dp))
										.background(palette.surfaceElevated)
										.padding(horizontal = 10.dp, vertical = 3.dp)
								) {
									Text(
										text = "${uiState.totalCount}",
										style = MaterialTheme.typography.labelMedium,
										color = palette.accentSecondary,
									)
								}
							}
						}

						val active = settledFocusItem
						if (active != null) {
							Spacer(modifier = Modifier.height(2.dp))
							Text(
								text = listOfNotNull(active.title, active.subtitle).joinToString(" · "),
								style = MaterialTheme.typography.bodyMedium,
								color = palette.textSecondary,
								maxLines = 1,
							)
						}
					}

					Surface(
						onClick = { settingsVisible = true },
						scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
						shape = ClickableSurfaceDefaults.shape(CircleShape),
						colors = ClickableSurfaceDefaults.colors(
							containerColor = palette.surfaceElevated,
							focusedContainerColor = palette.textPrimary,
							contentColor = palette.textSecondary,
							focusedContentColor = palette.canvas,
						),
						border = ClickableSurfaceDefaults.border(
							border = Border(BorderStroke(1.dp, palette.borderSubtle)),
							focusedBorder = Border(BorderStroke(2.dp, palette.focusRing)),
						),
						modifier = Modifier.size(40.dp),
					) {
						Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
							Icon(
								imageVector = Icons.Rounded.Tune,
								contentDescription = null,
								modifier = Modifier.size(20.dp),
							)
						}
					}
				}

				Spacer(modifier = Modifier.height(14.dp))

				// Sticky M3 Expressive filter & sort chips row
				LazyRow(
					horizontalArrangement = Arrangement.spacedBy(10.dp),
					modifier = Modifier.fillMaxWidth(),
				) {
					item(key = "filter_all") {
						val isAll = !uiState.unwatchedOnly && !uiState.favoritesOnly && uiState.selectedGenre == null
						CinemaFilterChip(
							text = stringResource(R.string.filter_all),
							selected = isAll,
							onClick = { viewModel.clearAllFilters() },
						)
					}

					item(key = "filter_unwatched") {
						CinemaFilterChip(
							text = stringResource(R.string.filter_unwatched),
							icon = Icons.Rounded.VisibilityOff,
							selected = uiState.unwatchedOnly,
							onClick = { viewModel.toggleUnwatchedOnly() },
						)
					}

					item(key = "filter_favorites") {
						CinemaFilterChip(
							text = stringResource(R.string.filter_favorites),
							icon = Icons.Rounded.Favorite,
							selected = uiState.favoritesOnly,
							onClick = { viewModel.toggleFavoritesOnly() },
						)
					}

					if (uiState.availableGenres.isNotEmpty()) {
						item(key = "filter_genres_toggle") {
							CinemaFilterChip(
								text = uiState.selectedGenre ?: stringResource(R.string.lbl_genres),
								icon = Icons.Rounded.FilterList,
								selected = uiState.selectedGenre != null || genresExpanded,
								onClick = { genresExpanded = !genresExpanded },
							)
						}
					}

					items(LibrarySortOption.entries, key = { it.name }) { option ->
						val selected = uiState.selectedSort == option
						val orderIcon = when {
							!selected -> null
							uiState.sortAscending -> Icons.Rounded.ArrowUpward
							else -> Icons.Rounded.ArrowDownward
						}
						CinemaFilterChip(
							text = stringResource(option.labelRes),
							icon = orderIcon,
							selected = selected,
							onClick = { viewModel.selectSort(option) },
						)
					}
				}

				if (genresExpanded && uiState.availableGenres.isNotEmpty()) {
					Spacer(modifier = Modifier.height(10.dp))
					LazyRow(
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						modifier = Modifier.fillMaxWidth(),
					) {
						items(uiState.availableGenres, key = { "genre_$it" }) { genre ->
							CinemaFilterChip(
								text = genre,
								selected = uiState.selectedGenre == genre,
								onClick = { viewModel.selectGenre(genre) },
							)
						}
					}
				}

				Spacer(modifier = Modifier.height(14.dp))

				val useLandscape = uiState.imageMode == AppImageType.THUMB || uiState.imageMode == AppImageType.BANNER
				val columns = when {
					useLandscape -> 4
					uiState.isSquareCards -> 5
					else -> 5
				}
				val cardW = when {
					useLandscape -> 195.dp
					uiState.isSquareCards -> 150.dp
					else -> 150.dp
				}
				val cardH = when {
					useLandscape -> 110.dp
					uiState.isSquareCards -> 150.dp
					else -> 225.dp
				}

				when {
					uiState.isLoading && uiState.items.isEmpty() -> {
						LazyVerticalGrid(
							columns = GridCells.Fixed(columns),
							horizontalArrangement = Arrangement.spacedBy(16.dp),
							verticalArrangement = Arrangement.spacedBy(18.dp),
							modifier = Modifier.fillMaxSize(),
						) {
							items(columns * 3) {
								Box(
									modifier = Modifier
										.width(cardW)
										.height(cardH)
										.clip(RoundedCornerShape(14.dp))
										.background(palette.surfaceElevated)
								)
							}
						}
					}

					uiState.items.isEmpty() -> {
						Box(
							contentAlignment = Alignment.Center,
							modifier = Modifier.fillMaxSize(),
						) {
							Text(
								text = stringResource(R.string.lbl_empty_library),
								style = MaterialTheme.typography.titleLarge,
								color = palette.textSecondary,
							)
						}
					}

					else -> {
						LazyVerticalGrid(
							state = gridState,
							columns = GridCells.Fixed(columns),
							contentPadding = PaddingValues(top = 6.dp, bottom = 36.dp),
							horizontalArrangement = Arrangement.spacedBy(16.dp),
							verticalArrangement = Arrangement.spacedBy(18.dp),
							modifier = Modifier
								.fillMaxSize()
								.focusGroup(),
						) {
							itemsIndexed(
								items = uiState.items,
								key = { _, item -> item.id },
								contentType = { _, _ -> "grid_card" },
							) { index, item ->
								val cardModifier = if (index == 0) {
									Modifier.focusRequester(gridFocusRequester)
								} else {
									Modifier
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
									modifier = cardModifier,
									onFocused = {
										pendingFocusItem = item
										viewModel.loadMoreIfNeeded(index)
									},
									onClick = {
										itemLauncher.launch(BaseItemDtoBaseRowItem(item.dto), null, context)
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

		if (settingsVisible) {
			ProvideRouter(
				routes,
				Routes.LIBRARIES_DISPLAY,
				mapOf(
					"itemId" to folder.id.toString(),
					"displayPreferencesId" to (folder.displayPreferencesId ?: folder.id.toString()),
				),
			) {
				SettingsDialog(
					visible = settingsVisible,
					onDismissRequest = {
						settingsVisible = false
						viewModel.refreshPreferencesAndReloadIfNeeded()
					},
				) {
					SettingsRouterContent()
				}
			}
		}
	}
}
