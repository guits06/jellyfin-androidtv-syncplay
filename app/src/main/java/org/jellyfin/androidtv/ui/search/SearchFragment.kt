package org.jellyfin.androidtv.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.jellyfin.androidtv.ui.design.CinemaTheme
import org.koin.androidx.compose.koinViewModel

class SearchFragment : Fragment() {
	companion object {
		const val EXTRA_QUERY = "query"
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		CinemaTheme {
			val viewModel = koinViewModel<SearchViewModel>()
			SearchScreen(
				initialQuery = arguments?.getString(EXTRA_QUERY),
				viewModel = viewModel,
			)
		}
	}
}
