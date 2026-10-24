package com.carter.glasslauncher.data

import android.content.Context

/**
 * Remembers the accent color extracted from each app's banner.
 *
 * Running androidx.palette over ~25 banners costs several seconds on this hardware, which
 * on a cold start left the app row blank long after the rest of the screen had drawn.
 * Colors are stable for a given app, so they're persisted and reused: the first launch
 * pays for extraction once, every launch after that paints the row immediately.
 */
class AccentColorCache(context: Context) {

    private val prefs = context.getSharedPreferences("accent_color_cache", Context.MODE_PRIVATE)

    fun get(id: String): Int? = if (prefs.contains(id)) prefs.getInt(id, 0) else null

    fun putAll(colors: Map<String, Int>) {
        if (colors.isEmpty()) return
        prefs.edit().apply {
            colors.forEach { (id, color) -> putInt(id, color) }
        }.apply()
    }
}
