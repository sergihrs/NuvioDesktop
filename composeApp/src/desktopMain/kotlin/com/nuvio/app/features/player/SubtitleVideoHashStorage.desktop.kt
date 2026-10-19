package com.nuvio.app.features.player

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SubtitleVideoHashStorage {
    private const val payloadKey = "subtitle_video_hashes"
    private val store = DesktopStorage.store("nuvio_subtitle_video_hashes")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of(payloadKey))?.takeIf { it.isNotBlank() }

    actual fun savePayload(payload: String?) {
        store.putString(ProfileScopedKey.of(payloadKey), payload?.takeIf { it.isNotBlank() })
    }
}
