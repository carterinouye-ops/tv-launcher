package com.carter.glasslauncher.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.animation.Interpolator
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.carter.glasslauncher.util.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The dock row, paged rather than free-scrolling.
 *
 * Exactly [itemsPerPage] tiles are on screen at a time and they never move while focus walks
 * across them. Stepping right off the last tile of a page slides the whole dock over by one
 * full page and lands focus on the first tile of the next page; stepping left off the first
 * tile does the mirror.
 *
 * Alignment is kept by working in *absolute* offsets rather than issuing relative scrolls:
 * [offset] reads the row's true position back off its first child, and every page change
 * animates to `page * pageWidth` measured from zero. Relative deltas were the bug behind
 * tiles being left half cut off -- holding the d-pad fired a second page while the first was
 * still animating, and the two compounded into an offset that was not on a page boundary.
 *
 * Two further things make it hold:
 *  - [PagedLayoutManager] refuses RecyclerView's automatic "scroll the focused child into
 *    view", which would otherwise creep the row by a single item.
 *  - A press arriving mid-animation finishes the outstanding page instantly ([settleNow])
 *    before being handled, so it always acts on a settled row.
 */
class PagedDockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    var itemsPerPage: Int = 5

    /**
     * While true the row is in rearrange mode: left/right carry the held tile along instead
     * of moving focus, and OK places it. Up/down are locked out so the tile cannot be
     * abandoned mid-move with the row left in a half-rearranged state.
     */
    var rearranging: Boolean = false
        set(value) {
            // Entering the mode always consumes the release of the very press that started
            // it, otherwise the hold's own key-up would immediately place the tile again.
            if (value && !field) swallowNextConfirmUp = true
            field = value
        }

    /** Called with -1 or +1 to shift the held tile one slot. */
    var onRearrangeMove: ((Int) -> Unit)? = null

    /** Called when the user places the tile (OK or Back). */
    var onRearrangeCommit: (() -> Unit)? = null

    /** Called when OK is held on a tile, asking for that tile to be picked up. */
    var onRearrangeRequested: (() -> Unit)? = null

    private var swallowNextConfirmUp = false

    private var pendingFocusPosition = NO_POSITION

    /** Page the row is on, or animating towards. Authoritative. */
    private var currentPage = 0

    /**
     * The row's true horizontal offset, read back from the first attached child rather than
     * accumulated from scroll callbacks.
     *
     * Counting deltas only sees scrolling the row reports; RecyclerView also repositions
     * children during layout (recovering focus after an item move, for one), which slid the
     * dock off its page boundary with the counter none the wiser. Every item is the same
     * width, so the offset can simply be computed, and it is correct no matter how the row
     * got where it is.
     */
    private fun offset(): Int {
        val child = getChildAt(0) ?: return 0
        val position = getChildAdapterPosition(child)
        if (position == NO_POSITION) return 0
        return position * itemWidth() - (child.left - paddingLeft)
    }

    private fun itemWidth(): Int = (pageWidth() / itemsPerPage).coerceAtLeast(1)

    private class PagedLayoutManager(context: Context) :
        LinearLayoutManager(context, HORIZONTAL, false) {
        /** Paging owns all scrolling; suppress RecyclerView's own focus-follow scroll. */
        override fun onRequestChildFocus(
            parent: RecyclerView,
            state: State,
            child: View,
            focused: View?
        ): Boolean = true

        /**
         * The *other* automatic scroll path, and the one that was still knocking the row
         * off its page boundary: calling requestFocus() on a tile bubbles a
         * requestRectangleOnScreen up to the LayoutManager, which scrolls to reveal it.
         * Since every tile we focus is already fully on screen, there is nothing to reveal.
         */
        override fun requestChildRectangleOnScreen(
            parent: RecyclerView,
            child: View,
            rect: android.graphics.Rect,
            immediate: Boolean,
            focusedChildVisible: Boolean
        ): Boolean = false
    }

    init {
        layoutManager = PagedLayoutManager(context)
        // Focusable so that when the tile holding focus is recycled out by a page scroll,
        // focus parks here instead of escaping the row. Without this a fast run of presses
        // ends up in the nav bar mid-page, and the dock stops receiving keys entirely.
        // FOCUS_AFTER_DESCENDANTS keeps tiles the preferred target, so arriving from the
        // nav still lands on a tile rather than on the row itself.
        isFocusable = true
        isFocusableInTouchMode = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        setHasFixedSize(true)
        // No item animator: a rearrange move would otherwise relayout over ~250ms, and
        // anything reading child positions during that window (the page-alignment snap
        // below, focus recovery) sees stale geometry and leaves the row off its boundary.
        // The tiles are identical rectangles, so the slide added nothing anyway.
        itemAnimator = null

        // Tiles bleed through the dock's edge rather than being chopped by it. The strength
        // is driven by how far the row sits from a page boundary (see
        // [horizontalFadingEdgeStrength]), so this only shows while a page is in flight and
        // the resting row stays perfectly crisp.
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(FADE_EDGE_DP).toInt())

        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != SCROLL_STATE_IDLE) return
                settleNow()
                flushPendingFocus()
                reclaimParkedFocus()
            }
        })

        addOnChildAttachStateChangeListener(object : OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (pendingFocusPosition == NO_POSITION) return
                val holder = findContainingViewHolder(view) ?: return
                if (holder.bindingAdapterPosition == pendingFocusPosition) {
                    pendingFocusPosition = NO_POSITION
                    view.post { view.requestFocus() }
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                // While rearranging, focus belongs to the tile being carried and is restored
                // explicitly after each move; grabbing it here would hand it to whichever
                // tile happens to sit first in the row.
                if (rearranging) return
                // The tile holding focus is being recycled out by a page scroll. Pull focus
                // onto the row first so it cannot be reassigned outside the dock.
                if (view.hasFocus()) requestFocus()
            }
        })
    }

    /**
     * Keeps left/right focus movement inside the dock. Focus search would otherwise walk
     * out of the row and into the top bar, which reads as the paging having broken.
     */
    override fun focusSearch(focused: View, direction: Int): View? {
        val result = super.focusSearch(focused, direction)
        if (direction == FOCUS_LEFT || direction == FOCUS_RIGHT) {
            if (result != null && findContainingViewHolder(result) == null) return focused
        }
        return result
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (rearranging && dispatchRearrangeKey(event)) return true
        if (!rearranging && isHeldConfirm(event)) {
            onRearrangeRequested?.invoke()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (page(+1)) return true
                    if (atEndOfRow(+1)) return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (page(-1)) return true
                    if (atEndOfRow(-1)) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * A confirm key being held down. Android emits repeats for a held key, so the first
     * repeat is the signal; a normal press never produces one. Detecting the hold here
     * rather than relying solely on the tile's own long-click means the key is consumed
     * before it can reach the tile and launch the app.
     */
    private fun isHeldConfirm(event: KeyEvent): Boolean =
        event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount >= 1 &&
            isConfirmKey(event.keyCode) &&
            findFocus() != null

    private fun isConfirmKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> true
        else -> false
    }

    /** Adapter position of the focused tile, or [NO_POSITION]. */
    fun focusedAdapterPosition(): Int {
        val focused = findFocus() ?: return NO_POSITION
        return findContainingViewHolder(focused)?.bindingAdapterPosition ?: NO_POSITION
    }

    /**
     * Key handling while a tile is held. Every relevant key is consumed here so nothing
     * reaches the tile underneath -- in particular OK, which would otherwise launch the app
     * the user is in the middle of moving.
     */
    private fun dispatchRearrangeKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down) onRearrangeMove?.invoke(-1)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) onRearrangeMove?.invoke(+1)
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK -> {
                // Commit on release: committing on the down event would leave the matching
                // up event to land on the tile and fire its click.
                if (!down) {
                    if (swallowNextConfirmUp && event.keyCode != KeyEvent.KEYCODE_BACK) {
                        swallowNextConfirmUp = false
                    } else {
                        onRearrangeCommit?.invoke()
                    }
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true

            else -> false
        }
    }

    /**
     * Follows a tile that has just been moved to [position], paging if it has crossed onto
     * another page.
     *
     * Focus is restored to [heldView] rather than to whatever sits at [position], because
     * the two are not the same thing straight after a move: the row has not relaid out yet,
     * so the slot still holds the tile that was displaced. Posting lets that layout settle
     * first. The held tile keeps its ViewHolder throughout, so the view is stable.
     */
    fun carryHeldTileTo(position: Int, heldView: View) {
        val count = adapter?.itemCount ?: return
        if (position !in 0 until count) return
        currentPage = (position / itemsPerPage).coerceIn(0, lastPage())
        // Settle after the move's layout, not before it: the row can be repositioned by
        // that layout, and this is what pulls it back onto the page boundary.
        post {
            settleNow()
            heldView.requestFocus()
        }
    }

    /**
     * Handles only the page-boundary case; moving within a page falls through so normal
     * focus search can take it (no scroll is needed, both tiles are already on screen).
     *
     * @return true when this consumed the key by turning the page.
     */
    private fun page(direction: Int): Boolean {
        // Act on a settled row: finish any page still animating, and hand focus to the tile
        // that page was flying towards, so the checks below see the real current position.
        settleNow()
        flushPendingFocus()
        reclaimParkedFocus()

        val focused = findFocus() ?: return false
        val holder = findContainingViewHolder(focused) ?: return false
        val position = holder.bindingAdapterPosition
        if (position == NO_POSITION) return false

        val count = adapter?.itemCount ?: return false
        val indexInPage = position % itemsPerPage
        val atBoundary = if (direction > 0) indexInPage == itemsPerPage - 1 else indexInPage == 0
        if (!atBoundary) return false

        val target = position + direction
        if (target < 0 || target >= count) return false

        currentPage = (target / itemsPerPage).coerceIn(0, lastPage())
        pendingFocusPosition = target
        smoothScrollBy(targetOffset() - offset(), 0, PAGE_INTERPOLATOR, SCROLL_MS)
        flushPendingFocus() // already on screen when the last page is only partly filled
        return true
    }

    /**
     * True when focus sits on the row's first or last tile and is being pushed further that
     * way. Swallowing the key there keeps focus in the dock: left to itself, focus search
     * would hop out of the row entirely and land in the nav bar, which during a fast run of
     * presses looks exactly like the paging having broken. Up and down still leave normally.
     */
    private fun atEndOfRow(direction: Int): Boolean {
        val focused = findFocus() ?: return false
        val holder = findContainingViewHolder(focused) ?: return false
        val position = holder.bindingAdapterPosition
        if (position == NO_POSITION) return false
        val count = adapter?.itemCount ?: return false
        return (direction > 0 && position == count - 1) || (direction < 0 && position == 0)
    }

    /**
     * How strongly the dock's edges feather, from 0 on a page boundary to 1 mid-slide.
     *
     * Driving it off the row's distance from the nearest boundary rather than off the
     * scroll state means it fades in and out with the movement itself, so a page change
     * reads as the tiles passing under the glass rather than being clipped by it.
     */
    private fun horizontalFadingEdgeStrength(): Float {
        val pageWidth = pageWidth()
        if (pageWidth <= 0) return 0f
        val withinPage = offset().mod(pageWidth)
        val fromBoundary = min(withinPage, pageWidth - withinPage).toFloat()
        return (fromBoundary / (pageWidth * FADE_RAMP)).coerceIn(0f, 1f)
    }

    override fun getLeftFadingEdgeStrength(): Float = horizontalFadingEdgeStrength()

    override fun getRightFadingEdgeStrength(): Float = horizontalFadingEdgeStrength()

    /** Realigns the row on its page boundary. Safe to call at any time. */
    fun snapToPage() = settleNow()

    /** Cancels any animation and jumps to the current page's exact offset. */
    private fun settleNow() {
        val delta = targetOffset() - offset()
        if (delta == 0) return
        stopScroll()
        val before = offset()
        scrollBy(delta, 0)
        // Clamped at an end and could not move: adopt where the row actually is, so the
        // page index never drifts away from what is on screen.
        if (offset() == before) {
            currentPage = (offset().toFloat() / pageWidth()).roundToInt().coerceIn(0, lastPage())
        }
    }

    /**
     * Focus is only ever meant to rest on a tile. The row is focusable purely as a catcher
     * so focus cannot escape the dock when a tile is recycled out mid-scroll, so the moment
     * it lands here, hand it straight on.
     */
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) post { reclaimParkedFocus() }
    }

    /**
     * Hands focus from the row itself down to a real tile. Only acts when focus is parked
     * here, so pressing up to the nav is never undone.
     */
    private fun reclaimParkedFocus() {
        // While rearranging, focus is restored to the carried tile explicitly.
        if (rearranging) return
        if (!isFocused) return
        val count = adapter?.itemCount ?: return
        if (count == 0) return
        val wanted = if (pendingFocusPosition != NO_POSITION) pendingFocusPosition
        else currentPage * itemsPerPage
        val position = wanted.coerceIn(0, count - 1)
        findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
    }

    private fun flushPendingFocus() {
        val position = pendingFocusPosition
        if (position == NO_POSITION) return
        val holder = findViewHolderForAdapterPosition(position) ?: return
        pendingFocusPosition = NO_POSITION
        holder.itemView.requestFocus()
    }

    /**
     * One page is the row's content box, which by construction is exactly [itemsPerPage]
     * items wide (see RowMetrics).
     */
    private fun pageWidth(): Int = (width - paddingLeft - paddingRight).coerceAtLeast(1)

    /**
     * A final page holding fewer than [itemsPerPage] tiles cannot scroll a whole page, so
     * the offset is capped at the end of the content. The row then shows the last full set
     * of tiles rather than scrolling past them and leaving a gap.
     */
    private fun targetOffset(): Int = (currentPage * pageWidth()).coerceIn(0, maxScroll())

    private fun maxScroll(): Int {
        val count = adapter?.itemCount ?: return 0
        val itemWidth = pageWidth() / itemsPerPage
        return (count * itemWidth - pageWidth()).coerceAtLeast(0)
    }

    private fun lastPage(): Int {
        val count = adapter?.itemCount ?: return 0
        if (count == 0) return 0
        return (count - 1) / itemsPerPage
    }

    companion object {
        private const val SCROLL_MS = 260
        private const val FADE_EDGE_DP = 34f

        /** Fraction of a page over which the edge fade reaches full strength. */
        private const val FADE_RAMP = 0.10f

        /**
         * Eased in and out rather than purely decelerating, which is what makes a page
         * change read as the row gliding rather than being flicked.
         */
        private val PAGE_INTERPOLATOR: Interpolator =
            PathInterpolatorCompat.create(0.4f, 0f, 0.2f, 1f)
    }
}
