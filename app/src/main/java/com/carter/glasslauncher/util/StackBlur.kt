package com.carter.glasslauncher.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/**
 * Pre-blurs bitmaps entirely in software. Fire OS 8 on this device runs Android 11 (API 30),
 * which has no RenderEffect (API 31+) and no guaranteed RenderScript, so we approximate a
 * heavy gaussian-style blur by:
 *  1) downscaling aggressively (most of the visual "blur" cost is free from this alone),
 *  2) running a cheap multi-pass box blur (three passes approximates gaussian) on the small
 *     bitmap,
 *  3) darkening it so text/pills stay legible,
 *  4) letting the caller upscale when drawing (bilinear filtering smooths the blocky edges).
 *
 * This keeps the whole operation fast enough to run off the UI thread once per featured item
 * and then be cached by the caller.
 */
object StackBlur {

    /**
     * @param downscale factor to shrink by before blurring (bigger = faster + blurrier).
     * @param passes number of box-blur passes; 3 approximates a gaussian blur well.
     * @param darkenAlpha 0..255 black overlay strength so foreground UI stays legible.
     */
    fun blurAndDarken(
        source: Bitmap,
        downscale: Int = 10,
        passes: Int = 3,
        radius: Int = 3,
        darkenAlpha: Int = 120
    ): Bitmap {
        val readable = source.toSoftware()
        val smallW = max(1, readable.width / downscale)
        val smallH = max(1, readable.height / downscale)
        val small = Bitmap.createScaledBitmap(readable, smallW, smallH, true)

        val pixels = IntArray(smallW * smallH)
        small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH)

        var current = pixels
        repeat(passes) {
            current = boxBlurHorizontal(current, smallW, smallH, radius)
            current = boxBlurVertical(current, smallW, smallH, radius)
        }

        val blurred = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        blurred.setPixels(current, 0, smallW, 0, 0, smallW, smallH)

        if (small != blurred) small.recycle()

        if (darkenAlpha <= 0) return blurred
        val darkened = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(darkened)
        canvas.drawBitmap(blurred, 0f, 0f, null)
        val paint = Paint().apply { color = Color.argb(darkenAlpha, 0, 0, 0) }
        canvas.drawRect(0f, 0f, smallW.toFloat(), smallH.toFloat(), paint)
        blurred.recycle()
        return darkened
    }

    private fun boxBlurHorizontal(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0) return pixels
        val out = IntArray(pixels.size)
        for (y in 0 until h) {
            val rowStart = y * w
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var a = 0; var count = 0
                val from = max(0, x - radius)
                val to = min(w - 1, x + radius)
                for (nx in from..to) {
                    val p = pixels[rowStart + nx]
                    a += (p ushr 24) and 0xFF
                    r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                    count++
                }
                out[rowStart + x] = (((a / count) and 0xFF) shl 24) or
                    (((r / count) and 0xFF) shl 16) or
                    (((g / count) and 0xFF) shl 8) or
                    ((b / count) and 0xFF)
            }
        }
        return out
    }

    private fun boxBlurVertical(pixels: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0) return pixels
        val out = IntArray(pixels.size)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var r = 0; var g = 0; var b = 0; var a = 0; var count = 0
                val from = max(0, y - radius)
                val to = min(h - 1, y + radius)
                for (ny in from..to) {
                    val p = pixels[ny * w + x]
                    a += (p ushr 24) and 0xFF
                    r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                    count++
                }
                out[y * w + x] = (((a / count) and 0xFF) shl 24) or
                    (((r / count) and 0xFF) shl 16) or
                    (((g / count) and 0xFF) shl 8) or
                    ((b / count) and 0xFF)
            }
        }
        return out
    }
}
