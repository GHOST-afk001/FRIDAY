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
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.friday.assistant.automation.FridayAutomation
import com.friday.assistant.runtime.FridayRuntime

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
            is FridayAction.Timer -> start(Intent(AlarmClock.ACTION_SET_TIMER).apply { putExtra(AlarmClock.EXTRA_LENGTH, action.seconds); putExtra(AlarmClock.EXTRA_SKIP_UI, true) })
            is FridayAction.Alarm -> start(Intent(AlarmClock.ACTION_SET_ALARM).apply { putExtra(AlarmClock.EXTRA_HOUR, action.hour); putExtra(AlarmClock.EXTRA_MINUTES, action.minute); putExtra(AlarmClock.EXTRA_SKIP_UI, true) })
            is FridayAction.AlarmAfter -> start(Intent(AlarmClock.ACTION_SET_TIMER).apply { putExtra(AlarmClock.EXTRA_LENGTH, action.seconds); putExtra(AlarmClock.EXTRA_SKIP_UI, true) })
            is FridayAction.MapQuery -> {
                val uri = if (action.navigation) Uri.parse("google.navigation:q=${Uri.encode(action.query)}") else Uri.parse("geo:0,0?q=${Uri.encode(action.query)}")
                start(Intent(Intent.ACTION_VIEW, uri))
            }
            is FridayAction.DialNumber -> start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${action.number}")))
            is FridayAction.DialContact -> {
                val number = findUniqueContactNumber(action.name) ?: return false
                val clean = number.filter { it.isDigit() || it == '+' }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    start(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(clean)}")))
                } else {
                    start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(clean)}")))
                }
            }
            is FridayAction.SmsContact -> {
                val number = findUniqueContactNumber(action.name) ?: return false
                start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply { putExtra("sms_body", action.message) })
            }
            is FridayAction.OpenApp -> {
                if (action.packageName.startsWith("__web_search__:")) openWebSearch(action.packageName.removePrefix("__web_search__:"))
                else openInstalledApp(action.packageName, action.label)
            }
            is FridayAction.AccessibilityCommand -> {
                val command = action.command.trim()
                when {
                    command.startsWith("whatsapp_ui|") -> openWhatsAppUiTask(command)
                    command.startsWith("whatsapp_message|") -> openWhatsAppMessage(command)
                    else -> {
                        val result = FridayAutomation.tryExecute(command)
                        if (result != null) true else {
                            FridayRuntime.update("AUTOMATION BLOCKED", "Enable FRIDAY Accessibility access in Android settings.", false)
                            false
                        }
                    }
                }
            }
            FridayAction.EmergencySos -> start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
            FridayAction.RequestAssistantRole -> false
        }
    } catch (_: SecurityException) { false } catch (_: Exception) { false }

    private fun start(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent); return true
    }

    private fun openPackageOrUrl(packageName: String, fallbackUrl: String?): Boolean = openInstalledApp(packageName, packageName) || (fallbackUrl?.let { start(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } ?: false)

    private fun openInstalledApp(packageOrLabel: String, label: String): Boolean {
        val direct = context.packageManager.getLaunchIntentForPackage(packageOrLabel)
        if (direct != null) return start(direct)
        val wanted = label.trim().ifBlank { packageOrLabel.trim() }.lowercase()
        if (wanted.isBlank()) return false
        val apps = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
        val match = apps.firstOrNull { info ->
            val appLabel = info.loadLabel(context.packageManager).toString().lowercase()
            appLabel == wanted || appLabel.contains(wanted) || wanted.contains(appLabel)
        } ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        return start(launch)
    }

    private fun openWebSearch(query: String): Boolean {
        val url = "https://www.google.com/search?q=${Uri.encode(query.trim())}"
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun openYouTubeSearch(query: String): Boolean {
        val encoded = Uri.encode(query)
        val youtubeSearch = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", query)
        }
        if (start(youtubeSearch)) return true
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://results?search_query=$encoded")).apply { setPackage("com.google.android.youtube") }
        if (start(deepLink)) return true
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

    private fun openWhatsAppUiTask(command: String): Boolean {
        val parts = command.split('|', limit = 3)
        if (parts.size != 3) return false
        val name = parts[1].trim()
        val message = parts[2].trim()
        if (name.isBlank() || message.isBlank()) return false
        if (!FridayAutomation.isConnected()) {
            FridayRuntime.update("AUTOMATION BLOCKED", "Enable FRIDAY Accessibility access before WhatsApp automation.", false)
            return false
        }
        val opened = openPackageOrUrl("com.whatsapp", "https://www.whatsapp.com")
        if (!opened) return false
        com.friday.assistant.automation.FridayAccessibilityService.queueWhatsAppUiTask(name, message)
        FridayRuntime.update("WHATSAPP AUTOMATION", "Finding $name and preparing the message", true)
        return true
    }

    private fun openWhatsAppMessage(command: String): Boolean {
        val parts = command.split('|', limit = 3)
        if (parts.size != 3) return false
        val name = parts[1].trim(); val message = parts[2].trim()
        if (name.isBlank() || message.isBlank()) return false
        val number = findUniqueContactNumber(name) ?: return false
        var phone = number.filter { it.isDigit() }
        if (phone.length == 10) phone = "91$phone"
        if (phone.isBlank()) return false
        val uri = Uri.parse("https://wa.me/$phone?text=${Uri.encode(message)}")
        if (!FridayAutomation.isConnected()) return false
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") }
        val opened = start(intent) || start(Intent(Intent.ACTION_VIEW, uri))
        if (!opened) return false
        // Queue the message so Accessibility events can retry after WhatsApp has
        // actually rendered the chat. This is more reliable than a single timed click.
        com.friday.assistant.automation.FridayAccessibilityService.queueWhatsAppMessage(message)
        val handler = Handler(Looper.getMainLooper())
        val sendDeadline = System.currentTimeMillis() + 12000L
        val trySend = object : Runnable {
            override fun run() {
                if (!FridayAutomation.clickSend() && System.currentTimeMillis() < sendDeadline) {
                    handler.postDelayed(this, 500L)
                }
            }
        }
        handler.postDelayed(trySend, 1200L)
        return true
    }

    private fun openCalculator(): Boolean {
        val selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
        if (start(selector)) return true
        val known = listOf("com.sec.android.app.popupcalculator", "com.samsung.android.calculator", "com.google.android.calculator")
        return known.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }?.let { start(context.packageManager.getLaunchIntentForPackage(it)!!) } ?: false
    }

    private fun adjustVolume(direction: Int): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustSuggestedStreamVolume(direction, AudioManager.STREAM_MUSIC, AudioManager.FLAG_SHOW_UI); return true
    }

    private fun setTorch(enabled: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingTorchRequest = enabled
            // A background service cannot show a runtime permission dialog itself. Bring the
            // FRIDAY HUD forward so Android can present the normal camera permission prompt.
            runCatching {
                val intent = Intent(context, com.friday.assistant.FridayHudActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(com.friday.assistant.FridayHudActivity.EXTRA_REQUEST_CAMERA_PERMISSION, true)
                }
                context.startActivity(intent)
                FridayRuntime.update("CAMERA PERMISSION", "Allow camera access once to control the flashlight.", false)
            }
            return false
        }
        val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return false
        camera.setTorchMode(id, enabled)
        return true
    }

    companion object {
        @Volatile private var pendingTorchRequest: Boolean? = null

        fun resumePendingTorch(context: Context): Boolean {
            val request = pendingTorchRequest ?: return false
            pendingTorchRequest = null
            return AppLauncher(context.applicationContext).launch(if (request) FridayAction.FlashlightOn else FridayAction.FlashlightOff)
        }
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
