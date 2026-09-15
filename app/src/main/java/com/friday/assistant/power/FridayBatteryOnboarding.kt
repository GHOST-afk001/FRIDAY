package com.friday.assistant.power

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * User-facing battery/background reliability guidance for FRIDAY.
 *
 * Android exposes the optimization allowlist through PowerManager. Samsung One UI
 * may additionally apply its own background limits, so FRIDAY never pretends that
 * the Android allowlist alone guarantees unrestricted background execution.
 */
object FridayBatteryOnboarding {
    data class Status(
        val manufacturer: String,
        val ignoringOptimization: Boolean,
        val needsUserAction: Boolean,
        val title: String,
        val detail: String,
        val steps: List<String>
    )

    fun status(context: Context): Status {
        val ignoring = FridayPowerManager.isIgnoringBatteryOptimizations(context)
        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        return if (ignoring) {
            Status(
                manufacturer = Build.MANUFACTURER,
                ignoringOptimization = true,
                needsUserAction = false,
                title = "Battery protection: unrestricted",
                detail = if (samsung) {
                    "Android battery optimization is unrestricted. Samsung One UI can still apply separate background limits."
                } else {
                    "Android battery optimization is unrestricted for FRIDAY."
                },
                steps = if (samsung) samsungSteps() else emptyList()
            )
        } else {
            Status(
                manufacturer = Build.MANUFACTURER,
                ignoringOptimization = false,
                needsUserAction = true,
                title = "Battery protection may stop hands-free FRIDAY",
                detail = if (samsung) {
                    "For reliable Hey Friday background operation, allow FRIDAY to use battery without optimization and review Samsung's background battery setting."
                } else {
                    "Allow FRIDAY to ignore battery optimization if you want more reliable background hands-free operation."
                },
                steps = if (samsung) samsungSteps() else listOf(
                    "Open the Android battery-optimization screen for FRIDAY.",
                    "Allow FRIDAY to use battery without optimization.",
                    "Return to FRIDAY and verify SYSTEM HEALTH shows UNRESTRICTED."
                )
            )
        }
    }

    private fun samsungSteps(): List<String> = listOf(
        "Allow FRIDAY to use battery without optimization when Android asks.",
        "In Samsung Settings, open Apps → FRIDAY → Battery.",
        "Select Unrestricted if that option is available on your One UI version.",
        "Return to FRIDAY and verify SYSTEM HEALTH shows UNRESTRICTED."
    )

    /** Prefer the one-app Android exemption dialog, then fall back to general settings. */
    fun createRecommendedIntent(context: Context): Intent {
        return FridayPowerManager.createOptimizationIntent(context)
            ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /** Opens FRIDAY's app details page for Samsung's per-app Battery screen. */
    fun createAppDetailsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )

    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
}
