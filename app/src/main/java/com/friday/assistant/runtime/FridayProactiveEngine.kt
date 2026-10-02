package com.friday.assistant.runtime

import android.content.Context
import android.content.Intent
import android.os.BatteryManager

class FridayProactiveEngine(private val context: Context) {
    data class Suggestion(val key: String, val text: String)

    fun check(): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        val battery = context.registerReceiver(null, IntentFilterCompat.battery())
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0) (level * 100 / scale.coerceAtLeast(1)) else -1
        if (percent in 1..14) out += Suggestion("low_battery", "Battery ${percent}% hai, Boss. Charging consider kar lena.")
        return out
    }

    private object IntentFilterCompat {
        fun battery(): android.content.IntentFilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    }
}
