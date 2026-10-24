package com.carter.glasslauncher.gl

import kotlin.math.*
import kotlin.random.Random

/** Shared motion for droplets gathering into a pool and spreading into a widget. */
internal class InputWidgetState {
    val drops = FloatArray(12)
    val modes = FloatArray(12)
    val parameters = FloatArray(4)
    var left = 0f; private set
    var top = 0f; private set
    var right = 0f; private set
    var bottom = 0f; private set
    private val delay = FloatArray(3)
    private val arrival = FloatArray(3)
    private val bend = FloatArray(3)
    private val phase = FloatArray(3)
    private val order = intArrayOf(0, 1, 2)
    fun begin() {
        for (i in 0..2) order[i] = i
        for (i in 2 downTo 1) {
            val j = Random.nextInt(i + 1)
            val old = order[i]; order[i] = order[j]; order[j] = old
        }
        for (i in 0..2) {
            delay[i] = order[i] * .025f
            arrival[i] = .36f + order[i] * .06f
            bend[i] = Random.nextFloat() * 28f - 14f
            phase[i] = Random.nextFloat() * 2f - 1f
        }
    }
    private fun smooth(a: Float, b: Float, t: Float) = LiquidMotion.smooth(a, b, t)
    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun update(t: Float, cx: Float, cy: Float, spacing: Float, radius: Float,
               offsetY: Float, density: Float, halfWidth: Float, halfHeight: Float) {
        val spread = smooth(.52f, 1f, t)
        val gather = smooth(0f, .48f, t)
        val wave = LiquidMotion.pulse(t)
        parameters[0] = t; parameters[1] = gather; parameters[2] = spread
        parameters[3] = max(.001f, wave * 28f * density)
        left = Float.POSITIVE_INFINITY; top = left
        right = Float.NEGATIVE_INFINITY; bottom = right
        val padding = parameters[3] + 4f
        if (t >= .28f) {
            val coreW = mix(radius * sqrt(3f), halfWidth, spread)
            val coreH = mix(radius * sqrt(3f), halfHeight, spread)
            left = cx - coreW - padding; right = cx + coreW + padding
            top = cy - coreH - padding; bottom = cy + coreH + padding
        }
        for (i in 0..2) {
            val k = i * 4
            val slot = (i - 1).toFloat()
            val move = smooth(delay[i], arrival[i], t)
            val arc = LiquidMotion.pulse(move)
            // Keep the individual drops' volume while gathering. The shared pool
            // carries their combined area, instead of inflating all three drops.
            val r = mix(radius, radius * .85f, spread)
            val poolHalf = mix(radius * sqrt(3f), halfWidth, spread)
            val burst = LiquidMotion.pulse(spread)
            drops[k] = cx + slot * spacing * (1f - move) +
                slot * (poolHalf - r) * smooth(.46f, .80f, t) +
                slot * burst * 12f * density
            drops[k + 1] = cy + offsetY * (1f - move) +
                arc * bend[i] * density + burst * sin(phase[i] * 2f) * 10f * density
            drops[k + 2] = r
            val envelope = r * 1.20f + padding
            left = min(left, drops[k] - envelope); right = max(right, drops[k] + envelope)
            top = min(top, drops[k + 1] - envelope); bottom = max(bottom, drops[k + 1] + envelope)
            val tilt = phase[i] + t * (1.2f + slot * .15f)
            modes[k] = cos(tilt); modes[k + 1] = sin(tilt)
            modes[k + 2] = wave * (.07f + slot * .01f) * cos(t * 6f + phase[i])
            modes[k + 3] = wave * .035f * sin(t * 4f + phase[i])
        }
    }
}
