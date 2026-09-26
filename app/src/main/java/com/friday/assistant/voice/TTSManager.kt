package com.friday.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.SpeechSynthesizer
import audio.soniqo.speech.SpeechSynthesizerConfig
import audio.soniqo.speech.TtsModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Local Kokoro TTS.
 *
 * - hf_alpha: Hindi female Kokoro voice.
 * - Model/assets are downloaded once to app-private storage; no API key or
 *   per-request billing.
 * - One synthesized PCM buffer is played per response, avoiding sentence-by-
 *   sentence TTS gaps and cut-offs.
 * - A new request cancels the old synthesis/playback instead of overlapping.
 */
class TTSManager(context: Context, private val onUnavailable: () -> Unit) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var audioTrack: AudioTrack? = null
    private var destroyed = false
    private val generation = AtomicLong(0L)

    fun speak(text: String, onDone: () -> Unit = {}) {
        if (text.isBlank()) {
            onDone()
            return
        }

        val myGeneration = generation.incrementAndGet()
        synchronized(lock) {
            if (destroyed) {
                onDone()
                return
            }
            job?.cancel()
            job = scope.launch {
                try {
                    stopPlayback()
                    val modelDir = ModelManager.ensureTtsModels(
                        appContext,
                        TtsModel.KOKORO_SHORT_TURN,
                    )
                    ensureActive()

                    val engine = synchronized(lock) {
                        if (destroyed) return@launch
                        synthesizer ?: SpeechSynthesizer(
                            SpeechSynthesizerConfig(
                                modelDir = modelDir,
                                useNnapi = false,
                                ttsModel = TtsModel.KOKORO_SHORT_TURN,
                            )
                        ).also { synthesizer = it }
                    }

                    val language = detectLanguage(text)
                    val spoken = engine.synthesize(text, language, "hf_alpha")
                    ensureActive()
                    playPcm(spoken.pcm16, spoken.sampleRate, myGeneration)
                    withContext(Dispatchers.Main.immediate) {
                        if (!destroyed && generation.get() == myGeneration) onDone()
                    }
                } catch (_: CancellationException) {
                    // Expected when a newer command interrupts the current reply.
                } catch (_: Throwable) {
                    withContext(Dispatchers.Main.immediate) {
                        if (!destroyed && generation.get() == myGeneration) {
                            onUnavailable()
                            onDone()
                        }
                    }
                }
            }
        }
    }

    private fun detectLanguage(text: String): String {
        if (text.any { it in '\u0900'..'\u097F' }) return "hi"

        // Friday often speaks Latin-script Hinglish. Use the Hindi Kokoro
        // phonemizer when the sentence contains clear Hindi markers; otherwise
        // use English phonemization for clean English pronunciation.
        val hindiMarkers = setOf(
            "aap", "hai", "hain", "ho", "hoga", "kar", "karo", "karti",
            "rahi", "raha", "mujhe", "mujh", "aapko", "mera", "meri",
            "tum", "yeh", "ye", "woh", "wo", "ka", "ki", "ke", "ko",
            "se", "mein", "me", "par", "abhi", "nahi", "haan", "acha",
            "accha", "theek", "kya", "kyun", "kyu", "bhi", "bahut"
        )
        val words = text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z']+"))
            .filter { it.isNotBlank() }
        val hindiHits = words.count { it in hindiMarkers }
        return if (hindiHits >= 2 || (hindiHits == 1 && words.size <= 7)) "hi" else "en"
    }

    private fun playPcm(pcm16: ByteArray, sampleRate: Int, myGeneration: Long) {
        if (pcm16.isEmpty()) return
        synchronized(lock) {
            if (destroyed || generation.get() != myGeneration) return
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, pcm16.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        synchronized(lock) {
            if (destroyed || generation.get() != myGeneration) {
                track.release()
                return
            }
            audioTrack = track
        }

        try {
            track.write(pcm16, 0, pcm16.size)
            track.play()
            while (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                if (generation.get() != myGeneration || destroyed) break
                Thread.sleep(25L)
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            synchronized(lock) {
                if (audioTrack === track) audioTrack = null
            }
        }
    }

    private fun stopPlayback() {
        synchronized(lock) {
            runCatching { synthesizer?.stop() }
            runCatching { audioTrack?.pause() }
            runCatching { audioTrack?.flush() }
        }
    }

    fun shutdown() {
        val engine = synchronized(lock) {
            if (destroyed) return
            destroyed = true
            generation.incrementAndGet()
            job?.cancel()
            job = null
            runCatching { audioTrack?.stop() }
            runCatching { audioTrack?.release() }
            audioTrack = null
            synthesizer.also { synthesizer = null }
        }
        runCatching { engine?.close() }
        scope.cancel()
    }
}
