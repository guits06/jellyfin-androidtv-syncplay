package org.jellyfin.androidtv.util

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

data class AmbientPalette(
	val surfaceTint: Color,
	val accentTint: Color,
)

/**
 * JNI bridge to libcinefast.so (compiled with -O3 in C) for sub-millisecond
 * ambient palette extraction and micro-bitmap blurring without stressing Mali-G31.
 */
object CineFastNative {
	val DefaultAmbient = AmbientPalette(
		surfaceTint = Color(0xFF101018),
		accentTint = Color(0xFF28121A),
	)

	private val loaded: Boolean = runCatching {
		System.loadLibrary("cinefast")
		true
	}.onFailure { err ->
		Timber.w(err, "libcinefast native library unavailable, using fallback ambient palette")
	}.getOrDefault(false)

	private val paletteCache = LruCache<String, AmbientPalette>(128)
	private val ambientTextureCache = LruCache<String, ImageBitmap>(16)
	private val scrimTextureCache = LruCache<String, ImageBitmap>(4)

	fun getAmbientTexture(
		canvasColor: Color,
		ambient: AmbientPalette,
	): ImageBitmap? {
		if (!loaded) return null
		val cArgb = canvasColor.toArgb()
		val sArgb = ambient.surfaceTint.toArgb()
		val aArgb = ambient.accentTint.toArgb()
		val key = "${cArgb}_${sArgb}_${aArgb}"
		ambientTextureCache.get(key)?.let { return it }
		val bmp = Bitmap.createBitmap(96, 54, Bitmap.Config.ARGB_8888)
		return if (nativeRenderAmbientCanvas(bmp, cArgb, sArgb, aArgb)) {
			bmp.asImageBitmap().also { ambientTextureCache.put(key, it) }
		} else {
			bmp.recycle()
			null
		}
	}

	fun getHeroScrimTexture(
		canvasColor: Color,
		dimmed: Boolean,
	): ImageBitmap? {
		if (!loaded) return null
		val cArgb = canvasColor.toArgb()
		val key = "${cArgb}_${dimmed}"
		scrimTextureCache.get(key)?.let { return it }
		val bmp = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
		return if (nativeRenderHeroScrim(bmp, cArgb, dimmed)) {
			bmp.asImageBitmap().also { scrimTextureCache.put(key, it) }
		} else {
			bmp.recycle()
			null
		}
	}

	fun getCachedAmbient(key: String?): AmbientPalette? = key?.let { paletteCache.get(it) }

	fun extractAmbient(key: String, bitmap: Bitmap): AmbientPalette {
		paletteCache.get(key)?.let { return it }
		val extracted = if (!loaded || bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE) {
			DefaultAmbient
		} else {
			runCatching {
				val colors = nativeExtractAmbientColors(bitmap)
				if (colors.size >= 2) {
					AmbientPalette(
						surfaceTint = Color(colors[0]),
						accentTint = Color(colors[1]),
					)
				} else {
					DefaultAmbient
				}
			}.getOrDefault(DefaultAmbient)
		}
		paletteCache.put(key, extracted)
		return extracted
	}

	fun blurMicroBitmap(bitmap: Bitmap, radius: Int = 5): Boolean {
		if (!loaded || bitmap.isRecycled || bitmap.config != Bitmap.Config.ARGB_8888) return false
		return nativeBlurArgb8888InPlace(bitmap, radius)
	}

	@JvmStatic
	private external fun nativeExtractAmbientColors(bitmap: Bitmap): IntArray

	@JvmStatic
	private external fun nativeBlurArgb8888InPlace(bitmap: Bitmap, radius: Int): Boolean

	@JvmStatic
	private external fun nativeRenderAmbientCanvas(
		bitmap: Bitmap,
		canvasArgb: Int,
		surfaceArgb: Int,
		accentArgb: Int,
	): Boolean

	@JvmStatic
	private external fun nativeRenderHeroScrim(
		bitmap: Bitmap,
		canvasArgb: Int,
		dimmed: Boolean,
	): Boolean
}

/**
 * Computes an ambient M3 Expressive palette from an image URL using a 64x64 software sample
 * processed in C (libcinefast.so) in ~0.15ms without touching GPU textures.
 */
@Composable
fun rememberAmbientPalette(
	imageUrl: String?,
	fallback: AmbientPalette = CineFastNative.DefaultAmbient,
): AmbientPalette {
	val context = LocalContext.current
	var palette by remember(imageUrl) {
		mutableStateOf(CineFastNative.getCachedAmbient(imageUrl) ?: fallback)
	}

	LaunchedEffect(imageUrl) {
		if (imageUrl.isNullOrBlank()) {
			palette = fallback
			return@LaunchedEffect
		}
		CineFastNative.getCachedAmbient(imageUrl)?.let { cached ->
			palette = cached
			return@LaunchedEffect
		}
		val computed = withContext(Dispatchers.IO) {
			runCatching {
				val loader = SingletonImageLoader.get(context)
				val req = ImageRequest.Builder(context)
					.data(imageUrl)
					.size(64, 64)
					.allowHardware(false)
					.allowRgb565(true)
					.build()
				val result = loader.execute(req)
				if (result is SuccessResult) {
					val bmp = result.image.toBitmap(64, 64, Bitmap.Config.RGB_565)
					CineFastNative.extractAmbient(imageUrl, bmp)
				} else {
					fallback
				}
			}.getOrDefault(fallback)
		}
		palette = computed
	}

	return palette
}
