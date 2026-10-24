package com.carter.glasslauncher.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Fire OS doesn't expose a public API for "open the HDMI input picker", so we probe a
 * handful of known Settings-app components used across Fire OS 7/8 and fall back to the
 * general Fire TV settings screen if none resolve.
 */
object FireTvIntents {
    private const val TAG = "FireTvIntents"

    private val inputPickerComponents = listOf(
        "com.amazon.tv.settings/.tv.inputs.InputsActivity",
        "com.amazon.tv.settings.v2/.tv.inputs.InputsActivity",
        "com.amazon.tv.settings/.tv.inputs.external.InputsActivity",
        "com.amazon.tv.settings/.inputs.InputsActivity"
    )

    fun openInputsOrSettings(context: Context) {
        for (component in inputPickerComponents) {
            if (tryStart(context, Intent().apply {
                    component.split("/").let { parts ->
                        setClassName(parts[0], if (parts[1].startsWith(".")) parts[0] + parts[1] else parts[1])
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            ) return
        }
        openSettings(context)
    }

    /** Wi-Fi pill: jump straight to network settings, falling back to top-level settings. */
    fun openWifiSettings(context: Context) {
        val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (!tryStart(context, wifiIntent)) openSettings(context)
    }

    fun openSettings(context: Context) {
        // Fire TV briefly shows Home while opening Settings. Let that transition finish.
        com.carter.glasslauncher.service.HomeRedirectAccessibilityService.pauseRedirectFor(context, 2500L)
        val intent = Intent(Settings.ACTION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        tryStart(context, intent)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to start $intent", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start $intent", e)
            false
        }
    }
}
