package com.friday.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.friday.assistant.ai.SecureApiKeyStore
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.runtime.FridayStateFlow
import com.friday.assistant.voice.FridayHandsFreeService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Main FRIDAY screen. Voice is started explicitly after the HUD is stable to prevent startup crashes. */
class FridayHudActivity : ComponentActivity() {
    private lateinit var hud: FridayReferenceHudView
    private val scope = MainScope()
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveHud()
        hud = FridayReferenceHudView(this)
        hud.actions = object : FridayReferenceHudView.Actions {
            override fun onGeminiTap() { showGeminiDialog() }
            override fun onAssistantTap() { openAssistantRole() }
            override fun onAutomationTap() { openAccessibility() }
            override fun onOrbTap() { startBackgroundVoice() }
        }
        setContentView(hud)
        scope.launch {
            FridayStateFlow.state.collect { value -> if (!destroyed) hud.render(value) }
        }
        requestMicrophoneFirst()
    }

    override fun onResume() {
        super.onResume()
        destroyed = false
        enterImmersiveHud()
        // Do not start microphone foreground work automatically during Activity startup.
        // Android/Samsung can reject microphone FGS startup during lifecycle transitions.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophoneFirst()
        } else {
            FridayRuntime.update("READY", "FRIDAY HUD ready — tap the core to activate voice", true)
        }
    }

    private fun enterImmersiveHud() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun startBackgroundVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            FridayRuntime.update("MIC PERMISSION", "Allow microphone access for FRIDAY", false)
            requestMicrophoneFirst()
            return
        }
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, FridayHandsFreeService::class.java))
            FridayRuntime.update("STARTING", "FRIDAY voice engine starting", true)
        }.onFailure {
            FridayRuntime.update("HANDS-FREE ERROR", "Android refused FRIDAY's microphone service", false)
        }
    }

    private fun requestMicrophoneFirst() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                FridayRuntime.update("MIC READY", "Microphone permission granted — tap the core to activate voice", true)
                requestOptionalPermissions()
            } else {
                FridayRuntime.update("MIC BLOCKED", "Microphone permission is required for voice control", false)
            }
        }
    }

    private fun requestOptionalPermissions() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_OPTIONAL)
    }

    private fun showGeminiDialog() {
        val input = EditText(this).apply {
            hint = "Paste Gemini API key"
            setSingleLine(true)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setPadding(24, 16, 24, 16)
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding(22, 0, 22, 0)
            addView(input, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("GEMINI CORE")
            .setMessage("The key is stored locally using Android Keystore-backed encryption.")
            .setView(box)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CONNECT", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.isBlank()) { input.error = "Enter a Gemini API key"; return@setOnClickListener }
                val saved = runCatching { SecureApiKeyStore(applicationContext).save(key) }.getOrDefault(false)
                if (saved) {
                    FridayRuntime.update("GEMINI CONNECTED", "Cloud brain ready", true)
                    dialog.dismiss()
                } else {
                    input.error = "Could not securely store this key"
                    FridayRuntime.update("GEMINI ERROR", "Secure key storage failed", false)
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
                } else startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
        }.onFailure { FridayRuntime.update("ASSISTANT SETUP", "Open Android voice assistant settings", false) }
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
        private const val REQUEST_OPTIONAL = 7102
    }
}
