package com.friday.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.friday.assistant.runtime.FridayRuntime
import dev.ffmpegkit.kokoro.AudioFormat as KokoroAudioFormat
import dev.ffmpegkit.kokoro.KokoroConfig
import dev.ffmpegkit.kokoro.KokoroTTS
import dev.ffmpegkit.kokoro.KokoroVoice
import dev.ffmpegkit.kokoro.Gender
import dev.ffmpegkit.kokoro.Grade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Fully local Kokoro TTS with one-time model/voice download and uninterrupted playback. */
class KokoroLocalTts(private val context: Context) {
    private val mutex = Mutex()
    private var initialized = false

    private val modelUrl =
        "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.int8.onnx"
    private val voiceUrl =
        "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/voices/hf_alpha.bin?download=true"

    private val modelFile get() = File(context.filesDir, "tts/kokoro-v1.0.int8.onnx")
    private val voiceFile get() = File(context.filesDir, "tts/hf_alpha.bin")

    private val hindiFemale = KokoroVoice(
        id = "hf_alpha",
        name = "Alpha (Hindi Female)",
        language = "hi-IN",
        espeakLang = "hi",
        gender = Gender.FEMALE,
        grade = Grade.C
    )

    suspend fun speak(text: String, speed: Float = 0.96f): Boolean = mutex.withLock {
        runCatching {
            ensureInitialized()
            val chunks = chunk(text)
            if (chunks.isEmpty()) return false

            // Synthesize first, then play the complete queue: no mid-response TTS gaps.
            val pcm = ArrayList<ByteArray>(chunks.size)
            for (part in chunks) {
                val result = KokoroTTS.speak(
                    part,
                    KokoroConfig(
                        speed = speed,
                        sampleRate = 24000,
                        outputFormat = KokoroAudioFormat.PCM
                    )
                )
                pcm += result.audioData
            }
            playPcm(pcm)
            true
        }.getOrElse {
            FridayRuntime.update("KOKORO FALLBACK", "Local neural TTS unavailable; using Android TTS", false)
            false
        }
    }

    suspend fun warmUp() {
        runCatching { mutex.withLock { ensureInitialized() } }
    }

    fun shutdown() {
        runCatching { KokoroTTS.release() }
        initialized = false
    }

    private suspend fun ensureInitialized() = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        val dir = modelFile.parentFile ?: error("Unable to create TTS directory")
        if (!dir.exists()) dir.mkdirs()

        downloadIfMissing(modelFile, modelUrl, 10L * 1024L * 1024L)
        downloadIfMissing(voiceFile, voiceUrl, 100L * 1024L)

        KokoroTTS.initialize(context, modelFile.absolutePath, KokoroVoice.AF_HEART)
        check(KokoroTTS.addVoice(voiceFile.absolutePath)) { "hf_alpha voicepack could not be loaded" }
        KokoroTTS.setVoice(hindiFemale)
        initialized = true
        FridayRuntime.update("KOKORO READY", "Local Hindi female voice hf_alpha is ready", true)
    }

    private fun downloadIfMissing(file: File, url: String, minimumBytes: Long) {
        if (file.exists() && file.length() >= minimumBytes) return
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".part")
        if (temp.exists()) temp.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                error("TTS download failed: HTTP " + connection.responseCode)
            }
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(temp.length() >= minimumBytes) { "Downloaded TTS file is incomplete" }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun chunk(text: String): List<String> {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return emptyList()
        return normalized
            .split(Regex("(?<=[.!?।])\\s+"))
            .flatMap { sentence -> if (sentence.length <= 180) listOf(sentence) else sentence.chunked(150) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun playPcm(chunks: List<ByteArray>) {
        if (chunks.sumOf { it.size } == 0) return
        val minBuffer = AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(24_000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, 24_000 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            for (chunk in chunks) {
                var offset = 0
                while (offset < chunk.size) {
                    val written = track.write(chunk, offset, chunk.size - offset)
                    if (written <= 0) break
                    offset += written
                }
            }
            track.stop()
        } finally {
            track.release()
        }
    }
}
