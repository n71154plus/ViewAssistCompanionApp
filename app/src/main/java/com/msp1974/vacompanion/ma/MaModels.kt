package com.msp1974.vacompanion.ma

// ── Session ────────────────────────────────────────────────────────────────────

data class MaSession(
    val baseUrl: String,
    val username: String,
    val password: String
)

// ── Browse / library item ─────────────────────────────────────────────────────

data class MaBrowseItem(
    val itemId: String,
    val uri: String,
    val name: String,
    /** "track" / "album" / "artist" / "playlist" / "radio" / "folder" */
    val mediaType: String,
    /** Navigation path — same as uri for playable items, path field for folders. */
    val path: String,
    val imageUrl: String?,
    val artistName: String = "",
    val albumName: String  = "",
    val duration: Int      = 0,      // seconds (tracks)
    val year: Int          = 0,      // albums
    val isPlayable: Boolean = false,
    val isFavorite: Boolean = false
) {
    val isFolder get() = mediaType == "folder"
}

// ── Player ────────────────────────────────────────────────────────────────────

data class MaPlayer(
    val playerId: String,
    val name: String,
    /** "idle" / "playing" / "paused" / "off" */
    val state: String,
    val volumeLevel: Int = 0,
    val elapsedTime: Float = 0f,
    val elapsedTimeLastUpdated: Long = 0L,
    val currentTrackName: String = "",
    val currentArtistName: String = "",
    val currentDuration: Int = 0,
    val groupChildPlayerIds: List<String> = emptyList()
) {
    val isGroup: Boolean get() = groupChildPlayerIds.isNotEmpty()
}

// ── Search results ────────────────────────────────────────────────────────────

data class MaSearchResults(
    val artists: List<MaBrowseItem>   = emptyList(),
    val albums: List<MaBrowseItem>    = emptyList(),
    val tracks: List<MaBrowseItem>    = emptyList(),
    val playlists: List<MaBrowseItem> = emptyList()
) {
    val isEmpty get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() && playlists.isEmpty()
}

// ── Queue item (used by MaMediaPlayerRow) ─────────────────────────────────────

data class MaQueueItem(
    val queueItemId: String,
    val name: String,
    val artist: String,
    val duration: Int,
    val imageUrl: String?,
    val mediaItemUri: String = "",
    val album: String = ""
)

data class MaQueueSnapshot(
    val queueId: String,
    val state: String,
    val repeatMode: String,
    val shuffleEnabled: Boolean,
    val currentIndex: Int,
    val elapsedTime: Float,
    val items: List<MaQueueItem>
)

data class MaLyrics(
    val lines: List<String> = emptyList()
) {
    val text: String get() = lines.joinToString("\n")
    val isEmpty: Boolean get() = lines.isEmpty()
}
