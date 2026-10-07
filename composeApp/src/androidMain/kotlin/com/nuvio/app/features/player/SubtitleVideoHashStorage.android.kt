package com.nuvio.app.features.player

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SubtitleVideoHashStorage {
    private const val preferencesName = "nuvio_subtitle_video_hashes"
    private const val payloadKey = "subtitle_video_hashes"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadPayload(): String? =
        preferences?.getString(ProfileScopedKey.of(payloadKey), null)?.takeIf { it.isNotBlank() }

    actual fun savePayload(payload: String?) {
        preferences
            ?.edit()
            ?.apply {
                if (payload.isNullOrBlank()) remove(ProfileScopedKey.of(payloadKey))
                else putString(ProfileScopedKey.of(payloadKey), payload)
            }
            ?.apply()
    }
}
