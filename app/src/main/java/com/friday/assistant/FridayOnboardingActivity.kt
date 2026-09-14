package com.friday.assistant

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.friday.assistant.ai.FridayAgent
import kotlinx.coroutines.delay

/**
 * First-run bridge for sideloaded builds. Android may restrict Accessibility for downloaded
 * apps; FRIDAY cannot bypass that security control programmatically, so this screen gives the
 * owner the exact Settings entry point instead.
 */
class FridayOnboardingActivity : ComponentActivity() {
    private lateinit var agent: FridayAgent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agent = FridayAgent(applicationContext)
        val completed = getSharedPreferences("friday_onboarding", MODE_PRIVATE).getBoolean("completed", false)
        if (completed && agent.hasApiKey()) {
            startActivity(Intent(this, MainActivityV2::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
            return
        }
        setContent { Onboarding() }
    }

    private fun openAppInfo() = runCatching {
        Toast.makeText(this, "App info → ⋮ → Allow restricted settings", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    private fun openAccessibility() = runCatching {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAssistantRole() = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val roles = getSystemService(android.app.role.RoleManager::class.java)
            if (roles?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true) {
                startActivity(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))
            } else {
                Toast.makeText(this, "Android Assistant role is unavailable on this device.", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Hands-free Assistant role needs Android 10 or newer.", Toast.LENGTH_LONG).show()
        }
    }

    private fun isAccessibilityEnabled(): Boolean = runCatching {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        enabled.split(':').any {
            it.equals(ComponentName(this, com.friday.assistant.automation.FridayAccessibilityService::class.java).flattenToString(), true)
        }
    }.getOrDefault(false)

    private fun continueToFriday() {
        if (!agent.hasApiKey()) {
            Toast.makeText(this, "Connect Gemini brain first.", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("friday_onboarding", MODE_PRIVATE).edit().putBoolean("completed", true).apply()
        startActivity(Intent(this, MainActivityV2::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    override fun onDestroy() {
        if (::agent.isInitialized) agent.close()
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun Onboarding() {
        var key by remember { mutableStateOf("") }
        var saved by remember { mutableStateOf(agent.hasApiKey()) }
        var accessibility by remember { mutableStateOf(isAccessibilityEnabled()) }

        LaunchedEffect(Unit) {
            while (true) {
                saved = agent.hasApiKey()
                accessibility = isAccessibilityEnabled()
                delay(800)
            }
        }

        MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Color(0xFF35E8FF), background = Color(0xFF02040A), surface = Color(0xFF070C14))) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF02040A)) {
                Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("FRIDAY", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                    Text("INITIAL SYSTEM SETUP", color = Color(0xFF35E8FF))
                    Text("Imroz Sir, pehle brain aur system bridges activate karte hain. API key APK mein embed nahi hoti; ye Android Keystore mein encrypted store hogi.", color = Color(0xFFD8F6FF))

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("1 • GEMINI 3.6 FLASH", color = Color(0xFF4CFF9A))
                            OutlinedTextField(
                                value = key,
                                onValueChange = { key = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Gemini API key") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true
                            )
                            Button(onClick = {
                                if (key.isNotBlank()) {
                                    agent.configureApiKey(key)
                                    key = ""
                                    saved = agent.hasApiKey()
                                }
                            }) { Text(if (saved) "GEMINI BRAIN CONNECTED" else "CONNECT GEMINI BRAIN") }
                        }
                    }

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("2 • AUTOMATION ACCESS", color = if (accessibility) Color(0xFF4CFF9A) else Color(0xFFFFB74D))
                            Text("Sideloaded Android builds may block Accessibility until you manually allow Restricted settings.", color = Color(0xFFB7CBD4))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = ::openAppInfo, Modifier.weight(1f)) { Text("APP INFO") }
                                OutlinedButton(onClick = ::openAccessibility, Modifier.weight(1f)) { Text("ACCESSIBILITY") }
                            }
                            Text(
                                if (accessibility) "FRIDAY Automation is enabled."
                                else "App info → ⋮ → Allow restricted settings → back to Accessibility → enable FRIDAY.",
                                color = if (accessibility) Color(0xFF4CFF9A) else Color(0xFF7E9AA6)
                            )
                        }
                    }

                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("3 • HANDS-FREE", color = Color(0xFF35E8FF))
                            Text("Select FRIDAY as Android's Assistant. Android keeps the selected VoiceInteractionService running for hotwording.", color = Color(0xFFB7CBD4))
                            OutlinedButton(onClick = ::openAssistantRole) { Text("ENABLE HANDS-FREE ASSISTANT") }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = ::continueToFriday,
                        enabled = saved,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("ENTER FRIDAY HUD") }
                }
            }
        }
    }
}