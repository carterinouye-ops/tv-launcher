package com.carter.glasslauncher.data

import android.graphics.Color

/**
 * Per-brand tile treatment: a glossy vertical gradient in the brand's own colours, a bright
 * rim, and an outer glow. Apps listed here get that treatment; anything else falls back to
 * its TV banner on a palette-derived gradient.
 *
 * The tile is filled with the app's own TV banner, which for these apps already is the
 * brand mark on brand colour; the gradient, sheen, rim and glow frame it.
 */
data class Brand(
    val gradientTop: Int,
    val gradientBottom: Int,
    val borderTint: Int,
    val glow: Int
)

object BrandCatalog {

    private fun brand(top: String, bottom: String): Brand {
        val t = Color.parseColor(top)
        val b = Color.parseColor(bottom)
        return Brand(
            gradientTop = t,
            gradientBottom = b,
            // Rim is a lifted version of the brighter end, which is what makes the tile
            // read as backlit rather than merely filled.
            borderTint = lighten(b, 0.45f),
            glow = b
        )
    }

    private val byPackage: Map<String, Brand> = mapOf(
        "com.disney.disneyplus" to brand("#0B3A9E", "#1E7BFF"),
        "com.hulu.plus" to brand("#0B3D2E", "#1CE783"),
        "com.wbd.stream" to brand("#3A0CA3", "#7B2CFF"),
        "com.hbo.hbonow" to brand("#3A0CA3", "#7B2CFF"),
        "com.netflix.ninja" to brand("#4A0008", "#E50914"),
        "com.netflix.mediaclient" to brand("#4A0008", "#E50914")
    )

    fun forPackage(packageName: String): Brand? = byPackage[packageName]

    /** Brand colour for a package, used for continue-watching progress bars. */
    fun accentForPackage(packageName: String): Int? = byPackage[packageName]?.glow

    private fun lighten(color: Int, ratio: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * ratio).toInt()
        val g = (Color.green(color) + (255 - Color.green(color)) * ratio).toInt()
        val b = (Color.blue(color) + (255 - Color.blue(color)) * ratio).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }
}
