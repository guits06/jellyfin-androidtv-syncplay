package org.jellyfin.androidtv.ui.browsing

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID

@Immutable
data class SmartQuickAction(
	val id: String,
	val label: String,
)

@Immutable
data class SmartMediaItem(
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
data class SmartSectionModel(
	val id: String,
	val title: String,
	val isLandscape: Boolean,
	val isSquare: Boolean,
	val items: List<SmartMediaItem>,
	val allSourceDtos: List<BaseItemDto> = emptyList(),
)

@Immutable
data class LibrarySmartUiState(
	val title: String = "",
	val collectionType: CollectionType? = null,
	val isLoading: Boolean = true,
	val quickActions: List<SmartQuickAction> = emptyList(),
	val sections: List<SmartSectionModel> = emptyList(),
	val error: String? = null,
)

class LibrarySmartViewModel(
	private val context: Context,
	private val api: ApiClient,
) : ViewModel() {
	private val _uiState = MutableStateFlow(LibrarySmartUiState())
	val uiState: StateFlow<LibrarySmartUiState> = _uiState.asStateFlow()

	private var loadedFolderId: UUID? = null

	fun initialize(folder: BaseItemDto) {
		if (loadedFolderId == folder.id) return
		loadedFolderId = folder.id
		val type = folder.collectionType
		_uiState.value = LibrarySmartUiState(
			title = folder.name.orEmpty(),
			collectionType = type,
			isLoading = true,
			quickActions = buildQuickActions(type),
		)
		reload(folder)
	}

	private fun buildQuickActions(type: CollectionType?): List<SmartQuickAction> = when (type) {
		CollectionType.MUSIC -> listOf(
			SmartQuickAction("albums", context.getString(R.string.lbl_albums)),
			SmartQuickAction("album_artists", context.getString(R.string.lbl_album_artists)),
			SmartQuickAction("artists", context.getString(R.string.lbl_artists)),
			SmartQuickAction("favorites", context.getString(R.string.lbl_favorites)),
			SmartQuickAction("shuffle_songs", context.getString(R.string.lbl_shuffle_all)),
		)
		CollectionType.MOVIES, CollectionType.TVSHOWS -> listOf(
			SmartQuickAction("all", context.getString(R.string.lbl_all_items)),
			SmartQuickAction("by_genre", context.getString(R.string.lbl_genres)),
			SmartQuickAction("by_letter", context.getString(R.string.lbl_by_letter)),
			SmartQuickAction("random", context.getString(R.string.random)),
		)
		else -> listOf(
			SmartQuickAction("all", context.getString(R.string.lbl_all_items)),
		)
	}

	fun reload(folder: BaseItemDto) {
		viewModelScope.launch {
			runCatching {
				withContext(Dispatchers.IO) {
					loadSections(folder)
				}
			}.onSuccess { sections ->
				_uiState.value = _uiState.value.copy(
					isLoading = false,
					sections = sections,
					error = null,
				)
			}.onFailure { err ->
				Timber.e(err, "Failed loading smart view for %s", folder.id)
				_uiState.value = _uiState.value.copy(
					isLoading = false,
					error = err.localizedMessage,
				)
			}
		}
	}

	private suspend fun loadSections(folder: BaseItemDto): List<SmartSectionModel> = coroutineScope {
		when (folder.collectionType) {
			CollectionType.MUSIC -> {
				val latestJob = async {
					runCatching {
						api.userLibraryApi.getLatestMedia(
							BrowsingUtils.createLatestMediaRequest(folder.id, BaseItemKind.AUDIO, true)
								.copy(fields = ItemRepository.browseFields, limit = 20)
						).content
					}.getOrDefault(emptyList())
				}
				val lastPlayedJob = async {
					runCatching {
						api.itemsApi.getItems(
							BrowsingUtils.createLastPlayedRequest(folder.id)
								.copy(fields = ItemRepository.browseFields, limit = 20)
						).content.items
					}.getOrDefault(emptyList())
				}
				val favAlbumsJob = async {
					runCatching {
						api.itemsApi.getItems(
							BrowsingUtils.createFavoriteItemsRequest(folder.id, BaseItemKind.MUSIC_ALBUM)
								.copy(fields = ItemRepository.browseFields, limit = 24)
						).content.items
					}.getOrDefault(emptyList())
				}
				val playlistsJob = async {
					runCatching {
						api.itemsApi.getItems(
							BrowsingUtils.createPlaylistsRequest()
								.copy(fields = ItemRepository.browseFields, limit = 20)
						).content.items
					}.getOrDefault(emptyList())
				}

				val latest = latestJob.await()
				val lastPlayed = lastPlayedJob.await()
				val favAlbums = favAlbumsJob.await()
				val playlists = playlistsJob.await()

				listOfNotNull(
					latest.takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel(
							id = "music_latest",
							title = context.getString(R.string.lbl_latest),
							isLandscape = false,
							isSquare = true,
							items = list.map { mapItem(it, landscape = false, square = true) },
						)
					},
					lastPlayed.takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel(
							id = "music_last_played",
							title = context.getString(R.string.lbl_last_played),
							isLandscape = false,
							isSquare = true,
							items = list.map { mapItem(it, landscape = false, square = true) },
							allSourceDtos = list,
						)
					},
					favAlbums.takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel(
							id = "music_fav_albums",
							title = context.getString(R.string.lbl_favorites),
							isLandscape = false,
							isSquare = true,
							items = list.map { mapItem(it, landscape = false, square = true) },
						)
					},
					playlists.takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel(
							id = "music_playlists",
							title = context.getString(R.string.lbl_playlists),
							isLandscape = false,
							isSquare = true,
							items = list.map { mapItem(it, landscape = false, square = true) },
						)
					},
				)
			}

			CollectionType.MOVIES -> {
				val resumeJob = async {
					runCatching {
						api.itemsApi.getItems(
							BrowsingUtils.createResumeItemsRequest(folder.id, BaseItemKind.MOVIE)
								.copy(fields = ItemRepository.browseFields, limit = 18)
						).content.items
					}.getOrDefault(emptyList())
				}
				val latestJob = async {
					runCatching {
						api.userLibraryApi.getLatestMedia(
							BrowsingUtils.createLatestMediaRequest(folder.id)
								.copy(fields = ItemRepository.browseFields, limit = 20)
						).content
					}.getOrDefault(emptyList())
				}
				val favJob = async {
					runCatching {
						api.itemsApi.getItems(
							BrowsingUtils.createFavoriteItemsRequest(folder.id, BaseItemKind.MOVIE)
								.copy(fields = ItemRepository.browseFields, limit = 20)
						).content.items
					}.getOrDefault(emptyList())
				}
				val boxSetJob = async {
					runCatching {
						api.itemsApi.getItems(
							BrowsingUtils.createCollectionsRequest(folder.id)
								.copy(fields = ItemRepository.browseFields, limit = 20)
						).content.items
					}.getOrDefault(emptyList())
				}

				listOfNotNull(
					resumeJob.await().takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel("movies_resume", context.getString(R.string.lbl_continue_watching), isLandscape = true, isSquare = false, items = list.map { mapItem(it, landscape = true, square = false) })
					},
					latestJob.await().takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel("movies_latest", context.getString(R.string.lbl_latest), isLandscape = false, isSquare = false, items = list.map { mapItem(it, landscape = false, square = false) })
					},
					favJob.await().takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel("movies_favs", context.getString(R.string.lbl_favorites), isLandscape = false, isSquare = false, items = list.map { mapItem(it, landscape = false, square = false) })
					},
					boxSetJob.await().takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel("movies_collections", context.getString(R.string.lbl_collections), isLandscape = false, isSquare = false, items = list.map { mapItem(it, landscape = false, square = false) })
					},
				)
			}

			CollectionType.TVSHOWS -> {
				val resumeJob = async {
					runCatching {
						api.itemsApi.getItems(
							BrowsingUtils.createResumeItemsRequest(folder.id, BaseItemKind.EPISODE)
								.copy(fields = ItemRepository.browseFields, limit = 18)
						).content.items
					}.getOrDefault(emptyList())
				}
				val nextUpJob = async {
					runCatching {
						api.tvShowsApi.getNextUp(
							BrowsingUtils.createGetNextUpRequest(folder.id)
								.copy(fields = ItemRepository.browseFields, limit = 18)
						).content.items
					}.getOrDefault(emptyList())
				}
				val latestJob = async {
					runCatching {
						api.userLibraryApi.getLatestMedia(
							BrowsingUtils.createLatestMediaRequest(folder.id, BaseItemKind.EPISODE, true)
								.copy(fields = ItemRepository.browseFields, limit = 20)
						).content
					}.getOrDefault(emptyList())
				}
				val favJob = async {
					runCatching {
						api.itemsApi.getItems(
							BrowsingUtils.createFavoriteItemsRequest(folder.id, BaseItemKind.SERIES)
								.copy(fields = ItemRepository.browseFields, limit = 20)
						).content.items
					}.getOrDefault(emptyList())
				}

				listOfNotNull(
					resumeJob.await().takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel("tv_resume", context.getString(R.string.lbl_continue_watching), isLandscape = true, isSquare = false, items = list.map { mapItem(it, landscape = true, square = false) })
					},
					nextUpJob.await().takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel("tv_nextup", context.getString(R.string.lbl_next_up), isLandscape = true, isSquare = false, items = list.map { mapItem(it, landscape = true, square = false) })
					},
					latestJob.await().takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel("tv_latest", context.getString(R.string.lbl_latest), isLandscape = false, isSquare = false, items = list.map { mapItem(it, landscape = false, square = false) })
					},
					favJob.await().takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel("tv_favs", context.getString(R.string.lbl_favorites), isLandscape = false, isSquare = false, items = list.map { mapItem(it, landscape = false, square = false) })
					},
				)
			}

			else -> {
				// BOX_SET / Folder fallback
				val children = runCatching {
					api.itemsApi.getItems(
						GetItemsRequest(
							parentId = folder.id,
							fields = ItemRepository.browseFields,
							limit = 60,
							sortBy = setOf(ItemSortBy.SORT_NAME),
							sortOrder = setOf(SortOrder.ASCENDING),
						)
					).content.items
				}.getOrDefault(emptyList())
				val hasMusic = children.any { it.type == BaseItemKind.MUSIC_ALBUM || it.type == BaseItemKind.AUDIO }
				listOfNotNull(
					children.takeIf { it.isNotEmpty() }?.let { list ->
						SmartSectionModel("folder_items", folder.name.orEmpty(), isLandscape = false, isSquare = hasMusic, items = list.map { mapItem(it, landscape = false, square = hasMusic) })
					}
				)
			}
		}
	}

	private fun mapItem(dto: BaseItemDto, landscape: Boolean, square: Boolean): SmartMediaItem {
		val images = dto.itemImages
		val cardUrl = when {
			landscape -> (images[ImageType.THUMB] ?: dto.itemBackdropImages.firstOrNull() ?: images[ImageType.PRIMARY])
				?.getUrl(api, maxWidth = 320, maxHeight = 180)
			square -> (images[ImageType.PRIMARY] ?: images[ImageType.THUMB])
				?.getUrl(api, maxWidth = 220, maxHeight = 220)
			else -> (images[ImageType.PRIMARY] ?: images[ImageType.THUMB])
				?.getUrl(api, maxWidth = 220, maxHeight = 330)
		}

		val backdropUrl = (
			dto.itemBackdropImages.firstOrNull()
				?: dto.parentBackdropImages.firstOrNull()
				?: images[ImageType.PRIMARY]
			)?.getUrl(api, maxWidth = 480, maxHeight = 270)

		val subtitle = when (dto.type) {
			BaseItemKind.MUSIC_ALBUM,
			BaseItemKind.AUDIO -> dto.albumArtist ?: dto.artists?.firstOrNull() ?: dto.productionYear?.toString()
			BaseItemKind.EPISODE -> listOfNotNull(
				dto.seriesName,
				dto.indexNumber?.let { "E$it" },
			).joinToString(" · ").ifBlank { null }
			else -> dto.productionYear?.toString()
				?: dto.runTimeTicks?.takeIf { it > 0L }?.let { TimeUtils.formatMillis(it / 10_000L) }
		}

		return SmartMediaItem(
			id = dto.id,
			dto = dto,
			title = dto.name.orEmpty(),
			subtitle = subtitle,
			imageUrl = cardUrl,
			backdropUrl = backdropUrl ?: cardUrl,
			progressFraction = dto.userData?.playedPercentage?.toFloat()?.div(100f),
			isWatched = dto.userData?.played == true,
			unplayedCount = dto.userData?.unplayedItemCount,
			communityRating = dto.communityRating,
		)
	}
}
