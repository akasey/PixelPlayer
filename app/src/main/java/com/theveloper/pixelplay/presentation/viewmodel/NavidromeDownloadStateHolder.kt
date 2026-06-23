package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.navidrome.NavidromeCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for Navidrome download state, shared by every surface that offers a
 * download action (song ⋮ sheet, full player sheet, album/playlist detail, Library Downloads, the
 * dashboard). Exposes reactive sets instead of the old blocking `isCached()` call so Compose can
 * observe download status without touching disk on the main thread.
 */
@Singleton
class NavidromeDownloadStateHolder @Inject constructor(
    private val cacheManager: NavidromeCacheManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** IDs of songs with a completed pinned download. */
    val downloadedIds: StateFlow<Set<String>> =
        cacheManager.downloadedSongsFlow
            .map { entries -> entries.mapTo(HashSet()) { it.navidromeId } as Set<String> }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    /** IDs of songs whose download is currently in flight. */
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    /** Downloads (or no-ops if already downloaded/in-flight) the audio for [navidromeId]. */
    fun download(navidromeId: String) {
        if (_downloadingIds.value.contains(navidromeId)) return
        if (downloadedIds.value.contains(navidromeId)) return
        scope.launch {
            _downloadingIds.value = _downloadingIds.value + navidromeId
            try {
                cacheManager.downloadSong(navidromeId)
            } finally {
                _downloadingIds.value = _downloadingIds.value - navidromeId
            }
        }
    }

    /** Removes the pinned download for [navidromeId]. */
    fun remove(navidromeId: String) {
        scope.launch { cacheManager.removeSong(navidromeId) }
    }

    /** Downloads every id not already downloaded/in-flight (used for "Download all"). */
    fun downloadAll(navidromeIds: Collection<String>) {
        navidromeIds.forEach { download(it) }
    }
}
