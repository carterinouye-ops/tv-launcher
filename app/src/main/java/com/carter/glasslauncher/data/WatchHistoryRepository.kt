package com.carter.glasslauncher.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.carter.glasslauncher.data.db.AppDatabase
import com.carter.glasslauncher.data.db.WatchHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Bridges the Room-backed watch history table with artwork bitmaps saved to app-private
 * storage. De-dupes by package+title (the entity's primary key) and keeps only the latest 20.
 */
class WatchHistoryRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).watchHistoryDao()
    private val artworkDir = File(appContext.filesDir, "artwork").apply { mkdirs() }

    suspend fun recordPlayback(
        packageName: String,
        title: String,
        subtitle: String?,
        artwork: Bitmap?,
        positionMs: Long,
        durationMs: Long,
        deepLinkUri: String?
    ) = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext
        val id = idFor(packageName, title)
        val artworkPath = artwork?.let { saveArtwork(id, it) }
        val entity = WatchHistoryEntity(
            id = id,
            packageName = packageName,
            title = title,
            subtitle = subtitle,
            artworkPath = artworkPath ?: existingArtworkPath(id),
            positionMs = positionMs,
            durationMs = durationMs,
            lastUpdated = System.currentTimeMillis(),
            deepLinkUri = deepLinkUri
        )
        dao.upsert(entity)
        dao.trimToLatest(MAX_HISTORY)
        Log.i(
            TAG,
            "persisted id=$id pkg=$packageName title=\"$title\" artwork=${artworkPath != null} " +
                "(history now ${dao.getRecent(MAX_HISTORY).size} item(s))"
        )
    }

    /** Most recent thing played in one particular app, or null. */
    suspend fun latestFor(packageName: String): WatchHistoryEntity? =
        withContext(Dispatchers.IO) { dao.getLatestForPackage(packageName) }

    suspend fun getRecent(limit: Int = MAX_HISTORY): List<WatchHistoryEntity> =
        withContext(Dispatchers.IO) { dao.getRecent(limit) }

    private fun existingArtworkPath(id: String): String? {
        val f = File(artworkDir, "$id.png")
        return if (f.exists()) f.absolutePath else null
    }

    private fun saveArtwork(id: String, bitmap: Bitmap): String? {
        return try {
            val file = File(artworkDir, "$id.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save artwork for $id", e)
            null
        }
    }

    private fun idFor(packageName: String, title: String): String {
        val raw = "$packageName|$title"
        return try {
            val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            raw.hashCode().toString()
        }
    }

    companion object {
        private const val TAG = "WatchHistoryRepo"
        const val MAX_HISTORY = 20
    }
}
