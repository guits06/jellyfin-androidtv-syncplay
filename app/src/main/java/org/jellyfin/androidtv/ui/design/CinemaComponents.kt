package org.jellyfin.androidtv.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.util.AmbientPalette
import org.jellyfin.sdk.model.api.GroupStateType
import java.util.Locale

private val CardShape = RoundedCornerShape(14.dp)
private val PillShape = RoundedCornerShape(999.dp)

@Composable
fun CinemaAsyncImage(
	url: String?,
	contentDescription: String?,
	modifier: Modifier = Modifier,
	contentScale: ContentScale = ContentScale.Crop,
	targetWidthDp: Dp = 240.dp,
	targetHeightDp: Dp = 135.dp,
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

/**
 * Zero-invalidation ambient background tinted cleanly using native C color extraction (libcinefast.so):
 * swaps settled ambient colors smoothly using two cached static draws without per-frame Shader rebuilding.
 */
@Composable
fun CinemaAmbientCanvas(
	ambient: AmbientPalette,
	modifier: Modifier = Modifier,
) {
	val palette = CinemaTheme.palette

	Box(
		modifier = modifier
			.background(palette.canvas)
			.drawWithCache {
				val base = ambient.surfaceTint
				val radial = Brush.radialGradient(
					colors = listOf(
						ambient.accentTint.copy(alpha = 0.68f),
						base.copy(alpha = 0.42f),
						Color.Transparent,
					),
					center = Offset(size.width * 0.78f, size.height * 0.18f),
					radius = size.width * 0.75f,
				)
				onDrawBehind {
					drawRect(radial)
				}
			}
	)
}

/**
 * Zero-recomposition TV focus card:
 * Focus state animates an [Animatable] that is read only inside [graphicsLayer] (scale) and
 * [drawWithContent] (focused ring + border), skipping both Composition and Layout during D-pad travel.
 */
@Composable
fun CinemaMediaCard(
	title: String,
	subtitle: String? = null,
	imageUrl: String?,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	onFocused: (() -> Unit)? = null,
	cardWidth: Dp = 224.dp,
	cardHeight: Dp = 126.dp,
	progressFraction: Float? = null,
	isWatched: Boolean = false,
	unplayedCount: Int? = null,
	communityRating: Float? = null,
	badgeText: String? = null,
) {
	val palette = CinemaTheme.palette
	val scope = rememberCoroutineScope()
	val focusAnim = remember { Animatable(0f) }
	val interactionSource = remember { MutableInteractionSource() }
	val subtleBorder = palette.borderSubtle
	val focusBorder = palette.focusRing

	Column(
		modifier = modifier
			.width(cardWidth)
			.graphicsLayer {
				val p = focusAnim.value
				val scale = 1f + (p * 0.055f)
				scaleX = scale
				scaleY = scale
			},
	) {
		Box(
			modifier = Modifier
				.width(cardWidth)
				.height(cardHeight)
				.clip(CardShape)
				.background(palette.surfaceElevated)
				.onFocusChanged { state ->
					val nowFocused = state.isFocused
					if (nowFocused) onFocused?.invoke()
					scope.launch {
						focusAnim.animateTo(
							targetValue = if (nowFocused) 1f else 0f,
							animationSpec = CinemaMotion.FocusSpring,
						)
					}
				}
				.clickable(
					interactionSource = interactionSource,
					indication = null,
					onClick = onClick,
				)
				.drawWithContent {
					drawContent()
					val p = focusAnim.value
					val corner = 14.dp.toPx()
					val strokeWidth = (1.dp + 1.6.dp * p).toPx()
					drawRoundRect(
						color = lerp(subtleBorder, focusBorder, p),
						cornerRadius = CornerRadius(corner, corner),
						style = Stroke(width = strokeWidth),
					)
				},
		) {
			CinemaAsyncImage(
				url = imageUrl,
				contentDescription = title,
				targetWidthDp = cardWidth,
				targetHeightDp = cardHeight,
				modifier = Modifier.fillMaxSize(),
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
						.background(Color(0x9908080D))
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

		Spacer(modifier = Modifier.height(6.dp))

		Text(
			text = title,
			style = MaterialTheme.typography.titleMedium,
			color = palette.textPrimary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(horizontal = 2.dp),
		)

		if (!subtitle.isNullOrBlank()) {
			Text(
				text = subtitle,
				style = MaterialTheme.typography.bodyMedium,
				color = palette.textMuted,
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
				.padding(7.dp)
				.clip(RoundedCornerShape(6.dp))
				.background(Color(0xCC0A0A0F))
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
				.padding(7.dp)
				.clip(RoundedCornerShape(6.dp))
				.background(Color(0xCC0A0A0F))
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
				.padding(7.dp)
				.clip(PillShape)
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
				.padding(7.dp)
				.size(21.dp)
				.clip(CircleShape)
				.background(palette.accentSecondary),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				imageVector = Icons.Rounded.Check,
				contentDescription = null,
				tint = Color(0xFF06131A),
				modifier = Modifier.size(13.dp),
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
	val scope = rememberCoroutineScope()
	val focusAnim = remember { Animatable(0f) }
	val interactionSource = remember { MutableInteractionSource() }

	val baseContainer = when {
		accentColor != null -> accentColor
		primary -> palette.accentPrimary.copy(alpha = 0.78f)
		else -> palette.surfaceElevated.copy(alpha = 0.94f)
	}
	val focusedContainer = when {
		accentColor != null -> accentColor
		primary -> palette.accentPrimary
		else -> palette.surfaceBright
	}
	val baseContent = palette.textPrimary
	val ringColor = palette.focusRing
	val subtleColor = palette.borderSubtle

	Box(
		modifier = modifier
			.graphicsLayer {
				val p = focusAnim.value
				val scale = 1f + p * 0.055f
				scaleX = scale
				scaleY = scale
			}
			.clip(PillShape)
			.drawBehind {
				val p = focusAnim.value
				drawRect(lerp(baseContainer, focusedContainer, p))
				drawRoundRect(
					color = lerp(subtleColor, ringColor, p),
					cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
					style = Stroke(width = (1.dp + 1.8.dp * p).toPx()),
				)
			}
			.onFocusChanged { state ->
				val focused = state.isFocused
				scope.launch {
					focusAnim.animateTo(if (focused) 1f else 0f, CinemaMotion.PillSpring)
				}
			}
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.padding(horizontal = 20.dp, vertical = 11.dp),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			if (icon != null) {
				Icon(
					imageVector = icon,
					contentDescription = null,
					tint = baseContent,
					modifier = Modifier.size(18.dp),
				)
			}
			Text(
				text = text,
				style = MaterialTheme.typography.labelLarge,
				color = baseContent,
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
	val scope = rememberCoroutineScope()
	val focusAnim = remember { Animatable(0f) }
	val interactionSource = remember { MutableInteractionSource() }

	val unfocusedBg = if (selected) palette.accentSecondary.copy(alpha = 0.20f) else palette.surfaceElevated
	val focusedBg = if (selected) palette.accentSecondary.copy(alpha = 0.42f) else palette.surfaceBright
	val unfocusedBorder = if (selected) palette.accentSecondary else palette.borderSubtle
	val focusRing = palette.focusRing
	val contentColor = if (selected) Color.White else palette.textPrimary

	Box(
		modifier = modifier
			.graphicsLayer {
				val p = focusAnim.value
				val scale = 1f + p * 0.045f
				scaleX = scale
				scaleY = scale
			}
			.clip(PillShape)
			.drawBehind {
				val p = focusAnim.value
				drawRect(lerp(unfocusedBg, focusedBg, p))
				drawRoundRect(
					color = lerp(unfocusedBorder, focusRing, p),
					cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
					style = Stroke(width = (1.dp + 1.2.dp * p).toPx()),
				)
			}
			.onFocusChanged { state ->
				val focused = state.isFocused
				scope.launch {
					focusAnim.animateTo(if (focused) 1f else 0f, CinemaMotion.PillSpring)
				}
			}
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.padding(horizontal = 14.dp, vertical = 8.dp),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			if (icon != null) {
				Icon(
					imageVector = icon,
					contentDescription = null,
					tint = contentColor,
					modifier = Modifier.size(15.dp),
				)
			}
			Text(
				text = text,
				style = MaterialTheme.typography.labelMedium,
				color = contentColor,
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
		shape = ClickableSurfaceDefaults.shape(shape = PillShape),
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
					.clip(PillShape)
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
