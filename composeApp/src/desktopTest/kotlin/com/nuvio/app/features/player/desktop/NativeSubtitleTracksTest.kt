package com.nuvio.app.features.player.desktop

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeSubtitleTracksTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun externalAddonSubtitleIsNotABuiltInTrack() {
        // Shape of the bridge's subtitle track list after an addon subtitle was loaded with sub-add.
        val tracks = json.decodeFromString<List<NativeMpvTrack>>(
            """
            [
              {"index":0,"id":"1","label":"en (subrip)","language":"eng","selected":false,"forced":false,"external":false},
              {"index":1,"id":"2","label":"ja (subrip)","language":"jpn","selected":false,"forced":false},
              {"index":2,"id":"3","label":"episode-1-en.srt (subrip)","language":"","selected":true,"forced":false,"external":true}
            ]
            """.trimIndent(),
        )

        val builtIn = tracks.toBuiltInSubtitleTracks()

        assertEquals(listOf(0, 1), builtIn.map { it.index })
        assertEquals(listOf("1", "2"), builtIn.map { it.id })
        assertEquals(listOf(false, false), builtIn.map { it.isSelected })
    }
}
