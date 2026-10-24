package com.carter.glasslauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An app's TV banner and its launcher icon, either of which may be absent. */
data class AppArt(val banner: Drawable?, val icon: Drawable?)

/**
 * Loads app banners/icons off the main thread and keeps them in a small in-memory cache.
 *
 * Each lookup reads another package's resources, which is slow enough that doing it for
 * every installed app during enumeration visibly delayed the whole app row. Tiles now ask
 * for artwork as they bind and fill it in when it arrives.
 */
object AppArtLoader {

    private val cache = LruCache<String, AppArt>(64)

    fun cached(id: String): AppArt? = cache.get(id)

    suspend fun load(context: Context, app: AppInfo): AppArt = withContext(Dispatchers.IO) {
        cache.get(app.id)?.let { return@withContext it }

        val pm = context.packageManager
        val component = app.activityName?.let { ComponentName(app.packageName, it) }

        val banner = runCatching {
            component?.let { pm.getActivityBanner(it) }
                ?: pm.getApplicationBanner(app.packageName)
        }.getOrNull() ?: runCatching {
            pm.getApplicationInfo(app.packageName, 0).loadBanner(pm)
        }.getOrNull()

        val icon = runCatching {
            component?.let { pm.getActivityIcon(it) } ?: pm.getApplicationIcon(app.packageName)
        }.getOrNull()

        AppArt(banner, icon).also { cache.put(app.id, it) }
    }
}
