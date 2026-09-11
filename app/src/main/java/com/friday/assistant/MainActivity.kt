package com.friday.assistant

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.friday.assistant.commands.AppLauncher
import com.friday.assistant.commands.FridayCommandProcessor
import com.friday.assistant.voice.TTSManager
import com.friday.assistant.voice.VoiceManager

class MainActivity : ComponentActivity() {
    private lateinit var voiceManager: VoiceManager
    private lateinit var ttsManager: TTSManager
    private val processor = FridayCommandProcessor()
    private var startListening: (() -> Unit)? = null
    private var statusUpdater: ((String) -> Unit)? = null

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening?.invoke() else updateStatus("Microphone permission was denied. Enable it in Android Settings to speak with Friday.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FridayApp() }
    }

    private fun updateStatus(message: String) { statusUpdater?.invoke(message) }

    @Composable
    private fun FridayApp() {
        var status by remember { mutableStateOf("Tap the microphone and speak") }
        var recognized by remember { mutableStateOf("") }
        var response by remember { mutableStateOf("Hello. Main Friday hoon. Bataiye, kya karna hai?") }
        val appLauncher = remember { AppLauncher(applicationContext) }
        SideEffect { statusUpdater = { status = it } }

        DisposableEffect(Unit) {
            ttsManager = TTSManager(applicationContext) { status = "Text-to-speech is unavailable on this device." }
            voiceManager = VoiceManager(applicationContext, object : VoiceManager.Listener {
                override fun onListening() { status = "Listening..." }
                override fun onResult(text: String) {
                    recognized = text
                    if (text.isBlank()) { status = "I could not hear that. Please try again."; return }
                    val result = processor.process(text)
                    response = result.text
                    status = "Tap the microphone and speak"
                    ttsManager.speak(result.text)
                    result.action?.let { if (!appLauncher.launch(it)) { response = "I couldn't open that app on this device."; ttsManager.speak(response) } }
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
                    Spacer(Modifier.height(10.dp))
                    Text("PERSONAL AI ASSISTANT • DEMO V1", color = Color(0xFF8DA3B8), fontSize = 11.sp)
                    Spacer(Modifier.height(52.dp))
                    Button(onClick = ::requestOrStart, modifier = Modifier.size(176.dp).clip(CircleShape).border(3.dp, Color(0xFF32D5FF), CircleShape), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D2B3A))) {
                        Text("◉\nTAP TO SPEAK", textAlign = TextAlign.Center, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(status, color = Color(0xFFB8D6E3), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(32.dp))
                    AssistantCard("YOU", recognized.ifBlank { "Your words will appear here." }, Color(0xFF8DA3B8))
                    Spacer(Modifier.height(14.dp))
                    AssistantCard("FRIDAY", response, Color(0xFF32D5FF))
                }
            }
        }
    }

    @Composable
    private fun AssistantCard(label: String, message: String, accent: Color) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) { Text(label, color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp); Spacer(Modifier.height(6.dp)); Text(message, color = Color(0xFFE1F1F7)) }
        }
    }
}
