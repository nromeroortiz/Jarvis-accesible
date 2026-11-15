package com.jarvis.accesible.whatsapp

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.FileProvider
import com.jarvis.accesible.services.JarvisAccessibilityService
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class WhatsAppIncomingMessage(
    val id: String,
    val senderName: String,
    val text: String,
    val isVoiceNote: Boolean,
    val timestamp: Long,
    val replyPendingIntent: PendingIntent?,
    val remoteInputs: Array<RemoteInput>?,
    var isReadByUncle: Boolean = false
)

/**
 * Gestiona toda la interacción con WhatsApp pensada para una persona ciega:
 * 1. Almacena mensajes y notas de voz entrantes sin interrumpir hasta que el tío pregunte.
 * 2. Responde mensajes por texto directo en segundo plano (vía RemoteInput) o abriendo el chat directo y tocando Enviar.
 * 3. Reproduce las notas de voz (.opus) recibidas buscando en las carpetas de WhatsApp (Android 10 a 15).
 * 4. Graba un audio real con la voz del tío (con corte automático por silencio) y lo envía por WhatsApp haciendo clic automático en Enviar.
 */
object WhatsAppRepository {

    private val messages = CopyOnWriteArrayList<WhatsAppIncomingMessage>()
    private var mediaPlayer: MediaPlayer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addIncomingNotification(
        sender: String,
        text: String,
        replyIntent: PendingIntent?,
        remoteInputs: Array<RemoteInput>?
    ) {
        val cleanSender = sender.trim()
        val cleanText = text.trim()
        if (cleanSender.isEmpty() || cleanText.isEmpty()) return

        // Ignorar avisos del sistema de WhatsApp tipo "2 mensajes de 2 chats" o "Buscando nuevos mensajes"
        val lower = cleanText.lowercase()
        if (lower.contains("mensajes nuevos") ||
            lower.contains("buscando mensajes") ||
            lower.contains("copia de seguridad") ||
            lower.matches(Regex("\\d+ mensajes? de \\d+ chats?"))
        ) {
            return
        }

        // Detectar si es un audio / mensaje de voz
        val isAudio = lower.contains("mensaje de voz") ||
            lower.contains("nota de voz") ||
            lower.contains("🎤") ||
            lower.contains("audio")

        // Evitar duplicados exactos en los últimos 10 segundos
        val now = System.currentTimeMillis()
        val duplicate = messages.any {
            it.senderName.equals(cleanSender, ignoreCase = true) &&
                it.text == cleanText &&
                (now - it.timestamp) < 10_000L
        }
        if (duplicate) return

        messages.add(
            WhatsAppIncomingMessage(
                id = "$now-${cleanSender.hashCode()}",
                senderName = cleanSender,
                text = cleanText,
                isVoiceNote = isAudio,
                timestamp = now,
                replyPendingIntent = replyIntent,
                remoteInputs = remoteInputs,
                isReadByUncle = false
            )
        )
    }

    fun getUnreadSummary(): String {
        val unread = messages.filter { !it.isReadByUncle }
        if (unread.isEmpty()) {
            return "No tenés mensajes nuevos de WhatsApp en este momento."
        }

        val grouped = unread.groupBy { it.senderName }
        val sb = StringBuilder()
        sb.append("Tenés ${unread.size} ")
        sb.append(if (unread.size == 1) "mensaje nuevo" else "mensajes nuevos")
        sb.append(" de ${grouped.size} ")
        sb.append(if (grouped.size == 1) "persona: " else "personas: ")

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        grouped.entries.forEachIndexed { index, (sender, list) ->
            if (index > 0) sb.append(". También de ")
            sb.append(sender).append(": ")
            list.forEach { msg ->
                val timeStr = timeFormat.format(Date(msg.timestamp))
                if (msg.isVoiceNote) {
                    sb.append("te mandó un audio de voz a las $timeStr (${msg.text}). ")
                } else {
                    sb.append("a las $timeStr dijo: \"${msg.text}\". ")
                }
                msg.isReadByUncle = true
            }
        }
        sb.append("¿Querés escuchar algún audio o responderle a alguno?")
        return sb.toString()
    }

    fun getMessagesFromSender(senderQuery: String): String {
        val normQuery = normalizeText(senderQuery)
        val matching = messages.filter {
            normalizeText(it.senderName).contains(normQuery) ||
                normQuery.contains(normalizeText(it.senderName))
        }.takeLast(5)

        if (matching.isEmpty()) {
            return "No encontré mensajes recientes de $senderQuery."
        }

        val sb = StringBuilder("Últimos mensajes de ${matching.last().senderName}: ")
        matching.forEach { msg ->
            msg.isReadByUncle = true
            if (msg.isVoiceNote) {
                sb.append("Un audio de voz (${msg.text}). ")
            } else {
                sb.append("\"${msg.text}\". ")
            }
        }
        return sb.toString()
    }

    /**
     * Reproduce la nota de voz (.opus) más reciente de WhatsApp.
     * Recorre tanto la ruta nueva de Android 11-15 (Scoped Storage) como la ruta clásica de Android 10.
     */
    fun playLatestWhatsAppVoiceNote(
        context: Context,
        senderHint: String? = null,
        onFinished: (Boolean, String) -> Unit
    ) {
        stopAudioPlayback()

        val root = Environment.getExternalStorageDirectory()
        val candidateDirs = listOf(
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"),
            File(root, "WhatsApp/Media/WhatsApp Voice Notes"),
            File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"),
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"),
            File(root, "WhatsApp/Media/WhatsApp Audio")
        )

        val opusFiles = mutableListOf<File>()
        for (dir in candidateDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (file.isFile && (
                            file.extension.equals("opus", ignoreCase = true) ||
                                file.extension.equals("ogg", ignoreCase = true) ||
                                file.extension.equals("m4a", ignoreCase = true) ||
                                file.extension.equals("mp3", ignoreCase = true)
                            )
                    ) {
                        opusFiles.add(file)
                    }
                }
            }
        }

        val latestAudio = opusFiles.maxByOrNull { it.lastModified() }
        if (latestAudio != null) {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(latestAudio.absolutePath)
                    setOnCompletionListener {
                        stopAudioPlayback()
                        onFinished(true, "Terminó el audio de WhatsApp.")
                    }
                    setOnErrorListener { _, _, _ ->
                        stopAudioPlayback()
                        onFinished(false, "Hubo un problema al reproducir el archivo de audio.")
                        true
                    }
                    prepare()
                    start()
                }
                return
            } catch (_: Exception) {
            }
        }

        // Respaldo: si el audio aún no se descargó o Android restringe la carpeta, abrimos la notificación del chat
        // y le pedimos al Servicio de Accesibilidad que toque el botón Reproducir automáticamente.
        val targetMsg = if (!senderHint.isNullOrBlank()) {
            val norm = normalizeText(senderHint)
            messages.lastOrNull { normalizeText(it.senderName).contains(norm) && it.isVoiceNote }
                ?: messages.lastOrNull { normalizeText(it.senderName).contains(norm) }
        } else {
            messages.lastOrNull { it.isVoiceNote } ?: messages.lastOrNull()
        }

        if (targetMsg != null) {
            JarvisAccessibilityService.requestAutoPlayVoiceNoteInWhatsApp()
            onFinished(
                false,
                "No encontré el archivo de audio descargado todavía. Revisá que el permiso de Acceso a todos los archivos esté activado en la pantalla de configuración de Jarvis."
            )
        } else {
            onFinished(false, "No encontré ningún audio de WhatsApp guardado en el teléfono.")
        }
    }

    fun stopAudioPlayback() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    /**
     * Envía un mensaje de texto por WhatsApp:
     * 1) Si esa persona nos escribió recién, responde en segundo plano usando RemoteInput sin siquiera encender ni abrir WhatsApp.
     * 2) Si no hay notificación previa, busca el teléfono en los Contactos del celular, abre el chat directo y el Servicio de Accesibilidad toca "Enviar" solo.
     */
    fun sendTextMessage(context: Context, recipientName: String, messageText: String): String {
        val normRecipient = normalizeText(recipientName)
        val recentMsg = messages.lastOrNull {
            (normalizeText(it.senderName).contains(normRecipient) ||
                normRecipient.contains(normalizeText(it.senderName))) &&
                it.replyPendingIntent != null &&
                !it.remoteInputs.isNullOrEmpty()
        }

        if (recentMsg != null && recentMsg.replyPendingIntent != null && recentMsg.remoteInputs != null) {
            try {
                val fillInIntent = Intent()
                val bundle = Bundle()
                for (remoteInput in recentMsg.remoteInputs) {
                    bundle.putCharSequence(remoteInput.resultKey, messageText)
                }
                RemoteInput.addResultsToIntent(recentMsg.remoteInputs, fillInIntent, bundle)
                recentMsg.replyPendingIntent.send(context, 0, fillInIntent)
                return "Listo, ya le envié el mensaje de WhatsApp a ${recentMsg.senderName} diciendo: \"$messageText\"."
            } catch (_: Exception) {
                // Si la notificación venció, continuamos con el método por agenda de contactos
            }
        }

        val contact = findContactByName(context, recipientName)
            ?: return "No encontré a $recipientName ni en tus mensajes recientes ni en la agenda de contactos del teléfono."

        val cleanPhone = formatPhoneForWhatsApp(contact.second)
        return try {
            // Activamos el clic automático en el botón Enviar dentro del Servicio de Accesibilidad
            JarvisAccessibilityService.requestAutoClickSendInWhatsApp()

            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(messageText)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Enviando WhatsApp a ${contact.first}: \"$messageText\"."
        } catch (e: Exception) {
            "No pude abrir WhatsApp para enviarle el mensaje a ${contact.first}."
        }
    }

    /**
     * Graba un audio real con la voz del tío durante [durationSeconds] o hasta detectar silencio,
     * y lo envía automáticamente al contacto en WhatsApp presionando "Enviar" mediante Accesibilidad.
     */
    fun recordAndSendRealVoiceNote(
        context: Context,
        recipientName: String,
        onRecordingStarted: () -> Unit,
        onCompleted: (String) -> Unit
    ) {
        val contact = findContactByName(context, recipientName)
        if (contact == null) {
            onCompleted("No encontré a $recipientName en la agenda de contactos para mandarle el audio.")
            return
        }

        try {
            val audioDir = File(context.cacheDir, "audios").apply { mkdirs() }
            val audioFile = File(audioDir, "NotaDeVoz_${System.currentTimeMillis()}.m4a")
            currentRecordingFile = audioFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                setOutputFile(audioFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            onRecordingStarted()

            // Monitorear silencio para cortar automáticamente cuando el tío termina de hablar (mínimo 3 seg, máximo 45 seg)
            val startTime = System.currentTimeMillis()
            var lastSoundTime = startTime

            val checkRunnable = object : Runnable {
                override fun run() {
                    val activeRecorder = mediaRecorder ?: return
                    val now = System.currentTimeMillis()
                    val elapsed = now - startTime
                    val amplitude = try {
                        activeRecorder.maxAmplitude
                    } catch (_: Exception) {
                        0
                    }

                    if (amplitude > 1400) {
                        lastSoundTime = now
                    }

                    val silenceDuration = now - lastSoundTime
                    if (elapsed >= 45_000L || (elapsed >= 3_500L && silenceDuration >= 2_500L)) {
                        finishRecordingAndShareToWhatsApp(context, contact.first, contact.second, onCompleted)
                    } else {
                        mainHandler.postDelayed(this, 300L)
                    }
                }
            }
            mainHandler.postDelayed(checkRunnable, 500L)

        } catch (e: Exception) {
            stopRecordingInternal()
            onCompleted("Hubo un problema al usar el micrófono para grabar el audio.")
        }
    }

    private fun finishRecordingAndShareToWhatsApp(
        context: Context,
        contactName: String,
        contactPhone: String,
        onCompleted: (String) -> Unit
    ) {
        stopRecordingInternal()
        val file = currentRecordingFile
        if (file == null || !file.exists() || file.length() < 500L) {
            onCompleted("No se llegó a grabar el audio.")
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val cleanPhone = formatPhoneForWhatsApp(contactPhone)
            val jid = "$cleanPhone@s.whatsapp.net"

            // Indicamos al Servicio de Accesibilidad que haga clic automático en el botón Enviar de WhatsApp
            JarvisAccessibilityService.requestAutoClickSendInWhatsApp()

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra("jid", jid)
                clipData = ClipData.newRawUri("Audio", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(sendIntent)
            onCompleted("Enviando tu audio de voz a $contactName por WhatsApp.")
        } catch (e: Exception) {
            onCompleted("Grabé el audio pero no pude abrir WhatsApp para enviárselo a $contactName.")
        }
    }

    private fun stopRecordingInternal() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
    }

    /**
     * Busca un contacto en la agenda ignorando tildes y mayúsculas.
     * Devuelve Pair(NombreReal, NumeroTelefono).
     */
    fun findContactByName(context: Context, queryName: String): Pair<String, String>? {
        val normQuery = normalizeText(queryName)
        if (normQuery.isEmpty()) return null

        var bestMatch: Pair<String, String>? = null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                do {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numIdx) ?: continue
                    val normName = normalizeText(name)

                    if (normName == normQuery) {
                        return Pair(name, number)
                    }
                    if (normName.contains(normQuery) || normQuery.contains(normName)) {
                        if (bestMatch == null) {
                            bestMatch = Pair(name, number)
                        }
                    }
                } while (cursor.moveToNext())
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return bestMatch
    }

    private fun formatPhoneForWhatsApp(rawPhone: String): String {
        val digits = rawPhone.filter { it.isDigit() }
        // Si es un celular argentino guardado sin código de país (ej. 11... o 011...), lo normalizamos
        return when {
            digits.startsWith("54") -> digits
            digits.startsWith("0") -> "549" + digits.drop(1)
            digits.length == 10 -> "549$digits"
            else -> digits
        }
    }

    fun normalizeText(input: String): String {
        val temp = Normalizer.normalize(input.lowercase(Locale.getDefault()).trim(), Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(temp, "")
    }
}
