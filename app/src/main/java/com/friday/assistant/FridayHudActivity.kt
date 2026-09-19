package com.friday.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import androidx.activity.ComponentActivity
import android.graphics.drawable.GradientDrawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native HUD step 2.
 *
 * Keeps the stable native-View approach from Build #790/#805, but makes the
 * visible HUD live: clock/date, battery health, mode interaction and a
 * continuously refreshed system panel. No Canvas, coroutines, Gemini startup,
 * microphone, wake-word runtime or system assistant bridge is started here.
 */
class FridayHudActivity : ComponentActivity() {

    private val orange = Color.rgb(255, 137, 48)
    private val red = Color.rgb(229, 72, 78)
    private val pale = Color.rgb(244, 220, 221)
    private val panel = Color.rgb(15, 8, 10)
    private val border = Color.rgb(107, 31, 37)

    private lateinit var clockLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var modeLabel: TextView
    private lateinit var batteryLabel: TextView
    private lateinit var batteryBar: ProgressBar
    private lateinit var systemLabel: TextView
    private lateinit var voiceLabel: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var standby = true

    private val tick = object : Runnable {
        override fun run() {
            refreshLiveHud()
            handler.postDelayed(this, 1000L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateBattery(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildHud())
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onDestroy()
    }

    private fun buildHud(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(4, 2, 3))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val header = panelLayout()
        header.addView(label("ULTIMATE", 27f, pale, true))

        clockLabel = label("--:--:--", 21f, orange, true)
        header.addView(clockLabel)

        dateLabel = label("--", 11f, pale, false)
        header.addView(dateLabel)

        header.addView(label("FRIDAY • J.A.R.V.I.S / ULTRON CORE AI", 12f, orange, false))
        header.addView(label("FRIDAY", 34f, red, true))
        root.addView(header, matchWrap())

        root.addView(space(14))

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val mode = panelLayout().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                standby = !standby
                updateMode()
            }
        }
        mode.addView(label("MODE", 11f, red, true))
        modeLabel = label("STANDBY", 18f, orange, true)
        mode.addView(modeLabel)
        mode.addView(label("TAP TO TOGGLE", 8f, pale, false))
        statusRow.addView(mode, LinearLayout.LayoutParams(0, dp(86), 1f))

        statusRow.addView(space(10))

        val health = panelLayout()
        health.addView(label("SYSTEM HEALTH", 11f, red, true))
        systemLabel = label("ONLINE", 18f, orange, true)
        health.addView(systemLabel)
        health.addView(label("HUD RUNTIME ACTIVE", 8f, pale, false))
        statusRow.addView(health, LinearLayout.LayoutParams(0, dp(86), 1f))

        root.addView(statusRow)

        root.addView(space(18))

        val core = panelLayout().apply {
            gravity = Gravity.CENTER
        }
        core.addView(label("◉", 54f, orange, true))
        core.addView(label("GEMINI CORE", 18f, pale, true))
        core.addView(label("AI ENGINE READY", 11f, orange, false))

        batteryBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            progressTintList =
                android.content.res.ColorStateList.valueOf(orange)
            layoutParams = LinearLayout.LayoutParams(dp(190), dp(5)).apply {
                topMargin = dp(14)
            }
        }
        core.addView(batteryBar)

        batteryLabel = label("BATTERY --%", 10f, pale, false)
        core.addView(batteryLabel)

        root.addView(core, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(space(18))

        val bottom = panelLayout()
        bottom.addView(label("HUD STATUS", 11f, red, true))
        voiceLabel = label("VOICE • OFFLINE     GEMINI • OFFLINE", 12f, orange, false)
        bottom.addView(voiceLabel)
        bottom.addView(label("WAKE • STANDBY     SYSTEM • NOMINAL", 11f, pale, false))
        root.addView(bottom, matchWrap())

        return root
    }

    private fun refreshLiveHud() {
        val now = Date()
        clockLabel.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
        dateLabel.text =
            SimpleDateFormat("EEE • dd MMM yyyy", Locale.getDefault()).format(now)

        updateMode()
    }

    private fun updateMode() {
        modeLabel.text = if (standby) "STANDBY" else "ACTIVE"
        voiceLabel.text =
            if (standby) {
                "VOICE • OFFLINE     GEMINI • OFFLINE"
            } else {
                "VOICE • READY       GEMINI • OFFLINE"
            }
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) {
            val percent = (level * 100 / scale).coerceIn(0, 100)
            batteryBar.progress = percent
            batteryLabel.text = "BATTERY $percent%"
        }
    }

    private fun panelLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            setColor(panel)
            setStroke(dp(1), border)
            cornerRadius = dp(16).toFloat()
        }
    }

    private fun label(
        textValue: String,
        size: Float,
        color: Int,
        bold: Boolean
    ): TextView = TextView(this).apply {
        text = textValue
        textSize = size
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        gravity = Gravity.CENTER
        includeFontPadding = true
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(3)
        }
    }

    private fun space(value: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(value, value)
    }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
