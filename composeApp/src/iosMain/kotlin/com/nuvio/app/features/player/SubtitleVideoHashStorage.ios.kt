package com.nuvio.app.features.player

/**
 * iOS never computes video hashes (see [SubtitleVideoHashRepository.isSupported]), so there is
 * nothing to persist.
 */
internal actual object SubtitleVideoHashStorage {
    actual fun loadPayload(): String? = null

    actual fun savePayload(payload: String?) = Unit
}
