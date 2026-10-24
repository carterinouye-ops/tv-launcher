package com.carter.glasslauncher.service

import android.app.Notification
import android.content.ComponentName
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.carter.glasslauncher.data.WatchHistoryRepository
import com.carter.glasslauncher.util.AccessibilityHealer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Records what's been watched, and doubles as the watchdog that keeps the home-redirect
 * accessibility service enabled.
 *
 * Watch history is harder than "listen to getActiveSessions()" on Fire TV. Observed on this
 * box (Fire OS 8.1.8 / API 30) with Netflix:
 *
 *  - its MediaSession reports `active=false`, so it never appears in getActiveSessions()
 *    at all, and
 *  - its `metadata` is null, so even holding a controller yields no title.
 *
 * So three sources feed the same [recordFrom] sink, and whichever produces a usable title
 * first wins (the repository de-dupes on package+title):
 *
 *  1. active sessions from MediaSessionManager, with a per-session MediaController.Callback,
 *  2. MediaSession tokens pulled out of MediaStyle notifications -- this reaches sessions
 *     that never go "active", and
 *  3. the notification's own title/text/large-icon extras, for apps that publish a media
 *     notification but no usable session metadata at all.
 *
 * A periodic poll refreshes playback position so "12m left" stays roughly honest.
 */
class MediaWatchNotificationListenerService : NotificationListenerService() {

    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var repository: WatchHistoryRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val controllerCallbacks = mutableMapOf<MediaSession.Token, MediaController.Callback>()
    private val controllers = mutableMapOf<MediaSession.Token, MediaController>()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Watchdog for the home-redirect accessibility service. Fire OS cleared that grant on
     * its own (no reboot involved), which silently killed the Home-button redirect. This
     * listener is system-bound, outlives the launcher UI, and survived that same event --
     * which makes it the right place to notice the grant vanishing and put it back.
     */
    private val accessibilitySettingObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                AccessibilityHealer.ensureEnabled(applicationContext)
            }
        }

    private val periodicCheck = object : Runnable {
        override fun run() {
            AccessibilityHealer.ensureEnabled(applicationContext)
            refreshActiveSessions()
            pollControllers()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            Log.d(TAG, "onActiveSessionsChanged: ${list?.size ?: 0} session(s)")
            list?.forEach { adoptController(it, "activeSessions") }
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener connected")
        repository = WatchHistoryRepository(applicationContext)
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)

        registerAccessibilityWatchdog()

        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener, listenerComponent()
            )
            Log.i(TAG, "Registered OnActiveSessionsChangedListener")
        } catch (e: SecurityException) {
            Log.e(TAG, "Not allowed to observe media sessions", e)
        }
        refreshActiveSessions()

        // Media notifications already on screen when we connect still carry session tokens.
        try {
            activeNotifications?.forEach { handleNotification(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read existing notifications", e)
        }
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "Listener disconnected")
        handler.removeCallbacksAndMessages(null)
        runCatching { contentResolver.unregisterContentObserver(accessibilitySettingObserver) }
        runCatching {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        controllers.forEach { (token, controller) ->
            controllerCallbacks[token]?.let { runCatching { controller.unregisterCallback(it) } }
        }
        controllers.clear()
        controllerCallbacks.clear()
        serviceScope.cancel()
        super.onListenerDisconnected()
    }

    private fun listenerComponent() =
        ComponentName(this, MediaWatchNotificationListenerService::class.java)

    // region media sessions

    private fun refreshActiveSessions() {
        val sessions = try {
            mediaSessionManager.getActiveSessions(listenerComponent())
        } catch (e: SecurityException) {
            Log.e(TAG, "getActiveSessions denied -- is the listener still granted?", e)
            return
        } catch (e: Exception) {
            Log.w(TAG, "getActiveSessions failed", e)
            return
        }
        Log.d(TAG, "getActiveSessions -> ${sessions.size}: ${sessions.map { it.packageName }}")
        sessions.forEach { adoptController(it, "activeSessions") }
    }

    /** Starts tracking a controller (idempotent) and takes an immediate reading. */
    private fun adoptController(controller: MediaController, source: String) {
        val token = controller.sessionToken
        if (!controllers.containsKey(token)) {
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    Log.d(TAG, "onMetadataChanged ${controller.packageName} title=${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}")
                    recordFromController(controller, "metadataChanged")
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    // Persist on pause/stop too, not just while playing -- pausing is
                    // exactly when "continue watching" wants the position captured.
                    Log.d(TAG, "onPlaybackStateChanged ${controller.packageName} state=${state?.state} pos=${state?.position}")
                    recordFromController(controller, "playbackStateChanged")
                }

                override fun onSessionDestroyed() {
                    Log.d(TAG, "onSessionDestroyed ${controller.packageName}")
                    controllers.remove(token)
                    controllerCallbacks.remove(token)
                }
            }
            try {
                controller.registerCallback(callback)
                controllers[token] = controller
                controllerCallbacks[token] = callback
                Log.i(TAG, "Tracking session ${controller.packageName} (via $source)")
            } catch (e: Exception) {
                Log.w(TAG, "Could not register callback for ${controller.packageName}", e)
                return
            }
        }
        recordFromController(controller, source)
    }

    /** Re-reads every tracked controller, mainly to refresh playback position. */
    private fun pollControllers() {
        controllers.values.toList().forEach { recordFromController(it, "poll") }
    }

    private fun recordFromController(controller: MediaController, source: String) {
        val pkg = controller.packageName ?: return
        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()

        if (title.isNullOrBlank()) {
            Log.d(TAG, "skip $pkg ($source): session has no title (metadata=${if (metadata == null) "null" else "present"})")
            return
        }

        val subtitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        val position = controller.playbackState?.position?.coerceAtLeast(0) ?: 0L

        save(pkg, title, subtitle, artwork, position, duration, source)
    }

    // endregion

    // region notifications

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { handleNotification(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Nothing to do: history is already persisted by this point.
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        // A MediaStyle notification carries the session token, which reaches sessions that
        // never report themselves as "active".
        val token = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        }.getOrNull()

        if (token != null) {
            Log.d(TAG, "Media notification from $pkg carries a session token")
            runCatching { MediaController(this, token) }
                .onSuccess { adoptController(it, "notificationToken") }
                .onFailure { Log.w(TAG, "Could not build controller from token", it) }
        }

        // Fall back to the notification's own content when the session gave us nothing.
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        if (title.isNullOrBlank()) return
        if (token == null && !looksLikeMediaNotification(notification)) {
            return // avoid hoovering up recommendation/promo notifications
        }

        val subtitle = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        val artwork = extractLargeIcon(extras)

        save(pkg, title, subtitle, artwork, 0L, 0L, "notification")
    }

    /**
     * Only treat a notification as media if it declares the transport category or uses the
     * MediaStyle template. Twitch and the Appstore post plain "recommendation" notifications
     * with titles that would otherwise pollute Continue Watching.
     */
    private fun looksLikeMediaNotification(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        val template = notification.extras?.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        return template.contains("MediaStyle", ignoreCase = true)
    }

    private fun extractLargeIcon(extras: android.os.Bundle): Bitmap? {
        @Suppress("DEPRECATION")
        (extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Bitmap)?.let { return it }
        val icon = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_LARGE_ICON) as? Icon
        }.getOrNull() ?: return null
        return runCatching {
            (icon.loadDrawable(this) as? BitmapDrawable)?.bitmap
        }.getOrNull()
    }

    // endregion

    private fun save(
        pkg: String,
        title: String,
        subtitle: String?,
        artwork: Bitmap?,
        position: Long,
        duration: Long,
        source: String
    ) {
        Log.i(
            TAG,
            "SAVE [$source] pkg=$pkg title=\"$title\" sub=\"${subtitle.orEmpty()}\" " +
                "pos=${position}ms dur=${duration}ms art=${artwork != null}"
        )
        serviceScope.launch {
            runCatching {
                repository.recordPlayback(
                    packageName = pkg,
                    title = title,
                    subtitle = subtitle,
                    artwork = artwork,
                    positionMs = position,
                    durationMs = duration,
                    deepLinkUri = null
                )
            }.onFailure { Log.e(TAG, "Failed to persist watch history for $pkg", it) }
        }
    }

    private fun registerAccessibilityWatchdog() {
        AccessibilityHealer.ensureEnabled(applicationContext)
        handler.removeCallbacks(periodicCheck)
        handler.postDelayed(periodicCheck, WATCHDOG_INTERVAL_MS)
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false, accessibilitySettingObserver
            )
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false, accessibilitySettingObserver
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not observe accessibility settings", e)
        }
    }

    companion object {
        private const val TAG = "MediaWatchListener"
        private const val WATCHDOG_INTERVAL_MS = 15_000L
    }
}
