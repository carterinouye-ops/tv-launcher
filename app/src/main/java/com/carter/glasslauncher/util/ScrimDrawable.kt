package com.carter.glasslauncher.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Keeps the top bar and the dock legible over any wallpaper.
 *
 * One gradient with four stops rather than two stacked layer-list gradients. The pair was
 * two full-screen blends every frame, and at 1080p on this GPU full-screen blends are the
 * single most expensive thing on the screen.
 */
class ScrimDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.height() <= 0) return
        paint.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            intArrayOf(TOP, MID_CLEAR, MID_CLEAR, BOTTOM),
            floatArrayOf(0f, 0.28f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val TOP = 0xA6000000.toInt()
        const val MID_CLEAR = 0x00000000
        const val BOTTOM = 0xC2000000.toInt()
    }
}
