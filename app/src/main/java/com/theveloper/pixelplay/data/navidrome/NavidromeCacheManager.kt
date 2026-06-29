package com.theveloper.pixelplay.data.navidrome

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import com.theveloper.pixelplay.data.database.NavidromeCacheEntryDao
import com.theveloper.pixelplay.data.database.NavidromeCacheEntryEntity
import com.theveloper.pixelplay.data.database.NavidromeDao
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.presentation.viewmodel.ConnectivityStateHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class NavidromeCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cacheEntryDao: NavidromeCacheEntryDao,
    private val navidromeDao: NavidromeDao,
    private val navidromeRepository: NavidromeRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val connectivityStateHolder: ConnectivityStateHolder,
    private val tunnelManager: com.theveloper.pixelplay.data.navidrome.tunnel.WireGuardTunnelManager,
    @com.theveloper.pixelplay.di.NavidromeOkHttpClient
    private val navidromeOkHttpClient: okhttp3.OkHttpClient
) {
    private companion object {
        private const val TAG = "NavidromeCacheManager"
        // Transient as-you-play streaming cache (LRU-evicted, governed by the size pref).
        private const val STREAMING_CACHE_DIR = "navidrome_audio_cache"
        // Pinned offline downloads (never evicted — explicit + auto downloads land here).
        private const val DOWNLOAD_CACHE_DIR = "navidrome_downloads"
        private const val DEFAULT_MAX_SIZE_BYTES = 500L * 1024 * 1024 // 500 MB
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Correct any stale "downloaded" rows off the main thread at startup.
        scope.launch { reconcileDownloads() }
    }

    // Both caches share one database provider; SimpleCache requires distinct directories.
    private val databaseProvider by lazy { StandaloneDatabaseProvider(context) }

    /**
     * Pinned download cache. Uses [NoOpCacheEvictor] so explicitly (or auto-) downloaded songs
     * are never evicted — they remain available offline regardless of the streaming cache budget.
     */
    private val downloadCache: SimpleCache by lazy {
        SimpleCache(
            File(context.filesDir, DOWNLOAD_CACHE_DIR),
            NoOpCacheEvictor(),
            databaseProvider
        )
    }

    /**
     * Transient streaming cache populated as songs play. LRU-evicted under the user-configured
     * size budget. Read on the size pref at first use; a size change takes effect on next launch.
     */
    private val streamingCache: SimpleCache by lazy {
        val maxSizeBytes = runCatching {
            kotlinx.coroutines.runBlocking {
                userPreferencesRepository.navidromeMaxCacheSizeMbFlow.first() * 1024L * 1024L
            }
        }.getOrDefault(DEFAULT_MAX_SIZE_BYTES)

        SimpleCache(
            File(context.filesDir, STREAMING_CACHE_DIR),
            LeastRecentlyUsedCacheEvictor(maxSizeBytes),
            databaseProvider
        )
    }

    // Cache key factory. An explicit DataSpec.key (set as MediaItem.customCacheKey by
    // MediaItemBuilder, e.g. "navidrome://songId") is authoritative: it is the only key that
    // stays stable across every transport form the same song can take — navidrome:// scheme,
    // the local proxy URL (dynamic port), the real endpoint (rotating auth token / WireGuard),
    // or nothing at all when offline. Preferring it guarantees a pinned download is found
    // regardless of how the URI was resolved. Falls back to the navidrome:// URI, then the raw
    // URI string, so non-cloud (content://, file://) playback is unaffected.
    private val cacheKeyFactory = CacheKeyFactory { dataSpec ->
        val uri = dataSpec.uri
        when {
            !dataSpec.key.isNullOrEmpty() -> dataSpec.key!!
            uri.scheme == "navidrome" -> uri.toString()
            else -> uri.toString()
        }
    }

    /**
     * Wraps [upstreamFactory] with a two-tier cache for `navidrome://` URIs:
     *   pinned downloads (read-only) → streaming LRU cache (read/write) → [upstreamFactory] (network).
     *
     * Playback reads a pinned download if present, otherwise reads/writes the streaming cache, then
     * falls back to the network. Playback never writes into the pinned store — only explicit
     * downloads do (via [downloadSong]) — so the streaming budget can never evict a download.
     *
     * Called by DualPlayerEngine.buildPlayer() so both ExoPlayer instances share the same caches.
     */
    fun buildCacheDataSourceFactory(upstreamFactory: DataSource.Factory): CacheDataSource.Factory {
        // Tier 2: streaming LRU cache (read + write), upstream = network.
        val streamingFactory = CacheDataSource.Factory()
            .setCache(streamingCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheKeyFactory(cacheKeyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Tier 1: pinned downloads (read-only), upstream = streaming tier.
        return CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(streamingFactory)
            .setCacheKeyFactory(cacheKeyFactory)
            .setCacheWriteDataSinkFactory(null) // read-only: never write pinned bytes during playback
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** True if the full audio for [navidromeId] is present as a pinned download. */
    fun isCached(navidromeId: String): Boolean {
        val key = "navidrome://$navidromeId"
        val contentLength = ContentMetadata.getContentLength(downloadCache.getContentMetadata(key))
        return contentLength > 0
    }

    /** Bytes currently held by pinned downloads and the transient streaming cache. */
    fun cacheUsageBytes(): CacheUsage =
        CacheUsage(downloadBytes = downloadCache.cacheSpace, streamingBytes = streamingCache.cacheSpace)

    /** Evicts the entire transient streaming cache (does not touch pinned downloads). */
    suspend fun clearStreamingCache() = withContext(Dispatchers.IO) {
        runCatching {
            streamingCache.keys.toList().forEach { streamingCache.removeResource(it) }
        }.onFailure { Timber.tag(TAG).e(it, "clearStreamingCache: failed") }
    }

    /**
     * Reconciles Room with the pinned cache: any row flagged downloaded whose audio is missing
     * from the pinned store is corrected to not-downloaded, so the Downloads list never lies
     * (e.g. after a manual file deletion or cache corruption).
     */
    suspend fun reconcileDownloads() = withContext(Dispatchers.IO) {
        runCatching {
            cacheEntryDao.getDownloadedList().forEach { entry ->
                if (!isCached(entry.navidromeId)) {
                    Timber.tag(TAG).w("reconcileDownloads: %s flagged downloaded but missing — correcting", entry.navidromeId)
                    cacheEntryDao.markAsNotDownloaded(entry.navidromeId)
                }
            }
        }.onFailure { Timber.tag(TAG).e(it, "reconcileDownloads: failed") }
    }

    /** Emits the list of songs the user has explicitly (or auto-) downloaded. */
    val downloadedSongsFlow: Flow<List<NavidromeCacheEntryEntity>> =
        cacheEntryDao.getDownloadedFlow()

    /**
     * Downloads the audio for [navidromeId] to [SimpleCache] directly from the Navidrome
     * stream URL (bypassing the Ktor proxy). The cache key is `navidrome://songId` so ExoPlayer
     * hits the cache on subsequent playback without any network calls.
     */
    suspend fun downloadSong(navidromeId: String) = withContext(Dispatchers.IO) {
        val songEntity = navidromeDao.getSongByNavidromeId(navidromeId)
        if (songEntity == null) {
            Timber.tag(TAG).w("downloadSong: no song entity found for id=%s", navidromeId)
            return@withContext
        }

        // Ensure the WireGuard tunnel is up (when enabled) before downloading from the server.
        tunnelManager.ensureReady()

        val streamUrl = runCatching { navidromeRepository.getStreamUrl(navidromeId) }
            .getOrElse { e ->
                Timber.tag(TAG).e(e, "downloadSong: failed to get stream URL for %s", navidromeId)
                return@withContext
            }

        val cacheKey = "navidrome://$navidromeId"
        val dataSpec = DataSpec.Builder()
            .setUri(streamUrl.toUri())
            .setKey(cacheKey)
            .build()

        // Use the Navidrome OkHttp client so downloads honor the WireGuard tunnel when enabled.
        val httpDataSource = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(navidromeOkHttpClient)
            .createDataSource()
        // Write into the pinned download cache so the song is never LRU-evicted.
        val cacheDataSource = CacheDataSource(
            downloadCache,
            httpDataSource,
            FileDataSource(),
            CacheDataSink(downloadCache, CacheDataSink.DEFAULT_FRAGMENT_SIZE),
            CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
            null
        )

        try {
            Timber.tag(TAG).d("downloadSong: starting download for %s", navidromeId)
            val cacheWriter = CacheWriter(cacheDataSource, dataSpec, null, null)
            cacheWriter.cache()

            val sizeBytes = ContentMetadata.getContentLength(
                downloadCache.getContentMetadata(cacheKey)
            ).coerceAtLeast(0)

            // Ensure entry exists in Room (may already be there if song was played before)
            val existing = cacheEntryDao.getById(navidromeId)
            if (existing == null) {
                cacheEntryDao.upsert(
                    NavidromeCacheEntryEntity(
                        navidromeId = navidromeId,
                        title = songEntity.title,
                        artist = songEntity.artist,
                        album = songEntity.album,
                        coverArtId = songEntity.coverArtId,
                        duration = songEntity.duration,
                        mimeType = songEntity.mimeType,
                        isDownloaded = true,
                        sizeBytes = sizeBytes,
                        cachedAt = System.currentTimeMillis()
                    )
                )
            } else {
                cacheEntryDao.markAsDownloaded(navidromeId, sizeBytes, System.currentTimeMillis())
            }
            Timber.tag(TAG).d("downloadSong: completed for %s, %d bytes", navidromeId, sizeBytes)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "downloadSong: failed for %s", navidromeId)
        }
    }

    /**
     * Removes [navidromeId] from [SimpleCache] and marks its Room entry as not downloaded.
     * The Row is retained so play-count tracking is preserved.
     */
    suspend fun removeSong(navidromeId: String) = withContext(Dispatchers.IO) {
        val cacheKey = "navidrome://$navidromeId"
        try {
            downloadCache.removeResource(cacheKey)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "removeSong: failed to remove from download cache for %s", navidromeId)
        }
        cacheEntryDao.markAsNotDownloaded(navidromeId)
        Timber.tag(TAG).d("removeSong: removed %s", navidromeId)
    }

    /**
     * Called by MusicService when a Navidrome song completes playback (scrobble submission).
     * Increments the play count and triggers an auto-download if the threshold is reached.
     */
    fun onNavidromeSongCompleted(
        navidromeId: String,
        title: String,
        artist: String,
        album: String,
        coverArtId: String?,
        duration: Long,
        mimeType: String?
    ) {
        scope.launch {
            cacheEntryDao.recordPlay(navidromeId, title, artist, album, coverArtId, duration, mimeType)

            val threshold = userPreferencesRepository.navidromeAutoDownloadThresholdFlow.first()
            if (threshold <= 0) return@launch // auto-download disabled

            val playCount = cacheEntryDao.getPlayCount(navidromeId) ?: 0
            if (playCount < threshold) return@launch
            if (isCached(navidromeId)) return@launch // already cached

            val wifiOnly = userPreferencesRepository.navidromeAutoDownloadWifiOnlyFlow.first()
            if (wifiOnly && !connectivityStateHolder.isWifiEnabled.value) {
                Timber.tag(TAG).d("onNavidromeSongCompleted: deferring auto-download (not on WiFi) for %s", navidromeId)
                return@launch
            }

            Timber.tag(TAG).d("onNavidromeSongCompleted: auto-downloading %s (plays=%d)", navidromeId, playCount)
            downloadSong(navidromeId)
        }
    }

    fun release() {
        runCatching { streamingCache.release() }
            .onFailure { Timber.tag(TAG).e(it, "release: error releasing streaming cache") }
        runCatching { downloadCache.release() }
            .onFailure { Timber.tag(TAG).e(it, "release: error releasing download cache") }
    }
}

/** Bytes held by each Navidrome cache tier. */
data class CacheUsage(
    val downloadBytes: Long,
    val streamingBytes: Long,
)
