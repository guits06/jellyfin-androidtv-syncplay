package org.jellyfin.androidtv.ui.playback

import android.content.Context
import android.view.View
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.composable.LyricsDtoBox
import org.jellyfin.androidtv.ui.composable.modifier.fadingEdges
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.design.CinemaActionButton
import org.jellyfin.androidtv.ui.design.CinemaAmbientCanvas
import org.jellyfin.androidtv.ui.design.CinemaTheme
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.player.base.PlayerSeekbar
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.albumPrimaryImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.rememberAmbientPalette
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.lyrics.lyrics
import org.jellyfin.playback.jellyfin.lyrics.lyricsFlow
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject

fun createAudioNowPlayingComposeView(context: Context): View = ComposeView(context).apply {
	setContent {
		CinemaTheme {
			AudioNowPlayingScreen()
		}
	}
}

@Composable
fun AudioNowPlayingScreen() {
	val api = koinInject<ApiClient>()
	val mediaManager = koinInject<MediaManager>()
	val playbackManager = koinInject<PlaybackManager>()
	val navigationRepository = koinInject<NavigationRepository>()
	val context = LocalContext.current
	val palette = CinemaTheme.palette

	val currentEntry by rememberQueueEntry(playbackManager)
	val baseItemFromFlow = currentEntry?.run { baseItemFlow.collectAsState(baseItem).value }
	val playState by playbackManager.state.playState.collectAsState()
	val positionInfo by rememberPlayerPositionInfo(playbackManager)
	val lyrics = currentEntry?.run { lyricsFlow.collectAsState(lyrics).value }

	var stateVersion by remember { mutableIntStateOf(0) }
	var currentDto by remember(baseItemFromFlow) {
		mutableStateOf(baseItemFromFlow ?: mediaManager.currentAudioItem)
	}
	var repeatActive by remember { mutableStateOf(mediaManager.isRepeatMode) }
	var shuffleActive by remember { mutableStateOf(mediaManager.isShuffleMode) }
	var upcomingEntries by remember { mutableStateOf<List<QueueEntry>>(emptyList()) }

	DisposableEffect(mediaManager) {
		val listener = object : AudioEventListener {
			override fun onPlaybackStateChange(newState: PlaybackController.PlaybackState, item: BaseItemDto?) {
				if (item != null) currentDto = item
				repeatActive = mediaManager.isRepeatMode
				shuffleActive = mediaManager.isShuffleMode
				stateVersion++
			}

			override fun onQueueStatusChanged(hasQueue: Boolean) {
				stateVersion++
			}

			override fun onQueueReplaced() {
				stateVersion++
			}
		}
		mediaManager.addAudioEventListener(listener)
		onDispose { mediaManager.removeAudioEventListener(listener) }
	}

	LaunchedEffect(playbackManager) {
		playbackManager.queue.entry.onEach { stateVersion++ }.launchIn(this)
		playbackManager.queue.entries.onEach { stateVersion++ }.launchIn(this)
		playbackManager.state.playbackOrder.onEach {
			repeatActive = mediaManager.isRepeatMode
			shuffleActive = mediaManager.isShuffleMode
			stateVersion++
		}.launchIn(this)
	}

	LaunchedEffect(stateVersion, currentEntry) {
		upcomingEntries = playbackManager.queue.peekNext(24)
			.filter { it.baseItem != null }
	}

	val activeDto = currentDto ?: baseItemFromFlow ?: mediaManager.currentAudioItem
	val isPlaying = playState == PlayState.PLAYING || mediaManager.isPlayingAudio
	val coverImage = activeDto?.itemImages?.get(ImageType.PRIMARY)
		?: activeDto?.albumPrimaryImage
		?: activeDto?.parentImages?.get(ImageType.PRIMARY)
	val coverUrl = remember(coverImage) {
		coverImage?.getUrl(api, maxWidth = 420, maxHeight = 420)
	}
	val ambientUrl = remember(coverImage) {
		coverImage?.getUrl(api, maxWidth = 128, maxHeight = 128)
	}
	val ambientPalette = rememberAmbientPalette(ambientUrl)
	val playFocusRequester = remember { FocusRequester() }

	LaunchedEffect(Unit) {
		runCatching { playFocusRequester.requestFocus() }
	}

	Box(modifier = Modifier.fillMaxSize()) {
		CinemaAmbientCanvas(
			ambient = ambientPalette,
			modifier = Modifier.fillMaxSize(),
		)

		Row(
			modifier = Modifier
				.fillMaxSize()
				.padding(horizontal = 48.dp, vertical = 32.dp),
			horizontalArrangement = Arrangement.spacedBy(42.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(
				modifier = Modifier
					.weight(0.40f)
					.fillMaxHeight(),
				contentAlignment = Alignment.Center,
			) {
				Box(
					modifier = Modifier
						.size(340.dp)
						.drawBehind {
							drawCircle(
								brush = Brush.radialGradient(
									colors = listOf(
										ambientPalette.accentTint.copy(alpha = 0.55f),
										Color.Transparent,
									),
									center = Offset(size.width / 2f, size.height / 2f),
									radius = size.maxDimension * 0.72f,
								),
							)
						}
						.clip(RoundedCornerShape(24.dp))
						.background(palette.surface),
					contentAlignment = Alignment.Center,
				) {
					if (coverUrl != null) {
						AsyncImage(
							model = remember(coverUrl) {
								ImageRequest.Builder(context)
									.data(coverUrl)
									.size(420, 420)
									.allowRgb565(true)
									.crossfade(160)
									.build()
							},
							contentDescription = activeDto?.name,
							contentScale = ContentScale.Crop,
							alpha = if (lyrics != null) 0.22f else 1f,
							modifier = Modifier.fillMaxSize(),
						)
					} else if (lyrics == null) {
						Icon(
							painter = painterResource(R.drawable.ic_album),
							contentDescription = null,
							tint = palette.textMuted,
							modifier = Modifier.size(96.dp),
						)
					}

					if (lyrics != null) {
						LyricsDtoBox(
							lyricDto = lyrics,
							currentTimestamp = positionInfo.active,
							duration = positionInfo.duration,
							paused = !isPlaying,
							fontSize = 14.sp,
							color = Color.White,
							modifier = Modifier
								.fillMaxSize()
								.fadingEdges(vertical = 44.dp)
								.padding(horizontal = 18.dp, vertical = 14.dp),
						)
					}
				}
			}

			Column(
				modifier = Modifier
					.weight(0.60f)
					.fillMaxHeight(),
				verticalArrangement = Arrangement.Center,
			) {
				val genresText = activeDto?.genres?.take(3)?.joinToString(" · ")
				val queuePos = mediaManager.currentAudioQueuePosition
				val queueSize = mediaManager.currentAudioQueueSize
				val eyebrow = buildList {
					if (queuePos > 0 && queueSize > 0) add("$queuePos / $queueSize")
					activeDto?.productionYear?.let { add(it.toString()) }
					if (!genresText.isNullOrBlank()) add(genresText)
				}.joinToString(" · ")

				if (eyebrow.isNotBlank()) {
					Text(
						text = eyebrow.uppercase(),
						style = MaterialTheme.typography.labelLarge,
						color = palette.accentSecondary,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Spacer(Modifier.height(6.dp))
				}

				Text(
					text = activeDto?.name ?: stringResource(R.string.lbl_songs),
					style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
					color = palette.textPrimary,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)

				Spacer(Modifier.height(6.dp))

				val artistText = activeDto?.artists?.joinToString(", ")
					?: activeDto?.albumArtist
				val albumTitle = activeDto?.album
				val artistAndAlbum = listOfNotNull(
					artistText?.takeIf { it.isNotBlank() },
					albumTitle?.takeIf { it.isNotBlank() },
				).joinToString(" — ")
				if (artistAndAlbum.isNotBlank()) {
					Text(
						text = artistAndAlbum,
						style = MaterialTheme.typography.titleMedium,
						color = palette.textSecondary,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}

				Spacer(Modifier.height(18.dp))

				PlayerSeekbar(
					playbackManager = playbackManager,
					modifier = Modifier
						.fillMaxWidth()
						.height(14.dp),
				)
				Spacer(Modifier.height(6.dp))
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = TimeUtils.formatMillis(positionInfo.active.inWholeMilliseconds),
						style = MaterialTheme.typography.labelLarge,
						color = palette.textSecondary,
					)
					Text(
						text = TimeUtils.formatMillis(positionInfo.duration.inWholeMilliseconds),
						style = MaterialTheme.typography.labelLarge,
						color = palette.textSecondary,
					)
				}

				Spacer(Modifier.height(16.dp))

				Row(
					horizontalArrangement = Arrangement.spacedBy(10.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					TransportIconButton(
						iconRes = R.drawable.ic_previous,
						contentDescription = stringResource(R.string.lbl_prev_item),
						onClick = { mediaManager.prevAudioItem() },
					)
					TransportIconButton(
						iconRes = R.drawable.ic_rewind,
						contentDescription = stringResource(R.string.rewind),
						onClick = { mediaManager.rewind() },
					)
					CinemaActionButton(
						text = if (isPlaying) stringResource(R.string.lbl_pause) else stringResource(R.string.lbl_play),
						onClick = { mediaManager.togglePlayPause() },
						primary = true,
						icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
						modifier = Modifier.focusRequester(playFocusRequester),
					)
					TransportIconButton(
						iconRes = R.drawable.ic_fast_forward,
						contentDescription = stringResource(R.string.fast_forward),
						onClick = { mediaManager.fastForward() },
					)
					TransportIconButton(
						iconRes = R.drawable.ic_next,
						contentDescription = stringResource(R.string.lbl_next_item),
						onClick = { mediaManager.nextAudioItem() },
					)
					Spacer(Modifier.width(4.dp))
					TransportIconButton(
						iconRes = R.drawable.ic_loop,
						contentDescription = stringResource(R.string.lbl_repeat),
						selected = repeatActive,
						onClick = { repeatActive = mediaManager.toggleRepeat() },
					)
					TransportIconButton(
						iconRes = R.drawable.ic_shuffle,
						contentDescription = stringResource(R.string.lbl_shuffle_short),
						selected = shuffleActive,
						onClick = {
							mediaManager.shuffleAudioQueue()
							shuffleActive = mediaManager.isShuffleMode
							stateVersion++
						},
					)
					activeDto?.albumId?.let { albumId ->
						TransportIconButton(
							iconRes = R.drawable.ic_album,
							contentDescription = stringResource(R.string.lbl_open_album),
							onClick = { navigationRepository.navigate(Destinations.itemList(albumId)) },
						)
					}
					activeDto?.albumArtists?.firstOrNull()?.id?.let { artistId ->
						TransportIconButton(
							iconRes = R.drawable.ic_artist,
							contentDescription = stringResource(R.string.lbl_open_artist),
							onClick = { navigationRepository.navigate(Destinations.itemDetails(artistId)) },
						)
					}
				}

				if (upcomingEntries.isNotEmpty()) {
					Spacer(Modifier.height(22.dp))
					Text(
						text = stringResource(R.string.current_queue),
						style = MaterialTheme.typography.titleMedium,
						color = palette.textPrimary,
					)
					Spacer(Modifier.height(10.dp))
					LazyRow(
						horizontalArrangement = Arrangement.spacedBy(12.dp),
						contentPadding = PaddingValues(end = 24.dp),
					) {
						itemsIndexed(
							items = upcomingEntries,
							key = { idx, entry -> "${entry.baseItem?.id}_$idx" },
						) { _, entry ->
							val dto = entry.baseItem ?: return@itemsIndexed
							QueueTrackCard(
								dto = dto,
								api = api,
								onClick = { mediaManager.playFrom(entry) },
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun TransportIconButton(
	@DrawableRes iconRes: Int,
	contentDescription: String,
	onClick: () -> Unit,
	selected: Boolean = false,
	modifier: Modifier = Modifier,
) {
	val palette = CinemaTheme.palette
	val scope = rememberCoroutineScope()
	val focusFraction = remember { Animatable(0f) }

	val selectedTint = palette.accentSecondary
	val unselectedBg = palette.surfaceElevated.copy(alpha = 0.88f)
	val borderUnfocused = palette.borderSubtle
	val borderFocused = palette.accentPrimary

	Box(
		modifier = modifier
			.size(46.dp)
			.graphicsLayer {
				val f = focusFraction.value
				val s = 1f + 0.08f * f
				scaleX = s
				scaleY = s
			}
			.onFocusChanged { state ->
				val target = if (state.isFocused) 1f else 0f
				if (focusFraction.targetValue != target) {
					scope.launch {
						focusFraction.animateTo(
							targetValue = target,
							animationSpec = spring(
								dampingRatio = Spring.DampingRatioLowBouncy,
								stiffness = Spring.StiffnessMediumLow,
							),
						)
					}
				}
			}
			.focusable()
			.onKeyEvent { event ->
				if (event.type == KeyEventType.KeyUp &&
					(event.key == Key.Enter || event.key == Key.DirectionCenter || event.key == Key.NumPadEnter)
				) {
					onClick()
					true
				} else {
					false
				}
			}
			.drawBehind {
				val f = focusFraction.value
				val radius = size.minDimension / 2f
				val bgColor = when {
					f > 0.5f -> Color.White
					selected -> selectedTint.copy(alpha = 0.28f)
					else -> unselectedBg
				}
				drawCircle(color = bgColor, radius = radius)
				val borderColor = when {
					f > 0.1f -> borderFocused.copy(alpha = f)
					selected -> selectedTint
					else -> borderUnfocused
				}
				drawCircle(
					color = borderColor,
					radius = radius,
					style = Stroke(width = (1.5f + f * 1f).dp.toPx()),
				)
			},
		contentAlignment = Alignment.Center,
	) {
		val iconTint = if (focusFraction.value > 0.5f) {
			Color(0xFF0A0C12)
		} else if (selected) {
			palette.accentSecondary
		} else {
			palette.textPrimary
		}
		Icon(
			painter = painterResource(iconRes),
			contentDescription = contentDescription,
			tint = iconTint,
			modifier = Modifier.size(20.dp),
		)
	}
}

@Composable
private fun QueueTrackCard(
	dto: BaseItemDto,
	api: ApiClient,
	onClick: () -> Unit,
) {
	val palette = CinemaTheme.palette
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val focusFraction = remember { Animatable(0f) }
	val thumbUrl = remember(dto.id) {
		(dto.itemImages[ImageType.PRIMARY] ?: dto.albumPrimaryImage ?: dto.parentImages[ImageType.PRIMARY])
			?.getUrl(api, maxWidth = 140, maxHeight = 140)
	}

	Surface(
		onClick = onClick,
		shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(14.dp)),
		colors = ClickableSurfaceDefaults.colors(
			containerColor = palette.surface,
			focusedContainerColor = palette.surfaceElevated,
			contentColor = palette.textPrimary,
			focusedContentColor = palette.textPrimary,
		),
		border = ClickableSurfaceDefaults.border(
			border = Border(BorderStroke(1.dp, palette.borderSubtle)),
			focusedBorder = Border(BorderStroke(2.dp, palette.focusRing)),
		),
		modifier = Modifier
			.width(190.dp)
			.graphicsLayer {
				val f = focusFraction.value
				val s = 1f + 0.05f * f
				scaleX = s
				scaleY = s
			}
			.onFocusChanged { state ->
				val target = if (state.isFocused) 1f else 0f
				if (focusFraction.targetValue != target) {
					scope.launch {
						focusFraction.animateTo(
							targetValue = target,
							animationSpec = spring(
								dampingRatio = Spring.DampingRatioLowBouncy,
								stiffness = Spring.StiffnessMediumLow,
							),
						)
					}
				}
			},
	) {
		Row(
			modifier = Modifier.padding(8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Box(
				modifier = Modifier
					.size(44.dp)
					.clip(RoundedCornerShape(8.dp))
					.background(palette.surfaceElevated),
				contentAlignment = Alignment.Center,
			) {
				if (thumbUrl != null) {
					AsyncImage(
						model = remember(thumbUrl) {
							ImageRequest.Builder(context)
								.data(thumbUrl)
								.size(140, 140)
								.allowRgb565(true)
								.build()
						},
						contentDescription = dto.name,
						contentScale = ContentScale.Crop,
						modifier = Modifier.fillMaxSize(),
					)
				} else {
					Icon(
						painter = painterResource(R.drawable.ic_album),
						contentDescription = null,
						tint = palette.textMuted,
						modifier = Modifier.size(20.dp),
					)
				}
			}

			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = dto.name.orEmpty(),
					style = MaterialTheme.typography.labelLarge,
					color = palette.textPrimary,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				val sub = dto.artists?.firstOrNull() ?: dto.album
				if (!sub.isNullOrBlank()) {
					Text(
						text = sub,
						style = MaterialTheme.typography.bodySmall,
						color = palette.textSecondary,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}
