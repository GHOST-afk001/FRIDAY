package com.friday.assistant.commands

data class FridayResponse(
    val text: String,
    val action: FridayAction? = null,
    val needsConfirmation: Boolean = false,
    val handledLocally: Boolean = true
)

sealed interface FridayAction {
    data object YouTube : FridayAction
    data class YouTubeSearch(val query: String) : FridayAction
    data class SpotifySearch(val query: String) : FridayAction
    data object Calculator : FridayAction
    data object Settings : FridayAction
    data object Camera : FridayAction
    data object Chrome : FridayAction
    data object Messages : FridayAction
    data object WhatsApp : FridayAction
    data object Instagram : FridayAction
    data object FlashlightOn : FridayAction
    data object FlashlightOff : FridayAction
    data object VolumeUp : FridayAction
    data object VolumeDown : FridayAction
    data object MediaPlayPause : FridayAction
    data object MediaNext : FridayAction
    data object MediaPrevious : FridayAction
    data object Calendar : FridayAction
    data object EmailCompose : FridayAction
    data class BrightnessSet(val percent: Int) : FridayAction
    data class BrightnessAdjust(val deltaPercent: Int) : FridayAction
    data class Wifi(val enabled: Boolean) : FridayAction
    data class MobileData(val enabled: Boolean) : FridayAction
    data class PowerSaving(val enabled: Boolean) : FridayAction
    data class Timer(val seconds: Int) : FridayAction
    data class Alarm(val hour: Int, val minute: Int) : FridayAction
    data class AlarmAfter(val seconds: Int) : FridayAction
    data class MapQuery(val query: String, val navigation: Boolean = false) : FridayAction
    data class Weather(val location: String? = null, val dateIso: String? = null) : FridayAction
    data class DialNumber(val number: String) : FridayAction
    data class DialContact(val name: String) : FridayAction
    data class SmsContact(val name: String, val message: String) : FridayAction
    data class OpenApp(val packageName: String, val label: String) : FridayAction
    data class AccessibilityCommand(val command: String) : FridayAction
    data object EmergencySos : FridayAction
    data object RequestAssistantRole : FridayAction
    data class Sequence(val actions: List<FridayAction>) : FridayAction {
        init { require(actions.isNotEmpty()) }
    }
}
