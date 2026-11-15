package com.jarvis.accesible.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jarvis.accesible.whatsapp.WhatsAppRepository

/**
 * Escucha silenciosamente todas las notificaciones entrantes de WhatsApp (sin interrumpir al tío).
 * Guarda quién escribió, qué dijo (o si mandó un audio de voz) y la acción directa para responderle
 * cuando el tío pregunte "¿Tengo algún mensaje?".
 */
class WhatsAppNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            // Ignoramos el resumen grupal para no duplicar mensajes individuales
            return
        }

        val extras = notification.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return

        val rawText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: return

        // Buscamos la acción de "Responder" (RemoteInput) dentro de la notificación
        var replyPendingIntent = notification.contentIntent
        var remoteInputs: Array<android.app.RemoteInput>? = null

        notification.actions?.forEach { action ->
            val inputs = action.remoteInputs
            if (!inputs.isNullOrEmpty() && action.actionIntent != null) {
                replyPendingIntent = action.actionIntent
                remoteInputs = inputs
            }
        }

        WhatsAppRepository.addIncomingNotification(
            sender = rawTitle,
            text = rawText,
            replyIntent = replyPendingIntent,
            remoteInputs = remoteInputs
        )
    }

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set
    }
}
