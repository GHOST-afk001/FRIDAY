package com.friday.assistant

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.friday.assistant.voice.FridayHandsFreeService

/** Starts FRIDAY's persistent hands-free engine once Android has granted microphone access. */
class FridayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                getSharedPreferences("friday_diagnostics", MODE_PRIVATE)
                    .edit()
                    .putLong("crash_time", System.currentTimeMillis())
                    .putString("crash_thread", thread.name)
                    .putString("crash_type", error.javaClass.name)
                    .putString("crash_message", error.message.orEmpty().take(1000))
                    .putString("crash_stack", Log.getStackTraceString(error).take(12000))
                    .apply()
            }
            previous?.uncaughtException(thread, error) ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, FridayHandsFreeService::class.java))
            }
        }
    }
}
