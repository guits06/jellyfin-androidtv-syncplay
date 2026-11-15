package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.ui.design.CinemaTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class HomeFragment : Fragment() {
	private val sessionRepository by inject<SessionRepository>()
	private val serverRepository by inject<ServerRepository>()
	private val notificationRepository by inject<NotificationsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val homeViewModel by viewModel<HomeViewModel>()
	private var lastObservedRefreshMs = 0L

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		CinemaTheme {
			HomeScreen(viewModel = homeViewModel)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		sessionRepository.currentSession
			.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
			.map { session ->
				if (session == null) null
				else serverRepository.getServer(session.serverId)
			}
			.onEach { server ->
				notificationRepository.updateServerNotifications(server)
			}
			.launchIn(viewLifecycleOwner.lifecycleScope)
	}

	override fun onResume() {
		super.onResume()
		val lastRefresh = maxOf(
			dataRefreshService.lastPlayback?.toEpochMilli() ?: 0L,
			dataRefreshService.lastFavoriteUpdate?.toEpochMilli() ?: 0L,
			dataRefreshService.lastLibraryChange?.toEpochMilli() ?: 0L,
			dataRefreshService.lastDeletedItemId?.hashCode()?.toLong() ?: 0L,
		)
		if (lastObservedRefreshMs != 0L && lastRefresh != lastObservedRefreshMs) {
			homeViewModel.refresh()
		}
		lastObservedRefreshMs = lastRefresh
	}
}
