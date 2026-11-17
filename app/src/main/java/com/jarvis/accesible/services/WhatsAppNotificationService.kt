package com.jarvis.accesible.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.jarvis.accesible.whatsapp.WhatsAppRepository

/**
 * Escucha silenciosamente todas las notificaciones entrantes de WhatsApp (sin interrumpir al tío).
 * Extrae cada mensaje individual con su fecha/hora real usando MessagingStyle para no perder
 * mensajes acumulados durante la noche o madrugada, ignorando chats grupales.
 */
class WhatsAppNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        instance = this
        syncActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        if (instance == this) {
            instance = null
        }
    }

    fun syncActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                if (sbn != null && (sbn.packageName == "com.whatsapp" || sbn.packageName == "com.whatsapp.w4b")) {
                    onNotificationPosted(sbn)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 1. Descartar grupos de WhatsApp si el tío no los usa
        val isGroupExtra = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        if (isGroupExtra) return

        val messagingStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        if (messagingStyle?.isGroupConversation == true) return

        // Solo descartar FLAG_GROUP_SUMMARY si es un contenedor vacío sin mensajes individuales ni texto
        val hasIndividualMessages = messagingStyle != null && messagingStyle.messages.isNotEmpty()
        val hasTextLines = !extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).isNullOrEmpty()
        val hasDirectText = !extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank() ||
            !extras.getCharSequence(Notification.EXTRA_BIG_TEXT).isNullOrBlank()

        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0 && !hasIndividualMessages && !hasTextLines && !hasDirectText) {
            return
        }

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

        // Si tenemos MessagingStyle con mensajes individuales, los procesamos uno a uno con su fecha y hora real
        if (messagingStyle != null && messagingStyle.messages.isNotEmpty()) {
            val rawContactName = messagingStyle.conversationTitle?.toString()?.trim()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
                ?: "Contacto"

            val contactName = WhatsAppRepository.cleanSenderTitle(rawContactName)
            if (contactName.equals("WhatsApp", ignoreCase = true)) return

            for (msg in messagingStyle.messages) {
                // Descartamos mensajes salientes que haya enviado el propio tío desde otro teléfono o sincronización
                val senderPerson = msg.person
                val userPerson = messagingStyle.user
                if (senderPerson != null && userPerson != null) {
                    val isSelfKey = !senderPerson.key.isNullOrBlank() && senderPerson.key == userPerson.key
                    val isSelfName = !senderPerson.name.isNullOrBlank() &&
                        senderPerson.name.toString().equals(userPerson.name?.toString(), ignoreCase = true)
                    if (isSelfKey || isSelfName) continue
                }

                val text = msg.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) continue
                if (text.contains("buscando mensajes", ignoreCase = true) ||
                    text.contains("copia de seguridad", ignoreCase = true) ||
                    text.contains("cifrado de extremo a extremo", ignoreCase = true)
                ) {
                    continue
                }

                val isGenericNewMsg = text.contains("mensajes nuevos", ignoreCase = true) ||
                    text.contains("nuevos mensajes", ignoreCase = true) ||
                    text.contains("mensaje nuevo", ignoreCase = true) ||
                    text.contains("nuevo mensaje", ignoreCase = true)
                val finalText = if (isGenericNewMsg) "Mensaje nuevo" else text

                val msgSender = senderPerson?.name?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("WhatsApp", ignoreCase = true) }
                    ?.let { WhatsAppRepository.cleanSenderTitle(it) }
                    ?: contactName

                val msgTimestamp = if (msg.timestamp > 0L) msg.timestamp else sbn.postTime
                val isVoiceNote = msg.dataMimeType?.contains("audio") == true ||
                    text.contains("🎤") ||
                    text.contains("🎵") ||
                    text.contains("audio", ignoreCase = true) ||
                    text.contains("mensaje de voz", ignoreCase = true) ||
                    text.contains("nota de voz", ignoreCase = true)

                WhatsAppRepository.addIncomingNotification(
                    context = applicationContext,
                    sender = msgSender,
                    text = finalText,
                    replyIntent = replyPendingIntent,
                    remoteInputs = remoteInputs,
                    messageTimestamp = msgTimestamp,
                    isVoiceNote = isVoiceNote
                )
            }
            return
        }

        // Fallback para notificaciones estándar que no vienen en MessagingStyle
        val rawTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return

        val cleanTitle = WhatsAppRepository.cleanSenderTitle(rawTitle)
        if (cleanTitle.equals("WhatsApp", ignoreCase = true)) return

        // Puede venir como arreglo de líneas en EXTRA_TEXT_LINES (InboxStyle)
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!textLines.isNullOrEmpty()) {
            for (line in textLines) {
                val lineStr = line?.toString()?.trim().orEmpty()
                if (lineStr.isEmpty()) continue
                if (lineStr.contains("buscando mensajes", ignoreCase = true) ||
                    lineStr.contains("copia de seguridad", ignoreCase = true)
                ) {
                    continue
                }
                val isGenericNew = lineStr.contains("mensajes nuevos", ignoreCase = true) ||
                    lineStr.contains("nuevos mensajes", ignoreCase = true) ||
                    lineStr.contains("mensaje nuevo", ignoreCase = true) ||
                    lineStr.contains("nuevo mensaje", ignoreCase = true)
                val finalLine = if (isGenericNew) "Mensaje nuevo" else lineStr

                val isVoiceNote = lineStr.contains("🎤") || lineStr.contains("🎵") || lineStr.contains("audio", ignoreCase = true)
                WhatsAppRepository.addIncomingNotification(
                    context = applicationContext,
                    sender = cleanTitle,
                    text = finalLine,
                    replyIntent = replyPendingIntent,
                    remoteInputs = remoteInputs,
                    messageTimestamp = sbn.postTime,
                    isVoiceNote = isVoiceNote
                )
            }
            return
        }

        val rawText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: return

        if (rawText.contains("buscando mensajes", ignoreCase = true) ||
            rawText.contains("copia de seguridad", ignoreCase = true)
        ) {
            return
        }

        val isGenericNew = rawText.contains("mensajes nuevos", ignoreCase = true) ||
            rawText.contains("nuevos mensajes", ignoreCase = true) ||
            rawText.contains("mensaje nuevo", ignoreCase = true) ||
            rawText.contains("nuevo mensaje", ignoreCase = true)
        val finalRawText = if (isGenericNew) "Mensaje nuevo" else rawText

        val isVoiceNote = rawText.contains("🎤") || rawText.contains("🎵") || rawText.contains("audio", ignoreCase = true)

        WhatsAppRepository.addIncomingNotification(
            context = applicationContext,
            sender = cleanTitle,
            text = finalRawText,
            replyIntent = replyPendingIntent,
            remoteInputs = remoteInputs,
            messageTimestamp = sbn.postTime,
            isVoiceNote = isVoiceNote
        )
    }

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var instance: WhatsAppNotificationService? = null
            private set
    }
}
