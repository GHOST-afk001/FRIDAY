package com.friday.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Offline custom-wake bridge. The optional classifier is fetched and bundled by CI. */
class FridayWakeDetector(
    private val context: Context,
    private val onWake: (Float) -> Unit
) {
    companion object {
        private const val TAG = "FridayWakeDetector"
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({ loop() }, "friday-wake-detector").also { it.start() }
    }

    fun stop() {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running.set(false)
            return
        }

        try {
            val engineClass = Class.forName("com.voicute.wakeword.WakeWordEngine")
            val engine = engineClass.getConstructor(Context::class.java).newInstance(context)
            if (!(engineClass.getMethod("isLoaded").invoke(engine) as? Boolean ?: false)) {
                Log.w(TAG, "Wake model unavailable; system assistant invocation remains available.")
                running.set(false)
                return
            }

            val needed = max(16080, engineClass.getMethod("getAudioSamplesNeeded").invoke(engine) as? Int ?: 16080)
            val process: Method = engineClass.getMethod("process", ShortArray::class.java)
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) error("Invalid AudioRecord minimum buffer: $minBuffer")

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val localRecorder = AudioRecord.Builder()
                .setContext(context)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuffer, needed * BYTES_PER_SAMPLE * 2))
                .setPrivacySensitive(true)
                .build()

            if (localRecorder.state != AudioRecord.STATE_INITIALIZED) {
                localRecorder.release()
                error("AudioRecord failed to initialize")
            }

            recorder = localRecorder
            localRecorder.startRecording()
            if (localRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error("AudioRecord failed to start")
            }

            // Circular buffer prevents the previous shifting logic from corrupting frames.
            val ring = ShortArray(needed)
            var writeIndex = 0
            var filled = 0
            var lastWake = 0L
            val chunk = ShortArray(1600)

            while (running.get()) {
                val read = localRecorder.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                if (read >= ring.size) {
                    System.arraycopy(chunk, read - ring.size, ring, 0, ring.size)
                    writeIndex = 0
                    filled = ring.size
                } else {
                    val first = minOf(read, ring.size - writeIndex)
                    System.arraycopy(chunk, 0, ring, writeIndex, first)
                    if (first < read) System.arraycopy(chunk, first, ring, 0, read - first)
                    writeIndex = (writeIndex + read) % ring.size
                    filled = minOf(ring.size, filled + read)
                }

                if (filled < ring.size) continue

                val frame = if (writeIndex == 0) {
                    ring.copyOf()
                } else {
                    ShortArray(ring.size).also {
                        val tail = ring.size - writeIndex
                        System.arraycopy(ring, writeIndex, it, 0, tail)
                        System.arraycopy(ring, 0, it, tail, writeIndex)
                    }
                }

                val result = process.invoke(engine, frame) ?: continue
                val word = result.javaClass.getField("wakeWord").get(result) as? String ?: ""
                val probability = result.javaClass.getField("probability").getFloat(result)
                if (word.contains("friday", ignoreCase = true) && probability >= 0.55f) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastWake > 1800) {
                        lastWake = now
                        onWake(probability)
                    }
                }
            }
        } catch (e: ClassNotFoundException) {
            Log.i(TAG, "Optional wake runtime is not bundled in this build.")
        } catch (e: SecurityException) {
            Log.w(TAG, "Microphone access unavailable", e)
        } catch (e: Exception) {
            Log.e(TAG, "Wake detector stopped", e)
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            running.set(false)
        }
    }
}
