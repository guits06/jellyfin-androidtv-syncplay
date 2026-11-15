package org.jellyfin.androidtv.ui.itemdetail

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.albumPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import timber.log.Timber
import java.util.UUID

@Immutable
data class AlbumTrackItem(
	val id: UUID,
	val indexLabel: String,
	val title: String,
	val artistLabel: String?,
	val durationLabel: String?,
	val dto: BaseItemDto,
)

@Immutable
data class AlbumDetailUiState(
	val isLoading: Boolean = true,
	val isFavoritesMode: Boolean = false,
	val item: BaseItemDto? = null,
	val title: String = "",
	val artistName: String? = null,
	val artistId: UUID? = null,
	val coverUrl: String? = null,
	val yearLabel: String? = null,
	val genresLabel: String? = null,
	val totalDurationLabel: String? = null,
	val overview: String? = null,
	val isFavorite: Boolean = false,
	val tracks: List<AlbumTrackItem> = emptyList(),
	val trackDtos: List<BaseItemDto> = emptyList(),
	val error: String? = null,
)

class AlbumDetailViewModel(
	private val context: Context,
	private val api: ApiClient,
	private val itemMutationRepository: ItemMutationRepository,
) : ViewModel() {
	private val _uiState = MutableStateFlow(AlbumDetailUiState())
	val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

	private var loadedKey: String? = null

	fun loadAlbumOrPlaylist(itemId: UUID) {
		val key = "item_$itemId"
		if (loadedKey == key) return
		loadedKey = key
		_uiState.value = AlbumDetailUiState(isLoading = true)

		viewModelScope.launch {
			runCatching {
				withContext(Dispatchers.IO) {
					val item by api.userLibraryApi.getItem(itemId)
					val tracks = when (item.type) {
						BaseItemKind.PLAYLIST -> api.playlistsApi.getPlaylistItems(
							playlistId = item.id,
							limit = 150,
							fields = ItemRepository.browseFields,
						).content.items
						else -> api.itemsApi.getItems(
							parentId = item.id,
							includeItemTypes = setOf(BaseItemKind.AUDIO),
							recursive = true,
							sortBy = setOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
							limit = 200,
							fields = ItemRepository.browseFields,
						).content.items
					}
					buildState(item, tracks)
				}
			}.onSuccess { state ->
				_uiState.value = state
			}.onFailure { err ->
				Timber.e(err, "Failed loading album/playlist %s", itemId)
				_uiState.value = AlbumDetailUiState(isLoading = false, error = err.localizedMessage)
			}
		}
	}

	fun loadFavoriteSongs(parentId: UUID?) {
		val key = "fav_$parentId"
		if (loadedKey == key) return
		loadedKey = key
		_uiState.value = AlbumDetailUiState(
			isLoading = true,
			isFavoritesMode = true,
			title = context.getString(R.string.lbl_favorites),
			overview = context.getString(R.string.desc_automatic_fav_songs),
		)

		viewModelScope.launch {
			runCatching {
				withContext(Dispatchers.IO) {
					val tracks = api.itemsApi.getItems(
						parentId = parentId,
						includeItemTypes = setOf(BaseItemKind.AUDIO),
						recursive = true,
						filters = setOf(ItemFilter.IS_FAVORITE_OR_LIKES),
						sortBy = setOf(ItemSortBy.SORT_NAME),
						limit = 150,
						fields = ItemRepository.browseFields,
					).content.items

					val totalMs = tracks.sumOf { (it.runTimeTicks ?: 0L) / 10_000L }
					val coverUrl = tracks.firstOrNull()?.let { first ->
						(first.albumPrimaryImage ?: first.itemImages[ImageType.PRIMARY])?.getUrl(api, maxWidth = 360, maxHeight = 360)
					}
					AlbumDetailUiState(
						isLoading = false,
						isFavoritesMode = true,
						title = context.getString(R.string.lbl_favorites),
						coverUrl = coverUrl,
						totalDurationLabel = totalMs.takeIf { it > 0L }?.let(TimeUtils::formatMillis),
						overview = context.getString(R.string.desc_automatic_fav_songs),
						tracks = mapTracks(tracks),
						trackDtos = tracks,
					)
				}
			}.onSuccess { state ->
				_uiState.value = state
			}.onFailure { err ->
				Timber.e(err, "Failed loading favorite tracks")
				_uiState.value = _uiState.value.copy(isLoading = false, error = err.localizedMessage)
			}
		}
	}

	fun toggleFavorite() {
		val item = _uiState.value.item ?: return
		val nextFav = !_uiState.value.isFavorite
		_uiState.value = _uiState.value.copy(isFavorite = nextFav)
		viewModelScope.launch {
			runCatching {
				itemMutationRepository.setFavorite(item.id, nextFav)
			}.onSuccess { userData ->
				_uiState.value = _uiState.value.copy(isFavorite = userData.isFavorite)
			}.onFailure {
				_uiState.value = _uiState.value.copy(isFavorite = !nextFav)
			}
		}
	}

	private fun buildState(item: BaseItemDto, tracks: List<BaseItemDto>): AlbumDetailUiState {
		val coverUrl = (item.itemImages[ImageType.PRIMARY] ?: item.itemImages[ImageType.THUMB])
			?.getUrl(api, maxWidth = 380, maxHeight = 380)
		val artist = item.albumArtists?.firstOrNull()
		val artistName = artist?.name ?: item.albumArtist ?: item.artists?.firstOrNull()
		val totalMs = (item.cumulativeRunTimeTicks ?: item.runTimeTicks ?: tracks.sumOf { it.runTimeTicks ?: 0L }) / 10_000L

		return AlbumDetailUiState(
			isLoading = false,
			isFavoritesMode = false,
			item = item,
			title = item.name.orEmpty(),
			artistName = artistName,
			artistId = artist?.id,
			coverUrl = coverUrl,
			yearLabel = item.productionYear?.toString(),
			genresLabel = item.genres?.take(3)?.joinToString(" · ")?.takeIf { it.isNotBlank() },
			totalDurationLabel = totalMs.takeIf { it > 0L }?.let(TimeUtils::formatMillis),
			overview = item.overview,
			isFavorite = item.userData?.isFavorite == true,
			tracks = mapTracks(tracks),
			trackDtos = tracks,
		)
	}

	private fun mapTracks(tracks: List<BaseItemDto>): List<AlbumTrackItem> =
		tracks.mapIndexed { idx, track ->
			val trackNo = track.indexNumber ?: (idx + 1)
			val duration = track.runTimeTicks?.takeIf { it > 0L }?.let { TimeUtils.formatMillis(it / 10_000L) }
			val artist = track.artists?.firstOrNull() ?: track.albumArtist
			AlbumTrackItem(
				id = track.id,
				indexLabel = trackNo.toString().padStart(2, '0'),
				title = track.name.orEmpty(),
				artistLabel = artist,
				durationLabel = duration,
				dto = track,
			)
		}
}
