package com.carter.glasslauncher.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carter.glasslauncher.util.AccessibilityHealer

/**
 * A reboot is the other point where the home-redirect accessibility grant can come back
 * cleared. Re-assert it as soon as the box finishes booting, so the Home button keeps
 * landing on this launcher without anyone having to re-run an adb command.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" ->
                AccessibilityHealer.ensureEnabled(context.applicationContext)
        }
    }
}
