package org.jellyfin.androidtv.ui.itemdetail

import android.content.Context
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.design.CinemaActionButton
import org.jellyfin.androidtv.ui.design.CinemaAmbientCanvas
import org.jellyfin.androidtv.ui.design.CinemaAsyncImage
import org.jellyfin.androidtv.ui.design.CinemaFilterChip
import org.jellyfin.androidtv.ui.design.CinemaMotion
import org.jellyfin.androidtv.ui.design.CinemaNavDestination
import org.jellyfin.androidtv.ui.design.CinemaNavigationRail
import org.jellyfin.androidtv.ui.design.CinemaTheme
import org.jellyfin.androidtv.ui.design.CinemaSpacing
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.rememberAmbientPalette
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.UUID

fun createAlbumDetailComposeView(
	context: Context,
	itemId: UUID?,
	favoritesParentId: UUID? = null,
	favoritesMode: Boolean = false,
): View = ComposeView(context).apply {
	setContent {
		CinemaTheme {
			val viewModel: AlbumDetailViewModel = koinViewModel()
			LaunchedEffect(itemId, favoritesParentId, favoritesMode) {
				if (favoritesMode) {
					viewModel.loadFavoriteSongs(favoritesParentId)
				} else if (itemId != null) {
					viewModel.loadAlbumOrPlaylist(itemId)
				}
			}
			AlbumDetailScreen(viewModel = viewModel)
		}
	}
}

@Composable
fun AlbumDetailScreen(
	viewModel: AlbumDetailViewModel = koinViewModel(),
) {
	val uiState by viewModel.uiState.collectAsState()
	val mediaManager = koinInject<MediaManager>()
	val navigationRepository = koinInject<NavigationRepository>()
	val playbackHelper = koinInject<PlaybackHelper>()
	val context = LocalContext.current
	val palette = CinemaTheme.palette

	var playingItemId by remember { mutableStateOf(mediaManager.currentAudioItem?.id) }
	var hasQueue by remember { mutableStateOf(mediaManager.hasAudioQueueItems()) }
	var focusedTrack by remember { mutableStateOf<AlbumTrackItem?>(null) }
	val playFocusRequester = remember { FocusRequester() }

	DisposableEffect(mediaManager) {
		val listener = object : AudioEventListener {
			override fun onPlaybackStateChange(newState: PlaybackController.PlaybackState, currentItem: BaseItemDto?) {
				playingItemId = if (newState == PlaybackController.PlaybackState.PLAYING) currentItem?.id else null
				hasQueue = mediaManager.hasAudioQueueItems()
			}

			override fun onQueueStatusChanged(queueChanged: Boolean) {
				hasQueue = mediaManager.hasAudioQueueItems()
			}
		}
		mediaManager.addAudioEventListener(listener)
		onDispose { mediaManager.removeAudioEventListener(listener) }
	}

	LaunchedEffect(uiState.isLoading) {
		if (!uiState.isLoading) {
			runCatching { playFocusRequester.requestFocus() }
		}
	}

	val ambientPalette = rememberAmbientPalette(uiState.coverUrl)

	Box(modifier = Modifier.fillMaxSize()) {
		CinemaAmbientCanvas(
			ambient = ambientPalette,
			modifier = Modifier.fillMaxSize(),
		)

		Box(modifier = Modifier.fillMaxSize()) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(28.dp),
				modifier = Modifier
					.fillMaxSize()
					.padding(start = CinemaSpacing.RailCollapsedWidth + 28.dp, end = 36.dp, top = 28.dp, bottom = 24.dp)
					.focusGroup(),
			) {
				// Left column: Album art + metadata + expressive action buttons
				Column(
					modifier = Modifier
						.width(340.dp)
						.fillMaxHeight(),
				) {
					Box(
						modifier = Modifier
							.size(216.dp)
							.clip(RoundedCornerShape(18.dp))
							.background(palette.surfaceElevated)
					) {
						if (!uiState.coverUrl.isNullOrBlank()) {
							CinemaAsyncImage(
								url = uiState.coverUrl,
								contentDescription = uiState.title,
								targetWidthDp = 216.dp,
								targetHeightDp = 216.dp,
								modifier = Modifier.fillMaxSize(),
							)
						} else {
							Icon(
								imageVector = Icons.Rounded.Album,
								contentDescription = null,
								tint = palette.textMuted,
								modifier = Modifier
									.size(64.dp)
									.align(Alignment.Center),
							)
						}
					}

					Spacer(modifier = Modifier.height(16.dp))

					Text(
						text = uiState.title,
						style = MaterialTheme.typography.headlineLarge,
						color = palette.textPrimary,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)

					if (!uiState.artistName.isNullOrBlank()) {
						Spacer(modifier = Modifier.height(8.dp))
						val artistId = uiState.artistId
						CinemaFilterChip(
							text = uiState.artistName!!,
							icon = Icons.Rounded.Person,
							selected = false,
							onClick = {
								if (artistId != null) {
									navigationRepository.navigate(Destinations.itemDetails(artistId))
								}
							},
						)
					}

					Spacer(modifier = Modifier.height(10.dp))

					val meta = listOfNotNull(
						uiState.yearLabel,
						uiState.tracks.takeIf { it.isNotEmpty() }?.let {
							stringResource(R.string.lbl_tracks_count, it.size)
						},
						uiState.totalDurationLabel,
					).joinToString(" · ")
					if (meta.isNotBlank()) {
						Text(
							text = meta,
							style = MaterialTheme.typography.bodyMedium,
							color = palette.textSecondary,
						)
					}
					if (!uiState.genresLabel.isNullOrBlank()) {
						Text(
							text = uiState.genresLabel!!,
							style = MaterialTheme.typography.labelMedium,
							color = palette.accentSecondary,
							maxLines = 1,
						)
					}

					if (!uiState.overview.isNullOrBlank()) {
						Spacer(modifier = Modifier.height(10.dp))
						Text(
							text = uiState.overview!!,
							style = MaterialTheme.typography.bodyMedium,
							color = palette.textMuted,
							maxLines = 3,
							overflow = TextOverflow.Ellipsis,
						)
					}

					Spacer(modifier = Modifier.height(18.dp))

					Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
						CinemaActionButton(
							text = stringResource(R.string.lbl_play_all),
							icon = Icons.Rounded.PlayArrow,
							primary = true,
							modifier = Modifier.focusRequester(playFocusRequester),
							onClick = {
								if (uiState.trackDtos.isNotEmpty()) {
									mediaManager.playNow(context, uiState.trackDtos, 0, false)
								}
							},
						)
						CinemaActionButton(
							text = stringResource(R.string.lbl_shuffle_short),
							icon = Icons.Rounded.Shuffle,
							primary = false,
							onClick = {
								if (uiState.trackDtos.isNotEmpty()) {
									mediaManager.playNow(context, uiState.trackDtos, 0, true)
								}
							},
						)
					}

					Spacer(modifier = Modifier.height(10.dp))

					Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
						CinemaFilterChip(
							text = stringResource(R.string.lbl_add_to_queue),
							icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
							selected = false,
							onClick = {
								if (uiState.trackDtos.isNotEmpty()) {
									mediaManager.addToAudioQueue(uiState.trackDtos)
								}
							},
						)

						if (!uiState.isFavoritesMode && uiState.item != null) {
							CinemaFilterChip(
								text = stringResource(R.string.lbl_favorite),
								icon = if (uiState.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
								selected = uiState.isFavorite,
								onClick = { viewModel.toggleFavorite() },
							)
						}
					}

					if (hasQueue) {
						Spacer(modifier = Modifier.height(10.dp))
						CinemaFilterChip(
							text = stringResource(R.string.lbl_now_playing),
							icon = Icons.Rounded.GraphicEq,
							selected = true,
							onClick = { navigationRepository.navigate(Destinations.nowPlaying) },
						)
					}
				}

				// Right column: Smooth tracklist + quick focus actions
				Column(
					modifier = Modifier
						.weight(1f)
						.fillMaxHeight()
						.focusGroup(),
				) {
					val selected = focusedTrack
					if (selected != null) {
						Row(
							horizontalArrangement = Arrangement.spacedBy(10.dp),
							verticalAlignment = Alignment.CenterVertically,
							modifier = Modifier
								.fillMaxWidth()
								.padding(bottom = 10.dp),
						) {
							CinemaFilterChip(
								text = stringResource(R.string.lbl_add_to_queue),
								icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
								selected = false,
								onClick = { mediaManager.queueAudioItem(selected.dto) },
							)
							CinemaFilterChip(
								text = stringResource(R.string.lbl_instant_mix),
								icon = Icons.Rounded.AutoAwesome,
								selected = false,
								onClick = { playbackHelper.playInstantMix(context, selected.dto) },
							)
						}
					}

					when {
						uiState.isLoading -> {
							Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
								repeat(7) {
									Box(
										modifier = Modifier
											.fillMaxWidth()
											.height(54.dp)
											.clip(RoundedCornerShape(12.dp))
											.background(palette.surfaceElevated)
									)
								}
							}
						}

						uiState.tracks.isEmpty() -> {
							Box(
								contentAlignment = Alignment.Center,
								modifier = Modifier.fillMaxSize(),
							) {
								Text(
									text = stringResource(R.string.lbl_no_tracks),
									style = MaterialTheme.typography.titleLarge,
									color = palette.textSecondary,
								)
							}
						}

						else -> {
							LazyColumn(
								contentPadding = PaddingValues(bottom = 28.dp),
								verticalArrangement = Arrangement.spacedBy(8.dp),
								modifier = Modifier.fillMaxSize(),
							) {
								itemsIndexed(
									items = uiState.tracks,
									key = { _, track -> track.id },
									contentType = { _, _ -> "album_track" },
								) { index, track ->
									AlbumTrackRow(
										track = track,
										isPlaying = track.id == playingItemId,
										onFocused = { focusedTrack = track },
										onClick = {
											mediaManager.playNow(context, uiState.trackDtos, index, false)
										},
									)
								}
							}
						}
					}
				}
			}

			CinemaNavigationRail(
				activeDestination = CinemaNavDestination.HOME,
				modifier = Modifier.align(Alignment.CenterStart),
			)
		}
	}
}

@Composable
private fun AlbumTrackRow(
	track: AlbumTrackItem,
	isPlaying: Boolean,
	onFocused: () -> Unit,
	onClick: () -> Unit,
) {
	val palette = CinemaTheme.palette
	val scope = rememberCoroutineScope()
	val focusAnim = remember { Animatable(0f) }
	val interactionSource = remember { MutableInteractionSource() }
	val shape = RoundedCornerShape(12.dp)
	val baseBg = if (isPlaying) palette.accentPrimary.copy(alpha = 0.16f) else palette.surfaceElevated.copy(alpha = 0.72f)
	val focusedBg = palette.surfaceBright
	val subtleBorder = if (isPlaying) palette.accentPrimary else palette.borderSubtle
	val focusBorder = palette.focusRing

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(14.dp),
		modifier = Modifier
			.fillMaxWidth()
			.graphicsLayer {
				val p = focusAnim.value
				val scale = 1f + p * 0.02f
				scaleX = scale
				scaleY = scale
			}
			.clip(shape)
			.drawBehind {
				val p = focusAnim.value
				drawRect(lerp(baseBg, focusedBg, p))
				drawRoundRect(
					color = lerp(subtleBorder, focusBorder, p),
					cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
					style = Stroke(width = (1.dp + 1.2.dp * p).toPx()),
				)
			}
			.onFocusChanged { state ->
				val focused = state.isFocused
				if (focused) onFocused()
				scope.launch {
					focusAnim.animateTo(if (focused) 1f else 0f, CinemaMotion.PillSpring)
				}
			}
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.padding(horizontal = 16.dp, vertical = 12.dp),
	) {
		if (isPlaying) {
			Icon(
				imageVector = Icons.Rounded.GraphicEq,
				contentDescription = null,
				tint = palette.accentPrimary,
				modifier = Modifier.size(22.dp),
			)
		} else {
			Text(
				text = track.indexLabel,
				style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
				color = palette.textMuted,
				modifier = Modifier.width(28.dp),
			)
		}

		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = track.title,
				style = MaterialTheme.typography.titleMedium,
				color = if (isPlaying) palette.accentPrimary else palette.textPrimary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (!track.artistLabel.isNullOrBlank()) {
				Text(
					text = track.artistLabel,
					style = MaterialTheme.typography.bodyMedium,
					color = palette.textSecondary,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}

		track.durationLabel?.let { dur ->
			Text(
				text = dur,
				style = MaterialTheme.typography.labelLarge,
				color = palette.textSecondary,
			)
		}
	}
}
