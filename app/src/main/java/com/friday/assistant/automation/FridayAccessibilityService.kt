package com.friday.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.friday.assistant.runtime.FridayRuntime

/**
 * Optional user-enabled automation bridge. It only acts after Android's Accessibility
 * permission has explicitly been granted by the owner.
 */
class FridayAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FridayRuntime.update("AUTOMATION READY", "Accessibility control is connected", true)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event?.packageName?.toString() != WHATSAPP_PACKAGE) return
        if (event.eventType != android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        pendingWhatsAppReply?.let { reply ->
            if (replyToWhatsApp(reply)) pendingWhatsAppReply = null
        }
    }

    fun replyToWhatsApp(replyText: String): Boolean {
        if (replyText.isBlank()) return false
        return try {
            val root = rootInActiveWindow ?: return false
            val entry = findByViewId(root, "$WHATSAPP_PACKAGE:id/entry")
                ?: findEditableNode(root)
                ?: return false
            val args = android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    replyText
                )
            }
            val setOk = entry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ||
                (entry.isFocused && entry.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
                    entry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            if (!setOk) return false

            val send = findByViewId(root, "$WHATSAPP_PACKAGE:id/send")
                ?: findNode(root, "Send")
                ?: findNode(root, "भेजें")
                ?: findNodeByDescription(root, "Send")
                ?: findNodeByDescription(root, "Send message")
                ?: return false
            performClick(send)
        } catch (_: Throwable) {
            false
        }
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNodeRecursive(root) { node ->
            node.isVisibleToUser && node.isEditable && node.isEnabled
        }

    private fun findByViewId(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        return try {
            root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull { it.isVisibleToUser }
        } catch (_: Throwable) {
            null
        }
    }

    override fun onInterrupt() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, text) ?: return false
        return performClick(node)
    }

    fun clickDescription(description: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByDescription(root, description) ?: return false
        return performClick(node)
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun setQuickSetting(labels: List<String>, desiredEnabled: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val wanted = labels.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        val node = findNodeRecursive(root) { candidate ->
            if (!candidate.isVisibleToUser) return@findNodeRecursive false
            val text = candidate.text?.toString()?.trim()?.lowercase().orEmpty()
            val desc = candidate.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            val haystack = "$text $desc"
            wanted.any { haystack.contains(it) }
        } ?: return false

        val current = readToggleState(node) ?: return false
        if (current == desiredEnabled) return true
        return performClick(node)
    }

    private fun readToggleState(node: AccessibilityNodeInfo): Boolean? {
        if (node.isChecked) return true
        val parent = node.parent
        if (parent?.isChecked == true) return true
        val stateText = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            parent?.text?.toString(),
            parent?.contentDescription?.toString()
        ).joinToString(" ").lowercase()
        return when {
            Regex("\b(on|enabled|active|turned on| चालू |चालू)\b").containsMatchIn(stateText) -> true
            Regex("\b(off|disabled|inactive|turned off| बंद |बंद)\b").containsMatchIn(stateText) -> false
            else -> null
        }
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(),
            null,
            mainHandler
        )
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        val target = generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
            ?: node
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findNode(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val wanted = text.trim().lowercase()
        if (wanted.isBlank()) return null
        val direct = root.findAccessibilityNodeInfosByText(text)
            .firstOrNull { it.isVisibleToUser }
        if (direct != null) return direct
        return findNodeRecursive(root) { node ->
            node.isVisibleToUser && node.text?.toString()?.trim()?.lowercase() == wanted
        }
    }

    private fun findNodeByDescription(root: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? =
        findNodeRecursive(root) { node ->
            node.isVisibleToUser && node.contentDescription?.toString()?.trim()?.equals(description.trim(), ignoreCase = true) == true
        }

    private fun findNodeRecursive(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeRecursive(child, predicate)
            if (found != null) return found
        }
        return null
    }

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        @Volatile private var pendingWhatsAppReply: String? = null
        @Volatile private var instance: FridayAccessibilityService? = null

        fun isConnected(): Boolean = instance != null
        fun clickText(text: String): Boolean = instance?.clickText(text) == true
        fun clickDescription(description: String): Boolean = instance?.clickDescription(description) == true
        fun goHome(): Boolean = instance?.goHome() == true
        fun goBack(): Boolean = instance?.goBack() == true
        fun openRecents(): Boolean = instance?.openRecents() == true
        fun openNotifications(): Boolean = instance?.openNotifications() == true
        fun openQuickSettings(): Boolean = instance?.openQuickSettings() == true
        fun setQuickSetting(labels: List<String>, desiredEnabled: Boolean): Boolean = instance?.setQuickSetting(labels, desiredEnabled) == true
        fun tap(x: Float, y: Float): Boolean = instance?.tap(x, y) == true
        fun replyToWhatsApp(replyText: String): Boolean {
            val service = instance ?: return false
            return if (service.replyToWhatsApp(replyText)) true else {
                pendingWhatsAppReply = replyText
                false
            }
        }
    }
}
