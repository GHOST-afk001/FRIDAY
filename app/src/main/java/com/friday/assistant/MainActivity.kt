package com.friday.assistant

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import com.friday.assistant.runtime.DeviceSnapshot
import com.friday.assistant.runtime.DeviceTelemetry
import com.friday.assistant.runtime.FridayNotification
import com.friday.assistant.runtime.FridayNotificationListenerService
import com.friday.assistant.runtime.FridayNotifications
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.runtime.RuntimeStatus
import com.friday.assistant.security.ActionPolicyValidator
import com.friday.assistant.voice.TTSManager
import com.friday.assistant.voice.VoiceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    override fun onResume() {
        super.onResume()
        statusUpdater?.invoke("SYSTEM CHECK • ${nowTime()}")
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

    private fun openNotificationAccess() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this, FridayNotificationListenerService::class.java)
                )
                startActivity(intent)
            } else {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }.onFailure { updateStatus("Notification Access settings are unavailable.") }
    }

    private fun hasNotificationAccess(): Boolean = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
        manager.isNotificationListenerAccessGranted(ComponentName(this, FridayNotificationListenerService::class.java))
    }.getOrDefault(false)

    private fun openPowerSettings() {
        val requestIntent = FridayPowerManager.createOptimizationIntent(this)
        val intent = requestIntent ?: FridayPowerManager.createBatterySettingsIntent()
        runCatching { startActivity(intent) }.onFailure { updateStatus("Battery settings are not available on this device.") }
    }

    @Composable
    private fun FridayApp() {
        var status by remember { mutableStateOf("SYSTEM CHECK") }
        var recognized by remember { mutableStateOf("") }
        var response by remember { mutableStateOf("FRIDAY ready. Say Hey Friday or tap the voice control.") }
        var showKeyDialog by remember { mutableStateOf(false) }
        var apiKey by remember { mutableStateOf("") }
        var onlineBrain by remember { mutableStateOf(false) }
        var pendingConfirmation by remember { mutableStateOf<FridayAction?>(null) }
        var telemetry by remember { mutableStateOf(DeviceTelemetry.snapshot(applicationContext)) }
        var runtime by remember { mutableStateOf(FridayRuntime.status) }
        var notifications by remember { mutableStateOf(FridayNotifications.items) }
        var notificationAccess by remember { mutableStateOf(hasNotificationAccess()) }
        val appLauncher = remember { AppLauncher(applicationContext) }
        val localProcessor = remember { processor }
        SideEffect { statusUpdater = { status = it } }

        DisposableEffect(Unit) {
            val telemetryHandler = Handler(Looper.getMainLooper())
            val telemetryLoop = object : Runnable {
                override fun run() {
                    telemetry = DeviceTelemetry.snapshot(applicationContext)
                    notificationAccess = hasNotificationAccess()
                    telemetryHandler.postDelayed(this, 1000L)
                }
            }
            telemetryHandler.post(telemetryLoop)

            val runtimeSubscription = FridayRuntime.observe { runtime = it }
            val notificationSubscription = FridayNotifications.observe { notifications = it }
            agent = FridayAgent(applicationContext)
            onlineBrain = agent.hasApiKey()
            ttsManager = TTSManager(applicationContext) { status = "TTS unavailable. Install/update a Hindi or English voice in phone settings." }
            voiceManager = VoiceManager(applicationContext, object : VoiceManager.Listener {
                override fun onListening() {
                    FridayRuntime.update("LISTENING", "Microphone is listening for the next command", true)
                    status = "LISTENING • SPEAK NOW"
                }

                override fun onResult(text: String) {
                    voiceManager.cancel()
                    recognized = text
                    val normalized = text.trim().lowercase()

                    val pending = pendingConfirmation
                    if (pending != null) {
                        when {
                            normalized in setOf("yes", "yeah", "yep", "haan", "ha", "ji", "confirm", "do it", "kar do", "okay", "ok") -> {
                                pendingConfirmation = null
                                FridayRuntime.update("SAFETY CHECK", "Validating the confirmed action", true)
                                when (val outcome = policy.validate(pending)) {
                                    is ActionPolicyValidator.Outcome.Approved,
                                    is ActionPolicyValidator.Outcome.RequiresConfirmation -> {
                                        val action = when (outcome) {
                                            is ActionPolicyValidator.Outcome.Approved -> outcome.action
                                            is ActionPolicyValidator.Outcome.RequiresConfirmation -> outcome.action
                                            else -> pending
                                        }
                                        FridayRuntime.update("EXECUTING", "Executing the approved Android action", true)
                                        val ok = appLauncher.launch(action)
                                        response = if (ok) "Done, Boss." else "I couldn't complete that action on this phone."
                                        FridayRuntime.update(if (ok) "VERIFIED" else "ACTION FAILED", if (ok) "Android action reported success" else "Android action reported failure", ok)
                                    }
                                    is ActionPolicyValidator.Outcome.Rejected -> {
                                        response = outcome.reason
                                        FridayRuntime.update("ACTION REJECTED", outcome.reason, false)
                                    }
                                }
                                status = powerStatus()
                                ttsManager.speak(response)
                            }
                            normalized in setOf("no", "nope", "nah", "nahi", "nahin", "नहीं", "cancel", "mat karo") -> {
                                pendingConfirmation = null
                                response = "Okay Boss, cancelled."
                                FridayRuntime.update("CANCELLED", "Pending action cancelled by owner", true)
                                status = powerStatus()
                                ttsManager.speak(response)
                            }
                            else -> {
                                response = "Boss, please say yes or no. The action is still waiting for confirmation."
                                status = "CONFIRMATION REQUIRED"
                                FridayRuntime.update("WAITING CONFIRMATION", "No action has been executed", true)
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
                                        FridayRuntime.update("WAITING CONFIRMATION", "Owner confirmation required before execution", true)
                                        ttsManager.speak("${response} Kya main ise kar doon, Boss?")
                                    } else {
                                        FridayRuntime.update("EXECUTING", "Running a local Android command", true)
                                        val ok = appLauncher.launch(outcome.action)
                                        response = if (ok) local.text else "I couldn't complete that action on this phone."
                                        FridayRuntime.update(if (ok) "VERIFIED" else "ACTION FAILED", if (ok) "Android action reported success" else "Android action reported failure", ok)
                                        status = powerStatus()
                                        ttsManager.speak(response)
                                    }
                                }
                                is ActionPolicyValidator.Outcome.RequiresConfirmation -> {
                                    pendingConfirmation = outcome.action
                                    status = "CONFIRMATION REQUIRED"
                                    FridayRuntime.update("WAITING CONFIRMATION", "Safety policy requires owner approval", true)
                                    ttsManager.speak("${local.text} ${outcome.prompt} Say yes or no, Boss.")
                                }
                                is ActionPolicyValidator.Outcome.Rejected -> {
                                    response = outcome.reason
                                    status = "ACTION REJECTED"
                                    FridayRuntime.update("ACTION REJECTED", outcome.reason, false)
                                    ttsManager.speak(response)
                                }
                            }
                        } else {
                            status = powerStatus()
                            FridayRuntime.update("RESPONSE READY", "Local command completed", true)
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

                override fun onError(message: String) {
                    voiceManager.cancel()
                    FridayRuntime.update("VOICE ERROR", message, false)
                    status = message
                }
            })
            onDispose {
                telemetryHandler.removeCallbacks(telemetryLoop)
                statusUpdater = null
                runtimeSubscription.close()
                notificationSubscription.close()
                voiceManager.destroy()
                ttsManager.shutdown()
                agent.close()
            }
        }

        fun requestOrStart() {
            startListening = { voiceManager.start() }
            val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val contactsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            if (micGranted && contactsGranted) {
                voiceManager.start()
            } else {
                val permissions = buildList {
                    if (!micGranted) add(Manifest.permission.RECORD_AUDIO)
                    if (!contactsGranted) add(Manifest.permission.READ_CONTACTS)
                }.toTypedArray()
                permissionLauncher.launch(permissions)
            }
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF35E8FF), secondary = Color(0xFFFF3E55), background = Color(0xFF02040A), surface = Color(0xFF070C14))) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF02040A)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { HeaderPanel(telemetry, onlineBrain, runtime) }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCard("BATTERY", "${telemetry.batteryPercent}%", if (telemetry.charging) "CHARGING" else "ON BATTERY", Modifier.weight(1f))
                            MetricCard("RAM", "${telemetry.ramUsedGb}/${telemetry.ramTotalGb} GB", "LIVE", Modifier.weight(1f))
                            MetricCard("STORAGE", "${telemetry.storageUsedGb}/${telemetry.storageTotalGb} GB", "LIVE", Modifier.weight(1f))
                        }
                    }
                    item {
                        HudSection("FRIDAY CORE • REAL-TIME ACTIVITY") {
                            Text(runtime.stage, color = if (runtime.healthy) Color(0xFF4CFF9A) else Color(0xFFFF5266), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(runtime.detail, color = Color(0xFFD8F6FF), fontSize = 13.sp)
                            Text("Updated ${relativeTime(runtime.updatedAt)} • ${if (runtime.healthy) "HEALTHY" else "ATTENTION"}", color = Color(0xFF718896), fontSize = 10.sp)
                            Spacer(Modifier.height(8.dp))
                            AnalysisPipeline(runtime)
                        }
                    }
                    item {
                        HudSection("AI BRAIN") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(if (onlineBrain) "GEMINI CONNECTED" else "LOCAL CORE ACTIVE", color = if (onlineBrain) Color(0xFF4CFF9A) else Color(0xFFFFB74D), fontWeight = FontWeight.Bold)
                                Text("V6 AUTONOMOUS", color = Color(0xFF35E8FF), fontSize = 10.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("FRIDAY shows high-level decision stages here; private chain-of-thought is never displayed. The HUD reports real system stages instead of invented reasoning text.", color = Color(0xFF9EB2BF), fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                SmallStatus("CONTEXT", runtime.stage in setOf("CONTEXT", "PLANNING", "AI THINKING", "RESPONSE READY"))
                                SmallStatus("PLAN", runtime.stage in setOf("PLANNING", "AI THINKING", "ACTION READY"))
                                SmallStatus("SAFETY", runtime.stage.contains("SAFETY") || runtime.stage.contains("WAITING") || runtime.stage.contains("VERIFIED"))
                                SmallStatus("VERIFY", runtime.stage == "VERIFIED")
                            }
                        }
                    }
                    item {
                        HudSection("DEVICE • ${Build.MANUFACTURER.uppercase()} ${Build.MODEL}") {
                            DeviceLine("Android", "${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}")
                            DeviceLine("Network", telemetry.network)
                            DeviceLine("Battery", "${telemetry.batteryPercent}% • ${if (telemetry.charging) "Charging" else "Discharging"} • ${telemetry.batteryTempC}°C")
                            DeviceLine("Battery health", telemetry.batteryHealth)
                            DeviceLine("Screen", if (telemetry.interactive) "ON / INTERACTIVE" else "OFF / IDLE")
                        }
                    }
                    item {
                        HudSection("MESSAGES / NOTIFICATIONS") {
                            if (!notificationAccess) {
                                Text("Notification Access is OFF. Enable it once to let FRIDAY show live notification/message cards.", color = Color(0xFFFFB74D), fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = ::openNotificationAccess) { Text("ENABLE NOTIFICATION ACCESS") }
                            } else if (notifications.isEmpty()) {
                                Text("Connected • waiting for notifications", color = Color(0xFF7D95A3), fontSize = 12.sp)
                            } else {
                                notifications.take(8).forEach { NoticeRow(it) }
                            }
                        }
                    }
                    item {
                        HudSection("SYSTEM HEALTH") {
                            HealthRow("AI brain", if (onlineBrain) "READY" else "LOCAL CORE / KEY NEEDED", true)
                            HealthRow("Voice engine", "READY", true)
                            HealthRow("Battery optimization", if (FridayPowerManager.isIgnoringBatteryOptimizations(this@MainActivity)) "UNRESTRICTED" else "RESTRICTED", true)
                            HealthRow("Notification bridge", if (notificationAccess) "CONNECTED" else "DISABLED", notificationAccess)
                            HealthRow("Runtime", if (runtime.healthy) "STABLE" else "ATTENTION", runtime.healthy)
                        }
                    }
                    item {
                        HudSection("LIVE INPUT / OUTPUT") {
                            Text("YOU", color = Color(0xFF35E8FF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(recognized.ifBlank { "Awaiting voice command…" }, color = Color.White, fontSize = 13.sp)
                            Spacer(Modifier.height(7.dp))
                            Text("FRIDAY", color = Color(0xFFFF5266), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(response, color = Color(0xFFD8F6FF), fontSize = 13.sp)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = ::requestOrStart, modifier = Modifier.weight(1f).height(50.dp)) { Text("MIC • TALK", fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = ::requestAssistantRole, modifier = Modifier.weight(1f).height(50.dp)) { Text("HANDS-FREE") }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showKeyDialog = true }, modifier = Modifier.weight(1f)) { Text("AI BRAIN KEY") }
                            OutlinedButton(onClick = ::openPowerSettings, modifier = Modifier.weight(1f)) { Text("POWER") }
                            OutlinedButton(onClick = ::openNotificationAccess, modifier = Modifier.weight(1f)) { Text("NOTIFICATIONS") }
                        }
                    }
                    item { Text("FRIDAY • ULTRON-INSPIRED LIVE HUD • ${nowTime()}", color = Color(0xFF4B6675), fontSize = 9.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                }
            }
        }

        if (showKeyDialog) {
            AlertDialog(
                onDismissRequest = { showKeyDialog = false },
                title = { Text("AI BRAIN • GEMINI") },
                text = {
                    Column {
                        Text("The API key stays encrypted with Android Keystore and is not bundled into the APK.")
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

    @Composable
    private fun HeaderPanel(telemetry: DeviceSnapshot, onlineBrain: Boolean, runtime: RuntimeStatus) {
        val gradient = Brush.horizontalGradient(listOf(Color(0xFF0B1520), Color(0xFF160812), Color(0xFF08141A)))
        Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent), modifier = Modifier.fillMaxWidth().background(gradient, RoundedCornerShape(18.dp)).border(1.dp, Color(0xFF173B49), RoundedCornerShape(18.dp))) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("FRIDAY", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 5.sp)
                        Text("ULTRON-INSPIRED PERSONAL INTELLIGENCE", color = Color(0xFF7C95A3), fontSize = 8.sp, letterSpacing = 1.5.sp)
                    }
                    Text("${telemetry.batteryPercent}%", color = if (telemetry.charging) Color(0xFF4CFF9A) else Color(0xFF35E8FF), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text(if (onlineBrain) "AI BRAIN • CONNECTED" else "LOCAL CORE • ONLINE", color = Color(0xFF35E8FF), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text("${runtime.stage} • ${runtime.detail}", color = if (runtime.healthy) Color(0xFFB8D1DB) else Color(0xFFFF7585), fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Text(if (telemetry.charging) "⚡ CHARGING • ${telemetry.batteryPercent}% • ${telemetry.batteryTempC}°C" else "◉ ON BATTERY • ${telemetry.batteryPercent}% • ${telemetry.batteryTempC}°C", color = Color.White, fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun AnalysisPipeline(runtime: RuntimeStatus) {
        val stages = listOf("UNDERSTANDING", "CONTEXT", "PLANNING", "AI THINKING", "SAFETY CHECK", "EXECUTING", "VERIFIED")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            stages.forEach { stage ->
                val active = runtime.stage == stage || (runtime.stage == "RESPONSE READY" && stage == "VERIFIED")
                Box(Modifier.weight(1f).height(7.dp).background(if (active) Color(0xFF35E8FF) else Color(0xFF13232D), RoundedCornerShape(3.dp)))
            }
        }
    }

    @Composable
    private fun HudSection(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF070D16)), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF14313F), RoundedCornerShape(14.dp))) {
            Column(Modifier.padding(13.dp)) {
                Text(title, color = Color(0xFF35E8FF), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Spacer(Modifier.height(7.dp))
                content()
            }
        }
    }

    @Composable
    private fun MetricCard(title: String, value: String, sub: String, modifier: Modifier) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF080F18)), shape = RoundedCornerShape(11.dp), modifier = modifier.border(1.dp, Color(0xFF153441), RoundedCornerShape(11.dp))) {
            Column(Modifier.padding(10.dp)) {
                Text(title, color = Color(0xFF66828F), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(sub, color = Color(0xFF35E8FF), fontSize = 8.sp)
            }
        }
    }

    @Composable
    private fun SmallStatus(label: String, active: Boolean) {
        Text("${if (active) "●" else "○"} $label", color = if (active) Color(0xFF4CFF9A) else Color(0xFF536B77), fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }

    @Composable
    private fun DeviceLine(label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFF6E8793), fontSize = 10.sp)
            Text(value, color = Color(0xFFD8F6FF), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun NoticeRow(item: FridayNotification) {
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.app, color = Color(0xFFFF5266), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.time)), color = Color(0xFF5F7783), fontSize = 9.sp)
            }
            if (item.title.isNotBlank()) Text(item.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (item.text.isNotBlank()) Text(item.text, color = Color(0xFFAEC6D0), fontSize = 11.sp)
        }
    }

    @Composable
    private fun HealthRow(label: String, value: String, healthy: Boolean) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFF77909B), fontSize = 10.sp)
            Text("● $value", color = if (healthy) Color(0xFF4CFF9A) else Color(0xFFFF5266), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }

    private fun powerStatus(): String = if (FridayPowerManager.isIgnoringBatteryOptimizations(this)) "READY • BATTERY UNRESTRICTED" else "READY • BATTERY OPTIMIZATION ON"

    private fun nowTime(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun relativeTime(timestamp: Long): String {
        val seconds = ((System.currentTimeMillis() - timestamp) / 1000L).coerceAtLeast(0)
        return when {
            seconds < 2 -> "just now"
            seconds < 60 -> "${seconds}s ago"
            else -> "${seconds / 60}m ago"
        }
    }
}
