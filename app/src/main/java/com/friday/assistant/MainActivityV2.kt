package com.friday.assistant

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionService
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
import com.friday.assistant.security.ActionResultValidator
import com.friday.assistant.voice.TTSManager
import com.friday.assistant.voice.VoiceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivityV2 : ComponentActivity() {
    private lateinit var voice: VoiceManager
    private lateinit var tts: TTSManager
    private lateinit var agent: FridayAgent
    private val processor = FridayCommandProcessor()
    private val policy = ActionPolicyValidator()
    private val resultValidator = ActionResultValidator()
    private val launcher by lazy { AppLauncher(applicationContext) }
    private var startVoice: (() -> Unit)? = null
    private var status: ((String) -> Unit)? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] == true || hasPermission(Manifest.permission.RECORD_AUDIO)
        if (mic) startVoice?.invoke() else status?.invoke("Microphone permission is required for FRIDAY voice features.")
    }
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        status?.invoke(if (isAssistantActive()) "HANDS-FREE • WAKE ACTIVE" else "Choose FRIDAY as the default assistant to enable wake mode.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FridayHud() }
    }

    override fun onResume() {
        super.onResume()
        status?.invoke(if (isAssistantActive()) "HANDS-FREE • WAKE ACTIVE" else "SYSTEM CHECK • ${nowTime()}")
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isAssistantActive(): Boolean = runCatching {
        VoiceInteractionService.isActiveService(this, ComponentName(this, com.friday.assistant.voice.FridayVoiceInteractionService::class.java))
    }.getOrDefault(false)

    private fun requestAssistant() {
        if (Build.VERSION.SDK_INT < 29) {
            status?.invoke("Android Assistant role needs Android 10 or newer.")
            return
        }
        val roles = getSystemService(RoleManager::class.java)
        if (roles?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true && !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
        } else if (roles?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true) {
            status?.invoke("HANDS-FREE • WAKE ACTIVE")
        } else {
            status?.invoke("FRIDAY assistant role is unavailable on this device.")
        }
    }

    private fun openAccessibility() = runCatching {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }.onFailure { status?.invoke("Accessibility settings are unavailable.") }

    private fun openAppInfo() = runCatching {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
        })
    }

    private fun openNotifications() = runCatching {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }.onFailure { status?.invoke("Notification Access settings are unavailable.") }

    private fun hasNotificationAccess(): Boolean = runCatching {
        getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, FridayNotificationListenerService::class.java))
    }.getOrDefault(false)

    private fun openPower() {
        val intent = FridayPowerManager.createOptimizationIntent(this) ?: FridayPowerManager.createBatterySettingsIntent()
        runCatching { startActivity(intent) }.onFailure { status?.invoke("Battery settings are unavailable.") }
    }

    @Composable
    private fun FridayHud() {
        var recognized by remember { mutableStateOf("") }
        var response by remember { mutableStateOf("FRIDAY ready. Say Hey Friday for hands-free mode.") }
        var showKeyDialog by remember { mutableStateOf(false) }
        var apiKey by remember { mutableStateOf("") }
        var onlineBrain by remember { mutableStateOf(false) }
        var pendingConfirmation by remember { mutableStateOf<FridayAction?>(null) }
        var telemetry by remember { mutableStateOf(DeviceTelemetry.snapshot(applicationContext)) }
        var runtime by remember { mutableStateOf(FridayRuntime.status) }
        var notifications by remember { mutableStateOf(FridayNotifications.items) }
        var notificationAccess by remember { mutableStateOf(hasNotificationAccess()) }

        DisposableEffect(Unit) {
            val handler = Handler(Looper.getMainLooper())
            val loop = object : Runnable {
                override fun run() {
                    telemetry = DeviceTelemetry.snapshot(applicationContext)
                    notificationAccess = hasNotificationAccess()
                    handler.postDelayed(this, 1000L)
                }
            }
            handler.post(loop)
            val runtimeSubscription = FridayRuntime.observe { runtime = it }
            val notificationSubscription = FridayNotifications.observe { notifications = it }
            agent = FridayAgent(applicationContext)
            onlineBrain = agent.hasApiKey()
            tts = TTSManager(applicationContext) { response = "TTS unavailable. Install/update a Hindi or English voice." }
            voice = VoiceManager(applicationContext, object : VoiceManager.Listener {
                override fun onListening() { FridayRuntime.update("LISTENING", "Microphone is listening for your command", true) }

                override fun onResult(value: String) {
                    voice.cancel()
                    recognized = value
                    val normalized = value.trim().lowercase(Locale.ROOT)
                    val pending = pendingConfirmation
                    if (pending != null) {
                        if (normalized in setOf("yes", "haan", "ha", "han", "ji", "confirm", "ok", "okay", "do it", "kar do")) {
                            pendingConfirmation = null
                            execute(pending) { response = it }
                        } else if (normalized in setOf("no", "nahi", "nahin", "cancel", "mat karo")) {
                            pendingConfirmation = null
                            response = "Okay Boss, cancelled."
                            FridayRuntime.update("CANCELLED", "Owner cancelled the pending action", true)
                            tts.speak(response)
                        } else {
                            response = "Say yes or no, Boss."
                            tts.speak(response)
                        }
                        return
                    }

                    val local = processor.process(value)
                    if (local.handledLocally && local.action != null) {
                        when (val result = policy.validate(local.action)) {
                            is ActionPolicyValidator.Outcome.Approved -> {
                                if (local.needsConfirmation) {
                                    pendingConfirmation = result.action
                                    response = "${local.text} Kya main ise kar doon, Boss?"
                                    FridayRuntime.update("WAITING CONFIRMATION", "Owner approval required", true)
                                    tts.speak(response)
                                } else execute(result.action) { response = it }
                            }
                            is ActionPolicyValidator.Outcome.RequiresConfirmation -> {
                                pendingConfirmation = result.action
                                response = "${local.text} ${result.prompt} Say yes or no, Boss."
                                FridayRuntime.update("WAITING CONFIRMATION", result.prompt, true)
                                tts.speak(response)
                            }
                            is ActionPolicyValidator.Outcome.Rejected -> {
                                response = result.reason
                                FridayRuntime.update("ACTION REJECTED", result.reason, false)
                                tts.speak(response)
                            }
                        }
                    } else if (local.handledLocally) {
                        response = local.text
                        tts.speak(response)
                    } else {
                        agent.handle(value) { result, _ ->
                            response = result
                            tts.speak(result)
                        }
                    }
                }

                override fun onError(message: String) {
                    voice.cancel()
                    FridayRuntime.update("VOICE ERROR", message, false)
                    response = message
                }
            })
            onDispose {
                handler.removeCallbacks(loop)
                runtimeSubscription.close()
                notificationSubscription.close()
                voice.destroy()
                tts.shutdown()
                agent.close()
                status = null
            }
        }

        fun listen() {
            startVoice = { voice.start() }
            val needed = buildList {
                if (!hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
                if (!hasPermission(Manifest.permission.READ_CONTACTS)) add(Manifest.permission.READ_CONTACTS)
                if (!hasPermission(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            }.toTypedArray()
            if (needed.isEmpty()) voice.start() else permissions.launch(needed)
        }

        val publishStatus: (String) -> Unit = { message -> response = message }
        SideEffect { status = publishStatus }

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
                                Text(if (onlineBrain) "GEMINI CORE • ACTIVE" else "GEMINI CORE • NOT CONFIGURED", color = if (onlineBrain) Color(0xFF4CFF9A) else Color(0xFFFFB74D), fontWeight = FontWeight.Bold)
                                Text("V6 AUTONOMOUS", color = Color(0xFF35E8FF), fontSize = 10.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("FRIDAY reports real system stages here. Private chain-of-thought is never displayed.", color = Color(0xFF9EB2BF), fontSize = 11.sp)
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
                                Text("Notification Access is OFF. Enable it once for live notification/message cards.", color = Color(0xFFFFB74D), fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(onClick = ::openNotifications) { Text("ENABLE NOTIFICATION ACCESS") }
                            } else if (notifications.isEmpty()) {
                                Text("Connected • waiting for notifications", color = Color(0xFF7D95A3), fontSize = 12.sp)
                            } else notifications.take(8).forEach { NoticeRow(it) }
                        }
                    }
                    item {
                        HudSection("SYSTEM HEALTH") {
                            HealthRow("AI brain", if (onlineBrain) "GEMINI READY" else "KEY REQUIRED", onlineBrain)
                            HealthRow("Voice engine", "READY", true)
                            HealthRow("Hands-free assistant", if (isAssistantActive()) "WAKE ACTIVE" else "ROLE NOT ACTIVE", isAssistantActive())
                            val batteryUnrestricted = FridayPowerManager.isIgnoringBatteryOptimizations(this@MainActivityV2)
                            HealthRow("Battery optimization", if (batteryUnrestricted) "UNRESTRICTED" else "RESTRICTED", batteryUnrestricted)
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
                            Button(onClick = ::listen, modifier = Modifier.weight(1f).height(50.dp)) { Text("MIC • TALK", fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = ::requestAssistant, modifier = Modifier.weight(1f).height(50.dp)) { Text("HANDS-FREE") }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = ::openAccessibility, modifier = Modifier.weight(1f)) { Text("AUTOMATION", fontSize = 9.sp) }
                            OutlinedButton(onClick = ::openAppInfo, modifier = Modifier.weight(1f)) { Text("UNLOCK SETTINGS", fontSize = 9.sp) }
                            OutlinedButton(onClick = ::openPower, modifier = Modifier.weight(1f)) { Text("POWER", fontSize = 9.sp) }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { showKeyDialog = true }, modifier = Modifier.weight(1f)) { Text("AI BRAIN KEY", fontSize = 9.sp) }
                            OutlinedButton(onClick = ::openNotifications, modifier = Modifier.weight(1f)) { Text("NOTIFICATIONS", fontSize = 9.sp) }
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
                        val key = apiKey.trim()
                        if (key.isNotBlank()) {
                            agent.configureApiKey(key)
                            onlineBrain = agent.hasApiKey()
                            response = "AI brain configured, Boss."
                        }
                        apiKey = ""
                        showKeyDialog = false
                    }) { Text("SAVE") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        agent.clearApiKey()
                        onlineBrain = false
                        apiKey = ""
                        showKeyDialog = false
                    }) { Text("CLEAR") }
                }
            )
        }
    }

    private fun execute(action: FridayAction, onDone: (String) -> Unit) {
        FridayRuntime.update("EXECUTING", "Executing approved Android action", true)
        val ok = launcher.launch(action)
        val observation = if (ok) ActionResultValidator.ExecutionObservation.HANDED_OFF else ActionResultValidator.ExecutionObservation.FAILED
        val result = resultValidator.fromExecution(
            action = action,
            observation = observation,
            detail = if (ok) "Android accepted the action hand-off; external completion is not observed" else "Android action hand-off failed"
        )
        val message = when (result.status) {
            ActionResultValidator.Status.HANDED_OFF -> "Android accepted that request, Boss."
            ActionResultValidator.Status.SUCCESS -> "Done, Boss."
            else -> if (action is FridayAction.AccessibilityCommand) "Automation is blocked by Android settings. Enable Accessibility and allow restricted settings for FRIDAY." else "I couldn't complete that action on this phone."
        }
        FridayRuntime.update(if (result.status == ActionResultValidator.Status.HANDED_OFF) "HANDED OFF" else if (result.verified) "VERIFIED" else "ACTION FAILED", result.detail, result.verified || result.status == ActionResultValidator.Status.HANDED_OFF)
        onDone(message)
        tts.speak(message)
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
                Text(if (onlineBrain) "GEMINI CORE • ACTIVE" else "GEMINI CORE • NOT CONFIGURED", color = if (onlineBrain) Color(0xFF35E8FF) else Color(0xFFFFB74D), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text("${runtime.stage} • ${runtime.detail}", color = if (runtime.healthy) Color(0xFFB8D1DB) else Color(0xFFFF7585), fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Text(if (telemetry.charging) "⚡ CHARGING • ${telemetry.batteryPercent}% • ${telemetry.batteryTempC}°C" else "◉ ON BATTERY • ${telemetry.batteryPercent}% • ${telemetry.batteryTempC}°C", color = Color.White, fontSize = 12.sp)
                Text(if (isAssistantActive()) "WAKE: HEY FRIDAY • ACTIVE" else "WAKE: HEY FRIDAY • ROLE REQUIRED", color = if (isAssistantActive()) Color(0xFF4CFF9A) else Color(0xFFFFB74D), fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
