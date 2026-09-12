package com.friday.assistant.commands

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.media.AudioManager
import androidx.core.content.ContextCompat

/** Executes only public Android intents/APIs and reports whether the hand-off succeeded. */
class AppLauncher(private val context: Context) {
    fun launch(action: FridayAction): Boolean = try {
        when (action) {
            FridayAction.YouTube -> openPackageOrUrl("com.google.android.youtube", "https://www.youtube.com")
            FridayAction.Calculator -> start(Intent("android.intent.action.MAIN").addCategory("android.intent.category.APP_CALCULATOR"))
            FridayAction.Settings -> start(Intent(Settings.ACTION_SETTINGS))
            FridayAction.Camera -> start(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            FridayAction.Chrome -> openPackageOrUrl("com.android.chrome", "https://www.google.com")
            FridayAction.Messages -> openPackageOrUrl("com.google.android.apps.messaging", "sms:")
            FridayAction.WhatsApp -> openPackageOrUrl("com.whatsapp", "https://wa.me/")
            FridayAction.Instagram -> openPackageOrUrl("com.instagram.android", "https://www.instagram.com")
            FridayAction.FlashlightOn -> setTorch(true)
            FridayAction.FlashlightOff -> setTorch(false)
            FridayAction.VolumeUp -> adjustVolume(AudioManager.ADJUST_RAISE)
            FridayAction.VolumeDown -> adjustVolume(AudioManager.ADJUST_LOWER)
            is FridayAction.Timer -> start(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            })
            is FridayAction.Alarm -> start(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            })
            is FridayAction.MapQuery -> {
                val uri = if (action.navigation) Uri.parse("google.navigation:q=${Uri.encode(action.query)}")
                else Uri.parse("geo:0,0?q=${Uri.encode(action.query)}")
                start(Intent(Intent.ACTION_VIEW, uri))
            }
            is FridayAction.DialNumber -> start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${action.number}")))
            is FridayAction.DialContact -> {
                val number = findUniqueContactNumber(action.name) ?: return false
                start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
            }
            is FridayAction.SmsContact -> {
                val number = findUniqueContactNumber(action.name) ?: return false
                start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
                    putExtra("sms_body", action.message)
                })
            }
            is FridayAction.OpenApp -> openPackageOrUrl(action.packageName, null)
            FridayAction.RequestAssistantRole -> false
        }
    } catch (_: Exception) { false }

    private fun start(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        return true
    }

    private fun openPackageOrUrl(packageName: String, fallbackUrl: String?): Boolean {
        val app = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (app != null) start(app) else fallbackUrl?.let { start(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } ?: false
    }

    private fun adjustVolume(direction: Int): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustSuggestedStreamVolume(direction, AudioManager.STREAM_MUSIC, AudioManager.FLAG_SHOW_UI)
        return true
    }

    private fun setTorch(enabled: Boolean): Boolean {
        val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return false
        camera.setTorchMode(id, enabled)
        return true
    }

    /** Resolve only an exact match or a single unambiguous partial match. */
    private fun findUniqueContactNumber(name: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val requested = name.trim()
        if (requested.isBlank()) return null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val exactSelection = "LOWER(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}) = ?"
        val exact = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, exactSelection, arrayOf(requested.lowercase()), null
        )?.use { cursor ->
            val numbers = mutableListOf<String>()
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && numberIndex >= 0) numbers += cursor.getString(numberIndex)
            numbers.distinct().singleOrNull()
        }
        if (exact != null) return exact

        val partialSelection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        return context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, partialSelection, arrayOf("%$requested%"), null
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (numberIndex < 0) return@use null
            val numbers = mutableSetOf<String>()
            while (cursor.moveToNext()) numbers += cursor.getString(numberIndex)
            numbers.singleOrNull()
        }
    }
}
