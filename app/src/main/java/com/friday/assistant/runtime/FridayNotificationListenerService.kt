package com.friday.assistant.runtime

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Receives notification events after the user explicitly grants Notification Access.
 * Keeps a small local history so FRIDAY can answer recent and past-notification queries.
 * Reply actions are intentionally not persisted because Android notification RemoteInputs
 * are process-bound and may no longer be valid after the notification is removed.
 */
data class FridayNotification(
    val key: String,
    val app: String,
    val packageName: String,
    val title: String,
    val text: String,
    val time: Long,
    val replyAction: Notification.Action? = null,
    val replyActions: List<Notification.Action> = emptyList()
)

object FridayNotifications {
    @Volatile
    var items: List<FridayNotification> = emptyList()
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(List<FridayNotification>) -> Unit>()
    private var history: List<FridayNotification> = emptyList()

    internal fun loadHistory(context: android.content.Context) {
        history = runCatching {
            val raw = context.getSharedPreferences("friday_notifications", android.content.Context.MODE_PRIVATE)
                .getString("history", "[]") ?: "[]"
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                FridayNotification(
                    key = o.optString("key"),
                    app = o.optString("app"),
                    packageName = o.optString("packageName"),
                    title = o.optString("title"),
                    text = o.optString("text"),
                    time = o.optLong("time")
                )
            }.filter { it.app.isNotBlank() || it.title.isNotBlank() || it.text.isNotBlank() }
                .sortedByDescending { it.time }
                .take(100)
        }.getOrDefault(emptyList())
        replace(emptyList())
    }

    internal fun record(item: FridayNotification, context: android.content.Context) {
        history = (listOf(item) + history.filterNot { it.key == item.key && it.time == item.time })
            .distinctBy { "${it.key}:${it.time}" }
            .sortedByDescending { it.time }
            .take(100)
        runCatching {
            val array = JSONArray()
            history.forEach { n ->
                array.put(JSONObject()
                    .put("key", n.key)
                    .put("app", n.app)
                    .put("packageName", n.packageName)
                    .put("title", n.title)
                    .put("text", n.text)
                    .put("time", n.time))
            }
            context.getSharedPreferences("friday_notifications", android.content.Context.MODE_PRIVATE)
                .edit().putString("history", array.toString()).apply()
        }
    }

    fun addListener(listener: (List<FridayNotification>) -> Unit): AutoCloseable {
        listeners += listener
        val snapshot = items
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener(snapshot)
        } else {
            mainHandler.post {
                if (listeners.contains(listener)) {
                    runCatching { listener(snapshot) }
                }
            }
        }
        return AutoCloseable { listeners -= listener }
    }

    /** Backward-compatible alias for older callers. */
    fun observe(listener: (List<FridayNotification>) -> Unit): AutoCloseable =
        addListener(listener)

    fun latest(): FridayNotification? = items.firstOrNull()

    fun history(): List<FridayNotification> = history

    /** Ask the live notification listener to resync before answering a query. */
    fun refreshFromSystem(): Boolean = FridayNotificationListenerService.refreshActiveNotifications()

    fun find(query: String): FridayNotification? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return latest()
        return items.firstOrNull {
            it.title.lowercase(Locale.ROOT).contains(q) ||
                it.text.lowercase(Locale.ROOT).contains(q) ||
                it.app.lowercase(Locale.ROOT).contains(q)
        }
    }

    fun describe(query: String? = null): String {
        val selected = query?.let(::find)
        if (selected != null) {
            return "${selected.app}: ${selected.title}. ${selected.text}".trim()
        }
        if (items.isEmpty()) return "There are no recent notifications."
        return items.take(5)
            .joinToString(" | ") { "${it.app}: ${it.title}. ${it.text}" }
            .take(1800)
    }

    internal fun replace(next: List<FridayNotification>) {
        val snapshot = (next + history)
            .distinctBy { "${it.key}:${it.time}" }
            .sortedByDescending { it.time }
            .take(40)
        items = snapshot
        mainHandler.post {
            listeners.forEach { listener ->
                runCatching { listener(snapshot) }
            }
        }
    }
}

class FridayNotificationListenerService : NotificationListenerService() {
    companion object {
        @Volatile private var instance: FridayNotificationListenerService? = null

        fun refreshActiveNotifications(): Boolean {
            val service = instance ?: return false
            return runCatching {
                service.getActiveNotifications().orEmpty().forEach { service.add(it, announce = false) }
                service.publish()
                true
            }.getOrDefault(false)
        }
    }
    private val cache = LinkedHashMap<String, FridayNotification>(32, 0.75f, true)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppContextHolder.context = applicationContext
        FridayNotifications.loadHistory(applicationContext)
        tts = runCatching { TextToSpeech(this) {} }.getOrNull()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        cache.clear()
        runCatching {
            getActiveNotifications().orEmpty().forEach { add(it, announce = false) }
        }
        publish()
        FridayRuntime.update("NOTIFICATIONS", "Notification access connected", true)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        add(sbn, announce = true)
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

    override fun onDestroy() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        AppContextHolder.context = null
        instance = null
        super.onDestroy()
    }

    private fun add(sbn: StatusBarNotification, announce: Boolean) {
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val app = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)

        val replyActions = n.actions?.filter { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }.orEmpty()

        val replyAction = replyActions.firstOrNull()

        val isNew = !cache.containsKey(sbn.key)
        val item = FridayNotification(
            key = sbn.key,
            app = app,
            packageName = sbn.packageName,
            title = title,
            text = text,
            time = sbn.postTime,
            replyAction = replyAction,
            replyActions = replyActions
        )
        cache[sbn.key] = item
        FridayNotifications.record(item, applicationContext)

        while (cache.size > 24) {
            cache.remove(cache.entries.first().key)
        }

        if (announce && isNew && shouldAnnounce(sbn, n)) {
            val spoken = if (title.isBlank()) {
                "${app} se notification aayi hai."
            } else {
                "${app} se notification aayi hai: ${title}."
            }
            announce(spoken)
        }
    }

    private fun shouldAnnounce(sbn: StatusBarNotification, n: Notification): Boolean {
        if (sbn.packageName == packageName) return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if (n.category == Notification.CATEGORY_SERVICE ||
            n.category == Notification.CATEGORY_PROGRESS
        ) return false

        val pkg = sbn.packageName.lowercase(Locale.ROOT)

        val personList: List<Person> = when {
            Build.VERSION.SDK_INT >= 33 -> {
                n.extras.getParcelableArrayList(
                    Notification.EXTRA_PEOPLE_LIST,
                    Person::class.java
                ).orEmpty()
            }
            Build.VERSION.SDK_INT >= 28 -> {
                @Suppress("DEPRECATION")
                n.extras.getParcelableArrayList<Person>(
                    Notification.EXTRA_PEOPLE_LIST
                ).orEmpty()
            }
            else -> emptyList()
        }

        val conversation = n.category == Notification.CATEGORY_MESSAGE ||
            n.category == Notification.CATEGORY_SOCIAL ||
            personList.isNotEmpty()

        val commonMessaging = pkg in setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
            "org.telegram.messenger",
            "com.instagram.android"
        )

        return conversation || commonMessaging
    }

    private fun announce(text: String) {
        mainHandler.post {
            val engine = tts ?: return@post
            runCatching {
                if (engine.isSpeaking) engine.stop()
                engine.language = Locale("hi", "IN")
                engine.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "friday_notification"
                )
                FridayRuntime.update("NOTIFICATION ALERT", text.take(180), true)
            }
        }
    }

    private fun publish() {
        FridayNotifications.replace(
            cache.values.sortedByDescending { it.time }
        )
    }
}
