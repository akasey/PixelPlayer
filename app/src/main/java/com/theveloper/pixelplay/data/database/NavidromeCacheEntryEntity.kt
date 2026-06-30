package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Origin of a pinned download. Stored as the [NavidromeCacheEntryEntity.downloadSource] int code. */
object DownloadSource {
    /** Not downloaded — the row only tracks play counts. */
    const val NONE = 0
    /** Pinned automatically once play_count crossed the threshold. Evictable under the auto budget. */
    const val AUTO = 1
    /** Pinned explicitly by the user. Never evicted, never demoted to AUTO. */
    const val MANUAL = 2
}

/**
 * Tracks Navidrome songs for both play-count-based auto-download and manual cache entries.
 *
 * A row is created (or play_count is incremented) whenever a Navidrome song completes playback.
 * When play_count crosses the user-configured threshold, or the user taps the download button,
 * NavidromeCacheManager writes the audio bytes to SimpleCache and sets is_downloaded = true.
 *
 * [downloadSource] distinguishes auto vs manual pins: auto downloads are LRU-evicted (oldest
 * [lastPlayedAt] first) under the user's auto-download budget, while manual downloads are
 * permanent. [lastPlayedAt] is refreshed on every completed play and is the eviction key.
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
    val cachedAt: Long = 0L,

    /** One of [DownloadSource]. NONE while not downloaded; AUTO/MANUAL once pinned. */
    @ColumnInfo(name = "download_source")
    val downloadSource: Int = DownloadSource.NONE,

    /** Epoch millis of the last completed play. LRU key for auto-download eviction. */
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long = 0L
)
