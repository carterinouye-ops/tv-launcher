package com.carter.glasslauncher.util

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.carter.glasslauncher.service.HomeRedirectAccessibilityService

/**
 * Keeps our home-redirect accessibility service switched on.
 *
 * Fire OS clears `enabled_accessibility_services` out from under third-party services --
 * observed here with no reboot involved (the box had 17 days of uptime), which silently
 * killed the Home-button redirect and dropped the user back on Amazon's launcher.
 *
 * Re-enabling needs WRITE_SECURE_SETTINGS, which is granted once over adb:
 *   adb shell pm grant com.carter.glasslauncher android.permission.WRITE_SECURE_SETTINGS
 *
 * Without that permission every call here is a no-op that just logs, so the app still runs
 * fine -- it simply can't repair itself.
 */
object AccessibilityHealer {

    private const val TAG = "AccessibilityHealer"
    private const val MIN_REPAIR_INTERVAL_MS = 5_000L
    private const val RETRY_GRACE_MS = 250L

    @Volatile
    private var lastRepairAt = 0L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var retryScheduled = false

    /**
     * A repair that gets rate-limited must not simply be dropped: the two halves of the
     * setting are often cleared back to back, and the second change would otherwise land
     * inside the cooldown and never be retried, leaving the service listed but switched
     * off. Re-check once the cooldown expires.
     */
    private fun scheduleRetry(context: Context, delayMs: Long) {
        if (retryScheduled) return
        retryScheduled = true
        val appContext = context.applicationContext
        handler.postDelayed({
            retryScheduled = false
            ensureEnabled(appContext)
        }, delayMs)
    }

    private fun ourService(context: Context): String =
        ComponentName(context, HomeRedirectAccessibilityService::class.java).flattenToString()

    /**
     * Healthy means both halves are set: our component is in the enabled-services list AND
     * the global accessibility master switch is on. Fire OS has been seen clearing either
     * one independently, and with the master switch off the service stays unbound even
     * though the list still names it.
     */
    fun isEnabled(context: Context): Boolean =
        isListed(context) && isMasterSwitchOn(context)

    private fun isListed(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val ours = ourService(context)
        return enabled.split(':').any { it.equals(ours, ignoreCase = true) }
    }

    private fun isMasterSwitchOn(context: Context): Boolean = try {
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) == 1
    } catch (e: Settings.SettingNotFoundException) {
        false
    }

    /**
     * Adds our service back to the enabled list if it's missing, preserving any other
     * services the user has on (VoiceView, screen magnifier, ...). Rate-limited so a
     * system component that insists on disabling us can't spin us into a tight loop.
     *
     * @return true if a repair was actually written.
     */
    fun ensureEnabled(context: Context): Boolean {
        if (isEnabled(context)) return false

        val now = System.currentTimeMillis()
        val sinceLast = now - lastRepairAt
        if (sinceLast < MIN_REPAIR_INTERVAL_MS) {
            scheduleRetry(context, MIN_REPAIR_INTERVAL_MS - sinceLast + RETRY_GRACE_MS)
            return false
        }
        lastRepairAt = now

        return try {
            val resolver = context.contentResolver
            if (!isListed(context)) {
                val current = Settings.Secure.getString(
                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ).orEmpty()
                val entries = current.split(':').filter { it.isNotBlank() }
                val updated = (entries + ourService(context)).joinToString(":")
                Settings.Secure.putString(
                    resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated
                )
            }
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Re-enabled home-redirect accessibility service")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot self-heal: WRITE_SECURE_SETTINGS not granted", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-enable accessibility service", e)
            false
        }
    }
}
