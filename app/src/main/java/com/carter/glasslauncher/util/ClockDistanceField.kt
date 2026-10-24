package com.carter.glasslauncher.util

import android.graphics.Bitmap
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** Signed distances retain the depth inside a numeral and the empty space between digits. */
object ClockDistanceField {
    const val RANGE_PX = 128f

    data class Field(val distance: Bitmap, val normals: Bitmap, val groups: FloatArray, val anchors: FloatArray, val inkBounds: FloatArray) {
        fun recycle() {
            distance.recycle()
            normals.recycle()
        }
    }

    fun create(source: Bitmap, groupEdges: FloatArray = floatArrayOf(0.08f, 0.38f, 0.48f, 0.92f)): Field {
        // Preserve the original anti-aliased glyph resolution instead of thresholding
        // a half-size bitmap, which magnifies stair steps when the glass bends light.
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val inside = BooleanArray(pixels.size) { (pixels[it] ushr 24) >= 128 }
        val toInk = distances(inside, width, height, true)
        val toAir = distances(inside, width, height, false)
        val signed = FloatArray(pixels.size) { i ->
            val coverage = (pixels[i] ushr 24) / 255f
            if (coverage > 0f && coverage < 1f) 0.5f - coverage
            else if (inside[i]) -(toAir[i] - 0.5f) else toInk[i] - 0.5f
        }
        // A small separable Gaussian rounds pixel-scale corners without blurring the
        // backdrop. The field remains continuous on both sides of the glass boundary.
        val weights = floatArrayOf(1f, 6f, 15f, 20f, 15f, 6f, 1f)
        val horizontal = FloatArray(signed.size)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0f
            for (k in -3..3) sum += signed[y * width + (x + k).coerceIn(0, width - 1)] * weights[k + 3]
            horizontal[y * width + x] = sum / 64f
        }
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0f
            for (k in -3..3) sum += horizontal[(y + k).coerceIn(0, height - 1) * width + x] * weights[k + 3]
            signed[y * width + x] = sum / 64f
        }
        // Seed each new pool inside its own ink. A seed in a counter (the hole in
        // 0/8/9) would appear as a separate floating dot instead of joining the glass.
        val anchors = FloatArray(6)
        for (group in 0..2) {
            val preferredX = (groupEdges[group] + groupEdges[group + 1]) * 0.5f * width
            val preferredY = height * 0.58f
            val left = (groupEdges[group] * width).toInt().coerceIn(0, width - 1)
            val right = (groupEdges[group + 1] * width).toInt().coerceIn(left, width - 1)
            var best = Float.POSITIVE_INFINITY
            var anchorX = preferredX
            var anchorY = preferredY
            for (y in 0 until height) for (x in left..right) {
                val index = y * width + x
                if (!inside[index] || toAir[index] < 4f) continue
                val dx = x - preferredX
                val dy = y - preferredY
                val distance = dx * dx + dy * dy
                if (distance < best) {
                    best = distance
                    anchorX = x.toFloat()
                    anchorY = y.toFloat()
                }
            }
            anchors[group * 2] = anchorX / width
            anchors[group * 2 + 1] = anchorY / height
        }
        var inkLeft = width
        var inkRight = 0
        var inkTop = height
        var inkBottom = 0
        for (i in inside.indices) if (inside[i]) {
            val x = i % width
            val y = i / width
            inkLeft = minOf(inkLeft, x)
            inkRight = maxOf(inkRight, x + 1)
            inkTop = minOf(inkTop, y)
            inkBottom = maxOf(inkBottom, y + 1)
        }
        val inkBounds = if (inkLeft < inkRight && inkTop < inkBottom)
            floatArrayOf(inkLeft.toFloat() / width, inkTop.toFloat() / height,
                inkRight.toFloat() / width, inkBottom.toFloat() / height)
            else floatArrayOf(0f, 0f, 1f, 1f)
        val normals = IntArray(pixels.size)
        // Cache the field derivatives once per clock update. Each rendered fragment
        // can then read its gradient instead of evaluating the whole morph four times.
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val distance = signed[i]
            val dx = (signed[y * width + (x + 4).coerceAtMost(width - 1)] -
                signed[y * width + (x - 4).coerceAtLeast(0)]) / 8f
            val dy = (signed[(y + 4).coerceAtMost(height - 1) * width + x] -
                signed[(y - 4).coerceAtLeast(0) * width + x]) / 8f
            val nx = ((dx.coerceIn(-1f, 1f) * 0.5f + 0.5f) * 255f).roundToInt()
            val ny = ((dy.coerceIn(-1f, 1f) * 0.5f + 0.5f) * 255f).roundToInt()
            normals[i] = (0xFF shl 24) or (nx shl 16) or (ny shl 8)
            val value = ((0.5f + distance / (2f * RANGE_PX)) * 65535f)
                .roundToInt().coerceIn(0, 65535)
            // Two channels avoid visible bands and noisy normals from 8-bit distances.
            pixels[i] = (0xFF shl 24) or ((value ushr 8) shl 16) or ((value and 255) shl 8)
        }
        return Field(
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(normals, width, height, Bitmap.Config.ARGB_8888),
            groupEdges.copyOf(), anchors, inkBounds
        )
    }

    // Exact squared Euclidean distance, with a linear-time parabola envelope per axis.
    private fun distances(inside: BooleanArray, width: Int, height: Int, target: Boolean): FloatArray {
        val d = FloatArray(inside.size) { if (inside[it] == target) 0f else 1e10f }
        val count = maxOf(width, height)
        val input = FloatArray(count)
        val output = FloatArray(count)
        val sites = IntArray(count)
        val breaks = DoubleArray(count + 1)
        fun sweep(n: Int) {
            var k = 0
            sites[0] = 0
            breaks[0] = Double.NEGATIVE_INFINITY
            breaks[1] = Double.POSITIVE_INFINITY
            for (q in 1 until n) {
                fun intersection(): Double {
                    val v = sites[k]
                    return ((input[q].toDouble() + q.toDouble() * q) -
                        (input[v].toDouble() + v.toDouble() * v)) / (2.0 * (q - v))
                }
                var boundary = intersection()
                while (boundary <= breaks[k]) {
                    k--
                    boundary = intersection()
                }
                k++
                sites[k] = q
                breaks[k] = boundary
                breaks[k + 1] = Double.POSITIVE_INFINITY
            }
            k = 0
            for (q in 0 until n) {
                while (breaks[k + 1] < q) k++
                val delta = (q - sites[k]).toFloat()
                output[q] = delta * delta + input[sites[k]]
            }
        }
        for (x in 0 until width) {
            for (y in 0 until height) input[y] = d[y * width + x]
            sweep(height)
            for (y in 0 until height) d[y * width + x] = output[y]
        }
        for (y in 0 until height) {
            for (x in 0 until width) input[x] = d[y * width + x]
            sweep(width)
            for (x in 0 until width) d[y * width + x] = sqrt(output[x])
        }
        return d
    }
}
