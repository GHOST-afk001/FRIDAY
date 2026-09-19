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
import com.friday.assistant.runtime.FridayStateFlow
import java.lang.reflect.Method
import java.util.Locale
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
        // Prior builds could hear the microphone but never cross the wake gate on a real phone.
        // Use a recall-first threshold while still requiring multiple matching windows.
        private const val THRESHOLD = 0.30f
        private const val COOLDOWN_MS = 1_800L
        // Keep this only as a noise floor guard; normal quiet speech must reach the classifier.
        private const val RMS_GATE = 0.0010f
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
                var peak = 0
                for (sample in frame) {
                    val normalized = sample / 32768.0
                    energy += normalized * normalized
                    peak = max(peak, kotlin.math.abs(sample.toInt()))
                }
                val rms = sqrt(energy / frame.size).toFloat()
                // Drive the HUD orb from the actual always-on microphone, not only SpeechRecognizer.
                FridayStateFlow.updateAmplitude((rms / 0.06f).coerceIn(0f, 1f))
                if (rms < RMS_GATE) {
                    consecutiveWord = ""
                    consecutiveCount = 0
                    continue
                }

                val result = process.invoke(engine, frame) ?: continue
                if (!running.get()) continue
                val modelWord = result.javaClass.getField("wakeWord").get(result) as? String
                val probability = result.javaClass.getField("probability").getFloat(result)
                // The bundled engine has its own 0.50 detection gate and returns a null wakeWord below it.
                // FRIDAY uses a recall-first 0.30 threshold, so with the single Hey Friday model,
                // treat a score above our threshold as the known wake word.
                val word = modelWord ?: if (probability >= THRESHOLD) "Hey Friday" else ""
                // The bundled model recommends 3 windows. Two strong windows are enough for
                // a phone-mic wake because each window is already ~1 second of audio.
                val requiredFrames = result.javaClass.getField("recommendedConsFrames").getInt(result).coerceIn(2, 3)
                peakSinceLog = max(peakSinceLog, probability)
                val now = SystemClock.elapsedRealtime()
                if (now - scoreLogAt >= 1000L) {
                    Log.d(TAG, String.format(Locale.US, "Wake score=%.3f rms=%.4f peak=%d word=%s", peakSinceLog, rms, peak, word))
                    scoreLogAt = now
                    peakSinceLog = 0f
                }

                // Keep the HUD visibly alive while wake inference is running, but do not replace
                // the persistent WAKE LISTENING stage with noisy score updates.
                if (now - scoreLogAt < 50L) {
                    FridayRuntime.update("WAKE LISTENING", String.format(Locale.US, "Mic active • score %.2f • say Hey Friday", probability), true)
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
                        FridayStateFlow.updateAmplitude(1f)
                        FridayRuntime.update("WAKE DETECTED", String.format(Locale.US, "Hey Friday detected • %.0f%% confidence", probability * 100f), true)
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
        } catch (e: Throwable) {
            // Optional wake-word/native failures must never take down the FRIDAY process.
            FridayRuntime.update("WAKE ERROR", e.message ?: e.javaClass.simpleName, false)
            Log.e(TAG, "Wake detector stopped safely", e)
        } finally {
            try { localRecorder?.stop() } catch (_: Exception) {}
            try { localRecorder?.release() } catch (_: Exception) {}
            if (recorder === localRecorder) recorder = null
            try { engineClass?.getMethod("close")?.invoke(engine) } catch (_: Exception) {}
            FridayStateFlow.resetAmplitude()
            running.set(false)
            stoppedLatch = null
            latch.countDown()
            runCatching { onStopped() }.onFailure { Log.w(TAG, "Wake stop callback failed", it) }
        }
    }
}
