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
    val associatedAudioFile: File? = null,
    var isReadByUncle: Boolean = false
)

/**
 * Gestiona toda la interacción con WhatsApp de forma rápida y flexible:
 * 1. Almacena mensajes y notas de voz entrantes asociando el archivo .opus a la persona que lo envió.
 * 2. Busca los audios .opus únicamente en las 2 carpetas semanales más recientes (tarda <20ms en lugar de varios segundos).
 * 3. Permite reproducir el último audio, el anterior/penúltimo, repetir el actual, reproducir varios seguidos,
 *    o abrir directamente el chat de un contacto específico (ej. "Nico") y darle Play al audio deseado.
 */
object WhatsAppRepository {

    private val messages = CopyOnWriteArrayList<WhatsAppIncomingMessage>()
    private var mediaPlayer: MediaPlayer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Puntero para poder decir "reproducí el audio anterior" o "repetilo"
    @Volatile
    private var currentAudioIndex: Int = 0

    fun addIncomingNotification(
        sender: String,
        text: String,
        replyIntent: PendingIntent?,
        remoteInputs: Array<RemoteInput>?
    ) {
        val cleanSender = sender.trim()
        val cleanText = text.trim()
        if (cleanSender.isEmpty() || cleanText.isEmpty()) return

        val lower = cleanText.lowercase()
        if (lower.contains("mensajes nuevos") ||
            lower.contains("buscando mensajes") ||
            lower.contains("copia de seguridad") ||
            lower.matches(Regex("\\d+ mensajes? de \\d+ chats?"))
        ) {
            return
        }

        val isAudio = lower.contains("mensaje de voz") ||
            lower.contains("nota de voz") ||
            lower.contains("🎤") ||
            lower.contains("audio")

        val now = System.currentTimeMillis()
        val duplicate = messages.any {
            it.senderName.equals(cleanSender, ignoreCase = true) &&
                it.text == cleanText &&
                (now - it.timestamp) < 10_000L
        }
        if (duplicate) return

        val latestFile = if (isAudio) getRecentOpusFilesFast(limit = 1).firstOrNull() else null

        messages.add(
            WhatsAppIncomingMessage(
                id = "$now-${cleanSender.hashCode()}",
                senderName = cleanSender,
                text = cleanText,
                isVoiceNote = isAudio,
                timestamp = now,
                replyPendingIntent = replyIntent,
                remoteInputs = remoteInputs,
                associatedAudioFile = latestFile,
                isReadByUncle = false
            )
        )
    }

    /**
     * Resumen corto y al grano de los mensajes nuevos.
     */
    fun getUnreadSummary(): String {
        val unread = messages.filter { !it.isReadByUncle }
        if (unread.isEmpty()) {
            return "No tenés mensajes nuevos."
        }

        val grouped = unread.groupBy { it.senderName }
        val sb = StringBuilder()

        grouped.entries.forEachIndexed { index, (sender, list) ->
            if (index > 0) sb.append(" ")
            val audioCount = list.count { it.isVoiceNote }
            val textMsgs = list.filter { !it.isVoiceNote }

            if (audioCount > 0 && textMsgs.isEmpty()) {
                if (audioCount == 1) {
                    sb.append("$sender mandó un audio.")
                } else {
                    sb.append("$sender mandó $audioCount audios.")
                }
            } else if (audioCount > 0 && textMsgs.isNotEmpty()) {
                sb.append("$sender mandó $audioCount audio y dijo: ${textMsgs.joinToString(". ") { it.text }}.")
            } else {
                sb.append("$sender dijo: ${textMsgs.joinToString(". ") { it.text }}.")
            }

            list.forEach { it.isReadByUncle = true }
        }
        return sb.toString().trim()
    }

    fun getMessagesFromSender(context: Context, senderQuery: String): String {
        val normQuery = normalizeText(senderQuery)
        val matching = messages.filter {
            normalizeText(it.senderName).contains(normQuery) ||
                normQuery.contains(normalizeText(it.senderName))
        }.takeLast(4)

        if (matching.isNotEmpty()) {
            val senderReal = matching.last().senderName
            val sb = StringBuilder("$senderReal: ")
            matching.forEach { msg ->
                msg.isReadByUncle = true
                if (msg.isVoiceNote) {
                    sb.append("mandó un audio. ")
                } else {
                    sb.append("${msg.text}. ")
                }
            }
            return sb.toString().trim()
        }

        // Si no había notificaciones recientes guardadas, abrimos directamente el chat de ese contacto
        val contact = findContactByName(context, senderQuery)
        if (contact != null) {
            openWhatsAppChat(context, contact.second)
            return "Abriendo el chat de ${contact.first}."
        }

        return "No hay mensajes recientes de $senderQuery."
    }

    /**
     * Obtiene los archivos de audio (.opus) más recientes en menos de 20 milisegundos
     * revisando únicamente las 2 subcarpetas semanales más nuevas de WhatsApp.
     */
    private fun getRecentOpusFilesFast(limit: Int = 25): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val voiceNotesBases = listOf(
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"),
            File(root, "WhatsApp/Media/WhatsApp Voice Notes"),
            File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes")
        )
        val audioBases = listOf(
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"),
            File(root, "WhatsApp/Media/WhatsApp Audio")
        )

        val candidates = mutableListOf<File>()

        for (base in voiceNotesBases) {
            if (!base.exists() || !base.isDirectory) continue
            val subFolders = base.listFiles { f -> f.isDirectory }
                ?.sortedByDescending { it.name }
                ?.take(2)
                .orEmpty()

            for (folder in subFolders) {
                folder.listFiles { f ->
                    f.isFile && (f.name.endsWith(".opus", true) || f.name.endsWith(".ogg", true) || f.name.endsWith(".m4a", true))
                }?.let { candidates.addAll(it) }
            }
        }

        for (base in audioBases) {
            if (!base.exists() || !base.isDirectory) continue
            base.listFiles { f ->
                f.isFile && (f.name.endsWith(".opus", true) || f.name.endsWith(".mp3", true) || f.name.endsWith(".m4a", true))
            }?.sortedByDescending { it.lastModified() }?.take(10)?.let { candidates.addAll(it) }
        }

        return candidates.sortedByDescending { it.lastModified() }.take(limit)
    }

    /**
     * Reproducción flexible de audios de WhatsApp:
     * - Si el usuario especificó un contacto (ej. "Nico") y pidió abrir el chat o no tenemos el archivo local asociado,
     *   abre directamente el chat de Nico en WhatsApp y el Servicio de Accesibilidad toca el botón Play del audio pedido
     *   (1 = el último, 2 = el anterior/penúltimo, 3 = el antepenúltimo).
     * - Si no especificó contacto (o tenemos el archivo en disco), reproduce instantáneamente desde memoria permitiendo
     *   ir al "último", al "anterior / penúltimo", "repetir" o escuchar varios seguidos.
     */
    fun playWhatsAppVoiceNoteFlexible(
        context: Context,
        senderHint: String? = null,
        whichOffset: Int = 1, // 1 = último, 2 = penúltimo/anterior, 3 = antepenúltimo
        stepBackward: Boolean = false,
        repeatCurrent: Boolean = false,
        forceOpenChat: Boolean = false,
        onFinished: (Boolean, String) -> Unit
    ) {
        stopAudioPlayback()

        // 1. Si pidió explícitamente un contacto (ej. "reproducí el audio de Nico" o "abrí el chat de Nico y reproducí el audio")
        if (!senderHint.isNullOrBlank()) {
            val normSender = normalizeText(senderHint)

            // Si no forzó abrir el chat y tenemos el archivo asociado de una notificación recién llegada (y pidió el último)
            if (!forceOpenChat && whichOffset == 1 && !stepBackward) {
                val matchedNotifAudio = messages.lastOrNull {
                    (normalizeText(it.senderName).contains(normSender) || normSender.contains(normalizeText(it.senderName))) &&
                        it.isVoiceNote &&
                        it.associatedAudioFile?.exists() == true
                }?.associatedAudioFile

                if (matchedNotifAudio != null) {
                    playSingleAudioFile(matchedNotifAudio, onFinished)
                    return
                }
            }

            // Buscamos al contacto en la agenda, abrimos su chat en WhatsApp y le damos Play al audio #whichOffset desde abajo
            val contact = findContactByName(context, senderHint)
            if (contact != null) {
                JarvisAccessibilityService.requestAutoPlayVoiceNoteInWhatsApp(offsetFromBottom = whichOffset)
                openWhatsAppChat(context, contact.second)
                return
            }

            // Si dijo el nombre de alguien que nos mandó notificación pero no está agendado con ese nombre, abrimos la notificación
            val notifMsg = messages.lastOrNull {
                normalizeText(it.senderName).contains(normSender) || normSender.contains(normalizeText(it.senderName))
            }
            if (notifMsg?.replyPendingIntent != null) {
                try {
                    JarvisAccessibilityService.requestAutoPlayVoiceNoteInWhatsApp(offsetFromBottom = whichOffset)
                    notifMsg.replyPendingIntent.send()
                    return
                } catch (_: Exception) {
                }
            }
        }

        // 2. Reproducción directa ultrarrápida desde los archivos .opus guardados en el teléfono
        val recentFiles = getRecentOpusFilesFast(limit = 25)
        if (recentFiles.isNotEmpty()) {
            val targetIdx = when {
                repeatCurrent -> currentAudioIndex.coerceIn(0, recentFiles.lastIndex)
                stepBackward -> (currentAudioIndex + 1).coerceIn(0, recentFiles.lastIndex)
                else -> (whichOffset - 1).coerceIn(0, recentFiles.lastIndex)
            }
            currentAudioIndex = targetIdx
            playSingleAudioFile(recentFiles[targetIdx], onFinished)
            return
        }

        onFinished(false, "No encontré audios de WhatsApp.")
    }

    private fun playSingleAudioFile(file: File, onFinished: (Boolean, String) -> Unit) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stopAudioPlayback()
                    onFinished(true, "Fin del audio.")
                }
                setOnErrorListener { _, _, _ ->
                    stopAudioPlayback()
                    onFinished(false, "No pude reproducir el audio.")
                    true
                }
                prepare()
                start()
            }
        } catch (_: Exception) {
            stopAudioPlayback()
            onFinished(false, "No pude abrir el audio.")
        }
    }

    fun openWhatsAppChat(context: Context, rawPhone: String): Boolean {
        return try {
            val cleanPhone = formatPhoneForWhatsApp(rawPhone)
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
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
                return "Enviado a ${recentMsg.senderName}."
            } catch (_: Exception) {
            }
        }

        val contact = findContactByName(context, recipientName)
            ?: return "No encontré a $recipientName en tus contactos."

        val cleanPhone = formatPhoneForWhatsApp(contact.second)
        return try {
            JarvisAccessibilityService.requestAutoClickSendInWhatsApp()

            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${Uri.encode(messageText)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Enviando a ${contact.first}."
        } catch (e: Exception) {
            "No pude abrir WhatsApp."
        }
    }

    fun recordAndSendRealVoiceNote(
        context: Context,
        recipientName: String,
        onRecordingStarted: () -> Unit,
        onCompleted: (String) -> Unit
    ) {
        val contact = findContactByName(context, recipientName)
        if (contact == null) {
            onCompleted("No encontré a $recipientName en tus contactos.")
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
            onCompleted("No pude grabar el audio.")
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
            onCompleted("No se grabó el audio.")
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
            onCompleted("Enviando audio a $contactName.")
        } catch (e: Exception) {
            onCompleted("No pude enviar el audio a $contactName.")
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

    fun findContactByName(context: Context, queryName: String): Pair<String, String>? {
        val normQuery = normalizeText(queryName)
            .replace(Regex("^(el|la|los|las|a|de|del|con|chat de|chat con)\\s+"), "")
            .trim()
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

    /**
     * Busca si dentro de una frase libre hablada por el usuario se menciona algún contacto de la agenda.
     */
    fun findMentionedContactInSentence(context: Context, sentence: String): String? {
        val normSentence = " " + normalizeText(sentence) + " "
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        var cursor: Cursor? = null
        var longestMatch: String? = null
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
                do {
                    val name = cursor.getString(nameIdx)?.trim() ?: continue
                    val normName = normalizeText(name)
                    if (normName.length >= 3 && normSentence.contains(" $normName ")) {
                        if (longestMatch == null || name.length > longestMatch.length) {
                            longestMatch = name
                        }
                    }
                } while (cursor.moveToNext())
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return longestMatch
    }

    private fun formatPhoneForWhatsApp(rawPhone: String): String {
        val digits = rawPhone.filter { it.isDigit() }
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
