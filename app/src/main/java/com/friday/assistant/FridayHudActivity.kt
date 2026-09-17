package com.friday.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.voice.FridayHandsFreeService

/** Crash-safe launcher. No custom HUD, telemetry, coroutines, immersive flags, or services are touched during startup. */
class FridayHudActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            setBackgroundColor(Color.rgb(4, 2, 3))
            setTextColor(Color.rgb(255, 137, 48))
            textSize = 22f
            gravity = Gravity.CENTER
            text = "FRIDAY\n\nREADY\n\nTap here to activate voice"
            setPadding(32, 32, 32, 32)
            isClickable = true
            setOnClickListener { activateVoice() }
        }
        setContentView(status)
        runCatching { FridayRuntime.update("READY", "FRIDAY started safely", true) }
    }

    private fun activateVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        startVoiceService()
    }

    private fun startVoiceService() {
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, FridayHandsFreeService::class.java))
            status.text = "FRIDAY\n\nSTARTING VOICE..."
        }.onFailure {
            status.text = "FRIDAY\n\nVOICE START FAILED\n\n${it.javaClass.simpleName}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startVoiceService()
        else status.text = "FRIDAY\n\nMICROPHONE PERMISSION REQUIRED"
    }

    companion object { private const val REQUEST_MIC = 7101 }
}
