package com.carter.glasslauncher.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One recently-watched item, de-duped by package+title (the [id]).
 * Artwork is saved to app-private storage; [artworkPath] stores the absolute file path.
 */
@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val id: String, // "$packageName|$title"
    val packageName: String,
    val title: String,
    val subtitle: String?,
    val artworkPath: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastUpdated: Long,
    val deepLinkUri: String?
)
