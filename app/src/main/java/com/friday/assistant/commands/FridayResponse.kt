package com.friday.assistant.commands

/** Structured result from the local planner. The launcher performs the actual Android hand-off. */
data class FridayResponse(
    val text: String,
    val action: FridayAction? = null,
    val needsConfirmation: Boolean = false
)

sealed interface FridayAction {
    data object YouTube : FridayAction
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
    data class MapQuery(val query: String, val navigation: Boolean = false) : FridayAction
    data class DialNumber(val number: String) : FridayAction
    data class DialContact(val name: String) : FridayAction
    data class SmsContact(val name: String, val message: String) : FridayAction
    data class OpenApp(val packageName: String, val label: String) : FridayAction
    data object RequestAssistantRole : FridayAction
}
