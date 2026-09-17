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
import com.friday.assistant.ai.SecureApiKeyStore
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.runtime.FridayStateFlow
import com.friday.assistant.voice.FridayHandsFreeService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Main FRIDAY screen. Startup is intentionally passive; microphone service starts only from the orb. */
class FridayHudActivity : ComponentActivity() {
    private var hud: FridayReferenceHudView? = null
    private val scope = MainScope()
    private var destroyed = false
    private var hudFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveHud()
        setContentView(startupView())
        window.decorView.post { if (!destroyed) attachHudSafely() }
    }

    private fun startupView(): TextView = TextView(this).apply {
        setBackgroundColor(Color.rgb(4, 2, 3)); setTextColor(Color.rgb(255, 137, 48)); textSize = 16f; text = "FRIDAY"; gravity = Gravity.CENTER
    }

    private fun attachHudSafely() {
        try {
            val view = FridayReferenceHudView(this)
            view.actions = object : FridayReferenceHudView.Actions {
                override fun onGeminiTap() { showGeminiDialog() }
                override fun onAssistantTap() { openAssistantRole() }
                override fun onAutomationTap() { openAccessibility() }
                override fun onOrbTap() { requestMicrophoneAndStart() }
            }
            hud = view; setContentView(view)
            scope.launch { FridayStateFlow.state.collect { value -> if (!destroyed) hud?.render(value) } }
            FridayRuntime.update("READY", "FRIDAY HUD ready — tap the orb to activate voice", true)
        } catch (_: Throwable) {
            hudFailed = true
            FridayRuntime.update("HUD ERROR", "FRIDAY HUD could not initialize safely", false)
            setContentView(startupView().apply { text = "FRIDAY\n\nHUD initialization failed safely." })
        }
    }

    override fun onResume() {
        super.onResume(); destroyed = false; enterImmersiveHud()
    }

    private fun enterImmersiveHud() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let { c -> c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()); c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        else @Suppress("DEPRECATION") run { window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE }
    }

    private fun requestMicrophoneAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC); return
        }
        startBackgroundVoice()
    }

    private fun startBackgroundVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        runCatching { ContextCompat.startForegroundService(this, Intent(this, FridayHandsFreeService::class.java)); FridayRuntime.update("STARTING", "FRIDAY voice engine starting", true) }
            .onFailure { FridayRuntime.update("HANDS-FREE ERROR", "Android refused FRIDAY's microphone service", false) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startBackgroundVoice()
    }

    private fun showGeminiDialog() {
        val input = EditText(this).apply { hint = "Paste Gemini API key"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setPadding(24, 16, 24, 16) }
        val box = android.widget.FrameLayout(this).apply { setPadding(22, 0, 22, 0); addView(input, android.widget.FrameLayout.LayoutParams(-1, -2)) }
        val dialog = AlertDialog.Builder(this).setTitle("GEMINI CORE").setMessage("The key is stored locally using Android Keystore-backed encryption.").setView(box).setNegativeButton("CANCEL", null).setPositiveButton("CONNECT", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val key = input.text?.toString()?.trim().orEmpty()
            if (key.isBlank()) { input.error = "Enter a Gemini API key"; return@setOnClickListener }
            if (runCatching { SecureApiKeyStore(applicationContext).save(key) }.getOrDefault(false)) { FridayRuntime.update("GEMINI CONNECTED", "Cloud brain ready", true); dialog.dismiss() }
            else { input.error = "Could not securely store this key"; FridayRuntime.update("GEMINI ERROR", "Secure key storage failed", false) }
        }}
        dialog.show()
    }

    private fun openAssistantRole() { runCatching { if (Build.VERSION.SDK_INT >= 29) { val roles = getSystemService(android.app.role.RoleManager::class.java); if (roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true) startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT)) else startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) } }.onFailure { FridayRuntime.update("ASSISTANT SETUP", "Open Android voice assistant settings", false) } }
    private fun openAccessibility() { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure { FridayRuntime.update("AUTOMATION SETUP", "Open Android Accessibility settings", false) } }
    override fun onDestroy() { destroyed = true; scope.cancel(); super.onDestroy() }
    companion object { private const val REQUEST_MIC = 7101 }
}