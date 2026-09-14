package com.friday.assistant.runtime

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Receives notification events after the user explicitly grants Notification Access.
 * Only a bounded in-memory list is kept for the HUD; nothing is written to disk.
 */
data class FridayNotification(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long
)

object FridayNotifications {
    @Volatile var items: List<FridayNotification> = emptyList()
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(List<FridayNotification>) -> Unit>()

    fun observe(listener: (List<FridayNotification>) -> Unit): AutoCloseable {
        listeners += listener
        val snapshot = items
        if (Looper.myLooper() == Looper.getMainLooper()) listener(snapshot)
        else mainHandler.post { if (listeners.contains(listener)) listener(items) }
        return AutoCloseable { listeners -= listener }
    }

    internal fun replace(next: List<FridayNotification>) {
        val snapshot = next.take(24)
        items = snapshot
        mainHandler.post {
            listeners.forEach { runCatching { it(snapshot) } }
        }
    }
}

class FridayNotificationListenerService : NotificationListenerService() {
    private val cache = LinkedHashMap<String, FridayNotification>(32, 0.75f, true)

    override fun onListenerConnected() {
        super.onListenerConnected()
        cache.clear()
        getActiveNotifications().orEmpty().forEach { add(it) }
        publish()
        FridayRuntime.update("NOTIFICATIONS", "Notification access connected", true)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        add(sbn)
        publish()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        cache.remove(sbn.key)
        publish()
    }

    override fun onListenerDisconnected() {
        cache.clear()
        FridayNotifications.replace(emptyList())
        FridayRuntime.update("NOTIFICATIONS", "Notification access disconnected", false)
        super.onListenerDisconnected()
    }

    private fun add(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        cache[sbn.key] = FridayNotification(sbn.key, app, title, text, sbn.postTime)
        while (cache.size > 24) cache.remove(cache.entries.first().key)
    }

    private fun publish() {
        FridayNotifications.replace(cache.values.sortedByDescending { it.time })
    }
}
