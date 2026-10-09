package com.nuvio.app.features.player

/**
 * Profile-scoped persistence for [SubtitleVideoHashCache], so a file that was hashed once (a
 * Continue Watching entry, a restarted episode) has its OpenSubtitles hash immediately.
 */
internal expect object SubtitleVideoHashStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String?)
}
