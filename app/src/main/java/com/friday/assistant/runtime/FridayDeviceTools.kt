package com.friday.assistant.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract

object FridayDeviceTools {
    fun buildTimerIntent(seconds: Int): Intent =
        Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun buildAlarmIntent(hour: Int, minute: Int): Intent =
        Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun buildCalendarIntent(): Intent =
        Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun buildMapIntent(query: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun buildWebSearchIntent(query: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun canHandle(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null
}
