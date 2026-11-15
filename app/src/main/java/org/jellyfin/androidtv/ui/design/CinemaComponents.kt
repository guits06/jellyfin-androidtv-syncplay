package org.jellyfin.androidtv.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import org.jellyfin.sdk.model.api.GroupStateType
import java.util.Locale

@Composable
fun CinemaAsyncImage(
	url: String?,
	contentDescription: String?,
	modifier: Modifier = Modifier,
	contentScale: ContentScale = ContentScale.Crop,
	targetWidthDp: Dp = 320.dp,
	targetHeightDp: Dp = 180.dp,
	crossfadeMs: Int = 0,
	fallbackColor: Color = CinemaTheme.palette.surfaceElevated,
) {
	val context = LocalContext.current
	val density = LocalDensity.current
	val widthPx = with(density) { targetWidthDp.roundToPx().coerceAtLeast(64) }
	val heightPx = with(density) { targetHeightDp.roundToPx().coerceAtLeast(64) }

	val request = remember(url, widthPx, heightPx, crossfadeMs) {
		ImageRequest.Builder(context)
			.data(url)
			.size(widthPx, heightPx)
			.allowRgb565(true)
			.apply {
				if (crossfadeMs > 0) crossfade(crossfadeMs) else crossfade(false)
			}
			.build()
	}

	Box(modifier = modifier.background(fallbackColor)) {
		if (!url.isNullOrBlank()) {
			AsyncImage(
				model = request,
				contentDescription = contentDescription,
				contentScale = contentScale,
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}

@Composable
fun CinemaMediaCard(
	title: String,
	subtitle: String? = null,
	imageUrl: String?,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	onFocused: (() -> Unit)? = null,
	cardWidth: Dp = 236.dp,
	cardHeight: Dp = 133.dp,
	progressFraction: Float? = null,
	isWatched: Boolean = false,
	unplayedCount: Int? = null,
	communityRating: Float? = null,
	badgeText: String? = null,
) {
	val palette = CinemaTheme.palette
	var isFocused by remember { mutableStateOf(false) }
	val focusScale by animateFloatAsState(
		targetValue = if (isFocused) 1.06f else 1f,
		animationSpec = CinemaMotion.FocusSpring,
		label = "CardFocusScale",
	)

	Column(
		modifier = modifier
			.width(cardWidth)
			.graphicsLayer {
				scaleX = focusScale
				scaleY = focusScale
			},
	) {
		Surface(
			onClick = onClick,
			scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.98f),
			shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(14.dp)),
			colors = ClickableSurfaceDefaults.colors(
				containerColor = palette.surfaceElevated,
				focusedContainerColor = palette.surfaceBright,
			),
			border = ClickableSurfaceDefaults.border(
				border = Border(BorderStroke(1.dp, palette.borderSubtle)),
				focusedBorder = Border(BorderStroke(2.5.dp, palette.focusRing)),
			),
			modifier = Modifier
				.width(cardWidth)
				.height(cardHeight)
				.onFocusChanged { state ->
					val nowFocused = state.isFocused
					if (nowFocused && !isFocused) onFocused?.invoke()
					isFocused = nowFocused
				},
		) {
			Box(modifier = Modifier.fillMaxSize()) {
				CinemaAsyncImage(
					url = imageUrl,
					contentDescription = title,
					targetWidthDp = cardWidth * 1.15f,
					targetHeightDp = cardHeight * 1.15f,
					modifier = Modifier.fillMaxSize(),
				)

				// Subtle bottom gradient only drawn in GPU cache for badges & progress bar legibility
				Box(
					modifier = Modifier
						.fillMaxSize()
						.drawWithCache {
							val scrim = Brush.verticalGradient(
								0.48f to Color.Transparent,
								1.0f to Color(0xCC08080D),
							)
							onDrawWithContent {
								drawRect(scrim)
								drawContent()
							}
						}
				)

				CardOverlayBadges(
					isWatched = isWatched,
					unplayedCount = unplayedCount,
					communityRating = communityRating,
					badgeText = badgeText,
				)

				if (progressFraction != null && progressFraction > 0f) {
					val clamped = progressFraction.coerceIn(0f, 1f)
					Box(
						modifier = Modifier
							.align(Alignment.BottomCenter)
							.fillMaxWidth()
							.height(4.dp)
							.background(Color(0x66FFFFFF))
					) {
						Box(
							modifier = Modifier
								.fillMaxWidth(clamped)
								.fillMaxHeight()
								.background(palette.accentPrimary)
						)
					}
				}
			}
		}

		Spacer(modifier = Modifier.height(7.dp))

		Text(
			text = title,
			style = MaterialTheme.typography.titleMedium,
			color = if (isFocused) palette.textPrimary else palette.textPrimary.copy(alpha = 0.88f),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(horizontal = 2.dp),
		)

		if (!subtitle.isNullOrBlank()) {
			Text(
				text = subtitle,
				style = MaterialTheme.typography.bodyMedium,
				color = if (isFocused) palette.textSecondary else palette.textMuted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(horizontal = 2.dp),
			)
		}
	}
}

@Composable
private fun BoxScope.CardOverlayBadges(
	isWatched: Boolean,
	unplayedCount: Int?,
	communityRating: Float?,
	badgeText: String?,
) {
	val palette = CinemaTheme.palette

	if (!badgeText.isNullOrBlank()) {
		Box(
			modifier = Modifier
				.align(Alignment.TopStart)
				.padding(8.dp)
				.clip(RoundedCornerShape(6.dp))
				.background(Color(0xB80A0A0F))
				.padding(horizontal = 7.dp, vertical = 3.dp)
		) {
			Text(
				text = badgeText,
				style = MaterialTheme.typography.labelSmall,
				color = palette.accentSecondary,
			)
		}
	} else if (communityRating != null && communityRating > 0f) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(3.dp),
			modifier = Modifier
				.align(Alignment.TopStart)
				.padding(8.dp)
				.clip(RoundedCornerShape(6.dp))
				.background(Color(0xB80A0A0F))
				.padding(horizontal = 6.dp, vertical = 2.dp)
		) {
			Icon(
				imageVector = Icons.Rounded.Star,
				contentDescription = null,
				tint = palette.ratingGold,
				modifier = Modifier.size(12.dp),
			)
			Text(
				text = String.format(Locale.US, "%.1f", communityRating),
				style = MaterialTheme.typography.labelSmall,
				color = palette.textPrimary,
			)
		}
	}

	if (unplayedCount != null && unplayedCount > 0) {
		Box(
			modifier = Modifier
				.align(Alignment.TopEnd)
				.padding(8.dp)
				.clip(RoundedCornerShape(999.dp))
				.background(palette.accentPrimary)
				.padding(horizontal = 8.dp, vertical = 2.dp)
		) {
			Text(
				text = unplayedCount.toString(),
				style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
				color = Color.White,
			)
		}
	} else if (isWatched) {
		Box(
			modifier = Modifier
				.align(Alignment.TopEnd)
				.padding(8.dp)
				.size(22.dp)
				.clip(CircleShape)
				.background(palette.accentSecondary),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				imageVector = Icons.Rounded.Check,
				contentDescription = null,
				tint = Color(0xFF06131A),
				modifier = Modifier.size(14.dp),
			)
		}
	}
}

@Composable
fun CinemaActionButton(
	text: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	icon: ImageVector? = null,
	primary: Boolean = false,
	accentColor: Color? = null,
) {
	val palette = CinemaTheme.palette
	var isFocused by remember { mutableStateOf(false) }
	val scale by animateFloatAsState(
		targetValue = if (isFocused) 1.05f else 1f,
		animationSpec = CinemaMotion.PillSpring,
		label = "ActionBtnScale",
	)
	val baseContainer = when {
		accentColor != null -> accentColor
		primary -> palette.textPrimary
		else -> palette.surfaceElevated.copy(alpha = 0.92f)
	}
	val focusedContainer = when {
		accentColor != null -> accentColor
		primary -> Color.White
		else -> palette.surfaceBright
	}
	val baseContent = if (primary && accentColor == null) palette.canvas else palette.textPrimary

	Surface(
		onClick = onClick,
		scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.97f),
		shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(999.dp)),
		colors = ClickableSurfaceDefaults.colors(
			containerColor = baseContainer,
			contentColor = baseContent,
			focusedContainerColor = focusedContainer,
			focusedContentColor = baseContent,
		),
		border = ClickableSurfaceDefaults.border(
			border = Border(BorderStroke(1.dp, palette.borderSubtle)),
			focusedBorder = Border(BorderStroke(2.dp, if (primary) palette.accentPrimary else palette.focusRing)),
		),
		modifier = modifier
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.onFocusChanged { isFocused = it.isFocused },
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
		) {
			if (icon != null) {
				Icon(
					imageVector = icon,
					contentDescription = null,
					modifier = Modifier.size(18.dp),
				)
			}
			Text(
				text = text,
				style = MaterialTheme.typography.labelLarge,
				maxLines = 1,
			)
		}
	}
}

@Composable
fun CinemaFilterChip(
	text: String,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	icon: ImageVector? = null,
) {
	val palette = CinemaTheme.palette
	var isFocused by remember { mutableStateOf(false) }
	val scale by animateFloatAsState(
		targetValue = if (isFocused) 1.05f else 1f,
		animationSpec = CinemaMotion.PillSpring,
		label = "ChipScale",
	)

	Surface(
		onClick = onClick,
		scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.97f),
		shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(999.dp)),
		colors = ClickableSurfaceDefaults.colors(
			containerColor = if (selected) palette.accentSecondary.copy(alpha = 0.22f) else palette.surfaceElevated,
			contentColor = if (selected) palette.accentSecondary else palette.textSecondary,
			focusedContainerColor = if (selected) palette.accentSecondary else palette.surfaceBright,
			focusedContentColor = if (selected) Color(0xFF06131A) else palette.textPrimary,
		),
		border = ClickableSurfaceDefaults.border(
			border = Border(BorderStroke(1.dp, if (selected) palette.accentSecondary else palette.borderSubtle)),
			focusedBorder = Border(BorderStroke(2.dp, palette.focusRing)),
		),
		modifier = modifier
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.onFocusChanged { isFocused = it.isFocused },
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
		) {
			if (icon != null) {
				Icon(
					imageVector = icon,
					contentDescription = null,
					modifier = Modifier.size(15.dp),
				)
			}
			Text(
				text = text,
				style = MaterialTheme.typography.labelMedium,
				maxLines = 1,
			)
		}
	}
}

@Composable
fun SyncPlayStatusPill(
	groupName: String,
	groupState: GroupStateType,
	participantCount: Int,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val palette = CinemaTheme.palette
	val dotColor = when (groupState) {
		GroupStateType.PLAYING -> palette.syncPlayActive
		GroupStateType.WAITING -> palette.syncPlayWaiting
		else -> palette.accentSecondary
	}

	Surface(
		onClick = onClick,
		scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
		shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(999.dp)),
		colors = ClickableSurfaceDefaults.colors(
			containerColor = Color(0xCC161C24),
			focusedContainerColor = Color(0xFF212B38),
			contentColor = palette.textPrimary,
			focusedContentColor = palette.textPrimary,
		),
		border = ClickableSurfaceDefaults.border(
			border = Border(BorderStroke(1.dp, dotColor.copy(alpha = 0.7f))),
			focusedBorder = Border(BorderStroke(2.dp, palette.focusRing)),
		),
		modifier = modifier,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
		) {
			Box(
				modifier = Modifier
					.size(8.dp)
					.clip(CircleShape)
					.background(dotColor)
			)
			Icon(
				imageVector = Icons.Rounded.Sync,
				contentDescription = null,
				tint = dotColor,
				modifier = Modifier.size(16.dp),
			)
			Text(
				text = "$groupName · $participantCount",
				style = MaterialTheme.typography.labelLarge,
				maxLines = 1,
			)
		}
	}
}

@Composable
fun <T> CinemaSectionRow(
	title: String,
	items: List<T>,
	key: (T) -> Any,
	contentType: (T) -> Any? = { "media_card" },
	modifier: Modifier = Modifier,
	startPadding: Dp = 28.dp,
	itemSpacing: Dp = 14.dp,
	itemContent: @Composable (T) -> Unit,
) {
	val palette = CinemaTheme.palette
	Column(modifier = modifier.fillMaxWidth()) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			modifier = Modifier.padding(start = startPadding, bottom = 10.dp),
		) {
			Box(
				modifier = Modifier
					.width(4.dp)
					.height(18.dp)
					.clip(RoundedCornerShape(999.dp))
					.background(palette.accentPrimary)
			)
			Text(
				text = title,
				style = MaterialTheme.typography.headlineMedium,
				color = palette.textPrimary,
			)
		}

		LazyRow(
			contentPadding = PaddingValues(start = startPadding, end = 48.dp, top = 6.dp, bottom = 14.dp),
			horizontalArrangement = Arrangement.spacedBy(itemSpacing),
		) {
			items(
				items = items,
				key = key,
				contentType = contentType,
			) { item ->
				itemContent(item)
			}
		}
	}
}
