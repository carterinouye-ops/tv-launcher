package com.carter.glasslauncher.data

import android.content.Context
import android.util.Log
import com.carter.glasslauncher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Looks up poster artwork for a watch-history title on TMDB and caches it on disk.
 *
 * The key is supplied through local.properties (`tmdb.api.key`) and surfaced via
 * BuildConfig, so it never lands in source control. With no key configured every call
 * short-circuits to null and the caller falls back to whatever artwork the media session
 * or notification provided -- the launcher works fine offline and keyless, it just shows
 * less pretty thumbnails.
 *
 * Results are cached twice over: the downloaded image by title hash, and negative lookups
 * in memory, so a title that TMDB doesn't know is not re-queried on every row bind.
 */
/** A title TMDB recognised, with its own spelling of the name and its artwork. */
data class ResolvedTitle(val name: String, val artwork: File?)

private data class TmdbMatch(val name: String, val isTv: Boolean, val imagePath: String?)

class TmdbArtworkRepository(context: Context) {

    private val cacheDir = File(context.applicationContext.filesDir, "tmdb").apply { mkdirs() }
    private val missing = mutableSetOf<String>()
    private val lock = Mutex()

    val isConfigured: Boolean get() = BuildConfig.TMDB_API_KEY.isNotBlank()

    /** Cached file for this title if one has already been fetched, else null. */
    fun cached(title: String): File? {
        val f = fileFor(title)
        return if (f.exists() && f.length() > 0) f else null
    }

    /**
     * Returns a local file holding the poster for [title], fetching it if needed.
     * Null when unconfigured, not found, or the network call fails.
     */
    suspend fun posterFor(title: String): File? = imageFor(title, wide = false)

    /**
     * Landscape still, for anything shown in a 16:9 frame. A poster centre-cropped into a
     * wide box loses most of the picture, so the backdrop is preferred here and the poster
     * is only the fallback -- the reverse of [posterFor].
     */
    suspend fun backdropFor(title: String): File? = imageFor(title, wide = true)

    private suspend fun imageFor(title: String, wide: Boolean): File? {
        if (!isConfigured || title.isBlank()) return null
        val key = if (wide) "$title$WIDE_SUFFIX" else title
        cached(key)?.let { return it }

        return lock.withLock {
            cached(key)?.let { return@withLock it }
            if (missing.contains(key)) return@withLock null

            val path = withContext(Dispatchers.IO) {
                runCatching { lookupImagePath(title, wide) }.getOrNull()
            }
            if (path == null) {
                missing.add(key)
                return@withLock null
            }
            val base = if (wide) BACKDROP_BASE else IMAGE_BASE
            val file = withContext(Dispatchers.IO) {
                runCatching { download("$base$path", fileFor(key)) }.getOrNull()
            }
            if (file == null) missing.add(key)
            file
        }
    }

    /**
     * Works out which of several candidate strings actually names a title, and returns
     * TMDB's own name for it along with its artwork.
     *
     * Media sessions disagree about which field holds the series: HBO Max puts the episode
     * in METADATA_KEY_TITLE ("Timber") and the series in the subtitle ("President Curtis"),
     * and other apps do the reverse. Rather than assume, both are looked up and the better
     * match wins -- a series beats a one-off, and failing that the subtitle is trusted,
     * since the episode-in-title convention is the more common of the two.
     */
    suspend fun resolve(candidates: List<String>, wide: Boolean = true): ResolvedTitle? {
        if (!isConfigured) return null
        val matches = candidates.filter { it.isNotBlank() }.mapNotNull { candidate ->
            withContext(Dispatchers.IO) {
                runCatching { lookupMatch(candidate, wide) }.getOrNull()
            }
        }
        if (matches.isEmpty()) return null
        // Index 0 is the title field, 1 the subtitle; prefer a TV hit, then the later one.
        val best = matches.filter { it.isTv }.lastOrNull() ?: matches.last()
        val file = best.imagePath?.let { path ->
            val key = "${best.name}${if (wide) WIDE_SUFFIX else ""}"
            cached(key) ?: withContext(Dispatchers.IO) {
                runCatching {
                    download("${if (wide) BACKDROP_BASE else IMAGE_BASE}$path", fileFor(key))
                }.getOrNull()
            }
        }
        return ResolvedTitle(best.name, file)
    }

    /** @return TMDB's relative image path, preferring whichever shape the caller needs. */
    private fun lookupImagePath(title: String, wide: Boolean): String? =
        lookupMatch(title, wide)?.imagePath

    /** First usable movie/tv hit for [title], with the name TMDB knows it by. */
    private fun lookupMatch(title: String, wide: Boolean): TmdbMatch? {
        val url = URL(
            "$API_BASE/search/multi?api_key=${BuildConfig.TMDB_API_KEY}" +
                "&query=${URLEncoder.encode(title, "UTF-8")}&include_adult=false"
        )
        val body = url.openHttpConnection().use { conn ->
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "TMDB search for \"$title\" returned HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.bufferedReader().readText()
        }

        val results = JSONObject(body).optJSONArray("results") ?: return null
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val mediaType = item.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") continue
            val poster = item.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
            val backdrop = item.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
            val name = (item.optString("name").takeIf { it.isNotBlank() && it != "null" }
                ?: item.optString("title").takeIf { it.isNotBlank() && it != "null" }
                ?: title)
            return TmdbMatch(
                name = name,
                isTv = mediaType == "tv",
                imagePath = if (wide) backdrop ?: poster else poster ?: backdrop
            )
        }
        return null
    }

    private fun download(url: String, target: File): File? {
        URL(url).openHttpConnection().use { conn ->
            if (conn.responseCode !in 200..299) return null
            val tmp = File(target.absolutePath + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
        }
        return target.takeIf { it.exists() && it.length() > 0 }
    }

    private fun URL.openHttpConnection(): HttpURLConnection =
        (openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
        }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    private fun fileFor(title: String): File = File(cacheDir, "${hash(title)}.img")

    private fun hash(value: String): String = try {
        MessageDigest.getInstance("MD5").digest(value.lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        value.hashCode().toString()
    }

    companion object {
        private const val TAG = "TmdbArtwork"
        private const val API_BASE = "https://api.themoviedb.org/3"
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
        private const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"
        private const val WIDE_SUFFIX = "::wide"
        private const val TIMEOUT_MS = 8_000
    }
}
