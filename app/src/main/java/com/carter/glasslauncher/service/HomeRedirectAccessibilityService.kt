package com.carter.glasslauncher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.carter.glasslauncher.ui.MainActivity

/**
 * Fire TV won't let a third-party app become the system default launcher, so instead we
 * watch for the Amazon home launcher coming to the foreground and immediately bring our
 * own launcher activity back on top. We also try to intercept KEYCODE_HOME directly in
 * case the system delivers raw key events to accessibility services.
 */
class HomeRedirectAccessibilityService : AccessibilityService() {
    private val labelHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val captureInputLabels = Runnable {
        com.carter.glasslauncher.data.HdmiLabelSync.capture(this, rootInActiveWindow)
    }


    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 0
        }
        labelHandler.postDelayed(captureInputLabels, 300)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName == com.carter.glasslauncher.data.HdmiLabelSync.INPUT_PACKAGE) {
            labelHandler.removeCallbacks(captureInputLabels)
            labelHandler.postDelayed(captureInputLabels, 180)
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (packageName != AMAZON_HOME_PACKAGE) return
        // com.amazon.tv.launcher also hosts Settings, search, etc. under the same package,
        // so only redirect when the actual home screen activity is what came forward --
        // otherwise pressing a pill that opens Settings would immediately get yanked back here.
        val className = event.className?.toString().orEmpty()
        val looksLikeHomeScreen = HOME_ACTIVITY_HINTS.any { className.contains(it, ignoreCase = true) }
        if (looksLikeHomeScreen && !isRedirectPaused()) {
            launchOurHome()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_HOME && event.action == KeyEvent.ACTION_DOWN) {
            if (!isRedirectPaused()) {
                launchOurHome()
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onDestroy() {
        labelHandler.removeCallbacks(captureInputLabels)
        super.onDestroy()
    }

    override fun onInterrupt() {
        // No-op.
    }

    private fun isRedirectPaused(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return System.currentTimeMillis() < prefs.getLong(KEY_PAUSED_UNTIL, 0L)
    }

    private fun launchOurHome() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to redirect home", e)
        }
    }

    companion object {
        private const val TAG = "HomeRedirect"
        const val AMAZON_HOME_PACKAGE = "com.amazon.tv.launcher"
        private const val PREFS_NAME = "home_redirect_prefs"
        private const val KEY_PAUSED_UNTIL = "paused_until"

        // Observed across Fire OS versions: "HomeActivity_vNext" (Fire OS 7/8) and plain
        // "HomeActivity" on older builds. Deliberately does NOT match "Settings", "Search",
        // etc. so those stay usable when reached via our pills.
        private val HOME_ACTIVITY_HINTS = listOf("HomeActivity", ".home.")

        /** Called by the hidden "Fire TV Home" tile before it launches the Amazon home. */
        fun pauseRedirectFor(context: Context, millis: Long) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_PAUSED_UNTIL, System.currentTimeMillis() + millis)
                .apply()
        }
    }
}
