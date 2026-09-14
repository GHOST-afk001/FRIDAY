package com.friday.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
        private const val THRESHOLD = 0.55f
        private const val COOLDOWN_MS = 1_800L
        private const val RMS_GATE = 0.008f
    }

    private val running = AtomicBoolean(false)
    private val wakeDelivered = AtomicBoolean(false)
    private val focusGeneration = AtomicLong(0L)
    @Volatile private var thread: Thread? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var stoppedLatch: CountDownLatch? = null
    @Volatile private var audioManager: AudioManager? = null
    @Volatile private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    @Volatile private var focusHeld = false

    fun start() {
        if (!running.compareAndSet(false, true)) return
        wakeDelivered.set(false)
        synchronized(this) {
            focusHeld = false
            focusGeneration.incrementAndGet()
        }
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
        synchronized(this) {
            focusHeld = false
            focusGeneration.incrementAndGet()
        }
        val activeRecorder = recorder
        try { activeRecorder?.stop() } catch (_: Exception) {}
        try { activeRecorder?.release() } catch (_: Exception) {}
        if (recorder === activeRecorder) recorder = null
        abandonAudioFocus()
        thread?.interrupt()
    }

    private fun requestAudioFocus(): Boolean {
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        val requestGeneration = synchronized(this) { focusGeneration.incrementAndGet() }
        audioManager = manager
        return try {
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                if (!running.get()) return@OnAudioFocusChangeListener
                val shouldStop = synchronized(this) {
                    if (!running.get() || focusGeneration.get() != requestGeneration) return@synchronized false
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            focusHeld = false
                            Log.i(TAG, "Permanent audio focus loss; releasing wake microphone")
                            onAudioFocusLost(true)
                            true
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            focusHeld = false
                            Log.i(TAG, "Transient audio focus loss; releasing wake microphone")
                            onAudioFocusLost(false)
                            true
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            Log.i(TAG, "Audio focus regained; waiting for coordinator lifecycle")
                            false
                        }
                        else -> false
                    }
                }
                if (shouldStop) stop()
            }
            audioFocusListener = listener
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                audioFocusRequest = request
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
            val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            synchronized(this) {
                focusHeld = granted && running.get() && focusGeneration.get() == requestGeneration
            }
            if (!granted || !running.get() || focusGeneration.get() != requestGeneration) {
                Log.i(TAG, "Audio focus not granted or session became stale; wake microphone will not start")
                abandonAudioFocus()
                return false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to request audio focus", e)
            abandonAudioFocus()
            false
        }
    }

    private fun abandonAudioFocus() {
        val manager: AudioManager?
        val request: AudioFocusRequest?
        val listener: AudioManager.OnAudioFocusChangeListener?
        synchronized(this) {
            manager = audioManager
            request = audioFocusRequest
            listener = audioFocusListener
            audioFocusRequest = null
            audioFocusListener = null
            audioManager = null
            focusHeld = false
        }
        try {
            if (manager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) request?.let { manager.abandonAudioFocusRequest(it) }
                else if (listener != null) {
                    @Suppress("DEPRECATION")
                    manager.abandonAudioFocus(listener)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to abandon audio focus cleanly", e)
        }
    }

    private fun loop(latch: CountDownLatch) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            if (!requestAudioFocus()) return
            var engineClass: Class<*>? = null
            var engine: Any? = null
            var localRecorder: AudioRecord? = null
            try {
                engineClass = Class.forName("com.voicute.wakeword.WakeWordEngine")
                engine = engineClass.getConstructor(Context::class.java).newInstance(context)
                if (!(engineClass.getMethod("isLoaded").invoke(engine) as? Boolean ?: false)) {
                    Log.w(TAG, "Wake model unavailable; system assistant invocation remains available.")
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
                val ring = ShortArray(needed)
                val frame = ShortArray(needed)
                val chunk = ShortArray(1600)
                var writeIndex = 0
                var filled = 0
                var lastWake = 0L
                var consecutiveWord = ""
                var consecutiveCount = 0
                while (running.get() && focusHeld && !Thread.currentThread().isInterrupted) {
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
                    if (filled < ring.size || !running.get() || !focusHeld) continue
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
                    if (!running.get() || !focusHeld) continue
                    val word = result.javaClass.getField("wakeWord").get(result) as? String ?: ""
                    val probability = result.javaClass.getField("probability").getFloat(result)
                    val requiredFrames = result.javaClass.getField("recommendedConsFrames").getInt(result).coerceIn(1, 8)
                    if (word.contains("friday", ignoreCase = true) && probability >= THRESHOLD) {
                        if (word.equals(consecutiveWord, ignoreCase = true)) consecutiveCount++
                        else { consecutiveWord = word; consecutiveCount = 1 }
                    } else {
                        consecutiveWord = ""
                        consecutiveCount = 0
                    }
                    if (consecutiveCount >= requiredFrames) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastWake > COOLDOWN_MS && wakeDelivered.compareAndSet(false, true)) {
                            lastWake = now
                            consecutiveWord = ""
                            consecutiveCount = 0
                            onWake(probability, frame.copyOf())
                        }
                    }
                }
            } finally {
                try { localRecorder?.stop() } catch (_: Exception) {}
                try { localRecorder?.release() } catch (_: Exception) {}
                if (recorder === localRecorder) recorder = null
                try { engineClass?.getMethod("close")?.invoke(engine) } catch (_: Exception) {}
                abandonAudioFocus()
            }
        } catch (e: ClassNotFoundException) {
            Log.i(TAG, "Optional wake runtime is not bundled in this build.")
        } catch (e: SecurityException) {
            Log.w(TAG, "Microphone access unavailable", e)
        } catch (e: Exception) {
            Log.e(TAG, "Wake detector stopped", e)
        } finally {
            running.set(false)
            stoppedLatch = null
            latch.countDown()
            runCatching { onStopped() }.onFailure { Log.w(TAG, "Wake stop callback failed", it) }
        }
    }
}
