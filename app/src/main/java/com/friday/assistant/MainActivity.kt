package com.friday.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.friday.assistant.commands.FridayCommandProcessor
import com.friday.assistant.power.FridayPowerManager
import com.friday.assistant.voice.TTSManager
import com.friday.assistant.voice.VoiceManager

class MainActivity : ComponentActivity() {
    private lateinit var voiceManager: VoiceManager
    private lateinit var ttsManager: TTSManager
    private lateinit var agent: FridayAgent
    private val processor = FridayCommandProcessor()
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
        runCatching { startActivity(intent) }
            .onFailure { updateStatus("Battery settings are not available on this device.") }
    }

    @Composable
    private fun FridayApp() {
        var status by remember { mutableStateOf(powerStatus()) }
        var recognized by remember { mutableStateOf("") }
        var response by remember { mutableStateOf("Hello Boss. Main Friday hoon. Say 'Friday' when hands-free mode is enabled.") }
        var showKeyDialog by remember { mutableStateOf(false) }
        var apiKey by remember { mutableStateOf("") }
        val appLauncher = remember { AppLauncher(applicationContext) }
        val localProcessor = remember { processor }
        SideEffect { statusUpdater = { status = it } }

        DisposableEffect(Unit) {
            agent = FridayAgent(applicationContext)
            ttsManager = TTSManager(applicationContext) { status = "Text-to-speech is unavailable on this device." }
            voiceManager = VoiceManager(applicationContext, object : VoiceManager.Listener {
                override fun onListening() { status = "Listening..." }
                override fun onResult(text: String) {
                    recognized = text
                    val local = localProcessor.process(text)
                    if (local.handledLocally) {
                        response = local.text
                        ttsManager.speak(local.text)
                        local.action?.let { if (!appLauncher.launch(it)) { response = "I couldn't complete that action on this phone."; ttsManager.speak(response) } }
                    } else {
                        status = "Thinking..."
                        agent.handle(text) { answer, _ ->
                            response = answer
                            status = powerStatus()
                            ttsManager.speak(answer)
                        }
                    }
                    status = powerStatus()
                }
                override fun onError(message: String) { status = message }
            })
            onDispose { statusUpdater = null; voiceManager.destroy(); ttsManager.shutdown() }
        }

        fun requestOrStart() {
            startListening = { voiceManager.start() }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voiceManager.start()
            else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF32D5FF), surface = Color(0xFF111827))) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF080B12)) {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("FRIDAY", color = Color(0xFF32D5FF), fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("PERSONAL AI ASSISTANT • V2", color = Color(0xFF8DA3B8), fontSize = 11.sp)
                    Spacer(Modifier.height(30.dp))
                    Button(onClick = ::requestOrStart, modifier = Modifier.size(176.dp).clip(CircleShape).border(3.dp, Color(0xFF32D5FF), CircleShape), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D2B3A))) {
                        Text("◉\nTAP TO SPEAK", textAlign = TextAlign.Center, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = ::requestAssistantRole) { Text("ENABLE HANDS-FREE") }
                        OutlinedButton(onClick = { showKeyDialog = true }) { Text("AI BRAIN") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = ::openPowerSettings) { Text("BATTERY / BACKGROUND") }
                    Spacer(Modifier.height(18.dp))
                    Text(status, color = Color(0xFFB8D6E3), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(22.dp))
                    AssistantCard("YOU", recognized.ifBlank { "Your words will appear here." }, Color(0xFF8DA3B8))
                    Spacer(Modifier.height(12.dp))
                    AssistantCard("FRIDAY", response, Color(0xFF32D5FF))
                }
            }
        }

        if (showKeyDialog) {
            AlertDialog(
                onDismissRequest = { showKeyDialog = false },
                title = { Text("Online AI brain") },
                text = {
                    Column {
                        Text("Optional: add your Gemini API key. It is encrypted with Android Keystore and is not bundled into the APK.")
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("Gemini API key") }, visualTransformation = PasswordVisualTransformation())
                    }
                },
                confirmButton = {
                    TextButton(onClick = { agent.configureApiKey(apiKey); apiKey = ""; showKeyDialog = false; response = "Online brain configured, Boss." }) { Text("SAVE") }
                },
                dismissButton = { TextButton(onClick = { agent.clearApiKey(); showKeyDialog = false }) { Text("CLEAR") }
                }
            )
        }
    }

    private fun powerStatus(): String = if (FridayPowerManager.isIgnoringBatteryOptimizations(this)) {
        "Ready • Battery unrestricted"
    } else {
        "Ready • Battery optimization is enabled"
    }

    @Composable
    private fun AssistantCard(label: String, message: String, accent: Color) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) { Text(label, color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp); Spacer(Modifier.height(6.dp)); Text(message, color = Color(0xFFE1F1F7)) }
        }
    }
}
