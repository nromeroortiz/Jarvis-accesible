package com.jarvis.accesible.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.accesible.core.JarvisCoordinator
import com.jarvis.accesible.ui.MainActivity

/**
 * Mantiene vivo a Jarvis en segundo plano y asegura que, si la escucha continua por voz ("Hola Jarvis")
 * está habilitada, permanezca disponible sin que Android cierre el proceso.
 */
class JarvisForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        if (!hasMicrophonePermission(this)) {
            stopSelf()
            return
        }
        createNotificationChannel()
        if (!startForegroundSafely()) {
            stopSelf()
            return
        }
        try {
            JarvisCoordinator.getInstance(applicationContext).speechInput.syncWakeWordLoop()
        } catch (_: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasMicrophonePermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startForegroundSafely()) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            JarvisCoordinator.getInstance(applicationContext).speechInput.syncWakeWordLoop()
        } catch (_: Throwable) {
        }
        return START_STICKY
    }

    private fun startForegroundSafely(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Asistente Wally Activo",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene el asistente de voz siempre listo para personas con discapacidad visual"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wally Accesible está listo")
            .setContentText("Tocá dos veces Subir Volumen o decí Wally para pedir lo que necesites")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_accesible_channel"
        private const val NOTIFICATION_ID = 1001

        fun hasMicrophonePermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun startServiceIfPossible(context: Context) {
            try {
                // En Android 14/15 (API 34+), iniciar un ForegroundService de tipo micrófono
                // antes de otorgar el permiso RECORD_AUDIO lanza SecurityException.
                // Por eso solo lo iniciamos una vez concedido el permiso de micrófono.
                if (!hasMicrophonePermission(context)) return

                val intent = Intent(context.applicationContext, JarvisForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            } catch (_: Throwable) {
            }
        }
    }
}
