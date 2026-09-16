package com.friday.assistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.friday.assistant.R

/** Foreground lifecycle keeper. It does not open a second microphone. */
class FridayHandsFreeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate(){super.onCreate();createChannel();val n=buildNotification();try{if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)else startForeground(NOTIFICATION_ID,n)}catch(_:Throwable){stopSelf();return};runCatching{getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"FRIDAY:HandsFree").apply{setReferenceCounted(false);acquire();wakeLock=this}}}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_STICKY
    override fun onDestroy(){wakeLock?.let{if(it.isHeld)it.release()};wakeLock=null;super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    private fun createChannel(){if(Build.VERSION.SDK_INT<26)return;getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"FRIDAY Hands-Free",NotificationManager.IMPORTANCE_LOW).apply{description="FRIDAY hands-free lifecycle"})}
    private fun buildNotification():Notification=NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("FRIDAY hands-free active").setContentText("Wake detection is active through Android Assistant.").setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    companion object{private const val CHANNEL_ID="friday_hands_free";private const val NOTIFICATION_ID=704}
}
