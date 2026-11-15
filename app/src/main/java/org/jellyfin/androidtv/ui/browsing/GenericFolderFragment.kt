package org.jellyfin.androidtv.ui.browsing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.sdk.model.api.BaseItemDto

class GenericFolderFragment : Fragment() {
	private var folder: BaseItemDto? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val folderJson = arguments?.getString(Extras.Folder)
		if (folderJson != null) {
			folder = Json.decodeFromString(BaseItemDto.serializer(), folderJson)
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val target = folder ?: return View(requireContext())
		return createLibraryGridComposeView(requireContext(), target, null)
	}
}
