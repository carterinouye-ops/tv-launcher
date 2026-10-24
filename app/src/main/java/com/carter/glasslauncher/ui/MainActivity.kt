package com.carter.glasslauncher.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
import com.carter.glasslauncher.util.ClockDistanceField
import androidx.core.view.doOnPreDraw
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.carter.glasslauncher.R
import com.carter.glasslauncher.data.AccentColorCache
import android.opengl.GLSurfaceView
import android.view.View
import com.carter.glasslauncher.data.AppInfo
import com.carter.glasslauncher.data.EpisodeNews
import com.carter.glasslauncher.data.EpisodeNewsRepository
import com.carter.glasslauncher.data.ShowCandidate
import com.carter.glasslauncher.data.TmdbArtworkRepository
import com.carter.glasslauncher.data.WatchHistoryRepository
import com.carter.glasslauncher.data.InstalledAppsRepository
import com.carter.glasslauncher.data.TileOrderPrefs
import com.carter.glasslauncher.databinding.ActivityMainBinding
import com.carter.glasslauncher.service.HomeRedirectAccessibilityService
import com.carter.glasslauncher.ui.adapter.AppTileAdapter
import com.carter.glasslauncher.util.AccessibilityHealer
import com.carter.glasslauncher.util.FireTvIntents
import com.carter.glasslauncher.gl.GlassRenderer
import com.carter.glasslauncher.util.ScrimDrawable
import com.carter.glasslauncher.util.StackBlur
import com.carter.glasslauncher.util.applyGlassShadowOnly
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date

/**
 * Apple-TV style home screen: rotating photo wallpaper, a greeting, a sliding segmented
 * nav, and a frosted dock of apps along the bottom.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appsRepo: InstalledAppsRepository
    private lateinit var tilePrefs: TileOrderPrefs
    private lateinit var accentCache: AccentColorCache
    private lateinit var wallpaper: WallpaperController
    private lateinit var watchHistory: WatchHistoryRepository
    private lateinit var episodeNews: EpisodeNewsRepository
    private lateinit var episodeArt: TmdbArtworkRepository
    private lateinit var spotlightController: SpotlightController

    private val glassRenderer = GlassRenderer()
    private lateinit var inputMorph: InputMorphController
    private var inputHoverJob: Job? = null
    private var inputMorphProgress = 0f
    private var inputWidgetHandoff = false

    /** Kept so a panel's backdrop can be re-cut when that panel changes size. */
    private var currentWallpaper: android.graphics.Bitmap? = null
    private lateinit var dockAdapter: AppTileAdapter

    /** Id of the tile currently picked up for rearranging, or null. */
    private var heldTileId: String? = null

    /** Guards against a slow lookup landing after the cursor has moved on. */
    private var spotlightRequest = 0
    private var spotlightJob: Job? = null
    private var spotlightPackage: String? = null
    private var focusedDockApp: AppInfo? = null
    private var idleClockJob: Job? = null
    private var idleClockShowing = false
    private var launcherActive = false

    /** Remote input restarts the idle countdown, including repeats on the same tile. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!launcherActive) return
        val restorePreview = idleClockShowing
        idleClockShowing = false
        restartIdleClockTimer()
        if (restorePreview) binding.root.post {
            if (launcherActive && !idleClockShowing && binding.dockRow.hasFocus())
                focusedDockApp?.let { showSpotlightFor(it) }
        }
    }

    private fun restartIdleClockTimer() {
        idleClockJob?.cancel()
        idleClockJob = lifecycleScope.launch {
            delay(20_000L)
            if (!launcherActive) return@launch
            // Let a held tile or an open dialog finish without stealing its focus.
            if (heldTileId != null || !hasWindowFocus()) {
                restartIdleClockTimer()
                return@launch
            }
            idleClockShowing = true
            spotlightRequest++
            spotlightJob?.cancel()
            inputHoverJob?.cancel()
            if (inputMorph.isOpen) {
                inputMorph.close(restoreNavFocus = false)
                binding.nav.focusSegment(NAV_HOME)
            }
            spotlightController.show(null)
        }
    }

    /** 0 = the card is the clock's glyphs, 1 = the card is the widget panel. */
    private var cardMorphProgress = 0f
    private var clockMaskGeneration = 0
    private var clockMaskRefreshQueued = false

    /** The held tile's holder, kept so focus can follow the tile itself across moves. */
    private var heldHolder: AppTileAdapter.ViewHolder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appsRepo = InstalledAppsRepository(this)
        tilePrefs = TileOrderPrefs(this)
        accentCache = AccentColorCache(this)
        watchHistory = WatchHistoryRepository(this)
        episodeNews = EpisodeNewsRepository(this)
        episodeArt = TmdbArtworkRepository(this)

        binding.glGlass.setEGLContextClientVersion(3)
        // This is a 2D renderer: depth and stencil buffers only waste bandwidth.
        binding.glGlass.setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        binding.glGlass.preserveEGLContextOnPause = true
        // Render the glass and wallpaper at the full 1080p canvas resolution.
        binding.glGlass.holder.setFixedSize(GL_SURFACE_WIDTH, GL_SURFACE_HEIGHT)
        binding.glGlass.setRenderer(glassRenderer)
        binding.glGlass.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        wallpaper = WallpaperController(
            context = this,
            top = binding.wallpaperA,
            bottom = binding.wallpaperB,
            tint = binding.wallpaperTint
        )
        wallpaper.onWallpaperReady = { bitmap -> uploadWallpaperToGl(bitmap) }

        // The same screen shading is composed in the GL passes, avoiding a separate
        // full-screen translucent Android layer on every animation frame.
        binding.wallpaperScrim.visibility = View.GONE
        // One recipe for all three panels, taken from the dock. Only the corner radius
        // differs, and that is geometry rather than material: the nav is a short pill and
        // wants a radius near half its height, the other two are panels.
        binding.dock.applyGlassShadowOnly(DOCK_CORNER_DP, GLASS_ELEVATION_DP)
        // Deliberate exception, not drift: the centre card is flattened on request. It
        // keeps the shared blur and lens, and gives up only the cues that make a panel
        // read as a raised, thick-edged object.
        binding.clockCard.applyGlassShadowOnly(CLOCK_CORNER_DP, CARD_ELEVATION_DP)
        spotlightController = SpotlightController(
            card = binding.clockCard,
            clockContent = binding.clockContent,
            spotlightContent = binding.spotlightContent,
            artFrame = binding.spotlightArtFrame,
            art = binding.spotlightArt,
            badge = binding.spotlightBadge,
            title = binding.spotlightTitle,
            subtitle = binding.spotlightSubtitle,
            status = binding.spotlightStatus,
            progress = binding.spotlightProgress,
            button = binding.spotlightButton,
            onMorphProgress = { progress -> onCardMorphProgress(progress) }
        )
        syncGlassGeometryEachFrame()
        // The clock text itself is not drawn: the shader paints its silhouette in glass.
        binding.clockTime.alpha = 0f
        scheduleClockMaskRefresh()
        binding.clockTime.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) scheduleClockMaskRefresh()
        }
        binding.clockTime.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = scheduleClockMaskRefresh()
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) = Unit
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) = Unit
        })
        setupNav()
        setupDock()
        setupBackButton()
    }

    override fun onResume() {
        super.onResume()
        binding.glGlass.onResume()
        scheduleClockMaskRefresh()
        AccessibilityHealer.ensureEnabled(this)
        wallpaper.start()
        loadApps()
        // Returning from an Intent should leave the nav resting on Home again.
        binding.nav.setSelected(NAV_HOME)
        inputMorph.start()
        launcherActive = true
        idleClockShowing = false
        restartIdleClockTimer()
    }

    override fun onPause() {
        super.onPause()
        launcherActive = false
        idleClockJob?.cancel()
        inputHoverJob?.cancel()
        inputWidgetHandoff = false
        inputMorph.stop()
        inputHoverJob?.cancel()
        binding.glGlass.onPause()
        wallpaper.stop()
        spotlightRequest++
        spotlightJob?.cancel()
        spotlightController.show(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        wallpaper.release()
        spotlightController.dispose()
    }

    // region setup

    private fun setupBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inputWidgetHandoff) {
                    spotlightRequest++
                    spotlightJob?.cancel()
                    spotlightController.show(null)
                    binding.nav.focusSegment(NAV_HOME)
                } else if (inputMorph.isOpen) inputMorph.close()
            }
        })
    }

    private fun setupNav() {
        inputMorph = InputMorphController(binding.root, binding.nav, binding.clockCard, binding.dock,
            onProgress = { progress ->
                inputMorphProgress = progress
                updateSpotlightReveal()
                binding.root.postInvalidateOnAnimation()
            },
            onChoose = { input ->
                startActivitySafely(Intent(this, HdmiInputActivity::class.java)
                    .putExtra(HdmiInputActivity.EXTRA_INPUT_ID, input.id))
            },
            onExitToDock = { binding.dockRow.requestFocus() }
        )
        binding.nav.onSegmentFocused = { index ->
            inputHoverJob?.cancel()
            if (index >= 0) {
                showSpotlightFor(null)
                binding.nav.setSelected(index)
                if (index != NAV_INPUTS) inputMorph.close(restoreNavFocus = false)
            }
            if (index == NAV_INPUTS) inputHoverJob = lifecycleScope.launch {
                delay(180)
                while (inputWidgetHandoff) delay(16)
                if (hasWindowFocus() && binding.nav.focusedSegment() == NAV_INPUTS) inputMorph.open()
            }
        }
        binding.nav.onSegmentDown = { index ->
            if (index == NAV_INPUTS) {
                inputHoverJob?.cancel()
                showSpotlightFor(null)
                inputMorph.enterChoices()
                true
            } else false
        }
        binding.nav.setSegments(
            listOf(
                getString(R.string.nav_home),
                getString(R.string.nav_inputs),
                getString(R.string.nav_settings)
            )
        )
        binding.nav.onSegmentChosen = { index ->
            when (index) {
                NAV_HOME -> binding.dockRow.requestFocus()
                NAV_INPUTS -> { inputHoverJob?.cancel(); showSpotlightFor(null); inputMorph.enterChoices() }
                NAV_SETTINGS -> FireTvIntents.openSettings(this)
            }
        }
    }

    private fun setupDock() {
        dockAdapter = AppTileAdapter(
            scope = lifecycleScope,
            accentFor = { app -> accentCache.get(app.id) ?: colorFromSeed(app.id) },
            cachedAccentFor = { id -> accentCache.get(id) },
            onAccentExtracted = { id, color -> accentCache.putAll(mapOf(id to color)) },
            onClick = { app -> handleTileClick(app) },
            onLongPress = { app, _ -> beginRearrange(app) },
            onMenu = { app -> showTileContextMenu(app) },
            onFocused = { app ->
                focusedDockApp = app
                if (!idleClockShowing || app == null) showSpotlightFor(app)
            }
        )
        // Paged, not keyline-scrolled: the dock advances a whole page of tiles at a time.
        binding.dockRow.itemsPerPage = RowMetrics.ITEMS_ACROSS
        binding.dockRow.adapter = dockAdapter
        binding.dockRow.onRearrangeRequested = {
            val position = binding.dockRow.focusedAdapterPosition()
            dockAdapter.itemAt(position)?.let { beginRearrange(it) }
        }
        binding.dockRow.onRearrangeMove = { direction -> moveHeldTile(direction) }
        binding.dockRow.onRearrangeCommit = { endRearrange() }
    }

    // endregion

    /**
     * Hands the GPU the wallpaper plus a blurred copy of it. The blur is still made once on
     * the CPU: it does not change between frames, and a real gaussian every frame would not
     * fit this device's budget. Everything that *does* change per frame -- the displacement,
     * the rim, the frost mix -- is evaluated in the shader.
     */
    private fun uploadWallpaperToGl(bitmap: android.graphics.Bitmap) {
        lifecycleScope.launch {
            val blurred = withContext(Dispatchers.Default) {
                runCatching {
                    StackBlur.blurAndDarken(
                        source = bitmap, downscale = 8, passes = 3, radius = 5, darkenAlpha = 0
                    )
                }.getOrNull()
            } ?: return@launch
            glassRenderer.setWallpaper(bitmap, blurred)
            binding.glGlass.requestRender()
            // The GL layer owns the wallpaper now; the ImageViews would only overdraw it.
            binding.wallpaperA.visibility = View.GONE
            binding.wallpaperB.visibility = View.GONE
        }
    }

    /**
     * Shows what is waiting inside the app the cursor is sitting on, or returns the card
     * to the clock when the dock loses focus or that app has nothing to resume.
     *
     * Scoped to one app deliberately: the point of hovering Netflix is to see what is on
     * in Netflix, not whatever happened to be watched most recently anywhere.
     */
    private fun showSpotlightFor(app: AppInfo?) {
        val requestId = ++spotlightRequest
        spotlightJob?.cancel()
        val packageName = app?.packageName
        val streaming = app != null && app.packageName in STREAMING_PACKAGES
        val hasInputGlass = inputMorphProgress > 0f
        if (spotlightPackage != packageName && !inputWidgetHandoff && !streaming)
            spotlightController.show(null)
        spotlightPackage = packageName
        if (!streaming) {
            spotlightController.show(null)
            if (app != null && inputMorph.isOpen) inputMorph.close(restoreNavFocus = false)
            return
        }
        if (hasInputGlass && !inputWidgetHandoff) inputMorph.holdForSpotlight()
        spotlightJob = lifecycleScope.launch {
            delay(180)
            val spotlight = runCatching { resolveSpotlightFor(app!!.packageName) }.getOrNull()
            if (requestId != spotlightRequest) return@launch
            if (spotlight != null && inputMorphProgress > 0f && !inputWidgetHandoff) {
                inputMorph.beginWidgetHandoff()
                inputWidgetHandoff = true
                spotlightController.showFromInputs(spotlight)
            } else {
                if (spotlight == null && inputMorph.isOpen) inputMorph.close(restoreNavFocus = false)
                spotlightController.show(spotlight)
            }
        }
    }

    private suspend fun resolveSpotlightFor(packageName: String): Spotlight? {
        val entry = runCatching { watchHistory.latestFor(packageName) }.getOrNull() ?: return null

        if (entry.title.isBlank()) return null
        val candidates = ShowCandidate(entry.title, entry.subtitle).names()
        val resolved = runCatching { episodeArt.resolve(candidates) }.getOrNull()
        val show = resolved?.name ?: candidates.firstOrNull() ?: entry.title
        val artwork = decodeArtwork(resolved?.artwork ?: episodeArt.backdropFor(show))
        val numbering = seasonEpisodeIn(entry.title) ?: seasonEpisodeIn(entry.subtitle)

        val partWatched = entry.durationMs > 0 &&
            entry.positionMs > MIN_PROGRESS_MS &&
            entry.positionMs < entry.durationMs * WATCHED_THRESHOLD
        if (partWatched) {
            val remaining = entry.durationMs - entry.positionMs
            return Spotlight(
                continueWatching = true,
                show = show,
                season = numbering?.first,
                episode = numbering?.second,
                minutesLeft = (remaining / 60_000L).toInt().coerceAtLeast(1),
                progress = entry.positionMs.toFloat() / entry.durationMs,
                artwork = artwork,
                launch = launchIntentFor(packageName, entry.deepLinkUri)
            )
        }

        // Finished or barely started: fall back to whether a new episode has landed.
        val news = runCatching {
            episodeNews.latest(listOf(ShowCandidate(entry.title, entry.subtitle, packageName)))
        }.getOrNull() ?: return null
        if (news.upcoming) return null
        return Spotlight(
            continueWatching = false,
            show = news.show,
            season = news.season,
            episode = news.episode,
            minutesLeft = null,
            progress = null,
            artwork = decodeArtwork(episodeArt.backdropFor(news.show)) ?: artwork,
            launch = launchIntentFor(packageName, null)
        )
    }

    /**
     * Decodes the spotlight artwork off the main thread, downsampled to the frame it will
     * be shown in. Decoding it during bind stalled the UI thread right as the morph began.
     */
    private suspend fun decodeArtwork(file: java.io.File?): android.graphics.Bitmap? {
        if (file == null) return null
        val targetWidth = resources.getDimensionPixelSize(R.dimen.spotlight_art_width)
        return withContext(Dispatchers.Default) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
                android.graphics.BitmapFactory.decodeFile(
                    file.absolutePath,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
    }

    private fun launchIntentFor(packageName: String?, deepLink: String?): Intent? {
        deepLink?.let { uri ->
            return Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (packageName.isNullOrBlank()) return null
        return packageManager.getLaunchIntentForPackage(packageName)
    }

    /** Pulls "S2E5" style numbering out of whatever the media session reported. */
    private fun seasonEpisodeIn(value: String?): Pair<Int, Int>? {
        if (value == null) return null
        val match = SEASON_EPISODE.find(value) ?: return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val episode = match.groupValues[2].toIntOrNull() ?: return null
        return season to episode
    }

    /**
     * Pushes the panels' current bounds into the renderer before every frame that moves
     * them. This is what makes the glass live: while the card morphs, its rectangle changes
     * each frame and the shader re-refracts for the new shape instead of a baked image of
     * the old one being stretched.
     */
    /**
     * The card's glass is the clock's own glyphs at rest and a panel when expanded; this
     * slides between the two shapes. The drop shadow goes with it -- a rounded-rect shadow
     * under a set of floating numerals would give away a box that is not there.
     */
    private fun onCardMorphProgress(progress: Float) {
        cardMorphProgress = progress
        if (inputWidgetHandoff && progress >= 1f) {
            inputWidgetHandoff = false
            inputMorph.finishWidgetHandoff()
        } else if (inputWidgetHandoff && progress <= 0f && !spotlightController.isExpanded) {
            inputWidgetHandoff = false
            inputMorph.returnFromWidgetHandoff(binding.nav.focusedSegment() == NAV_INPUTS)
        }
        updateSpotlightReveal()
        val shadow = if (inputWidgetHandoff || spotlightController.isClockLiquid) {
            val t = ((progress - .82f) / .18f).coerceIn(0f, 1f)
            t * t * (3f - 2f * t)
        } else progress
        binding.clockCard.elevation =
            resources.displayMetrics.density * CARD_ELEVATION_DP * shadow
        // Geometry and morph amount are published together by the pre-draw listener.
        // Rendering here also would submit an obsolete frame before layout, doubling
        // GPU work during animation and putting the native UI behind the glass.
        binding.root.postInvalidateOnAnimation()
    }

    /**
     * Capture after layout, then build distances inside and between the clock's glyphs.
     * This field supplies both the liquid silhouette and the normals for clear refraction.
     */
    private fun scheduleClockMaskRefresh() {
        // TextWatcher's callback precedes TextView's new layout and auto-size pass.
        // Capturing there paints the new time using the previous digits' layout.
        clockMaskGeneration++
        if (clockMaskRefreshQueued) return
        clockMaskRefreshQueued = true
        binding.clockTime.doOnPreDraw {
            clockMaskRefreshQueued = false
            refreshClockMask()
        }
        binding.clockTime.invalidate()
    }

    private fun refreshClockMask() {
        val view = binding.clockTime
        if (view.width <= 0 || view.height <= 0) return
        val generation = clockMaskGeneration
        val text = view.text.toString()
        val colon = text.indexOf(':')
        val layout = view.layout
        val groupEdges = if (colon >= 0 && layout != null) {
            val offsets = intArrayOf(0, colon, colon + 1, text.length)
            FloatArray(4) { index ->
                ((view.compoundPaddingLeft + layout.getPrimaryHorizontal(offsets[index]) - view.scrollX) /
                    view.width).coerceIn(0f, 1f)
            }
        } else floatArrayOf(0.08f, 0.38f, 0.48f, 0.92f)
        lifecycleScope.launch {
            val source = runCatching {
                android.graphics.Bitmap.createBitmap(
                    view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888
                ).also { view.draw(android.graphics.Canvas(it)) }
            }.getOrNull() ?: return@launch

            val field = withContext(Dispatchers.Default) {
                runCatching {
                    ClockDistanceField.create(source, groupEdges)
                }.getOrNull()
            }.also { source.recycle() } ?: return@launch

            if (generation != clockMaskGeneration) {
                field.recycle()
                return@launch
            }
            val location = IntArray(2)
            view.getLocationInWindow(location)
            glassRenderer.maskLeft = location[0].toFloat()
            glassRenderer.maskTop = location[1].toFloat()
            glassRenderer.maskRight = (location[0] + view.width).toFloat()
            glassRenderer.maskBottom = (location[1] + view.height).toFloat()
            glassRenderer.setMask(field)
            binding.glGlass.requestRender()
        }
    }

    private fun updateSpotlightReveal() {
        val revealStart = if (inputWidgetHandoff) .88f else if (spotlightController.isClockLiquid) .90f else .72f
        val revealDuration = if (inputWidgetHandoff) .12f else if (spotlightController.isClockLiquid) .10f else .26f
        val t = ((cardMorphProgress - revealStart) / revealDuration).coerceIn(0f, 1f)
        binding.spotlightContent.alpha = t * t * (3f - 2f * t) *
            (if (inputWidgetHandoff) 1f else (1f - inputMorphProgress / 0.30f).coerceIn(0f, 1f))
    }

    private fun syncGlassGeometryEachFrame() {
        val panels = listOf(binding.nav, binding.clockCard, binding.dock)
        val radii = floatArrayOf(
            resources.displayMetrics.density * NAV_CORNER_DP,
            resources.displayMetrics.density * CLOCK_CORNER_DP,
            resources.displayMetrics.density * DOCK_CORNER_DP
        )
        val flatness = floatArrayOf(0f, 1f, 0f)   // only the centre card is flattened
        val location = IntArray(2)
        var trailInitialized = false
        binding.root.viewTreeObserver.addOnPreDrawListener {
            var changed = false
            panels.forEachIndexed { index, view ->
                view.getLocationInWindow(location)
                val panel = glassRenderer.panels[index]
                val left = location[0].toFloat()
                val top = location[1].toFloat()
                val right = left + view.width * view.scaleX
                val bottom = top + view.height * view.scaleY
                if (panel.left != left || panel.top != top ||
                    panel.right != right || panel.bottom != bottom
                ) {
                    changed = true
                }
                panel.left = left; panel.top = top
                panel.right = right; panel.bottom = bottom
                panel.cornerRadius = radii[index]
                panel.flatness = flatness[index]
            }
            val inputPanel = glassRenderer.panels[CARD_PANEL]
            if (inputPanel.inputProgress != inputMorphProgress) changed = true
            inputPanel.inputProgress = inputMorphProgress
            val transfer = if (inputWidgetHandoff) cardMorphProgress else -1f
            if (inputPanel.widgetTransfer != transfer) changed = true
            inputPanel.widgetTransfer = transfer
            val clockTransfer = if (spotlightController.isClockLiquid && inputMorphProgress <= 0f && !inputWidgetHandoff)
                cardMorphProgress else -1f
            if (inputPanel.clockTransfer != clockTransfer) changed = true
            inputPanel.clockTransfer = clockTransfer
            val inputOffsetY = if (::inputMorph.isInitialized) inputMorph.inputOffsetY else 0f
            if (inputPanel.inputOffsetY != inputOffsetY) changed = true
            inputPanel.inputOffsetY = inputOffsetY
            glassRenderer.displayDensity = resources.displayMetrics.density
            // The card's shape slides from the clock's glyphs to the widget's panel.
            val card = glassRenderer.panels[CARD_PANEL]
            val maskAmount = 1f - cardMorphProgress
            if (card.maskAmount != maskAmount) changed = true
            card.maskAmount = maskAmount

            // A damped copy of the card's rectangle, always chasing the real one. While
            // the card resizes the two separate, and the shader's smooth union stretches
            // between them; once it settles they coincide and the union does nothing.
            if (!trailInitialized) {
                card.trailLeft = card.left
                card.trailTop = card.top
                card.trailRight = card.right
                card.trailBottom = card.bottom
                trailInitialized = true
            }
            card.trailLeft += (card.left - card.trailLeft) * TRAIL_DAMPING
            card.trailTop += (card.top - card.trailTop) * TRAIL_DAMPING
            card.trailRight += (card.right - card.trailRight) * TRAIL_DAMPING
            card.trailBottom += (card.bottom - card.trailBottom) * TRAIL_DAMPING
            val lag = kotlin.math.abs(card.left - card.trailLeft) +
                kotlin.math.abs(card.top - card.trailTop) +
                kotlin.math.abs(card.right - card.trailRight) +
                kotlin.math.abs(card.bottom - card.trailBottom)
            val previousFlow = card.flow
            card.flow = if (lag > LAG_EPSILON) FLOW_PX else 0f
            if (card.flow != previousFlow) changed = true
            if (card.flow > 0f) {
                changed = true
                // requestRender updates only the GL surface, not this pre-draw listener.
                // Keep UI frames coming until the trailing shape has actually caught up.
                binding.root.postInvalidateOnAnimation()
            }
            glassRenderer.windowWidth = binding.root.width
            glassRenderer.windowHeight = binding.root.height
            glassRenderer.panelCount = panels.size
            if (changed) binding.glGlass.requestRender()
            true
        }
    }

    // region content

    private fun loadApps() {
        lifecycleScope.launch {
            dockAdapter.submit(appsRepo.loadApps())
        }
    }

    // endregion

    // region rearrange

    /**
     * Holding OK picks a tile up; left/right then carry it along the dock and OK puts it
     * down. The order is written to prefs on release.
     */
    private fun beginRearrange(app: AppInfo) {
        if (app.id == AppInfo.ID_FIRE_TV_HOME) return
        if (heldTileId != null) return
        val position = dockAdapter.indexOf(app.id)
        val holder = binding.dockRow.findViewHolderForAdapterPosition(position)
                as? AppTileAdapter.ViewHolder ?: return
        heldTileId = app.id
        heldHolder = holder
        binding.dockRow.rearranging = true
        binding.rowTitle.setText(R.string.rearrange_hint)
        holder.setPickedUp(true)
    }

    private fun moveHeldTile(direction: Int) {
        val id = heldTileId ?: return
        val from = dockAdapter.indexOf(id)
        if (from < 0) return
        val to = from + direction
        val held = heldHolder ?: return
        if (!dockAdapter.moveItem(from, to)) return
        binding.dockRow.carryHeldTileTo(to, held.itemView)
    }

    private fun endRearrange() {
        if (heldTileId == null) return
        heldHolder?.setPickedUp(false)
        heldHolder = null
        heldTileId = null
        binding.dockRow.rearranging = false
        binding.dockRow.snapToPage()
        binding.rowTitle.text = ""
        // The Fire TV Home tile is appended by the repository and never ordered, so it is
        // kept out of the saved arrangement.
        tilePrefs.setCustomOrder(
            dockAdapter.currentIds().filterNot { it == AppInfo.ID_FIRE_TV_HOME }
        )
    }

    // endregion

    // region actions

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start $intent", e)
            Toast.makeText(this, R.string.toast_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleTileClick(app: AppInfo) {
        if (app.id == AppInfo.ID_XBOX_HDMI) {
            startActivity(Intent(this, HdmiInputActivity::class.java))
            return
        }
        if (app.id == AppInfo.ID_FIRE_TV_HOME) {
            HomeRedirectAccessibilityService.pauseRedirectFor(this, 60_000L)
            val intent = packageManager
                .getLaunchIntentForPackage(HomeRedirectAccessibilityService.AMAZON_HOME_PACKAGE)
            if (intent != null) startActivity(intent)
            else Toast.makeText(this, "Amazon home launcher not found", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = app.launchIntent ?: return
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open ${app.label}", e)
            Toast.makeText(this, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTileContextMenu(app: AppInfo) {
        if (app.id == AppInfo.ID_FIRE_TV_HOME) return
        // Moving is done by holding OK on the tile itself; the menu is just hide/unhide.
        val options = arrayOf(
            getString(R.string.context_hide),
            getString(R.string.context_unhide)
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> hideTile(app)
                    1 -> unhideTiles()
                }
            }
            .show()
    }

    private fun hideTile(app: AppInfo) {
        tilePrefs.hide(app.id)
        Toast.makeText(this, R.string.toast_hidden, Toast.LENGTH_LONG).show()
        loadApps()
    }

    private fun unhideTiles() {
        tilePrefs.unhideAll()
        loadApps()
    }

    // endregion

    private fun colorFromSeed(seed: String): Int {
        val hue = (seed.hashCode() and 0xFF).toFloat() / 255f * 360f
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.65f))
    }

    companion object {
        private val STREAMING_PACKAGES = setOf(
            "com.disney.disneyplus", "com.hulu.plus", "com.wbd.stream", "com.hbo.hbonow",
            "com.netflix.ninja", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient",
            "com.amazon.amazonvideo.livingroom", "com.apple.atve.androidtv",
            "com.cbs.ott", "com.cbs.ott.firetv", "com.peacocktv.peacockandroid"
        )
        private const val TAG = "MainActivity"
        private const val DOCK_CORNER_DP = 30f
        private const val CLOCK_CORNER_DP = 30f
        private const val NAV_CORNER_DP = 22f

        /** Index of the centre card within the panel list handed to the renderer. */
        private const val CARD_PANEL = 1

        /** How fast the trailing rectangle chases the real one. Lower is more viscous. */
        private const val TRAIL_DAMPING = 0.22f

        /** Smoothing radius of the union between leading and trailing shapes, in px. */
        private const val FLOW_PX = 46f

        /** Below this the two have converged and the union is switched off. */
        private const val LAG_EPSILON = 1.5f
        private const val GL_SURFACE_WIDTH = 1920
        private const val GL_SURFACE_HEIGHT = 1080

        /**
         * How much of the blurred copy shows through the middle of each panel, and how
         * hard that copy is blurred. Mirrored into the GL renderer's uniforms.
         * High: these panels are a backdrop blur, so the middle is almost entirely the
         * defocused wallpaper. Costs nothing per frame -- it is baked into each panel's
         * cached composite when the wallpaper loads.
         */
        private const val GLASS_CENTRE_FROST = 0.9f

        /**
         * The card enlarges what is behind it, so wallpaper lines crossing its edge break
         * rather than running straight through. Deliberately only the card: the nav and
         * dock still register with the wallpaper.
         */
        /** Shared by every glass panel; mirrored into GlassRenderer. */
        private const val GLASS_ELEVATION_DP = 8f
        private const val GLASS_MAGNIFY = 1.12f
        private const val GLASS_BARREL = 0.42f
        private const val GLASS_DRIFT = 5f

        /** A thick, lit rim rather than a flat one. */
        private const val GLASS_RIM_REFRACTION = 0.8f
        private const val GLASS_EDGE_DARKEN = 0.2f

        /**
         * The centre card keeps a flat *face* -- even border, no bevel, a rim that barely
         * bends or darkens -- but sits higher than the other panels so it still reads as
         * lifted off the wallpaper. Edge treatment and drop shadow are separate things:
         * flattening the first does not require giving up the second.
         */
        private const val CARD_ELEVATION_DP = 16f
        private const val CARD_RIM_REFRACTION = 0.25f
        private const val CARD_EDGE_DARKEN = 0.03f

        /**
         * Only a touch. Draining the colour was what made the panels read as grey: a
         * backdrop blur is supposed to show the wallpaper's own colours, just defocused.
         * Enough here to stop the three drifting far apart in tint, no more.
         */
        private const val GLASS_NEUTRALISE = 0.15f
        private const val NAV_HOME = 0
        private const val NAV_INPUTS = 1
        private const val NAV_SETTINGS = 2
        /** Below this, "continue watching" is really "just started it by accident". */
        private const val MIN_PROGRESS_MS = 60_000L

        /** Past this fraction the episode counts as finished, not in progress. */
        private const val WATCHED_THRESHOLD = 0.95f

        private val SEASON_EPISODE =
            Regex("""S(\d{1,2})\s?E(\d{1,3})""", RegexOption.IGNORE_CASE)
    }
}
