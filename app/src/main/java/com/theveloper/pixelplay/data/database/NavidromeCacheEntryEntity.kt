package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks Navidrome songs for both play-count-based auto-download and manual cache entries.
 *
 * A row is created (or play_count is incremented) whenever a Navidrome song completes playback.
 * When play_count crosses the user-configured threshold, or the user taps the download button,
 * NavidromeCacheManager writes the audio bytes to SimpleCache and sets is_downloaded = true.
 */
@Entity(
    tableName = "navidrome_cache_entries",
    indices = [Index(value = ["is_downloaded"])]
)
data class NavidromeCacheEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "navidrome_id")
    val navidromeId: String,

    val title: String,
    val artist: String,
    val album: String,

    @ColumnInfo(name = "cover_art_id")
    val coverArtId: String?,

    val duration: Long,

    @ColumnInfo(name = "mime_type")
    val mimeType: String?,

    @ColumnInfo(name = "play_count")
    val playCount: Int = 0,

    @ColumnInfo(name = "is_downloaded")
    val isDownloaded: Boolean = false,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0L,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long = 0L
)
