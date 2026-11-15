package org.jellyfin.androidtv.ui.browsing

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ImageType as AppImageType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.preference.LibraryPreferences
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.genresApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber
import java.util.UUID

enum class LibrarySortOption(val sortBy: ItemSortBy, val defaultOrder: SortOrder, val labelRes: Int) {
	TITLE(ItemSortBy.SORT_NAME, SortOrder.ASCENDING, R.string.sort_name),
	DATE_ADDED(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING, R.string.sort_date_added),
	RELEASE_DATE(ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING, R.string.sort_release_date),
	RATING(ItemSortBy.COMMUNITY_RATING, SortOrder.DESCENDING, R.string.sort_rating),
}

@Immutable
data class LibraryMediaItem(
	val id: UUID,
	val dto: BaseItemDto,
	val title: String,
	val subtitle: String?,
	val imageUrl: String?,
	val backdropUrl: String?,
	val progressFraction: Float?,
	val isWatched: Boolean,
	val unplayedCount: Int?,
	val communityRating: Float?,
)

@Immutable
data class LibraryGridUiState(
	val libraryTitle: String = "",
	val isLoading: Boolean = true,
	val isLoadingMore: Boolean = false,
	val totalCount: Int = 0,
	val items: List<LibraryMediaItem> = emptyList(),
	val unwatchedOnly: Boolean = false,
	val favoritesOnly: Boolean = false,
	val selectedSort: LibrarySortOption = LibrarySortOption.TITLE,
	val sortAscending: Boolean = true,
	val availableGenres: List<String> = emptyList(),
	val selectedGenre: String? = null,
	val imageMode: AppImageType = AppImageType.POSTER,
	val isSquareCards: Boolean = false,
	val error: String? = null,
)

class LibraryGridViewModel(
	private val context: Context,
	private val api: ApiClient,
	private val preferencesRepository: PreferencesRepository,
) : ViewModel() {
	private val _uiState = MutableStateFlow(LibraryGridUiState())
	val uiState: StateFlow<LibraryGridUiState> = _uiState.asStateFlow()

	private var folder: BaseItemDto? = null
	private var includeType: String? = null
	private var libraryPreferences: LibraryPreferences? = null
	private var loadJob: Job? = null
	private var nextStartIndex: Int = 0
	private var hasMore: Boolean = false

	fun initialize(folder: BaseItemDto, includeType: String?) {
		if (this.folder?.id == folder.id && this.includeType == includeType) return
		this.folder = folder
		this.includeType = includeType
		val prefsId = folder.displayPreferencesId ?: folder.id.toString()
		val prefs = preferencesRepository.getLibraryPreferences(prefsId)
		libraryPreferences = prefs

		val savedSortBy = prefs[LibraryPreferences.sortBy]
		val sortOption = LibrarySortOption.entries.firstOrNull { it.sortBy == savedSortBy }
			?: LibrarySortOption.TITLE
		val sortOrder = prefs[LibraryPreferences.sortOrder]
		val savedImageType = prefs[LibraryPreferences.imageType].let { mode ->
			if ((folder.type == BaseItemKind.SEASON || includeType == "Episode") && mode == AppImageType.POSTER) {
				AppImageType.THUMB
			} else {
				mode
			}
		}
		val square = isSquareLibrary(folder) ||
			includeType in setOf("MusicAlbum", "AlbumArtist", "Artist", "Audio")

		_uiState.value = LibraryGridUiState(
			libraryTitle = folder.name.orEmpty(),
			isLoading = true,
			unwatchedOnly = prefs[LibraryPreferences.filterUnwatchedOnly],
			favoritesOnly = prefs[LibraryPreferences.filterFavoritesOnly],
			selectedSort = sortOption,
			sortAscending = sortOrder == SortOrder.ASCENDING,
			imageMode = savedImageType,
			isSquareCards = square,
		)

		loadGenres(folder.id)
		reload()
	}

	fun refreshPreferencesAndReloadIfNeeded() {
		val prefs = libraryPreferences ?: return
		val current = _uiState.value
		val newImageMode = prefs[LibraryPreferences.imageType]
		val newUnwatched = prefs[LibraryPreferences.filterUnwatchedOnly]
		val newFav = prefs[LibraryPreferences.filterFavoritesOnly]
		if (newImageMode != current.imageMode || newUnwatched != current.unwatchedOnly || newFav != current.favoritesOnly) {
			_uiState.value = current.copy(
				imageMode = newImageMode,
				unwatchedOnly = newUnwatched,
				favoritesOnly = newFav,
			)
			reload()
		}
	}

	fun toggleUnwatchedOnly() {
		val current = _uiState.value
		val updated = !current.unwatchedOnly
		_uiState.value = current.copy(unwatchedOnly = updated)
		persistPreferences()
		reload()
	}

	fun toggleFavoritesOnly() {
		val current = _uiState.value
		val updated = !current.favoritesOnly
		_uiState.value = current.copy(favoritesOnly = updated)
		persistPreferences()
		reload()
	}

	fun clearAllFilters() {
		val current = _uiState.value
		if (!current.unwatchedOnly && !current.favoritesOnly && current.selectedGenre == null) return
		_uiState.value = current.copy(
			unwatchedOnly = false,
			favoritesOnly = false,
			selectedGenre = null,
		)
		persistPreferences()
		reload()
	}

	fun selectSort(option: LibrarySortOption) {
		val current = _uiState.value
		val ascending = if (current.selectedSort == option) {
			!current.sortAscending
		} else {
			option.defaultOrder == SortOrder.ASCENDING
		}
		_uiState.value = current.copy(selectedSort = option, sortAscending = ascending)
		persistPreferences()
		reload()
	}

	fun selectGenre(genre: String?) {
		val current = _uiState.value
		val next = if (current.selectedGenre == genre) null else genre
		if (next == current.selectedGenre) return
		_uiState.value = current.copy(selectedGenre = next)
		reload()
	}

	fun loadMoreIfNeeded(focusedIndex: Int) {
		val state = _uiState.value
		if (state.isLoading || state.isLoadingMore || !hasMore) return
		if (focusedIndex < state.items.size - 16) return
		fetchPage(reset = false)
	}

	fun reload() {
		fetchPage(reset = true)
	}

	private fun persistPreferences() {
		val prefs = libraryPreferences ?: return
		val state = _uiState.value
		viewModelScope.launch {
			prefs[LibraryPreferences.filterFavoritesOnly] = state.favoritesOnly
			prefs[LibraryPreferences.filterUnwatchedOnly] = state.unwatchedOnly
			prefs[LibraryPreferences.sortBy] = state.selectedSort.sortBy
			prefs[LibraryPreferences.sortOrder] = if (state.sortAscending) SortOrder.ASCENDING else SortOrder.DESCENDING
			prefs.commit()
		}
	}

	private fun loadGenres(parentId: UUID) {
		viewModelScope.launch(Dispatchers.IO) {
			runCatching {
				val res by api.genresApi.getGenres(parentId = parentId)
				res.items.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }.distinct().take(24)
			}.onSuccess { genres ->
				_uiState.value = _uiState.value.copy(availableGenres = genres)
			}.onFailure { err ->
				Timber.d(err, "Could not load genres for library %s", parentId)
			}
		}
	}

	private fun fetchPage(reset: Boolean) {
		val currentFolder = folder ?: return
		if (reset) {
			loadJob?.cancel()
			nextStartIndex = 0
			hasMore = true
			_uiState.value = _uiState.value.copy(isLoading = true, isLoadingMore = false, error = null)
		} else {
			_uiState.value = _uiState.value.copy(isLoadingMore = true)
		}

		val stateSnapshot = _uiState.value
		val startIndex = nextStartIndex

		loadJob = viewModelScope.launch {
			runCatching {
				withContext(Dispatchers.IO) {
					queryItems(currentFolder, stateSnapshot, startIndex)
				}
			}.onSuccess { (total, mapped) ->
				val merged = if (reset) mapped else (_uiState.value.items + mapped).distinctBy { it.id }
				nextStartIndex = startIndex + mapped.size
				hasMore = merged.size < total && mapped.isNotEmpty()
				_uiState.value = _uiState.value.copy(
					isLoading = false,
					isLoadingMore = false,
					totalCount = total,
					items = merged,
					error = null,
				)
			}.onFailure { err ->
				Timber.e(err, "Failed loading library grid page startIndex=%d", startIndex)
				hasMore = false
				_uiState.value = _uiState.value.copy(
					isLoading = false,
					isLoadingMore = false,
					error = err.localizedMessage,
				)
			}
		}
	}

	private suspend fun queryItems(
		currentFolder: BaseItemDto,
		state: LibraryGridUiState,
		startIndex: Int,
	): Pair<Int, List<LibraryMediaItem>> {
		val pageSize = 60
		val specialMusicMode = currentFolder.collectionType == CollectionType.MUSIC &&
			(includeType == "AlbumArtist" || includeType == "Artist")

		val response = when {
			specialMusicMode && includeType == "AlbumArtist" -> {
				val res by api.artistsApi.getAlbumArtists(
					parentId = currentFolder.id,
					fields = ItemRepository.browseFields,
					startIndex = startIndex,
					limit = pageSize,
				)
				res
			}
			specialMusicMode && includeType == "Artist" -> {
				val res by api.artistsApi.getArtists(
					parentId = currentFolder.id,
					fields = ItemRepository.browseFields,
					startIndex = startIndex,
					limit = pageSize,
				)
				res
			}
			else -> {
				val base = BrowsingUtils.createBrowseGridItemsRequest(currentFolder)
				val filters = buildSet {
					if (state.favoritesOnly) add(ItemFilter.IS_FAVORITE)
					if (state.unwatchedOnly) add(ItemFilter.IS_UNPLAYED)
				}.ifEmpty { null }
				val order = if (state.sortAscending) SortOrder.ASCENDING else SortOrder.DESCENDING
				val request = base.copy(
					fields = ItemRepository.browseFields,
					startIndex = startIndex,
					limit = pageSize,
					filters = filters,
					sortBy = setOf(state.selectedSort.sortBy, ItemSortBy.SORT_NAME),
					sortOrder = setOf(order, SortOrder.ASCENDING),
					genres = state.selectedGenre?.let { setOf(it) },
				)
				val res by api.itemsApi.getItems(request)
				res
			}
		}

		val useLandscape = !state.isSquareCards && (state.imageMode == AppImageType.THUMB || state.imageMode == AppImageType.BANNER)
		val items = response.items.map { dto -> mapItem(dto, useLandscape) }
		return response.totalRecordCount to items
	}

	private fun mapItem(dto: BaseItemDto, useLandscape: Boolean): LibraryMediaItem {
		val userData = dto.userData
		val progressFraction = userData?.playedPercentage?.toFloat()?.div(100f)?.coerceIn(0f, 1f)
			?: run {
				val total = dto.runTimeTicks ?: 0L
				val pos = userData?.playbackPositionTicks ?: 0L
				if (total > 0L && pos > 0L) (pos.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
			}

		val cardUrl = when {
			useLandscape -> (dto.itemImages[ImageType.THUMB] ?: dto.itemImages[ImageType.BACKDROP] ?: dto.itemImages[ImageType.PRIMARY])
				?.getUrl(api, maxWidth = 320, maxHeight = 180)
			isSquareLibrary(dto) -> (dto.itemImages[ImageType.PRIMARY] ?: dto.itemImages[ImageType.THUMB])
				?.getUrl(api, maxWidth = 220, maxHeight = 220)
			else -> (dto.itemImages[ImageType.PRIMARY] ?: dto.itemImages[ImageType.THUMB])
				?.getUrl(api, maxWidth = 220, maxHeight = 330)
		}

		val backdropUrl = (
			dto.itemBackdropImages.firstOrNull()
				?: dto.parentBackdropImages.firstOrNull()
				?: dto.itemImages[ImageType.THUMB]
			)?.getUrl(api, maxWidth = 480, maxHeight = 270)

		val subtitle = buildList {
			dto.productionYear?.let { add(it.toString()) }
			dto.runTimeTicks?.takeIf { it > 0L }?.let { add(TimeUtils.formatMillis(it / 10_000L)) }
			dto.officialRating?.takeIf { it.isNotBlank() }?.let { add(it) }
		}.joinToString(" · ").ifBlank { null }

		return LibraryMediaItem(
			id = dto.id,
			dto = dto,
			title = dto.name.orEmpty(),
			subtitle = subtitle,
			imageUrl = cardUrl,
			backdropUrl = backdropUrl,
			progressFraction = progressFraction,
			isWatched = userData?.played == true,
			unplayedCount = userData?.unplayedItemCount,
			communityRating = dto.communityRating,
		)
	}

	private fun isSquareLibrary(folder: BaseItemDto): Boolean {
		return folder.collectionType == CollectionType.MUSIC ||
			folder.type == BaseItemKind.MUSIC_ALBUM ||
			folder.type == BaseItemKind.MUSIC_ARTIST ||
			folder.type == BaseItemKind.GENRE ||
			folder.type == BaseItemKind.MUSIC_GENRE
	}
}
