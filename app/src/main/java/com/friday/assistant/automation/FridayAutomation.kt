package com.friday.assistant.automation

/** Safe command bridge for explicit cross-app UI automation. */
object FridayAutomation {
    fun tryExecute(input: String): String? {
        if (!FridayAccessibilityService.isConnected()) return null
        val text = input.trim(); val lower = text.lowercase()
        return when {
            lower == "go home" || lower == "home" || lower == "ghar jao" -> if (FridayAccessibilityService.goHome()) "Home screen opened." else "I couldn't go home."
            lower == "go back" || lower == "back" || lower == "peeche jao" -> if (FridayAccessibilityService.goBack()) "Went back." else "I couldn't go back."
            lower == "open recents" || lower == "recent apps" -> if (FridayAccessibilityService.openRecents()) "Recent apps opened." else "I couldn't open recent apps."
            lower == "open notifications" || lower == "notifications kholo" -> if (FridayAccessibilityService.openNotifications()) "Notifications opened." else "I couldn't open notifications."
            lower == "scroll down" || lower == "scroll forward" -> if (FridayAccessibilityService.scrollForward()) "Scrolled down." else "I couldn't scroll down."
            lower == "scroll up" || lower == "scroll backward" -> if (FridayAccessibilityService.scrollBackward()) "Scrolled up." else "I couldn't scroll up."
            lower.startsWith("click ") -> {
                val target = text.substringAfter("click ").trim(); if (target.isBlank()) return null
                if (FridayAccessibilityService.clickText(target)) "Clicked $target." else "I couldn't find a visible control named $target."
            }
            lower.startsWith("click description ") -> {
                val target = text.substringAfter("click description ").trim(); if (target.isBlank()) return null
                if (FridayAccessibilityService.clickDescription(target)) "Clicked the requested control." else "I couldn't find that control."
            }
            lower.startsWith("type ") || lower.startsWith("enter ") -> {
                val value = text.substringAfter(' ').trim(); if (value.isBlank()) return null
                if (FridayAccessibilityService.setText(value)) "Text entered." else "I couldn't enter text into the active field."
            }
            lower.startsWith("tap ") -> {
                val parts = text.substringAfter("tap ").trim().split(Regex("\\s+")); if (parts.size != 2) return null
                val x = parts[0].toFloatOrNull() ?: return null; val y = parts[1].toFloatOrNull() ?: return null
                if (FridayAccessibilityService.tap(x, y)) "Tapped the requested screen position." else "I couldn't perform that tap."
            }
            else -> null
        }
    }
}
