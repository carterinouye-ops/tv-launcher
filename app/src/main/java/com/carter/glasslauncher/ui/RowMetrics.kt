package com.carter.glasslauncher.ui

import android.content.Context
import com.carter.glasslauncher.R

/**
 * Derives dock tile sizes from the panel width so exactly [ITEMS_ACROSS] items span the
 * row's content box:
 *
 *     itemWidth = (screenWidth - 2*margin - 2*dockPadding) / ITEMS_ACROSS
 *
 * Computing this rather than hard-coding a width is what makes the first tile's left edge
 * land on the dock's inner edge and the last tile's right edge land on the mirrored one.
 * It also makes the row exactly one page wide, so the tile after the last one begins
 * precisely at the clip edge and the dock never shows a sliver of the next page.
 */
object RowMetrics {

    const val ITEMS_ACROSS = 5
    private const val TILE_ASPECT = 16f / 9f

    fun margin(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.screen_side_margin)

    fun gap(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.row_gap)

    /** Width of one row item so [ITEMS_ACROSS] of them exactly fill the content width. */
    fun itemWidth(context: Context): Int {
        val res = context.resources
        val screen = res.displayMetrics.widthPixels
        val dockPadding = res.getDimensionPixelSize(R.dimen.dock_padding)
        // Inside the dock strip: screen minus the page margins and the dock's own padding.
        // Every item view carries half the gap on BOTH sides, so n items consume n gaps,
        // not n-1. Using n-1 made the tiles 5px too wide each and overflowed the dock.
        val usable = screen - 2 * margin(context) - 2 * dockPadding - ITEMS_ACROSS * gap(context)
        return usable / ITEMS_ACROSS
    }

    /** App tiles keep a 16:9 shape. */
    fun tileHeight(context: Context): Int = (itemWidth(context) / TILE_ASPECT).toInt()
}
