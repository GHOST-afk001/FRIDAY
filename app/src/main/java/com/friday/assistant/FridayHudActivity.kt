package com.friday.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.activity.ComponentActivity
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.ai.SecureApiKeyStore
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.runtime.FridayStateFlow
import com.friday.assistant.voice.TTSManager
import com.friday.assistant.voice.VoiceManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** FRIDAY HUD. While the HUD is open, speech is hands-free: no tap is required for each command. */
class FridayHudActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var transcript: TextView
    private lateinit var orb: TextView
    private lateinit var meter: TextView
    private lateinit var keyInput: EditText
    private lateinit var connectButton: Button
    private lateinit var voiceButton: Button
    private lateinit var assistantButton: Button
    private lateinit var automationButton: Button
    private lateinit var tts: TTSManager
    private var voice: VoiceManager? = null
    private var agent: FridayAgent? = null
    private var handsFree = false
    private var speaking = false
    private var destroyed = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val cyan = Color.rgb(50, 235, 255)
    private val green = Color.rgb(72, 255, 158)
    private val red = Color.rgb(255, 75, 100)
    private val muted = Color.rgb(104, 135, 148)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(1, 3, 8)
        window.navigationBarColor = Color.rgb(1, 3, 8)
        tts = TTSManager(applicationContext) { setState("TTS ERROR", "Android speech output is unavailable", false) }
        buildHud()
        observeRuntime()
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        destroyed = false
        requestPermissionsIfNeeded()
        refreshControls()
        handler.postDelayed({ if (!destroyed) beginHandsFreeIfReady() }, 700L)
    }

    private fun buildHud() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(Color.rgb(1, 3, 8))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -1))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val title = TextView(this).apply {
            text = "FRIDAY"
            setTextColor(Color.WHITE); textSize = 29f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = .22f
        }
        content.addView(title, lp(-1, -2, 0, 2, 0, 1))
        val sub = TextView(this).apply {
            text = "PERSONAL INTELLIGENCE • HANDS-FREE"
            setTextColor(muted); textSize = 8f; gravity = Gravity.CENTER; letterSpacing = .12f
        }
        content.addView(sub, lp(-1, -2, 0, 0, 0, 5))

        orb = TextView(this).apply { text = "◉"; setTextColor(cyan); textSize = 88f; gravity = Gravity.CENTER }
        content.addView(orb, lp(-1, dp(122), 0, 0, 0, 0))
        status = TextView(this).apply {
            text = "FRIDAY • STARTING"; setTextColor(cyan); textSize = 12f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = .08f
        }
        content.addView(status, lp(-1, -2, 0, 2, 0, 2))
        detail = TextView(this).apply { text = "Preparing microphone..."; setTextColor(Color.LTGRAY); textSize = 12f; gravity = Gravity.CENTER }
        content.addView(detail, lp(-1, -2, 0, 0, 0, 4))
        meter = TextView(this).apply { text = "MIC  ░░░░░░░░░░  0%"; setTextColor(muted); textSize = 9f; gravity = Gravity.CENTER }
        content.addView(meter, lp(-1, -2, 0, 0, 0, 8))

        val heardLabel = TextView(this).apply { text = "LAST HEARD"; setTextColor(muted); textSize = 8f; letterSpacing = .12f }
        content.addView(heardLabel, lp(-1, -2, 0, 0, 0, 2))
        transcript = TextView(this).apply {
            text = "Waiting for your voice…"
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(Color.rgb(7, 14, 23), Color.rgb(23, 61, 74), 14)
        }
        content.addView(transcript, lp(-1, -2, 0, 0, 0, 8))

        val brain = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9)); background = rounded(Color.rgb(6, 12, 20), Color.rgb(18, 48, 61), 14) }
        content.addView(brain, lp(-1, -2, 0, 0, 0, 7))
        brain.addView(TextView(this).apply { text = "GEMINI BRAIN"; setTextColor(cyan); textSize = 8f; typeface = Typeface.DEFAULT_BOLD }, lp(-1, -2, 0, 0, 0, 4))
        keyInput = EditText(this).apply {
            hint = "Gemini API key (first setup only)"; setHintTextColor(muted); setTextColor(Color.WHITE); textSize = 11f
            setSingleLine(true); setPadding(dp(9), dp(6), dp(9), dp(6)); background = rounded(Color.rgb(10, 19, 29), Color.rgb(28, 67, 81), 10)
        }
        brain.addView(keyInput, lp(-1, -2, 0, 0, 0, 5))
        connectButton = button("CONNECT GEMINI")
        brain.addView(connectButton, lp(-1, -2, 0, 0, 0, 0))
        connectButton.setOnClickListener { connectGemini() }

        voiceButton = button("START HANDS-FREE")
        content.addView(voiceButton, lp(-1, -2, 0, 0, 0, 6))
        voiceButton.setOnClickListener { if (handsFree) stopHandsFree() else beginHandsFreeIfReady(true) }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        assistantButton = button("ASSISTANT")
        automationButton = button("AUTOMATION")
        row.addView(assistantButton, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(4), 0) })
        row.addView(automationButton, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), 0, 0, 0) })
        content.addView(row, lp(-1, -2, 0, 0, 0, 4))
        assistantButton.setOnClickListener { openAssistantRole() }
        automationButton.setOnClickListener { openAccessibility() }

        content.addView(TextView(this).apply {
            text = "VOICE → UNDERSTAND → COMMAND / GEMINI → ACTION → SPEAK\nNo tap needed while hands-free mode is active."
            setTextColor(Color.rgb(66, 92, 104)); textSize = 8f; gravity = Gravity.CENTER; setPadding(dp(5), dp(8), dp(5), 0)
        }, lp(-1, -2, 0, 0, 0, 3))
        setContentView(root)
    }

    private fun observeRuntime() {
        scope.launch {
            FridayStateFlow.state.collect { s ->
                if (destroyed) return@collect
                status.text = s.stage
                detail.text = s.detail
                status.setTextColor(if (s.healthy) cyan else red)
                if (!speaking) orb.setTextColor(if (s.healthy) cyan else red)
                val a = (s.audioAmplitude.coerceIn(0f, 1f) * 100).toInt()
                meter.text = "MIC  ${"█".repeat((a / 10).coerceIn(0, 10))}${"░".repeat(10 - (a / 10).coerceIn(0, 10))}  $a%"
                if (!speaking) orb.text = if (a > 8) "◉" else "○"
            }
        }
    }

    private fun beginHandsFreeIfReady(manual: Boolean = false) {
        if (destroyed || speaking || handsFree) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (manual) setState("MIC PERMISSION", "Allow microphone access, then FRIDAY will listen automatically", false)
            requestPermissionsIfNeeded(); return
        }
        handsFree = true
        voiceButton.text = "STOP HANDS-FREE"
        startRecognition()
    }

    private fun startRecognition() {
        if (destroyed || !handsFree || speaking) return
        voice?.destroy()
        voice = VoiceManager(this, object : VoiceManager.Listener {
            override fun onListening() { setState("LISTENING", "Speak naturally — FRIDAY is listening", true) }
            override fun onAmplitude(value: Float) {
                val a = (value.coerceIn(0f, 1f) * 100).toInt()
                meter.text = "MIC  ${"█".repeat((a / 10).coerceIn(0, 10))}${"░".repeat(10 - (a / 10).coerceIn(0, 10))}  $a%"
                orb.text = if (a > 7) "◉" else "○"; orb.setTextColor(if (a > 7) green else cyan)
            }
            override fun onResult(text: String) {
                if (!handsFree || destroyed) return
                if (text.isBlank()) { scheduleRecognition(); return }
                transcript.text = text
                setState("HEARD", text, true)
                ensureAgent().handle(text) { answer, _ ->
                    if (destroyed || !handsFree) return@handle
                    speaking = true
                    setState("SPEAKING", answer, true)
                    tts.speak(answer) {
                        speaking = false
                        if (handsFree && !destroyed) handler.postDelayed({ startRecognition() }, 250L)
                    }
                }
            }
            override fun onError(message: String) {
                if (!handsFree || destroyed) return
                setState("VOICE ERROR", message, false)
                scheduleRecognition()
            }
        })
        voice?.start()
    }

    private fun scheduleRecognition() {
        if (!handsFree || destroyed || speaking) return
        voice?.destroy(); voice = null
        handler.postDelayed({ if (handsFree && !destroyed && !speaking) startRecognition() }, 900L)
    }

    private fun stopHandsFree() {
        handsFree = false
        speaking = false
        handler.removeCallbacksAndMessages(null)
        voice?.destroy(); voice = null
        voiceButton.text = "START HANDS-FREE"
        setState("IDLE", "Hands-free paused", true)
    }

    private fun connectGemini() {
        val clean = keyInput.text?.toString()?.trim().orEmpty()
        if (clean.isBlank()) { setState("GEMINI SETUP", "Paste the Gemini API key first", false); return }
        val saved = runCatching { SecureApiKeyStore(applicationContext).save(clean) }.getOrDefault(false)
        if (!saved) { setState("GEMINI ERROR", "Secure key storage failed on this phone", false); return }
        keyInput.setText("")
        setState("GEMINI CONNECTED", "Cloud brain ready", true)
        ensureAgent()
    }

    private fun ensureAgent(): FridayAgent { if (agent == null) agent = FridayAgent(applicationContext); return agent!! }

    private fun requestPermissionsIfNeeded() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 7001)
    }

    private fun refreshControls() {
        val keyReady = !runCatching { SecureApiKeyStore(applicationContext).read() }.getOrNull().isNullOrBlank()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val assistant = runCatching { VoiceInteractionService.isActiveService(this, ComponentName(this, com.friday.assistant.voice.FridayVoiceInteractionService::class.java)) }.getOrDefault(false)
        connectButton.text = if (keyReady) "GEMINI CONNECTED" else "CONNECT GEMINI"
        connectButton.isEnabled = !keyReady
        assistantButton.text = if (assistant) "ASSISTANT ON" else "ASSISTANT SETUP"
        automationButton.text = if (isAccessibilityEnabled()) "AUTOMATION ON" else "AUTOMATION SETUP"
        voiceButton.text = if (handsFree) "STOP HANDS-FREE" else "START HANDS-FREE"
        if (!mic) setState("MIC PERMISSION", "Microphone access is required", false)
    }

    private fun openAccessibility() = runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    private fun openAssistantRole() = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(android.app.role.RoleManager::class.java)
            if (roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true) startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))
        }
    }
    private fun isAccessibilityEnabled() = runCatching {
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any {
            it.equals(ComponentName(this, com.friday.assistant.automation.FridayAccessibilityService::class.java).flattenToString(), true)
        }
    }.getOrDefault(false)
    private fun setState(stage: String, message: String, healthy: Boolean) { FridayRuntime.update(stage, message, healthy) }
    private fun button(text: String) = Button(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 10f; isAllCaps = false
        background = rounded(Color.rgb(8, 21, 31), Color.rgb(24, 70, 84), 11); setPadding(dp(8), dp(6), dp(8), dp(6))
    }
    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply { setColor(fill); setStroke(dp(1), stroke); cornerRadius = dp(radius).toFloat() }
    private fun lp(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int) = LinearLayout.LayoutParams(w, h).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations) stopHandsFree()
    }
    override fun onDestroy() {
        destroyed = true
        stopHandsFree()
        scope.cancel(); agent?.close(); agent = null; tts.shutdown(); super.onDestroy()
    }
}
