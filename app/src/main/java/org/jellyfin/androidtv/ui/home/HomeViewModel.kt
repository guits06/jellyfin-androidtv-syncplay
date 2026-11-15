package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetRecommendedProgramsRequest
import org.jellyfin.sdk.model.api.request.GetRecordingsRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

@Immutable
data class HomeMediaItem(
	val id: UUID,
	val dto: BaseItemDto,
	val title: String,
	val subtitle: String?,
	val overview: String?,
	val cardImageUrl: String?,
	val backdropUrl: String?,
	val year: String?,
	val runtimeLabel: String?,
	val officialRating: String?,
	val communityRating: Float?,
	val progressFraction: Float?,
	val isWatched: Boolean,
	val unplayedCount: Int?,
	val isLandscapeCard: Boolean,
	val canResume: Boolean,
)

@Immutable
data class HomeSectionModel(
	val id: String,
	val title: String,
	val isLandscape: Boolean,
	val items: List<HomeMediaItem>,
)

@Immutable
data class HomeUiState(
	val isLoading: Boolean = true,
	val heroDefault: HomeMediaItem? = null,
	val sections: List<HomeSectionModel> = emptyList(),
	val error: String? = null,
)

class HomeViewModel(
	private val context: Context,
	private val api: ApiClient,
	private val userRepository: UserRepository,
	private val userViewsRepository: UserViewsRepository,
	private val userPreferences: UserPreferences,
	private val userSettingPreferences: UserSettingPreferences,
) : ViewModel() {
	private val _uiState = MutableStateFlow(HomeUiState())
	val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

	init {
		refresh()
	}

	fun refresh() {
		viewModelScope.launch {
			if (_uiState.value.sections.isEmpty()) {
				_uiState.value = _uiState.value.copy(isLoading = true, error = null)
			}
			runCatching {
				withContext(Dispatchers.IO) {
					loadHomeData()
				}
			}.onSuccess { loaded ->
				_uiState.value = loaded
			}.onFailure { err ->
				Timber.e(err, "Failed loading Compose Home sections")
				_uiState.value = _uiState.value.copy(
					isLoading = false,
					error = err.localizedMessage,
				)
			}
		}
	}

	private suspend fun loadHomeData(): HomeUiState = coroutineScope {
		val currentUser = userRepository.currentUser.filterNotNull().first()
		val views = userViewsRepository.views.first().toList()
		val sectionsOrder = userSettingPreferences.activeHomesections

		val tasks = sectionsOrder.map { section ->
			async {
				buildSectionsForType(
					section = section,
					views = views,
					excludes = currentUser.configuration?.latestItemsExcludes.orEmpty().toSet(),
					liveTvEnabled = currentUser.policy?.enableLiveTvAccess == true,
				)
			}
		}

		val sections = tasks.awaitAll().flatten().filter { it.items.isNotEmpty() }
		val heroCandidate = sections
			.firstNotNullOfOrNull { sec -> sec.items.firstOrNull { !it.backdropUrl.isNullOrBlank() && it.dto.type != BaseItemKind.COLLECTION_FOLDER } }
			?: sections.firstOrNull()?.items?.firstOrNull()

		HomeUiState(
			isLoading = false,
			heroDefault = heroCandidate,
			sections = sections,
			error = null,
		)
	}

	private suspend fun buildSectionsForType(
		section: HomeSectionType,
		views: List<BaseItemDto>,
		excludes: Set<UUID>,
		liveTvEnabled: Boolean,
	): List<HomeSectionModel> = when (section) {
		HomeSectionType.RESUME -> {
			val items = runCatching {
				api.itemsApi.getResumeItems(
					GetResumeItemsRequest(
						limit = 16,
						fields = ItemRepository.browseFields,
						imageTypeLimit = 1,
						enableTotalRecordCount = false,
						mediaTypes = listOf(MediaType.VIDEO),
						excludeItemTypes = setOf(BaseItemKind.AUDIO_BOOK),
					)
				).content.items
			}.getOrDefault(emptyList())
			listOf(
				HomeSectionModel(
					id = "resume_video",
					title = context.getString(R.string.lbl_continue_watching),
					isLandscape = true,
					items = items.map { mapItem(it, landscape = true) },
				)
			)
		}

		HomeSectionType.NEXT_UP -> {
			val maxDays = userPreferences[UserPreferences.homeNextUpMaxDays]
			val cutoff = maxDays.takeIf { it > 0 }?.let { LocalDateTime.now().minusDays(it.toLong()) }
			val items = runCatching {
				api.tvShowsApi.getNextUp(
					GetNextUpRequest(
						imageTypeLimit = 1,
						limit = 16,
						enableResumable = false,
						fields = ItemRepository.browseFields,
						nextUpDateCutoff = cutoff,
					)
				).content.items
			}.getOrDefault(emptyList())
			listOf(
				HomeSectionModel(
					id = "next_up",
					title = context.getString(R.string.lbl_next_up),
					isLandscape = true,
					items = items.map { mapItem(it, landscape = true) },
				)
			)
		}

		HomeSectionType.LATEST_MEDIA -> coroutineScope {
			val allowedViews = views.filterNot {
				it.collectionType in EXCLUDED_LATEST_COLLECTIONS || it.id in excludes
			}
			allowedViews.map { view ->
				async {
					val items = runCatching {
						api.userLibraryApi.getLatestMedia(
							GetLatestMediaRequest(
								fields = ItemRepository.browseFields,
								imageTypeLimit = 1,
								parentId = view.id,
								groupItems = true,
								limit = 16,
							)
						).content
					}.getOrDefault(emptyList())
					HomeSectionModel(
						id = "latest_${view.id}",
						title = context.getString(R.string.lbl_latest_in, view.name.orEmpty()),
						isLandscape = false,
						items = items.map { mapItem(it, landscape = false) },
					)
				}
			}.awaitAll()
		}

		HomeSectionType.LIBRARY_TILES_SMALL,
		HomeSectionType.LIBRARY_BUTTONS -> listOf(
			HomeSectionModel(
				id = "my_media",
				title = context.getString(R.string.lbl_my_media),
				isLandscape = true,
				items = views.map { mapItem(it, landscape = true) },
			)
		)

		HomeSectionType.RESUME_AUDIO -> {
			val items = runCatching {
				api.itemsApi.getResumeItems(
					GetResumeItemsRequest(
						limit = 20,
						fields = ItemRepository.browseFields,
						imageTypeLimit = 1,
						enableTotalRecordCount = false,
						mediaTypes = listOf(MediaType.AUDIO),
					)
				).content.items
			}.getOrDefault(emptyList())
			listOf(
				HomeSectionModel(
					id = "resume_audio",
					title = context.getString(R.string.continue_listening),
					isLandscape = true,
					items = items.map { mapItem(it, landscape = true) },
				)
			)
		}

		HomeSectionType.LIVE_TV -> if (!liveTvEnabled) emptyList() else {
			val items = runCatching {
				api.liveTvApi.getRecommendedPrograms(
					GetRecommendedProgramsRequest(
						isAiring = true,
						fields = ItemRepository.browseFields,
						imageTypeLimit = 1,
						enableTotalRecordCount = false,
						limit = 16,
					)
				).content.items
			}.getOrDefault(emptyList())
			listOf(
				HomeSectionModel(
					id = "live_tv",
					title = context.getString(R.string.lbl_on_now),
					isLandscape = true,
					items = items.map { mapItem(it, landscape = true) },
				)
			)
		}

		HomeSectionType.ACTIVE_RECORDINGS -> if (!liveTvEnabled) emptyList() else {
			val items = runCatching {
				api.liveTvApi.getRecordings(
					GetRecordingsRequest(
						fields = ItemRepository.browseFields,
						enableImages = true,
						limit = 16,
					)
				).content.items
			}.getOrDefault(emptyList())
			listOf(
				HomeSectionModel(
					id = "recordings",
					title = context.getString(R.string.lbl_recordings),
					isLandscape = true,
					items = items.map { mapItem(it, landscape = true) },
				)
			)
		}

		HomeSectionType.RESUME_BOOK,
		HomeSectionType.NONE -> emptyList()
	}

	private fun mapItem(dto: BaseItemDto, landscape: Boolean): HomeMediaItem {
		val images = dto.itemImages
		val backdrop = (dto.itemBackdropImages.firstOrNull()
			?: dto.parentBackdropImages.firstOrNull())
			?.getUrl(api, maxWidth = 640, maxHeight = 360)

		val isSquareMusic = !landscape && (
			dto.type == BaseItemKind.MUSIC_ALBUM ||
				dto.type == BaseItemKind.MUSIC_ARTIST ||
				dto.type == BaseItemKind.AUDIO
			)
		val cardImg = when {
			landscape -> (images[ImageType.THUMB]
				?: dto.itemBackdropImages.firstOrNull()
				?: dto.parentBackdropImages.firstOrNull()
				?: images[ImageType.PRIMARY])
				?.getUrl(api, maxWidth = 320, maxHeight = 180)
			isSquareMusic -> (images[ImageType.PRIMARY] ?: images[ImageType.THUMB])
				?.getUrl(api, maxWidth = 220, maxHeight = 220)
			else -> (images[ImageType.PRIMARY]
				?: images[ImageType.THUMB]
				?: dto.itemBackdropImages.firstOrNull())
				?.getUrl(api, maxWidth = 220, maxHeight = 330)
		}

		val title = when (dto.type) {
			BaseItemKind.EPISODE -> dto.seriesName ?: dto.name.orEmpty()
			else -> dto.name.orEmpty()
		}

		val subtitle = when (dto.type) {
			BaseItemKind.EPISODE -> {
				val season = dto.parentIndexNumber
				val ep = dto.indexNumber
				val code = if (season != null && ep != null) "T$season E$ep" else null
				listOfNotNull(code, dto.name).joinToString(" · ").takeIf { it.isNotBlank() }
			}
			else -> dto.productionYear?.toString()
		}

		val ticks = dto.runTimeTicks
		val runtimeLabel = ticks?.let { TimeUtils.formatMillis(it / 10_000L) }
		val playedPct = dto.userData?.playedPercentage?.toFloat()?.div(100f)
		val unplayed = dto.userData?.unplayedItemCount

		return HomeMediaItem(
			id = dto.id,
			dto = dto,
			title = title,
			subtitle = subtitle,
			overview = dto.overview,
			cardImageUrl = cardImg,
			backdropUrl = backdrop ?: cardImg,
			year = dto.productionYear?.toString(),
			runtimeLabel = runtimeLabel,
			officialRating = dto.officialRating,
			communityRating = dto.communityRating,
			progressFraction = playedPct,
			isWatched = dto.userData?.played == true,
			unplayedCount = unplayed,
			isLandscapeCard = landscape,
			canResume = (dto.userData?.playbackPositionTicks ?: 0L) > 0L,
		)
	}

	private companion object {
		val EXCLUDED_LATEST_COLLECTIONS = setOf(
			CollectionType.PLAYLISTS,
			CollectionType.LIVETV,
			CollectionType.BOXSETS,
			CollectionType.BOOKS,
		)
	}
}
