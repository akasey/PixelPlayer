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

    @Query("DELETE FROM navidrome_cache_entries WHERE navidrome_id = :id")
    suspend fun delete(id: String)

    /**
     * Upsert a play-count record. Inserts with play_count=1 on first play; increments on subsequent plays.
     * Metadata fields are only set on INSERT — existing rows keep their stored title/artist/album.
     */
    @Query("""
        INSERT INTO navidrome_cache_entries
            (navidrome_id, title, artist, album, cover_art_id, duration, mime_type, play_count, is_downloaded, size_bytes, cached_at)
        VALUES
            (:navidromeId, :title, :artist, :album, :coverArtId, :duration, :mimeType, 1, 0, 0, 0)
        ON CONFLICT(navidrome_id) DO UPDATE SET
            play_count = play_count + 1
    """)
    suspend fun recordPlay(
        navidromeId: String,
        title: String,
        artist: String,
        album: String,
        coverArtId: String?,
        duration: Long,
        mimeType: String?
    )

    @Query("""
        UPDATE navidrome_cache_entries
        SET is_downloaded = 1, size_bytes = :sizeBytes, cached_at = :cachedAt
        WHERE navidrome_id = :id
    """)
    suspend fun markAsDownloaded(id: String, sizeBytes: Long, cachedAt: Long)

    @Query("""
        UPDATE navidrome_cache_entries
        SET is_downloaded = 0, size_bytes = 0, cached_at = 0
        WHERE navidrome_id = :id
    """)
    suspend fun markAsNotDownloaded(id: String)
}
