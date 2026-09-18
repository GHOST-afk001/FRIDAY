package com.friday.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.friday.assistant.ai.GeminiProvider
import com.friday.assistant.ai.SecureApiKeyStore
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.runtime.FridayStateFlow
import com.friday.assistant.voice.FridayHandsFreeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stable FRIDAY launcher. Keeps the proven reference HUD without auto-starting a microphone FGS. */
class FridayHudActivity : ComponentActivity() {
    private var hud: FridayReferenceHudView? = null
    private val scope = MainScope()
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveHud()
        setContentView(startupView())
        window.decorView.post { if (!destroyed) attachHudSafely() }
    }

    private fun startupView(): TextView = TextView(this).apply {
        setBackgroundColor(Color.rgb(4, 2, 3))
        setTextColor(Color.rgb(255, 137, 48))
        textSize = 16f
        text = "FRIDAY"
        gravity = Gravity.CENTER
    }

    private fun attachHudSafely() {
        try {
            val view = FridayReferenceHudView(this)
            view.actions = object : FridayReferenceHudView.Actions {
                override fun onGeminiTap() { showGeminiDialog() }
                override fun onAssistantTap() { openAssistantRole() }
                override fun onAutomationTap() { openAccessibility() }
                // This is an explicit, visible-user fallback. It is never started from onResume.
                override fun onOrbTap() { startManualVoiceFallback() }
            }
            hud = view
            setContentView(view)
            scope.launch {
                FridayStateFlow.state.collect { value ->
                    if (!destroyed) hud?.render(value)
                }
            }
            FridayRuntime.update("READY", "FRIDAY HUD ready", true)
            requestMicrophoneIfNeeded()
        } catch (_: Throwable) {
            FridayRuntime.update("HUD ERROR", "FRIDAY HUD initialization failed safely", false)
            setContentView(startupView().apply { text = "FRIDAY\n\nHUD initialization failed safely." })
        }
    }

    override fun onResume() {
        super.onResume()
        destroyed = false
        enterImmersiveHud()
        // Deliberately no microphone-service startup here. Android 14+ treats microphone
        // foreground services as while-in-use and can throw when they are created from a
        // background lifecycle. The selected VoiceInteractionService owns the always-on wake path.
    }

    private fun enterImmersiveHud() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }
    }

    private fun requestMicrophoneIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        } else {
            FridayRuntime.update("MIC READY", "Microphone permission is available for Hey Friday", true)
        }
    }

    private fun startManualVoiceFallback() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophoneIfNeeded()
            return
        }
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, FridayHandsFreeService::class.java))
        }.onSuccess {
            FridayRuntime.update("VOICE FALLBACK", "Listening for one command…", true)
        }.onFailure {
            FridayRuntime.update("VOICE ERROR", "Android refused the microphone fallback", false)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                FridayRuntime.update("MIC READY", "Microphone permission granted; Hey Friday can listen", true)
            } else {
                FridayRuntime.update("MIC BLOCKED", "Microphone permission is required for hands-free voice", false)
            }
        }
    }

    private fun showGeminiDialog() {
        val input = EditText(this).apply {
            hint = "Paste Gemini API key"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(24, 16, 24, 16)
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding(22, 0, 22, 0)
            addView(input, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("GEMINI CORE")
            .setMessage("Paste your Gemini API key. CONNECT verifies it with Gemini before accepting it. The key is stored locally using Android Keystore-backed encryption.")
            .setView(box)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CONNECT", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.isBlank()) {
                    input.error = "Enter a Gemini API key"
                    return@setOnClickListener
                }
                val oldKey = runCatching { SecureApiKeyStore(applicationContext).read() }.getOrNull()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                FridayRuntime.update("GEMINI CONNECTING", "Verifying Gemini API key…", true)
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val store = SecureApiKeyStore(applicationContext)
                            if (!store.save(key)) error("Secure key storage failed")
                            GeminiProvider(applicationContext).ask("Reply with exactly: CONNECTED").getOrThrow()
                        }
                    }
                    if (result.isSuccess) {
                        FridayRuntime.update("GEMINI CONNECTED", "Cloud brain verified and ready", true)
                        hud?.invalidate()
                        dialog.dismiss()
                    } else {
                        val store = SecureApiKeyStore(applicationContext)
                        runCatching { if (!oldKey.isNullOrBlank()) store.save(oldKey) else store.clear() }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        input.error = "Gemini connection failed. Check the key and try again."
                        FridayRuntime.update("GEMINI ERROR", result.exceptionOrNull()?.message ?: "Gemini connection failed", false)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun openAssistantRole() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val roles = getSystemService(android.app.role.RoleManager::class.java)
                if (roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true) {
                    startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))
                } else {
                    startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                }
            } else {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
        }.onFailure {
            FridayRuntime.update("ASSISTANT SETUP", "Open Android voice assistant settings", false)
        }
    }

    private fun openAccessibility() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { FridayRuntime.update("AUTOMATION SETUP", "Open Android Accessibility settings", false) }
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_MIC = 7101
    }
}
