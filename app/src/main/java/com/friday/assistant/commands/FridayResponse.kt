package com.friday.assistant.commands

/** Result of an offline command. Actions are executed separately by AppLauncher. */
data class FridayResponse(val text: String, val action: FridayAction? = null)

enum class FridayAction { YOUTUBE, CALCULATOR, SETTINGS, CAMERA }
