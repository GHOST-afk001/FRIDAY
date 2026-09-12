package com.friday.assistant.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat

/** System-level assistant service. Android keeps the selected assistant available for voice invocation. */
class FridayVoiceInteractionService : VoiceInteractionService() {
    private var wakeDetector: FridayWakeDetector? = null

    override fun onReady() {
        super.onReady()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            wakeDetector = FridayWakeDetector(applicationContext) { confidence ->
                val args = Bundle().apply { putFloat("friday_wake_confidence", confidence) }
                showSession(args, 0)
            }.also { it.start() }
        }
    }

    override fun onShutdown() {
        wakeDetector?.stop()
        wakeDetector = null
        super.onShutdown()
    }
}
