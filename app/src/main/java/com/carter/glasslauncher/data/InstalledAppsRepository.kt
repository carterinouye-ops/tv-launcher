package com.carter.glasslauncher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Queries installed, launchable apps via both LEANBACK_LAUNCHER (TV apps) and LAUNCHER
 * (phone-style apps sideloaded onto the Fire TV) intents, merges them, pins a few streaming
 * apps first, appends everything else alphabetically, and always makes sure the Amazon
 * Appstore and system Settings are present. Finally applies the user's saved custom order
 * and hidden set from [TileOrderPrefs].
 */
class InstalledAppsRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val prefs = TileOrderPrefs(context)

    // Pin order: Disney+, Hulu, Max, Netflix, then alphabetical.
    private val pinnedPackages = listOf(
        "com.disney.disneyplus",
        "com.hulu.plus",
        "com.wbd.stream",
        "com.hbo.hbonow",
        "com.netflix.ninja",
        "com.netflix.mediaclient"
    )

    private val amazonAppstorePackage = "com.amazon.venezia"

    /** Replaced in the dock by the Xbox / HDMI 1 tile. */
    private val alexaSmartHomePackage = "com.amazon.smarthomemapviewapp"

    /**
     * Suspending because the two intent queries, and the per-app label lookups inside each,
     * all run in parallel on the IO dispatcher. Resolving labels serially meant ~2s of
     * cross-package resource reads before the row could be shown.
     */
    suspend fun loadApps(): List<AppInfo> = coroutineScope {
        val merged = LinkedHashMap<String, AppInfo>() // keyed by packageName

        val leanback = async { queryLaunchable(Intent.CATEGORY_LEANBACK_LAUNCHER) }
        val launcher = async { queryLaunchable(Intent.CATEGORY_LAUNCHER) }

        leanback.await().forEach { info -> merged.putIfAbsent(info.packageName, info) }
        launcher.await().forEach { info -> merged.putIfAbsent(info.packageName, info) }

        // Always ensure Amazon Appstore and Settings exist, even if hidden from launcher queries.
        ensureAppstore(merged)
        ensureSettings(merged)

        // The Xbox tile takes the Alexa Smart Home app's place. It is swapped in *here*,
        // before ordering, so it is an ordinary member of the list: that is what lets the
        // user move it and have the new position persist like any other tile.
        merged.remove(alexaSmartHomePackage)
        merged[AppInfo.ID_XBOX_HDMI] = buildXboxTile()

        val hidden = prefs.getHidden()
        val customOrder = prefs.getCustomOrder()

        val ordered = orderApps(merged.values.toList())
            .filterNot { hidden.contains(it.id) }

        val finalList = if (customOrder.isEmpty()) {
            ordered
        } else {
            applyCustomOrder(ordered, customOrder)
        }

        // Hidden "Fire TV Home" utility tile always goes last, regardless of hide/order prefs.
        finalList + buildFireTvHomeTile()
    }

    private suspend fun queryLaunchable(category: String): List<AppInfo> = coroutineScope {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        val results: List<ResolveInfo> = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        } catch (e: Exception) {
            pm.queryIntentActivities(intent, 0)
        }
        results
            .map { resolveInfo -> async(Dispatchers.IO) { toAppInfo(resolveInfo, category) } }
            .awaitAll()
            .filterNotNull()
    }

    private fun toAppInfo(resolveInfo: ResolveInfo, category: String): AppInfo? {
        val pkg = resolveInfo.activityInfo?.packageName ?: return null
        if (pkg == context.packageName) return null // never list ourselves
        val label = resolveInfo.activityInfo.nonLocalizedLabel?.toString()
            ?: resolveInfo.loadLabel(pm)?.toString()
            ?: pkg
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(category)
                setClassName(pkg, resolveInfo.activityInfo.name)
            }
        return AppInfo(
            id = pkg,
            packageName = pkg,
            activityName = resolveInfo.activityInfo.name,
            label = label,
            launchIntent = launchIntent
        )
    }

    private fun ensureAppstore(merged: LinkedHashMap<String, AppInfo>) {
        if (merged.containsKey(amazonAppstorePackage)) return
        val launchIntent = pm.getLaunchIntentForPackage(amazonAppstorePackage) ?: return
        val appInfo = try {
            pm.getApplicationInfo(amazonAppstorePackage, 0)
        } catch (e: Exception) {
            null
        } ?: return
        merged[amazonAppstorePackage] = AppInfo(
            id = amazonAppstorePackage,
            packageName = amazonAppstorePackage,
            activityName = launchIntent.component?.className,
            label = pm.getApplicationLabel(appInfo).toString(),
            launchIntent = launchIntent
        )
    }

    private fun ensureSettings(merged: LinkedHashMap<String, AppInfo>) {
        val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
        val resolved = pm.resolveActivity(settingsIntent, 0) ?: return
        val pkg = resolved.activityInfo.packageName
        if (merged.containsKey(pkg)) return
        merged[pkg] = AppInfo(
            id = pkg,
            packageName = pkg,
            activityName = resolved.activityInfo.name,
            label = "Settings",
            launchIntent = settingsIntent
        )
    }

    private fun orderApps(apps: List<AppInfo>): List<AppInfo> {
        val pinned = mutableListOf<AppInfo>()
        pinnedPackages.forEach { pkg ->
            apps.firstOrNull { it.packageName == pkg }?.let { if (it !in pinned) pinned.add(it) }
        }
        // Xbox has no package of its own, so it is pinned by id rather than by package.
        apps.firstOrNull { it.id == AppInfo.ID_XBOX_HDMI }
            ?.let { if (it !in pinned) pinned.add(it) }
        val rest = apps.filterNot { pinned.contains(it) }.sortedBy { it.label.lowercase() }
        return pinned + rest
    }

    private fun applyCustomOrder(apps: List<AppInfo>, customOrder: List<String>): List<AppInfo> {
        val byId = apps.associateBy { it.id }
        val ordered = customOrder.mapNotNull { byId[it] }
        val remaining = apps.filterNot { customOrder.contains(it.id) }
        return ordered + remaining
    }

    private fun buildXboxTile(): AppInfo {
        return AppInfo(
            id = AppInfo.ID_XBOX_HDMI,
            packageName = "",
            activityName = null,
            label = context.getString(com.carter.glasslauncher.R.string.tile_xbox),
            launchIntent = null,
            isSpecial = true
        )
    }

    private fun buildFireTvHomeTile(): AppInfo {
        return AppInfo(
            id = AppInfo.ID_FIRE_TV_HOME,
            packageName = "com.amazon.tv.launcher",
            activityName = null,
            label = context.getString(com.carter.glasslauncher.R.string.tile_fire_tv_home),
            launchIntent = null,
            isSpecial = true
        )
    }
}
