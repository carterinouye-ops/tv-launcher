package com.carter.glasslauncher.data

import android.content.Context
import android.util.Log
import com.carter.glasslauncher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One episode worth telling the user about. */
data class EpisodeNews(
    val show: String,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val airDate: LocalDate,
    /** True when it has not aired yet; false when it landed in the last few days. */
    val upcoming: Boolean,
    /** App the show was being watched in, so the card knows where "Watch now" goes. */
    val sourcePackage: String?
)

/**
 * Looks up whether anything the user has been watching has a new episode out, or one due
 * shortly, using TMDB's `last_episode_to_air` / `next_episode_to_air`.
 *
 * The shows come from [WatchHistoryRepository], which is fed by the media-session listener.
 * Until something has actually been played there is nothing to look up and this returns
 * null, which the clock card treats as "show no line at all".
 *
 * Results are cached on disk per show for [CACHE_TTL_MS]; air dates move rarely and the
 * launcher is relaunched constantly, so hitting the network each time would be pure waste.
 */
class EpisodeNewsRepository(context: Context) {

    private val cacheDir = File(context.filesDir, "episodes").apply { mkdirs() }

    /** The single most relevant piece of news, or null. */
    suspend fun latest(history: List<ShowCandidate>): EpisodeNews? = withContext(Dispatchers.IO) {
        if (BuildConfig.TMDB_API_KEY.isBlank()) return@withContext null
        val today = LocalDate.now()
        val found = mutableListOf<EpisodeNews>()

        for (candidate in history.take(MAX_SHOWS)) {
            for (name in candidate.names()) {
                val news = runCatching { newsForShow(name, today) }.getOrNull()
                if (news != null) {
                    found += news.copy(sourcePackage = candidate.packageName)
                    break // this history entry resolved; don't try its other spellings
                }
            }
        }

        // An episode that is already out beats one that is merely coming, and sooner beats
        // later within each group.
        found.sortedWith(
            compareBy<EpisodeNews> { it.upcoming }
                .thenBy { ChronoUnit.DAYS.between(it.airDate, today).let { d -> if (d < 0) -d else d } }
        ).firstOrNull()
    }

    private fun newsForShow(showName: String, today: LocalDate): EpisodeNews? {
        val payload = cached(showName) ?: fetch(showName)?.also { store(showName, it) } ?: return null
        if (payload.optBoolean("missing", false)) return null

        val name = payload.optString("name", showName)
        fun read(key: String, upcoming: Boolean): EpisodeNews? {
            val episode = payload.optJSONObject(key) ?: return null
            val date = runCatching { LocalDate.parse(episode.optString("air_date")) }.getOrNull()
                ?: return null
            val days = ChronoUnit.DAYS.between(today, date)
            val inWindow = if (upcoming) days in 0..WINDOW_DAYS else days in -WINDOW_DAYS..0
            if (!inWindow) return null
            return EpisodeNews(
                show = name,
                season = episode.optInt("season_number", 0),
                episode = episode.optInt("episode_number", 0),
                episodeTitle = episode.optString("name").takeIf { it.isNotBlank() },
                airDate = date,
                upcoming = upcoming,
                sourcePackage = null
            )
        }
        return read("last_episode_to_air", upcoming = false)
            ?: read("next_episode_to_air", upcoming = true)
    }

    /** Resolves the show then pulls its episode fields, collapsed into one cached object. */
    private fun fetch(showName: String): JSONObject? {
        val query = URLEncoder.encode(showName, "UTF-8")
        val search = getJson(
            "$API_BASE/search/tv?api_key=${BuildConfig.TMDB_API_KEY}&query=$query"
        ) ?: return null
        val first = search.optJSONArray("results")?.optJSONObject(0)
            ?: return JSONObject().put("missing", true)
        val id = first.optInt("id", -1)
        if (id < 0) return JSONObject().put("missing", true)

        val detail = getJson("$API_BASE/tv/$id?api_key=${BuildConfig.TMDB_API_KEY}") ?: return null
        return JSONObject().apply {
            put("name", detail.optString("name", showName))
            detail.optJSONObject("last_episode_to_air")?.let { put("last_episode_to_air", it) }
            detail.optJSONObject("next_episode_to_air")?.let { put("next_episode_to_air", it) }
        }
    }

    private fun getJson(url: String): JSONObject? {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "TMDB returned HTTP ${connection.responseCode}")
                    return null
                }
                JSONObject(connection.inputStream.bufferedReader().readText())
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            Log.w(TAG, "TMDB request failed", it)
            null
        }
    }

    private fun fileFor(showName: String) =
        File(cacheDir, showName.lowercase().hashCode().toString() + ".json")

    private fun cached(showName: String): JSONObject? {
        val file = fileFor(showName)
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun store(showName: String, payload: JSONObject) {
        runCatching { fileFor(showName).writeText(payload.toString()) }
    }

    companion object {
        private const val TAG = "EpisodeNews"
        private const val API_BASE = "https://api.themoviedb.org/3"
        private const val TIMEOUT_MS = 8_000
        private const val CACHE_TTL_MS = 12 * 60 * 60 * 1000L
        private const val WINDOW_DAYS = 7L
        private const val MAX_SHOWS = 6
    }
}

/**
 * A watch-history entry reduced to the names a show might go by.
 *
 * Which field holds the series name is not consistent between apps -- some put the series in
 * the media title and the episode in the subtitle, others the reverse -- so both are offered
 * and whichever TMDB recognises first wins.
 */
data class ShowCandidate(
    val title: String,
    val subtitle: String?,
    val packageName: String? = null
) {

    fun names(): List<String> =
        listOfNotNull(title, subtitle)
            .map { clean(it) }
            .filter { it.length >= MIN_NAME }
            .distinct()

    private fun clean(raw: String): String {
        var value = raw.trim()
        // Strip the usual episode decorations: "Show: Season 2: Episode 3", "Show - S2E3".
        value = value.substringBefore(" - ").substringBefore(": Season")
        value = value.replace(EPISODE_TAG, "").trim()
        value = value.trim(' ', '-', ':', '·')
        return value
    }

    companion object {
        private const val MIN_NAME = 2
        private val EPISODE_TAG = Regex("""\bS\d{1,2}\s?E\d{1,3}\b""", RegexOption.IGNORE_CASE)
    }
}
