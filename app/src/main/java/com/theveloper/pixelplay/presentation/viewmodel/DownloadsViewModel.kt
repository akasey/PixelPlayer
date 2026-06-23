package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.database.NavidromeDao
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.navidrome.NavidromeCacheManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Backs the top-level Library → Downloads screen. Resolves the pinned-download cache entries into
 * playable [Song]s (joined against the synced Navidrome library) and exposes total on-disk size.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val cacheManager: NavidromeCacheManager,
    private val navidromeDao: NavidromeDao,
    private val downloadStateHolder: NavidromeDownloadStateHolder,
) : ViewModel() {

    /** Downloaded songs as playable [Song]s, newest download first. */
    val downloadedSongs: StateFlow<List<Song>> =
        cacheManager.downloadedSongsFlow
            .flatMapLatest { entries ->
                val orderedIds = entries.map { it.navidromeId }
                navidromeDao.getSongsByNavidromeIds(orderedIds).map { songEntities ->
                    val byId = songEntities.associateBy { it.navidromeId }
                    // Preserve the cache ordering (cached_at DESC) from the entries list.
                    orderedIds.mapNotNull { id -> byId[id]?.toSong() }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** Total bytes held by pinned downloads. */
    val totalDownloadBytes: StateFlow<Long> =
        cacheManager.downloadedSongsFlow
            .map { entries -> entries.sumOf { it.sizeBytes } }
            .stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    fun removeDownload(navidromeId: String) = downloadStateHolder.remove(navidromeId)
}
