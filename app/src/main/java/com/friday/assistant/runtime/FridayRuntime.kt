package com.friday.assistant.runtime

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.CopyOnWriteArrayList

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

    fun update(stage: String, detail: String, healthy: Boolean = true) {
        val next = RuntimeStatus(stage, detail, healthy, System.currentTimeMillis())
        status = next
        synchronized(historyLock) {
            eventHistory.addLast(next)
            while (eventHistory.size > 30) eventHistory.removeFirst()
        }
        listeners.forEach { runCatching { it(next) } }
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
    private fun emptySnapshot(): DeviceSnapshot = DeviceSnapshot(
        batteryPercent = 0,
        charging = false,
        batteryTempC = 0f,
        batteryHealth = "UNKNOWN",
        storageUsedGb = 0L,
        storageTotalGb = 0L,
        ramUsedGb = 0L,
        ramTotalGb = 0L,
        network = "UNKNOWN",
        interactive = false
    )

    fun snapshot(context: Context): DeviceSnapshot {
        // Telemetry must never be allowed to take down the UI process. OEM Android builds
        // can reject individual system-service calls, especially during early app startup.
        return runCatching {
            val battery = context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
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
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val network = when {
                caps == null -> "OFFLINE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE DATA"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "CONNECTED"
            }

            val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            DeviceSnapshot(
                batteryPercent = percent,
                charging = charging,
                batteryTempC = temp,
                batteryHealth = health,
                storageUsedGb = used / 1_000_000_000L,
                storageTotalGb = total / 1_000_000_000L,
                ramUsedGb = usedRam / 1_000_000_000L,
                ramTotalGb = totalRam / 1_000_000_000L,
                network = network,
                interactive = power?.isInteractive ?: false
            )
        }.getOrElse { emptySnapshot() }
    }
}
