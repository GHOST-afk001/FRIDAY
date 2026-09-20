package com.friday.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.graphics.drawable.GradientDrawable
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.runtime.RuntimeStatus

/**
 * Native FRIDAY HUD.
 *
 * The original stable View hierarchy is preserved, but the HUD is now a live control surface:
 * the orb starts a real voice command, Gemini/voice state is reflected from FridayRuntime, and
 * the screen no longer pretends that disconnected services are online.
 */
class FridayHudActivity : ComponentActivity() {

    private val orange = Color.rgb(255, 137, 48)
    private val red = Color.rgb(229, 72, 78)
    private val pale = Color.rgb(244, 220, 221)
    private val panel = Color.rgb(15, 8, 10)
    private val border = Color.rgb(107, 31, 37)

    private lateinit var agent: FridayAgent
    private var runtimeSubscription: AutoCloseable? = null
    private var modeLabel: TextView? = null
    private var healthLabel: TextView? = null
    private var coreLabel: TextView? = null
    private var coreSubLabel: TextView? = null
    private var voiceStatusLabel: TextView? = null
    private var geminiStatusLabel: TextView? = null
    private var wakeStatusLabel: TextView? = null
    private var systemStatusLabel: TextView? = null
    private var orbLabel: TextView? = null
    private var coreBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agent = FridayAgent(applicationContext)
        setContentView(buildHud())
        runtimeSubscription = FridayRuntime.observe(::renderRuntime)
        renderRuntime(FridayRuntime.status)
        startHandsFreeIfReady()
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
        header.addView(label("FRIDAY • J.A.R.V.I.S / ULTRON CORE AI", 12f, orange, false))
        header.addView(label("FRIDAY", 34f, red, true))
        root.addView(header, matchWrap())

        root.addView(space(14))

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val mode = panelLayout()
        mode.addView(label("MODE", 11f, red, true))
        modeLabel = label("STANDBY", 18f, orange, true)
        mode.addView(modeLabel)
        statusRow.addView(mode, LinearLayout.LayoutParams(0, dp(74), 1f))

        statusRow.addView(space(10))

        val health = panelLayout()
        health.addView(label("SYSTEM HEALTH", 11f, red, true))
        healthLabel = label("ONLINE", 18f, orange, true)
        health.addView(healthLabel)
        statusRow.addView(health, LinearLayout.LayoutParams(0, dp(74), 1f))

        root.addView(statusRow)
        root.addView(space(18))

        val core = panelLayout().apply {
            gravity = Gravity.CENTER
        }

        orbLabel = label("◉", 54f, orange, true).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { startVoiceCommand() }
            setOnLongClickListener {
                openAccessibility()
                true
            }
            contentDescription = "FRIDAY voice command. Tap to speak."
        }
        core.addView(orbLabel)
        core.addView(label("GEMINI CORE", 18f, pale, true))
        coreSubLabel = label("CHECKING BRAIN", 11f, orange, false)
        core.addView(coreSubLabel)

        coreBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(orange)
            layoutParams = LinearLayout.LayoutParams(dp(190), dp(5)).apply {
                topMargin = dp(14)
            }
        }
        core.addView(coreBar)

        root.addView(core, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(space(18))

        val bottom = panelLayout()
        bottom.addView(label("HUD STATUS", 11f, red, true))
        voiceStatusLabel = label("VOICE • OFFLINE", 12f, orange, false)
        geminiStatusLabel = label("GEMINI • OFFLINE", 12f, orange, false)
        wakeStatusLabel = label("WAKE • STANDBY", 11f, pale, false)
        systemStatusLabel = label("SYSTEM • NOMINAL", 11f, pale, false)
        bottom.addView(voiceStatusLabel)
        bottom.addView(geminiStatusLabel)
        bottom.addView(wakeStatusLabel)
        bottom.addView(systemStatusLabel)

        val keyButton = android.widget.Button(this).apply {
            text = "GEMINI API KEY / BRAIN SETTINGS"
            setOnClickListener { showGeminiKeyDialog() }
        }
        bottom.addView(keyButton, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })

        val assistantButton = android.widget.Button(this).apply {
            text = "SELECT FRIDAY AS ANDROID ASSISTANT"
            setOnClickListener { requestAssistantRole() }
        }
        bottom.addView(assistantButton, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })

        root.addView(bottom, matchWrap())

        return root
    }

    private fun renderRuntime(status: RuntimeStatus) {
        runOnUiThread {
            val configured = agent.hasApiKey()
            val active = status.stage in setOf("LISTENING", "HEARD", "UNDERSTANDING", "AI THINKING", "ANDROID TOOL", "EXECUTING", "SPEAKING")
            val healthy = status.healthy

            modeLabel?.text = when {
                active -> status.stage
                configured -> "READY"
                else -> "STANDBY"
            }
            healthLabel?.text = when {
                !healthy -> "ATTENTION"
                configured -> "ONLINE"
                else -> "SETUP"
            }

            coreLabel?.text = coreLabel?.text ?: "GEMINI CORE"
            coreSubLabel?.text = if (configured) {
                when {
                    status.stage == "AI THINKING" -> "AI THINKING"
                    status.stage == "BRAIN ERROR" -> "CONNECTION ERROR"
                    else -> "AI ENGINE READY"
                }
            } else "API KEY REQUIRED"
            coreBar?.progress = when {
                !configured -> 0
                status.stage == "AI THINKING" -> 88
                status.stage == "SPEAKING" -> 100
                active -> 72
                else -> 55
            }

            voiceStatusLabel?.text = "VOICE • " + if (active || status.stage == "ASSISTANT READY" || status.stage == "ASSISTANT SERVICE") "ONLINE" else "STANDBY"
            geminiStatusLabel?.text = "GEMINI • " + if (configured) "ONLINE" else "OFFLINE"
            wakeStatusLabel?.text = "WAKE • " + when {
                status.stage == "LISTENING" || status.stage == "ASSISTANT READY" -> "READY"
                status.stage == "WAKE ACCEPTED" || active -> "ACTIVE"
                else -> "STANDBY"
            }
            systemStatusLabel?.text = "SYSTEM • " + if (healthy) "NOMINAL" else status.stage
            orbLabel?.text = if (active) "◉" else "◉"
            orbLabel?.setTextColor(if (healthy) orange else red)
        }
    }

    private fun showGeminiKeyDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Paste Gemini API key"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("FRIDAY Gemini Brain")
            .setMessage(if (agent.hasApiKey()) "Gemini is already connected. Paste a new key to replace it." else "Add your Gemini API key to enable FRIDAY's AI brain.")
            .setView(box)
            .setPositiveButton("SAVE & CONNECT") { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    android.widget.Toast.makeText(this, "Please enter a Gemini API key.", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val saved = agent.configureApiKey(key)
                if (!saved) {
                    android.widget.Toast.makeText(this, "Gemini key save failed. Please try again.", android.widget.Toast.LENGTH_LONG).show()
                    FridayRuntime.update("GEMINI SAVE ERROR", "API key could not be persisted", false)
                    return@setPositiveButton
                }
                FridayRuntime.update("GEMINI CHECKING", "Testing Gemini connection…", true)
                android.widget.Toast.makeText(this, "Gemini key saved. Testing connection…", android.widget.Toast.LENGTH_SHORT).show()
                agent.verifyGemini { ok, detail ->
                    if (ok) {
                        FridayRuntime.update("GEMINI READY", "Gemini connection verified", true)
                        android.widget.Toast.makeText(this, "Gemini connected. FRIDAY is ready.", android.widget.Toast.LENGTH_SHORT).show()
                        startHandsFreeIfReady()
                    } else {
                        FridayRuntime.update("GEMINI ERROR", detail, false)
                        android.widget.Toast.makeText(this, "Gemini saved, but connection failed: $detail", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun startHandsFreeIfReady() {
        if (!agent.hasApiKey()) {
            FridayRuntime.update("GEMINI SETUP", "Add your Gemini API key to activate FRIDAY", true)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        runCatching {
            com.friday.assistant.voice.FridayAlwaysOnService.start(this)
            FridayRuntime.update("WAKE LISTENING", "Hands-free active • say Hey Friday", true)
        }.onFailure {
            FridayRuntime.update("VOICE ERROR", it.message ?: "Could not start hands-free service", false)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startHandsFreeIfReady()
        }
    }

    private fun startVoiceCommand() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        FridayRuntime.update("LISTENING", "Starting FRIDAY voice command", true)
        val intent = Intent(this, com.friday.assistant.voice.FridayHandsFreeService::class.java)
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure {
                FridayRuntime.update("VOICE ERROR", it.message ?: "Could not start FRIDAY voice service", false)
            }
    }

    private fun requestAssistantRole() {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            android.widget.Toast.makeText(this, "Android Assistant role needs Android 10+.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            val roles = getSystemService(android.app.role.RoleManager::class.java)
            if (roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true) {
                startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))
            } else {
                android.widget.Toast.makeText(this, "This phone does not expose the Android Assistant role.", android.widget.Toast.LENGTH_LONG).show()
            }
        }.onFailure {
            android.widget.Toast.makeText(this, "Android Assistant selection failed: ${it.message ?: "unknown error"}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun openAccessibility() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    override fun onDestroy() {
        runtimeSubscription?.close()
        runtimeSubscription = null
        runCatching { agent.close() }
        super.onDestroy()
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

    companion object {
        private const val REQUEST_MIC = 7101
    }
}
