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
    data class Timer(val seconds: Int) : FridayAction
    data class Alarm(val hour: Int, val minute: Int) : FridayAction
    data class AlarmAfter(val seconds: Int) : FridayAction
    data class MapQuery(val query: String, val navigation: Boolean = false) : FridayAction
    data class DialNumber(val number: String) : FridayAction
    data class DialContact(val name: String) : FridayAction
    data class SmsContact(val name: String, val message: String) : FridayAction
    data class OpenApp(val packageName: String, val label: String) : FridayAction
    /** Explicit user-facing cross-app automation. Requires the user-enabled Accessibility bridge. */
    data class AccessibilityCommand(val command: String) : FridayAction
    /** Opens the device dialer for India's emergency number. Confirmation is mandatory. */
    data object EmergencySos : FridayAction
    data object RequestAssistantRole : FridayAction

    /** Ordered plan used for compound commands. Each action is executed at most once, in order. */
    data class Sequence(val actions: List<FridayAction>) : FridayAction {
        init { require(actions.isNotEmpty()) }
    }
}
