package com.friday.assistant

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import android.widget.Toast
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.voice.TTSManager

/**
 * Functional FRIDAY HUD.
 *
 * The previous HUD was deliberately a static visual placeholder and did not start
 * voice/AI at all. The real runtime is owned by VoiceInteractionService; this screen
 * now reflects the actual assistant/brain configuration and provides a safe manual
 * voice test when the system assistant bridge is active.
 */
class FridayHudActivity : androidx.activity.ComponentActivity() {
    private lateinit var agent: FridayAgent
    private lateinit var tts: TTSManager
    private lateinit var assistantStatus: TextView
    private lateinit var brainStatus: TextView
    private lateinit var wakeStatus: TextView

    private val red = Color.rgb(229, 72, 78)
    private val orange = Color.rgb(255, 137, 48)
    private val pale = Color.rgb(244, 220, 221)
    private val panel = Color.rgb(15, 8, 10)
    private val border = Color.rgb(107, 31, 37)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agent = FridayAgent(applicationContext)
        tts = TTSManager(applicationContext) {
            Toast.makeText(this, "Android TTS unavailable", Toast.LENGTH_SHORT).show()
        }
        setContentView(buildHud())
        refreshStatus()
        if (isAssistantSelected()) {
            tts.speak("FRIDAY online, Boss.")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::agent.isInitialized) refreshStatus()
    }

    private fun isAssistantSelected(): Boolean = runCatching {
        VoiceInteractionService.isActiveService(
            this,
            ComponentName(this, com.friday.assistant.voice.FridayVoiceInteractionService::class.java)
        )
    }.getOrDefault(false)

    private fun refreshStatus() {
        val assistant = isAssistantSelected()
        val brain = agent.hasApiKey() || agent.hasGroqKey() || agent.hasOpenRouterKey()
        assistantStatus.text = if (assistant) "ASSISTANT • ACTIVE" else "ASSISTANT • NOT SELECTED"
        assistantStatus.setTextColor(if (assistant) Color.rgb(76, 255, 154) else Color.rgb(255, 183, 77))
        brainStatus.text = if (brain) "AI BRAIN • CONFIGURED" else "AI BRAIN • OFFLINE"
        brainStatus.setTextColor(if (brain) Color.rgb(76, 255, 154) else Color.rgb(255, 183, 77))
        wakeStatus.text = if (assistant) "WAKE BRIDGE • READY — SAY “FRIDAY”" else "WAKE BRIDGE • BLOCKED — SELECT FRIDAY AS ASSISTANT"
        wakeStatus.setTextColor(if (assistant) Color.rgb(76, 255, 154) else Color.rgb(255, 183, 77))
    }

    @SuppressLint("NewApi")
    private fun openAssistantSettings() {
        runCatching {
            val roles = getSystemService(android.app.role.RoleManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 29 &&
                roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true
            ) {
                startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))
            } else {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
        }.onFailure {
            Toast.makeText(this, "Assistant settings unavailable on this device", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildHud(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(4, 2, 3))
        }

        val header = panelLayout()
        header.addView(label("FRIDAY", 34f, red, true))
        header.addView(label("J.A.R.V.I.S / CORE AI", 11f, orange, false))
        root.addView(header, matchWrap())

        root.addView(space(12))

        val status = panelLayout()
        status.addView(label("SYSTEM STATUS", 11f, red, true))
        assistantStatus = label("", 14f, pale, true)
        brainStatus = label("", 14f, pale, true)
        wakeStatus = label("", 12f, pale, false)
        status.addView(assistantStatus)
        status.addView(brainStatus)
        status.addView(wakeStatus)
        root.addView(status, matchWrap())

        root.addView(space(12))

        val core = panelLayout().apply { gravity = Gravity.CENTER }
        core.addView(label("◉", 58f, orange, true))
        core.addView(label("VOICE CORE", 18f, pale, true))
        core.addView(label("HANDS-FREE WAKE ENGINE", 10f, orange, false))
        root.addView(core, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(space(12))

        val test = Button(this).apply {
            text = "TEST VOICE COMMAND"
            setOnClickListener {
                if (!isAssistantSelected()) {
                    Toast.makeText(this@FridayHudActivity, "Pehle FRIDAY ko Android Assistant select kijiye.", Toast.LENGTH_LONG).show()
                    openAssistantSettings()
                } else {
                    runCatching {
                        startService(Intent(this@FridayHudActivity, com.friday.assistant.voice.FridayHandsFreeService::class.java))
                    }.onFailure {
                        Toast.makeText(this@FridayHudActivity, "Voice test start nahi hua.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        root.addView(test, LinearLayout.LayoutParams(-1, dp(52)))

        root.addView(space(8))

        val setup = Button(this).apply {
            text = "OPEN ASSISTANT SETTINGS"
            setOnClickListener { openAssistantSettings() }
        }
        root.addView(setup, LinearLayout.LayoutParams(-1, dp(48)))

        return root
    }

    private fun panelLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(panel)
            setStroke(dp(1), border)
            cornerRadius = dp(16).toFloat()
        }
    }

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(3))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

    private fun space(value: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(value))
    }

    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::agent.isInitialized) agent.close()
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }
}
