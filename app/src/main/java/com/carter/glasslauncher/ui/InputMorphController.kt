package com.carter.glasslauncher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.carter.glasslauncher.data.HdmiInputs
import com.carter.glasslauncher.ui.view.HdmiDeviceIcon
import com.carter.glasslauncher.ui.view.SegmentedNav

/** The clock becomes three HDMI droplets; native icons and focus follow their glass silhouettes. */
class InputMorphController(
    private val root: FrameLayout,
    private val nav: SegmentedNav,
    private val clock: View,
    private val dock: View,
    private val onProgress: (Float) -> Unit,
    private val onChoose: (HdmiInputs.Input) -> Unit,
    private val onExitToDock: () -> Unit
) {
    private val context = root.context
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
    private val overlay = FrameLayout(context).apply {
        clipChildren = false; clipToPadding = false
        visibility = View.GONE
    }
    private data class Cell(val target: FrameLayout, val icon: HdmiDeviceIcon, val label: TextView)
    private val cells = mutableListOf<Cell>()
    private var inputs = emptyList<HdmiInputs.Input>()
    private var animator: ValueAnimator? = null
    private var progress = 0f
    private var baseOffsetY = 0f
    var inputOffsetY = 0f
        private set
    var isOpen = false
        private set
    private var observing = false
    private val refresh = object : Runnable {
        override fun run() {
            updateInputs()
            if (isOpen) handler.postDelayed(this, 2000)
        }
    }
    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) = updateInputs()
        override fun onInputRemoved(inputId: String) = updateInputs()
        override fun onInputUpdated(inputId: String) = updateInputs()
        override fun onInputStateChanged(inputId: String, state: Int) = updateInputs()
        override fun onTvInputInfoUpdated(inputInfo: TvInputInfo) = updateInputs()
    }
    private val labelsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = updateInputs()
    }

    init {
        root.addView(overlay, FrameLayout.LayoutParams(dp(570f).toInt(), dp(168f).toInt(),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 0 })
        repeat(3) { index ->
            val target = FrameLayout(context).apply {
                id = View.generateViewId()
                isFocusable = false; isClickable = true; isEnabled = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x00FFFFFF)
                    setStroke(dp(1f).toInt().coerceAtLeast(1), 0x00FFFFFF)
                }
            }
            val icon = HdmiDeviceIcon(context)
            target.addView(icon, FrameLayout.LayoutParams(dp(48f).toInt(), dp(48f).toInt(), Gravity.CENTER))
            overlay.addView(target, FrameLayout.LayoutParams(dp(128f).toInt(), dp(128f).toInt()).apply {
                leftMargin = dp(index * 190f + 31f).toInt()
            })
            val label = TextView(context).apply {
                gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.WHITE)
                text = "HDMI ${index + 1}"
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
                setShadowLayer(dp(2f), 0f, dp(1f), 0xCC000000.toInt())
            }
            overlay.addView(label, FrameLayout.LayoutParams(dp(186f).toInt(), dp(32f).toInt()).apply {
                leftMargin = dp(index * 190f + 2f).toInt(); topMargin = dp(136f).toInt()
            })
            target.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
                (target.background as GradientDrawable).apply {
                    setColor(if (focused) 0x16FFFFFF else 0x00FFFFFF)
                    setStroke(dp(1f).toInt().coerceAtLeast(1), if (focused) 0xCCFFFFFF.toInt() else 0x00FFFFFF)
                }
                icon.alpha = if (focused) 1f else 0.76f
                label.alpha = if (focused) 1f else 0.76f
            }
            target.setOnClickListener { inputs.getOrNull(index)?.let(onChoose) }
            target.setOnKeyListener { _, key, event ->
                if (event.action != KeyEvent.ACTION_DOWN) false else when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { cells[(index + 2) % 3].target.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { cells[(index + 1) % 3].target.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { close(toDock = true); true }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        nav.focusSegment(1)
                        cells.forEach { it.target.isFocusable = false }
                        true
                    }
                    else -> false
                }
            }
            cells += Cell(target, icon, label)
        }
    }

    fun start() {
        if (observing) return
        observing = true
        manager?.registerCallback(inputCallback, handler)
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("tv_input_custom_labels"), false, labelsObserver)
        updateInputs()
    }

    fun stop() {
        if (observing) {
            manager?.unregisterCallback(inputCallback)
            context.contentResolver.unregisterContentObserver(labelsObserver)
            observing = false
        }
        handler.removeCallbacks(refresh)
        val old = animator; animator = null; old?.cancel()
        isOpen = false
        nav.setSelected(0)
        applyProgress(0f)
        overlay.visibility = View.GONE
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        nav.setSelected(1)
        updateInputs()
        val clockLocation = IntArray(2)
        val rootLocation = IntArray(2)
        val navLocation = IntArray(2)
        val dockLocation = IntArray(2)
        clock.getLocationInWindow(clockLocation)
        root.getLocationInWindow(rootLocation)
        nav.getLocationInWindow(navLocation)
        dock.getLocationInWindow(dockLocation)
        // Equal clearance above and below the glass circles, using live layout bounds.
        val centreY = (navLocation[1] + nav.height + dockLocation[1]) / 2f
        val overlayTop = (centreY - rootLocation[1] - dp(64f)).toInt()
        baseOffsetY = overlayTop + rootLocation[1] + dp(64f) -
            (clockLocation[1] + clock.height / 2f)
        overlay.layoutParams = (overlay.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = overlayTop
        }
        overlay.visibility = View.VISIBLE
        cells.forEach { it.target.isFocusable = false }
        handler.removeCallbacks(refresh); handler.postDelayed(refresh, 2000)
        animateTo(1f)
    }

    fun enterChoices() {
        open()
        cells.forEach { it.target.isFocusable = it.target.isEnabled }
        (cells.getOrNull(1)?.takeIf { it.target.isEnabled } ?: cells.firstOrNull { it.target.isEnabled })
            ?.target?.requestFocus()
    }

    fun close(toDock: Boolean = false, restoreNavFocus: Boolean = true) {
        if (!isOpen) return
        if (toDock) {
            cells.forEach { it.target.isFocusable = false }
            onExitToDock()
            return
        }
        isOpen = false
        handler.removeCallbacks(refresh)
        cells.forEach { it.target.isFocusable = false }
        animateTo(0f) {
            overlay.visibility = View.GONE
            if (!toDock && restoreNavFocus && nav.focusedSegment() != 2) { nav.setSelected(0); nav.focusSelected() }
        }
    }

    /** Keep the existing liquid visible while a streaming app's data is resolved. */
    fun holdForSpotlight() {
        if (progress <= 0f) return
        isOpen = true
        cells.forEach { it.target.isFocusable = false }
        animateTo(1f)
    }

    fun beginWidgetHandoff() {
        val old = animator; animator = null; old?.cancel()
        isOpen = false
        handler.removeCallbacks(refresh)
        cells.forEach { it.target.isFocusable = false }
        overlay.visibility = View.GONE
    }

    fun finishWidgetHandoff() {
        isOpen = false
        applyProgress(0f)
        overlay.visibility = View.GONE
    }

    fun returnFromWidgetHandoff(keepInputs: Boolean) {
        isOpen = true
        overlay.visibility = View.VISIBLE
        if (keepInputs) animateTo(1f) else close(restoreNavFocus = false)
    }

    private fun updateInputs() {
        val updated = HdmiInputs.list(context)
        if (updated == inputs) return
        inputs = updated
        cells.forEachIndexed { index, cell ->
            val input = inputs.getOrNull(index)
            cell.label.text = input?.name ?: "HDMI ${index + 1}"
            cell.icon.deviceName = input?.name.orEmpty()
            cell.target.contentDescription = input?.let { "${it.name}, HDMI ${it.port}" }
                ?: "HDMI ${index + 1}, unavailable"
            cell.target.isEnabled = input != null
            cell.target.isFocusable = cell.target.isFocusable && isOpen && input != null
        }
    }

    private fun animateTo(target: Float, finished: () -> Unit = {}) {
        val old = animator; animator = null; old?.cancel()
        val animation = ValueAnimator.ofFloat(progress, target).apply {
            duration = (650L * kotlin.math.abs(target - progress)).toLong().coerceAtLeast(1)
            // Geometry already has smooth Hermite timing in the shader. Easing this
            // clock a second time rushed the actual joining into a few frames.
            interpolator = LinearInterpolator()
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator === animation) { animator = null; finished() }
                }
            })
        }
        animator = animation
        animation.start()
    }

    private fun applyProgress(value: Float) {
        progress = value
        // One small overshoot and a softer return. Zero velocity at both ends keeps
        // interruptions continuous; glass, icons, and focus rings share the offset.
        val settle = ((value - 0.70f) / 0.30f).coerceIn(0f, 1f)
        val phase = Math.PI * settle
        val bounce = (dp(4f) * kotlin.math.sin(2.0 * phase) *
            kotlin.math.sin(phase) * kotlin.math.sin(phase)).toFloat()
        inputOffsetY = baseOffsetY + bounce
        overlay.translationY = bounce
        val reveal = ((value - 0.80f) / 0.18f).coerceIn(0f, 1f)
        overlay.alpha = reveal * reveal * (3f - 2f * reveal)
        onProgress(value)
    }

    private fun dp(value: Float) = value * context.resources.displayMetrics.density
}
