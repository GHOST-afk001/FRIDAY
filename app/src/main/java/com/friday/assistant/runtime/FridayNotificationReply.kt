package com.friday.assistant.runtime

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import androidx.core.app.PendingIntentCompat

/** Sends replies through notification RemoteInput actions when the target app exposes them. */
object FridayNotificationReply {
    fun send(notification: FridayNotification, message: String): Boolean {
        val context = AppContextHolder.context ?: return false
        val actions = notification.replyActions.ifEmpty {
            listOfNotNull(notification.replyAction)
        }
        if (actions.isEmpty()) return false

        for (action in actions) {
            val remoteInputs = action.remoteInputs?.filter { it.allowFreeFormInput }.orEmpty()
            val pendingIntent = action.actionIntent ?: continue
            if (remoteInputs.isEmpty()) continue

            val intent = Intent()
            val bundle = android.os.Bundle().apply {
                remoteInputs.forEach { input ->
                    putCharSequence(input.resultKey, message)
                }
            }
            RemoteInput.addResultsToIntent(remoteInputs.toTypedArray(), intent, bundle)

            try {
                PendingIntentCompat.send(pendingIntent, context, 0, intent, null, null)
                return true
            } catch (_: PendingIntent.CanceledException) {
                // Try the next reply action if this action was canceled.
            } catch (_: Throwable) {
                // Notification providers can expose malformed or short-lived actions.
            }
        }
        return false
    }
}

/** Small process-local context holder initialized by the notification service. */
object AppContextHolder {
    @Volatile var context: android.content.Context? = null
}
