package org.jellyfin.androidtv.ui.playback

import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentAction
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.androidtv.util.sdk.end
import org.jellyfin.androidtv.util.sdk.start
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID

fun PlaybackController.getLiveTvChannel(
	id: UUID,
	callback: (channel: BaseItemDto) -> Unit,
) {
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getChannel(id).content
			}
		}.onSuccess { channel ->
			callback(channel)
		}
	}
}

@OptIn(UnstableApi::class)
fun PlaybackController.disableDefaultSubtitles() {
	Timber.i("Disabling non-baked subtitles")

	val trackSelector = mVideoManager?.mExoPlayer?.trackSelector ?: run {
		Timber.w("Skipping disableDefaultSubtitles: trackSelector unavailable")
		return
	}
	with(trackSelector) {
		parameters = parameters.buildUpon()
			.clearOverridesOfType(C.TRACK_TYPE_TEXT)
			.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
			.build()
	}
}

@OptIn(UnstableApi::class)
@JvmOverloads
fun PlaybackController.setSubtitleIndex(index: Int, force: Boolean = false) {
	val options = mCurrentOptions ?: return
	Timber.i("Switching subtitles from index ${options.subtitleStreamIndex} to $index")

	// Already using this subtitle index
	if (options.subtitleStreamIndex == index && !force) return

	val mediaSource = currentMediaSource
	val mediaStreams = mediaSource?.mediaStreams.orEmpty()

	// Save subtitle language preference for restoration after NextUp screen
	val videoQueueManager by fragment.inject<VideoQueueManager>()
	if (index == -1) {
		// Use empty string to indicate "subtitles explicitly disabled" vs null meaning "no preference"
		videoQueueManager.setLastPlayedSubtitleLanguageIsoCode("")
	} else {
		val stream = mediaStreams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == index }
		videoQueueManager.setLastPlayedSubtitleLanguageIsoCode(stream?.language)
	}

	// Disable subtitles
	if (index == -1) {
		options.subtitleStreamIndex = -1

		if (burningSubs) {
			Timber.i("Disabling subtitle baking cleanly")
			restartStreamForSubtitleChange(-1, false)
		} else {
			disableDefaultSubtitles()
		}
	} else if (burningSubs) {
		Timber.i("Restarting playback to switch from subtitle baking to index $index")
		val nextStream = mediaStreams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == index }
		val nextRequiresBake = nextStream?.deliveryMethod == SubtitleDeliveryMethod.ENCODE ||
			(currentStreamInfo != null && shouldBurnInSubtitles(currentStreamInfo.playMethod))
		restartStreamForSubtitleChange(index, nextRequiresBake)
	} else {
		if (mediaSource == null) {
			Timber.w("Failed to get current media source for subtitle selection")
			return
		}
		val stream = mediaStreams.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == index }
		if (stream == null) {
			Timber.w("Failed to find correct media stream")
			return setSubtitleIndex(-1)
		}

		val streamInfo = currentStreamInfo
		val requiresBake = stream.deliveryMethod == SubtitleDeliveryMethod.ENCODE ||
			(streamInfo != null && shouldBurnInSubtitles(streamInfo.playMethod))

		when {
			requiresBake -> {
				if (burningSubs && options.subtitleStreamIndex == index) {
					Timber.i("Subtitles already being baked for index $index, skipping restart")
					return
				}
				Timber.i("Restarting playback cleanly for subtitle baking (index=$index)")
				restartStreamForSubtitleChange(index, true)
			}

			stream.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL ||
				stream.deliveryMethod == SubtitleDeliveryMethod.EMBED ||
				stream.deliveryMethod == SubtitleDeliveryMethod.HLS -> {
				val exoPlayer = mVideoManager?.mExoPlayer ?: return
				val trackSelector = exoPlayer.trackSelector ?: return

				// External subtitles need to be resolved differently
				val group = if (stream.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL) {
					exoPlayer.currentTracks.groups.firstOrNull { group ->
						group.length == 1 && group.getTrackFormat(0).id?.endsWith(":JF_EXTERNAL:$index") == true
					}
				} else {
					val localIndex = mediaStreams
						.filter { it.type == MediaStreamType.SUBTITLE }
						.filter {
							if (stream.deliveryMethod == SubtitleDeliveryMethod.EMBED) {
								!it.isExternal
							} else {
								it.deliveryMethod == SubtitleDeliveryMethod.EMBED || it.deliveryMethod == SubtitleDeliveryMethod.HLS
							}
						}
						.indexOf(stream)
						.takeIf { it != -1 }

					if (localIndex == null) {
						Timber.w("Failed to find local subtitle index")
						return setSubtitleIndex(-1)
					}

					exoPlayer.currentTracks.groups
						.filter { it.type == C.TRACK_TYPE_TEXT }
						.filterNot { it.length == 1 && it.getTrackFormat(0).id?.contains(":JF_EXTERNAL:") == true }
						.getOrNull(localIndex)
				}?.mediaTrackGroup

				if (group == null) {
					Timber.w("Failed to find correct subtitle group for method ${stream.deliveryMethod}")
					return setSubtitleIndex(-1)
				}

				Timber.i("Enabling subtitle group $index via method ${stream.deliveryMethod}")
				options.subtitleStreamIndex = index
				with(trackSelector) {
					parameters = parameters.buildUpon()
						.clearOverridesOfType(C.TRACK_TYPE_TEXT)
						.addOverride(TrackSelectionOverride(group, 0))
						.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
						.build()
				}
			}

			stream.deliveryMethod == SubtitleDeliveryMethod.DROP || stream.deliveryMethod == null -> {
				Timber.i("Dropping subtitles")
				setSubtitleIndex(-1)
			}
		}
	}
}

fun PlaybackController.applyMediaSegments(
	item: BaseItemDto,
	callback: () -> Unit,
) {
	val mediaSegmentRepository by fragment.inject<MediaSegmentRepository>()

	fragment?.clearSkipOverlay()
	fragment.lifecycleScope.launch {
		val mediaSegments = runCatching {
			mediaSegmentRepository.getSegmentsForItem(item)
		}.getOrNull().orEmpty()

		for (mediaSegment in mediaSegments) {
			val action = mediaSegmentRepository.getMediaSegmentAction(mediaSegment)

			when (action) {
				MediaSegmentAction.SKIP -> addSkipAction(mediaSegment)
				MediaSegmentAction.ASK_TO_SKIP -> addAskToSkipAction(mediaSegment)
				MediaSegmentAction.NOTHING -> Unit
			}
		}

		callback()
	}
}

@OptIn(UnstableApi::class)
private fun PlaybackController.addSkipAction(mediaSegment: MediaSegmentDto) {
	mVideoManager.mExoPlayer
		.createMessage { _, _ ->
			// We can't seek directly on the ExoPlayer instance as not all media is seekable
			// the seek function in the PlaybackController checks this and optionally starts a transcode
			// at the requested position
			fragment.lifecycleScope.launch(Dispatchers.Main) {
				seek(mediaSegment.end.inWholeMilliseconds, true)
			}
		}
		// Segments at position 0 will never be hit by ExoPlayer so we need to add a minimum value
		.setPosition(mediaSegment.start.inWholeMilliseconds.coerceAtLeast(1))
		.setDeleteAfterDelivery(false)
		.send()
}

@OptIn(UnstableApi::class)
private fun PlaybackController.addAskToSkipAction(mediaSegment: MediaSegmentDto) {
	mVideoManager.mExoPlayer
		.createMessage { _, _ ->
			fragment?.askToSkip(mediaSegment.end)
		}
		// Segments at position 0 will never be hit by ExoPlayer so we need to add a minimum value
		.setPosition(mediaSegment.start.inWholeMilliseconds.coerceAtLeast(1))
		.setDeleteAfterDelivery(false)
		.send()
}
