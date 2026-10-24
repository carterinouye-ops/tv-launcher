package com.carter.glasslauncher.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette

/** 2-3 colors driving the ambient blobs (fully opaque; the view carries the alpha). */
data class GlowPalette(val colors: List<Int>) {
    companion object {
        /** Default ambient mood: electric blue, violet, cyan over deep navy. */
        val AMBIENT = GlowPalette(
            listOf(
                Color.parseColor("#2B6BFF"),
                Color.parseColor("#7A3CFF"),
                Color.parseColor("#18C8FF")
            )
        )
    }
}

object PaletteUtils {

    fun extractGlowPalette(bitmap: Bitmap): GlowPalette {
        return try {
            val palette = Palette.from(bitmap.toSoftware()).maximumColorCount(24).generate()
            val candidates = listOfNotNull(
                palette.vibrantSwatch?.rgb,
                palette.lightVibrantSwatch?.rgb,
                palette.dominantSwatch?.rgb,
                palette.darkVibrantSwatch?.rgb,
                palette.mutedSwatch?.rgb
            ).distinct()
            when {
                candidates.isEmpty() -> GlowPalette.AMBIENT
                candidates.size < 3 -> GlowPalette(
                    (candidates + GlowPalette.AMBIENT.colors).take(3)
                )
                else -> GlowPalette(candidates.take(3))
            }
        } catch (e: Exception) {
            GlowPalette.AMBIENT
        }
    }

    /** A single strong accent color, used for tile gradients/glow and progress bars. */
    fun extractAccentColor(bitmap: Bitmap): Int {
        return try {
            val palette = Palette.from(bitmap.toSoftware()).generate()
            palette.vibrantSwatch?.rgb
                ?: palette.dominantSwatch?.rgb
                ?: palette.lightVibrantSwatch?.rgb
                ?: palette.darkVibrantSwatch?.rgb
                ?: GlowPalette.AMBIENT.colors.first()
        } catch (e: Exception) {
            GlowPalette.AMBIENT.colors.first()
        }
    }

    /**
     * Two colors for a tile's background gradient: a lifted version of the artwork's
     * dominant color at the top, falling to a much darker version at the bottom.
     */
    fun tileGradientColors(accent: Int): Pair<Int, Int> {
        val top = blendColor(accent, 0.22f, towardWhite = true)
        val bottom = blendColor(accent, 0.62f, towardWhite = false)
        return top to bottom
    }
}
