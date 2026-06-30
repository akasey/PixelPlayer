package com.theveloper.pixelplay.data.model

enum class StorageFilter(val value: Int) {
    ALL(0),
    OFFLINE(1),
    ONLINE(2),

    /**
     * "Available offline" — local songs plus Navidrome songs with a completed pinned download.
     * Unlike [OFFLINE] (which is purely local `source_type = 0`), this includes downloaded cloud
     * songs so the library can be scoped to everything playable with no network.
     */
    DOWNLOADED(3)
}
