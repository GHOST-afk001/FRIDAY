package com.friday.assistant

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.assistant.ai.FridayAgent
import com.friday.assistant.power.FridayPowerManager
import com.friday.assistant.runtime.DeviceSnapshot
import com.friday.assistant.runtime.DeviceTelemetry
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.runtime.FridayStateFlow
import com.friday.assistant.runtime.RuntimeStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Voice-first FRIDAY HUD. Conversation is intentionally hands-free: there is no tap-to-speak
 * control. The Android Assistant/VoiceInteractionService owns wake and speech interaction.
 */
class FridayHudActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FridayHudScreen() }
    }

    @Composable
    private fun FridayHudScreen() {
        val uiState by FridayStateFlow.state.collectAsState()
        var telemetry by remember { mutableStateOf(DeviceTelemetry.snapshot(applicationContext)) }
        var runtime by remember { mutableStateOf(FridayRuntime.status) }
        var geminiReady by remember { mutableStateOf(false) }

        DisposableEffect(Unit) {
            val handler = Handler(Looper.getMainLooper())
            val refresh = object : Runnable {
                override fun run() {
                    telemetry = DeviceTelemetry.snapshot(applicationContext)
                    geminiReady = FridayAgent(applicationContext).useAndClose { it.hasApiKey() }
                    handler.postDelayed(this, 1000L)
                }
            }
            geminiReady = FridayAgent(applicationContext).useAndClose { it.hasApiKey() }
            handler.post(refresh)
            val subscription = FridayRuntime.observe { runtime = it }
            onDispose {
                handler.removeCallbacks(refresh)
                subscription.close()
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
                    Header(telemetry, geminiReady, runtime)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        FridayDynamicOrb(
                            state = uiState,
                            modifier = Modifier.size(290.dp)
                        )
                    }
                    StatusPanel(runtime, geminiReady, telemetry)
                    Spacer(Modifier.height(10.dp))
                    VoiceStandby(runtime)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "FRIDAY • VOICE-FIRST PERSONAL INTELLIGENCE",
                        color = Color(0xFF45616D),
                        fontSize = 8.sp,
                        letterSpacing = 1.6.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    @Composable
    private fun Header(telemetry: DeviceSnapshot, geminiReady: Boolean, runtime: RuntimeStatus) {
        val gradient = Brush.horizontalGradient(
            listOf(Color(0xFF07131D), Color(0xFF15080F), Color(0xFF07151B))
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
                .background(gradient, RoundedCornerShape(18.dp))
                .border(1.dp, Color(0xFF173B49), RoundedCornerShape(18.dp))
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("FRIDAY", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 7.sp)
                        Text("ULTRON-INSPIRED PERSONAL INTELLIGENCE", color = Color(0xFF718B98), fontSize = 8.sp, letterSpacing = 1.5.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${telemetry.batteryPercent}%", color = if (telemetry.charging) Color(0xFF4CFF9A) else Color(0xFF35E8FF), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(if (telemetry.charging) "CHARGING" else "ON BATTERY", color = Color(0xFF708994), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (geminiReady) "GEMINI 3.6 FLASH • CONNECTED" else "GEMINI 3.6 FLASH • KEY REQUIRED",
                        color = if (geminiReady) Color(0xFF4CFF9A) else Color(0xFFFFB74D),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(runtime.stage, color = if (runtime.healthy) Color(0xFF35E8FF) else Color(0xFFFF5266), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun StatusPanel(runtime: RuntimeStatus, geminiReady: Boolean, telemetry: DeviceSnapshot) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF070D16)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF14313F), RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.padding(13.dp)) {
                Text("FRIDAY CORE", color = Color(0xFF35E8FF), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    runtime.detail,
                    color = Color(0xFFD8F6FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatusPill("VOICE", runtime.stage.contains("LISTEN") || runtime.stage.contains("WAKE") || runtime.stage == "IDLE")
                    StatusPill("AI", geminiReady)
                    StatusPill("SYSTEM", runtime.healthy)
                    StatusPill("BATTERY", telemetry.batteryPercent > 15 || telemetry.charging)
                }
            }
        }
    }

    @Composable
    private fun VoiceStandby(runtime: RuntimeStatus) {
        val active = runtime.stage.contains("LISTEN") || runtime.stage.contains("WAKE") || runtime.stage.contains("SPEAKER")
        Text(
            if (active) "● VOICE ACTIVE • SPEAK NOW" else "● HANDS-FREE STANDBY • SAY “HEY FRIDAY”",
            color = if (active) Color(0xFF4CFF9A) else Color(0xFF35E8FF),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            textAlign = TextAlign.Center
        )
        Text(
            "No tap-to-speak control • Android Assistant wake path",
            color = Color(0xFF526B76),
            fontSize = 8.sp,
            textAlign = TextAlign.Center
        )
    }

    @Composable
    private fun StatusPill(label: String, active: Boolean) {
        Text(
            "${if (active) "●" else "○"} $label",
            color = if (active) Color(0xFF4CFF9A) else Color(0xFF536B77),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }

    private fun <T> FridayAgent.useAndClose(block: (FridayAgent) -> T): T {
        return try { block(this) } finally { close() }
    }
}
