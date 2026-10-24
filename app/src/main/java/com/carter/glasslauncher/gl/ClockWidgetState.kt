package com.carter.glasslauncher.gl

import kotlin.math.*
import kotlin.random.Random

/** Rounded liquid fronts grow around the original clock before settling into the card. */
internal class ClockWidgetState {
    val targets = FloatArray(12)
    val core = FloatArray(4)
    private val bendX = FloatArray(3)
    private val bendY = FloatArray(3)
    private val phase = FloatArray(3)
    fun begin() {
        for (i in 0..2) {
            bendX[i] = Random.nextFloat() * 24f - 12f
            bendY[i] = Random.nextFloat() * 36f - 18f
            phase[i] = Random.nextFloat() * 2f - 1f
        }
    }
    private fun smooth(a: Float, b: Float, t: Float) = LiquidMotion.smooth(a, b, t)
    fun update(t: Float, cx: Float, cy: Float, halfWidth: Float, halfHeight: Float,
               maskWidth: Float, corner: Float, density: Float) {
        val spread = smooth(.08f, 1f, t)
        val wave = LiquidMotion.pulse(t)
        core[0] = min(maskWidth * .45f, halfWidth) * (1f - spread) + halfWidth * spread
        core[1] = 3f * (1f - spread) + halfHeight * spread
        core[2] = corner
        core[3] = max(.001f, wave * 20f * density)
        for (i in 0..2) {
            val k = i * 4
            val r = min(halfHeight * .78f, 90f * density) * smooth(.02f, .68f, t) + 2f
            targets[k] = cx + (i - 1) * max(0f, core[0] - r) +
                wave * bendX[i] * density * sin(t * 4f + phase[i])
            targets[k + 1] = cy + wave * bendY[i] * density
            targets[k + 2] = r
        }
    }
}
