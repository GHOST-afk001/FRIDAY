package com.friday.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.ai.SecureApiKeyStore
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.runtime.FridayStateFlow
import com.friday.assistant.voice.TTSManager
import com.friday.assistant.voice.VoiceManager

/** Crash-proof native HUD. Direct LISTEN NOW bypasses Android Assistant/wake-word for diagnostics. */
class FridayHudActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var orb: TextView
    private lateinit var keyInput: EditText
    private lateinit var connectButton: Button
    private lateinit var listenButton: Button
    private lateinit var assistantButton: Button
    private lateinit var automationButton: Button
    private lateinit var tts: TTSManager
    private var voice: VoiceManager? = null
    private var agent: FridayAgent? = null

    private val cyan = Color.rgb(53, 232, 255)
    private val green = Color.rgb(76, 255, 154)
    private val red = Color.rgb(255, 82, 102)
    private val panel = Color.rgb(7, 13, 22)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(2, 4, 10)
        window.navigationBarColor = Color.rgb(2, 4, 10)
        tts = TTSManager(applicationContext) { setStatus("TTS ERROR", "Android text-to-speech is unavailable", false) }
        buildHud()
        requestCorePermissions()
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        requestCorePermissions()
        refreshState()
    }

    private fun buildHud() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.rgb(2, 4, 10))
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -1))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val title = TextView(this).apply {
            text = "FRIDAY"
            textColor = Color.WHITE
            textSize = 30f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.18f
        }
        content.addView(title, lp(-1, -2, 0, 0, 0, 4))

        val subtitle = TextView(this).apply {
            text = "ULTRON-INSPIRED PERSONAL INTELLIGENCE"
            textColor = Color.rgb(90, 115, 126)
            textSize = 8f
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        }
        content.addView(subtitle, lp(-1, -2, 0, 0, 0, 12))

        orb = TextView(this).apply {
            text = "◉"
            textColor = cyan
            textSize = 92f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(orb, lp(-1, dp(150), 0, 0, 0, 4))

        status = TextView(this).apply {
            text = "FRIDAY CORE • STARTING"
            textColor = cyan
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        content.addView(status, lp(-1, -2, 0, 0, 0, 4))

        detail = TextView(this).apply {
            text = "Initializing voice console..."
            textColor = Color.rgb(190, 215, 224)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(8))
        }
        content.addView(detail, lp(-1, -2, 0, 0, 0, 10))

        val bridge = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(panel, Color.rgb(20, 49, 63), 16)
        }
        content.addView(bridge, lp(-1, -2, 0, 0, 0, 10))

        val keyLabel = TextView(this).apply {
            text = "GEMINI BRAIN"
            textColor = cyan
            textSize = 9f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        bridge.addView(keyLabel, lp(-1, -2, 0, 0, 0, 6))

        keyInput = EditText(this).apply {
            hint = "Paste Gemini API key (stored encrypted)"
            hintTextColor = Color.rgb(90, 115, 126)
            setTextColor(Color.WHITE)
            textSize = 12f
            setSingleLine(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.rgb(12, 21, 31), Color.rgb(35, 70, 82), 12)
        }
        bridge.addView(keyInput, lp(-1, -2, 0, 0, 0, 7))

        connectButton = button("CONNECT GEMINI BRAIN")
        bridge.addView(connectButton, lp(-1, -2, 0, 0, 0, 7))
        connectButton.setOnClickListener { connectGemini() }

        listenButton = button("LISTEN NOW — TEST MICROPHONE")
        content.addView(listenButton, lp(-1, -2, 0, 0, 0, 7))
        listenButton.setOnClickListener { startDirectListening() }

        val testVoice = button("TEST VOICE OUTPUT")
        content.addView(testVoice, lp(-1, -2, 0, 0, 0, 7))
        testVoice.setOnClickListener { tts.speak("Hello Boss. FRIDAY voice output is working.") }

        assistantButton = button("ENABLE ANDROID ASSISTANT")
        content.addView(assistantButton, lp(-1, -2, 0, 0, 0, 7))
        assistantButton.setOnClickListener { openAssistantRole() }

        automationButton = button("ENABLE AUTOMATION")
        content.addView(automationButton, lp(-1, -2, 0, 0, 0, 7))
        automationButton.setOnClickListener { openAccessibility() }

        val footer = TextView(this).apply {
            text = "DIRECT VOICE PATH: MICROPHONE → SPEECH → COMMAND/GEMINI → ACTION → TTS\nWAKE PATH: ANDROID ASSISTANT → HEY FRIDAY → SESSION"
            textColor = Color.rgb(76, 105, 116)
            textSize = 8f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(4))
        }
        content.addView(footer, lp(-1, -2, 0, 0, 0, 4))
    }

    private fun connectGemini() {
        val clean = keyInput.text?.toString()?.trim().orEmpty()
        if (clean.isBlank()) { setStatus("GEMINI SETUP", "Paste an API key first", false); return }
        val saved = runCatching { SecureApiKeyStore(applicationContext).save(clean) }.getOrDefault(false)
        if (!saved) {
            setStatus("GEMINI ERROR", "Secure key storage failed on this phone", false)
            return
        }
        keyInput.setText("")
        setStatus("GEMINI CONNECTED", "API key stored securely • direct voice path ready", true)
        Toast.makeText(this, "Gemini connected", Toast.LENGTH_SHORT).show()
        ensureAgent()
    }

    private fun startDirectListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestCorePermissions()
            setStatus("MIC PERMISSION", "Allow microphone access, then press LISTEN NOW again", false)
            return
        }
        ensureAgent()
        setStatus("LISTENING", "Speak now — this bypasses the Hey Friday wake path", true)
        listenButton.isEnabled = false
        val manager = VoiceManager(this, object : VoiceManager.Listener {
            override fun onListening() { setStatus("LISTENING", "Microphone is receiving speech", true) }
            override fun onAmplitude(value: Float) {
                orb.text = if (value > 0.08f) "◉" else "○"
                orb.textColor = if (value > 0.08f) green else cyan
            }
            override fun onResult(text: String) {
                listenButton.isEnabled = true
                if (text.isBlank()) { setStatus("NO SPEECH", "Nothing was recognized. Try again.", false); return }
                setStatus("UNDERSTANDING", "Heard: $text", true)
                ensureAgent().handle(text) { answer, _ ->
                    setStatus("RESPONSE READY", answer, true)
                    tts.speak(answer) { setStatus("READY", "Direct voice path ready • press LISTEN NOW again", true) }
                }
            }
            override fun onError(message: String) {
                listenButton.isEnabled = true
                setStatus("VOICE ERROR", message, false)
            }
        })
        voice?.destroy()
        voice = manager
        manager.start()
    }

    private fun ensureAgent(): FridayAgent {
        if (agent == null) agent = FridayAgent(applicationContext)
        return agent!!
    }

    private fun requestCorePermissions() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 7001)
    }

    private fun openAccessibility() = runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

    private fun openAssistantRole() = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(android.app.role.RoleManager::class.java)
            if (roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true) {
                startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))
            } else Toast.makeText(this, "Android Assistant role is unavailable.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshState() {
        val keyReady = !runCatching { SecureApiKeyStore(applicationContext).read() }.getOrNull().isNullOrBlank()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val assistant = runCatching { VoiceInteractionService.isActiveService(this, ComponentName(this, com.friday.assistant.voice.FridayVoiceInteractionService::class.java)) }.getOrDefault(false)
        connectButton.text = if (keyReady) "GEMINI CONNECTED" else "CONNECT GEMINI BRAIN"
        connectButton.isEnabled = !keyReady
        assistantButton.text = if (assistant) "ANDROID ASSISTANT ON" else "ENABLE ANDROID ASSISTANT"
        automationButton.text = if (isAccessibilityEnabled()) "AUTOMATION ON" else "ENABLE AUTOMATION"
        listenButton.isEnabled = mic
        if (mic) setStatus("READY", if (assistant) "Android Assistant active • direct LISTEN NOW is available" else "Mic ready • press LISTEN NOW to test speech", true)
        else setStatus("MIC REQUIRED", "Allow microphone permission to use voice", false)
    }

    private fun isAccessibilityEnabled(): Boolean = runCatching {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        enabled.split(':').any { it.equals(ComponentName(this, com.friday.assistant.automation.FridayAccessibilityService::class.java).flattenToString(), true) }
    }.getOrDefault(false)

    private fun setStatus(stage: String, message: String, healthy: Boolean) {
        FridayRuntime.update(stage, message, healthy)
        runOnUiThread {
            status.text = stage
            status.setTextColor(if (healthy) cyan else red)
            detail.text = message
            orb.textColor = if (healthy) cyan else red
        }
    }

    private fun button(text: String) = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 10f
        isAllCaps = false
        background = rounded(Color.rgb(10, 24, 34), Color.rgb(24, 69, 82), 12)
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radius).toFloat()
    }

    private fun lp(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        voice?.destroy()
        voice = null
        agent?.close()
        agent = null
        tts.shutdown()
        super.onDestroy()
    }
}
