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
        private const val CACHE_DIR = "navidrome_audio_cache"
        private const val DEFAULT_MAX_SIZE_BYTES = 500L * 1024 * 1024 // 500 MB
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Lazily initialized so max-cache-size pref is read before first use.
    private val simpleCache: SimpleCache by lazy {
        val maxSizeBytes = runCatching {
            kotlinx.coroutines.runBlocking {
                userPreferencesRepository.navidromeMaxCacheSizeMbFlow.first() * 1024L * 1024L
            }
        }.getOrDefault(DEFAULT_MAX_SIZE_BYTES)

        SimpleCache(
            File(context.filesDir, CACHE_DIR),
            LeastRecentlyUsedCacheEvictor(maxSizeBytes),
            StandaloneDatabaseProvider(context)
        )
    }

    // Cache key factory: navidrome:// URIs use their string as key; others bypass the cache.
    private val cacheKeyFactory = CacheKeyFactory { dataSpec ->
        val uri = dataSpec.uri
        when {
            uri.scheme == "navidrome" -> uri.toString()
            !dataSpec.key.isNullOrEmpty() -> dataSpec.key!!
            else -> dataSpec.uri.toString()
        }
    }

    /**
     * Wraps [upstreamFactory] with a [CacheDataSource.Factory] that reads/writes
     * audio for `navidrome://` URIs from/to [SimpleCache].
     *
     * Called by DualPlayerEngine.buildPlayer() so both ExoPlayer instances share the same cache.
     */
    fun buildCacheDataSourceFactory(upstreamFactory: DataSource.Factory): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheKeyFactory(cacheKeyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** True if the full audio for [navidromeId] is present in the local cache. */
    fun isCached(navidromeId: String): Boolean {
        val key = "navidrome://$navidromeId"
        val contentLength = ContentMetadata.getContentLength(simpleCache.getContentMetadata(key))
        return contentLength > 0
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
        val cacheDataSource = CacheDataSource(
            simpleCache,
            httpDataSource,
            FileDataSource(),
            CacheDataSink(simpleCache, CacheDataSink.DEFAULT_FRAGMENT_SIZE),
            CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR,
            null
        )

        try {
            Timber.tag(TAG).d("downloadSong: starting download for %s", navidromeId)
            val cacheWriter = CacheWriter(cacheDataSource, dataSpec, null, null)
            cacheWriter.cache()

            val sizeBytes = ContentMetadata.getContentLength(
                simpleCache.getContentMetadata(cacheKey)
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
            simpleCache.removeResource(cacheKey)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "removeSong: failed to remove from SimpleCache for %s", navidromeId)
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
        try {
            simpleCache.release()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "release: error releasing SimpleCache")
        }
    }
}
