package org.jellyfin.androidtv.ui.design

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.design.Tokens

@Immutable
data class CinemaPalette(
	val canvas: Color = Color(0xFF0A0A0F),
	val surface: Color = Color(0xFF14141C),
	val surfaceElevated: Color = Color(0xFF1E1E2A),
	val surfaceBright: Color = Color(0xFF282838),
	val accentPrimary: Color = Color(0xFFE50914),
	val accentSecondary: Color = Tokens.Color.colorCyan400,
	val accentExpressive: Color = Tokens.Color.colorPurple400,
	val ratingGold: Color = Color(0xFFF5C518),
	val syncPlayActive: Color = Color(0xFF22C55E),
	val syncPlayWaiting: Color = Color(0xFFF59E0B),
	val focusRing: Color = Color(0xFFFFFFFF),
	val textPrimary: Color = Color(0xFFF5F5F8),
	val textSecondary: Color = Color(0xFFB3B8C6),
	val textMuted: Color = Color(0xFF838999),
	val borderSubtle: Color = Color(0x2EFFFFFF),
)

val LocalCinemaPalette = staticCompositionLocalOf { CinemaPalette() }

object CinemaMotion {
	val FocusSpring: AnimationSpec<Float> = spring(
		dampingRatio = 0.86f,
		stiffness = 1200f,
	)
	val PillSpring: AnimationSpec<Float> = spring(
		dampingRatio = 0.88f,
		stiffness = 1400f,
	)
}

object CinemaSpacing {
	val Xs = 4.dp
	val Sm = 8.dp
	val Md = 16.dp
	val Lg = 24.dp
	val Xl = 32.dp
	val ScreenEdgeHorizontal = 48.dp
	val RailCollapsedWidth = 72.dp
}

private val CinemaTvColorScheme = darkColorScheme(
	primary = Color(0xFFF5F5F8),
	onPrimary = Color(0xFF0A0A0F),
	primaryContainer = Color(0xFFE50914),
	onPrimaryContainer = Color(0xFFFFFFFF),
	secondary = Tokens.Color.colorCyan400,
	onSecondary = Color(0xFF06131A),
	secondaryContainer = Color(0xFF232533),
	onSecondaryContainer = Color(0xFFE6E9F2),
	tertiary = Tokens.Color.colorPurple400,
	onTertiary = Color(0xFF150629),
	background = Color(0xFF0A0A0F),
	onBackground = Color(0xFFF5F5F8),
	surface = Color(0xFF14141C),
	onSurface = Color(0xFFF5F5F8),
	surfaceVariant = Color(0xFF1E1E2A),
	onSurfaceVariant = Color(0xFFB3B8C6),
	border = Color(0x33FFFFFF),
	borderVariant = Color(0x1FFFFFFF),
	scrim = Color(0xCC06060A),
)

private val CinemaTvShapes = Shapes(
	extraSmall = RoundedCornerShape(8.dp),
	small = RoundedCornerShape(12.dp),
	medium = RoundedCornerShape(16.dp),
	large = RoundedCornerShape(22.dp),
	extraLarge = RoundedCornerShape(28.dp),
)

private val CinemaTvTypography = Typography(
	displayLarge = TextStyle(
		fontSize = 38.sp,
		lineHeight = 44.sp,
		fontWeight = FontWeight.ExtraBold,
		letterSpacing = (-0.5).sp,
	),
	displayMedium = TextStyle(
		fontSize = 30.sp,
		lineHeight = 36.sp,
		fontWeight = FontWeight.Bold,
		letterSpacing = (-0.25).sp,
	),
	headlineMedium = TextStyle(
		fontSize = 22.sp,
		lineHeight = 28.sp,
		fontWeight = FontWeight.Bold,
	),
	titleLarge = TextStyle(
		fontSize = 18.sp,
		lineHeight = 24.sp,
		fontWeight = FontWeight.SemiBold,
	),
	titleMedium = TextStyle(
		fontSize = 15.sp,
		lineHeight = 20.sp,
		fontWeight = FontWeight.SemiBold,
	),
	bodyLarge = TextStyle(
		fontSize = 15.sp,
		lineHeight = 21.sp,
		fontWeight = FontWeight.Normal,
	),
	bodyMedium = TextStyle(
		fontSize = 13.sp,
		lineHeight = 18.sp,
		fontWeight = FontWeight.Normal,
	),
	labelLarge = TextStyle(
		fontSize = 14.sp,
		lineHeight = 18.sp,
		fontWeight = FontWeight.SemiBold,
	),
	labelMedium = TextStyle(
		fontSize = 12.sp,
		lineHeight = 16.sp,
		fontWeight = FontWeight.Medium,
	),
	labelSmall = TextStyle(
		fontSize = 11.sp,
		lineHeight = 14.sp,
		fontWeight = FontWeight.Medium,
	),
)

@Composable
fun CinemaTheme(
	palette: CinemaPalette = CinemaTheme.palette,
	content: @Composable () -> Unit,
) {
	CompositionLocalProvider(LocalCinemaPalette provides palette) {
		MaterialTheme(
			colorScheme = CinemaTvColorScheme,
			shapes = CinemaTvShapes,
			typography = CinemaTvTypography,
		) {
			JellyfinTheme {
				content()
			}
		}
	}
}

object CinemaTheme {
	val palette: CinemaPalette
		@Composable
		@ReadOnlyComposable
		get() = LocalCinemaPalette.current
}
