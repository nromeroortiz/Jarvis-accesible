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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class WhatsAppIncomingMessage(
    val id: String,
    val senderName: String,
    val text: String,
    val isVoiceNote: Boolean,
    val timestamp: Long,
    val replyPendingIntent: PendingIntent? = null,
    val remoteInputs: Array<RemoteInput>? = null,
    val associatedAudioPath: String? = null,
    var isReadByUncle: Boolean = false
)

/**
 * Gestiona toda la interacción con WhatsApp:
 * - Guarda un historial persistente de mensajes (incluso ya leídos) para poder pedir "mensajes de hoy", "de ayer", "de X persona".
 * - Permite reproducir listas de audios por día ("audios de hoy", "audios de ayer") y navegar con "Siguiente" y "Anterior".
 * - Permite leer en voz alta los mensajes ya vistos dentro del chat abierto en pantalla de cualquier contacto.
 */
object WhatsAppRepository {

    private val messages = CopyOnWriteArrayList<WhatsAppIncomingMessage>()
    private var isHistoryLoaded = false
    private var mediaPlayer: MediaPlayer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Cola activa de navegación para que el tío pueda decir "Siguiente" o "Anterior"
    private enum class NavMode { AUDIO_LIST, CHAT_AUDIO, MESSAGE_LIST }

    @Volatile
    private var currentNavMode: NavMode = NavMode.AUDIO_LIST

    private var activeAudioPlaylist: List<File> = emptyList()

    @Volatile
    private var activeAudioIndex: Int = 0

    private var activeMessagePlaylist: List<String> = emptyList()

    @Volatile
    private var activeMessageIndex: Int = 0

    @Volatile
    private var activeChatSender: String? = null

    @Volatile
    private var activeChatAudioOffset: Int = 1

    fun ensureHistoryLoaded(context: Context) {
        if (isHistoryLoaded) return
        isHistoryLoaded = true
        try {
            val file = File(context.filesDir, "whatsapp_history.json")
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            val cutoff = System.currentTimeMillis() - 14L * 24L * 3600L * 1000L
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val ts = obj.optLong("timestamp", 0L)
                if (ts < cutoff) continue
                messages.add(
                    WhatsAppIncomingMessage(
                        id = obj.optString("id"),
                        senderName = obj.optString("senderName"),
                        text = obj.optString("text"),
                        isVoiceNote = obj.optBoolean("isVoiceNote"),
                        timestamp = ts,
                        associatedAudioPath = obj.optString("associatedAudioPath").ifBlank { null },
                        isReadByUncle = obj.optBoolean("isReadByUncle", false)
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun saveHistoryToDisk(context: Context) {
        try {
            val arr = JSONArray()
            val recent = messages.takeLast(150)
            for (m in recent) {
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("senderName", m.senderName)
                    put("text", m.text)
                    put("isVoiceNote", m.isVoiceNote)
                    put("timestamp", m.timestamp)
                    put("associatedAudioPath", m.associatedAudioPath ?: "")
                    put("isReadByUncle", m.isReadByUncle)
                })
            }
            File(context.filesDir, "whatsapp_history.json").writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    fun addIncomingNotification(
        context: Context,
        sender: String,
        text: String,
        replyIntent: PendingIntent?,
        remoteInputs: Array<RemoteInput>?
    ) {
        ensureHistoryLoaded(context)
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
                associatedAudioPath = latestFile?.absolutePath,
                isReadByUncle = false
            )
        )
        saveHistoryToDisk(context)
    }

    fun getUnreadSummary(context: Context): String {
        ensureHistoryLoaded(context)
        val unread = messages.filter { !it.isReadByUncle }
        if (unread.isEmpty()) {
            return "No tenés mensajes nuevos."
        }

        // También armamos la cola de lectura por si dice "Siguiente" o "Anterior"
        activeMessagePlaylist = unread.reversed().map { msg ->
            if (msg.isVoiceNote) "${msg.senderName} mandó un audio."
            else "${msg.senderName} dijo: ${msg.text}."
        }
        activeMessageIndex = 0
        currentNavMode = NavMode.MESSAGE_LIST

        val grouped = unread.groupBy { it.senderName }
        val sb = StringBuilder()

        grouped.entries.forEachIndexed { index, (sender, list) ->
            if (index > 0) sb.append(" ")
            val audioCount = list.count { it.isVoiceNote }
            val textMsgs = list.filter { !it.isVoiceNote }

            if (audioCount > 0 && textMsgs.isEmpty()) {
                sb.append(if (audioCount == 1) "$sender mandó un audio." else "$sender mandó $audioCount audios.")
            } else if (audioCount > 0 && textMsgs.isNotEmpty()) {
                sb.append("$sender mandó $audioCount audio y dijo: ${textMsgs.joinToString(". ") { it.text }}.")
            } else {
                sb.append("$sender dijo: ${textMsgs.joinToString(". ") { it.text }}.")
            }

            list.forEach { it.isReadByUncle = true }
        }
        saveHistoryToDisk(context)
        return sb.toString().trim()
    }

    /**
     * Lee mensajes de una persona o de un día ("hoy", "ayer", "hoy y ayer") INCLUSO SI YA FUERON LEÍDOS.
     * Permite luego decir "Siguiente" y "Anterior" para pasar uno por uno.
     */
    fun readMessagesByFilter(
        context: Context,
        senderQuery: String?,
        dayFilter: String?, // "hoy", "ayer", "ambos", o null
        onAsyncScreenRead: (String) -> Unit
    ): String {
        ensureHistoryLoaded(context)
        val normSender = senderQuery?.let { normalizeText(it) }

        val filtered = messages.filter { msg ->
            val matchesSender = if (normSender.isNullOrBlank()) {
                true
            } else {
                val s = normalizeText(msg.senderName)
                s.contains(normSender) || normSender.contains(s)
            }
            val matchesDay = matchesDaySpec(msg.timestamp, dayFilter)
            matchesSender && matchesDay
        }.sortedByDescending { it.timestamp }

        if (filtered.isNotEmpty()) {
            val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            activeMessagePlaylist = filtered.map { msg ->
                val hr = timeFmt.format(Date(msg.timestamp))
                if (msg.isVoiceNote) "${msg.senderName} a las $hr mandó un audio."
                else "${msg.senderName} a las $hr dijo: ${msg.text}."
            }
            activeMessageIndex = 0
            currentNavMode = NavMode.MESSAGE_LIST

            filtered.forEach { it.isReadByUncle = true }
            saveHistoryToDisk(context)

            val firstItem = activeMessagePlaylist.first()
            val total = activeMessagePlaylist.size
            return if (total > 1) {
                "Mensaje 1 de $total: $firstItem Decí siguiente para escuchar el otro."
            } else {
                firstItem
            }
        }

        // Si pidió de una persona específica y esos mensajes llegaron antes de instalar Jarvis,
        // abrimos su chat en WhatsApp y leemos las burbujas visibles directamente de la pantalla.
        if (!senderQuery.isNullOrBlank()) {
            val contact = findContactByName(context, senderQuery)
            if (contact != null) {
                JarvisAccessibilityService.requestReadVisibleChatInWhatsApp { screenTexts ->
                    val recentTexts = screenTexts.reversed()
                    activeMessagePlaylist = recentTexts.map { "${contact.first} dijo: $it" }
                    activeMessageIndex = 0
                    currentNavMode = NavMode.MESSAGE_LIST
                    val first = activeMessagePlaylist.firstOrNull()
                    if (first != null) {
                        onAsyncScreenRead("$first. Decí siguiente o anterior para recorrer el chat.")
                    }
                }
                openWhatsAppChat(context, contact.second)
                return "Abriendo y leyendo el chat de ${contact.first}."
            }
        }

        return "No encontré mensajes guardados para ese filtro."
    }

    /**
     * Reproduce los audios de un día ("hoy", "ayer", etc.) o de una persona, y habilita decir "Siguiente" o "Anterior".
     */
    fun playWhatsAppVoiceNoteFlexible(
        context: Context,
        senderHint: String? = null,
        whichOffset: Int = 1,
        dayFilter: String? = null,
        stepNext: Boolean = false,
        stepPrevious: Boolean = false,
        repeatCurrent: Boolean = false,
        forceOpenChat: Boolean = false,
        onFinished: (Boolean, String) -> Unit
    ) {
        stopAudioPlayback()

        // 1. Si el usuario dijo "Siguiente", "Anterior" o "Repetir" sobre lo que estaba escuchando
        if (stepNext || stepPrevious || repeatCurrent) {
            handleStepNavigation(context, stepNext, stepPrevious, repeatCurrent, onFinished)
            return
        }

        // 2. Si pidió explícitamente los audios de un contacto (ej. "reproducí el último audio de Nico")
        if (!senderHint.isNullOrBlank()) {
            val contact = findContactByName(context, senderHint)
            if (contact != null) {
                activeChatSender = contact.first
                activeChatAudioOffset = whichOffset.coerceAtLeast(1)
                currentNavMode = NavMode.CHAT_AUDIO
                JarvisAccessibilityService.requestAutoPlayVoiceNoteInWhatsApp(offsetFromBottom = activeChatAudioOffset)
                openWhatsAppChat(context, contact.second)
                return
            }
        }

        // 3. Buscamos los archivos .opus filtrando por día si pidió "audios de hoy", "audios de ayer", etc.
        val allRecent = getRecentOpusFilesFast(limit = 60)
        val filteredFiles = if (!dayFilter.isNullOrBlank()) {
            allRecent.filter { matchesDaySpec(it.lastModified(), dayFilter) }
        } else {
            allRecent
        }

        if (filteredFiles.isNotEmpty()) {
            activeAudioPlaylist = filteredFiles
            activeAudioIndex = (whichOffset - 1).coerceIn(0, filteredFiles.lastIndex)
            currentNavMode = NavMode.AUDIO_LIST
            playSingleAudioFile(activeAudioPlaylist[activeAudioIndex], onFinished)
            return
        }

        val noAudioMsg = if (!dayFilter.isNullOrBlank()) {
            "No encontré audios de $dayFilter."
        } else {
            "No encontré audios de WhatsApp."
        }
        onFinished(false, noAudioMsg)
    }

    /**
     * Avanza ("Siguiente") o retrocede ("Anterior") tanto en listas de audios como en listas de mensajes.
     */
    fun stepActiveQueue(
        context: Context,
        isNext: Boolean,
        isRepeat: Boolean,
        onSpeakMessage: (String) -> Unit
    ) {
        stopAudioPlayback()

        when (currentNavMode) {
            NavMode.MESSAGE_LIST -> {
                if (activeMessagePlaylist.isEmpty()) {
                    onSpeakMessage("No hay más mensajes en la lista.")
                    return
                }
                activeMessageIndex = when {
                    isRepeat -> activeMessageIndex.coerceIn(0, activeMessagePlaylist.lastIndex)
                    isNext -> (activeMessageIndex + 1)
                    else -> (activeMessageIndex - 1)
                }
                if (activeMessageIndex > activeMessagePlaylist.lastIndex) {
                    activeMessageIndex = activeMessagePlaylist.lastIndex
                    onSpeakMessage("Ese fue el último mensaje.")
                    return
                }
                if (activeMessageIndex < 0) {
                    activeMessageIndex = 0
                    onSpeakMessage("Estás en el primer mensaje: ${activeMessagePlaylist[0]}")
                    return
                }
                val item = activeMessagePlaylist[activeMessageIndex]
                onSpeakMessage(item)
            }

            NavMode.CHAT_AUDIO -> {
                val sender = activeChatSender
                if (sender != null) {
                    val contact = findContactByName(context, sender)
                    if (contact != null) {
                        activeChatAudioOffset = when {
                            isRepeat -> activeChatAudioOffset
                            isNext -> (activeChatAudioOffset + 1).coerceAtMost(10)
                            else -> (activeChatAudioOffset - 1).coerceAtLeast(1)
                        }
                        JarvisAccessibilityService.requestAutoPlayVoiceNoteInWhatsApp(offsetFromBottom = activeChatAudioOffset)
                        openWhatsAppChat(context, contact.second)
                        return
                    }
                }
                onSpeakMessage("No hay otro audio en este chat.")
            }

            NavMode.AUDIO_LIST -> {
                if (activeAudioPlaylist.isEmpty()) {
                    activeAudioPlaylist = getRecentOpusFilesFast(limit = 40)
                    activeAudioIndex = 0
                }
                if (activeAudioPlaylist.isEmpty()) {
                    onSpeakMessage("No hay audios para reproducir.")
                    return
                }
                activeAudioIndex = when {
                    isRepeat -> activeAudioIndex.coerceIn(0, activeAudioPlaylist.lastIndex)
                    isNext -> activeAudioIndex + 1
                    else -> activeAudioIndex - 1
                }
                if (activeAudioIndex > activeAudioPlaylist.lastIndex) {
                    activeAudioIndex = activeAudioPlaylist.lastIndex
                    onSpeakMessage("Ya no hay más audios en esta lista.")
                    return
                }
                if (activeAudioIndex < 0) {
                    activeAudioIndex = 0
                }
                playSingleAudioFile(activeAudioPlaylist[activeAudioIndex]) { ok, msg ->
                    if (!ok) onSpeakMessage(msg)
                }
            }
        }
    }

    private fun handleStepNavigation(
        context: Context,
        stepNext: Boolean,
        stepPrevious: Boolean,
        repeatCurrent: Boolean,
        onFinished: (Boolean, String) -> Unit
    ) {
        stepActiveQueue(
            context = context,
            isNext = stepNext,
            isRepeat = repeatCurrent,
            onSpeakMessage = { msg -> onFinished(false, msg) }
        )
    }

    private fun matchesDaySpec(timestamp: Long, dayFilter: String?): Boolean {
        if (dayFilter.isNullOrBlank()) return true
        val norm = normalizeText(dayFilter)
        val calNow = Calendar.getInstance()
        val calTarget = Calendar.getInstance().apply { timeInMillis = timestamp }

        val todayYear = calNow.get(Calendar.YEAR)
        val todayDay = calNow.get(Calendar.DAY_OF_YEAR)

        val calYesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yestYear = calYesterday.get(Calendar.YEAR)
        val yestDay = calYesterday.get(Calendar.DAY_OF_YEAR)

        val isToday = calTarget.get(Calendar.YEAR) == todayYear && calTarget.get(Calendar.DAY_OF_YEAR) == todayDay
        val isYesterday = calTarget.get(Calendar.YEAR) == yestYear && calTarget.get(Calendar.DAY_OF_YEAR) == yestDay

        return when {
            norm.contains("hoy") && norm.contains("ayer") -> isToday || isYesterday
            norm.contains("antes de ayer") || norm.contains("anteayer") -> {
                val calBefore = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -2) }
                calTarget.get(Calendar.YEAR) == calBefore.get(Calendar.YEAR) &&
                    calTarget.get(Calendar.DAY_OF_YEAR) == calBefore.get(Calendar.DAY_OF_YEAR)
            }
            norm.contains("ayer") -> isYesterday
            norm.contains("hoy") -> isToday
            else -> true
        }
    }

    private fun getRecentOpusFilesFast(limit: Int = 50): List<File> {
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
                ?.take(3)
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
            }?.sortedByDescending { it.lastModified() }?.take(15)?.let { candidates.addAll(it) }
        }

        return candidates.sortedByDescending { it.lastModified() }.take(limit)
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
                    onFinished(true, "")
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
            .replace(Regex("^(el|la|los|las|a|de|del|con|chat de|chat con|hoy|ayer)\\s+"), "")
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
