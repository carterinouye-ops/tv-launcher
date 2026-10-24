package com.carter.glasslauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.carter.glasslauncher.R
import com.carter.glasslauncher.util.PaletteUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-bleed photographic wallpaper that rotates on a slow timer, Apple-TV style.
 *
 * Two ImageViews are crossfaded so a change never flashes. Each wallpaper's dominant colour
 * is pulled out with Palette and pushed into a translucent tint layer, so the glass panels
 * and the overall cast of the screen pick up the photo rather than sitting on a fixed hue.
 *
 * Bitmaps are decoded off the main thread at the panel's own resolution and the outgoing
 * one is released, so only two full-screen bitmaps are ever resident.
 */
class WallpaperController(
    private val context: Context,
    private val top: ImageView,
    private val bottom: ImageView,
    private val tint: View,
    private val crossfadeMs: Long = 1200L,
    private val rotateEveryMs: Long = 90_000L
) {
    /** Fired on the main thread whenever a new wallpaper is on screen. */
    var onWallpaperReady: ((Bitmap) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    private var index = -1
    private var topIsFront = false
    private var currentTint = Color.TRANSPARENT

    private val rotate = object : Runnable {
        override fun run() {
            next()
            handler.postDelayed(this, rotateEveryMs)
        }
    }

    fun start() {
        // The wash lives in the bitmap now; the view is kept only so callers' bindings
        // still resolve.
        tint.visibility = View.GONE
        if (index < 0) next()
        handler.removeCallbacks(rotate)
        // Nothing to rotate to with a single wallpaper, and crossfading an image onto
        // itself every 90s would just be a visible flicker for no reason.
        if (WALLPAPERS.size > 1) handler.postDelayed(rotate, rotateEveryMs)
    }

    fun stop() {
        handler.removeCallbacks(rotate)
    }

    fun release() {
        stop()
        job?.cancel()
        scope.cancel()
    }

    private fun next() {
        index = (index + 1) % WALLPAPERS.size
        val res = WALLPAPERS[index]
        job?.cancel()
        job = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(res) } ?: return@launch
            val primary = withContext(Dispatchers.Default) {
                runCatching { PaletteUtils.extractAccentColor(bitmap) }.getOrNull()
            } ?: Color.TRANSPARENT

            // The colour wash is painted into the bitmap once rather than laid over the
            // screen as another view. As a separate layer it was a full-screen blend on
            // every single frame, which on this GPU is among the most expensive things the
            // launcher can do, and it never changes between frames anyway.
            withContext(Dispatchers.Default) {
                val wash = tintFor(primary)
                if (wash != Color.TRANSPARENT) {
                    runCatching { Canvas(bitmap).drawColor(wash) }
                }
            }
            apply(bitmap)
        }
    }

    /** Decoded mutable so the colour wash can be painted straight into it. */
    private fun decode(@DrawableRes res: Int): Bitmap? {
        val metrics = context.resources.displayMetrics
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, res, bounds)
        var sample = 1
        while (bounds.outWidth / sample > metrics.widthPixels * 2) sample *= 2
        return runCatching {
            BitmapFactory.decodeResource(
                context.resources, res,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable = true
                }
            )
        }.getOrNull()
    }

    private fun apply(bitmap: Bitmap) {
        val incoming = if (topIsFront) bottom else top
        val outgoing = if (topIsFront) top else bottom
        topIsFront = !topIsFront

        incoming.setImageBitmap(bitmap)
        incoming.alpha = 0f
        incoming.animate().alpha(1f).setDuration(crossfadeMs).start()
        outgoing.animate().alpha(0f).setDuration(crossfadeMs).withEndAction {
            outgoing.setImageDrawable(null)
        }.start()

        onWallpaperReady?.invoke(bitmap)

    }

    /** A low-alpha wash of the photo's dominant colour; never enough to muddy the image. */
    private fun tintFor(primary: Int): Int {
        if (primary == Color.TRANSPARENT) return Color.TRANSPARENT
        val hsv = FloatArray(3)
        Color.colorToHSV(primary, hsv)
        hsv[1] = (hsv[1] * 0.9f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * 0.7f).coerceIn(0f, 1f)
        return Color.HSVToColor(TINT_ALPHA, hsv)
    }

    companion object {
        private const val TINT_ALPHA = 46 // ~18%

        /**
         * macOS "Golden Gate" (day). The other wallpapers are still bundled -- add them
         * back to this array to restore the slow rotation.
         */
        private val WALLPAPERS = intArrayOf(
            R.drawable.wallpaper_goldengate
        )
    }
}
