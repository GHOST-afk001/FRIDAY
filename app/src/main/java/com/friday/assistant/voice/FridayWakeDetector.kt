package com.friday.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.friday.assistant.runtime.FridayRuntime
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/** Offline custom-wake bridge. The optional classifier is fetched and bundled by CI. */
class FridayWakeDetector(
    private val context: Context,
    private val onWake: (Float, ShortArray) -> Unit,
    private val onAudioFocusLost: (Boolean) -> Unit = {},
    private val onStopped: () -> Unit = {}
) {
    companion object {
        private const val TAG = "FridayWakeDetector"
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        // Voicute documents 0.5 as the baseline threshold. 0.45 gives the real phone mic
        // a little more headroom without turning the detector into an always-triggered gate.
        private const val THRESHOLD = 0.45f
        private const val COOLDOWN_MS = 1_800L
        // Do not discard quiet but valid speech before the classifier sees it.
        private const val RMS_GATE = 0.0015f
    }

    private val running = AtomicBoolean(false)
    private val wakeDelivered = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var stoppedLatch: CountDownLatch? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        wakeDelivered.set(false)
        val latch = CountDownLatch(1)
        stoppedLatch = latch
        val worker = Thread({ loop(latch) }, "FridayWakeEngineThread")
        thread = worker
        worker.start()
    }

    fun isRunning(): Boolean = running.get()

    fun stopAndWait(timeoutMs: Long = 3000L): Boolean {
        val worker = thread
        val latch = stoppedLatch
        stop()
        if (Thread.currentThread() === worker) return !running.get()
        if (latch == null) return !running.get()
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS) && !running.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun stop() {
        running.set(false)
        val activeRecorder = recorder
        try { activeRecorder?.stop() } catch (_: Exception) {}
        try { activeRecorder?.release() } catch (_: Exception) {}
        if (recorder === activeRecorder) recorder = null
        thread?.interrupt()
    }

    private fun loop(latch: CountDownLatch) {
        var engineClass: Class<*>? = null
        var engine: Any? = null
        var localRecorder: AudioRecord? = null
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                FridayRuntime.update("MIC BLOCKED", "Microphone permission is not available to the wake service", false)
                return
            }

            engineClass = Class.forName("com.voicute.wakeword.WakeWordEngine")
            engine = engineClass.getConstructor(Context::class.java).newInstance(context)
            if (!(engineClass.getMethod("isLoaded").invoke(engine) as? Boolean ?: false)) {
                FridayRuntime.update("WAKE MODEL ERROR", "Hey Friday wake model could not be loaded", false)
                return
            }

            val needed = max(16080, engineClass.getMethod("getAudioSamplesNeeded").invoke(engine) as? Int ?: 16080)
            val process: Method = engineClass.getMethod("process", ShortArray::class.java)
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) error("Invalid AudioRecord minimum buffer: $minBuffer")
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val builder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(minBuffer, needed * BYTES_PER_SAMPLE * 2))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setContext(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setPrivacySensitive(true)

            localRecorder = builder.build()
            if (localRecorder.state != AudioRecord.STATE_INITIALIZED) error("AudioRecord failed to initialize")
            recorder = localRecorder
            localRecorder.startRecording()
            if (localRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("AudioRecord failed to start")

            // Do NOT request transient media audio focus for the always-on assistant microphone.
            // Samsung/Android may revoke transient focus immediately, which leaves AudioRecord
            // alive but effectively silent. VoiceInteractionService already owns the assistant
            // microphone lifecycle, so the wake detector should capture without taking media focus.
            FridayRuntime.update("WAKE LISTENING", "Microphone active • listening for Hey Friday", true)
            Log.i(TAG, "Wake microphone started: ${SAMPLE_RATE}Hz mono, buffer=$needed")

            val ring = ShortArray(needed)
            val frame = ShortArray(needed)
            val chunk = ShortArray(1600)
            var writeIndex = 0
            var filled = 0
            var lastWake = 0L
            var consecutiveWord = ""
            var consecutiveCount = 0
            var scoreLogAt = 0L
            var peakSinceLog = 0f

            while (running.get() && !Thread.currentThread().isInterrupted) {
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
                if (filled < ring.size || !running.get()) continue
                if (writeIndex == 0) System.arraycopy(ring, 0, frame, 0, ring.size)
                else {
                    val tail = ring.size - writeIndex
                    System.arraycopy(ring, writeIndex, frame, 0, tail)
                    System.arraycopy(ring, 0, frame, tail, writeIndex)
                }

                var energy = 0.0
                for (sample in frame) {
                    val normalized = sample / 32768.0
                    energy += normalized * normalized
                }
                val rms = sqrt(energy / frame.size).toFloat()
                if (rms < RMS_GATE) {
                    consecutiveWord = ""
                    consecutiveCount = 0
                    continue
                }

                val result = process.invoke(engine, frame) ?: continue
                if (!running.get()) continue
                val word = result.javaClass.getField("wakeWord").get(result) as? String ?: ""
                val probability = result.javaClass.getField("probability").getFloat(result)
                val requiredFrames = result.javaClass.getField("recommendedConsFrames").getInt(result).coerceIn(1, 8)
                peakSinceLog = max(peakSinceLog, probability)
                val now = SystemClock.elapsedRealtime()
                if (now - scoreLogAt >= 3000L) {
                    Log.d(TAG, "Wake score=${"%.3f".format(peakSinceLog)} rms=${"%.4f".format(rms)} word=$word")
                    scoreLogAt = now
                    peakSinceLog = 0f
                }

                if (word.contains("friday", ignoreCase = true) && probability >= THRESHOLD) {
                    if (word.equals(consecutiveWord, ignoreCase = true)) consecutiveCount++
                    else { consecutiveWord = word; consecutiveCount = 1 }
                } else {
                    consecutiveWord = ""
                    consecutiveCount = 0
                }

                if (consecutiveCount >= requiredFrames) {
                    if (now - lastWake > COOLDOWN_MS && wakeDelivered.compareAndSet(false, true)) {
                        lastWake = now
                        consecutiveWord = ""
                        consecutiveCount = 0
                        FridayRuntime.update("WAKE DETECTED", "Hey Friday detected • opening voice session", true)
                        onWake(probability, frame.copyOf())
                    }
                }
            }
        } catch (e: ClassNotFoundException) {
            FridayRuntime.update("WAKE RUNTIME MISSING", "Hey Friday wake engine is not bundled", false)
            Log.i(TAG, "Optional wake runtime is not bundled in this build.")
        } catch (e: SecurityException) {
            FridayRuntime.update("MIC ERROR", "Android denied wake microphone access", false)
            Log.w(TAG, "Microphone access unavailable", e)
        } catch (e: Exception) {
            FridayRuntime.update("WAKE ERROR", e.message ?: "Wake detector stopped unexpectedly", false)
            Log.e(TAG, "Wake detector stopped", e)
        } finally {
            try { localRecorder?.stop() } catch (_: Exception) {}
            try { localRecorder?.release() } catch (_: Exception) {}
            if (recorder === localRecorder) recorder = null
            try { engineClass?.getMethod("close")?.invoke(engine) } catch (_: Exception) {}
            running.set(false)
            stoppedLatch = null
            latch.countDown()
            runCatching { onStopped() }.onFailure { Log.w(TAG, "Wake stop callback failed", it) }
        }
    }
}
