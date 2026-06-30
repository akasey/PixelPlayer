package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.database.DownloadSource
import com.theveloper.pixelplay.data.navidrome.NavidromeCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * IDs of songs whose download is currently in flight. Sourced from the cache manager so manual
     * taps and auto-downloads (via the worker) surface a single, consistent spinner.
     */
    val downloadingIds: StateFlow<Set<String>> = cacheManager.inFlightIds

    /** Downloads (or no-ops if already downloaded/in-flight) the audio for [navidromeId]. */
    fun download(navidromeId: String) {
        if (downloadingIds.value.contains(navidromeId)) return
        if (downloadedIds.value.contains(navidromeId)) return
        // Manual download — de-dupe and in-flight tracking live in cacheManager.downloadSong.
        scope.launch { cacheManager.downloadSong(navidromeId, DownloadSource.MANUAL) }
    }

    /** Removes the pinned download for [navidromeId]. */
    fun remove(navidromeId: String) {
        scope.launch { cacheManager.removeSong(navidromeId) }
    }

    /** Promotes an auto-download to a permanent manual download (the Downloads "Keep" action). */
    fun keep(navidromeId: String) {
        scope.launch { cacheManager.keepDownload(navidromeId) }
    }

    /** Downloads every id not already downloaded/in-flight (used for "Download all"). */
    fun downloadAll(navidromeIds: Collection<String>) {
        navidromeIds.forEach { download(it) }
    }

    /** Removes the pinned downloads for every id (used for "Remove all" on an album/artist). */
    fun removeAll(navidromeIds: Collection<String>) {
        navidromeIds.forEach { remove(it) }
    }
}
