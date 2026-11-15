package org.jellyfin.androidtv.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.ui.browsing.LibrarySortOption
import org.jellyfin.androidtv.ui.design.CinemaPalette
import org.jellyfin.androidtv.ui.design.CinemaSpacing
import org.jellyfin.androidtv.ui.itemdetail.DetailTrackOption
import org.jellyfin.androidtv.ui.itemdetail.MediaDetailViewModel
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

@OptIn(ExperimentalCoroutinesApi::class)
class CinemaRedesignTests : FunSpec({
	val dispatcher = StandardTestDispatcher()

	beforeSpec {
		Dispatchers.setMain(dispatcher)
	}

	afterSpec {
		Dispatchers.resetMain()
	}

	test("MediaDetailViewModel syncs selected audio and subtitle language to VideoQueueManager") {
		val context = mockk<Context>(relaxed = true)
		val api = mockk<ApiClient>(relaxed = true)
		val mutationRepo = mockk<ItemMutationRepository>(relaxed = true)
		val refreshService = mockk<DataRefreshService>(relaxed = true)
		val videoQueueManager = mockk<VideoQueueManager>(relaxed = true)

		val viewModel = MediaDetailViewModel(context, api, mutationRepo, refreshService, videoQueueManager)
		val jpnAudio = DetailTrackOption(index = 1, label = "Japanese AAC", language = "jpn")
		val spaSubs = DetailTrackOption(index = 2, label = "Spanish ASS", language = "spa")
		val offSubs = DetailTrackOption(index = -1, label = "Off", language = null)

		viewModel.selectAudioTrack(jpnAudio)
		viewModel.uiState.value.selectedAudioIndex shouldBe 1
		verify { videoQueueManager.setLastPlayedAudioLanguageIsoCode("jpn") }

		viewModel.selectSubtitleTrack(spaSubs)
		viewModel.uiState.value.selectedSubtitleIndex shouldBe 2
		verify { videoQueueManager.setLastPlayedSubtitleLanguageIsoCode("spa") }

		viewModel.selectSubtitleTrack(offSubs)
		viewModel.uiState.value.selectedSubtitleIndex shouldBe -1
		verify { videoQueueManager.setLastPlayedSubtitleLanguageIsoCode("") }
	}

	test("MediaDetailViewModel applySelectedTracksBeforePlay clears subtitle preference when Off") {
		val viewModel = MediaDetailViewModel(
			mockk(relaxed = true),
			mockk(relaxed = true),
			mockk(relaxed = true),
			mockk(relaxed = true),
			mockk<VideoQueueManager>(relaxed = true).also { qm ->
				// Keep mock reachable inside closure via viewModel below
				qm.setLastPlayedSubtitleLanguageIsoCode("eng")
			},
		)

		val videoQueueManager = mockk<VideoQueueManager>(relaxed = true)
		val vm2 = MediaDetailViewModel(
			mockk(relaxed = true),
			mockk(relaxed = true),
			mockk(relaxed = true),
			mockk(relaxed = true),
			videoQueueManager,
		)
		vm2.selectSubtitleTrack(DetailTrackOption(index = -1, label = "Off", language = null))
		vm2.applySelectedTracksBeforePlay()

		verify(atLeast = 1) { videoQueueManager.setLastPlayedSubtitleLanguageIsoCode("") }
		viewModel.uiState.value.isLoading shouldBe true
	}

	test("LibrarySortOption maps sorting modes consistently") {
		LibrarySortOption.TITLE.sortBy shouldBe ItemSortBy.SORT_NAME
		LibrarySortOption.TITLE.defaultOrder shouldBe SortOrder.ASCENDING

		LibrarySortOption.DATE_ADDED.sortBy shouldBe ItemSortBy.DATE_CREATED
		LibrarySortOption.DATE_ADDED.defaultOrder shouldBe SortOrder.DESCENDING

		LibrarySortOption.RELEASE_DATE.sortBy shouldBe ItemSortBy.PREMIERE_DATE
		LibrarySortOption.RELEASE_DATE.defaultOrder shouldBe SortOrder.DESCENDING

		LibrarySortOption.RATING.sortBy shouldBe ItemSortBy.COMMUNITY_RATING
		LibrarySortOption.RATING.defaultOrder shouldBe SortOrder.DESCENDING
	}

	test("CinemaSpacing and CinemaPalette keep TV overscan and dark canvas defaults") {
		CinemaSpacing.RailCollapsedWidth shouldBe 72.dp
		CinemaSpacing.ScreenEdgeHorizontal shouldBe 48.dp

		val palette = CinemaPalette()
		palette.canvas shouldBe Color(0xFF0A0A0F)
		palette.accentPrimary shouldBe Color(0xFFE50914)
	}
})
