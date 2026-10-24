package com.carter.glasslauncher.ui.view

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.carter.glasslauncher.R
import com.carter.glasslauncher.util.applyGlassShadowOnly
import com.carter.glasslauncher.util.dp

/**
 * The sliding segmented control: a clear glass pill holding several labels,
 * with a lighter "thumb" pill that slides under whichever segment is current.
 *
 * The thumb tracks *focus* while the user is inside the control, and settles back on the
 * selected segment when focus leaves — so arrowing across it reads as one continuous
 * sliding highlight rather than three separate buttons lighting up.
 */
class SegmentedNav @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val thumb = View(context)
    private val row = LinearLayout(context)
    private val labels = mutableListOf<TextView>()

    /** Index whose segment the thumb rests on when focus is elsewhere. */
    var selectedIndex: Int = 0
        private set

    /** Invoked when a segment is clicked/confirmed. */
    var onSegmentChosen: ((Int) -> Unit)? = null
    var onSegmentFocused: ((Int) -> Unit)? = null
    var onSegmentDown: ((Int) -> Boolean)? = null


    init {
        clipChildren = false
        clipToPadding = false
        val inset = dp(THUMB_INSET_DP).toInt()
        setPadding(inset, inset, inset, inset)
        // Same path as the dock and the card. Building the drawable directly here is how
        // this panel ended up with no elevation and a different edge treatment from the
        // other two.
        applyGlassShadowOnly(cornerRadiusDp = PILL_RADIUS_DP, elevationDp = GLASS_ELEVATION_DP)

        // A thin highlight belongs to the parent glass surface; avoid an opaque pill
        // that reads as a second, unrelated material on top of it.
        thumb.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x26FFFFFF, 0x0DFFFFFF)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(THUMB_RADIUS_DP)
            setStroke(dp(0.75f).toInt().coerceAtLeast(1), 0x30FFFFFF)
        }
        // Height is the padded height, so the inset is identical on all four sides and the
        // thumb's corners stay inside the container's own curve at either end.
        addView(thumb, LayoutParams(0, LayoutParams.MATCH_PARENT))

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun setSegments(titles: List<String>) {
        row.removeAllViews()
        labels.clear()
        titles.forEachIndexed { index, title ->
            val tv = TextView(context).apply {
                text = title
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                includeFontPadding = false
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setTextAppearance(R.style.Text_NavSegment)
                setShadowLayer(dp(1f), 0f, dp(0.5f), 0x66000000)
                val h = dp(SEGMENT_H_PADDING_DP).toInt()
                setPadding(h, 0, h, 0)
                setOnClickListener { choose(index) }
                setOnKeyListener { _, key, event ->
                    if (event.action != android.view.KeyEvent.ACTION_DOWN) false
                    else when (key) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                            labels.getOrNull((index - 1).coerceAtLeast(0))?.requestFocus()
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            labels.getOrNull((index + 1).coerceAtMost(labels.lastIndex))?.requestFocus()
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> onSegmentDown?.invoke(index) == true
                        else -> false
                    }
                }
                onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        moveThumbTo(index, animate = true)
                        onSegmentFocused?.invoke(index)
                    }
                    else post {
                        if (!hasFocusInside()) {
                            moveThumbTo(selectedIndex, animate = true)
                            onSegmentFocused?.invoke(-1)
                        }
                    }
                    refreshLabelColors()
                }
            }
            labels += tv
            row.addView(
                tv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        post {
            moveThumbTo(selectedIndex, animate = false)
            refreshLabelColors()
        }
    }

    /** Marks a segment as the resting one (e.g. Home after returning from an Intent). */
    fun setSelected(index: Int) {
        selectedIndex = index.coerceIn(0, (labels.size - 1).coerceAtLeast(0))
        if (!hasFocusInside()) moveThumbTo(selectedIndex, animate = true)
        refreshLabelColors()
    }

    fun focusedSegment() = labels.indexOfFirst { it.hasFocus() }

    fun focusSegment(index: Int) = labels.getOrNull(index)?.requestFocus()

    fun focusSelected() = labels.getOrNull(selectedIndex)?.requestFocus()

    private fun choose(index: Int) {
        selectedIndex = index
        refreshLabelColors()
        onSegmentChosen?.invoke(index)
    }

    private fun hasFocusInside() = labels.any { it.hasFocus() }

    private fun moveThumbTo(index: Int, animate: Boolean) {
        val target = labels.getOrNull(index) ?: return
        if (target.width == 0) {
            post { moveThumbTo(index, animate = false) }
            return
        }
        val lp = thumb.layoutParams as LayoutParams
        if (lp.width != target.width) {
            lp.width = target.width
            thumb.layoutParams = lp
        }
        val x = target.x
        if (animate) {
            thumb.animate().translationX(x)
                .setDuration(SLIDE_MS).setInterpolator(DecelerateInterpolator()).start()
        } else {
            thumb.translationX = x
        }
    }

    /** The segment under the thumb reads at full strength; the others sit back. */
    private fun refreshLabelColors() {
        val lit = labels.indexOfFirst { it.hasFocus() }.takeIf { it >= 0 } ?: selectedIndex
        labels.forEachIndexed { i, tv ->
            tv.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (i == lit) R.color.text_primary else R.color.text_secondary
                )
            )
        }
    }

    companion object {
        private const val PILL_RADIUS_DP = 22f

        /** Matches the other glass panels. */
        private const val GLASS_ELEVATION_DP = 8f
        private const val THUMB_RADIUS_DP = 18f
        private const val THUMB_INSET_DP = 5f
        private const val SEGMENT_H_PADDING_DP = 22f
        private const val SLIDE_MS = 250L
    }
}
