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
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.util.Calendar
import androidx.core.content.ContextCompat
import com.friday.assistant.automation.FridayAutomation
import com.friday.assistant.runtime.FridayRuntime
import com.friday.assistant.ai.SecureApiKeyStore
import com.friday.assistant.integrations.FridayPollinationsService
import com.friday.assistant.integrations.FridayTermuxService

class AppLauncher(private val context: Context) {
    fun launch(action: FridayAction): Boolean = try {
        when (action) {
            is FridayAction.Sequence -> action.actions.all { launch(it) }
            FridayAction.YouTube -> openPackageOrUrl("com.google.android.youtube", "https://www.youtube.com")
            is FridayAction.YouTubeSearch -> openYouTubeSearch(action.query)
            is FridayAction.BrightnessSet -> setBrightnessPercent(action.percent)
            is FridayAction.MediaPlayPause -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            is FridayAction.MediaNext -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            is FridayAction.MediaPrevious -> dispatchMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            FridayAction.Calendar -> openCalendar()
            FridayAction.EmailCompose -> openEmailCompose()
            is FridayAction.BrightnessAdjust -> adjustBrightness(action.deltaPercent)
            is FridayAction.Wifi -> toggleQuickSetting("wifi", action.enabled)
            is FridayAction.MobileData -> toggleQuickSetting("mobile data", action.enabled)
            is FridayAction.PowerSaving -> toggleQuickSetting("power saving", action.enabled)
            is FridayAction.SpotifySearch -> openSpotifySearch(action.query)
            FridayAction.Calculator -> openCalculator()
            FridayAction.Settings -> start(Intent(Settings.ACTION_SETTINGS))
            FridayAction.Camera -> capturePhoto()
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
            is FridayAction.AlarmAfter -> setAlarmAfter(action.seconds)
            is FridayAction.Weather -> false
            is FridayAction.MapQuery -> {
                val uri = if (action.navigation) Uri.parse("google.navigation:q=${Uri.encode(action.query)}") else Uri.parse("geo:0,0?q=${Uri.encode(action.query)}")
                start(Intent(Intent.ACTION_VIEW, uri))
            }
            is FridayAction.DialNumber -> callNumber(action.number)
            is FridayAction.DialContact -> {
                val number = findUniqueContactNumber(action.name) ?: return false
                callNumber(number)
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
            FridayAction.RequestAssistantRole -> requestAssistantRole()
            is FridayAction.GenerateImage -> openGeneratedImage(action.prompt)
            is FridayAction.TermuxApi -> FridayTermuxService(context).run(action.command)
            is FridayAction.News, is FridayAction.AnalyzeMood -> false
        }
    } catch (_: SecurityException) { false } catch (_: Exception) { false }

    private fun start(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent); return true
    }

    private fun dispatchMediaKey(keyCode: Int): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
        audio.dispatchMediaKeyEvent(down)
        audio.dispatchMediaKeyEvent(up)
        return true
    }

    private fun openCalendar(): Boolean = start(Intent(Intent.ACTION_VIEW).apply { data = CalendarContract.CONTENT_URI })

    private fun openEmailCompose(): Boolean = start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")))

    private fun setBrightnessPercent(percent: Int): Boolean {
        val value = percent.coerceIn(0, 100)
        return try {
            if (!Settings.System.canWrite(context)) {
                start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
                return false
            }
            val brightness = (value * 255 / 100).coerceIn(0, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            true
        } catch (_: Throwable) { false }
    }

    private fun adjustBrightness(deltaPercent: Int): Boolean {
        return try {
            if (!Settings.System.canWrite(context)) {
                start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
                return false
            }
            val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val currentPercent = current * 100 / 255
            setBrightnessPercent((currentPercent + deltaPercent).coerceIn(0, 100))
        } catch (_: Throwable) { false }
    }

    private fun setAlarmAfter(seconds: Int): Boolean {
        if (seconds <= 0) return false
        val target = Calendar.getInstance().apply { add(Calendar.SECOND, seconds) }
        return start(Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, target.get(Calendar.HOUR_OF_DAY))
            putExtra(AlarmClock.EXTRA_MINUTES, target.get(Calendar.MINUTE))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        })
    }

    private fun toggleQuickSetting(kind: String, enabled: Boolean): Boolean {
        if (FridayAutomation.isConnected()) {
            val labels = when (kind) {
                "wifi" -> listOf("Wi-Fi", "WiFi", "वाई-फाई", "वाईफाई")
                "mobile data" -> listOf("Mobile data", "Mobile Data", "मोबाइल डेटा", "Data")
                else -> listOf("Power saving", "Power saving mode", "Battery saver", "Power Saver", "पावर सेविंग", "बैटरी सेवर")
            }
            if (FridayAutomation.openQuickSettings()) {
                if (FridayAutomation.setQuickSetting(labels, enabled)) return true
            }
        }
        val intent = when (kind) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "mobile data" -> Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
            else -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        }
        // Opening Settings is only a fallback for the user to complete manually.
        // Do not report it as if the requested toggle actually changed state.
        start(intent)
        return false
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
        if (!FridayAutomation.isConnected()) return false
        val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.whatsapp") }
        val opened = start(intent) || start(Intent(Intent.ACTION_VIEW, uri))
        if (!opened) return false
        scheduleWhatsAppReply(message, 1400L, 0)
        return true
    }

    private fun scheduleWhatsAppReply(message: String, delayMs: Long, attempt: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (FridayAutomation.replyToWhatsApp(message)) return@postDelayed
            if (attempt < 2) scheduleWhatsAppReply(message, 1400L, attempt + 1)
        }, delayMs)
    }

    private fun callNumber(number: String): Boolean {
        val clean = number.filter { it.isDigit() || it == '+' }
        if (clean.isBlank()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            FridayRuntime.update("CALL BLOCKED", "Phone call permission is required for direct calling.", false)
            return false
        }
        return start(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(clean)}")))
    }

    private fun requestAssistantRole(): Boolean = try {\n        if (android.os.Build.VERSION.SDK_INT < 29) return false\n        val roles = context.getSystemService(android.app.role.RoleManager::class.java) ?: return false\n        if (!roles.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) return false\n        start(roles.createRequestRoleIntent(android.app.role.RoleManager.ROLE_ASSISTANT))\n    } catch (_: Throwable) { false }\n\n    private fun capturePhoto(): Boolean = runCatching {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        val output = File(context.cacheDir, "friday_photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.packageManager.queryIntentActivities(this, PackageManager.MATCH_DEFAULT_ONLY).forEach {
                context.grantUriPermission(it.activityInfo.packageName, uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        start(intent)
    }.getOrDefault(false)

    private fun openGeneratedImage(prompt: String): Boolean = runCatching {
        val url = FridayPollinationsService(SecureApiKeyStore(context)).imageUrl(prompt)
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.getOrDefault(false)

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
