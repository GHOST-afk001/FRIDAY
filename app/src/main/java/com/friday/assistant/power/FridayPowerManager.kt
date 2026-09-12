package com.friday.assistant.power

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Battery state helper. Exemption is optional and always user-controlled. */
object FridayPowerManager {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Returns the one-app exemption dialog only when the manifest declares the
     * corresponding permission. Otherwise the caller should use general battery settings.
     */
    fun createOptimizationIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || isIgnoringBatteryOptimizations(context)) return null
        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) return null
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun createBatterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
