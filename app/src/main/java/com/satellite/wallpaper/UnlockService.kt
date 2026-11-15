package com.satellite.wallpaper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class UnlockService : Service() {

    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> UnlockRefreshCoordinator.screenOff(applicationContext)
                Intent.ACTION_SCREEN_ON -> UnlockRefreshCoordinator.screenOn(applicationContext)
                Intent.ACTION_USER_PRESENT -> UnlockRefreshCoordinator.userPresent(applicationContext, "servicio")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        receiverRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            true
        }.getOrDefault(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            WallpaperRepository.note(this, "servicio", "activo (primer plano)")
        } catch (t: Throwable) {
            WallpaperRepository.note(this, "servicio", "no pudo pasar a primer plano: ${t.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Fondo de satélite", NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
            )
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_mapmode)
            .setContentTitle("Fondo de satélite activo")
            .setContentText("Se actualiza al desbloquear el teléfono")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "satellite_unlock"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            val app = context.applicationContext
            val i = Intent(app, UnlockService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
            } catch (t: Throwable) {
                WallpaperRepository.note(app, "servicio", "no se pudo iniciar: ${t.javaClass.simpleName}")
            }
        }
    }
}
