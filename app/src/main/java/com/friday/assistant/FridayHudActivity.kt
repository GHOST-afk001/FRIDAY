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
    private var groqStatusLabel: TextView? = null
    private var wakeStatusLabel: TextView? = null
    private var systemStatusLabel: TextView? = null
    private var orbLabel: TextView? = null
    private var coreBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agent = FridayAgent(applicationContext)
        setContentView(buildHud())
        runtimeSubscription = FridayRuntime.observe(::renderRuntime)
        if (intent.getBooleanExtra(EXTRA_REQUEST_CAMERA_PERMISSION, false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
        if (intent.getBooleanExtra(EXTRA_REQUEST_CALL_PERMISSION, false) &&
            ContextCompat.checkSelfPermission(this@FridayHudActivity, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL)
        }
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
        header.addView(label("FRIDAY", 30f, pale, true))
        header.addView(label("Your personal AI assistant", 13f, orange, false))
        root.addView(header, matchWrap())
        root.addView(space(12))

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mode = panelLayout()
        mode.addView(label("MODE", 10f, red, true))
        modeLabel = label("STANDBY", 16f, orange, true)
        mode.addView(modeLabel)
        statusRow.addView(mode, LinearLayout.LayoutParams(0, dp(68), 1f))
        statusRow.addView(space(8))
        val health = panelLayout()
        health.addView(label("STATUS", 10f, red, true))
        healthLabel = label("SETUP", 16f, orange, true)
        health.addView(healthLabel)
        statusRow.addView(health, LinearLayout.LayoutParams(0, dp(68), 1f))
        root.addView(statusRow)
        root.addView(space(12))

        val core = panelLayout().apply { gravity = Gravity.CENTER }
        orbLabel = label("◉", 62f, orange, true).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { startVoiceCommand() }
            contentDescription = "FRIDAY voice command. Tap to speak."
        }
        core.addView(orbLabel)
        core.addView(label("Ready when you are, Boss", 16f, pale, true))
        coreSubLabel = label("Checking brain…", 11f, orange, false)
        core.addView(coreSubLabel)
        coreBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(orange)
            layoutParams = LinearLayout.LayoutParams(dp(190), dp(5)).apply { topMargin = dp(12) }
        }
        core.addView(coreBar)
        root.addView(core, LinearLayout.LayoutParams(-1, dp(245)))
        root.addView(space(12))

        val status = panelLayout()
        status.addView(label("FRIDAY STATUS", 10f, red, true))
        voiceStatusLabel = label("VOICE • STANDBY", 11f, orange, false)
        geminiStatusLabel = label("GEMINI • OFFLINE", 11f, orange, false)
        groqStatusLabel = label("GROQ • OFFLINE", 11f, orange, false)
        wakeStatusLabel = label("WAKE • STANDBY", 11f, pale, false)
        systemStatusLabel = label("SYSTEM • NOMINAL", 11f, pale, false)
        status.addView(voiceStatusLabel)
        status.addView(geminiStatusLabel)
        status.addView(groqStatusLabel)
        status.addView(wakeStatusLabel)
        status.addView(systemStatusLabel)

        val settingsButton = android.widget.Button(this).apply {
            text = "⚙  SETTINGS"
            setOnClickListener { showSettings() }
        }
        status.addView(settingsButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        root.addView(status, matchWrap())
        return root
    }

    private fun showSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        fun addSetting(title: String, action: () -> Unit) {
            content.addView(android.widget.Button(this).apply {
                text = title
                setOnClickListener { action(); if (title != "DEVICE DIAGNOSTICS") dialog.dismiss() }
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("FRIDAY Settings")
            .setMessage("Brain, voice, permissions and Android control — all in one place.")
            .setView(content)
            .setNegativeButton("CLOSE", null)
            .create()
        addSetting("GEMINI • PRIMARY BRAIN") { showGeminiKeyDialog() }
        addSetting("GROQ • FALLBACK BRAIN") { showGroqKeyDialog() }
        addSetting("VOICE & SPEECH") { showVoiceSettings() }
        addSetting("DEVICE PERMISSIONS") { requestDevicePermissions() }
        addSetting("ACCESSIBILITY AUTOMATION") { openAccessibility() }
        addSetting("ANDROID ASSISTANT") { requestAssistantRole() }
        addSetting("DEVICE DIAGNOSTICS") { runDeviceDiagnostic() }
        dialog.show()
    }

    private fun showVoiceSettings() {
        android.app.AlertDialog.Builder(this)
            .setTitle("FRIDAY Voice")
            .setMessage("FRIDAY uses Indian-English/Hinglish recognition and a softer female-leaning Android TTS profile. For studio-quality human voice, we can add a dedicated TTS API next without changing the assistant brain.")
            .setPositiveButton("OK", null)
            .show()
    }
    private fun renderRuntime(status: RuntimeStatus) {
        runOnUiThread {
            val configured = agent.hasApiKey()
            val groqConfigured = agent.hasGroqApiKey()
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
            groqStatusLabel?.text = "GROQ • " + if (groqConfigured) "ARMED • FALLBACK" else "OFFLINE"
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

    private fun showGroqKeyDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Paste Groq API key"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(52)))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("FRIDAY Groq Fallback Brain")
            .setMessage(if (agent.hasGroqApiKey()) "Groq is armed as FRIDAY's fallback brain. Paste a new key to replace it." else "Add your Groq API key. Gemini remains primary; Groq is used automatically if Gemini fails.")
            .setView(box)
            .setPositiveButton("SAVE & TEST") { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    android.widget.Toast.makeText(this, "Please enter a Groq API key.", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val saved = agent.configureGroqApiKey(key)
                if (!saved) {
                    android.widget.Toast.makeText(this, "Groq key save failed. Please try again.", android.widget.Toast.LENGTH_LONG).show()
                    FridayRuntime.update("GROQ SAVE ERROR", "API key could not be persisted", false)
                    return@setPositiveButton
                }
                FridayRuntime.update("GROQ CHECKING", "Testing Groq fallback connection…", true)
                agent.verifyGroq { ok, detail ->
                    if (ok) {
                        FridayRuntime.update("GROQ READY", "Groq fallback connection verified", true)
                        android.widget.Toast.makeText(this, "Groq fallback connected.", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        FridayRuntime.update("GROQ ERROR", detail, false)
                        android.widget.Toast.makeText(this, "Groq saved, but connection failed: $detail", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun runDiagnostics() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val notifications = android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        val accessibilityEnabled = runCatching {
            val manager = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            manager?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    info.resolveInfo?.serviceInfo?.packageName == packageName &&
                        info.resolveInfo?.serviceInfo?.name == "com.friday.assistant.automation.FridayAccessibilityService"
                } == true
        }.getOrDefault(false)

        val assistantHeld = if (android.os.Build.VERSION.SDK_INT >= 29) runCatching {
            getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true
        }.getOrDefault(false) else false

        val power = getSystemService(android.os.PowerManager::class.java)
        val batteryOptimized = power?.isIgnoringBatteryOptimizations(packageName) == false
        val report = buildString {
            appendLine("Gemini: ${if (agent.hasApiKey()) "READY" else "MISSING"}")
            appendLine("Groq fallback: ${if (agent.hasGroqApiKey()) "ARMED" else "MISSING"}")
            appendLine("Microphone: ${if (mic) "OK" else "MISSING"}")
            appendLine("Camera/flashlight permission: ${if (camera) "OK" else "MISSING"}")
            appendLine("Contacts/WhatsApp lookup: ${if (contacts) "OK" else "MISSING"}")
            appendLine("Direct calling permission: ${if (ContextCompat.checkSelfPermission(this@FridayHudActivity, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) "OK" else "MISSING"}")
            appendLine("Notifications: ${if (notifications) "OK" else "MISSING"}")
            appendLine("Accessibility automation: ${if (accessibilityEnabled) "ON" else "OFF"}")
            appendLine("Android Assistant role: ${if (assistantHeld) "SELECTED" else "NOT SELECTED"}")
            appendLine("Battery optimization: ${if (batteryOptimized) "ACTIVE — background may be limited" else "IGNORED"}")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("FRIDAY Device Diagnostics")
            .setMessage(report)
            .setPositiveButton("OK", null)
            .setNeutralButton("ACCESSIBILITY") { _, _ -> openAccessibility() }
            .show()
    }

    private fun runDeviceDiagnostic() {
        val checks = mutableListOf<String>()
        val accessibilityEnabled = runCatching {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            enabled.split(':').any { it.equals("${packageName}/.automation.FridayAccessibilityService", true) ||
                it.endsWith("/${com.friday.assistant.automation.FridayAccessibilityService::class.java.simpleName}", true) ||
                it.contains("friday.assistant.automation.FridayAccessibilityService", true) }
        }.getOrDefault(false)
        checks += if (accessibilityEnabled) "✓ Accessibility automation" else "✗ Accessibility automation OFF"
        checks += if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "✓ Microphone" else "✗ Microphone permission"
        checks += if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) "✓ Camera / flashlight" else "✗ Camera / flashlight permission"
        checks += if (agent.hasApiKey()) "✓ Gemini API key saved" else "✗ Gemini API key missing"
        checks += if (agent.hasGroqApiKey()) "✓ Groq fallback API key saved" else "○ Groq fallback not configured"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(android.app.role.RoleManager::class.java)
            checks += if (roles?.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) == true) "✓ FRIDAY is Assistant" else "✗ FRIDAY is not selected Assistant"
        }
        checks += if (com.friday.assistant.automation.FridayAccessibilityService.isConnected()) "✓ Accessibility service connected" else "✗ Accessibility service process not connected"
        android.app.AlertDialog.Builder(this)
            .setTitle("FRIDAY Device Control Diagnostic")
            .setMessage(checks.joinToString("\n"))
            .setPositiveButton("OK", null)
            .setNeutralButton("OPEN ACCESSIBILITY") { _, _ -> openAccessibility() }
            .show()
    }

    private fun requestDevicePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.READ_CONTACTS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CALL_PHONE
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) {
            android.widget.Toast.makeText(this, "FRIDAY device permissions are already granted.", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_DEVICE_PERMISSIONS)
        }
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
        if (requestCode == REQUEST_CAMERA) {
            android.widget.Toast.makeText(
                this,
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) "Camera permission granted. Flashlight is ready." else "Camera permission denied. Flashlight cannot be controlled.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        if (requestCode == REQUEST_CALL && ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            com.friday.assistant.commands.AppLauncher.resumePendingCall(this)
        }
        if (requestCode == REQUEST_CAMERA && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            com.friday.assistant.commands.AppLauncher.resumePendingTorch(this)
        }
        if (requestCode == REQUEST_MIC || requestCode == REQUEST_DEVICE_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startHandsFreeIfReady()
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
        private const val REQUEST_DEVICE_PERMISSIONS = 7102
        private const val REQUEST_CAMERA = 7103
        private const val REQUEST_CALL = 7104
        const val EXTRA_REQUEST_CALL_PERMISSION = "request_call_permission"
        const val EXTRA_REQUEST_CAMERA_PERMISSION = "friday_request_camera_permission"
    }
}
