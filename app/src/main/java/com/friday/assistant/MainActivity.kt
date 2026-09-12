package com.friday.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.commands.AppLauncher
import com.friday.assistant.commands.FridayAction
import com.friday.assistant.commands.FridayCommandProcessor
import com.friday.assistant.power.FridayPowerManager
import com.friday.assistant.security.ActionPolicyValidator
import com.friday.assistant.voice.TTSManager
import com.friday.assistant.voice.VoiceManager

class MainActivity : ComponentActivity() {
    private lateinit var voiceManager: VoiceManager
    private lateinit var ttsManager: TTSManager
    private lateinit var agent: FridayAgent
    private val processor = FridayCommandProcessor()
    private val policy = ActionPolicyValidator()
    private var startListening: (() -> Unit)? = null
    private var statusUpdater: ((String) -> Unit)? = null

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening?.invoke() else updateStatus("Microphone permission is required for FRIDAY voice features.")
    }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FridayApp() }
    }

    private fun updateStatus(message: String) { statusUpdater?.invoke(message) }

    private fun requestAssistantRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true && !roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
            }
        }
    }

    private fun openPowerSettings() {
        val requestIntent = FridayPowerManager.createOptimizationIntent(this)
        val intent = requestIntent ?: FridayPowerManager.createBatterySettingsIntent()
        runCatching { startActivity(intent) }.onFailure { updateStatus("Battery settings are not available on this device.") }
    }

    @Composable
    private fun FridayApp() {
        var status by remember { mutableStateOf(powerStatus()) }
        var recognized by remember { mutableStateOf("") }
        var response by remember { mutableStateOf("Hello Boss. Main Friday hoon. Say 'Hey Friday' when hands-free mode is enabled.") }
        var showKeyDialog by remember { mutableStateOf(false) }
        var apiKey by remember { mutableStateOf("") }
        var onlineBrain by remember { mutableStateOf(false) }
        var pendingConfirmation by remember { mutableStateOf<FridayAction?>(null) }
        val appLauncher = remember { AppLauncher(applicationContext) }
        val localProcessor = remember { processor }
        SideEffect { statusUpdater = { status = it } }

        DisposableEffect(Unit) {
            agent = FridayAgent(applicationContext)
            onlineBrain = agent.hasApiKey()
            ttsManager = TTSManager(applicationContext) { status = "Text-to-speech is unavailable on this device." }
            voiceManager = VoiceManager(applicationContext, object : VoiceManager.Listener {
                override fun onListening() { status = "Listening..." }
                override fun onResult(text: String) {
                    voiceManager.cancel()
                    recognized = text

                    val pending = pendingConfirmation
                    if (pending != null) {
                        when {
                            text.trim().lowercase() in setOf("yes", "yeah", "yep", "haan", "ha", "ji", "confirm", "do it", "kar do", "okay", "ok") -> {
                                pendingConfirmation = null
                                val ok = appLauncher.launch(pending)
                                response = if (ok) "Done, Boss." else "I couldn't complete that action on this phone."
                                status = powerStatus()
                                ttsManager.speak(response)
                            }
                            text.trim().lowercase() in setOf("no", "nope", "nah", "nahi", "nahin", "नहीं", "cancel", "mat karo") -> {
                                pendingConfirmation = null
                                response = "Okay Boss, cancelled."
                                status = powerStatus()
                                ttsManager.speak(response)
                            }
                            else -> {
                                response = "Boss, please say yes or no. The action is still waiting for confirmation."
                                status = "Confirmation required"
                                ttsManager.speak(response)
                            }
                        }
                        return
                    }

                    val local = localProcessor.process(text)
                    if (local.handledLocally) {
                        response = local.text
                        val action = local.action
                        if (action != null) {
                            when (val outcome = policy.validate(action)) {
                                is ActionPolicyValidator.Outcome.Approved -> {
                                    if (local.needsConfirmation) {
                                        pendingConfirmation = outcome.action
                                        status = "Confirmation required"
                                        ttsManager.speak(response)
                                    } else {
                                        response = if (appLauncher.launch(outcome.action)) local.text else "I couldn't complete that action on this phone."
                                        status = powerStatus()
                                        ttsManager.speak(response)
                                    }
                                }
                                is ActionPolicyValidator.Outcome.RequiresConfirmation -> {
                                    pendingConfirmation = outcome.action
                                    status = "Confirmation required"
                                    ttsManager.speak("${local.text} ${outcome.prompt} Say yes or no, Boss.")
                                }
                                is ActionPolicyValidator.Outcome.Rejected -> {
                                    response = outcome.reason
                                    status = "Action rejected"
                                    ttsManager.speak(response)
                                }
                            }
                        } else {
                            status = powerStatus()
                            ttsManager.speak(response)
                        }
                    } else {
                        status = "Thinking..."
                        agent.handle(text) { answer, _ ->
                            response = answer
                            status = powerStatus()
                            ttsManager.speak(answer)
                        }
                    }
                }
                override fun onError(message: String) { voiceManager.cancel(); status = message }
            })
            onDispose {
                statusUpdater = null
                voiceManager.destroy()
                ttsManager.shutdown()
                agent.close()
            }
        }

        fun requestOrStart() {
            startListening = { voiceManager.start() }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voiceManager.start()
            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF39E7FF), secondary = Color(0xFF8A7CFF), surface = Color(0xFF090D16))) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF03060B)) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("ULTRON CORE", color = Color(0xFF39E7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("FRIDAY", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
                    Spacer(Modifier.height(18.dp))
                    Box(Modifier.size(190.dp).clip(CircleShape).background(Color(0xFF07121B)).border(2.dp, Color(0xFF39E7FF), CircleShape), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("◉", color = Color(0xFF39E7FF), fontSize = 54.sp)
                            Text(if (onlineBrain) "AI READY" else "LOCAL CORE", color = Color(0xFF9FEFFF), fontSize = 12.sp, letterSpacing = 3.sp)
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = ::requestOrStart, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("TAP TO SPEAK", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = ::requestAssistantRole) { Text("HANDS-FREE") }
                        OutlinedButton(onClick = { showKeyDialog = true }) { Text("AI BRAIN") }
                    }
                    OutlinedButton(onClick = ::openPowerSettings) { Text("BATTERY / BACKGROUND") }
                    Spacer(Modifier.height(12.dp))
                    Text(status, color = Color(0xFF9FB6C7), textAlign = TextAlign.Center, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    AssistantCard("YOU", recognized.ifBlank { "Waiting for your command..." })
                    Spacer(Modifier.height(8.dp))
                    AssistantCard("FRIDAY", response)
                }
            }
        }

        if (showKeyDialog) {
            AlertDialog(
                onDismissRequest = { showKeyDialog = false },
                title = { Text("Online AI brain") },
                text = { Column { Text("Optional Gemini API key. It is encrypted with Android Keystore and is not bundled into the APK."); Spacer(Modifier.height(12.dp)); OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("Gemini API key") }, visualTransformation = PasswordVisualTransformation()) } },
                confirmButton = { TextButton(onClick = { agent.configureApiKey(apiKey); onlineBrain = agent.hasApiKey(); apiKey = ""; showKeyDialog = false; response = if (onlineBrain) "Online brain configured, Boss." else "No valid online brain key is configured." }) { Text("SAVE") } },
                dismissButton = { TextButton(onClick = { agent.clearApiKey(); onlineBrain = false; showKeyDialog = false }) { Text("CLEAR") } }
            )
        }
    }

    private fun powerStatus(): String = if (FridayPowerManager.isIgnoringBatteryOptimizations(this)) "Ready • Battery unrestricted" else "Ready • Battery optimization is enabled"

    @Composable
    private fun AssistantCard(label: String, message: String) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0B111C)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) { Text(label, color = Color(0xFF39E7FF), fontWeight = FontWeight.Bold, fontSize = 11.sp); Spacer(Modifier.height(4.dp)); Text(message, color = Color(0xFFE5F7FF)) }
        }
    }
}
