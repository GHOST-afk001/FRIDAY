package com.friday.assistant.commands

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings

/** Opens device apps through public intents, returning false instead of crashing when absent. */
class AppLauncher(private val context: Context) {
    fun launch(action: FridayAction): Boolean = try {
        val intent = when (action) {
            FridayAction.YOUTUBE -> context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
            FridayAction.CALCULATOR -> Intent("android.intent.action.MAIN").apply {
                addCategory("android.intent.category.APP_CALCULATOR")
            }
            FridayAction.SETTINGS -> Intent(Settings.ACTION_SETTINGS)
            FridayAction.CAMERA -> Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) { false }
}
