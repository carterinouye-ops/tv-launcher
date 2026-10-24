package com.carter.glasslauncher.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists user customization of the app row: explicit ordering overrides and hidden tiles.
 * Backed by plain SharedPreferences as requested.
 */
class TileOrderPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tile_order_prefs", Context.MODE_PRIVATE)

    /** Ordered list of package names the user has explicitly rearranged, or empty if untouched. */
    fun getCustomOrder(): List<String> {
        val raw = prefs.getString(KEY_ORDER, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(SEPARATOR)
    }

    fun setCustomOrder(order: List<String>) {
        prefs.edit().putString(KEY_ORDER, order.joinToString(SEPARATOR)).apply()
    }

    fun getHidden(): Set<String> {
        return prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()
    }

    fun hide(packageKey: String) {
        val hidden = getHidden().toMutableSet()
        hidden.add(packageKey)
        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply()
    }

    fun unhideAll() {
        prefs.edit().putStringSet(KEY_HIDDEN, emptySet()).apply()
    }

    companion object {
        private const val KEY_ORDER = "custom_order"
        private const val KEY_HIDDEN = "hidden_tiles"
        private const val SEPARATOR = "|,|"
    }
}
