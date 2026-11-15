package org.jellyfin.androidtv.ui.settings.compat

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.androidtv.ui.settings.Routes

class SettingsViewModel : ViewModel() {
	private val _visible = MutableStateFlow(false)
	val visible get() = _visible.asStateFlow()

	private val _initialRoute = MutableStateFlow(Routes.MAIN)
	val initialRoute get() = _initialRoute.asStateFlow()

	fun show(route: String = Routes.MAIN) {
		_initialRoute.value = route
		_visible.value = true
	}

	fun hide() {
		_visible.value = false
	}
}
