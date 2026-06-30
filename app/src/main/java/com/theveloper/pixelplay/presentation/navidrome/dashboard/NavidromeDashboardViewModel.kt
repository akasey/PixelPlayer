package com.theveloper.pixelplay.presentation.navidrome.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.database.NavidromeCacheEntryEntity
import com.theveloper.pixelplay.data.database.NavidromePlaylistEntity
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.navidrome.CacheUsage
import com.theveloper.pixelplay.data.navidrome.NavidromeCacheManager
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.worker.NavidromeSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class NavidromeDashboardViewModel @Inject constructor(
    private val repository: NavidromeRepository,
    private val navCacheManager: NavidromeCacheManager,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val workManager: WorkManager
) : ViewModel() {

    /** Configured max streaming-cache size in MB (applies on next app start). */
    val maxCacheSizeMb: StateFlow<Int> = userPreferencesRepository.navidromeMaxCacheSizeMbFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, 500)

    /** Budget for evictable auto-downloads, in MB (0 = unlimited). */
    val autoDownloadMaxSizeMb: StateFlow<Int> = userPreferencesRepository.navidromeAutoDownloadMaxSizeMbFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, 1024)

    /** Completed-play count that triggers an auto-download (0 = disabled). */
    val autoDownloadThreshold: StateFlow<Int> = userPreferencesRepository.navidromeAutoDownloadThresholdFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, 3)

    /** Whether auto-downloads are restricted to un-metered (Wi‑Fi) connections. */
    val autoDownloadWifiOnly: StateFlow<Boolean> = userPreferencesRepository.navidromeAutoDownloadWifiOnlyFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    private val _cacheUsage = MutableStateFlow(CacheUsage(0L, 0L))
    val cacheUsage: StateFlow<CacheUsage> = _cacheUsage.asStateFlow()

    init {
        refreshCacheUsage()
    }

    fun setMaxCacheSizeMb(sizeMb: Int) {
        viewModelScope.launch { userPreferencesRepository.setNavidromeMaxCacheSizeMb(sizeMb) }
    }

    fun setAutoDownloadMaxSizeMb(sizeMb: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setNavidromeAutoDownloadMaxSizeMb(sizeMb)
            // The cache manager re-enforces the budget reactively; refresh usage once it settles.
            refreshCacheUsage()
        }
    }

    fun setAutoDownloadThreshold(threshold: Int) {
        viewModelScope.launch { userPreferencesRepository.setNavidromeAutoDownloadThreshold(threshold) }
    }

    fun setAutoDownloadWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setNavidromeAutoDownloadWifiOnly(wifiOnly) }
    }

    fun refreshCacheUsage() {
        viewModelScope.launch {
            _cacheUsage.value = withContext(Dispatchers.IO) { navCacheManager.cacheUsageBytes() }
        }
    }

    fun clearStreamingCache() {
        viewModelScope.launch {
            navCacheManager.clearStreamingCache()
            refreshCacheUsage()
        }
    }

    val playlists: StateFlow<List<NavidromePlaylistEntity>> = repository.getPlaylists()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncProgress = MutableStateFlow<Float?>(null)
    val syncProgress: StateFlow<Float?> = _syncProgress.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _selectedPlaylistSongs = MutableStateFlow<List<Song>>(emptyList())
    val selectedPlaylistSongs: StateFlow<List<Song>> = _selectedPlaylistSongs.asStateFlow()

    /** Downloaded songs for the offline cache tab. */
    val downloadedSongs: StateFlow<List<NavidromeCacheEntryEntity>> =
        navCacheManager.downloadedSongsFlow
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Per-song download-in-progress flags (navidromeId → true while downloading). */
    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    val username: String? get() = repository.username
    val serverUrl: String? get() = repository.serverUrl
    val isLoggedIn: StateFlow<Boolean> = repository.isLoggedInFlow
    val lastSyncTime: Long get() = repository.lastFullSyncTime

    init {
        observeSyncWorker()
        val lastSync = repository.lastFullSyncTime
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSync > NavidromeRepository.SYNC_THRESHOLD_MS) {
            syncAllPlaylistsAndSongs()
        }
    }

    private fun observeSyncWorker() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME_SYNC_ALL).collect { workInfos ->
                val workInfo = workInfos.firstOrNull() ?: return@collect

                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        _isSyncing.value = true
                        val progress = workInfo.progress.getFloat(NavidromeSyncWorker.PROGRESS_VALUE, 0f)
                        _syncProgress.value = if (progress > 0f) progress else null
                        _syncMessage.value = workInfo.progress.getString(NavidromeSyncWorker.PROGRESS_MESSAGE)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _isSyncing.value = false
                        _syncProgress.value = null
                    }
                    WorkInfo.State.FAILED -> {
                        _isSyncing.value = false
                        _syncProgress.value = null
                        _syncMessage.value = workInfo.outputData.getString(NavidromeSyncWorker.ERROR_MESSAGE) ?: "Sync failed"
                    }
                    else -> {
                        _isSyncing.value = false
                        _syncProgress.value = null
                    }
                }
            }
        }
    }

    fun syncAllPlaylistsAndSongs() {
        workManager.enqueueUniqueWork(
            WORK_NAME_SYNC_ALL,
            ExistingWorkPolicy.KEEP,
            NavidromeSyncWorker.startAllSync()
        )
    }

    fun syncPlaylistSongs(playlistId: String) {
        workManager.enqueueUniqueWork(
            "navidrome_sync_playlist_$playlistId",
            ExistingWorkPolicy.REPLACE,
            NavidromeSyncWorker.startPlaylistSync(playlistId)
        )
    }

    fun loadPlaylistSongs(playlistId: String) {
        viewModelScope.launch {
            repository.getPlaylistSongs(playlistId).collect { songs ->
                _selectedPlaylistSongs.value = songs
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
        }
    }

    fun isCached(navidromeId: String): Boolean = navCacheManager.isCached(navidromeId)

    fun downloadSong(navidromeId: String) {
        if (_downloadingIds.value.contains(navidromeId)) return
        viewModelScope.launch {
            _downloadingIds.value = _downloadingIds.value + navidromeId
            try {
                navCacheManager.downloadSong(navidromeId)
            } finally {
                _downloadingIds.value = _downloadingIds.value - navidromeId
            }
        }
    }

    fun removeSong(navidromeId: String) {
        viewModelScope.launch {
            navCacheManager.removeSong(navidromeId)
        }
    }

    companion object {
        private const val WORK_NAME_SYNC_ALL = "navidrome_sync_all"
    }
}
