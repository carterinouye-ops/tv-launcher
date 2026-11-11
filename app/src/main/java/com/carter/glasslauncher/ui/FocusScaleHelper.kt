package com.carter.glasslauncher.ui

import android.view.View
import android.view.animation.DecelerateInterpolator

/** A centered, enlarged tile casts a soft shadow; its artwork stays fully opaque. */
object FocusScaleHelper {
    const val FOCUS_SCALE = 1.12f
    const val DURATION_MS = 200L
    const val REST_DIM = 1f
    private const val FOCUS_TRANSLATION_Z = 16f
    private val interpolator = DecelerateInterpolator()

    /** Recycled holders must inherit their current focus appearance, not the old app's. */
    fun resetVisuals(target: View, glow: View? = null, border: View? = null) {
        target.animate().cancel()
        glow?.animate()?.cancel()
        border?.animate()?.cancel()
        val focused = target.hasFocus()
        target.scaleX = if (focused) FOCUS_SCALE else 1f
        target.scaleY = target.scaleX
        target.translationZ = if (focused) FOCUS_TRANSLATION_Z else 0f
        target.translationY = 0f
        target.alpha = 1f
        glow?.alpha = 0f
        border?.alpha = 0f
    }

    fun attach(target: View, onFocusChanged: ((Boolean) -> Unit)? = null) {
        target.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            val scale = if (hasFocus) FOCUS_SCALE else 1f
            // Elevation also draws above neighbours without reordering the dock's items.
            v.animate().scaleX(scale).scaleY(scale)
                .translationY(0f)
                .translationZ(if (hasFocus) FOCUS_TRANSLATION_Z else 0f)
                .setDuration(DURATION_MS).setInterpolator(interpolator).start()
            onFocusChanged?.invoke(hasFocus)
        }
    }
}
