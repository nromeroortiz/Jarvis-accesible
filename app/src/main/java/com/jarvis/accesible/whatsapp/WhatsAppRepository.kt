package com.jarvis.accesible.whatsapp

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioAttributes
import android.media.AudioManager
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
    val audioDurationMs: Long = 12_000L,
    val dayFilter: String = "hoy"
)

/**
 * Gestiona toda la interacción con WhatsApp:
 * - Coincidencia fonética inteligente ("Dayana UNLa" <-> "Daiana UNLa").
 * - Unifica textos y audios (recibidos y enviados) sin discriminar, ordenados SIEMPRE DEL PRIMERO AL ÚLTIMO del día elegido.
 * - Respeta la duración real de cada audio (ej. 0:45) antes de pasar al siguiente elemento, permitiendo decir "Siguiente" o "Anterior".
 * - Al terminar de leer un día anterior ("ayer", "viernes", etc.), pregunta si quiere continuar con los mensajes de hoy.
 */
object WhatsAppRepository {

    private val messages = CopyOnWriteArrayList<WhatsAppIncomingMessage>()
    private var isHistoryLoaded = false
    private var mediaPlayer: MediaPlayer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var activeRecordingContactName: String? = null
    private var activeRecordingContactPhone: String? = null
    private var activeRecordingOnCompleted: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isCurrentlyRecordingVoiceNote(): Boolean = mediaRecorder != null

    fun finishActiveVoiceNoteRecordingIfAny(context: Context): Boolean {
        if (mediaRecorder == null) return false
        val name = activeRecordingContactName ?: "contacto"
        val phone = activeRecordingContactPhone
        val cb = activeRecordingOnCompleted
        activeRecordingContactName = null
        activeRecordingContactPhone = null
        activeRecordingOnCompleted = null
        finishRecordingAndShareToWhatsApp(context, name, phone) { msg ->
            cb?.invoke(msg)
        }
        return true
    }

    private var activeQueue: List<UnifiedChatEntry> = emptyList()

    @Volatile
    private var activeQueueIndex: Int = 0

    @Volatile
    private var autoAdvanceGeneration: Int = 0

    @Volatile
    var lastOpenedContactName: String? = null

    @Volatile
    private var activeQueueContactName: String? = null

    @Volatile
    private var activeQueueDayFilter: String = "hoy"

    private var onPastDayFinishedCallback: ((String, String) -> Unit)? = null

    fun isCurrentlyPlayingOrRecordingAudio(): Boolean {
        val mpPlaying = try {
            mediaPlayer?.isPlaying == true
        } catch (_: Exception) {
            false
        }
        return mpPlaying || mediaRecorder != null
    }

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
        val lower = text.lowercase()
        val isVoiceNote = lower.contains("mensaje de voz") ||
            lower.contains("nota de voz") ||
            lower.contains("audio") ||
            text.contains("🎤") ||
            text.contains("🎵")
        onNewNotificationMessage(
            context = context,
            id = "${sender}_${System.currentTimeMillis()}",
            sender = sender,
            text = text,
            isVoiceNote = isVoiceNote,
            replyIntent = replyIntent,
            remoteInputs = remoteInputs
        )
    }

    fun onNewNotificationMessage(
        context: Context,
        id: String,
        sender: String,
        text: String,
        isVoiceNote: Boolean,
        replyIntent: PendingIntent?,
        remoteInputs: Array<RemoteInput>?
    ) {
        ensureHistoryLoaded(context)
        val cleanSender = sender.trim()
        val cleanText = text.trim()

        if (cleanSender.isEmpty() || cleanText.isEmpty()) return
        if (cleanSender.equals("WhatsApp", ignoreCase = true)) return
        if (cleanText.contains("mensajes nuevos", ignoreCase = true)) return
        if (cleanText.contains("buscando mensajes", ignoreCase = true)) return
        if (cleanText.contains("copia de seguridad", ignoreCase = true)) return

        lastOpenedContactName = cleanSender

        val existingIndex = messages.indexOfFirst {
            it.senderName.equals(cleanSender, ignoreCase = true) &&
                it.text == cleanText &&
                (System.currentTimeMillis() - it.timestamp) < 25_000L
        }
        if (existingIndex >= 0) return

        val latestAudioPath = if (isVoiceNote) {
            findLatestWhatsAppVoiceNoteFile(maxAgeMillis = 180_000L)?.absolutePath
        } else {
            null
        }

        messages.add(
            WhatsAppIncomingMessage(
                id = id,
                senderName = cleanSender,
                text = cleanText,
                isVoiceNote = isVoiceNote,
                timestamp = System.currentTimeMillis(),
                replyPendingIntent = replyIntent,
                remoteInputs = remoteInputs,
                associatedAudioPath = latestAudioPath,
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

        val grouped = unread.groupBy { it.senderName }
        // Guardamos el último remitente nombrado para que si el usuario dice "Contestale" sepa a quién responder
        lastOpenedContactName = unread.last().senderName

        val parts = grouped.map { (sender, list) ->
            val audioCount = list.count { it.isVoiceNote }
            val textCount = list.size - audioCount
            when {
                audioCount > 0 && textCount > 0 -> "$sender mandó $textCount mensajes y $audioCount audios"
                audioCount == 1 -> "$sender mandó 1 audio"
                audioCount > 1 -> "$sender mandó $audioCount audios"
                textCount == 1 -> "$sender mandó 1 mensaje: \"${list.last().text}\""
                else -> "$sender mandó $textCount mensajes. El último dice: \"${list.last().text}\""
            }
        }
        unread.forEach { it.isReadByUncle = true }
        saveHistoryToDisk(context)
        return parts.joinToString(". ") + "."
    }

    /**
     * Abre el chat de una persona (usando coincidencia fonética en la agenda o buscándola con la lupa).
     */
    fun openContactChatOnly(context: Context, personQuery: String): String {
        ensureHistoryLoaded(context)
        val contact = findContactByName(context, personQuery)
        if (contact != null) {
            lastOpenedContactName = contact.first
            openWhatsAppChatByPhone(context, contact.second)
            return contact.first
        }

        val notif = messages.lastOrNull {
            isPhoneticMatch(it.senderName, personQuery)
        }
        if (notif?.replyPendingIntent != null) {
            try {
                lastOpenedContactName = notif.senderName
                notif.replyPendingIntent.send()
                return notif.senderName
            } catch (_: Exception) {
            }
        }

        lastOpenedContactName = personQuery.trim()
        JarvisAccessibilityService.requestSearchAndOpenChatInWhatsApp(personQuery.trim())
        launchWhatsAppHome(context)
        return personQuery.trim()
    }

    /**
     * Inicia la lectura y reproducción unificada (textos + audios recibidos y enviados sin discriminar)
     * DEL PRIMERO AL ÚLTIMO del día elegido ("hoy", "ayer", "viernes", etc.).
     * Al finalizar un día anterior ("ayer", "viernes", etc.), invoca onPastDayFinished para preguntar si quiere seguir con los de hoy.
     */
    fun startDayChatPlaybackFromFirstToLast(
        context: Context,
        senderQuery: String?,
        dayFilterRaw: String?,
        onPastDayFinished: ((contactName: String, finishedDay: String) -> Unit)? = null,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        ensureHistoryLoaded(context)
        stopAudioPlayback()
        autoAdvanceGeneration++

        val dayFilter = dayFilterRaw?.ifBlank { "hoy" } ?: "hoy"
        val targetPerson = senderQuery?.takeIf { it.isNotBlank() } ?: lastOpenedContactName
        activeQueueDayFilter = dayFilter
        onPastDayFinishedCallback = onPastDayFinished

        if (!targetPerson.isNullOrBlank()) {
            val contact = findContactByName(context, targetPerson)
            val resolvedDisplayName = contact?.first ?: targetPerson
            lastOpenedContactName = resolvedDisplayName
            activeQueueContactName = resolvedDisplayName

            val onChatReadyToInspect = {
                JarvisAccessibilityService.requestInspectOpenChatForDay(dayFilter) { screenItems ->
                    if (screenItems.isNotEmpty()) {
                        activeQueue = screenItems.map { item: ScreenChatItem ->
                            if (item.isAudio) {
                                UnifiedChatEntry(
                                    isAudio = true,
                                    spokenText = "",
                                    screenAudioOrderInDay = item.audioOrderInDay,
                                    audioDurationMs = item.audioDurationMs,
                                    dayFilter = dayFilter
                                )
                            } else {
                                UnifiedChatEntry(
                                    isAudio = false,
                                    spokenText = item.text,
                                    audioDurationMs = 0L,
                                    dayFilter = dayFilter
                                )
                            }
                        }
                        activeQueueIndex = 0
                        playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
                    } else {
                        val fallbackEntries = buildSavedEntriesForPersonAndDay(resolvedDisplayName, dayFilter)
                        if (fallbackEntries.isNotEmpty()) {
                            activeQueue = fallbackEntries
                            activeQueueIndex = 0
                            playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
                        } else {
                            speakCallback("No encontré mensajes de $dayFilter en el chat de $resolvedDisplayName.", null)
                        }
                    }
                }
            }

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

        // Si no especificó persona, leemos los mensajes/audios guardados del día elegido del primero al último
        activeQueueContactName = null
        val entries = buildGeneralDayEntriesFromFirstToLast(dayFilter)
        if (entries.isEmpty()) {
            speakCallback("No hay mensajes ni audios de $dayFilter.", null)
            return
        }

        activeQueue = entries
        activeQueueIndex = 0
        playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
    }

    fun playSingleRequestedVoiceNote(
        context: Context,
        senderHint: String?,
        whichOffsetFromEnd: Int = 1,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        stopAudioPlayback()
        autoAdvanceGeneration++

        val effectiveSender = senderHint?.takeIf { it.isNotBlank() } ?: lastOpenedContactName
        if (!effectiveSender.isNullOrBlank() && !senderHint.isNullOrBlank()) {
            startDayChatPlaybackFromFirstToLast(context, effectiveSender, "hoy", null, speakCallback)
            return
        }

        val todayFiles = getOpusFilesForDayFromFirstToLast("hoy")
        val filesToUse = if (todayFiles.isNotEmpty()) {
            todayFiles
        } else {
            getRecentOpusFilesNewestFirst(limit = 20).reversed()
        }

        if (filesToUse.isEmpty()) {
            if (!effectiveSender.isNullOrBlank()) {
                startDayChatPlaybackFromFirstToLast(context, effectiveSender, "hoy", null, speakCallback)
            } else {
                speakCallback("No encontré audios de WhatsApp.", null)
            }
            return
        }

        activeQueue = filesToUse.map { f ->
            UnifiedChatEntry(isAudio = true, spokenText = "", localAudioFile = f, dayFilter = "hoy")
        }
        activeQueueIndex = if (whichOffsetFromEnd == 1 && todayFiles.isNotEmpty()) {
            0
        } else {
            (activeQueue.size - whichOffsetFromEnd).coerceIn(0, activeQueue.lastIndex)
        }
        playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
    }

    /**
     * Ejecuta el elemento actual de la cola (texto o audio) y al terminar pasa automáticamente al siguiente.
     * Al terminar el último elemento de un día anterior ("ayer", "viernes", etc.), pregunta si quiere continuar con los de hoy.
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
            // Si antes había un audio en WhatsApp que quiso seguir reproduciendo solo, lo pausamos para leer el texto limpio
            JarvisAccessibilityService.instance?.pauseAnyPlayingVoiceNoteOnScreen()

            speakCallback(entry.spokenText) {
                mainHandler.postDelayed({
                    if (currentGen == autoAdvanceGeneration) {
                        advanceOrFinishDayQueue(context, speakCallback)
                    }
                }, 450L)
            }
        } else if (entry.localAudioFile != null && entry.localAudioFile.exists()) {
            playSingleAudioFile(entry.localAudioFile) { ok, errMsg ->
                if (!ok && errMsg.isNotBlank()) {
                    speakCallback(errMsg, null)
                } else if (currentGen == autoAdvanceGeneration) {
                    advanceOrFinishDayQueue(context, speakCallback)
                }
            }
        } else if (entry.screenAudioOrderInDay >= 0) {
            val clicked = JarvisAccessibilityService.instance?.clickAudioInCurrentScreenByDayOrder(
                entry.dayFilter,
                entry.screenAudioOrderInDay
            ) ?: false

            if (!clicked) {
                mainHandler.postDelayed({
                    if (currentGen == autoAdvanceGeneration) {
                        JarvisAccessibilityService.instance?.clickAudioInCurrentScreenByDayOrder(
                            entry.dayFilter,
                            entry.screenAudioOrderInDay
                        )
                    }
                }, 650L)
            }

            // Esperamos la duración real del audio (ej. 0:45 -> 45000ms) + verificación de reproducción activa
            scheduleScreenAudioCompletionMonitor(
                context = context,
                expectedGen = currentGen,
                expectedDurationMs = entry.audioDurationMs,
                speakCallback = speakCallback
            )
        }
    }

    private fun advanceOrFinishDayQueue(
        context: Context,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        if (activeQueueIndex + 1 <= activeQueue.lastIndex) {
            activeQueueIndex++
            playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
        } else {
            // Llegó al final de los mensajes del día pedido
            val finishedDay = activeQueueDayFilter
            val contact = activeQueueContactName
            if (finishedDay != "hoy" && !contact.isNullOrBlank() && onPastDayFinishedCallback != null) {
                val cb = onPastDayFinishedCallback
                onPastDayFinishedCallback = null
                cb?.invoke(contact, finishedDay)
            }
        }
    }

    /**
     * Espera a que termine el audio en pantalla respetando su duración real (ej. 0:45 -> 45000ms)
     * y verificando AudioManager.isMusicActive + el botón de Pausa en pantalla para no cortarlo jamás a los 4 segundos.
     */
    private fun scheduleScreenAudioCompletionMonitor(
        context: Context,
        expectedGen: Int,
        expectedDurationMs: Long,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val startWait = System.currentTimeMillis()
        // Nunca cortar antes de la duración leída de la burbuja (con un mínimo seguro de 6.5 segundos)
        val minWaitMs = expectedDurationMs.coerceAtLeast(6_500L)
        var consecutiveQuietChecks = 0

        val checkRunnable = object : Runnable {
            override fun run() {
                if (expectedGen != autoAdvanceGeneration) return
                val elapsed = System.currentTimeMillis() - startWait

                // Mientras no haya pasado la duración del audio, seguimos esperando sin interrumpir
                if (elapsed < minWaitMs) {
                    mainHandler.postDelayed(this, 1000L)
                    return
                }

                val hasPauseBtn = JarvisAccessibilityService.instance?.isAnyVoiceNoteCurrentlyPlayingOnScreen() ?: false
                val musicActive = try {
                    audioManager.isMusicActive
                } catch (_: Exception) {
                    false
                }

                // Si el siguiente elemento en la cola es un TEXTO, o si ya dejó de sonar el audio en WhatsApp, avanzamos
                val nextEntryIsText = (activeQueueIndex + 1 <= activeQueue.lastIndex) && !activeQueue[activeQueueIndex + 1].isAudio
                if (nextEntryIsText && elapsed >= expectedDurationMs + 600L) {
                    advanceOrFinishDayQueue(context, speakCallback)
                    return
                }

                if (!hasPauseBtn && !musicActive) {
                    consecutiveQuietChecks++
                } else {
                    consecutiveQuietChecks = 0
                }

                if (consecutiveQuietChecks >= 2 || elapsed >= expectedDurationMs + 2500L) {
                    advanceOrFinishDayQueue(context, speakCallback)
                } else if (elapsed < 240_000L) {
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
        mainHandler.postDelayed(checkRunnable, 1500L)
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
        val filtered = messages.filter { msg ->
            isPhoneticMatch(msg.senderName, personQuery) && matchesDaySpec(msg.timestamp, dayFilter)
        }.sortedBy { it.timestamp }

        return filtered.map { msg ->
            val f = msg.associatedAudioPath?.let { File(it) }?.takeIf { it.exists() }
            if (msg.isVoiceNote && f != null) {
                UnifiedChatEntry(isAudio = true, spokenText = "", localAudioFile = f, dayFilter = dayFilter)
            } else if (msg.isVoiceNote) {
                UnifiedChatEntry(isAudio = false, spokenText = "Audio de voz.", dayFilter = dayFilter)
            } else {
                UnifiedChatEntry(isAudio = false, spokenText = msg.text, dayFilter = dayFilter)
            }
        }
    }

    private fun buildGeneralDayEntriesFromFirstToLast(dayFilter: String): List<UnifiedChatEntry> {
        val timedItems = mutableListOf<Pair<Long, UnifiedChatEntry>>()

        val dayMessages = messages.filter { !it.isVoiceNote && matchesDaySpec(it.timestamp, dayFilter) }
        for (m in dayMessages) {
            timedItems.add(
                Pair(
                    m.timestamp,
                    UnifiedChatEntry(
                        isAudio = false,
                        spokenText = "${m.senderName}: ${m.text}.",
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

    private fun openWhatsAppChatByPhone(context: Context, rawPhone: String) {
        try {
            val cleanPhone = formatPhoneForWhatsApp(rawPhone)
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun launchWhatsAppHome(context: Context) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: context.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        } catch (_: Exception) {
        }
    }

    private fun matchesDaySpec(timestamp: Long, daySpecRaw: String): Boolean {
        val spec = normalizeText(daySpecRaw)
        val calNow = Calendar.getInstance()

        val startOfToday = (calNow.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val startOfYesterday = startOfToday - 24L * 3600L * 1000L
        val startOfBeforeYesterday = startOfToday - 48L * 3600L * 1000L

        return when {
            spec.contains("ayer") && spec.contains("hoy") -> timestamp >= startOfYesterday
            spec.contains("antes de ayer") || spec.contains("anteayer") -> timestamp in startOfBeforeYesterday until startOfYesterday
            spec.contains("ayer") -> timestamp in startOfYesterday until startOfToday
            spec.contains("hoy") -> timestamp >= startOfToday
            else -> {
                val targetDow = when {
                    spec.contains("lunes") -> Calendar.MONDAY
                    spec.contains("martes") -> Calendar.TUESDAY
                    spec.contains("miercoles") -> Calendar.WEDNESDAY
                    spec.contains("jueves") -> Calendar.THURSDAY
                    spec.contains("viernes") -> Calendar.FRIDAY
                    spec.contains("sabado") -> Calendar.SATURDAY
                    spec.contains("domingo") -> Calendar.SUNDAY
                    else -> null
                }
                if (targetDow != null) {
                    val msgCal = Calendar.getInstance().apply { timeInMillis = timestamp }
                    msgCal.get(Calendar.DAY_OF_WEEK) == targetDow &&
                        (System.currentTimeMillis() - timestamp) <= 7L * 24L * 3600L * 1000L
                } else {
                    true
                }
            }
        }
    }

    private fun playSingleAudioFile(file: File, onFinished: (Boolean, String) -> Unit) {
        try {
            val mp = MediaPlayer().apply {
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
                    onFinished(false, "Error al reproducir el audio.")
                    true
                }
                prepare()
                start()
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            stopAudioPlayback()
            onFinished(false, "No pude reproducir el audio.")
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
        stopRecordingInternal()
    }

    private fun findLatestWhatsAppVoiceNoteFile(maxAgeMillis: Long): File? {
        val recent = getRecentOpusFilesNewestFirst(limit = 1)
        val first = recent.firstOrNull() ?: return null
        return if (System.currentTimeMillis() - first.lastModified() <= maxAgeMillis) first else null
    }

    private fun getOpusFilesForDayFromFirstToLast(dayFilter: String): List<File> {
        val all = getRecentOpusFilesNewestFirst(limit = 60)
        return all.filter { matchesDaySpec(it.lastModified(), dayFilter) }
            .sortedBy { it.lastModified() }
    }

    private fun getRecentOpusFilesNewestFirst(limit: Int = 25): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val candidateDirs = listOf(
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"),
            File(root, "WhatsApp/Media/WhatsApp Voice Notes"),
            File(root, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"),
            File(root, "WhatsApp/Media/WhatsApp Audio"),
            File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes")
        )

        val foundFiles = mutableListOf<File>()

        for (baseDir in candidateDirs) {
            if (!baseDir.exists() || !baseDir.isDirectory) continue
            val subFolders = baseDir.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }
                ?.take(6)
                ?: emptyList()

            for (folder in subFolders) {
                folder.listFiles()?.forEach { f ->
                    if (f.isFile && (f.name.endsWith(".opus", true) || f.name.endsWith(".m4a", true) || f.name.endsWith(".mp3", true))) {
                        foundFiles.add(f)
                    }
                }
            }

            baseDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".opus", true) || f.name.endsWith(".m4a", true))) {
                    foundFiles.add(f)
                }
            }
        }

        return foundFiles
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
            .take(limit)
    }

    fun sendTextMessage(context: Context, recipientName: String, messageText: String): String {
        ensureHistoryLoaded(context)
        val effectiveRecipient = recipientName.ifBlank { lastOpenedContactName.orEmpty() }
        if (effectiveRecipient.isBlank()) {
            return "Decime para quién es el mensaje."
        }

        val recentMsg = messages.lastOrNull {
            isPhoneticMatch(it.senderName, effectiveRecipient) &&
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

        val contact = findContactByName(context, effectiveRecipient)
            ?: return "No encontré a $effectiveRecipient en tus contactos."

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

    /**
     * Graba un audio real cuando el usuario dice "Contestale a X", "Respondele a X", "Enviale un audio a X" o "Mandale un audio a X"
     * y lo envía automáticamente al detectar que terminó de hablar (usando umbral adaptativo para que el ruido ambiente no lo trabe).
     */
    fun recordAndSendRealVoiceNote(
        context: Context,
        recipientName: String,
        onRecordingStarted: () -> Unit,
        onCompleted: (String) -> Unit
    ) {
        stopRecordingInternal()
        val targetQuery = recipientName.ifBlank { lastOpenedContactName.orEmpty() }
        if (targetQuery.isBlank()) {
            onCompleted("Decime para quién es el audio.")
            return
        }

        val contact = findContactByName(context, targetQuery)
        val displayName = contact?.first ?: targetQuery
        lastOpenedContactName = displayName

        onRecordingStarted()

        // Iniciamos el grabador 260ms después del pitido para no grabar el pitido ni chocar con el micrófono
        mainHandler.postDelayed({
            try {
                val audioDir = File(context.cacheDir, "audios").apply { mkdirs() }
                val audioFile = File(audioDir, "NotaDeVoz_${System.currentTimeMillis()}.m4a")
                currentRecordingFile = audioFile
                activeRecordingContactName = displayName
                activeRecordingContactPhone = contact?.second
                activeRecordingOnCompleted = onCompleted

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

                val startTime = System.currentTimeMillis()
                var lastSoundTime = startTime
                var peakAmplitude = 1500
                var hasDetectedVoice = false

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

                        if (amplitude > peakAmplitude) {
                            peakAmplitude = amplitude
                        }

                        // Umbral adaptativo: ignora el ruido ambiente constante una vez que la persona habló
                        val speechThreshold = maxOf(1000, (peakAmplitude * 0.35).toInt())
                        if (amplitude > speechThreshold) {
                            lastSoundTime = now
                            if (elapsed > 600L) {
                                hasDetectedVoice = true
                            }
                        }

                        val silenceDuration = now - lastSoundTime
                        val shouldFinish = elapsed >= 35_000L ||
                            (hasDetectedVoice && elapsed >= 2_500L && silenceDuration >= 2_100L) ||
                            (!hasDetectedVoice && elapsed >= 6_500L)

                        if (shouldFinish) {
                            activeRecordingContactName = null
                            activeRecordingContactPhone = null
                            activeRecordingOnCompleted = null
                            finishRecordingAndShareToWhatsApp(context, displayName, contact?.second, onCompleted)
                        } else {
                            mainHandler.postDelayed(this, 250L)
                        }
                    }
                }
                mainHandler.postDelayed(checkRunnable, 400L)

            } catch (e: Exception) {
                stopRecordingInternal()
                onCompleted("No pude grabar el audio.")
            }
        }, 260L)
    }

    private fun finishRecordingAndShareToWhatsApp(
        context: Context,
        contactName: String,
        contactPhone: String?,
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

            // Si tenemos el teléfono enviamos directo al JID y tocamos Enviar;
            // si el contacto no estaba en la agenda del chip (o si WhatsApp abre el selector de contactos),
            // el servicio de accesibilidad selecciona el nombre del contacto y toca Enviar automáticamente.
            JarvisAccessibilityService.requestAutoClickSendInWhatsApp(contactName)

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                setPackage("com.whatsapp")
                putExtra(Intent.EXTRA_STREAM, uri)
                if (!contactPhone.isNullOrBlank()) {
                    val cleanPhone = formatPhoneForWhatsApp(contactPhone)
                    putExtra("jid", "$cleanPhone@s.whatsapp.net")
                }
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

    /**
     * Búsqueda fonética inteligente de contactos en la agenda del teléfono:
     * Entiende que "Dayana UNLa" == "Daiana UNLa", "Banesa" == "Vanesa", "Jise" == "Gise", etc.
     */
    fun findContactByName(context: Context, queryName: String): Pair<String, String>? {
        val normQuery = normalizeText(queryName)
            .replace(Regex("^(el|la|los|las|a|de|del|con|chat de|chat con|hoy|ayer|persona)\\s+"), "")
            .trim()
        if (normQuery.isEmpty()) return null

        val phoneticQuery = toSpanishPhonetic(normQuery)
        var exactPhoneticMatch: Pair<String, String>? = null
        var partialMatch: Pair<String, String>? = null

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
                    val phoneticName = toSpanishPhonetic(normName)

                    if (normName == normQuery) {
                        return Pair(name, number)
                    }
                    if (phoneticName == phoneticQuery && exactPhoneticMatch == null) {
                        exactPhoneticMatch = Pair(name, number)
                    }
                    if (isPhoneticMatch(name, normQuery) && partialMatch == null) {
                        partialMatch = Pair(name, number)
                    }
                } while (cursor.moveToNext())
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return exactPhoneticMatch ?: partialMatch
    }

    fun findMentionedContactInSentence(context: Context, sentence: String): String? {
        val phoneticSentenceWords = toSpanishPhonetic(sentence).split(" ").filter { it.length >= 3 }
        if (phoneticSentenceWords.isEmpty()) return null

        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        var cursor: Cursor? = null
        var bestMatch: String? = null
        var bestWordCount = 0
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
                    val contactWords = toSpanishPhonetic(name).split(" ").filter { it.length >= 2 }
                    if (contactWords.isEmpty()) continue

                    val allWordsPresent = contactWords.all { cw ->
                        phoneticSentenceWords.any { sw -> sw == cw || (cw.length >= 4 && editDistance(sw, cw) <= 1) }
                    }
                    if (allWordsPresent && contactWords.size >= bestWordCount) {
                        bestWordCount = contactWords.size
                        bestMatch = name
                    }
                } while (cursor.moveToNext())
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return bestMatch
    }

    fun isPhoneticMatch(candidateName: String, queryName: String): Boolean {
        val pCand = toSpanishPhonetic(candidateName)
        val pQuery = toSpanishPhonetic(queryName)
        if (pCand.isEmpty() || pQuery.isEmpty()) return false
        if (pCand == pQuery || pCand.contains(pQuery) || pQuery.contains(pCand)) return true

        val candWords = pCand.split(" ").filter { it.length >= 2 }
        val queryWords = pQuery.split(" ").filter { it.length >= 2 }
        if (queryWords.isNotEmpty() && queryWords.all { qw ->
                candWords.any { cw -> cw == qw || cw.contains(qw) || qw.contains(cw) || (qw.length >= 4 && editDistance(cw, qw) <= 1) }
            }
        ) {
            return true
        }
        return false
    }

    fun toSpanishPhonetic(input: String): String {
        return normalizeText(input)
            .replace("ll", "i")
            .replace("y", "i")
            .replace("v", "b")
            .replace("z", "s")
            .replace("ce", "se")
            .replace("ci", "si")
            .replace("h", "")
            .replace(Regex("([a-z])\\1+"), "$1")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[b.length]
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
