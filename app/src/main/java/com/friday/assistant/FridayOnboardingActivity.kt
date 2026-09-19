package com.friday.assistant

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.runtime.FridayStateFlow
import com.friday.assistant.ui.FridayDynamicOrb
import kotlinx.coroutines.delay\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext

/** First-run bridge. Kept visually consistent with the main FRIDAY HUD. */
class FridayOnboardingActivity : ComponentActivity() {
    private lateinit var agent: FridayAgent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agent = FridayAgent(applicationContext)
        val completed = getSharedPreferences("friday_onboarding", MODE_PRIVATE).getBoolean("completed", false)
        if (completed && agent.hasApiKey()) {
            openHud()
            return
        }
        setContent { Onboarding() }
    }

    private fun openHud() {
        startActivity(Intent(this, FridayHudActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun openAppInfo() = runCatching {
        Toast.makeText(this, "App info → ⋮ → Allow restricted settings", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") })
    }

    private fun openAccessibility() = runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

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

    private fun isAssistantSelected(): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 23) return@runCatching false
        VoiceInteractionService.isActiveService(
            this,
            ComponentName(this, com.friday.assistant.voice.FridayVoiceInteractionService::class.java)
        )
    }.getOrDefault(false)

    private fun continueToFriday() {
        if (!agent.hasApiKey()) {
            Toast.makeText(this, "Connect Gemini brain first.", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("friday_onboarding", MODE_PRIVATE).edit().putBoolean("completed", true).apply()
        openHud()
    }

    override fun onDestroy() {
        if (::agent.isInitialized) agent.close()
        super.onDestroy()
    }

    @Composable
    private fun Onboarding() {
        var key by remember { mutableStateOf("") }
        var saved by remember { mutableStateOf(agent.hasApiKey()) }\n        var connecting by remember { mutableStateOf(false) }\n        var connectError by remember { mutableStateOf<String?>(null) }\n        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var accessibility by remember { mutableStateOf(isAccessibilityEnabled()) }
        var assistantSelected by remember { mutableStateOf(isAssistantSelected()) }
        val orbState by FridayStateFlow.state.collectAsState()

        LaunchedEffect(Unit) {
            while (true) {
                saved = agent.hasApiKey()
                accessibility = isAccessibilityEnabled()
                assistantSelected = isAssistantSelected()
                delay(700)
            }
        }

        MaterialTheme(
            colorScheme = androidx.compose.material3.darkColorScheme(
                primary = Color(0xFF35E8FF),
                secondary = Color(0xFFFF3E55),
                background = Color(0xFF02040A),
                surface = Color(0xFF070C14)
            )
        ) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF02040A)) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("FRIDAY", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp)
                            Text("INITIALIZATION", color = Color(0xFF35E8FF), fontSize = 9.sp, letterSpacing = 2.5.sp)
                        }
                        Text("SYSTEM 01", color = Color(0xFF4CFF9A), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    }

                    Box(Modifier.fillMaxWidth().height(270.dp), contentAlignment = Alignment.Center) {
                        FridayDynamicOrb(state = orbState, modifier = Modifier.size(255.dp))
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF070D16)),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF14313F), RoundedCornerShape(18.dp))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("SYSTEM BRIDGES", color = Color(0xFF35E8FF), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)

                            StatusRow("GEMINI BRAIN", saved, if (saved) "CONNECTED" else "API KEY REQUIRED")
                            StatusRow("AUTOMATION", accessibility, if (accessibility) "ONLINE" else "PERMISSION REQUIRED")
                            StatusRow("ANDROID ASSISTANT", assistantSelected, if (assistantSelected) "ACTIVE" else "SELECT FRIDAY")

                            if (!saved) {
                                OutlinedTextField(
                                    value = key,
                                    onValueChange = { key = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Gemini API key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Button(
                                    onClick = {
                                        if (key.isNotBlank()) {
                                            agent.configureApiKey(key.trim())
                                            key = ""
                                            saved = agent.hasApiKey()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (connecting) "VERIFYING GEMINI…" else "CONNECT GEMINI BRAIN") }\n                                connectError?.let { message ->\n                                    Text(message, color = Color(0xFFFF6B6B), fontSize = 9.sp, textAlign = TextAlign.Center)\n                                }
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = ::openAppInfo, modifier = Modifier.weight(1f)) { Text("APP INFO") }
                                OutlinedButton(onClick = ::openAccessibility, modifier = Modifier.weight(1f)) { Text("ACCESS") }
                            }
                            OutlinedButton(onClick = ::openAssistantRole, modifier = Modifier.fillMaxWidth()) {
                                Text(if (assistantSelected) "ANDROID ASSISTANT ACTIVE" else "SELECT FRIDAY AS ASSISTANT")
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = ::continueToFriday,
                        enabled = saved,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("ENTER FRIDAY HUD", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        if (assistantSelected) "HANDS-FREE READY • SAY “HEY FRIDAY”" else "SELECT FRIDAY AS ASSISTANT FOR HANDS-FREE WAKE",
                        color = if (assistantSelected) Color(0xFF4CFF9A) else Color(0xFF607985),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusRow(label: String, active: Boolean, detail: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xFFD8F6FF), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text("${if (active) "●" else "○"} $detail", color = if (active) Color(0xFF4CFF9A) else Color(0xFFFFB74D), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}
