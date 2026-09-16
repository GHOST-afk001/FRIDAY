package com.friday.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
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

/** Main FRIDAY screen: reference-matched HUD plus live runtime telemetry and background voice controls. */
class FridayHudActivity : ComponentActivity() {
    private lateinit var hud: FridayHudView
    private val scope = MainScope()
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        hud = FridayHudView(this)
        hud.actions = object : FridayHudView.Actions {
            override fun onGeminiTap() = showGeminiDialog()
            override fun onAssistantTap() = openAssistantRole()
            override fun onAutomationTap() = openAccessibility()
            override fun onOrbTap() = startBackgroundVoice(true)
        }
        setContentView(hud)
        scope.launch {
            FridayStateFlow.state.collect { state -> if (!destroyed) hud.render(state) }
        }
        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        destroyed = false
        requestRuntimePermissions()
        hud.postDelayed({ if (!destroyed) startBackgroundVoice(false) }, 900L)
    }

    private fun startBackgroundVoice(manual: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (manual) FridayRuntime.update("MIC PERMISSION", "Allow microphone access for hands-free FRIDAY", false)
            requestRuntimePermissions()
            return
        }
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, FridayHandsFreeService::class.java))
        }.onFailure {
            FridayRuntime.update("HANDS-FREE ERROR", "Android refused FRIDAY's background microphone service", false)
        }
    }

    private fun requestRuntimePermissions() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.READ_CONTACTS)
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            hud.postDelayed({ startBackgroundVoice(false) }, 250L)
        }
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
        AlertDialog.Builder(this)
            .setTitle("GEMINI CORE")
            .setMessage("The key stays in Android Keystore-backed app storage. Never paste a key that you have already exposed publicly.")
            .setView(box)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CONNECT", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val key = input.text?.toString()?.trim().orEmpty()
                        if (key.isBlank()) {
                            input.error = "Enter a Gemini API key"
                            return@setOnClickListener
                        }
                        val saved = runCatching { SecureApiKeyStore(applicationContext).save(key) }.getOrDefault(false)
                        if (saved) {
                            FridayRuntime.update("GEMINI CONNECTED", "Cloud brain ready", true)
                            dialog.dismiss()
                        } else {
                            input.error = "Could not securely store this key on the phone"
                            FridayRuntime.update("GEMINI ERROR", "Secure key storage failed", false)
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun openAssistantRole() = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(android.app.role.RoleManager::class.java)
            if (roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true) {
                startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))
            } else {
                startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
        }
    }.onFailure { FridayRuntime.update("ASSISTANT SETUP", "Open Android voice assistant settings", false) }

    private fun openAccessibility() = runCatching {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }.onFailure { FridayRuntime.update("AUTOMATION SETUP", "Open Android Accessibility settings", false) }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        super.onDestroy()
    }

    companion object { private const val REQUEST_PERMISSIONS = 7101 }
}
