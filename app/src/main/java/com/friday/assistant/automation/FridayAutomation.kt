package com.friday.assistant.automation

/** Safe command bridge for explicit cross-app UI automation. */
object FridayAutomation {
    fun isConnected(): Boolean = FridayAccessibilityService.isConnected()

    fun openQuickSettings(): Boolean = FridayAccessibilityService.openQuickSettings()
    fun setQuickSetting(labels: List<String>, desiredEnabled: Boolean): Boolean = FridayAccessibilityService.setQuickSetting(labels, desiredEnabled)
    fun clickVisibleText(text: String): Boolean = FridayAccessibilityService.clickText(text)
    fun scroll(direction: String): Boolean = FridayAccessibilityService.scroll(direction)
    fun typeText(value: String): Boolean = FridayAccessibilityService.typeText(value)
    fun swipe(direction: String): Boolean = FridayAccessibilityService.swipe(direction)

    fun clickSend(): Boolean = FridayAccessibilityService.clickText("Send") || FridayAccessibilityService.clickText("भेजें") || FridayAccessibilityService.clickDescription("Send")

    fun replyToWhatsApp(message: String): Boolean = FridayAccessibilityService.replyToWhatsApp(message)
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
            lower.startsWith("type ") || lower.startsWith("text ") -> {
                val value = text.substringAfter(' ').trim()
                if (value.isBlank()) return null
                if (FridayAccessibilityService.typeText(value)) "Typed the requested text." else "I couldn't find an editable field."
            }
            lower == "scroll up" || lower == "upar scroll karo" ->
                if (FridayAccessibilityService.scroll("up")) "Scrolled up." else "I couldn't scroll the current screen."
            lower == "scroll down" || lower == "neeche scroll karo" ->
                if (FridayAccessibilityService.scroll("down")) "Scrolled down." else "I couldn't scroll the current screen."
            lower == "swipe up" ->
                if (FridayAccessibilityService.swipe("up")) "Swiped up." else "I couldn't perform that swipe."
            lower == "swipe down" ->
                if (FridayAccessibilityService.swipe("down")) "Swiped down." else "I couldn't perform that swipe."
            lower.startsWith("tap ") -> {
                val parts = text.substringAfter("tap ").trim().split(Regex("\\s+"))
                if (parts.size != 2) return null
                val x = parts[0].toFloatOrNull() ?: return null
                val y = parts[1].toFloatOrNull() ?: return null
                if (FridayAccessibilityService.tap(x, y)) "Tapped the requested screen position." else "I couldn't perform that tap."
            }
            else -> null
        }
    }
}
