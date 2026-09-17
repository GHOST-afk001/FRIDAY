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

/**
 * Safe launcher: once microphone permission is granted, hands-free voice starts automatically.
 * No tap is required for normal voice operation.
 */
class FridayHudActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            setBackgroundColor(Color.rgb(4, 2, 3))
            setTextColor(Color.rgb(255, 137, 48))
            textSize = 22f
            gravity = Gravity.CENTER
            text = "FRIDAY\n\nREADY\n\nHands-free voice standby"
            setPadding(32, 32, 32, 32)
        }
        setContentView(status)
        runCatching { FridayRuntime.update("READY", "FRIDAY started safely", true) }
        startHandsFreeAutomatically()
    }

    private fun startHandsFreeAutomatically() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "FRIDAY\n\nMICROPHONE ACCESS REQUIRED\n\nAllow it once — voice will then start automatically"
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        startVoiceService()
    }

    private fun startVoiceService() {
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, FridayHandsFreeService::class.java))
            status.text = "FRIDAY\n\nHANDS-FREE ACTIVE\n\nListening for voice commands..."
        }.onFailure {
            status.text = "FRIDAY\n\nVOICE START FAILED\n\n${it.javaClass.simpleName}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceService()
        } else if (requestCode == REQUEST_MIC) {
            status.text = "FRIDAY\n\nMICROPHONE ACCESS REQUIRED\n\nVoice standby cannot start without it"
        }
    }

    companion object { private const val REQUEST_MIC = 7101 }
}
