package com.carter.glasslauncher.gl

import kotlin.math.sin

/** Bounded motion with zero velocity and acceleration at either end of a blend. */
internal object LiquidMotion {
    fun smooth(from: Float, to: Float, progress: Float): Float {
        val x = ((progress - from) / (to - from)).coerceIn(0f, 1f)
        return x * x * x * (x * (x * 6f - 15f) + 10f)
    }

    /** A gentle disturbance that starts and ends at rest; randomness stays per gesture. */
    fun pulse(progress: Float): Float {
        val s = sin(Math.PI * progress.coerceIn(0f, 1f)).toFloat()
        return s * s
    }
}
