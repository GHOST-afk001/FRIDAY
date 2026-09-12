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

/**
 * Offline wake-word bridge.
 *
 * The actual classifier is the open-source Voicute ONNX wake-word runtime, fetched by CI
 * from its public repository and compiled into the APK. Reflection keeps the core app buildable
 * even when the optional runtime/model is not present locally.
 */
class FridayWakeDetector(
    private val context: Context,
    private val onWake: (Float) -> Unit
) {
    companion object { private const val TAG = "FridayWakeDetector" }

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
        recorder?.release()
        recorder = null
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running.set(false); return
        }

        try {
            val engineClass = Class.forName("com.voicute.wakeword.WakeWordEngine")
            val engine = engineClass.getConstructor(Context::class.java).newInstance(context)
            val isLoaded = engineClass.getMethod("isLoaded").invoke(engine) as? Boolean ?: false
            if (!isLoaded) {
                Log.w(TAG, "Wake model unavailable; assistant invocation remains available.")
                running.set(false); return
            }
            val needed = max(16080, (engineClass.getMethod("getAudioSamplesNeeded").invoke(engine) as? Int ?: 16080))
            val process: Method = engineClass.getMethod("process", ShortArray::class.java)

            val minBuffer = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minBuffer, needed * 2)
            val localRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            recorder = localRecorder
            localRecorder.startRecording()

            val ring = ShortArray(needed)
            var ringCount = 0
            var lastWake = 0L
            val chunk = ShortArray(1600)

            while (running.get()) {
                val read = localRecorder.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                for (i in 0 until read) {
                    if (ringCount < ring.size) {
                        ring[ringCount++] = chunk[i]
                    } else {
                        System.arraycopy(ring, 1600, ring, 0, ring.size - 1600)
                        ring[ring.size - 1600] = chunk[i]
                        // refill the tail with the remaining chunk below
                        for (j in i + 1 until read) {
                            System.arraycopy(ring, 1600, ring, 0, ring.size - 1600)
                            ring[ring.size - 1600] = chunk[j]
                        }
                        break
                    }
                }
                if (ringCount < ring.size) continue

                val result = process.invoke(engine, ring.copyOf()) ?: continue
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
            recorder?.release(); recorder = null
            running.set(false)
        }
    }
}
