package com.carter.glasslauncher.util

import android.graphics.Bitmap
import android.os.Build
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

fun Drawable.toBitmapOrNull(maxSize: Int = 128): Bitmap? {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val w = intrinsicWidth.coerceIn(1, maxSize)
    val h = intrinsicHeight.coerceIn(1, maxSize)
    return try {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bmp
    } catch (e: Exception) {
        null
    }
}

/** Blend [color] toward white/black by [ratio] (0 = original color, 1 = target). */
fun blendColor(color: Int, ratio: Float, towardWhite: Boolean): Int {
    val target = if (towardWhite) Color.WHITE else Color.BLACK
    val r = (Color.red(color) + (Color.red(target) - Color.red(color)) * ratio).roundToInt()
    val g = (Color.green(color) + (Color.green(target) - Color.green(color)) * ratio).roundToInt()
    val b = (Color.blue(color) + (Color.blue(target) - Color.blue(color)) * ratio).roundToInt()
    return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
}

/** Same colour at a different opacity. */
fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

/**
 * A pixel-readable copy of this bitmap.
 *
 * Coil hands back Config.HARDWARE bitmaps by default on API 26+, and those throw from
 * getPixels() and from androidx.palette. That failure was being swallowed by a runCatching,
 * which silently stopped the background ever picking up a featured item's colour.
 */
fun Bitmap.toSoftware(): Bitmap =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE) {
        copy(Bitmap.Config.ARGB_8888, false) ?: this
    } else {
        this
    }
