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
import androidx.compose.foundation.shape.RoundedCornerShape
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

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (mic) startListening?.invoke() else updateStatus("Microphone permission is required for FRIDAY voice features.")
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
                updateStatus("Choose FRIDAY as your default assistant, then say Hey Friday.")
            } else {
                updateStatus("FRIDAY assistant role is already active or unavailable.")
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
        var response by remember { mutableStateOf("System ready. Say Hey Friday for hands-free mode.") }
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
            ttsManager = TTSManager(applicationContext) { status = "TTS unavailable. Install/update a Hindi or English voice in phone settings." }
            voiceManager = VoiceManager(applicationContext, object : VoiceManager.Listener {
                override fun onListening() { status = "LISTENING • SPEAK NOW" }
                override fun onResult(text: String) {
                    voiceManager.cancel()
                    recognized = text
                    val normalized = text.trim().lowercase()

                    val pending = pendingConfirmation
                    if (pending != null) {
                        when {
                            normalized in setOf("yes", "yeah", "yep", "haan", "ha", "ji", "confirm", "do it", "kar do", "okay", "ok") -> {
                                pendingConfirmation = null
                                when (val outcome = policy.validate(pending)) {
                                    is ActionPolicyValidator.Outcome.Approved,
                                    is ActionPolicyValidator.Outcome.RequiresConfirmation -> {
                                        val action = when (outcome) {
                                            is ActionPolicyValidator.Outcome.Approved -> outcome.action
                                            is ActionPolicyValidator.Outcome.RequiresConfirmation -> outcome.action
                                            else -> pending
                                        }
                                        val ok = appLauncher.launch(action)
                                        response = if (ok) "Done, Boss." else "I couldn't complete that action on this phone."
                                    }
                                    is ActionPolicyValidator.Outcome.Rejected -> response = outcome.reason
                                }
                                status = powerStatus()
                                ttsManager.speak(response)
                            }
                            normalized in setOf("no", "nope", "nah", "nahi", "nahin", "नहीं", "cancel", "mat karo") -> {
                                pendingConfirmation = null
                                response = "Okay Boss, cancelled."
                                status = powerStatus()
                                ttsManager.speak(response)
                            }
                            else -> {
                                response = "Boss, please say yes or no. The action is still waiting for confirmation."
                                status = "CONFIRMATION REQUIRED"
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
                                        status = "CONFIRMATION REQUIRED"
                                        ttsManager.speak("${response} Kya main ise kar doon, Boss?")
                                    } else {
                                        response = if (appLauncher.launch(outcome.action)) local.text else "I couldn't complete that action on this phone."
                                        status = powerStatus()
                                        ttsManager.speak(response)
                                    }
                                }
                                is ActionPolicyValidator.Outcome.RequiresConfirmation -> {
                                    pendingConfirmation = outcome.action
                                    status = "CONFIRMATION REQUIRED"
                                    ttsManager.speak("${local.text} ${outcome.prompt} Say yes or no, Boss.")
                                }
                                is ActionPolicyValidator.Outcome.Rejected -> {
                                    response = outcome.reason
                                    status = "ACTION REJECTED"
                                    ttsManager.speak(response)
                                }
                            }
                        } else {
                            status = powerStatus()
                            ttsManager.speak(response)
                        }
                    } else {
                        status = if (onlineBrain) "AI BRAIN • THINKING" else "LOCAL CORE • AI KEY NEEDED"
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
            val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                voiceManager.start()
            } else {
                val permissions = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                        add(Manifest.permission.READ_CONTACTS)
                    }
                }.toTypedArray()
                permissionLauncher.launch(permissions)
            }
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF35E8FF), secondary = Color(0xFF8D7BFF), surface = Color(0xFF080D15))) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF02040A)) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("ULTRON // CORE ONLINE", color = Color(0xFF35E8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    Text("FRIDAY", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 9.sp)
                    Text("PERSONAL INTELLIGENCE SYSTEM", color = Color(0xFF6D8292), fontSize = 9.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.height(16.dp))

                    Box(
                        Modifier.size(210.dp).clip(CircleShape).background(Color(0xFF06101A)).border(1.dp, Color(0xFF164E5E), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(176.dp).border(2.dp, Color(0xFF35E8FF), CircleShape), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(136.dp).border(1.dp, Color(0xFF8D7BFF), CircleShape), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("◉", color = Color(0xFF35E8FF), fontSize = 48.sp)
                                    Text(if (onlineBrain) "AI READY" else "LOCAL CORE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                                    Text("WAKE: HEY FRIDAY", color = Color(0xFF6D8292), fontSize = 8.sp, letterSpacing = 1.sp)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(status, color = Color(0xFF35E8FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = ::requestOrStart,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("MIC • TALK TO FRIDAY", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }

                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = ::requestAssistantRole, modifier = Modifier.weight(1f)) { Text("HANDS-FREE") }
                        OutlinedButton(onClick = { showKeyDialog = true }, modifier = Modifier.weight(1f)) { Text("AI BRAIN") }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = ::openPowerSettings) { Text("BATTERY / BACKGROUND") }

                    Spacer(Modifier.height(10.dp))
                    HudCard("VOICE INPUT", recognized.ifBlank { "Awaiting command..." })
                    Spacer(Modifier.height(7.dp))
                    HudCard("FRIDAY RESPONSE", response)
                }
            }
        }

        if (showKeyDialog) {
            AlertDialog(
                onDismissRequest = { showKeyDialog = false },
                title = { Text("AI BRAIN • GEMINI") },
                text = {
                    Column {
                        Text("Without an API key FRIDAY uses the local core only. The key is encrypted with Android Keystore and is not bundled into the APK.")
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("Gemini API key") }, visualTransformation = PasswordVisualTransformation())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        agent.configureApiKey(apiKey)
                        onlineBrain = agent.hasApiKey()
                        apiKey = ""
                        showKeyDialog = false
                        response = if (onlineBrain) "AI brain configured, Boss." else "No valid AI key is configured."
                    }) { Text("SAVE") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        agent.clearApiKey()
                        onlineBrain = false
                        showKeyDialog = false
                    }) { Text("CLEAR") }
                }
            )
        }
    }

    private fun powerStatus(): String = if (FridayPowerManager.isIgnoringBatteryOptimizations(this)) "READY • BATTERY UNRESTRICTED" else "READY • BATTERY OPTIMIZATION ON"

    @Composable
    private fun HudCard(label: String, message: String) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF080E17)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF132B38), RoundedCornerShape(10.dp))
        ) {
            Column(Modifier.padding(11.dp)) {
                Text(label, color = Color(0xFF35E8FF), fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                Text(message, color = Color(0xFFD9F6FF), fontSize = 13.sp)
            }
        }
    }
}
