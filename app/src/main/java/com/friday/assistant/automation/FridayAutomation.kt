package com.friday.assistant.automation

/** Safe command bridge for explicit cross-app UI automation. */
object FridayAutomation {
    fun isConnected(): Boolean = FridayAccessibilityService.isConnected()

    fun clickSend(): Boolean =
        FridayAccessibilityService.clickResourceId("com.whatsapp:id/send") ||
        FridayAccessibilityService.clickText("Send") ||
        FridayAccessibilityService.clickText("send") ||
        FridayAccessibilityService.clickText("भेजें") ||
        FridayAccessibilityService.clickText("Bhej") ||
        FridayAccessibilityService.clickDescription("Send") ||
        FridayAccessibilityService.clickDescription("Send message") ||
        FridayAccessibilityService.clickDescription("भेजें")
    fun tryExecute(input: String): String? {
        if (!FridayAccessibilityService.isConnected()) return null
        val text = input.trim()
        val lower = text.lowercase()
        return when {
            lower == "go home" || lower == "home" || lower == "ghar jao" ->
                if (FridayAccessibilityService.goHome()) "Home screen opened." else "I couldn't go home."
            lower == "go back" || lower == "back" || lower == "peeche jao" ->
                if (FridayAccessibilityService.goBack()) "Went back." else "I couldn't go back."
            lower == "open recents" || lower == "recent apps" ->
                if (FridayAccessibilityService.openRecents()) "Recent apps opened." else "I couldn't open recent apps."
            lower == "open notifications" || lower == "notifications kholo" ->
                if (FridayAccessibilityService.openNotifications()) "Notifications opened." else "I couldn't open notifications."
            lower.startsWith("click ") -> {
                val target = text.substringAfter("click ", "").trim()
                if (target.isBlank()) return null
                if (FridayAccessibilityService.clickText(target)) "Clicked $target." else "I couldn't find a visible control named $target."
            }
            lower.startsWith("tap ") -> {
                val parts = text.substringAfter("tap ").trim().split(Regex("\\s+"))
                if (parts.size != 2) return null
                val x = parts[0].toFloatOrNull() ?: return null
                val y = parts[1].toFloatOrNull() ?: return null
                if (FridayAccessibilityService.tap(x, y)) "Tapped the requested screen position." else "I couldn't perform that tap."
            }
            lower.startsWith("type ") -> {
                val value = text.substringAfter("type ").trim()
                if (value.isBlank()) return null
                if (FridayAccessibilityService.setText(value)) "Typed the requested text." else "I couldn't find an editable field."
            }
            lower.startsWith("click_id ") -> {
                val id = text.substringAfter("click_id ").trim()
                if (id.isBlank()) return null
                if (FridayAccessibilityService.clickResourceId(id)) "Clicked the requested control." else "I couldn't find that control."
            }
            lower.startsWith("wait_click_type|") -> {
                val parts = text.substringAfter("wait_click_type|").split("|", limit = 2)
                if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!FridayAccessibilityService.clickText(parts[0]) &&
                        !FridayAccessibilityService.clickDescription(parts[0])) return@postDelayed
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        FridayAccessibilityService.setText(parts[1])
                    }, 350L)
                }, 1200L)
                "Opened the requested app and started its search flow."
            }
            lower == "scroll down" || lower == "scroll" || lower == "neeche scroll karo" ->
                if (FridayAccessibilityService.scrollForward()) "Scrolled down." else "I couldn't scroll the current screen."
            else -> null
        }
    }
}
