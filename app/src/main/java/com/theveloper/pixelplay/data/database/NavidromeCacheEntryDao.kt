package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NavidromeCacheEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: NavidromeCacheEntryEntity)

    @Query("SELECT * FROM navidrome_cache_entries WHERE navidrome_id = :id")
    suspend fun getById(id: String): NavidromeCacheEntryEntity?

    @Query("SELECT * FROM navidrome_cache_entries WHERE is_downloaded = 1 ORDER BY cached_at DESC")
    fun getDownloadedFlow(): Flow<List<NavidromeCacheEntryEntity>>

    @Query("SELECT * FROM navidrome_cache_entries WHERE is_downloaded = 1")
    suspend fun getDownloadedList(): List<NavidromeCacheEntryEntity>

    @Query("SELECT play_count FROM navidrome_cache_entries WHERE navidrome_id = :id")
    suspend fun getPlayCount(id: String): Int?

    @Query("SELECT download_source FROM navidrome_cache_entries WHERE navidrome_id = :id")
    suspend fun getDownloadSource(id: String): Int?

    @Query("DELETE FROM navidrome_cache_entries WHERE navidrome_id = :id")
    suspend fun delete(id: String)

    /**
     * Upsert a play-count record. Inserts with play_count=1 on first play; increments on subsequent plays.
     * Metadata fields are only set on INSERT — existing rows keep their stored title/artist/album.
     * [playedAt] refreshes last_played_at on every play (the LRU key for auto-download eviction).
     */
    @Query("""
        INSERT INTO navidrome_cache_entries
            (navidrome_id, title, artist, album, cover_art_id, duration, mime_type, play_count, is_downloaded, size_bytes, cached_at, download_source, last_played_at)
        VALUES
            (:navidromeId, :title, :artist, :album, :coverArtId, :duration, :mimeType, 1, 0, 0, 0, 0, :playedAt)
        ON CONFLICT(navidrome_id) DO UPDATE SET
            play_count = play_count + 1,
            last_played_at = :playedAt
    """)
    suspend fun recordPlay(
        navidromeId: String,
        title: String,
        artist: String,
        album: String,
        coverArtId: String?,
        duration: Long,
        mimeType: String?,
        playedAt: Long
    )

    @Query("""
        UPDATE navidrome_cache_entries
        SET is_downloaded = 1, size_bytes = :sizeBytes, cached_at = :cachedAt, download_source = :source
        WHERE navidrome_id = :id
    """)
    suspend fun markAsDownloaded(id: String, sizeBytes: Long, cachedAt: Long, source: Int)

    @Query("""
        UPDATE navidrome_cache_entries
        SET is_downloaded = 0, size_bytes = 0, cached_at = 0, download_source = 0
        WHERE navidrome_id = :id
    """)
    suspend fun markAsNotDownloaded(id: String)

    /** Promotes an auto-download to manual so it is never LRU-evicted. */
    @Query("""
        UPDATE navidrome_cache_entries
        SET download_source = 2
        WHERE navidrome_id = :id AND is_downloaded = 1
    """)
    suspend fun promoteToManual(id: String)

    /** Total bytes held by auto-downloads (the pool the auto budget governs). */
    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM navidrome_cache_entries WHERE is_downloaded = 1 AND download_source = 1")
    suspend fun getAutoDownloadedBytes(): Long

    /** Total bytes held by manual downloads (uncapped; surfaced in the dashboard). */
    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM navidrome_cache_entries WHERE is_downloaded = 1 AND download_source = 2")
    suspend fun getManualDownloadedBytes(): Long

    /** Auto-downloads ordered least-recently-played first — the eviction order under the budget. */
    @Query("SELECT * FROM navidrome_cache_entries WHERE is_downloaded = 1 AND download_source = 1 ORDER BY last_played_at ASC")
    suspend fun getAutoDownloadEvictionCandidates(): List<NavidromeCacheEntryEntity>
}
