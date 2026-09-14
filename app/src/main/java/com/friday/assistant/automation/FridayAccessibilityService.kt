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

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

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
        @Volatile private var instance: FridayAccessibilityService? = null

        fun isConnected(): Boolean = instance != null
        fun clickText(text: String): Boolean = instance?.clickText(text) == true
        fun clickDescription(description: String): Boolean = instance?.clickDescription(description) == true
        fun goHome(): Boolean = instance?.goHome() == true
        fun goBack(): Boolean = instance?.goBack() == true
        fun openRecents(): Boolean = instance?.openRecents() == true
        fun openNotifications(): Boolean = instance?.openNotifications() == true
        fun tap(x: Float, y: Float): Boolean = instance?.tap(x, y) == true
    }
}
