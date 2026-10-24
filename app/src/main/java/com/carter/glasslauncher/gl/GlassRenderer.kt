package com.carter.glasslauncher.gl

import com.carter.glasslauncher.util.ClockDistanceField
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

/** A glass panel's live geometry, handed to the shader every frame it moves. */
data class GlassPanel(
    var left: Float = 0f,
    var top: Float = 0f,
    var right: Float = 0f,
    var bottom: Float = 0f,
    var cornerRadius: Float = 0f,
    /** 0 = the shared thick-edged recipe, 1 = the flattened centre card. */
    var flatness: Float = 0f,

    /**
     * A damped copy of this panel's own rectangle, lagging behind while it resizes. The
     * shader folds the two together with a smooth minimum, so instead of a box changing
     * size the silhouette stretches and flows to catch up with itself -- surface tension,
     * near enough. When nothing is moving the two coincide and the blend is a no-op.
     */
    var trailLeft: Float = 0f,
    var trailTop: Float = 0f,
    var trailRight: Float = 0f,
    var trailBottom: Float = 0f,
    /** Smoothing radius in px for that union. 0 disables it. */
    var flow: Float = 0f,

    /**
     * 0 draws a rounded rectangle; 1 takes the panel's silhouette from the mask texture
     * instead, which is how the clock becomes glass numerals with no box around them.
     * Values between morph one into the other.
     */
    var maskAmount: Float = 0f,
    var inputProgress: Float = 0f,
    var inputOffsetY: Float = 0f,
    var widgetTransfer: Float = -1f,
    var clockTransfer: Float = -1f
)

/** Full-resolution glass with cached gradients and bounded draw regions. */
class GlassRenderer : GLSurfaceView.Renderer {
    val panels = Array(MAX_PANELS) { GlassPanel() }
    @Volatile var panelCount = 0
    @Volatile var displayDensity = 2f
    @Volatile var windowWidth = 0
    @Volatile var windowHeight = 0
    @Volatile var maskLeft = 0f
    @Volatile var maskTop = 0f
    @Volatile var maskRight = 0f
    @Volatile var maskBottom = 0f
    private val fallbackGroups = floatArrayOf(0.08f, 0.38f, 0.48f, 0.92f)
    private val fallbackInkBounds = floatArrayOf(0f, 0f, 1f, 1f)
    private val fallbackAnchors = floatArrayOf(0.23f, 0.58f, 0.43f, 0.58f, 0.70f, 0.58f)

    private data class Wallpaper(val sharp: Bitmap, val blurred: Bitmap)
    private val pendingWallpaper = AtomicReference<Wallpaper?>()
    private val pendingMask = AtomicReference<ClockDistanceField.Field?>()
    // Keep the latest source textures available if Android recreates the GL context.
    private var currentWallpaper: Wallpaper? = null
    private var currentMask: ClockDistanceField.Field? = null

    private var wallpaperProgram: Program? = null
    private var glassProgram: Program? = null
    private var widgetProgram: Program? = null
    private var combinedProgram: Program? = null
    private var clockProgram: Program? = null
    private val clockState = ClockWidgetState()
    private val clockFlow = InputGlassState()
    private var previousClockTransfer = -1f
    private val inputState = InputGlassState()
    private var previousInputProgress = 0f
    private val widgetState = InputWidgetState()
    private var previousWidgetTransfer = -1f
    private var sharpTexture = 0
    private var blurTexture = 0
    private var maskTexture = 0
    private var normalsTexture = 0
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var textureWidth = 1
    private var textureHeight = 1
    // Cache only the stationary wallpaper/navigation/dock at native resolution.
    // The clock and every in-between liquid shape are still shaded live at 1080p.
    private var backdropFramebuffer = 0
    private var backdropTexture = 0
    private var backdropWidth = 0
    private var backdropHeight = 0
    private var backdropDirty = true
    private var cachedPanelCount = -1
    private val staticGeometry = FloatArray(MAX_PANELS * 5) { Float.NaN }
    private val framebufferId = IntArray(1)
    private val crop = FloatArray(4)
    private val vertices = FloatArray(8)
    private val textureId = IntArray(1)
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // Uniform/attribute locations belong to a linked program, so resolve them once.
    private class Program(fragment: String) {
        val id = GlShader.buildProgram(VERTEX_SRC, fragment)
        val position = if (id != 0) GLES20.glGetAttribLocation(id, "aPosition") else -1
        private val locations = HashMap<String, Int>()
        fun u(name: String): Int = locations.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }
        fun use() = GLES20.glUseProgram(id)
        init {
            if (id != 0) {
                use()
                arrayOf("uSharp", "uBlur", "uMask", "uNormals").forEachIndexed { unit, name ->
                    GLES20.glUniform1i(u(name), unit)
                }
            }
        }
    }

    fun setWallpaper(sharp: Bitmap, blurred: Bitmap) {
        pendingWallpaper.set(Wallpaper(sharp, blurred))
    }

    fun setMask(field: ClockDistanceField.Field) {
        pendingMask.getAndSet(field)?.recycle()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        sharpTexture = 0; blurTexture = 0; maskTexture = 0; normalsTexture = 0
        backdropFramebuffer = 0; backdropTexture = 0
        backdropWidth = 0; backdropHeight = 0
        backdropDirty = true
        staticGeometry.fill(Float.NaN)
        wallpaperProgram = Program(WALLPAPER_FRAGMENT_SRC)
        glassProgram = Program(standardFragment())
        widgetProgram = Program(widgetFragment())
        combinedProgram = Program(GLASS_FRAGMENT_SRC)
        clockProgram = Program(clockWidgetFragment())
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = max(1, width)
        surfaceHeight = max(1, height)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        backdropDirty = true
        Log.i("GlassSurface", "buffer=${surfaceWidth}x${surfaceHeight} renderer=${GLES20.glGetString(GLES20.GL_RENDERER)}")
    }

    override fun onDrawFrame(gl: GL10?) {
        uploadPendingTextures()
        if (sharpTexture == 0) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        val scale = max(surfaceWidth.toFloat() / textureWidth, surfaceHeight.toFloat() / textureHeight)
        crop[0] = (surfaceWidth - textureWidth * scale) / 2f
        crop[1] = (surfaceHeight - textureHeight * scale) / 2f
        crop[2] = textureWidth * scale
        crop[3] = textureHeight * scale
        val canCache = ensureBackdrop()
        val count = panelCount.coerceIn(0, MAX_PANELS)
        checkStaticGeometry(count)
        // Texture units are global GL state; bind once for the whole frame.
        bindTexture(0, sharpTexture)
        bindTexture(1, blurTexture)
        bindTexture(2, maskTexture)
        bindTexture(3, normalsTexture)
        if (canCache) {
            if (backdropDirty) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, backdropFramebuffer)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawWallpaper()
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                for (i in 0 until count) if (panels[i].flatness < 0.5f) drawPanel(panels[i])
                GLES20.glDisable(GLES20.GL_BLEND)
                backdropDirty = false
            }
            // A 1:1 copy, with no scaling or reduced-resolution intermediate.
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, backdropFramebuffer)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
            GLES30.glBlitFramebuffer(0, 0, surfaceWidth, surfaceHeight,
                0, 0, surfaceWidth, surfaceHeight, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            for (i in 0 until count) if (panels[i].flatness > 0.5f) drawPanel(panels[i])
        } else {
            // Retain a full-quality direct path if an off-screen target is unavailable.
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawWallpaper()
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            for (i in 0 until count) drawPanel(panels[i])
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun checkStaticGeometry(count: Int) {
        if (count != cachedPanelCount) {
            backdropDirty = true
            cachedPanelCount = count
        }
        for (i in 0 until count) {
            val p = panels[i]
            if (p.flatness > 0.5f) continue
            val index = i * 5
            if (staticGeometry[index] != p.left || staticGeometry[index + 1] != p.top ||
                staticGeometry[index + 2] != p.right || staticGeometry[index + 3] != p.bottom ||
                staticGeometry[index + 4] != p.cornerRadius
            ) {
                backdropDirty = true
                staticGeometry[index] = p.left; staticGeometry[index + 1] = p.top
                staticGeometry[index + 2] = p.right; staticGeometry[index + 3] = p.bottom
                staticGeometry[index + 4] = p.cornerRadius
            }
        }
    }

    private fun ensureBackdrop(): Boolean {
        if (backdropFramebuffer != 0 && backdropWidth == surfaceWidth && backdropHeight == surfaceHeight) return true
        if (backdropFramebuffer != 0) {
            framebufferId[0] = backdropFramebuffer
            GLES30.glDeleteFramebuffers(1, framebufferId, 0)
        }
        if (backdropTexture != 0) {
            textureId[0] = backdropTexture
            GLES20.glDeleteTextures(1, textureId, 0)
        }
        GLES20.glGenTextures(1, textureId, 0)
        backdropTexture = textureId[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backdropTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            surfaceWidth, surfaceHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES30.glGenFramebuffers(1, framebufferId, 0)
        backdropFramebuffer = framebufferId[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, backdropFramebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, backdropTexture, 0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        backdropWidth = surfaceWidth; backdropHeight = surfaceHeight
        backdropDirty = true
        if (!complete) {
            Log.w("GlassSurface", "Native-resolution backdrop cache unavailable; using direct rendering")
            framebufferId[0] = backdropFramebuffer
            GLES30.glDeleteFramebuffers(1, framebufferId, 0)
            textureId[0] = backdropTexture
            GLES20.glDeleteTextures(1, textureId, 0)
            backdropFramebuffer = 0; backdropTexture = 0
        }
        return complete
    }

    private fun drawWallpaper() {
        val program = wallpaperProgram ?: return
        if (program.id == 0) return
        program.use()
        bindQuad(program, -1f, -1f, 1f, 1f)
        GLES20.glUniform2f(program.u("uScreen"), surfaceWidth.toFloat(), surfaceHeight.toFloat())
        GLES20.glUniform4fv(program.u("uCrop"), 1, crop, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawPanel(panel: GlassPanel) {
        val program = if (panel.widgetTransfer >= 0f) {
            if (panel.inputProgress >= .999f || panel.widgetTransfer >= .16f) widgetProgram else combinedProgram
        } else if (panel.clockTransfer >= 0f) clockProgram else glassProgram
        if (program == null) return
        if (program.id == 0 || panel.right <= panel.left || panel.bottom <= panel.top) return
        val sx = if (windowWidth > 0) surfaceWidth.toFloat() / windowWidth else 1f
        val sy = if (windowHeight > 0) surfaceHeight.toFloat() / windowHeight else 1f
        val left = panel.left * sx; val top = panel.top * sy
        val right = panel.right * sx; val bottom = panel.bottom * sy
        val trailL = panel.trailLeft * sx; val trailT = panel.trailTop * sy
        val trailR = panel.trailRight * sx; val trailB = panel.trailBottom * sy
        val flow = panel.flow * sx
        val inputs = panel.inputProgress > 0f
        val inputScale = displayDensity * sx
        val inputCentreY = (top + bottom) / 2f + panel.inputOffsetY * sy
        val inputPad = if (inputs) 40f * inputScale * kotlin.math.sin(panel.inputProgress * Math.PI).toFloat() else 0f
        val pad = 2f + flow + inputPad
        // Static panels have no trail. Including their zero-initialized trail bounds
        // previously stretched the dock's draw quad across almost the entire screen.
        val hasTrail = flow > 0f && trailR > trailL && trailB > trailT
        var unionL = (if (hasTrail) min(left, trailL) else if (inputs) min(left, (left + right) / 2f - 254f * inputScale) else left) - pad
        var unionT = (if (hasTrail) min(top, trailT) else if (inputs) min(top, inputCentreY - 64f * inputScale) else top) - pad
        var unionR = (if (hasTrail) max(right, trailR) else if (inputs) max(right, (left + right) / 2f + 254f * inputScale) else right) + pad
        var unionB = (if (hasTrail) max(bottom, trailB) else if (inputs) max(bottom, inputCentreY + 64f * inputScale) else bottom) + pad
        val field = currentMask
        if (panel.flatness > .5f) {
            if (inputs && previousInputProgress <= 0f) inputState.beginTransition()
            previousInputProgress = panel.inputProgress
        }
        if (inputs) {
            inputState.update(panel.inputProgress, (left + right) * .5f, (top + bottom) * .5f,
                190f * inputScale, 64f * inputScale, panel.inputOffsetY * sy, sx,
                max(1f, (maskRight - maskLeft) * sx), max(1f, (maskBottom - maskTop) * sy),
                field?.anchors ?: fallbackAnchors, field?.groups ?: fallbackGroups,
                field?.inkBounds ?: fallbackInkBounds)
        }
        if (inputs && field != null && (panel.maskAmount >= 0.999f || panel.inputProgress >= 0.22f)) {
            unionL = inputState.left; unionT = inputState.top
            unionR = inputState.right; unionB = inputState.bottom
        }
        if (panel.flatness > .5f) {
            if (panel.widgetTransfer >= 0f) {
                if (previousWidgetTransfer < 0f) widgetState.begin()
                widgetState.update(panel.widgetTransfer, (left + right) * .5f, (top + bottom) * .5f,
                    190f * inputScale, 64f * inputScale, panel.inputOffsetY * sy, inputScale,
                    (right - left) * .5f, (bottom - top) * .5f)
                if (panel.inputProgress >= .999f || panel.widgetTransfer >= .16f) {
                    unionL = widgetState.left; unionR = widgetState.right
                    unionT = widgetState.top; unionB = widgetState.bottom
                } else {
                    unionL = min(unionL, widgetState.left); unionR = max(unionR, widgetState.right)
                    unionT = min(unionT, widgetState.top); unionB = max(unionB, widgetState.bottom)
                }
            }
            previousWidgetTransfer = panel.widgetTransfer
        }
        if (panel.flatness > .5f) {
            if (panel.clockTransfer >= 0f) {
                if (previousClockTransfer < 0f) { clockState.begin(); clockFlow.beginTransition() }
                val cx = (left + right) * .5f; val cy = (top + bottom) * .5f
                val mw = max(1f, (maskRight - maskLeft) * sx)
                val mh = max(1f, (maskBottom - maskTop) * sy)
                clockState.update(panel.clockTransfer, cx, cy, (right - left) * .5f,
                    (bottom - top) * .5f, mw, panel.cornerRadius * sx, inputScale)
                clockFlow.update(panel.clockTransfer, cx, cy, 190f * inputScale, 64f * inputScale,
                    0f, sx, mw, mh, field?.anchors ?: fallbackAnchors,
                    field?.groups ?: fallbackGroups, field?.inkBounds ?: fallbackInkBounds, clockState.targets)
                val corePad = clockState.core[3] + 4f
                unionL = min(clockFlow.left, cx - clockState.core[0] - corePad)
                unionR = max(clockFlow.right, cx + clockState.core[0] + corePad)
                unionT = min(clockFlow.top, cy - clockState.core[1] - corePad)
                unionB = max(clockFlow.bottom, cy + clockState.core[1] + corePad)
            }
            previousClockTransfer = panel.clockTransfer
        }
        program.use()
        GLES20.glUniform4f(program.u("uTransfer"), panel.widgetTransfer,
            widgetState.parameters[1], widgetState.parameters[2], widgetState.parameters[3])
        if (panel.widgetTransfer >= 0f) {
            GLES20.glUniform4fv(program.u("uMergeDrops[0]"), 3, widgetState.drops, 0)
            GLES20.glUniform4fv(program.u("uMergeModes[0]"), 3, widgetState.modes, 0)
        }
        GLES20.glUniform1f(program.u("uClockProgress"), panel.clockTransfer)
        if (panel.clockTransfer >= 0f) GLES20.glUniform4fv(program.u("uClockCore"), 1, clockState.core, 0)
        bindQuad(program, unionL / surfaceWidth * 2f - 1f,
            1f - unionB / surfaceHeight * 2f, unionR / surfaceWidth * 2f - 1f,
            1f - unionT / surfaceHeight * 2f)
        GLES20.glUniform2f(program.u("uScreen"), surfaceWidth.toFloat(), surfaceHeight.toFloat())
        GLES20.glUniform4fv(program.u("uCrop"), 1, crop, 0)
        GLES20.glUniform4f(program.u("uRect"), left, top, right, bottom)
        GLES20.glUniform2f(program.u("uShape"), panel.cornerRadius * sx, panel.flatness)
        GLES20.glUniform4f(program.u("uTrail"), trailL, trailT, trailR, trailB)
        GLES20.glUniform1f(program.u("uFlow"), flow)
        GLES20.glUniform4f(program.u("uInputs"), if (panel.clockTransfer >= 0f) panel.clockTransfer else panel.inputProgress, 190f * inputScale, 64f * inputScale, panel.inputOffsetY * sy)
        if (inputs || panel.clockTransfer >= 0f) {
            val liquid = if (panel.clockTransfer >= 0f) clockFlow else inputState
            // Motion is constant across this frame: upload it once, rather than
            // recalculating trigonometry and stream geometry in every fragment.
            GLES20.glUniform4fv(program.u("uInputShape"), 1, liquid.shape, 0)
            GLES20.glUniform4fv(program.u("uInputReceivers[0]"), 3, liquid.receivers, 0)
            GLES20.glUniform4fv(program.u("uInputStreams[0]"), 3, liquid.streams, 0)
            GLES20.glUniform4fv(program.u("uInputModes[0]"), 3, liquid.modes, 0)
            GLES20.glUniform4fv(program.u("uInputBounds[0]"), 3, liquid.bounds, 0)
        }
        GLES20.glUniform4fv(program.u("uClockGroups"), 1, currentMask?.groups ?: fallbackGroups, 0)
        GLES20.glUniform2fv(program.u("uClockAnchors"), 3, currentMask?.anchors ?: fallbackAnchors, 0)
        GLES20.glUniform1f(program.u("uMaskAmount"), panel.maskAmount)
        GLES20.glUniform1f(program.u("uDriftScale"), sx)
        GLES20.glUniform1f(program.u("uMaskRange"), ClockDistanceField.RANGE_PX * sx)
        GLES20.glUniform4f(program.u("uMaskRect"), maskLeft * sx, maskTop * sy,
            (maskRight - maskLeft) * sx, (maskBottom - maskTop) * sy)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun bindQuad(program: Program, left: Float, bottom: Float, right: Float, top: Float) {
        vertices[0] = left; vertices[1] = bottom
        vertices[2] = right; vertices[3] = bottom
        vertices[4] = left; vertices[5] = top
        vertices[6] = right; vertices[7] = top
        quad.position(0); quad.put(vertices); quad.position(0)
        GLES20.glEnableVertexAttribArray(program.position)
        GLES20.glVertexAttribPointer(program.position, 2, GLES20.GL_FLOAT, false, 0, quad)
    }

    private fun bindTexture(unit: Int, texture: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
    }

    private fun uploadPendingTextures() {
        val nextMask = pendingMask.getAndSet(null)
        val field = nextMask ?: if (maskTexture == 0) currentMask else null
        if (field != null) {
            maskTexture = upload(field.distance, maskTexture, liquidLevels = true)
            normalsTexture = upload(field.normals, normalsTexture, liquidLevels = true)
            if (currentMask !== field) currentMask?.recycle()
            currentMask = field
        }
        val nextWallpaper = pendingWallpaper.getAndSet(null)
        val wallpaper = nextWallpaper ?: if (sharpTexture == 0) currentWallpaper else null
        if (wallpaper != null) {
            backdropDirty = true
            sharpTexture = upload(wallpaper.sharp, sharpTexture)
            blurTexture = upload(wallpaper.blurred, blurTexture)
            textureWidth = wallpaper.sharp.width
            textureHeight = wallpaper.sharp.height
            currentWallpaper = wallpaper
        }
    }

    private fun upload(bitmap: Bitmap, existing: Int, liquidLevels: Boolean = false): Int {
        textureId[0] = existing
        if (existing == 0) GLES20.glGenTextures(1, textureId, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
            if (liquidLevels) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        // Built once per clock update. Averaging signed distances rounds corners;
        // it never blurs the full-resolution backdrop or the final glass rendering.
        if (liquidLevels) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        return textureId[0]
    }

    companion object {
        const val MAX_PANELS = 3
        // Separate linked programs let the TV's GPU eliminate unused clock/flow
        // branches and registers during the larger widget handoff.
        private fun standardFragment(): String {
            var source = GLASS_FRAGMENT_SRC
            val start = source.indexOf("vec3 inputWidgetSurface(")
            val end = source.indexOf("vec3 evaluateSurface(", start)
            source = source.removeRange(start, end)
                .replace("    if (uShape.y > 0.5 && uTransfer.x >= 0.0) return inputWidgetSurface(p, centre);\n", "")
            val optics = source.indexOf("    if (uShape.y > 0.5 && uTransfer.x >= 0.0) {")
            val existing = source.indexOf("    } else if (uShape.y > 0.5 && uInputs.x > 0.0) {", optics)
            source = source.replaceRange(optics, existing + "    } else ".length, "    ")
                .replace(" * (uTransfer.x < 0.0 ? 1.0 : 0.0)", "")
            return source
        }

        private fun widgetFragment(): String {
            var source = GLASS_FRAGMENT_SRC
            val start = source.indexOf("vec3 evaluateSurface(")
            val end = source.indexOf("void main()", start)
            source = source.replaceRange(start, end,
                "vec3 evaluateSurface(vec2 p) { return inputWidgetSurface(p, 0.5 * (uRect.xy + uRect.zw)); }\n")
            val partial = source.indexOf("    if (uTransfer.x < 0.16 && uInputs.x < 0.999)")
            val afterPartial = source.indexOf("    return surface;", partial)
            source = source.removeRange(partial, afterPartial)
            val oldOptics = source.indexOf("    } else if (uShape.y > 0.5 && uInputs.x > 0.0) {")
            val afterOptics = source.indexOf("    vec2 local = px - centre;", oldOptics)
            source = source.replaceRange(oldOptics, afterOptics, "    }\n")
            val clock = source.indexOf("    float clock =")
            val clockEnd = source.indexOf(';', clock)
            source = source.replaceRange(clock, clockEnd + 1, "    float clock = 0.0;")
            return source
        }

        private fun clockWidgetFragment(): String {
            var source = standardFragment().replace("uniform vec4 uInputShape;",
                "uniform vec4 uInputShape;\nuniform vec4 uClockCore;\nuniform float uClockProgress;")
            val start = source.indexOf("vec3 evaluateSurface(")
            val end = source.indexOf("void main()", start)
            source = source.replaceRange(start, end, CLOCK_WIDGET_SURFACE)
            val optics = source.indexOf("    if (uShape.y > 0.5 && uInputs.x > 0.0) {")
            val opticsEnd = source.indexOf("    vec2 local = px - centre;", optics)
            source = source.replaceRange(optics, opticsEnd,
                "    halfSize = mix(uMaskRect.zw * 0.5, halfSize, smoothstep(0.0, 1.0, uClockProgress));\n")
            val clock = source.indexOf("    float clock =")
            val clockEnd = source.indexOf(';', clock)
            return source.replaceRange(clock, clockEnd + 1,
                "    float clock = 1.0 - smoothstep(0.08, 0.70, uClockProgress);")
        }

        private const val CLOCK_WIDGET_SURFACE = """
vec3 evaluateSurface(vec2 p) {
    vec2 centre = 0.5 * (uRect.xy + uRect.zw);
    vec3 a = inputSection(p, centre, uClockAnchors[0], vec2(0.0, uClockGroups.y), 0);
    vec3 b = inputSection(p, centre, uClockAnchors[1], uClockGroups.yz, 1);
    vec3 c = inputSection(p, centre, uClockAnchors[2], vec2(uClockGroups.z, 1.0), 2);
    vec3 fronts = liquidUnion(liquidUnion(a, b, uInputShape.y), c, uInputShape.y);
    vec2 halfSize = max(uClockCore.xy, vec2(1.0));
    vec2 q = (p - centre) / halfSize;
    float len = length(q);
    float metric = min(halfSize.x, halfSize.y);
    vec3 ellipse = vec3((len - 1.0) * metric, q / max(len, 0.001) / halfSize * metric);
    vec3 box = roundedSurface(p - centre, halfSize, min(uClockCore.z, metric));
    vec3 pool = mix(ellipse, box, smoothstep(0.72, 1.0, uClockProgress));
    // Grow the shared pool from its centre rather than revealing a large
    // surface only after a fixed, oversized distance offset disappears.
    pool.x += (1.0 - smoothstep(0.08, 0.42, uClockProgress)) * metric * 1.05;
    return liquidUnion(fronts, pool, uClockCore.w);
}
"""

        private const val VERTEX_SRC = """#version 300 es
in vec2 aPosition;
void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }
"""
        private const val WALLPAPER_FRAGMENT_SRC = """#version 300 es
precision highp float;
out vec4 fragColor;
uniform sampler2D uSharp;
uniform vec2 uScreen;
uniform vec4 uCrop;
float screenShade(float y) {
    float top = (166.0 / 255.0) * (1.0 - clamp(y / 0.28, 0.0, 1.0));
    float bottom = (194.0 / 255.0) * clamp((y - 0.58) / 0.42, 0.0, 1.0);
    return 1.0 - top - bottom;
}
void main() {
    vec2 px = vec2(gl_FragCoord.x, uScreen.y - gl_FragCoord.y);
    fragColor = vec4(texture(uSharp, (px - uCrop.xy) / uCrop.zw).rgb * screenShade(px.y / uScreen.y), 1.0);
}
"""
        private const val GLASS_FRAGMENT_SRC = """#version 300 es
precision highp float;
out vec4 fragColor;
uniform sampler2D uSharp, uBlur, uMask, uNormals;
uniform vec2 uScreen;
uniform vec4 uCrop, uRect, uTrail, uMaskRect;
uniform vec2 uShape;
uniform vec4 uInputs, uClockGroups;
uniform vec2 uClockAnchors[3];
uniform vec4 uInputShape;
uniform vec4 uTransfer, uMergeDrops[3], uMergeModes[3];
uniform vec4 uInputReceivers[3], uInputStreams[3];
uniform vec4 uInputModes[3], uInputBounds[3];
uniform float uFlow, uMaskAmount, uMaskRange;
uniform float uDriftScale;

float screenShade(float y) {
    float top = (166.0 / 255.0) * (1.0 - clamp(y / 0.28, 0.0, 1.0));
    float bottom = (194.0 / 255.0) * clamp((y - 0.58) / 0.42, 0.0, 1.0);
    return 1.0 - top - bottom;
}
vec2 texUv(vec2 p) { return (p - uCrop.xy) / uCrop.zw; }
// Distance and analytic derivative travel together. One evaluation replaces the
// five full shape evaluations previously needed for finite-difference normals.
vec3 roundedSurface(vec2 p, vec2 halfSize, float radius) {
    vec2 q = abs(p) - halfSize + radius;
    vec2 outside = max(q, vec2(0.0));
    float lengthOutside = length(outside);
    float distance = min(max(q.x, q.y), 0.0) + lengthOutside - radius;
    vec2 gradient = lengthOutside > 0.0001 ? outside / lengthOutside
        : (q.x > q.y ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
    return vec3(distance, gradient * sign(p + vec2(0.0001)));
}
vec3 circleSurface(vec2 p, float radius) {
    float distance = length(p);
    return vec3(distance - radius, p / max(distance, 0.0001));
}
vec3 joinSurfaces(vec3 a, vec3 b, float radius) {
    float h = clamp(0.5 + 0.5 * (b.x - a.x) / max(radius, 0.001), 0.0, 1.0);
    return vec3(mix(b.x, a.x, h) - radius * h * (1.0 - h), mix(b.yz, a.yz, h));
}
// Cubic smooth-min has continuous curvature at the neck, avoiding the visible
// crease of a quadratic blend. Its derivative weights drive the same clear lens.
vec3 liquidUnion(vec3 a, vec3 b, float radius) {
    float h = max(radius - abs(a.x - b.x), 0.0) / max(radius, 0.001);
    float distance = min(a.x, b.x) - h * h * h * radius / 6.0;
    float weightA = a.x < b.x ? 1.0 - 0.5 * h * h : 0.5 * h * h;
    return vec3(distance, mix(b.yz, a.yz, weightA));
}
// A rounded liquid drop with low-frequency surface tension modes. Each section
// gets a different lean and balance of stretching and pear-shaped displacement.
// The analytic normal keeps refraction attached to the moving contour.
vec3 formingInputDrop(vec2 point, float radius, vec4 mode) {
    float c = mode.x, s = mode.y;
    vec2 q = vec2(c * point.x + s * point.y, -s * point.x + c * point.y);
    float len = length(q);
    vec2 n = q / max(len, 0.001);
    float cos2 = n.x * n.x - n.y * n.y;
    float sin2 = 2.0 * n.x * n.y;
    float sin3 = n.y * (3.0 * n.x * n.x - n.y * n.y);
    float cos3 = n.x * (n.x * n.x - 3.0 * n.y * n.y);
    float contour = radius * (1.0 + mode.z * cos2 + mode.w * sin3);
    float derivative = radius * (-2.0 * mode.z * sin2 + 3.0 * mode.w * cos3);
    vec2 gradient = n - derivative / max(len, radius * 0.25) * vec2(-n.y, n.x);
    gradient = vec2(c * gradient.x - s * gradient.y, s * gradient.x + c * gradient.y);
    return vec3(len - contour, gradient);
}
// Sample only one text section. Clamping in the empty side bearings prevents an
// hour from picking up the colon or minute ink as it stretches into its own drop.
vec3 inputSection(vec2 p, vec2 centre, vec2 anchorUv, vec2 clipEdges, int section) {
    vec4 bounds = uInputBounds[section];
    // Beyond this conservative envelope the section cannot affect even a merging
    // neighbour. Skip its texture reads and surface evaluations altogether.
    if (p.x < bounds.x || p.y < bounds.y || p.x > bounds.z || p.y > bounds.w)
        return vec3(10000.0, 0.0, 0.0);
    vec4 receiver = uInputReceivers[section];
    vec3 bead = formingInputDrop(p - receiver.xy, receiver.z, uInputModes[section]);
    if (uInputStreams[section].z < 0.5) return bead;
    vec2 maskSize = max(uMaskRect.zw, vec2(1.0));
    // Invert the radial flow to sample the ORIGINAL ink. Glass closer to the
    // receiver travels into it first, pulling the trailing strokes into a stream.
    vec2 flowPoint = p - receiver.xy;
    float rr = dot(flowPoint, flowPoint);
    float currentRadius = sqrt(max(rr, 1.0));
    float originalRadius = sqrt(rr + uInputStreams[section].x);
    float ratio = max(1.0, originalRadius / currentRadius);
    vec2 radial = flowPoint / currentRadius;
    vec2 sourcePoint = receiver.xy - centre + flowPoint * ratio;
    vec2 low = vec2((clipEdges.x - 0.5) * maskSize.x, -maskSize.y * 0.5);
    vec2 high = vec2((clipEdges.y - 0.5) * maskSize.x, maskSize.y * 0.5);
    vec2 clampedPoint = clamp(sourcePoint, low, high);
    vec2 outside = sourcePoint - clampedPoint;
    float outsideLength = length(outside);
    float distance = uMaskRange;
    vec2 gradient = vec2(0.0);
    // Far-away sections cannot contribute a visible edge. Avoid their texture reads.
    if (outsideLength < uMaskRange) {
        vec2 uv = clampedPoint / maskSize + 0.5;
        vec2 packed = textureLod(uMask, uv, receiver.w).rg;
        distance = (dot(packed, vec2(65280.0, 255.0)) / 65535.0 - 0.5) * 2.0 * uMaskRange;
        gradient = textureLod(uNormals, uv, receiver.w).rg * 2.0 - 1.0;
        gradient *= step(abs(sourcePoint - clampedPoint), vec2(0.0001));
    }
    gradient += outside / max(outsideLength, 0.0001);
    // The inverse map stretches radially and contracts tangentially. Carry
    // that exact Jacobian into the glass normal so refraction follows the flow.
    gradient = ratio * gradient + (1.0 / ratio - ratio) * radial * dot(radial, gradient);
    gradient /= ratio;
    distance = (distance + outsideLength) / ratio - uInputStreams[section].w;
    vec3 ink = vec3(distance, gradient);
    return liquidUnion(ink, bead, uInputStreams[section].y);

}
vec3 inputSections(vec2 p, vec2 centre) {
    float t = uInputs.x;
    if (t >= 1.0) {
        float nearest = clamp(floor((p.x - centre.x) / uInputs.y + 0.5), -1.0, 1.0);
        return circleSurface(p - centre - vec2(nearest * uInputs.y, uInputs.w), uInputs.z);
    }
    // Every source point is now inside its receiver; only the settling drop remains.
    if (uInputShape.w < 0.5) {
        int nearest = int(clamp(floor((p.x - centre.x) / uInputs.y + 0.5), -1.0, 1.0)) + 1;
        vec4 receiver = uInputReceivers[nearest];
        return formingInputDrop(p - receiver.xy, receiver.z, uInputModes[nearest]);
    }
    vec3 hours = inputSection(p, centre, uClockAnchors[0], vec2(0.0, uClockGroups.y), 0);
    vec3 colon = inputSection(p, centre, uClockAnchors[1], uClockGroups.yz, 1);
    vec3 minutes = inputSection(p, centre, uClockAnchors[2], vec2(uClockGroups.z, 1.0), 2);
    float joining = uInputShape.y;
    return liquidUnion(liquidUnion(hours, colon, joining), minutes, joining);
}
vec3 inputWidgetSurface(vec2 p, vec2 centre) {
    vec3 a = formingInputDrop(p - uMergeDrops[0].xy, uMergeDrops[0].z, uMergeModes[0]);
    vec3 b = formingInputDrop(p - uMergeDrops[1].xy, uMergeDrops[1].z, uMergeModes[1]);
    vec3 c = formingInputDrop(p - uMergeDrops[2].xy, uMergeDrops[2].z, uMergeModes[2]);
    vec3 drops = liquidUnion(liquidUnion(a, b, uTransfer.w), c, uTransfer.w);
    vec2 halfSize = mix(vec2(uInputs.z * 1.7320508), (uRect.zw - uRect.xy) * 0.5, uTransfer.z);
    float radius = mix(uInputs.z * 1.7320508, uShape.x, uTransfer.z);
    vec3 pool = roundedSurface(p - centre, halfSize, min(radius, min(halfSize.x, halfSize.y)));
    pool.x += (1.0 - smoothstep(0.28, 0.52, uTransfer.x)) * uMaskRange * 8.0;
    vec3 surface = liquidUnion(drops, pool, uTransfer.w);
    if (uTransfer.x < 0.16 && uInputs.x < 0.999)
        surface = mix(inputSections(p, centre), surface, smoothstep(0.0, 0.16, uTransfer.x));
    return surface;
}
vec3 evaluateSurface(vec2 p) {
    vec2 centre = 0.5 * (uRect.xy + uRect.zw);
    vec2 halfSize = 0.5 * (uRect.zw - uRect.xy);
    if (uShape.y > 0.5 && uTransfer.x >= 0.0) return inputWidgetSurface(p, centre);
    if (uShape.y > 0.5 && uInputs.x >= 0.22) return inputSections(p, centre);
    float progress = 1.0 - uMaskAmount;
    float motion = sin(progress * 3.14159265);
    float radius = clamp(uShape.x, 1.0, min(halfSize.x, halfSize.y));
    vec3 surface = roundedSurface(p - centre, halfSize, radius);
    if (uShape.y > 0.5 && uShape.y < 1.5 && progress < 0.999) {
        float gather = smoothstep(0.0, 0.48, progress);
        vec2 squeeze = mix(vec2(1.0), vec2(0.64, 0.82), gather);
        float squeezeScale = min(squeeze.x, squeeze.y);
        vec2 maskSize = max(uMaskRect.zw, vec2(1.0));
        vec2 maskPoint = (p - centre) / squeeze;
        vec2 uv = maskPoint / maskSize + 0.5;
        vec2 clampedUv = clamp(uv, 0.0, 1.0);
        vec2 outside = maskPoint - clamp(maskPoint, -maskSize * 0.5, maskSize * 0.5);
        float outsideLength = length(outside);
        vec2 packed = textureLod(uMask, clampedUv, 0.0).rg;
        float field = dot(packed, vec2(65280.0, 255.0)) / 65535.0;
        float glyphDistance = (field - 0.5) * 2.0 * uMaskRange;
        vec2 glyphGradient = textureLod(uNormals, clampedUv, 0.0).rg * 2.0 - 1.0;
        glyphGradient *= step(abs(maskPoint), maskSize * 0.5);
        glyphGradient += outside / max(outsideLength, 0.0001);
        glyphGradient *= squeezeScale / squeeze;
        glyphDistance = (glyphDistance + outsideLength) * squeezeScale;
        glyphDistance -= motion * 42.0 * uDriftScale;
        vec3 glyph = vec3(glyphDistance, glyphGradient);
        float growth = smoothstep(0.28, 0.96, progress);
        vec2 poolHalf = mix(vec2(2.0 * uDriftScale), halfSize, growth);
        float poolRadius = mix(min(poolHalf.x, poolHalf.y), radius, smoothstep(0.58, 0.98, progress));
        poolRadius = clamp(poolRadius, 1.0, min(poolHalf.x, poolHalf.y));
        vec3 pool = roundedSurface(p - centre, poolHalf, poolRadius);
        pool.x += (1.0 - smoothstep(0.12, 0.38, progress)) * uMaskRange * 2.0;
        float attraction = smoothstep(0.14, 0.52, progress);
        float join = max(0.01, motion * mix(12.0, 48.0, attraction) * uDriftScale);
        surface = joinSurfaces(glyph, pool, join);
    }
    float flow = uFlow * smoothstep(0.65, 0.98, progress);
    if (flow > 0.5 && uShape.y > 0.5 && uShape.y < 1.5) {
        vec2 h = 0.5 * (uTrail.zw - uTrail.xy);
        float r = clamp(uShape.x, 1.0, min(h.x, h.y));
        vec3 trail = roundedSurface(p - 0.5 * (uTrail.xy + uTrail.zw), h, r);
        surface = joinSurfaces(surface,
            mix(surface, trail, smoothstep(0.65, 1.0, progress)), flow);
    }
    if (uShape.y > 0.5 && uInputs.x > 0.0) {
        surface = mix(surface, inputSections(p, centre), smoothstep(0.0, 0.22, uInputs.x));
    }
    return surface;
}
void main() {
    vec2 px = vec2(gl_FragCoord.x, uScreen.y - gl_FragCoord.y);
    vec3 surface = evaluateSurface(px);
    float dist = surface.x;
    // Culling envelopes return a large positive distance. Their derivative jumps
    // must never widen coverage enough to expose a rectangular drawing boundary.
    float antialias = clamp(fwidth(dist) * 0.75, 0.85, max(0.85, 2.0 * uDriftScale));
    float cover = 1.0 - smoothstep(-antialias, antialias, dist);
    if (cover <= 0.0) discard;
    // Cached glyph derivatives and analytic union derivatives keep the lens smooth
    // without four additional texture reads and repeated morph calculations.
    vec2 gradient = surface.yz;
    vec2 normal = gradient * inversesqrt(dot(gradient, gradient) + 0.08163265);
    vec2 centre = 0.5 * (uRect.xy + uRect.zw);
    vec2 halfSize = 0.5 * (uRect.zw - uRect.xy);
    if (uShape.y > 0.5 && uTransfer.x >= 0.0) {
        float nearest = clamp(floor((px.x - centre.x) / uInputs.y + 0.5), -1.0, 1.0);
        vec2 beadCentre = centre + vec2(nearest * uInputs.y, uInputs.w);
        centre = mix(beadCentre, centre, uTransfer.y);
        halfSize = mix(vec2(uInputs.z), halfSize, max(uTransfer.y, uTransfer.z));
    } else if (uShape.y > 0.5 && uInputs.x > 0.0) {
        // Each separated bead has its own lens centre, rather than one wide lens.
        float nearest = clamp(floor((px.x - centre.x) / uInputs.y + 0.5), -1.0, 1.0);
        vec2 beadCentre = centre + vec2(nearest * uInputs.y, uInputs.w);
        float split = smoothstep(0.35, 1.0, uInputs.x);
        centre = mix(centre, beadCentre, split);
        halfSize = mix(halfSize, vec2(uInputs.z), split);
    }
    vec2 local = px - centre;
    float card = step(0.5, uShape.y) * (1.0 - step(1.5, uShape.y));
    float progress = 1.0 - uMaskAmount;
    float clock = card * (1.0 - smoothstep(0.55, 1.0, progress)) * (1.0 - uInputs.x) * (uTransfer.x < 0.0 ? 1.0 : 0.0);
    // One optical recipe for the clock, navigation pill, dock, and expanded widget.
    // Scale only the bevel to fit each silhouette rather than changing the material.
    float panelBand = min(uShape.x * 1.6, min(halfSize.x, halfSize.y) * 0.68);
    float band = mix(panelBand, 30.0 * uDriftScale, clock);
    float edgeDepth = clamp(-dist / max(band, 1.0), 0.0, 1.0);
    float rim = 1.0 - edgeDepth;
    // A curved clear lens: the sharp backdrop bends strongly in the bevel, then
    // relaxes inside the glass. Blurring that sample would erase the refraction.
    float flex = sin(progress * 3.14159265) * card;
    float lensPower = min(30.0 * uDriftScale, band * 1.25) * (1.0 + flex * 0.12);
    vec2 nd = local / halfSize;
    float warp = (1.0 + 0.12 * min(dot(nd, nd) * 0.5, 1.0)) / 1.06;
    vec2 refracted = centre + local * warp + normal * lensPower * sin(rim * 1.5707963);
    vec2 uv = texUv(refracted);
    vec3 clearGlass = texture(uSharp, uv).rgb;
    // Subtle spectral separation only at the bevel.
    vec2 dispersion = normal * rim * rim * 1.15 * uDriftScale / uCrop.zw;
    clearGlass.r = texture(uSharp, uv + dispersion).r;
    clearGlass.b = texture(uSharp, uv - dispersion).b;
    vec3 blurred = texture(uBlur, uv).rgb;
    float frost = mix(0.12, 0.035, clock);
    vec3 color = mix(clearGlass, blurred, frost);
    // A slight clear dimming supports white navigation labels without a grey fill.
    color *= mix(0.94, 1.0, clock);
    float shadow = pow(rim, 3.0) * 0.20;
    color *= 1.0 - shadow;
    color = mix(color, vec3(1.0), 0.018);
    float light = dot(normal, normalize(vec2(-0.65, -0.76)));
    float highlight = pow(max(light, 0.0), 2.0);
    float edge = 1.0 - smoothstep(0.6, 2.0 * uDriftScale, abs(dist));
    float reflection = 0.24 + highlight * 0.58;
    color = mix(color, vec3(1.0), edge * reflection);
    color += vec3(0.10, 0.11, 0.12) * highlight * pow(rim, 5.0);
    fragColor = vec4(color * screenShade(px.y / uScreen.y), cover);
}
"""
    }
}
