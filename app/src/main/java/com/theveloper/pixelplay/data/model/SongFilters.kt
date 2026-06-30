package com.theveloper.pixelplay.data.model

/**
 * Storage/availability filtering for in-memory [Song] lists. Mirrors the SQL `filterMode`
 * predicate used by the paginated library queries, so list-based surfaces (album/artist detail,
 * shuffle scoping) classify songs identically to the database.
 */

/** A song is "local" when it carries no remote-source identifier. */
fun Song.isLocal(): Boolean =
    telegramFileId == null && neteaseId == null && gdriveFileId == null &&
        qqMusicMid == null && navidromeId == null && jellyfinId == null

/**
 * "Available offline" — local songs, plus Navidrome songs whose id is in [downloadedNavidromeIds]
 * (a completed pinned download). Matches [StorageFilter.DOWNLOADED].
 */
fun Song.isOfflineAvailable(downloadedNavidromeIds: Set<String>): Boolean =
    isLocal() || (navidromeId != null && navidromeId in downloadedNavidromeIds)

/** Filters a song list by [filter]; [downloadedNavidromeIds] is only consulted for [StorageFilter.DOWNLOADED]. */
fun List<Song>.filterByStorage(
    filter: StorageFilter,
    downloadedNavidromeIds: Set<String>,
): List<Song> = when (filter) {
    StorageFilter.ALL -> this
    StorageFilter.OFFLINE -> filter { it.isLocal() }
    StorageFilter.ONLINE -> filterNot { it.isLocal() }
    StorageFilter.DOWNLOADED -> filter { it.isOfflineAvailable(downloadedNavidromeIds) }
}
