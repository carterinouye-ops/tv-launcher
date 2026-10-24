package com.carter.glasslauncher.util

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider

fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.dpInt(value: Float): Int = dp(value).toInt()

fun View.dp(value: Float): Float = context.dp(value)

/**
 * Gives a panel its shape and its drop shadow. The glass itself is drawn underneath by the
 * GL layer, so the background here is transparent -- it exists only to supply the outline,
 * which the platform uses both to clip the panel's children and to cast its shadow.
 */
fun View.applyGlassShadowOnly(cornerRadiusDp: Float, elevationDp: Float) {
    background = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = dp(cornerRadiusDp)
        setColor(android.graphics.Color.TRANSPARENT)
    }
    outlineProvider = ViewOutlineProvider.BACKGROUND
    clipToOutline = true
    elevation = dp(elevationDp)
    outlineAmbientShadowColor = SHADOW_AMBIENT
    outlineSpotShadowColor = SHADOW_SPOT
}

private const val SHADOW_AMBIENT = 0x30000000
private const val SHADOW_SPOT = 0x40000000
