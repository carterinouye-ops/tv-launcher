package com.carter.glasslauncher.data

import android.content.Intent

/**
 * A single launchable app (or utility action) as shown in the app row.
 *
 * Deliberately carries no Drawable: resolving an app's banner and icon means opening that
 * app's APK resources, and doing it for every installed app up front cost ~2.5s on this
 * device before a single tile could be drawn. Artwork is fetched lazily per tile through
 * [AppArtLoader] using [activityName].
 */
data class AppInfo(
    val id: String,          // stable key for ordering/hiding: packageName, or a special id
    val packageName: String, // real package when known, else "" for pure-action tiles
    val activityName: String?,
    val label: String,
    val launchIntent: Intent?,
    val isSpecial: Boolean = false // e.g. "Fire TV Home" utility tile
) {
    companion object {
        const val ID_FIRE_TV_HOME = "__firetv_home__"

        /** Tile that switches the TV to the HDMI 1 passthrough input. */
        const val ID_XBOX_HDMI = "__xbox_hdmi1__"
    }
}
