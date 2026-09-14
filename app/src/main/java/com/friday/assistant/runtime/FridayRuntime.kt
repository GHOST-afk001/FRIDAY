package com.friday.assistant.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Small, in-memory runtime bus used by the HUD. It never stores message bodies or secrets. */
data class RuntimeStatus(
    val stage: String = "IDLE",
    val detail: String = "FRIDAY ready",
    val healthy: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

object FridayRuntime {
    @Volatile var status: RuntimeStatus = RuntimeStatus()
        private set

    private val listeners = CopyOnWriteArrayList<(RuntimeStatus) -> Unit>()
    private val eventHistory = ArrayDeque<RuntimeStatus>()
    private val historyLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun update(stage: String, detail: String, healthy: Boolean = true) {
        val next = RuntimeStatus(stage, detail, healthy, System.currentTimeMillis())
        status = next
        FridayStateFlow.updateRuntime(next)
        synchronized(historyLock) {
            eventHistory.addLast(next)
            while (eventHistory.size > 30) eventHistory.removeFirst()
        }
        // Runtime updates can originate from audio/AI/background threads. Deliver HUD callbacks
        // on the main thread so Compose state is never mutated from a worker thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { runCatching { it(next) } }
        } else {
            mainHandler.post {
                listeners.forEach { runCatching { it(next) } }
            }
        }
    }

    fun history(): List<RuntimeStatus> = synchronized(historyLock) { eventHistory.toList().asReversed() }

    fun observe(listener: (RuntimeStatus) -> Unit): AutoCloseable {
        listeners += listener
        listener(status)
        return AutoCloseable { listeners -= listener }
    }
}

data class DeviceSnapshot(
    val batteryPercent: Int,
    val charging: Boolean,
    val batteryTempC: Float,
    val batteryHealth: String,
    val storageUsedGb: Long,
    val storageTotalGb: Long,
    val ramUsedGb: Long,
    val ramTotalGb: Long,
    val network: String,
    val interactive: Boolean
)

object DeviceTelemetry {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "friday-telemetry").apply { isDaemon = true } }
    private val refreshRunning = AtomicBoolean(false)
    @Volatile private var cached = DeviceSnapshot(0, false, 0f, "UNKNOWN", 0L, 0L, 0L, 0L, "UNKNOWN", false)
    @Volatile private var lastRefresh = 0L

    /** Returns cached data immediately and schedules the actual system probes off the main thread. */
    fun snapshot(context: Context): DeviceSnapshot {
        val now = System.currentTimeMillis()
        if (now - lastRefresh > 900L && refreshRunning.compareAndSet(false, true)) {
            val appContext = context.applicationContext
            executor.execute {
                try {
                    cached = readSnapshot(appContext)
                    lastRefresh = System.currentTimeMillis()
                } finally {
                    refreshRunning.set(false)
                }
            }
        }
        return cached
    }

    private fun readSnapshot(context: Context): DeviceSnapshot = runCatching {
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 0
        val state = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val health = when (battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER VOLTAGE"
            BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
            else -> "UNKNOWN"
        }
        val stat = StatFs(android.os.Environment.getDataDirectory().path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val used = (total - free).coerceAtLeast(0)
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memory = android.app.ActivityManager.MemoryInfo()
        activity?.getMemoryInfo(memory)
        val totalRam = memory.totalMem.coerceAtLeast(1)
        val usedRam = (totalRam - memory.availMem).coerceAtLeast(0)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = if (context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED) {
            val activeNetwork = cm?.activeNetwork
            val caps = if (activeNetwork != null) cm?.getNetworkCapabilities(activeNetwork) else null
            when {
                caps == null -> "OFFLINE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE DATA"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "CONNECTED"
            }
        } else {
            "UNKNOWN"
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        DeviceSnapshot(percent, charging, temp, health, used / 1_000_000_000L, total / 1_000_000_000L, usedRam / 1_000_000_000L, totalRam / 1_000_000_000L, network, power?.isInteractive ?: false)
    }.getOrElse { cached }
}
