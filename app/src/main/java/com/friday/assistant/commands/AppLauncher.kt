package com.friday.assistant.commands

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.friday.assistant.automation.FridayAutomation
import com.friday.assistant.runtime.FridayRuntime

/** Executes Android intents/APIs plus the explicitly user-enabled Accessibility bridge. */
class AppLauncher(private val context: Context) {
    fun launch(action: FridayAction): Boolean = try {
        when (action) {
            is FridayAction.Sequence -> action.actions.all { launch(it) }
            FridayAction.YouTube -> openPackageOrUrl("com.google.android.youtube", "https://www.youtube.com")
            is FridayAction.YouTubeSearch -> openYouTubeSearch(action.query)
            is FridayAction.SpotifySearch -> openSpotifySearch(action.query)
            FridayAction.Calculator -> openCalculator()
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
            is FridayAction.AlarmAfter -> start(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
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
            is FridayAction.OpenApp -> openInstalledApp(action.packageName, action.label)
            is FridayAction.AccessibilityCommand -> {
                val command = action.command.trim()
                if (command.startsWith("whatsapp_message|")) openWhatsAppMessage(command)
                else {
                    val result = FridayAutomation.tryExecute(command)
                    if (result != null) true else {
                        FridayRuntime.update("AUTOMATION BLOCKED", "Enable FRIDAY Accessibility access in Android settings.", false)
                        false
                    }
                }
            }
            FridayAction.EmergencySos -> start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
            FridayAction.RequestAssistantRole -> false
        }
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }

    private fun start(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        return true
    }

    private fun openPackageOrUrl(packageName: String, fallbackUrl: String?): Boolean =
        openInstalledApp(packageName, packageName) || (fallbackUrl?.let { start(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } ?: false)

    private fun openInstalledApp(packageOrLabel: String, label: String): Boolean {
        val direct = context.packageManager.getLaunchIntentForPackage(packageOrLabel)
        if (direct != null) return start(direct)
        val wanted = label.trim().ifBlank { packageOrLabel.trim() }.lowercase()
        if (wanted.isBlank()) return false
        val apps = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.MATCH_ALL
        )
        val match = apps.firstOrNull { info ->
            val appLabel = info.loadLabel(context.packageManager).toString().lowercase()
            appLabel == wanted || appLabel.contains(wanted) || wanted.contains(appLabel)
        } ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        return start(launch)
    }

    private fun openYouTubeSearch(query: String): Boolean {
        val encoded = Uri.encode(query)
        val youtube = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
        if (youtube != null) {
            val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://results?search_query=$encoded")).apply { setPackage("com.google.android.youtube") }
            if (start(deepLink)) return true
        }
        return start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")))
    }

    private fun openSpotifySearch(query: String): Boolean {
        val encoded = Uri.encode(query)
        val spotify = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (spotify != null) {
            val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded")).apply { setPackage("com.spotify.music") }
            if (start(deepLink)) return true
        }
        return start(Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$encoded")))
    }

    private fun openWhatsAppMessage(command: String): Boolean {
        val parts = command.split('|', limit = 3)
        if (parts.size != 3) return false
        val name = parts[1].trim(); val message = parts[2].trim()
        if (name.isBlank() || message.isBlank()) return false
        val number = findUniqueContactNumber(name) ?: return false
        val phone = number.filter { it.isDigit() }
        if (phone.isBlank()) return false
        val uri = Uri.parse("https://wa.me/$phone?text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") }
        return if (start(intent)) true else start(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun openCalculator(): Boolean {
        val selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
        if (start(selector)) return true
        val known = listOf("com.sec.android.app.popupcalculator", "com.samsung.android.calculator", "com.google.android.calculator")
        return known.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }
            ?.let { start(context.packageManager.getLaunchIntentForPackage(it)!!) } ?: false
    }

    private fun adjustVolume(direction: Int): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustSuggestedStreamVolume(direction, AudioManager.STREAM_MUSIC, AudioManager.FLAG_SHOW_UI)
        return true
    }

    private fun setTorch(enabled: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return false
        camera.setTorchMode(id, enabled); return true
    }

    private fun findUniqueContactNumber(name: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val requested = name.trim(); if (requested.isBlank()) return null
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val exactSelection = "LOWER(${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}) = ?"
        val exact = context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, exactSelection, arrayOf(requested.lowercase()), null)?.use { cursor ->
            val numbers = mutableListOf<String>(); val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && numberIndex >= 0) numbers += cursor.getString(numberIndex)
            numbers.distinct().singleOrNull()
        }
        if (exact != null) return exact
        val partialSelection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        return context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, partialSelection, arrayOf("%$requested%"), null)?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER); if (numberIndex < 0) return@use null
            val numbers = mutableSetOf<String>(); while (cursor.moveToNext()) numbers += cursor.getString(numberIndex); numbers.singleOrNull()
        }
    }
}
