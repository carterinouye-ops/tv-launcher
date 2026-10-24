package com.carter.glasslauncher.util

object TimeUtils {

    fun relativeTimeAgo(epochMs: Long): String {
        val diff = (System.currentTimeMillis() - epochMs).coerceAtLeast(0)
        val minutes = diff / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            else -> "${days}d ago"
        }
    }

    fun minutesLeft(positionMs: Long, durationMs: Long): Long {
        if (durationMs <= 0) return 0
        return ((durationMs - positionMs).coerceAtLeast(0)) / 60_000
    }
}
