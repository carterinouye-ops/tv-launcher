package com.carter.glasslauncher.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Simple matching device silhouettes, inferred again whenever the TV renames a port. */
class HdmiDeviceIcon(context: Context) : View(context) {
    var deviceName: String = ""
        set(value) { field = value.lowercase(); invalidate() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        val scale = minOf(width, height) / 32f
        canvas.scale(scale, scale)
        when {
            listOf("xbox", "playstation", "ps5", "ps4", "switch", "nintendo", "game", "console").any { it in deviceName } -> {
                val p = Path().apply {
                    moveTo(-7f,-7f); cubicTo(-12f,-7f,-13f,0f,-14f,6f)
                    cubicTo(-15f,12f,-9f,12f,-6f,6f); lineTo(6f,6f)
                    cubicTo(9f,12f,15f,12f,14f,6f); cubicTo(13f,0f,12f,-7f,7f,-7f); close()
                }
                canvas.drawPath(p,ink)
                canvas.drawLine(-9f,0f,-3f,0f,ink); canvas.drawLine(-6f,-3f,-6f,3f,ink)
                canvas.drawCircle(6f,-2f,0.9f,ink); canvas.drawCircle(9f,1f,0.9f,ink)
            }
            listOf("pc", "computer", "laptop", "mac", "desktop").any { it in deviceName } -> {
                canvas.drawRoundRect(-13f,-10f,13f,7f,2f,2f,ink)
                canvas.drawLine(0f,7f,0f,12f,ink); canvas.drawLine(-6f,12f,6f,12f,ink)
            }
            listOf("apple", "roku", "fire", "stream", "box", "shield", "chromecast").any { it in deviceName } -> {
                canvas.drawRoundRect(-12f,-8f,12f,8f,4f,4f,ink)
                canvas.drawLine(-7f,4f,3f,4f,ink); canvas.drawCircle(7f,4f,0.6f,ink)
            }
            else -> {
                val p = Path().apply { moveTo(-13f,-8f); lineTo(13f,-8f); lineTo(13f,2f)
                    lineTo(7f,8f); lineTo(-7f,8f); lineTo(-13f,2f); close() }
                canvas.drawPath(p,ink)
                for (x in -7..7 step 3) canvas.drawLine(x.toFloat(),-3f,x.toFloat(),1f,ink)
                canvas.drawLine(0f,8f,0f,13f,ink)
            }
        }
        canvas.restore()
    }
}
