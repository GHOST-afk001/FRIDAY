package com.friday.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import android.graphics.drawable.GradientDrawable
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.runtime.DeviceTelemetry
import com.friday.assistant.runtime.FridayRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live native FRIDAY HUD with guarded voice/Gemini entry points. */
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
    private lateinit var coreStatus: TextView
    private lateinit var telemetryLabel: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var standby = true
    private var runtimeObserver: AutoCloseable? = null
    private var agent: FridayAgent? = null

    private val tick = object : Runnable {
        override fun run() {
            refreshLiveHud()
            handler.postDelayed(this, 1000L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateBattery(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildHud())

        runCatching { agent = FridayAgent(applicationContext) }
        runtimeObserver = FridayRuntime.observe { status ->
            runOnUiThread {
                systemLabel.text = if (status.healthy) "ONLINE" else "ATTENTION"
                coreStatus.text = status.stage.take(24)
                if (status.detail.isNotBlank()) voiceLabel.text = status.detail.take(60)
            }
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runtimeObserver?.close()
        runtimeObserver = null
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { agent?.close() }
        agent = null
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
        header.addView(actionButton("SETUP • GEMINI / ASSISTANT") {
            startActivity(Intent(this, FridayOnboardingActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(5) })
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
        health.addView(label("RUNTIME MONITOR", 8f, pale, false))
        statusRow.addView(health, LinearLayout.LayoutParams(0, dp(86), 1f))
        root.addView(statusRow)

        root.addView(space(18))

        val core = panelLayout().apply { gravity = Gravity.CENTER }
        core.addView(label("◉", 54f, orange, true))
        core.addView(label("GEMINI CORE", 18f, pale, true))
        coreStatus = label("READY • TAP MIC", 11f, orange, true)
        core.addView(coreStatus)

        batteryBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(orange)
            layoutParams = LinearLayout.LayoutParams(dp(190), dp(5)).apply { topMargin = dp(14) }
        }
        core.addView(batteryBar)
        batteryLabel = label("BATTERY --%", 10f, pale, false)
        core.addView(batteryLabel)
        telemetryLabel = label("SYSTEM • --     RAM • --", 9f, pale, false)
        core.addView(telemetryLabel)
        core.addView(space(12))
        core.addView(actionButton("◉  TALK TO FRIDAY") { startOneShotVoice() },
            LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(core, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(space(18))

        val bottom = panelLayout()
        bottom.addView(label("HUD STATUS", 11f, red, true))
        voiceLabel = label("VOICE • READY     GEMINI • CHECKING", 12f, orange, false)
        bottom.addView(voiceLabel)
        bottom.addView(label("WAKE • ASSISTANT ROLE     SYSTEM • NOMINAL", 10f, pale, false))
        bottom.addView(actionButton("ACTIVATE HANDS-FREE ASSISTANT") {
            openAssistantSettings()
        }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(6) })
        root.addView(bottom, matchWrap())

        return root
    }

    private fun refreshLiveHud() {
        val now = Date()
        clockLabel.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(now)
        dateLabel.text = SimpleDateFormat("EEE • dd MMM yyyy", Locale.getDefault()).format(now)
        val snapshot = DeviceTelemetry.snapshot(this)
        batteryBar.progress = snapshot.batteryPercent.coerceIn(0, 100)
        batteryLabel.text = "BATTERY " + snapshot.batteryPercent + if (snapshot.charging) " • CHARGING" else ""
        telemetryLabel.text = "SYSTEM • " + snapshot.network + "     RAM • " + snapshot.ramUsedGb + "/" + snapshot.ramTotalGb + " GB"
        updateMode()
        if (agent?.hasApiKey() == true && voiceLabel.text.toString().contains("CHECKING")) {
            voiceLabel.text = "VOICE • READY     GEMINI • CONNECTED"
        } else if (agent?.hasApiKey() != true && voiceLabel.text.toString().contains("CHECKING")) {
            voiceLabel.text = "VOICE • READY     GEMINI • SETUP REQUIRED"
        }
    }

    private fun updateMode() {
        modeLabel.text = if (standby) "STANDBY" else "ACTIVE"
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) {
            val percent = (level * 100 / scale).coerceIn(0, 100)
            batteryBar.progress = percent
            batteryLabel.text = "BATTERY " + percent + "%"
        }
    }

    private fun startOneShotVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, com.friday.assistant.voice.FridayHandsFreeService::class.java)
            )
            standby = false
            coreStatus.text = "LISTENING • SPEAK NOW"
        }.onFailure {
            coreStatus.text = "VOICE START FAILED"
            voiceLabel.text = "VOICE • ERROR • " + (it.message?.take(40).orEmpty())
        }
    }

    private fun openAssistantSettings() {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val roles = getSystemService(android.app.role.RoleManager::class.java)
                if (roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true) {
                    startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))
                } else startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } else startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startOneShotVoice()
        } else if (requestCode == REQUEST_MIC) {
            coreStatus.text = "MIC PERMISSION REQUIRED"
            voiceLabel.text = "VOICE • OFFLINE • ALLOW MICROPHONE ACCESS"
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

    private fun actionButton(textValue: String, action: () -> Unit): TextView =
        TextView(this).apply {
            text = textValue
            textSize = 10f
            setTextColor(pale)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                setColor(Color.rgb(26, 10, 13))
                setStroke(dp(1), orange)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { action() }
        }

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
            includeFontPadding = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) }
        }

    private fun space(value: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(value, value)
    }

    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val REQUEST_MIC = 7001 }
}
