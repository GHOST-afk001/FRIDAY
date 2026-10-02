package com.friday.assistant.voice

import android.content.Context

/**
 * Optional local neural TTS adapter.
 *
 * The Kokoro Android AAR advertised by its upstream repository is not currently
 * resolvable from Maven Central, so this adapter is intentionally a no-op rather
 * than making the FRIDAY build depend on an unavailable artifact.
 *
 * TTSManager falls back to Android's on-device TTS engine, including hi-IN/en-IN
 * voices, without API keys or subscriptions.
 */
class KokoroLocalTts(private val context: Context) {
    suspend fun speak(text: String, speed: Float = 0.96f): Boolean = false

    suspend fun warmUp() = Unit

    fun shutdown() = Unit
}
