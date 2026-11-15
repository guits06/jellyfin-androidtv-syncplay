package org.jellyfin.androidtv.ui.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlaybackControllerContainer {
	private val _playbackController = MutableStateFlow<PlaybackController?>(null)
	val playbackControllerFlow: StateFlow<PlaybackController?> = _playbackController.asStateFlow()

	var playbackController: PlaybackController?
		get() = _playbackController.value
		set(value) {
			_playbackController.value = value
		}
}
