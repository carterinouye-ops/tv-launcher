package com.carter.glasslauncher.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.carter.glasslauncher.R

/** What the spotlight card is currently offering the user. */
data class Spotlight(
    val continueWatching: Boolean,
    val show: String,
    val season: Int?,
    val episode: Int?,
    val minutesLeft: Int?,
    val progress: Float?,
    val artwork: android.graphics.Bitmap?,
    val launch: Intent?
)

/**
 * Drives the centre card between its two faces: the clock at rest, and a spotlight for
 * whichever streaming app the remote is currently sitting on.
 *
 * The card's own size is animated rather than scaled, so the glass genuinely changes shape.
 * The GL layer reads that rectangle every frame and re-refracts for it, and is separately
 * given a lagging copy of the same rectangle so the two silhouettes flow together like a
 * liquid catching up with itself rather than a box being resized.
 */
class SpotlightController(
    private val card: View,
    private val clockContent: View,
    private val spotlightContent: LinearLayout,
    private val artFrame: View,
    private val art: ImageView,
    private val badge: TextView,
    private val title: TextView,
    private val subtitle: TextView,
    private val status: TextView,
    private val progress: ProgressBar,
    private val button: TextView,
    /** 0 while the card is the clock, 1 when it is the widget; driven by the morph. */
    private val onMorphProgress: (Float) -> Unit
) {

    private var morphProgress = 0f
    private var expanded = false
    val isExpanded: Boolean get() = expanded
    private var inputHandoff = false
    private var liquidClock = false
    val isClockLiquid: Boolean get() = liquidClock
    private var animator: ValueAnimator? = null

    /** Latest spotlight shown, so a repeat hover on the same app does not re-animate. */
    private var boundKey: String? = null

    init {
        artFrame.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        artFrame.clipToOutline = true
    }

    /** Null collapses the card back to the clock. */
    fun show(spotlight: Spotlight?) {
        if (spotlight == null) {
            boundKey = null
            if (expanded) { liquidClock = !inputHandoff; morph(toExpanded = false) }
            return
        }
        val key = "${spotlight.show}|${spotlight.minutesLeft}|${spotlight.continueWatching}"
        if (key != boundKey) {
            boundKey = key
            bind(spotlight)
        }
        if (!expanded) { liquidClock = !inputHandoff; morph(toExpanded = true) }
    }

    fun showFromInputs(spotlight: Spotlight) {
        boundKey = "${spotlight.show}|${spotlight.minutesLeft}|${spotlight.continueWatching}"
        bind(spotlight)
        animator?.cancel(); animator = null
        morphProgress = 0f
        inputHandoff = true
        liquidClock = false
        morph(toExpanded = true)
    }

    private fun bind(spotlight: Spotlight) {
        badge.setText(
            if (spotlight.continueWatching) R.string.spotlight_continue
            else R.string.spotlight_new_episode
        )
        badge.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (spotlight.continueWatching) R.drawable.ic_spotlight_play
            else R.drawable.ic_spotlight_dot,
            0, 0, 0
        )
        title.text = spotlight.show
        // History rarely carries season/episode numbers, so that line is dropped rather
        // than guessed at when they are unknown.
        if (spotlight.season != null && spotlight.episode != null) {
            subtitle.visibility = View.VISIBLE
            subtitle.text = card.context.getString(
                R.string.spotlight_season_episode, spotlight.season, spotlight.episode
            )
        } else {
            subtitle.visibility = View.GONE
        }
        status.text = if (spotlight.minutesLeft != null) {
            card.context.getString(R.string.spotlight_minutes_left, spotlight.minutesLeft)
        } else {
            card.context.getString(R.string.spotlight_now_available)
        }

        val fraction = spotlight.progress
        if (fraction != null) {
            progress.visibility = View.VISIBLE
            progress.progress = (fraction.coerceIn(0f, 1f) * 1000).toInt()
        } else {
            progress.visibility = View.GONE
        }

        art.setImageBitmap(spotlight.artwork)
        equalisePillWidths()
    }

    private fun morph(toExpanded: Boolean) {
        expanded = toExpanded
        animator?.cancel()
        val target = if (toExpanded) 1f else 0f
        val start = morphProgress
        if (toExpanded) spotlightContent.visibility = View.VISIBLE
        spotlightContent.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        animator = ValueAnimator.ofFloat(start, target).apply {
            duration = ((if (inputHandoff || liquidClock) 900L else MORPH_MS) * kotlin.math.abs(target - start)).toLong().coerceAtLeast(120L)
            interpolator = if (inputHandoff || liquidClock) LinearInterpolator() else PathInterpolator(0.34f, 0f, 0.18f, 1f)
            addUpdateListener {
                applyMorph(it.animatedValue as Float)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // cancel() also calls onAnimationEnd; never snap an interrupted morph.
                    if (cancelled) return
                    applyMorph(target)
                    spotlightContent.setLayerType(View.LAYER_TYPE_NONE, null)
                    if (!toExpanded) spotlightContent.visibility = View.GONE
                    inputHandoff = false
                    liquidClock = false
                    if (!toExpanded) applyMorph(0f)
                }
            })
            start()
        }
    }

    private fun applyMorph(progress: Float) {
        morphProgress = progress
        // Gather the digits first; the panel starts growing only once pools have joined.
        val size = if (inputHandoff || liquidClock) 1f else smoothStep(progress, 0.30f, 1f)
        card.layoutParams = card.layoutParams.apply {
            width = lerp(compactWidth, expandedWidth, size)
            height = lerp(compactHeight, expandedHeight, size)
        }
        // The clock is entirely shader glass. Reveal widget content after its surface forms.
        clockContent.alpha = 1f
        val reveal = when {
            inputHandoff -> smoothStep(progress, .88f, 1f)
            liquidClock -> smoothStep(progress, .90f, 1f)
            else -> smoothStep(progress, .72f, .98f)
        }
        spotlightContent.alpha = reveal
        val scale = CONTENT_ENTER_SCALE + (1f - CONTENT_ENTER_SCALE) * reveal
        spotlightContent.scaleX = scale
        spotlightContent.scaleY = scale
        onMorphProgress(progress)
    }

    fun dispose() {
        animator?.cancel()
        spotlightContent.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    /** Smooth hermite ramp between two points; 0 below [from], 1 above [to]. */
    private fun smoothStep(value: Float, from: Float, to: Float): Float {
        if (to <= from) return if (value >= to) 1f else 0f
        val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Both sizes follow the same continuous progress, including direction reversals. */
    private fun lerp(from: Int, to: Int, fraction: Float): Int =
        (from + (to - from) * fraction).toInt().coerceAtLeast(1)

    /**
     * Gives the status badge and the Watch now button one shared width, sized to whichever
     * is naturally wider: "Continue Watching" is longer than "Watch now" and forcing it
     * down would clip it, while "New Episode" is shorter and would mismatch the other way.
     */
    private fun equalisePillWidths() {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        listOf(badge, button).forEach { pill ->
            pill.layoutParams = pill.layoutParams.apply {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            pill.measure(unspecified, unspecified)
        }
        val shared = maxOf(badge.measuredWidth, button.measuredWidth)
        listOf(badge, button).forEach { pill ->
            pill.layoutParams = pill.layoutParams.apply { width = shared }
            pill.requestLayout()
        }
    }

    private val compactWidth = card.resources.getDimensionPixelSize(R.dimen.clock_card_width)
    private val compactHeight =
        card.resources.getDimensionPixelSize(R.dimen.clock_card_height_compact)
    private val expandedWidth = card.resources.getDimensionPixelSize(R.dimen.spotlight_card_width)
    private val expandedHeight = card.resources.getDimensionPixelSize(R.dimen.spotlight_card_height)

    companion object {
        private const val MORPH_MS = 1250L

        /** The incoming face grows the last few percent into place. */
        private const val CONTENT_ENTER_SCALE = 0.94f

    }
}
