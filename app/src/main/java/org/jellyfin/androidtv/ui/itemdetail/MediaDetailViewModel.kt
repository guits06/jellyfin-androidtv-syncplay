package org.jellyfin.androidtv.ui.itemdetail

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
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber
import java.time.Instant
import java.util.UUID

@Immutable
data class DetailTrackOption(
	val index: Int,
	val label: String,
	val language: String?,
)

@Immutable
data class DetailCardItem(
	val id: UUID,
	val dto: BaseItemDto,
	val title: String,
	val subtitle: String?,
	val imageUrl: String?,
	val progressFraction: Float?,
	val isWatched: Boolean,
	val unplayedCount: Int?,
	val communityRating: Float?,
	val isLandscape: Boolean,
	val isSquare: Boolean,
)

@Immutable
data class DetailChapterItem(
	val index: Int,
	val name: String,
	val startPositionTicks: Long,
	val timeLabel: String,
	val imageUrl: String?,
)

@Immutable
data class DetailPersonItem(
	val id: UUID,
	val name: String,
	val role: String?,
	val imageUrl: String?,
)

@Immutable
data class DetailSeasonTab(
	val id: UUID,
	val title: String,
	val seasonNumber: Int?,
)

@Immutable
data class MediaDetailUiState(
	val isLoading: Boolean = true,
	val item: BaseItemDto? = null,
	val title: String = "",
	val eyebrow: String? = null,
	val metadataChips: List<String> = emptyList(),
	val genresLine: String? = null,
	val overview: String? = null,
	val backdropUrl: String? = null,
	val posterUrl: String? = null,
	val logoUrl: String? = null,
	val isFavorite: Boolean = false,
	val isPlayed: Boolean = false,
	val canResume: Boolean = false,
	val resumePositionMs: Long = 0L,
	val resumeLabel: String? = null,
	val hasTrailer: Boolean = false,
	val playableTarget: BaseItemDto? = null,
	val audioTracks: List<DetailTrackOption> = emptyList(),
	val selectedAudioIndex: Int? = null,
	val subtitleTracks: List<DetailTrackOption> = emptyList(),
	val selectedSubtitleIndex: Int = -1,
	val seasons: List<DetailSeasonTab> = emptyList(),
	val selectedSeasonId: UUID? = null,
	val episodes: List<DetailCardItem> = emptyList(),
	val episodesLoading: Boolean = false,
	val chapters: List<DetailChapterItem> = emptyList(),
	val castAndCrew: List<DetailPersonItem> = emptyList(),
	val secondaryItemsTitle: String? = null,
	val secondaryItems: List<DetailCardItem> = emptyList(),
	val similarItems: List<DetailCardItem> = emptyList(),
	val error: String? = null,
)

class MediaDetailViewModel(
	private val context: Context,
	private val api: ApiClient,
	private val itemMutationRepository: ItemMutationRepository,
	private val dataRefreshService: DataRefreshService,
	private val videoQueueManager: VideoQueueManager,
) : ViewModel() {
	private val _uiState = MutableStateFlow(MediaDetailUiState())
	val uiState: StateFlow<MediaDetailUiState> = _uiState.asStateFlow()

	private var currentItemId: UUID? = null

	fun load(itemId: UUID, forceRefresh: Boolean = false) {
		if (!forceRefresh && currentItemId == itemId && _uiState.value.item != null) return
		currentItemId = itemId

		viewModelScope.launch {
			if (_uiState.value.item == null || _uiState.value.item?.id != itemId) {
				_uiState.value = MediaDetailUiState(isLoading = true)
			}

			runCatching {
				withContext(Dispatchers.IO) {
					fetchDetails(itemId)
				}
			}.onSuccess { state ->
				_uiState.value = state
			}.onFailure { err ->
				Timber.e(err, "Failed loading media detail for %s", itemId)
				_uiState.value = _uiState.value.copy(
					isLoading = false,
					error = err.localizedMessage,
				)
			}
		}
	}

	fun refreshIfNeeded() {
		currentItemId?.let { load(it, forceRefresh = true) }
	}

	fun selectSeason(seasonId: UUID) {
		val current = _uiState.value
		if (current.selectedSeasonId == seasonId) return
		val seriesId = current.item?.id ?: return

		_uiState.value = current.copy(selectedSeasonId = seasonId, episodesLoading = true)
		viewModelScope.launch {
			val eps = withContext(Dispatchers.IO) {
				runCatching {
					api.tvShowsApi.getEpisodes(
						seriesId = seriesId,
						seasonId = seasonId,
						fields = ItemRepository.browseFields,
					).content.items.map { mapCard(it, landscape = true, square = false) }
				}.getOrDefault(emptyList())
			}
			_uiState.value = _uiState.value.copy(episodes = eps, episodesLoading = false)
		}
	}

	fun selectAudioTrack(option: DetailTrackOption) {
		_uiState.value = _uiState.value.copy(selectedAudioIndex = option.index)
		option.language?.let { videoQueueManager.setLastPlayedAudioLanguageIsoCode(it) }
	}

	fun selectSubtitleTrack(option: DetailTrackOption) {
		_uiState.value = _uiState.value.copy(selectedSubtitleIndex = option.index)
		videoQueueManager.setLastPlayedSubtitleLanguageIsoCode(option.language.orEmpty())
	}

	fun applySelectedTracksBeforePlay() {
		val state = _uiState.value
		state.audioTracks.firstOrNull { it.index == state.selectedAudioIndex }?.language?.let {
			videoQueueManager.setLastPlayedAudioLanguageIsoCode(it)
		}
		if (state.selectedSubtitleIndex < 0) {
			videoQueueManager.setLastPlayedSubtitleLanguageIsoCode("")
		} else {
			state.subtitleTracks.firstOrNull { it.index == state.selectedSubtitleIndex }?.language?.let {
				videoQueueManager.setLastPlayedSubtitleLanguageIsoCode(it)
			}
		}
	}

	fun toggleFavorite() {
		val dto = _uiState.value.item ?: return
		viewModelScope.launch {
			runCatching {
				val userData = itemMutationRepository.setFavorite(
					item = dto.id,
					favorite = !(dto.userData?.isFavorite ?: false),
				)
				dataRefreshService.lastFavoriteUpdate = Instant.now()
				_uiState.value = _uiState.value.copy(
					item = dto.copy(userData = userData),
					isFavorite = userData.isFavorite,
				)
			}.onFailure { err ->
				Timber.w(err, "Failed toggling favorite for %s", dto.id)
			}
		}
	}

	fun togglePlayed() {
		val dto = _uiState.value.item ?: return
		viewModelScope.launch {
			runCatching {
				val userData = itemMutationRepository.setPlayed(
					item = dto.id,
					played = !(dto.userData?.played ?: false),
				)
				dataRefreshService.lastPlayback = Instant.now()
				val resumeMs = (userData.playbackPositionTicks / 10_000L).coerceAtLeast(0L)
				_uiState.value = _uiState.value.copy(
					item = dto.copy(userData = userData),
					isPlayed = userData.played,
					canResume = resumeMs > 10_000L && !userData.played,
					resumePositionMs = resumeMs,
				)
			}.onFailure { err ->
				Timber.w(err, "Failed toggling played for %s", dto.id)
			}
		}
	}

	private suspend fun fetchDetails(itemId: UUID): MediaDetailUiState = coroutineScope {
		val item = api.userLibraryApi.getItem(itemId).content

		val isSeries = item.type == BaseItemKind.SERIES
		val isArtist = item.type == BaseItemKind.MUSIC_ARTIST
		val isPerson = item.type == BaseItemKind.PERSON
		val isBoxSet = item.type == BaseItemKind.BOX_SET

		val seasonsDeferred = if (isSeries) {
			async {
				runCatching {
					api.tvShowsApi.getSeasons(
						seriesId = item.id,
						fields = ItemRepository.browseFields,
					).content.items
				}.getOrDefault(emptyList())
			}
		} else null

		val nextUpDeferred = if (isSeries) {
			async {
				runCatching {
					api.tvShowsApi.getNextUp(
						seriesId = item.id,
						limit = 1,
						fields = ItemRepository.browseFields,
					).content.items.firstOrNull()
				}.getOrNull()
			}
		} else null

		val secondaryDeferred = async {
			runCatching {
				when {
					isArtist -> context.getString(R.string.lbl_discography) to api.itemsApi.getItems(
						albumArtistIds = setOf(item.id),
						includeItemTypes = setOf(BaseItemKind.MUSIC_ALBUM),
						recursive = true,
						sortBy = setOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME),
						sortOrder = setOf(SortOrder.DESCENDING, SortOrder.ASCENDING),
						fields = ItemRepository.browseFields,
						limit = 40,
					).content.items.map { mapCard(it, landscape = false, square = true) }

					isPerson -> context.getString(R.string.lbl_filmography) to api.itemsApi.getItems(
						personIds = setOf(item.id),
						includeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
						recursive = true,
						sortBy = setOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME),
						sortOrder = setOf(SortOrder.DESCENDING, SortOrder.ASCENDING),
						fields = ItemRepository.browseFields,
						limit = 36,
					).content.items.map { mapCard(it, landscape = false, square = false) }

					isBoxSet -> context.getString(R.string.lbl_collections) to api.itemsApi.getItems(
						parentId = item.id,
						sortBy = setOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME),
						sortOrder = setOf(SortOrder.ASCENDING, SortOrder.ASCENDING),
						fields = ItemRepository.browseFields,
						limit = 48,
					).content.items.map { mapCard(it, landscape = false, square = false) }

					else -> null to emptyList()
				}
			}.getOrDefault(null to emptyList())
		}

		val similarDeferred = if (!isPerson && !isArtist) {
			async {
				runCatching {
					api.libraryApi.getSimilarItems(
						itemId = item.id,
						limit = 16,
						fields = ItemRepository.browseFields,
					).content.items.map { mapCard(it, landscape = false, square = false) }
				}.getOrDefault(emptyList())
			}
		} else null

		val rawSeasons = seasonsDeferred?.await().orEmpty()
		val nextUpEpisode = nextUpDeferred?.await()

		val selectedSeasonId = when {
			nextUpEpisode?.seasonId != null && rawSeasons.any { it.id == nextUpEpisode.seasonId } -> nextUpEpisode.seasonId
			else -> rawSeasons.firstOrNull()?.id
		}

		val episodes = if (isSeries && selectedSeasonId != null) {
			runCatching {
				api.tvShowsApi.getEpisodes(
					seriesId = item.id,
					seasonId = selectedSeasonId,
					fields = ItemRepository.browseFields,
				).content.items.map { mapCard(it, landscape = true, square = false) }
			}.getOrDefault(emptyList())
		} else {
			emptyList()
		}

		val seasonTabs = rawSeasons.map { season ->
			DetailSeasonTab(
				id = season.id,
				title = season.name ?: context.getString(R.string.lbl_season_number, season.indexNumber ?: 1),
				seasonNumber = season.indexNumber,
			)
		}

		val (secondaryTitle, secondaryItems) = secondaryDeferred.await()
		val similarItems = similarDeferred?.await().orEmpty()

		val streams = item.mediaSources?.firstOrNull()?.mediaStreams.orEmpty()
		val audioTracks = streams
			.filter { it.type == MediaStreamType.AUDIO }
			.map { stream ->
				DetailTrackOption(
					index = stream.index,
					label = formatAudioLabel(stream),
					language = stream.language,
				)
			}
		val rawSubTracks = streams
			.filter { it.type == MediaStreamType.SUBTITLE }
			.map { stream ->
				DetailTrackOption(
					index = stream.index,
					label = formatSubtitleLabel(stream),
					language = stream.language,
				)
			}
		val subtitleTracks = if (rawSubTracks.isNotEmpty()) {
			listOf(
				DetailTrackOption(
					index = -1,
					label = context.getString(R.string.lbl_subtitles_off),
					language = "",
				)
			) + rawSubTracks
		} else {
			emptyList()
		}

		val source = item.mediaSources?.firstOrNull()
		val defaultAudioIndex = source?.defaultAudioStreamIndex ?: audioTracks.firstOrNull()?.index
		val defaultSubtitleIndex = source?.defaultSubtitleStreamIndex ?: -1

		val chapters = item.chapters.orEmpty().mapIndexed { idx, ch ->
			val chapterImgUrl = if (ch.imageTag != null) {
				api.imageApi.getItemImageUrl(
					itemId = item.id,
					imageType = ImageType.CHAPTER,
					tag = ch.imageTag,
					imageIndex = idx,
					maxWidth = 300,
					maxHeight = 169,
				)
			} else null
			DetailChapterItem(
				index = idx,
				name = ch.name ?: "Chapter ${idx + 1}",
				startPositionTicks = ch.startPositionTicks,
				timeLabel = TimeUtils.formatMillis(ch.startPositionTicks / 10_000L),
				imageUrl = chapterImgUrl,
			)
		}

		val people = item.people.orEmpty()
			.take(20)
			.map { person ->
				val url = person.primaryImageTag?.let { tag ->
					api.imageApi.getItemImageUrl(
						itemId = person.id,
						imageType = ImageType.PRIMARY,
						tag = tag,
						maxWidth = 200,
						maxHeight = 300,
					)
				}
				DetailPersonItem(
					id = person.id,
					name = person.name.orEmpty(),
					role = person.role?.takeIf { it.isNotBlank() } ?: person.type.toString(),
					imageUrl = url,
				)
			}

		val backdropUrl = (
			item.itemBackdropImages.firstOrNull()
				?: item.parentBackdropImages.firstOrNull()
				?: item.itemImages[ImageType.THUMB]
				?: item.itemImages[ImageType.PRIMARY]
			)?.getUrl(api, maxWidth = 640, maxHeight = 360)

		val posterSize = if (isArtist) 300 else 360
		val posterUrl = (
			item.itemImages[ImageType.PRIMARY]
				?: item.parentImages[ImageType.PRIMARY]
				?: item.itemImages[ImageType.THUMB]
			)?.getUrl(api, maxWidth = 260, maxHeight = posterSize)

		val logoUrl = (item.itemImages[ImageType.LOGO] ?: item.parentImages[ImageType.LOGO])
			?.getUrl(api, maxWidth = 420, maxHeight = 140)

		val playableTarget = when {
			isSeries -> nextUpEpisode ?: rawSeasons.firstOrNull()?.let { item } ?: item
			isPerson -> null
			else -> item
		}
		val resumeTicks = playableTarget?.userData?.playbackPositionTicks ?: 0L
		val resumeMs = (resumeTicks / 10_000L).coerceAtLeast(0L)
		val targetPlayed = playableTarget?.userData?.played == true
		val canResume = resumeMs > 10_000L && !targetPlayed

		val eyebrow = when (item.type) {
			BaseItemKind.EPISODE -> listOfNotNull(
				item.seriesName,
				item.parentIndexNumber?.let { s ->
					item.indexNumber?.let { e -> "T$s E$e" } ?: "T$s"
				},
			).joinToString(" · ").ifBlank { null }
			else -> item.taglines?.firstOrNull()?.takeIf { it.isNotBlank() }
				?: item.studios?.firstOrNull()?.name
		}

		val metadataChips = buildList {
			item.communityRating?.let { add("★ " + String.format("%.1f", it)) }
			item.criticRating?.let { add("${it.toInt()}%") }
			item.productionYear?.let { add(it.toString()) }
			item.officialRating?.takeIf { it.isNotBlank() }?.let { add(it) }
			item.runTimeTicks?.takeIf { it > 0L }?.let { add(TimeUtils.formatMillis(it / 10_000L)) }
			if (isSeries && rawSeasons.isNotEmpty()) {
				add("${rawSeasons.size} ${context.getString(R.string.lbl_seasons)}")
			}
		}

		val genresLine = item.genres?.take(4)?.joinToString(" · ")?.ifBlank { null }
		val hasTrailer = (item.localTrailerCount ?: 0) > 0 || !item.remoteTrailers.isNullOrEmpty()

		MediaDetailUiState(
			isLoading = false,
			item = item,
			title = item.name.orEmpty(),
			eyebrow = eyebrow,
			metadataChips = metadataChips,
			genresLine = genresLine,
			overview = item.overview,
			backdropUrl = backdropUrl,
			posterUrl = posterUrl,
			logoUrl = logoUrl,
			isFavorite = item.userData?.isFavorite == true,
			isPlayed = item.userData?.played == true,
			canResume = canResume,
			resumePositionMs = resumeMs,
			resumeLabel = if (canResume) {
				context.getString(R.string.lbl_resume_from, TimeUtils.formatMillis(resumeMs))
			} else null,
			hasTrailer = hasTrailer,
			playableTarget = playableTarget,
			audioTracks = audioTracks,
			selectedAudioIndex = defaultAudioIndex,
			subtitleTracks = subtitleTracks,
			selectedSubtitleIndex = defaultSubtitleIndex,
			seasons = seasonTabs,
			selectedSeasonId = selectedSeasonId,
			episodes = episodes,
			chapters = chapters,
			castAndCrew = people,
			secondaryItemsTitle = secondaryTitle,
			secondaryItems = secondaryItems,
			similarItems = similarItems,
		)
	}

	private fun mapCard(dto: BaseItemDto, landscape: Boolean, square: Boolean): DetailCardItem {
		val userData = dto.userData
		val progressFraction = userData?.playedPercentage?.toFloat()?.div(100f)?.coerceIn(0f, 1f)
			?: run {
				val total = dto.runTimeTicks ?: 0L
				val pos = userData?.playbackPositionTicks ?: 0L
				if (total > 0L && pos > 0L) (pos.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
			}

		val imgUrl = when {
			landscape -> (dto.itemImages[ImageType.PRIMARY] ?: dto.itemImages[ImageType.THUMB] ?: dto.itemBackdropImages.firstOrNull())
				?.getUrl(api, maxWidth = 320, maxHeight = 180)
			square -> (dto.itemImages[ImageType.PRIMARY] ?: dto.itemImages[ImageType.THUMB])
				?.getUrl(api, maxWidth = 220, maxHeight = 220)
			else -> (dto.itemImages[ImageType.PRIMARY] ?: dto.itemImages[ImageType.THUMB])
				?.getUrl(api, maxWidth = 220, maxHeight = 330)
		}

		val subtitle = when (dto.type) {
			BaseItemKind.EPISODE -> listOfNotNull(
				dto.indexNumber?.let { "E$it" },
				dto.runTimeTicks?.takeIf { it > 0L }?.let { TimeUtils.formatMillis(it / 10_000L) },
			).joinToString(" · ").ifBlank { null }
			else -> listOfNotNull(
				dto.productionYear?.toString(),
				dto.albumArtist,
			).joinToString(" · ").ifBlank { null }
		}

		return DetailCardItem(
			id = dto.id,
			dto = dto,
			title = dto.name.orEmpty(),
			subtitle = subtitle,
			imageUrl = imgUrl,
			progressFraction = progressFraction,
			isWatched = userData?.played == true,
			unplayedCount = userData?.unplayedItemCount,
			communityRating = dto.communityRating,
			isLandscape = landscape,
			isSquare = square,
		)
	}

	private fun formatAudioLabel(stream: MediaStream): String {
		val base = stream.displayTitle?.takeIf { it.isNotBlank() }
		if (base != null) return base
		val lang = stream.language?.uppercase() ?: "AUD"
		val codec = stream.codec?.uppercase()
		val channels = stream.channels?.let { "${it}ch" }
		return listOfNotNull(lang, codec, channels).joinToString(" ")
	}

	private fun formatSubtitleLabel(stream: MediaStream): String {
		val base = stream.displayTitle?.takeIf { it.isNotBlank() }
		if (base != null) return base
		val lang = stream.language?.uppercase() ?: "SUB"
		val codec = stream.codec?.uppercase()
		return listOfNotNull(lang, codec).joinToString(" ")
	}
}
