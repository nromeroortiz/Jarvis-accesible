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
import java.util.Calendar
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

    private var activeQueue: List<UnifiedChatEntry> = emptyList()
    private val alreadyReadTextsInCurrentDay = mutableSetOf<String>()

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
        return mpPlaying || mediaRecorder != null || JarvisAccessibilityService.isNativeWhatsAppRecordingActive
    }

    fun isCurrentlyRecordingVoiceNote(): Boolean =
        mediaRecorder != null || JarvisAccessibilityService.isNativeWhatsAppRecordingActive

    fun finishActiveVoiceNoteRecordingIfAny(context: Context): Boolean {
        if (JarvisAccessibilityService.isNativeWhatsAppRecordingActive) {
            JarvisAccessibilityService.instance?.finishAndSendNativeWhatsAppRecordingNow()
            return true
        }
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

    /**
     * MODO RÁPIDO DE CHARLA EN VIVO ("¿Tengo mensajes?", "¿Hay algún mensaje?"):
     * - Te dice quién te escribió y te lee directamente los mensajes nuevos.
     * - Si el mensaje nuevo es un audio, apenas termina de avisarte ("Fulano mandó un audio"),
     *   reproduce ÚNICAMENTE ese audio nuevo (sin volver al principio del día).
     */
    fun checkAndPlayUnreadImmediately(
        context: Context,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        ensureHistoryLoaded(context)
        stopAudioPlayback()
        autoAdvanceGeneration++

        val unread = messages.filter { !it.isReadByUncle }
        if (unread.isEmpty()) {
            speakCallback("No tenés mensajes nuevos.", null)
            return
        }

        lastOpenedContactName = unread.last().senderName
        unread.forEach { it.isReadByUncle = true }
        saveHistoryToDisk(context)

        // Armamos una cola rápida SOLO con los mensajes/audios nuevos sin leer (no todo el día)
        val liveQueue = unread.map { msg ->
            if (msg.isVoiceNote) {
                val localFile = msg.associatedAudioPath?.let { File(it) }?.takeIf { it.exists() }
                    ?: findLatestWhatsAppVoiceNoteFile(maxAgeMillis = 600_000L)
                UnifiedChatEntry(
                    isAudio = true,
                    spokenText = "${msg.senderName} mandó un audio.",
                    localAudioFile = localFile,
                    screenAudioOrderInDay = -99, // Señal de reproducir solo el último audio en el chat de msg.senderName
                    dayFilter = msg.senderName
                )
            } else {
                UnifiedChatEntry(
                    isAudio = false,
                    spokenText = "${msg.senderName} dice: ${msg.text}.",
                    dayFilter = "hoy"
                )
            }
        }

        activeQueue = liveQueue
        activeQueueIndex = 0
        playLiveUnreadQueue(context, speakCallback)
    }

    private fun playLiveUnreadQueue(
        context: Context,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        if (activeQueueIndex !in activeQueue.indices) return
        val currentGen = ++autoAdvanceGeneration
        val entry = activeQueue[activeQueueIndex]

        if (!entry.isAudio) {
            speakCallback(entry.spokenText) {
                if (currentGen == autoAdvanceGeneration && activeQueueIndex + 1 <= activeQueue.lastIndex) {
                    activeQueueIndex++
                    playLiveUnreadQueue(context, speakCallback)
                }
            }
        } else {
            // Primero anuncia "Fulano mandó un audio" y en el acto lo reproduce
            speakCallback(entry.spokenText) {
                if (currentGen != autoAdvanceGeneration) return@speakCallback
                if (entry.localAudioFile != null && entry.localAudioFile.exists()) {
                    playSingleAudioFile(entry.localAudioFile) { _, _ ->
                        if (currentGen == autoAdvanceGeneration && activeQueueIndex + 1 <= activeQueue.lastIndex) {
                            activeQueueIndex++
                            playLiveUnreadQueue(context, speakCallback)
                        }
                    }
                } else {
                    playLatestSingleAudioOnly(context, entry.dayFilter, speakCallback)
                }
            }
        }
    }

    /**
     * Cuando el usuario dice "Reproducilo", "Escuchalo" o "Reproducí el último audio":
     * Reproduce ÚNICAMENTE ese último audio recibido (sin ir al primer audio del día).
     */
    fun playLatestSingleAudioOnly(
        context: Context,
        senderHint: String?,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        stopAudioPlayback()
        autoAdvanceGeneration++

        val recentFile = findLatestWhatsAppVoiceNoteFile(maxAgeMillis = 900_000L)
        if (recentFile != null && senderHint.isNullOrBlank()) {
            playSingleAudioFile(recentFile) { ok, err ->
                if (!ok && err.isNotBlank()) speakCallback(err, null)
            }
            return
        }

        val targetPerson = senderHint?.takeIf { it.isNotBlank() } ?: lastOpenedContactName
        if (!targetPerson.isNullOrBlank()) {
            val contact = findContactByName(context, targetPerson)
            if (contact != null) {
                lastOpenedContactName = contact.first
                JarvisAccessibilityService.requestPlayLatestSingleAudioInOpenChat()
                openWhatsAppChatByPhone(context, contact.second)
                return
            } else {
                lastOpenedContactName = targetPerson
                JarvisAccessibilityService.requestSearchAndOpenChatInWhatsApp(targetPerson) {
                    JarvisAccessibilityService.requestPlayLatestSingleAudioInOpenChat()
                }
                launchWhatsAppHome(context)
                return
            }
        }

        // Si el chat ya está abierto en pantalla o hay archivos .opus locales, reproducimos solo el último
        val clickedOnScreen = JarvisAccessibilityService.instance?.clickLatestAudioInCurrentChatScreen() ?: false
        if (clickedOnScreen) return

        val lastOpus = getRecentOpusFilesNewestFirst(limit = 1).firstOrNull()
        if (lastOpus != null) {
            playSingleAudioFile(lastOpus) { ok, err ->
                if (!ok && err.isNotBlank()) speakCallback(err, null)
            }
        } else {
            speakCallback("No encontré el audio para reproducir.", null)
        }
    }

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
     * MODO HISTORIAL POR DÍA ("Leeme los mensajes de hoy/ayer/viernes de X"):
     * Sube hasta el cartel de ese día para arrancar SIEMPRE desde el primer mensaje de ese día hasta el último,
     * y si el día es largo, baja con scroll automáticamente para seguir leyendo hasta terminar ese día.
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
        alreadyReadTextsInCurrentDay.clear()

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
                        activeQueue = mapScreenItemsToEntries(screenItems, dayFilter)
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

    private fun mapScreenItemsToEntries(screenItems: List<ScreenChatItem>, dayFilter: String): List<UnifiedChatEntry> {
        return screenItems.map { item ->
            if (!item.isAudio) {
                alreadyReadTextsInCurrentDay.add(item.text)
                UnifiedChatEntry(
                    isAudio = false,
                    spokenText = item.text,
                    audioDurationMs = 0L,
                    dayFilter = dayFilter
                )
            } else {
                UnifiedChatEntry(
                    isAudio = true,
                    spokenText = "",
                    screenAudioOrderInDay = item.audioOrderInDay,
                    audioDurationMs = item.audioDurationMs,
                    dayFilter = dayFilter
                )
            }
        }
    }

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
            JarvisAccessibilityService.instance?.pauseAnyPlayingVoiceNoteOnScreen()

            speakCallback(entry.spokenText) {
                mainHandler.postDelayed({
                    if (currentGen == autoAdvanceGeneration) {
                        advanceOrFinishDayQueue(context, speakCallback)
                    }
                }, 400L)
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
                }, 600L)
            }

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
            return
        }

        // Llegamos al último mensaje visible en pantalla: si el día es largo, probamos bajar con scroll para ver si siguen más mensajes de ese día
        val service = JarvisAccessibilityService.instance
        if (service != null && !activeQueueContactName.isNullOrBlank()) {
            service.scrollDownForMoreItemsInSameDay(activeQueueDayFilter, alreadyReadTextsInCurrentDay) { moreScreenItems ->
                if (moreScreenItems.isNotEmpty()) {
                    activeQueue = mapScreenItemsToEntries(moreScreenItems, activeQueueDayFilter)
                    activeQueueIndex = 0
                    playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
                } else {
                    notifyEndOfDayReached()
                }
            }
        } else {
            notifyEndOfDayReached()
        }
    }

    private fun notifyEndOfDayReached() {
        val finishedDay = activeQueueDayFilter
        val contact = activeQueueContactName
        if (finishedDay != "hoy" && !contact.isNullOrBlank() && onPastDayFinishedCallback != null) {
            val cb = onPastDayFinishedCallback
            onPastDayFinishedCallback = null
            cb?.invoke(contact, finishedDay)
        }
    }

    private fun scheduleScreenAudioCompletionMonitor(
        context: Context,
        expectedGen: Int,
        expectedDurationMs: Long,
        speakCallback: (String, (() -> Unit)?) -> Unit
    ) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val startWait = System.currentTimeMillis()
        val minWaitMs = expectedDurationMs.coerceAtLeast(6_500L)
        var consecutiveQuietChecks = 0

        val checkRunnable = object : Runnable {
            override fun run() {
                if (expectedGen != autoAdvanceGeneration) return
                val elapsed = System.currentTimeMillis() - startWait

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
                val service = JarvisAccessibilityService.instance
                if (service != null && !activeQueueContactName.isNullOrBlank()) {
                    service.scrollDownForMoreItemsInSameDay(activeQueueDayFilter, alreadyReadTextsInCurrentDay) { moreScreenItems ->
                        if (moreScreenItems.isNotEmpty()) {
                            activeQueue = mapScreenItemsToEntries(moreScreenItems, activeQueueDayFilter)
                            activeQueueIndex = 0
                            playCurrentQueueEntryAndAutoAdvance(context, speakCallback)
                        } else {
                            speakCallback("Ese fue el último mensaje.", null)
                        }
                    }
                } else {
                    speakCallback("Ese fue el último mensaje.", null)
                }
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
            JarvisAccessibilityService.requestAutoClickSendInWhatsApp(contact.first)

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
     * Graba y envía un AUDIO NATIVO directamente dentro del chat de WhatsApp (usando el candado manos libres del micrófono de WhatsApp),
     * para que nunca aparezca como archivo externo ni pida "Aceptar".
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

        val service = JarvisAccessibilityService.instance
        if (service != null) {
            val startLockOnMic: (android.graphics.Rect) -> Unit = { micRect ->
                onRecordingStarted()
                mainHandler.postDelayed({
                    service.lockNativeWhatsAppMicAndRecord(
                        micRect = micRect,
                        onRecordingLocked = {},
                        onGestureFailedFallback = {
                            recordInternalFallbackVoiceNote(
                                context = context,
                                displayName = displayName,
                                contactPhone = contact?.second,
                                onRecordingStarted = {},
                                onCompleted = onCompleted
                            )
                        },
                        onFinishedSending = { statusMsg ->
                            onCompleted(statusMsg)
                        }
                    )
                }, 260L)
            }

            // Si el chat ya quedó abierto mientras Wally decía "Grabando audio para...", usamos el micrófono en el acto
            val existingMic = service.getVisibleWhatsAppMicBoundsNow()
            if (existingMic != null) {
                startLockOnMic(existingMic)
                return
            }

            JarvisAccessibilityService.requestWaitForNativeMicInOpenChat { micRect ->
                startLockOnMic(micRect)
            }

            if (contact != null) {
                openWhatsAppChatByPhone(context, contact.second)
            } else {
                JarvisAccessibilityService.requestSearchAndOpenChatInWhatsApp(targetQuery)
                launchWhatsAppHome(context)
            }
            return
        }

        // Respaldo si el servicio de accesibilidad estuviera apagado
        recordInternalFallbackVoiceNote(context, displayName, contact?.second, onRecordingStarted, onCompleted)
    }

    private fun recordInternalFallbackVoiceNote(
        context: Context,
        displayName: String,
        contactPhone: String?,
        onRecordingStarted: () -> Unit,
        onCompleted: (String) -> Unit
    ) {
        onRecordingStarted()
        mainHandler.postDelayed({
            try {
                val audioDir = File(context.cacheDir, "audios").apply { mkdirs() }
                val audioFile = File(audioDir, "NotaDeVoz_${System.currentTimeMillis()}.m4a")
                currentRecordingFile = audioFile
                activeRecordingContactName = displayName
                activeRecordingContactPhone = contactPhone
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
                            finishRecordingAndShareToWhatsApp(context, displayName, contactPhone, onCompleted)
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
