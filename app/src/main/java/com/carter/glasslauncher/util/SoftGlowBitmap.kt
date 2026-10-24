package com.carter.glasslauncher.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * A reusable soft white->transparent radial gradient bitmap. Tinted per-use via
 * PorterDuff.Mode.SRC_IN color filters (solid color masked by this bitmap's alpha gradient),
 * so the same bitmap backs every background glow and every tile focus glow -- no per-frame
 * drawing work, just a cheap color filter swap and alpha/scale animation.
 */
object SoftGlowBitmap {

    @Volatile
    private var cached: Bitmap? = null

    fun get(sizePx: Int = 512): Bitmap {
        cached?.let { if (it.width == sizePx) return it }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = sizePx / 2f
        val shader = RadialGradient(
            radius, radius, radius,
            // Tighter falloff than a plain 50% midpoint: concentrates the light into a
            // bright core so a few glows can carry the whole scene over a dark base.
            intArrayOf(0xFFFFFFFF.toInt(), 0xB3FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.32f, 1f),
            Shader.TileMode.CLAMP
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawCircle(radius, radius, radius, paint)
        cached = bitmap
        return bitmap
    }
}
