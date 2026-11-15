package org.jellyfin.androidtv.ui.search

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.design.CinemaAmbientCanvas
import org.jellyfin.androidtv.ui.design.CinemaFilterChip
import org.jellyfin.androidtv.ui.design.CinemaMediaCard
import org.jellyfin.androidtv.ui.design.CinemaNavDestination
import org.jellyfin.androidtv.ui.design.CinemaNavigationRail
import org.jellyfin.androidtv.ui.design.CinemaSectionRow
import org.jellyfin.androidtv.ui.design.CinemaSpacing
import org.jellyfin.androidtv.ui.design.CinemaTheme
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.search.composable.SearchTextInput
import org.jellyfin.androidtv.ui.search.composable.SearchVoiceInput
import org.jellyfin.androidtv.util.CineFastNative
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.albumPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.rememberAmbientPalette
import org.jellyfin.androidtv.util.speech.rememberSpeechRecognizerAvailability
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private enum class SearchTypeFilter(val labelRes: Int) {
	ALL(R.string.search_filter_all),
	MOVIES(R.string.search_filter_movies),
	SERIES(R.string.search_filter_series),
	EPISODES(R.string.search_filter_episodes),
	MUSIC(R.string.search_filter_music),
	PEOPLE(R.string.search_filter_people),
}

@Composable
fun SearchScreen(
	initialQuery: String?,
	viewModel: SearchViewModel = koinViewModel(),
) {
	val api = koinInject<ApiClient>()
	val itemLauncher = koinInject<ItemLauncher>()
	val context = LocalContext.current
	val palette = CinemaTheme.palette

	val results by viewModel.searchResultsFlow.collectAsState()
	var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
	var selectedFilter by remember { mutableStateOf(SearchTypeFilter.ALL) }
	var focusedBackdropUrl by remember { mutableStateOf<String?>(null) }

	val textInputFocusRequester = remember { FocusRequester() }
	val resultsFocusRequester = remember { FocusRequester() }
	val speechAvailable = rememberSpeechRecognizerAvailability()

	LaunchedEffect(initialQuery) {
		if (!initialQuery.isNullOrBlank()) {
			query = TextFieldValue(initialQuery)
			viewModel.searchImmediately(initialQuery)
			runCatching { resultsFocusRequester.requestFocus() }
		} else {
			runCatching { textInputFocusRequester.requestFocus() }
		}
	}

	val filteredGroups = remember(results, selectedFilter) {
		results.filter { group ->
			if (group.items.isEmpty()) return@filter false
			when (selectedFilter) {
				SearchTypeFilter.ALL -> true
				SearchTypeFilter.MOVIES -> group.labelRes == R.string.lbl_movies || group.labelRes == R.string.lbl_collections
				SearchTypeFilter.SERIES -> group.labelRes == R.string.lbl_series
				SearchTypeFilter.EPISODES -> group.labelRes == R.string.lbl_episodes
				SearchTypeFilter.MUSIC -> group.labelRes == R.string.lbl_artists ||
					group.labelRes == R.string.lbl_albums ||
					group.labelRes == R.string.lbl_songs ||
					group.labelRes == R.string.lbl_playlists
				SearchTypeFilter.PEOPLE -> group.labelRes == R.string.lbl_people
			}
		}
	}

	val ambientPalette = rememberAmbientPalette(focusedBackdropUrl, CineFastNative.DefaultAmbient)

	Box(modifier = Modifier.fillMaxSize()) {
		CinemaAmbientCanvas(
			ambient = ambientPalette,
			modifier = Modifier.fillMaxSize(),
		)

		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(start = CinemaSpacing.RailCollapsedWidth + 24.dp, end = 36.dp, top = 26.dp)
				.focusGroup(),
		) {
			Text(
				text = stringResource(R.string.nav_search),
				style = MaterialTheme.typography.displayMedium,
				color = palette.textPrimary,
			)

			Spacer(Modifier.height(14.dp))

			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.fillMaxWidth()
					.focusGroup(),
			) {
				if (speechAvailable) {
					SearchVoiceInput(
						onQueryChange = { query = query.copy(text = it) },
						onQuerySubmit = {
							viewModel.searchImmediately(query.text)
							runCatching { resultsFocusRequester.requestFocus() }
						},
					)
				}

				SearchTextInput(
					query = query.text,
					onQueryChange = {
						query = query.copy(text = it)
						viewModel.searchDebounced(query.text)
					},
					onQuerySubmit = {
						viewModel.searchImmediately(query.text)
						runCatching { resultsFocusRequester.requestFocus() }
					},
					modifier = Modifier
						.weight(1f)
						.focusRequester(textInputFocusRequester),
				)
			}

			Spacer(Modifier.height(12.dp))

			LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				items(SearchTypeFilter.entries, key = { it.name }) { filter ->
					CinemaFilterChip(
						text = stringResource(filter.labelRes),
						selected = selectedFilter == filter,
						onClick = { selectedFilter = filter },
					)
				}
			}

			Spacer(Modifier.height(14.dp))

			when {
				query.text.isBlank() -> {
					Box(
						contentAlignment = Alignment.CenterStart,
						modifier = Modifier
							.fillMaxSize()
							.padding(bottom = 80.dp),
					) {
						Text(
							text = stringResource(R.string.search_hint),
							style = MaterialTheme.typography.headlineMedium,
							color = palette.textMuted,
						)
					}
				}

				filteredGroups.isEmpty() -> {
					Box(
						contentAlignment = Alignment.Center,
						modifier = Modifier.fillMaxSize(),
					) {
						Text(
							text = stringResource(R.string.search_no_results),
							style = MaterialTheme.typography.titleLarge,
							color = palette.textSecondary,
						)
					}
				}

				else -> {
					LazyColumn(
						contentPadding = PaddingValues(top = 6.dp, bottom = 36.dp),
						verticalArrangement = Arrangement.spacedBy(20.dp),
						modifier = Modifier
							.fillMaxSize()
							.focusRequester(resultsFocusRequester),
					) {
						items(
							items = filteredGroups,
							key = { it.labelRes },
							contentType = { "search_row" },
						) { group ->
							CinemaSectionRow(
								title = stringResource(group.labelRes),
								items = group.items.toList(),
								key = { "${group.labelRes}_${it.id}" },
								startPadding = 0.dp,
							) { dto ->
								val isSquare = dto.type == BaseItemKind.MUSIC_ALBUM ||
									dto.type == BaseItemKind.MUSIC_ARTIST ||
									dto.type == BaseItemKind.AUDIO ||
									dto.type == BaseItemKind.PLAYLIST
								val isLandscape = dto.type == BaseItemKind.EPISODE ||
									dto.type == BaseItemKind.VIDEO ||
									dto.type == BaseItemKind.LIVE_TV_CHANNEL ||
									dto.type == BaseItemKind.LIVE_TV_PROGRAM

								val cardW = when {
									isLandscape -> 236.dp
									isSquare -> 166.dp
									else -> 146.dp
								}
								val cardH = when {
									isLandscape -> 133.dp
									isSquare -> 166.dp
									else -> 219.dp
								}
								val imgUrl = remember(dto.id, isLandscape, isSquare) {
									searchImageUrl(api, dto, isLandscape, isSquare)
								}
								val subtitle = remember(dto.id) { searchSubtitle(dto) }
								val backdrop = remember(dto.id) {
									(dto.itemBackdropImages.firstOrNull() ?: dto.itemImages[ImageType.PRIMARY])
										?.getUrl(api, maxWidth = 128, maxHeight = 128)
								}

								CinemaMediaCard(
									title = dto.name.orEmpty(),
									subtitle = subtitle,
									imageUrl = imgUrl,
									cardWidth = cardW,
									cardHeight = cardH,
									isWatched = dto.userData?.played == true,
									communityRating = dto.communityRating,
									onFocused = { if (backdrop != null) focusedBackdropUrl = backdrop },
									onClick = { itemLauncher.launch(BaseItemDtoBaseRowItem(dto), null, context) },
								)
							}
						}
					}
				}
			}
		}

		CinemaNavigationRail(
			activeDestination = CinemaNavDestination.SEARCH,
			modifier = Modifier.align(Alignment.CenterStart),
		)
	}
}

private fun searchImageUrl(
	api: ApiClient,
	dto: BaseItemDto,
	isLandscape: Boolean,
	isSquare: Boolean,
): String? = when {
	isLandscape -> (dto.itemImages[ImageType.THUMB] ?: dto.itemImages[ImageType.PRIMARY] ?: dto.itemBackdropImages.firstOrNull())
		?.getUrl(api, maxWidth = 320, maxHeight = 180)
	isSquare -> (dto.itemImages[ImageType.PRIMARY] ?: dto.albumPrimaryImage ?: dto.parentImages[ImageType.PRIMARY])
		?.getUrl(api, maxWidth = 220, maxHeight = 220)
	else -> (dto.itemImages[ImageType.PRIMARY] ?: dto.itemImages[ImageType.THUMB])
		?.getUrl(api, maxWidth = 220, maxHeight = 330)
}

private fun searchSubtitle(dto: BaseItemDto): String? = when (dto.type) {
	BaseItemKind.EPISODE -> listOfNotNull(
		dto.seriesName,
		dto.parentIndexNumber?.let { s -> dto.indexNumber?.let { e -> "T$s E$e" } },
	).joinToString(" · ").ifBlank { null }
	BaseItemKind.AUDIO, BaseItemKind.MUSIC_ALBUM -> listOfNotNull(
		dto.albumArtist ?: dto.artists?.firstOrNull(),
		dto.productionYear?.toString(),
	).joinToString(" · ").ifBlank { null }
	else -> listOfNotNull(
		dto.productionYear?.toString(),
		dto.runTimeTicks?.takeIf { it > 0L }?.let { TimeUtils.formatMillis(it / 10_000L) },
	).joinToString(" · ").ifBlank { null }
}
