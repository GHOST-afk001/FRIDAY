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
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.friday.assistant.ai.SecureApiKeyStore
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.runtime.FridayStateFlow
import com.friday.assistant.voice.FridayHandsFreeService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** FRIDAY live HUD launcher. Voice becomes hands-free after the one-time microphone permission. */
class FridayHudActivity : ComponentActivity() {
    private val scope = MainScope()
    private var hud: FridayGreenHudView? = null
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()
        attachHud()
        startHandsFreeAutomatically()
    }

    private fun attachHud() {
        runCatching {
            val view = FridayGreenHudView(this)
            view.actions = object : FridayGreenHudView.Actions {
                override fun onGeminiTap() = showGeminiDialog()
                override fun onAssistantTap() = openAssistantRole()
                override fun onAutomationTap() = openAccessibility()
                override fun onOrbTap() = startHandsFreeAutomatically()
            }
            hud = view
            setContentView(view)
            scope.launch { FridayStateFlow.state.collect { if (!destroyed) hud?.render(it) } }
            FridayRuntime.update("READY", "FRIDAY live HUD ready", true)
        }.onFailure {
            FridayRuntime.update("HUD ERROR", "Live HUD failed safely", false)
        }
    }

    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let { c -> c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()); c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        else @Suppress("DEPRECATION") run { window.decorView.systemUiVisibility = 5894 }
    }

    private fun startHandsFreeAutomatically() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            FridayRuntime.update("MIC PERMISSION", "Allow microphone once to enable hands-free FRIDAY", true)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, FridayHandsFreeService::class.java))
            FridayRuntime.update("HANDS-FREE ACTIVE", "Listening in background — no tap required", true)
        }.onFailure { FridayRuntime.update("HANDS-FREE ERROR", "Android refused the microphone service", false) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startHandsFreeAutomatically()
    }

    private fun showGeminiDialog() {
        val input = EditText(this).apply { hint = "Paste Gemini API key"; setSingleLine(true); setTextColor(android.graphics.Color.WHITE); setHintTextColor(android.graphics.Color.GRAY) }
        val box = FrameLayout(this).apply { setPadding(24, 0, 24, 0); addView(input, FrameLayout.LayoutParams(-1, -2)) }
        val dialog = AlertDialog.Builder(this).setTitle("GEMINI CORE").setMessage("Key is stored locally using Android Keystore-backed encryption.").setView(box).setNegativeButton("CANCEL", null).setPositiveButton("CONNECT", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val key = input.text?.toString()?.trim().orEmpty()
            if (key.isBlank()) { input.error = "Enter Gemini API key"; return@setOnClickListener }
            if (runCatching { SecureApiKeyStore(applicationContext).save(key) }.getOrDefault(false)) { FridayRuntime.update("GEMINI CONNECTED", "Cloud brain ready", true); hud?.setGeminiReady(true); dialog.dismiss() }
            else input.error = "Could not securely store key"
        }}
        dialog.show()
    }

    private fun openAssistantRole() { runCatching { if (Build.VERSION.SDK_INT >= 29) { val roles = getSystemService(android.app.role.RoleManager::class.java); if (roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true) startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT)) else startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) } } }
    private fun openAccessibility() { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
    override fun onDestroy() { destroyed = true; scope.cancel(); super.onDestroy() }
    companion object { private const val REQUEST_MIC = 7101 }
}
