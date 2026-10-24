package com.carter.glasslauncher.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WatchHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchHistoryEntity)

    @Query("SELECT * FROM watch_history ORDER BY lastUpdated DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<WatchHistoryEntity>

    @Query(
        "DELETE FROM watch_history WHERE id NOT IN " +
            "(SELECT id FROM watch_history ORDER BY lastUpdated DESC LIMIT :keep)"
    )
    suspend fun trimToLatest(keep: Int = 20)

    @Query("SELECT * FROM watch_history WHERE packageName = :packageName ORDER BY lastUpdated DESC LIMIT 1")
    suspend fun getLatestForPackage(packageName: String): WatchHistoryEntity?
}
