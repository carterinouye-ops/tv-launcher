package com.carter.glasslauncher.ui.adapter

import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.carter.glasslauncher.R
import com.carter.glasslauncher.data.AppArt
import com.carter.glasslauncher.data.AppArtLoader
import com.carter.glasslauncher.data.AppInfo
import com.carter.glasslauncher.data.Brand
import com.carter.glasslauncher.data.BrandCatalog
import com.carter.glasslauncher.ui.FocusScaleHelper
import com.carter.glasslauncher.ui.RowMetrics
import com.carter.glasslauncher.util.PaletteUtils
import com.carter.glasslauncher.util.SoftGlowBitmap
import com.carter.glasslauncher.util.blendColor
import com.carter.glasslauncher.util.withAlpha
import com.carter.glasslauncher.util.dp
import com.carter.glasslauncher.util.toBitmapOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App banners share a consistent shape; focus enlarges and lifts the selected tile. */
class AppTileAdapter(
    private val scope: CoroutineScope,
    private val accentFor: (AppInfo) -> Int,
    private val cachedAccentFor: (String) -> Int?,
    private val onAccentExtracted: (String, Int) -> Unit,
    private val onClick: (AppInfo) -> Unit,
    private val onLongPress: (AppInfo, View) -> Unit,
    private val onMenu: (AppInfo) -> Unit,
    /** Fired with the app under the cursor, or null when the dock loses focus entirely. */
    private val onFocused: (AppInfo?) -> Unit
) : RecyclerView.Adapter<AppTileAdapter.ViewHolder>() {

    private val items = mutableListOf<AppInfo>()

    fun submit(newItems: List<AppInfo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun indexOf(id: String): Int = items.indexOfFirst { it.id == id }

    fun itemAt(position: Int): AppInfo? = items.getOrNull(position)

    /** Ids in their current on-screen order, for persisting the user's arrangement. */
    fun currentIds(): List<String> = items.map { it.id }

    /**
     * Swaps a tile with its neighbour while the user is rearranging. Uses notifyItemMoved so
     * the displaced tile slides across rather than the row blinking, and so the moved tile
     * keeps its ViewHolder -- and therefore its focus -- all the way through the move.
     */
    fun moveItem(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices) return false
        if (items[to].id == AppInfo.ID_FIRE_TV_HOME) return false
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_tile, parent, false)
        return ViewHolder(view, scope, accentFor, cachedAccentFor, onAccentExtracted)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onClick, onLongPress, onMenu, onFocused)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.cancelPendingArtLoad()
    }

    class ViewHolder(
        itemView: View,
        private val scope: CoroutineScope,
        private val accentFor: (AppInfo) -> Int,
        private val cachedAccentFor: (String) -> Int?,
        private val onAccentExtracted: (String, Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val glow: ImageView = itemView.findViewById(R.id.tile_glow)
        private val root: ViewGroup = itemView.findViewById(R.id.tile_root)
        private val banner: ImageView = itemView.findViewById(R.id.tile_banner)
        private val border: View = itemView.findViewById(R.id.tile_border)
        private val sheen: View = itemView.findViewById(R.id.tile_sheen)
        private val fallback: View = itemView.findViewById(R.id.tile_fallback)
        private val icon: ImageView = itemView.findViewById(R.id.tile_icon)
        private val label: TextView = itemView.findViewById(R.id.tile_label)
        private var focusAttached = false
        private var artJob: Job? = null
        private var boundId: String? = null

        /**
         * The app currently in this holder. The focus listener is attached once and then
         * reused across rebinds, so it has to read the live value rather than capture one.
         */
        private var boundApp: AppInfo? = null

        init {
            // The tile's rounded outline casts a quiet shadow underneath its lifted face.
            root.outlineAmbientShadowColor = 0x50000000
            root.outlineSpotShadowColor = 0x70000000
            glow.visibility = View.GONE
            border.visibility = View.GONE
        }

        fun cancelPendingArtLoad() {
            artJob?.cancel()
            artJob = null
        }

        fun bind(
            app: AppInfo,
            onClick: (AppInfo) -> Unit,
            onLongPress: (AppInfo, View) -> Unit,
            onMenu: (AppInfo) -> Unit,
            onFocused: (AppInfo?) -> Unit
        ) {
            cancelPendingArtLoad()
            boundId = app.id
            boundApp = app
            applyMetrics()
            // Holders get recycled mid-focus; without this the previous tile's glow, rim
            // and scale would carry over onto the app now bound here.
            FocusScaleHelper.resetVisuals(target = root, glow = glow, border = border)

            glow.setImageBitmap(SoftGlowBitmap.get())

            // The Xbox tile is synthetic: no package, so no banner to load. It gets Xbox
            // chrome and the drawn mark directly, skipping the art pipeline entirely.
            if (app.id == AppInfo.ID_XBOX_HDMI) {
                applyFlatPlate(XBOX_PLATE, XBOX_GREEN)
                applyCentredMark(R.drawable.xbox_mark, MARK_INSET_FRACTION)
                bindTileActions(app, onClick, onLongPress, onMenu, onFocused)
                return
            }

            val brand = BrandCatalog.forPackage(app.packageName)

            if (brand != null) applyBrandChrome(brand) else applyAccent(accentFor(app))

            val cachedArt = AppArtLoader.cached(app.id)
            if (cachedArt != null) {
                applyArt(app, cachedArt)
            } else {
                showPlaceholder(app)
                artJob = scope.launch {
                    val art = AppArtLoader.load(itemView.context, app)
                    if (boundId != app.id) return@launch
                    applyArt(app, art)
                    if (brand == null) maybeExtractAccent(app, art)
                }
            }

            bindTileActions(app, onClick, onLongPress, onMenu, onFocused)
        }

        private fun bindTileActions(
            app: AppInfo,
            onClick: (AppInfo) -> Unit,
            onLongPress: (AppInfo, View) -> Unit,
            onMenu: (AppInfo) -> Unit,
            onFocused: (AppInfo?) -> Unit
        ) {
            root.setOnClickListener { onClick(app) }
            root.setOnLongClickListener {
                onLongPress(app, root)
                true
            }
            root.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    onMenu(app)
                    true
                } else {
                    false
                }
            }

            if (!focusAttached) {
                FocusScaleHelper.attach(
                    target = root,
                    onFocusChanged = { hasFocus ->
                        if (hasFocus) {
                            onFocused(boundApp)
                        } else {
                            // A tile loses focus before its neighbour receives it.
                            // Clear the card only if focus actually left the dock.
                            val dock = root.rootView.findViewById<View>(R.id.dock_row)
                            root.post { if (dock?.hasFocus() != true) onFocused(null) }
                        }
                    }
                )
                focusAttached = true
            }
        }

        /**
         * The "lifted" look while a tile is being rearranged: bigger than the focus scale
         * and slightly translucent, so it reads as picked up off the row. Applied directly
         * to the view rather than through a rebind, which would risk dropping focus.
         */
        fun setPickedUp(pickedUp: Boolean) {
            val scale = if (pickedUp) PICKED_UP_SCALE else FocusScaleHelper.FOCUS_SCALE
            root.animate().cancel()
            root.animate()
                .scaleX(scale).scaleY(scale)
                .alpha(if (pickedUp) PICKED_UP_ALPHA else 1f)
                .setDuration(FocusScaleHelper.DURATION_MS)
                .start()
        }

        /** A bundled mark (rather than an app banner) centred on the tile's own plate. */
        private fun applyCentredMark(drawableRes: Int, insetFraction: Float) {
            banner.visibility = View.VISIBLE
            fallback.visibility = View.GONE
            banner.scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = (RowMetrics.tileHeight(itemView.context) * insetFraction).toInt()
            banner.setPadding(inset, inset, inset, inset)
            banner.setImageResource(drawableRes)
        }

        /**
         * Flat fill matching a bundled mark's own backing colour, so the square artwork
         * blends into the tile with no visible seam. Deliberately skips the sheen that
         * [applyBrandChrome] adds, which would lighten the tile's top half and give the
         * mark's plate a hard edge to show against.
         */
        private fun applyFlatPlate(plate: Int, accent: Int) {
            val radius = root.dp(TILE_CORNER_DP)
            root.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(plate)
            }
            root.outlineProvider = ViewOutlineProvider.BACKGROUND
            root.clipToOutline = true

            border.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(root.dp(FOCUS_STROKE_DP).toInt(), accent)
            }
            glow.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
            sheen.visibility = View.GONE
        }

        /**
         * Branded chrome: glossy vertical gradient in the brand's colours, a white sheen
         * over the top 40%, a lit rim and a strong outer glow. The tile itself is filled
         * with the app's own TV banner, which for these apps already is the brand mark on
         * brand colour.
         */
        private fun applyBrandChrome(brand: Brand) {
            val radius = root.dp(TILE_CORNER_DP)

            root.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(brand.gradientTop, brand.gradientBottom)
            ).apply { cornerRadius = radius }
            root.outlineProvider = ViewOutlineProvider.BACKGROUND
            root.clipToOutline = true

            sheen.visibility = View.VISIBLE
            sheen.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(SHEEN_TOP, SHEEN_BOTTOM)
            ).apply {
                // Only the top corners follow the tile; the sheen's lower edge is a
                // straight fade into the artwork underneath.
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }

            border.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(root.dp(FOCUS_STROKE_DP).toInt(), brand.borderTint)
            }

            glow.setColorFilter(brand.glow, PorterDuff.Mode.SRC_IN)
        }

        /**
         * Width comes from RowMetrics so four tiles exactly span the content width; the
         * glow and rim track the tile so the focus treatment stays aligned with it.
         */
        private fun applyMetrics() {
            val ctx = itemView.context
            val w = RowMetrics.itemWidth(ctx)
            val h = RowMetrics.tileHeight(ctx)
            listOf<View>(root, glow, border).forEach { v ->
                val lp = v.layoutParams
                if (lp.width != w || lp.height != h) {
                    lp.width = w
                    lp.height = h
                    v.layoutParams = lp
                }
            }
        }

        private fun applyAccent(accent: Int) {
            val (top, bottom) = PaletteUtils.tileGradientColors(accent)
            root.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(top, bottom)
            ).apply { cornerRadius = root.dp(TILE_CORNER_DP) }

            // Lit brand-coloured rim, drawn above the artwork. The reference reads as a
            // backlit panel, and that comes from this bright edge more than from the fill.
            border.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = root.dp(TILE_CORNER_DP)
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(root.dp(FOCUS_STROKE_DP).toInt(), blendColor(accent, 0.55f, towardWhite = true))
            }
            root.outlineProvider = ViewOutlineProvider.BACKGROUND
            root.clipToOutline = true
            glow.setColorFilter(accent, PorterDuff.Mode.SRC_IN)
            sheen.visibility = View.GONE
        }

        /** Until artwork arrives, show the app's name on its colored gradient. */
        private fun showPlaceholder(app: AppInfo) {
            banner.visibility = View.GONE
            fallback.visibility = View.VISIBLE
            icon.setImageDrawable(null)
            label.text = app.label
        }

        private suspend fun maybeExtractAccent(app: AppInfo, art: AppArt) {
            if (cachedAccentFor(app.id) != null) return
            val source = art.banner ?: art.icon ?: return
            val accent = withContext(Dispatchers.Default) {
                source.toBitmapOrNull()?.let { PaletteUtils.extractAccentColor(it) }
            } ?: return
            if (boundId != app.id) return
            applyAccent(accent)
            onAccentExtracted(app.id, accent)
        }

        private fun applyArt(app: AppInfo, art: AppArt) {
            val wide = art.banner
            when {
                wide != null && isFullBleedBanner(wide) -> {
                    banner.visibility = View.VISIBLE
                    fallback.visibility = View.GONE
                    banner.scaleType = ImageView.ScaleType.CENTER_CROP
                    banner.setPadding(0, 0, 0, 0)
                    banner.setImageDrawable(wide)
                }
                wide != null -> {
                    // Narrow/square artwork: inset it so it occupies ~80% of the tile width
                    // and sits centered on the gradient rather than being stretched.
                    banner.visibility = View.VISIBLE
                    fallback.visibility = View.GONE
                    banner.scaleType = ImageView.ScaleType.FIT_CENTER
                    val hInset = (RowMetrics.itemWidth(itemView.context) * (1f - BANNER_WIDTH_FRACTION) / 2f).toInt()
                    val vInset = (RowMetrics.tileHeight(itemView.context) * 0.12f).toInt()
                    banner.setPadding(hInset, vInset, hInset, vInset)
                    banner.setImageDrawable(wide)
                }
                else -> {
                    banner.visibility = View.GONE
                    fallback.visibility = View.VISIBLE
                    icon.setImageDrawable(art.icon)
                    label.text = app.label
                }
            }
        }

        /**
         * A real TV banner is a wide 16:9-ish image meant to fill the tile. Icons and other
         * stand-ins are squarer, and some apps ship a banner only a few dozen pixels across,
         * which would look soft blown up to tile size.
         */
        private fun isFullBleedBanner(drawable: Drawable): Boolean {
            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight
            if (w <= 0 || h <= 0) return false
            if (w < MIN_BANNER_WIDTH_PX) return false
            val aspect = w.toFloat() / h
            return aspect in MIN_BANNER_ASPECT..MAX_BANNER_ASPECT
        }
    }

    companion object {
        private const val TILE_CORNER_DP = 28f
        private const val SHEEN_TOP = 0x2EFFFFFF
        private const val SHEEN_BOTTOM = 0x00FFFFFF
        private const val FOCUS_STROKE_DP = 2f
        private const val BANNER_WIDTH_FRACTION = 0.80f

        private const val PICKED_UP_SCALE = 1.17f
        private const val PICKED_UP_ALPHA = 0.85f

        /** Padding around a bundled mark, as a fraction of tile height. */
        private const val MARK_INSET_FRACTION = 0.06f

        /** Backing colour of xbox_mark.png, so the tile and the artwork are seamless. */
        private const val XBOX_PLATE = 0xFF191C1F.toInt()
        private const val XBOX_GREEN = 0xFF7CC520.toInt()
        private const val MIN_BANNER_WIDTH_PX = 160
        private const val MIN_BANNER_ASPECT = 1.5f
        private const val MAX_BANNER_ASPECT = 2.4f
    }
}
