package com.friday.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.friday.assistant.runtime.FridayRuntime

/** Optional user-enabled bridge for visible, user-authorized Android UI automation. */
class FridayAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    override fun onServiceConnected() { super.onServiceConnected(); instance = this; FridayRuntime.update("AUTOMATION READY", "Accessibility control is connected", true) }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    fun clickText(text: String): Boolean = rootInActiveWindow?.let { findNode(it, text)?.let(::performClick) ?: false } ?: false
    fun clickDescription(description: String): Boolean = rootInActiveWindow?.let { findNodeByDescription(it, description)?.let(::performClick) ?: false } ?: false
    fun setText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeRecursive(root) { it.isVisibleToUser && it.isEditable } ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
    fun scrollForward(): Boolean = rootInActiveWindow?.let { findNodeRecursive(it) { n -> n.isVisibleToUser && n.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true } ?: false
    fun scrollBackward(): Boolean = rootInActiveWindow?.let { findNodeRecursive(it) { n -> n.isVisibleToUser && n.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true } ?: false
    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun tap(x: Float, y: Float): Boolean = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 80)).build(), null, mainHandler)

    private fun performClick(node: AccessibilityNodeInfo): Boolean = (generateSequence(node) { it.parent }.firstOrNull { it.isClickable } ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    private fun findNode(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val wanted = text.trim().lowercase(); if (wanted.isBlank()) return null
        root.findAccessibilityNodeInfosByText(text).firstOrNull { it.isVisibleToUser }?.let { return it }
        return findNodeRecursive(root) { it.isVisibleToUser && it.text?.toString()?.trim()?.lowercase() == wanted }
    }
    private fun findNodeByDescription(root: AccessibilityNodeInfo, description: String) = findNodeRecursive(root) { it.isVisibleToUser && it.contentDescription?.toString()?.trim()?.equals(description.trim(), true) == true }
    private fun findNodeRecursive(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) root.getChild(i)?.let { child -> findNodeRecursive(child, predicate)?.let { return it } }
        return null
    }
    companion object {
        @Volatile private var instance: FridayAccessibilityService? = null
        fun isConnected() = instance != null
        fun clickText(text: String) = instance?.clickText(text) == true
        fun clickDescription(description: String) = instance?.clickDescription(description) == true
        fun setText(text: String) = instance?.setText(text) == true
        fun scrollForward() = instance?.scrollForward() == true
        fun scrollBackward() = instance?.scrollBackward() == true
        fun goHome() = instance?.goHome() == true
        fun goBack() = instance?.goBack() == true
        fun openRecents() = instance?.openRecents() == true
        fun openNotifications() = instance?.openNotifications() == true
        fun tap(x: Float, y: Float) = instance?.tap(x, y) == true
    }
}
