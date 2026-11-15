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
import com.jarvis.accesible.services.ScreenChatItem
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

data class UnifiedChatEntry(
    val isAudio: Boolean,
    val spokenText: String,
    val localAudioFile: File? = null,
    val screenAudioOrderInDay: Int = -1,
    val dayFilter: String = "hoy"
)

/**
 * Gestiona toda la interacción con WhatsApp:
 * - Unifica textos y audios sin discriminar, ordenados SIEMPRE DEL PRIMERO AL ÚLTIMO del día elegido ("hoy", "ayer", etc.).
 * - Avanza automáticamente al siguiente elemento cuando termina un mensaje o audio, y permite saltar diciendo "Siguiente" o "Anterior".
 * - Si se llega al final y el usuario dice "Siguiente", avisa siempre: "Ese fue el último mensaje."
 * - Si el contacto no está en la agenda del teléfono, lo busca usando la lupa de WhatsApp.
 */
object WhatsAppRepository {

    private val messages = CopyOnWriteArrayList<WhatsAppIncomingMessage>()
    private var isHistoryLoaded = false
    private var mediaPlayer: MediaPlayer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Cola unificada activa (textos + audios ordenados del primero al último del día)
    private var activeQueue: List<UnifiedChatEntry> = emptyList()

    @Volatile
    private var activeQueueIndex: Int = 0

    @Volatile
    private var autoAdvanceGeneration: Int = 0

    @Volatile
    var lastOpenedContactName: String? = null

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
            val recent = messages.takeLast(200)
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

        val latestFile = if (isAudio) getRecentOpusFilesNewestFirst(limit = 1).firstOrNull() else null

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
        val unread = messages.filter { !it.isReadByUncle }.sortedBy { it.timestamp }
        if (unread.isEmpty()) {
            return "No tenés mensajes nuevos."
        }

        activeQueue = unread.map { msg ->
            val audioFile = msg.associatedAudioPath?.let { File(it) }?.takeIf { it.exists() }
            if (msg.isVoiceNote && audioFile != null) {
                UnifiedChatEntry(
                    isAudio = true,
                    spokenText = "Audio de ${msg.senderName}.",
                    localAudioFile = audioFile
                )
            } else if (msg.isVoiceNote) {
                UnifiedChatEntry(
                    isAudio = false,
                    spokenText = "${msg.senderName} mandó un audio."
                )
            } else {
                UnifiedChatEntry(
                    isAudio = false,
                    spokenText = "${msg.senderName} dijo: ${msg.text}."
                )
            }
        }
        activeQueueIndex = 0

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
     * Abre únicamente el chat de la persona (por Agenda o por la lupa de WhatsApp)
     * para que luego el coordinador le pregunte de qué día quiere escuchar.
     */
    fun openContactChatOnly(context: Context, personQuery: String): String {
        val contact = findContactByName(context, personQuery)
        if (contact != null) {
            lastOpenedContactName = contact.first
            openWhatsAppChatByPhone(context, contact.second)
            return contact.first
        }

        // Si nos escribió antes en notificaciones pero no está agendado con ese nombre
        val norm = normalizeText(personQuery)
        val notif = messages.lastOrNull {
            normalizeText(it.senderName).contains(norm) || norm.contains(normalizeText(it.senderName))
        }
        if (notif?.replyPendingIntent != null) {
            try {
                lastOpenedContactName = notif.senderName
                notif.replyPendingIntent.send()
                return notif.senderName
            } catch (_: Exception) {
            }
        }

        // Si no está en la agenda del chip, abrimos WhatsApp y lo buscamos con la lupa
        lastOpenedContactName = personQuery.trim()
        JarvisAccessibilityService.requestSearchAndOpenChatInWhatsApp(personQuery.trim())
        launchWhatsAppHome(context)
        return personQuery.trim()
    }

    /**
     * Inicia la lectura y reproducción unificada (textos + audios sin discriminar)
     * DEL PRIMERO AL ÚLTIMO del día elegido ("hoy", "ayer", etc.), avanzando solo al terminar cada uno
     * y permitiendo decir "Siguiente" o "Anterior".
     */
    fun startDayChatPlaybackFromFirstToLast(
        context: Context,
        senderQuery: String?,
        dayFilterRaw: String?,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        ensureHistoryLoaded(context)
        stopAudioPlayback()
        autoAdvanceGeneration++
        val dayFilter = dayFilterRaw?.ifBlank { "hoy" } ?: "hoy"
        val targetPerson = senderQuery?.takeIf { it.isNotBlank() } ?: lastOpenedContactName

        // Caso A: Pidió el chat/mensajes/audios de una persona específica para un día ("hoy", "ayer", etc.)
        if (!targetPerson.isNullOrBlank()) {
            lastOpenedContactName = targetPerson
            val onChatReadyToInspect = {
                JarvisAccessibilityService.requestInspectOpenChatForDay(dayFilter) { screenItems ->
                    if (screenItems.isNotEmpty()) {
                        // screenItems ya viene ordenado de arriba hacia abajo (DEL PRIMERO AL ÚLTIMO de ese día)
                        activeQueue = screenItems.map { item: ScreenChatItem ->
                            if (item.isAudio) {
                                UnifiedChatEntry(
                                    isAudio = true,
                                    spokenText = "",
                                    screenAudioOrderInDay = item.audioOrderInDay,
                                    dayFilter = dayFilter
                                )
                            } else {
                                UnifiedChatEntry(
                                    isAudio = false,
                                    spokenText = item.text,
                                    dayFilter = dayFilter
                                )
                            }
                        }
                        activeQueueIndex = 0
                        playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
                    } else {
                        // Respaldo: buscamos en nuestro historial guardado de ese día para esa persona (del primero al último)
                        val fallbackEntries = buildSavedEntriesForPersonAndDay(targetPerson, dayFilter)
                        if (fallbackEntries.isNotEmpty()) {
                            activeQueue = fallbackEntries
                            activeQueueIndex = 0
                            playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
                        } else {
                            speakCallback("No hay mensajes de $dayFilter en el chat de $targetPerson.", null)
                        }
                    }
                }
            }

            val contact = findContactByName(context, targetPerson)
            if (contact != null) {
                onChatReadyToInspect()
                openWhatsAppChatByPhone(context, contact.second)
            } else {
                JarvisAccessibilityService.requestSearchAndOpenChatInWhatsApp(targetPerson) {
                    onChatReadyToInspect()
                }
                launchWhatsAppHome(context)
            }
            return
        }

        // Caso B: No especificó persona (ej: "leeme los mensajes de hoy", "reproducí los audios de hoy", "de ayer")
        // Armamos la lista del día elegido ORDENADA DEL PRIMERO AL ÚLTIMO
        val entries = buildGeneralDayEntriesFromFirstToLast(dayFilter)
        if (entries.isEmpty()) {
            speakCallback("No hay mensajes ni audios de $dayFilter.", null)
            return
        }

        activeQueue = entries
        activeQueueIndex = 0
        playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
    }

    /**
     * Reproducción rápida cuando dice "reproducí el último audio" sin pedir un día entero.
     */
    fun playSingleRequestedVoiceNote(
        context: Context,
        senderHint: String?,
        whichOffsetFromEnd: Int = 1,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        stopAudioPlayback()
        autoAdvanceGeneration++

        if (!senderHint.isNullOrBlank()) {
            startDayChatPlaybackFromFirstToLast(context, senderHint, "hoy", speakCallback)
            return
        }

        // Si no dijo persona, ordenamos los audios de hoy del primero al último y empezamos desde el primero
        val todayFiles = getOpusFilesForDayFromFirstToLast("hoy")
        val filesToUse = if (todayFiles.isNotEmpty()) {
            todayFiles
        } else {
            getRecentOpusFilesNewestFirst(limit = 20).reversed()
        }

        if (filesToUse.isEmpty()) {
            speakCallback("No encontré audios de WhatsApp.", null)
            return
        }

        activeQueue = filesToUse.map { f ->
            UnifiedChatEntry(isAudio = true, spokenText = "", localAudioFile = f, dayFilter = "hoy")
        }
        activeQueueIndex = if (whichOffsetFromEnd == 1 && todayFiles.isNotEmpty()) {
            0 // Empieza del primero del día hacia el último
        } else {
            (activeQueue.size - whichOffsetFromEnd).coerceIn(0, activeQueue.lastIndex)
        }
        playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
    }

    /**
     * Ejecuta el elemento actual de la cola (texto o audio) y al terminar pasa solo al siguiente,
     * salvo que el usuario diga "Siguiente", "Anterior" o "Pará".
     */
    private fun playCurrentQueueEntryAndAutoAdvance(
        context: Context,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        if (activeQueue.isEmpty()) {
            speakCallback("No hay mensajes en la lista.", null)
            return
        }
        if (activeQueueIndex !in activeQueue.indices) {
            speakCallback("Ese fue el último mensaje.", null)
            return
        }

        val currentGen = ++autoAdvanceGeneration
        val entry = activeQueue[activeQueueIndex]

        if (!entry.isAudio) {
            // Es mensaje de texto: lo lee en voz alta y al terminar pasa solo al siguiente
            speakCallback(entry.spokenText) {
                mainHandler.postDelayed({
                    if (currentGen == autoAdvanceGeneration) {
                        if (activeQueueIndex + 1 <= activeQueue.lastIndex) {
                            activeQueueIndex++
                            playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
                        }
                    }
                }, 600L)
            }
        } else if (entry.localAudioFile != null && entry.localAudioFile.exists()) {
            // Es archivo de audio .opus en el teléfono: lo reproduce y al terminar pasa solo al siguiente
            playSingleAudioFile(entry.localAudioFile) { ok, errMsg ->
                if (!ok && errMsg.isNotBlank()) {
                    speakCallback(errMsg, null)
                } else if (currentGen == autoAdvanceGeneration) {
                    if (activeQueueIndex + 1 <= activeQueue.lastIndex) {
                        activeQueueIndex++
                        playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
                    }
                }
            }
        } else if (entry.screenAudioOrderInDay >= 0) {
            // Es un audio dentro del chat abierto en pantalla de WhatsApp:
            // Le damos un único clic a Reproducir y monitoreamos cuándo termina para pasar al siguiente item
            val clicked = JarvisAccessibilityService.instance?.clickAudioInCurrentScreenByDayOrder(
                entry.dayFilter,
                entry.screenAudioOrderInDay
            ) ?: false

            if (!clicked) {
                // Si el nodo aún estaba cargando, reintentamos una vez a los 600ms
                mainHandler.postDelayed({
                    if (currentGen == autoAdvanceGeneration) {
                        JarvisAccessibilityService.instance?.clickAudioInCurrentScreenByDayOrder(
                            entry.dayFilter,
                            entry.screenAudioOrderInDay
                        )
                    }
                }, 600L)
            }

            // Monitoreamos cuándo termina de sonar el audio en WhatsApp para avanzar al siguiente elemento del día
            scheduleScreenAudioCompletionMonitor(context, currentGen, speakCallback)
        }
    }

    private fun scheduleScreenAudioCompletionMonitor(
        context: Context,
        expectedGen: Int,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        val startWait = System.currentTimeMillis()
        val checkRunnable = object : Runnable {
            override fun run() {
                if (expectedGen != autoAdvanceGeneration) return
                val elapsed = System.currentTimeMillis() - startWait
                val isStillPlaying = JarvisAccessibilityService.instance?.isAnyVoiceNoteCurrentlyPlayingOnScreen() ?: false

                // Damos al menos 2.5 segundos desde el clic inicial para que arranque el audio
                if (elapsed > 2800L && !isStillPlaying) {
                    if (activeQueueIndex + 1 <= activeQueue.lastIndex) {
                        val nextEntry = activeQueue[activeQueueIndex + 1]
                        activeQueueIndex++
                        // Si el siguiente elemento es un texto, lo leemos; si es otro audio y WhatsApp no lo arrancó solo, le damos Play
                        playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
                    }
                } else if (elapsed < 120_000L) {
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        mainHandler.postDelayed(checkRunnable, 2800L)
    }

    /**
     * Comando por voz "Siguiente", "Anterior" o "Repetir":
     * Salta inmediatamente al siguiente o anterior elemento (sea texto o audio).
     * Si no hay más mensajes, responde siempre: "Ese fue el último mensaje."
     */
    fun stepActiveQueue(
        context: Context,
        isNext: Boolean,
        isRepeat: Boolean,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        stopAudioPlayback()
        autoAdvanceGeneration++

        if (activeQueue.isEmpty()) {
            speakCallback("No hay mensajes cargados. Decime de quién y de qué día querés escuchar.", null)
            return
        }

        if (isRepeat) {
            activeQueueIndex = activeQueueIndex.coerceIn(0, activeQueue.lastIndex)
            playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
            return
        }

        if (isNext) {
            if (activeQueueIndex + 1 > activeQueue.lastIndex) {
                speakCallback("Ese fue el último mensaje.", null)
                return
            }
            activeQueueIndex++
            playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
        } else {
            if (activeQueueIndex - 1 < 0) {
                activeQueueIndex = 0
                speakCallback("Estás en el primer mensaje.") {
                    playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
                }
                return
            }
            activeQueueIndex--
            playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
        }
    }

    private fun buildSavedEntriesForPersonAndDay(personQuery: String, dayFilter: String): List<UnifiedChatEntry> {
        val normSender = normalizeText(personQuery)
        val filtered = messages.filter { msg ->
            val s = normalizeText(msg.senderName)
            (s.contains(normSender) || normSender.contains(s)) && matchesDaySpec(msg.timestamp, dayFilter)
        }.sortedBy { it.timestamp } // Ordenado DEL PRIMERO AL ÚLTIMO de ese día

        return filtered.map { msg ->
            val f = msg.associatedAudioPath?.let { File(it) }?.takeIf { it.exists() }
            if (msg.isVoiceNote && f != null) {
                UnifiedChatEntry(isAudio = true, spokenText = "", localAudioFile = f, dayFilter = dayFilter)
            } else if (msg.isVoiceNote) {
                UnifiedChatEntry(isAudio = false, spokenText = "${msg.senderName} mandó un audio.", dayFilter = dayFilter)
            } else {
                UnifiedChatEntry(isAudio = false, spokenText = msg.text, dayFilter = dayFilter)
            }
        }
    }

    private fun buildGeneralDayEntriesFromFirstToLast(dayFilter: String): List<UnifiedChatEntry> {
        // Combinamos mensajes guardados y archivos de audio .opus de ese día ordenados del PRIMERO al ÚLTIMO
        val timedItems = mutableListOf<Pair<Long, UnifiedChatEntry>>()

        val dayMessages = messages.filter { !it.isVoiceNote && matchesDaySpec(it.timestamp, dayFilter) }
        for (m in dayMessages) {
            timedItems.add(
                Pair(
                    m.timestamp,
                    UnifiedChatEntry(
                        isAudio = false,
                        spokenText = "${m.senderName} dijo: ${m.text}.",
                        dayFilter = dayFilter
                    )
                )
            )
        }

        val dayAudios = getOpusFilesForDayFromFirstToLast(dayFilter)
        for (f in dayAudios) {
            timedItems.add(
                Pair(
                    f.lastModified(),
                    UnifiedChatEntry(
                        isAudio = true,
                        spokenText = "",
                        localAudioFile = f,
                        dayFilter = dayFilter
                    )
                )
            )
        }

        return timedItems.sortedBy { it.first }.map { it.second }
    }

    private fun getOpusFilesForDayFromFirstToLast(dayFilter: String): List<File> {
        return getRecentOpusFilesNewestFirst(limit = 80)
            .filter { matchesDaySpec(it.lastModified(), dayFilter) }
            .sortedBy { it.lastModified() } // Del primero de ese día al último
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
            else -> {
                val dowName = SimpleDateFormat("EEEE", Locale("es", "AR")).format(Date(timestamp))
                normalizeText(dowName).contains(norm) || norm.contains(normalizeText(dowName))
            }
        }
    }

    private fun getRecentOpusFilesNewestFirst(limit: Int = 60): List<File> {
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

    fun openWhatsAppChatByPhone(context: Context, rawPhone: String): Boolean {
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

    private fun launchWhatsAppHome(context: Context) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        } catch (_: Exception) {
        }
    }

    fun stopAudioPlayback() {
        autoAdvanceGeneration++
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
                    val words = normName.split(" ")
                    if (words.contains(normQuery) || normName.contains(normQuery) || normQuery.contains(normName)) {
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
