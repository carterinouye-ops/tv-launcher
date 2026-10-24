package com.carter.glasslauncher.gl

import kotlin.math.*
import kotlin.random.Random

/** Per-frame liquid motion and conservative drawing bounds; no animation-time allocations. */
internal class InputGlassState {
    val shape = FloatArray(4)
    val receivers = FloatArray(12)
    val streams = FloatArray(12)
    val modes = FloatArray(12)
    val bounds = FloatArray(12)
    var left = 0f; private set
    var top = 0f; private set
    var right = 0f; private set
    var bottom = 0f; private set

    private val order = intArrayOf(0, 1, 2)
    private val starts = FloatArray(3)
    private val ends = floatArrayOf(.82f, .92f, 1f)
    private val flowEnds = floatArrayOf(.86f, .90f, .94f)
    private val character = FloatArray(3)
    private val fluidity = FloatArray(3) { 1f }

    /** Pick one coherent profile per opening. Keep it for reversal/interruption. */
    fun beginTransition() {
        for (i in 0..2) order[i] = i
        for (i in 2 downTo 1) {
            val j = Random.nextInt(i + 1)
            val old = order[i]; order[i] = order[j]; order[j] = old
        }
        for (i in 0..2) {
            val rank = order[i]
            starts[i] = rank * .05f
            ends[i] = if (rank == 2) 1f else .82f + rank * .10f + Random.nextFloat() * .015f
            flowEnds[i] = .86f + rank * .04f
            character[i] = Random.nextFloat() * .8f - .4f
            fluidity[i] = 1f + rank * .16f + Random.nextFloat() * .12f
        }
    }

    private fun smooth(a: Float, b: Float, t: Float) = LiquidMotion.smooth(a, b, t)
    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun wave(x: Float) = LiquidMotion.pulse(x)

    fun update(t: Float, cx: Float, cy: Float, spacing: Float, radius: Float,
               offsetY: Float, scale: Float, maskWidth: Float, maskHeight: Float,
               anchors: FloatArray, groups: FloatArray, inkBounds: FloatArray, destinations: FloatArray? = null) {
        shape[1] = max(.001f, wave(t) * 32f * scale)
        shape[3] = 0f
        left = Float.POSITIVE_INFINITY; top = left
        right = Float.NEGATIVE_INFINITY; bottom = right
        for (index in 0..2) {
            val k = index * 4
            val slot = (index - 1).toFloat()
            val local = ((t - starts[index]) / (ends[index] - starts[index])).coerceIn(0f, 1f)
            val arc = wave(smooth(0f, 1f, local))
            val lead = smooth(0f, .46f, local)
            val draw = smooth(.02f, flowEnds[index], local)
            val active = local < flowEnds[index]
            val cohesion = max(.001f, arc * 24f * scale)
            val rounding = arc * 2.5f * scale
            val envelope = smooth(0f, .24f, local) * (1f - smooth(.60f, 1f, local))
            if (active) shape[3] = 1f
            val sx = cx + (anchors[index * 2] - .5f) * maskWidth
            val sy = cy + (anchors[index * 2 + 1] - .5f) * maskHeight
            val tx = destinations?.get(k) ?: (cx + slot * spacing)
            val ty = destinations?.get(k + 1) ?: (cy + offsetY)
            val bx = mix(sx, tx, lead) + arc * radius * slot * .035f
            val by = mix(sy, ty, lead) + arc * radius * .10f
            val destinationRadius = destinations?.get(k + 2) ?: radius
            val beadRadius = mix(2f * scale, destinationRadius, smooth(.03f, .88f - slot * .025f, local))
            receivers[k] = bx; receivers[k + 1] = by
            receivers[k + 2] = beadRadius
            // Smooth the source contour as it becomes liquid, without inflating
            // or erasing the strokes. The resting clock still uses its exact field.
            receivers[k + 3] = 4.3f * smooth(.02f, .30f, local)
            val tilt = slot * .55f + .30f * sin(local * 6f + slot * 1.4f + character[index])
            modes[k] = cos(tilt); modes[k + 1] = sin(tilt)
            modes[k + 2] = envelope * fluidity[index] * (.07f + slot * .015f) * cos(local * 5f + slot * 1.1f + character[index])
            modes[k + 3] = envelope * fluidity[index] * (.035f - slot * .010f)
            val lowX = cx + (max(inkBounds[0], if (index == 0) 0f else groups[index]) - .5f) * maskWidth - 4f
            val highX = cx + (min(inkBounds[2], if (index == 2) 1f else groups[index + 1]) - .5f) * maskWidth + 4f
            val lowY = cy + (inkBounds[1] - .5f) * maskHeight - 4f
            val highY = cy + (inkBounds[3] - .5f) * maskHeight + 4f
            var farSquared = 0f
            for (corner in 0..3) {
                val dx = (if (corner and 1 == 0) lowX else highX) - bx
                val dy = (if (corner and 2 == 0) lowY else highY) - by
                farSquared = max(farSquared, dx * dx + dy * dy)
            }
            // Radial sink flow: r_out² = r_in² - drawnArea. Nearer glass enters
            // first; the rest stretches continuously behind it. No erosion/fade.
            val drawnArea = max(0f, farSquared - beadRadius * beadRadius * .16f) * draw
            streams[k] = drawnArea; streams[k + 1] = cohesion
            streams[k + 2] = if (active) 1f else 0f
            streams[k + 3] = rounding
            val br = beadRadius * 1.20f
            var l = bx - br; var r = bx + br
            var upper = by - br; var lower = by + br
            if (active) {
                // The radial map stays between each source corner and the sink.
                // Include their transformed extrema and the receiving drop.
                for (corner in 0..3) {
                    val dx = (if (corner and 1 == 0) lowX else highX) - bx
                    val dy = (if (corner and 2 == 0) lowY else highY) - by
                    val rr = dx * dx + dy * dy
                    val factor = sqrt(max(0f, rr - drawnArea) / max(rr, .001f))
                    val x = bx + dx * factor; val y = by + dy * factor
                    l = min(l, x); r = max(r, x)
                    upper = min(upper, y); lower = max(lower, y)
                }
            }
            val padding = if (!active) 4f else cohesion + shape[1] + rounding + 6f
            if (local >= 1f) {
                l = tx - destinationRadius; r = tx + destinationRadius
                upper = ty - destinationRadius; lower = ty + destinationRadius
            }
            bounds[k] = l - padding; bounds[k + 1] = upper - padding
            bounds[k + 2] = r + padding; bounds[k + 3] = lower + padding
            left = min(left, bounds[k]); top = min(top, bounds[k + 1])
            right = max(right, bounds[k + 2]); bottom = max(bottom, bounds[k + 3])
        }
    }
}
