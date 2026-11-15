package com.jarvis.accesible.services

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.jarvis.accesible.core.AppPrefs
import com.jarvis.accesible.core.JarvisCoordinator
import com.jarvis.accesible.whatsapp.WhatsAppRepository
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

data class ScreenChatItem(
    val isAudio: Boolean,
    val text: String,
    val topY: Int,
    val audioOrderInDay: Int, // Índice 0-based entre los audios visibles de ese día (0 = primero de arriba)
    val audioDurationMs: Long = 12_000L
)

/**
 * Servicio de Accesibilidad de Android:
 * 1. Doble toque en Subir Volumen para hablar manos libres (y pausa en el acto cualquier audio de WhatsApp para escuchar limpio).
 * 2. En WhatsApp:
 *    - Graba y envía AUDIOS NATIVOS de WhatsApp manteniendo y deslizando hacia arriba el botón de micrófono del chat
 *      (candado manos libres de WhatsApp) y tocando el botón Enviar al detectar 2 segundos de silencio o doble toque de Volumen Arriba.
 *    - En modo "Mensajes nuevos / Reproducilo", reproduce únicamente el último audio recibido sin ir al principio del día.
 *    - En modo "Leeme los mensajes de [día] de [persona]", sube con scroll hasta que el cartel de ese día ("HOY", "AYER", "VIERNES")
 *      quede arriba de todo para empezar SIEMPRE desde el primer mensaje real de ese día, y baja con scroll si el día es largo.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastVolumeUpDownTime = 0L
    private var pendingChatInspectRunnable: Runnable? = null
    private var scrollAttemptsLeft = 0
    private var searchAlternateTried = false
    private var silenceMonitorAudioRecord: AudioRecord? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        JarvisForegroundService.startServiceIfPossible(applicationContext)
        JarvisCoordinator.getInstance(applicationContext).speechInput.syncWakeWordLoop()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (!AppPrefs.isVolumeButtonTriggerEnabled(applicationContext)) return false

        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeUpDownTime in 80L..650L) {
                lastVolumeUpDownTime = 0L
                // Si estaba grabando un audio nativo en WhatsApp o en el grabador interno, el doble toque lo envía en el acto
                if (isNativeWhatsAppRecordingActive) {
                    finishAndSendNativeWhatsAppRecordingNow()
                    return true
                }
                if (WhatsAppRepository.finishActiveVoiceNoteRecordingIfAny(applicationContext)) {
                    return true
                }
                // Pausamos cualquier nota de voz que esté sonando en pantalla para que el micrófono escuche "Siguiente"/"Anterior" sin interferencia
                pauseAnyPlayingVoiceNoteOnScreen()
                JarvisCoordinator.getInstance(applicationContext).triggerHandsFreeListening(greetBeforeListening = false)
                return true
            }
            lastVolumeUpDownTime = now
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        if (pkg.startsWith("com.whatsapp")) {
            // A) Clic automático en "Enviar" o "Aceptar / OK" al mandar mensaje o audio
            if (now < autoClickSendUntilTimestamp) {
                val root = rootInActiveWindow ?: return
                if (findAndClickAcceptOrSendInWhatsApp(root)) {
                    // Seguimos atentos 1.5s más por si después de "Aceptar" aparece el botón verde de "Enviar"
                    mainHandler.postDelayed({
                        val freshRoot = rootInActiveWindow
                        if (freshRoot != null) {
                            findAndClickAcceptOrSendInWhatsApp(freshRoot)
                        }
                    }, 700L)
                    autoClickSendUntilTimestamp = 0L
                    pendingSendRecipientName = null
                } else if (!pendingSendRecipientName.isNullOrBlank()) {
                    val recipient = pendingSendRecipientName!!
                    if (clickNodeMatchingPhonetically(root, recipient, minTop = 210)) {
                        pendingSendRecipientName = null
                    }
                }
                return
            }

            // B) Búsqueda de un contacto con coincidencia fonética o lupa de WhatsApp
            if (now < autoSearchContactUntilTimestamp && pendingSearchContactName != null) {
                val root = rootInActiveWindow ?: return
                handleWhatsAppSearchBarFlow(root)
                return
            }

            // C) Iniciar grabación de AUDIO NATIVO dentro del chat abierto de WhatsApp
            if (now < autoStartNativeMicUntilTimestamp) {
                val root = rootInActiveWindow ?: return
                val micBounds = findWhatsAppMicButtonBounds(root)
                if (micBounds != null) {
                    autoStartNativeMicUntilTimestamp = 0L
                    val cb = onNativeMicReadyCallback
                    onNativeMicReadyCallback = null
                    cb?.invoke(micBounds)
                }
                return
            }

            // D) Reproducir ÚNICAMENTE el último audio del chat abierto (para "¿Tengo mensajes?" / "Reproducilo")
            if (now < autoPlayLatestSingleAudioUntilTimestamp) {
                pendingChatInspectRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    val freshRoot = rootInActiveWindow ?: return@Runnable
                    if (clickLatestAudioInCurrentChatScreen(freshRoot)) {
                        autoPlayLatestSingleAudioUntilTimestamp = 0L
                        val cb = onLatestSingleAudioClickedCallback
                        onLatestSingleAudioClickedCallback = null
                        cb?.invoke(true)
                    }
                }
                pendingChatInspectRunnable = runnable
                mainHandler.postDelayed(runnable, 750L)
                return
            }

            // E) Inspeccionar el chat abierto para el día pedido ("hoy", "ayer", "viernes", etc.) subiendo hasta el principio del día
            if (now < autoInspectDayChatUntilTimestamp) {
                pendingChatInspectRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    val freshRoot = rootInActiveWindow ?: return@Runnable
                    processOpenChatForRequestedDay(freshRoot)
                }
                pendingChatInspectRunnable = runnable
                mainHandler.postDelayed(runnable, 750L)
                return
            }
        }

        // Automatización en YouTube
        if (pkg.contains("youtube") && now < autoPlayYouTubeUntilTimestamp && now > lastYouTubeClickTime + 3000L) {
            val root = rootInActiveWindow ?: return
            if (findAndClickFirstYouTubeItem(root)) {
                lastYouTubeClickTime = now
                autoPlayYouTubeUntilTimestamp = 0L
            }
        }
    }

    /**
     * Inicia la grabación de un audio NATIVO de WhatsApp dentro del chat abierto:
     * Mantiene presionado el botón de micrófono de WhatsApp y desliza hacia arriba para trabar el candado manos libres.
     * Monitorea el silencio del usuario (2 segundos luego de hablar) o el doble toque de Volumen Arriba para tocar Enviar.
     */
    fun lockNativeWhatsAppMicAndRecord(
        micRect: Rect,
        onRecordingLocked: () -> Unit,
        onFinishedSending: (String) -> Unit
    ) {
        val cx = micRect.exactCenterX().coerceIn(100f, 1050f)
        val cy = micRect.exactCenterY().coerceIn(400f, 2350f)
        val lockY = (cy - 320f).coerceAtLeast(300f)

        // Gesto en 2 trazos continuos: 1) Mantener presionado el botón de micrófono 650ms, 2) Deslizar hacia arriba al candado
        val holdPath = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, cy)
        }
        val swipeUpPath = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, lockY)
        }

        val strokeHold = GestureDescription.StrokeDescription(holdPath, 0L, 650L, true)
        val strokeSwipe = strokeHold.continueStroke(swipeUpPath, 0L, 450L, false)
        val gesture = GestureDescription.Builder()
            .addStroke(strokeHold)
            .addStroke(strokeSwipe)
            .build()

        isNativeWhatsAppRecordingActive = true
        nativeRecordingFinishedCallback = onFinishedSending

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onRecordingLocked()
                startNativeRecordingSilenceMonitor()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                isNativeWhatsAppRecordingActive = false
                nativeRecordingFinishedCallback = null
                onFinishedSending("No pude mantener apretado el micrófono de WhatsApp.")
            }
        }, mainHandler)

        if (!dispatched) {
            isNativeWhatsAppRecordingActive = false
            nativeRecordingFinishedCallback = null
            onFinishedSending("No pude activar el micrófono de WhatsApp.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNativeRecordingSilenceMonitor() {
        stopSilenceMonitorAudioRecord()
        val startTime = System.currentTimeMillis()
        var lastSoundTime = startTime
        var peakAmplitude = 1400
        var hasDetectedSpeech = false

        // Android 10+ permite que un AccessibilityService escuche el nivel de voz para detectar silencio
        val audioRecord = try {
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                val minBuf = AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                ).also {
                    it.startRecording()
                }
            } else null
        } catch (_: Exception) {
            null
        }
        silenceMonitorAudioRecord = audioRecord
        val buffer = ShortArray(1024)

        val checkRunnable = object : Runnable {
            override fun run() {
                if (!isNativeWhatsAppRecordingActive) {
                    stopSilenceMonitorAudioRecord()
                    return
                }
                val now = System.currentTimeMillis()
                val elapsed = now - startTime

                var currentMaxAmp = 0
                if (audioRecord != null && audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = try {
                        audioRecord.read(buffer, 0, buffer.size)
                    } catch (_: Exception) {
                        0
                    }
                    for (i in 0 until read) {
                        val v = abs(buffer[i].toInt())
                        if (v > currentMaxAmp) currentMaxAmp = v
                    }
                }

                if (currentMaxAmp > peakAmplitude) {
                    peakAmplitude = currentMaxAmp
                }

                val threshold = maxOf(1000, (peakAmplitude * 0.35).toInt())
                if (currentMaxAmp > threshold) {
                    lastSoundTime = now
                    if (elapsed > 500L) {
                        hasDetectedSpeech = true
                    }
                }

                val silenceMs = now - lastSoundTime
                // Si habló y luego hizo 2.1 segundos de silencio (o si pasaron 35s máximo), enviamos el audio nativo
                val shouldSend = elapsed >= 35_000L ||
                    (hasDetectedSpeech && elapsed >= 2_400L && silenceMs >= 2_100L) ||
                    (!hasDetectedSpeech && elapsed >= 8_000L && currentMaxAmp == 0)

                if (shouldSend) {
                    finishAndSendNativeWhatsAppRecordingNow()
                } else {
                    mainHandler.postDelayed(this, 250L)
                }
            }
        }
        mainHandler.postDelayed(checkRunnable, 400L)
    }

    private fun stopSilenceMonitorAudioRecord() {
        try {
            silenceMonitorAudioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            silenceMonitorAudioRecord?.release()
        } catch (_: Exception) {
        }
        silenceMonitorAudioRecord = null
    }

    fun finishAndSendNativeWhatsAppRecordingNow() {
        if (!isNativeWhatsAppRecordingActive) return
        isNativeWhatsAppRecordingActive = false
        stopSilenceMonitorAudioRecord()

        val cb = nativeRecordingFinishedCallback
        nativeRecordingFinishedCallback = null

        val root = rootInActiveWindow
        var sent = false
        if (root != null) {
            sent = findAndClickNativeVoiceNoteSendButton(root)
        }
        if (!sent) {
            // Si el botón Enviar de la barra de voz está en la esquina inferior derecha, hacemos clic por nodo o gesto en (980, 2150)
            mainHandler.postDelayed({
                val fresh = rootInActiveWindow
                if (fresh != null) {
                    findAndClickNativeVoiceNoteSendButton(fresh)
                }
            }, 350L)
        }
        cb?.invoke("Audio enviado.")
    }

    private fun findWhatsAppMicButtonBounds(root: AccessibilityNodeInfo): Rect? {
        val micIds = listOf(
            "com.whatsapp:id/voice_note_btn",
            "com.whatsapp.w4b:id/voice_note_btn"
        )
        for (id in micIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.width() > 20 && r.height() > 20 && r.top > 1000) {
                        return r
                    }
                }
            }
        }
        return findMicNodeByDescriptionRecursive(root)
    }

    private fun findMicNodeByDescriptionRecursive(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        if (desc == "mensaje de voz" || desc == "botón de mensaje de voz" || desc == "voice message") {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.top > 1000 && r.width() > 20) return r
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findMicNodeByDescriptionRecursive(child)
            if (res != null) return res
        }
        return null
    }

    private fun findAndClickNativeVoiceNoteSendButton(root: AccessibilityNodeInfo): Boolean {
        val sendIds = listOf(
            "com.whatsapp:id/voice_recorder_send_btn",
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_container",
            "com.whatsapp:id/voice_note_btn",
            "com.whatsapp.w4b:id/voice_recorder_send_btn",
            "com.whatsapp.w4b:id/send"
        )
        for (id in sendIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                    if (id.contains("send") || desc.contains("enviar") || desc.contains("send")) {
                        if (clickNodeOrParent(node)) return true
                    }
                }
            }
        }
        return clickByContentDescriptionOrText(root, listOf("enviar", "send"))
    }

    /**
     * Busca un contacto en WhatsApp tolerando diferencias fonéticas ("Dayana UNLa" <-> "Daiana UNLa").
     */
    private fun handleWhatsAppSearchBarFlow(root: AccessibilityNodeInfo) {
        val query = pendingSearchContactName ?: return

        val editNodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditTexts(root, editNodes)
        val searchInput = editNodes.firstOrNull()

        if (searchInput == null) {
            if (clickNodeMatchingPhonetically(root, query, minTop = 220)) {
                completeSearchChatOpened()
                return
            }
        }

        if (searchInput != null) {
            val currentText = searchInput.text?.toString()?.trim().orEmpty()
            val altQuery = generateAlternativeSpelling(query)
            if (!currentText.equals(query, ignoreCase = true) && !currentText.equals(altQuery, ignoreCase = true)) {
                searchAlternateTried = false
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                }
                searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                return
            } else {
                mainHandler.postDelayed({
                    val fresh = rootInActiveWindow ?: return@postDelayed
                    if (clickFirstSearchResultInWhatsApp(fresh, query)) {
                        completeSearchChatOpened()
                    } else if (!searchAlternateTried && altQuery != query) {
                        searchAlternateTried = true
                        val freshEdits = mutableListOf<AccessibilityNodeInfo>()
                        collectEditTexts(fresh, freshEdits)
                        freshEdits.firstOrNull()?.let { input ->
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, altQuery)
                            }
                            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        }
                    }
                }, 650L)
                return
            }
        }

        val searchIds = listOf(
            "com.whatsapp:id/menuitem_search",
            "com.whatsapp:id/search_bar_inner_layout"
        )
        for (id in searchIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    if (clickNodeOrParent(n)) return
                }
            }
        }
        clickByContentDescriptionOrText(root, listOf("buscar", "search"))
    }

    private fun completeSearchChatOpened() {
        autoSearchContactUntilTimestamp = 0L
        pendingSearchContactName = null
        val cb = onSearchChatOpenedCallback
        onSearchChatOpenedCallback = null
        mainHandler.postDelayed({
            cb?.invoke()
        }, 550L)
    }

    private fun generateAlternativeSpelling(query: String): String {
        val lower = query.lowercase()
        return when {
            lower.contains("dayana") -> lower.replace("dayana", "daiana")
            lower.contains("daiana") -> lower.replace("daiana", "dayana")
            lower.contains("y") -> lower.replace("y", "i")
            lower.contains("i") -> lower.replaceFirst("i", "y")
            else -> query
        }
    }

    private fun clickFirstSearchResultInWhatsApp(root: AccessibilityNodeInfo, query: String): Boolean {
        if (clickNodeMatchingPhonetically(root, query, minTop = 230)) {
            return true
        }
        val rowIds = listOf(
            "com.whatsapp:id/contact_row_container",
            "com.whatsapp:id/conversations_row_contact_name"
        )
        for (id in rowIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    val rect = Rect()
                    n.getBoundsInScreen(rect)
                    if (rect.top > 230 && clickNodeOrParent(n)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Analiza el chat abierto para el día pedido ("hoy", "ayer", "viernes", etc.):
     * - Si el cartel del día pedido todavía NO apareció, o si el cartel del día pedido está muy abajo en la pantalla
     *   (o si ni siquiera apareció un cartel arriba porque el día tiene muchos mensajes más arriba),
     *   hace scroll hacia arriba hasta posicionar el comienzo del día en la parte superior de la pantalla.
     *   Así SIEMPRE empieza desde el PRIMER mensaje real de ese día (solucionando el problema de Daiana UNLa y del viernes).
     */
    private fun processOpenChatForRequestedDay(root: AccessibilityNodeInfo) {
        val targetDay = normalize(requestedDayFilter ?: "hoy")
        val extracted = extractChatItemsByDayFromTree(root, targetDay)

        // ¿Necesitamos seguir subiendo hacia arriba para llegar al PRIMER mensaje de ese día?
        // 1. Si no encontramos el cartel del día pedido (y tampoco vimos un cartel de un día más viejo arriba), subimos.
        // 2. Si encontramos el cartel del día pedido pero está en la mitad inferior de la pantalla (headerTopY > 650),
        //    no hay que subir (más bien el día empieza ahí mismo). Pero si NO vimos el cartel del día pedido
        //    (porque el cartel quedó más arriba fuera de la pantalla), debemos subir hasta ver el cartel del día pedido!
        val needsScrollUpToFindStartOfDay = !extracted.sawExactDayHeaderBanner && scrollAttemptsLeft > 0

        if (needsScrollUpToFindStartOfDay) {
            scrollAttemptsLeft--
            if (scrollChatListBackward(root)) {
                autoInspectDayChatUntilTimestamp = System.currentTimeMillis() + 6000L
                return
            }
        }

        // Si el cartel del día pedido apareció muy abajo (ej. topY > 1450) y no entran sus mensajes en pantalla,
        // bajamos un poco (scroll forward) para que sus mensajes queden visibles en pantalla.
        if (extracted.sawExactDayHeaderBanner && extracted.items.isEmpty() && extracted.headerTopY > 1200) {
            if (scrollChatListForward(root)) {
                mainHandler.postDelayed({
                    val fresh = rootInActiveWindow ?: return@postDelayed
                    val afterScrollDown = extractChatItemsByDayFromTree(fresh, targetDay)
                    deliverExtractedDayItems(afterScrollDown.items)
                }, 650L)
                return
            }
        }

        deliverExtractedDayItems(extracted.items)
    }

    private fun deliverExtractedDayItems(items: List<ScreenChatItem>) {
        autoInspectDayChatUntilTimestamp = 0L
        val cb = onDayChatItemsReadyCallback
        onDayChatItemsReadyCallback = null
        cb?.invoke(items)
    }

    /**
     * Cuando un día tiene muchos mensajes y llegamos al último visible de la pantalla actual,
     * hace scroll hacia abajo (forward) para cargar los siguientes mensajes de ese mismo día.
     */
    fun scrollDownForMoreItemsInSameDay(
        dayFilterRaw: String,
        alreadyReadTexts: Set<String>,
        onMoreItemsLoaded: (List<ScreenChatItem>) -> Unit
    ) {
        val root = rootInActiveWindow
        if (root == null || !scrollChatListForward(root)) {
            onMoreItemsLoaded(emptyList())
            return
        }
        val targetDay = normalize(dayFilterRaw.ifBlank { "hoy" })
        mainHandler.postDelayed({
            val fresh = rootInActiveWindow
            if (fresh == null) {
                onMoreItemsLoaded(emptyList())
                return@postDelayed
            }
            val extracted = extractChatItemsByDayFromTree(fresh, targetDay, allowWithoutBannerIfContinuing = true)
            val newItems = extracted.items.filter { item ->
                item.isAudio || !alreadyReadTexts.contains(item.text)
            }
            onMoreItemsLoaded(newItems)
        }, 700L)
    }

    private data class DayExtractionResult(
        val sawExactDayHeaderBanner: Boolean,
        val headerTopY: Int,
        val items: List<ScreenChatItem>
    )

    private data class RawScreenElement(
        val type: String, // "HEADER", "TEXT", "AUDIO", "DURATION"
        val text: String,
        val topY: Int,
        val durationMs: Long = 0L
    )

    private fun extractChatItemsByDayFromTree(
        root: AccessibilityNodeInfo,
        targetDayNorm: String,
        allowWithoutBannerIfContinuing: Boolean = false
    ): DayExtractionResult {
        val rawElements = mutableListOf<RawScreenElement>()
        collectChatScreenElements(root, rawElements)

        val durationsOnScreen = rawElements.filter { it.type == "DURATION" && it.durationMs > 0L }
        val mainElements = rawElements
            .filter { it.type != "DURATION" }
            .sortedBy { it.topY }
            .distinctBy { "${it.type}_${it.topY / 28}_${it.text.take(25)}" }

        val headers = mainElements.filter { it.type == "HEADER" }

        var currentSectionDay = if (headers.isEmpty()) targetDayNorm else "anterior_al_primer_cartel"
        var sawRequestedHeader = false
        var requestedHeaderTopY = 0
        val matchingItems = mutableListOf<RawScreenElement>()

        for (elem in mainElements) {
            if (elem.type == "HEADER") {
                val hNorm = normalize(elem.text)
                currentSectionDay = hNorm
                if (headerMatchesDay(hNorm, targetDayNorm)) {
                    sawRequestedHeader = true
                    requestedHeaderTopY = elem.topY
                }
            } else {
                if (headerMatchesDay(currentSectionDay, targetDayNorm)) {
                    matchingItems.add(elem)
                }
            }
        }

        // Si ya hicimos todos los intentos de scroll hacia arriba (o estamos continuando hacia abajo en el mismo día)
        // y el cartel quedó más arriba de la pantalla:
        if (!sawRequestedHeader && matchingItems.isEmpty() && (scrollAttemptsLeft <= 0 || allowWithoutBannerIfContinuing)) {
            if (headers.isEmpty()) {
                if (targetDayNorm == "hoy" || allowWithoutBannerIfContinuing) {
                    matchingItems.addAll(mainElements.filter { it.type != "HEADER" })
                }
            } else {
                val firstH = headers.first()
                val firstHNorm = normalize(firstH.text)
                // Si el primer cartel visible en pantalla es "HOY" y el usuario pidió "ayer", los mensajes ARRIBA del cartel "HOY" son de ayer!
                if (targetDayNorm == "ayer" && headerMatchesDay(firstHNorm, "hoy")) {
                    matchingItems.addAll(mainElements.filter { it.type != "HEADER" && it.topY < firstH.topY })
                } else if (targetDayNorm == "hoy" && !headerMatchesDay(firstHNorm, "hoy")) {
                    val lastH = headers.last()
                    if (headerMatchesDay(normalize(lastH.text), "hoy")) {
                        matchingItems.addAll(mainElements.filter { it.type != "HEADER" && it.topY > lastH.topY })
                    }
                }
            }
        }

        var audioCounter = 0
        val finalItems = matchingItems.map { raw ->
            if (raw.type == "AUDIO") {
                val idx = audioCounter
                audioCounter++
                val nearbyDuration = durationsOnScreen
                    .minByOrNull { abs(it.topY - raw.topY) }
                    ?.takeIf { abs(it.topY - raw.topY) < 160 }
                    ?.durationMs
                val finalDurationMs = when {
                    raw.durationMs > 1000L -> raw.durationMs
                    nearbyDuration != null && nearbyDuration > 1000L -> nearbyDuration
                    else -> 12_000L
                }
                ScreenChatItem(
                    isAudio = true,
                    text = raw.text,
                    topY = raw.topY,
                    audioOrderInDay = idx,
                    audioDurationMs = finalDurationMs
                )
            } else {
                ScreenChatItem(
                    isAudio = false,
                    text = raw.text,
                    topY = raw.topY,
                    audioOrderInDay = -1,
                    audioDurationMs = 0L
                )
            }
        }

        return DayExtractionResult(
            sawExactDayHeaderBanner = sawRequestedHeader,
            headerTopY = requestedHeaderTopY,
            items = finalItems
        )
    }

    private fun headerMatchesDay(headerNorm: String, targetDayNorm: String): Boolean {
        if (headerNorm == targetDayNorm) return true
        if (headerNorm.contains(targetDayNorm) || targetDayNorm.contains(headerNorm)) return true

        val targetDates = getCandidateDateStringsForDayFilter(targetDayNorm)
        for (candidate in targetDates) {
            if (headerNorm.contains(candidate) || candidate.contains(headerNorm)) {
                return true
            }
        }
        return false
    }

    private fun getCandidateDateStringsForDayFilter(targetDayNorm: String): List<String> {
        val cal = Calendar.getInstance()
        val localeAr = Locale("es", "AR")
        when (targetDayNorm) {
            "hoy" -> {}
            "ayer" -> cal.add(Calendar.DAY_OF_YEAR, -1)
            "antes de ayer", "anteayer" -> cal.add(Calendar.DAY_OF_YEAR, -2)
            else -> {
                val targetDow = when {
                    targetDayNorm.contains("lunes") -> Calendar.MONDAY
                    targetDayNorm.contains("martes") -> Calendar.TUESDAY
                    targetDayNorm.contains("miercoles") -> Calendar.WEDNESDAY
                    targetDayNorm.contains("jueves") -> Calendar.THURSDAY
                    targetDayNorm.contains("viernes") -> Calendar.FRIDAY
                    targetDayNorm.contains("sabado") -> Calendar.SATURDAY
                    targetDayNorm.contains("domingo") -> Calendar.SUNDAY
                    else -> null
                } ?: return emptyList()

                var steps = 0
                while (cal.get(Calendar.DAY_OF_WEEK) != targetDow && steps < 7) {
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                    steps++
                }
            }
        }

        val d = cal.time
        val f1 = normalize(SimpleDateFormat("EEEE", localeAr).format(d))
        val f2 = normalize(SimpleDateFormat("d 'de' MMMM", localeAr).format(d))
        val f3 = normalize(SimpleDateFormat("d MMM", localeAr).format(d))
        val f4 = normalize(SimpleDateFormat("d/M", localeAr).format(d))
        val f5 = normalize(SimpleDateFormat("dd/MM", localeAr).format(d))
        return listOf(f1, f2, f3, f4, f5).filter { it.isNotEmpty() }
    }

    private fun collectChatScreenElements(
        node: AccessibilityNodeInfo?,
        outList: MutableList<RawScreenElement>
    ) {
        if (node == null) return
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val lowerDesc = desc.lowercase()
        val lowerText = text.lowercase()

        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.top in 165..2080 && rect.bottom > rect.top) {
            val isDateDividerId = viewId.endsWith(":id/conversation_row_date_divider") ||
                viewId.endsWith(":id/date_divider")
            val isDateBannerText = !node.isClickable && text.length in 3..32 && (
                lowerText == "hoy" ||
                    lowerText == "ayer" ||
                    lowerText == "lunes" ||
                    lowerText == "martes" ||
                    lowerText == "miércoles" ||
                    lowerText == "miercoles" ||
                    lowerText == "jueves" ||
                    lowerText == "viernes" ||
                    lowerText == "sábado" ||
                    lowerText == "sabado" ||
                    lowerText == "domingo" ||
                    lowerText.matches(Regex("\\d{1,2} de [a-záéíóú]+.*")) ||
                    lowerText.matches(Regex("\\d{1,2}/\\d{1,2}/\\d{2,4}"))
                )

            if (isDateDividerId || isDateBannerText) {
                if (text.isNotEmpty()) {
                    outList.add(RawScreenElement("HEADER", text, rect.top))
                }
                return
            }

            val parsedDur = parseAudioDurationTextMs(text.ifEmpty { desc })
            if (parsedDur > 0L && (viewId.endsWith(":id/description") || viewId.endsWith(":id/duration") || text.matches(Regex("^\\d{1,2}:\\d{2}$")))) {
                outList.add(RawScreenElement("DURATION", text, rect.top, parsedDur))
            }

            val isAudioControl = lowerDesc == "reproducir" ||
                lowerDesc.startsWith("reproducir ") ||
                lowerDesc == "play" ||
                lowerDesc.contains("mensaje de voz") ||
                lowerText == "reproducir" ||
                viewId.endsWith(":id/control_btn") ||
                viewId.endsWith(":id/audio_play_pause_btn")

            if (isAudioControl) {
                val durFromDesc = parseAudioDurationTextMs(desc)
                outList.add(RawScreenElement("AUDIO", "Audio de voz", rect.top, durFromDesc))
                return
            }

            // Solo tomamos el mensaje principal (no quoted_text de respuesta citada para no leer duplicado ni desordenado)
            val isMessageTextView = viewId.endsWith(":id/message_text") ||
                viewId.endsWith(":id/caption")
            if (isMessageTextView && text.isNotEmpty() && !text.matches(Regex("^\\d{1,2}:\\d{2}(\\s*[ap]\\.?\\s*m\\.?)?$", RegexOption.IGNORE_CASE))) {
                outList.add(RawScreenElement("TEXT", text, rect.top))
                return
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectChatScreenElements(child, outList)
        }
    }

    private fun parseAudioDurationTextMs(raw: String): Long {
        if (raw.isBlank()) return 0L
        val mmSs = Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(raw)
        if (mmSs != null) {
            val mins = mmSs.groupValues[1].toLongOrNull() ?: return 0L
            val secs = mmSs.groupValues[2].toLongOrNull() ?: return 0L
            if (mins in 0..9 && secs in 0..59 && (mins > 0 || secs > 0)) {
                return (mins * 60L + secs) * 1000L
            }
        }
        val secMatch = Regex("(\\d+)\\s*seg").find(raw.lowercase())
        if (secMatch != null) {
            val s = secMatch.groupValues[1].toLongOrNull() ?: 0L
            val mMatch = Regex("(\\d+)\\s*min").find(raw.lowercase())
            val m = mMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            if (m > 0 || s > 0) {
                return (m * 60L + s) * 1000L
            }
        }
        return 0L
    }

    /**
     * Reproduce ÚNICAMENTE el último audio de abajo de todo en el chat abierto (para "¿Tengo mensajes?" / "Reproducilo").
     */
    fun clickLatestAudioInCurrentChatScreen(rootNode: AccessibilityNodeInfo? = rootInActiveWindow): Boolean {
        val root = rootNode ?: return false
        val headers = mutableListOf<RawScreenElement>()
        val audioButtons = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectHeadersAndAudioButtons(root, headers, audioButtons)

        val latestButton = audioButtons.maxByOrNull { it.first }?.second ?: return false
        val desc = latestButton.contentDescription?.toString()?.lowercase().orEmpty()
        if (desc.contains("paus")) return true

        lastVoiceNoteClickTime = System.currentTimeMillis()
        return clickNodeOrParent(latestButton)
    }

    /**
     * Hace clic en el audio de la posición indicada dentro del día pedido (0 = el primer audio de ese día, 1 = el segundo, etc.).
     */
    fun clickAudioInCurrentScreenByDayOrder(targetDayRaw: String, audioOrderInDay: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now < lastVoiceNoteClickTime + 900L) return false

        val root = rootInActiveWindow ?: return false
        val targetDayNorm = normalize(targetDayRaw.ifBlank { "hoy" })

        val headers = mutableListOf<RawScreenElement>()
        val audioButtons = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectHeadersAndAudioButtons(root, headers, audioButtons)

        val sortedHeaders = headers.sortedBy { it.topY }
        val sortedButtons = audioButtons
            .sortedBy { it.first }
            .distinctBy { it.first / 28 }

        val matchingButtons = if (sortedHeaders.isEmpty()) {
            sortedButtons.map { it.second }
        } else {
            val filtered = sortedButtons.filter { (btnTop, _) ->
                val headerAbove = sortedHeaders.lastOrNull { it.topY < btnTop }
                if (headerAbove != null) {
                    headerMatchesDay(normalize(headerAbove.text), targetDayNorm)
                } else {
                    (targetDayNorm == "hoy" && sortedHeaders.none { headerMatchesDay(normalize(it.text), "hoy") }) ||
                        (targetDayNorm == "ayer" && headerMatchesDay(normalize(sortedHeaders.first().text), "hoy"))
                }
            }.map { it.second }
            filtered.ifEmpty { sortedButtons.map { it.second } }
        }

        val targetNode = matchingButtons.getOrNull(audioOrderInDay)
            ?: sortedButtons.getOrNull(audioOrderInDay)?.second
            ?: return false

        val desc = targetNode.contentDescription?.toString()?.lowercase().orEmpty()
        val txt = targetNode.text?.toString()?.lowercase().orEmpty()
        if (desc.contains("paus") || txt.contains("paus")) {
            return true
        }

        lastVoiceNoteClickTime = now
        return clickNodeOrParent(targetNode)
    }

    fun pauseAnyPlayingVoiceNoteOnScreen() {
        val root = rootInActiveWindow ?: return
        pausePlayingNodeRecursive(root)
    }

    private fun pausePlayingNodeRecursive(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val desc = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
        val txt = node.text?.toString()?.lowercase()?.trim().orEmpty()
        if (desc.contains("paus") || txt.contains("paus")) {
            if (clickNodeOrParent(node)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (pausePlayingNodeRecursive(child)) return true
        }
        return false
    }

    fun isAnyVoiceNoteCurrentlyPlayingOnScreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        return hasPauseButtonNode(root)
    }

    private fun hasPauseButtonNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val desc = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
        val txt = node.text?.toString()?.lowercase()?.trim().orEmpty()
        if (desc.contains("paus") || txt.contains("paus")) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasPauseButtonNode(child)) return true
        }
        return false
    }

    private fun collectHeadersAndAudioButtons(
        node: AccessibilityNodeInfo?,
        headersOut: MutableList<RawScreenElement>,
        buttonsOut: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        if (node == null) return
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val lowerDesc = desc.lowercase()
        val lowerText = text.lowercase()

        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.top in 165..2080 && rect.bottom > rect.top) {
            val isDateDivider = viewId.endsWith(":id/conversation_row_date_divider") ||
                viewId.endsWith(":id/date_divider") ||
                (!node.isClickable && text.length in 3..32 && (
                    lowerText == "hoy" || lowerText == "ayer" ||
                        lowerText == "lunes" || lowerText == "martes" ||
                        lowerText == "miércoles" || lowerText == "miercoles" ||
                        lowerText == "jueves" || lowerText == "viernes" ||
                        lowerText == "sábado" || lowerText == "sabado" ||
                        lowerText == "domingo" ||
                        lowerText.matches(Regex("\\d{1,2} de [a-záéíóú]+.*")) ||
                        lowerText.matches(Regex("\\d{1,2}/\\d{1,2}/\\d{2,4}"))
                    ))
            if (isDateDivider && text.isNotEmpty()) {
                headersOut.add(RawScreenElement("HEADER", text, rect.top))
                return
            }

            val isAudioBtn = lowerDesc == "reproducir" ||
                lowerDesc.startsWith("reproducir ") ||
                lowerDesc == "play" ||
                lowerDesc.contains("paus") ||
                lowerText == "reproducir" ||
                viewId.endsWith(":id/control_btn") ||
                viewId.endsWith(":id/audio_play_pause_btn")

            if (isAudioBtn) {
                buttonsOut.add(Pair(rect.top, node))
                return
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectHeadersAndAudioButtons(child, headersOut, buttonsOut)
        }
    }

    private fun scrollChatListBackward(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollChatListBackward(child)) return true
        }
        return false
    }

    private fun scrollChatListForward(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollChatListForward(child)) return true
        }
        return false
    }

    private fun collectEditTexts(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText", ignoreCase = true) || node.isEditable) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditTexts(child, out)
        }
    }

    private fun clickNodeMatchingPhonetically(
        node: AccessibilityNodeInfo?,
        query: String,
        minTop: Int
    ): Boolean {
        if (node == null) return false
        val txt = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.top >= minTop && (
                (txt.isNotEmpty() && isPhoneticContactMatch(txt, query)) ||
                    (desc.isNotEmpty() && isPhoneticContactMatch(desc, query))
                )
        ) {
            if (clickNodeOrParent(node)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickNodeMatchingPhonetically(child, query, minTop)) return true
        }
        return false
    }

    private fun isPhoneticContactMatch(candidateText: String, queryText: String): Boolean {
        val pCand = toSpanishPhonetic(candidateText)
        val pQuery = toSpanishPhonetic(queryText)
        if (pCand.isEmpty() || pQuery.isEmpty()) return false
        if (pCand.contains(pQuery) || pQuery.contains(pCand)) return true

        val candWords = pCand.split(" ").filter { it.length >= 2 }
        val queryWords = pQuery.split(" ").filter { it.length >= 2 }
        if (queryWords.isNotEmpty() && queryWords.all { qw ->
                candWords.any { cw -> cw.contains(qw) || qw.contains(cw) || editDistance(cw, qw) <= 1 }
            }
        ) {
            return true
        }
        return false
    }

    private fun toSpanishPhonetic(input: String): String {
        return normalize(input)
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

    private fun findAndClickAcceptOrSendInWhatsApp(root: AccessibilityNodeInfo): Boolean {
        val sendIds = listOf(
            "android:id/button1", // Botón "Aceptar" / "OK" en diálogos de confirmación de WhatsApp
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_container",
            "com.whatsapp:id/voice_recorder_send_btn",
            "com.whatsapp.w4b:id/send"
        )
        for (id in sendIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (clickNodeOrParent(node)) return true
                }
            }
        }
        return clickByContentDescriptionOrText(root, listOf("enviar", "send", "aceptar", "ok"))
    }

    private fun findAndClickFirstYouTubeItem(root: AccessibilityNodeInfo): Boolean {
        if (clickByContentDescriptionOrText(root, listOf("saltar anuncio", "omitir anuncio", "skip ad"))) {
            return true
        }
        val videoCandidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectYouTubeVideoResults(root, videoCandidates)
        if (videoCandidates.isNotEmpty()) {
            val firstVideo = videoCandidates.minByOrNull { it.first }?.second
            if (firstVideo != null && clickNodeOrParent(firstVideo)) {
                return true
            }
        }
        return false
    }

    private fun collectYouTubeVideoResults(
        node: AccessibilityNodeInfo?,
        outList: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        if (node == null) return
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val lowerDesc = desc.lowercase()

        if (node.isClickable && desc.length > 20 &&
            (lowerDesc.contains("minuto") || lowerDesc.contains("segundo") || lowerDesc.contains("vista") || lowerDesc.contains("hace ")) &&
            !lowerDesc.contains("buscar") && !lowerDesc.contains("filtrar")
        ) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.top in 220..1800 && (rect.bottom - rect.top) > 100) {
                outList.add(Pair(rect.top, node))
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectYouTubeVideoResults(child, outList)
        }
    }

    private fun clickByContentDescriptionOrText(
        node: AccessibilityNodeInfo?,
        keywords: List<String>
    ): Boolean {
        if (node == null) return false
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        if (keywords.any { desc == it || desc.startsWith("$it ") || text == it }) {
            if (clickNodeOrParent(node)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickByContentDescriptionOrText(child, keywords)) return true
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun normalize(input: String): String {
        val temp = Normalizer.normalize(input.lowercase(Locale.getDefault()).trim(), Normalizer.Form.NFD)
        return Regex("\\p{InCombiningDiacriticalMarks}+").replace(temp, "")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopSilenceMonitorAudioRecord()
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        @Volatile
        private var autoClickSendUntilTimestamp: Long = 0L

        @Volatile
        private var pendingSendRecipientName: String? = null

        @Volatile
        private var lastVoiceNoteClickTime: Long = 0L

        @Volatile
        private var autoSearchContactUntilTimestamp: Long = 0L

        @Volatile
        private var pendingSearchContactName: String? = null

        @Volatile
        private var onSearchChatOpenedCallback: (() -> Unit)? = null

        @Volatile
        private var autoInspectDayChatUntilTimestamp: Long = 0L

        @Volatile
        private var requestedDayFilter: String? = null

        @Volatile
        private var onDayChatItemsReadyCallback: ((List<ScreenChatItem>) -> Unit)? = null

        @Volatile
        private var autoStartNativeMicUntilTimestamp: Long = 0L

        @Volatile
        private var onNativeMicReadyCallback: ((Rect) -> Unit)? = null

        @Volatile
        var isNativeWhatsAppRecordingActive: Boolean = false

        @Volatile
        private var nativeRecordingFinishedCallback: ((String) -> Unit)? = null

        @Volatile
        private var autoPlayLatestSingleAudioUntilTimestamp: Long = 0L

        @Volatile
        private var onLatestSingleAudioClickedCallback: ((Boolean) -> Unit)? = null

        @Volatile
        private var autoPlayYouTubeUntilTimestamp: Long = 0L

        @Volatile
        private var lastYouTubeClickTime: Long = 0L

        fun requestAutoClickSendInWhatsApp(recipientName: String? = null) {
            pendingSendRecipientName = recipientName?.trim()?.takeIf { it.isNotEmpty() }
            autoClickSendUntilTimestamp = System.currentTimeMillis() + 14_000L
        }

        fun requestSearchAndOpenChatInWhatsApp(contactName: String, onOpened: (() -> Unit)? = null) {
            pendingSearchContactName = contactName.trim()
            onSearchChatOpenedCallback = onOpened
            instance?.searchAlternateTried = false
            autoSearchContactUntilTimestamp = System.currentTimeMillis() + 12_000L
        }

        fun requestWaitForNativeMicInOpenChat(onMicFound: (Rect) -> Unit) {
            onNativeMicReadyCallback = onMicFound
            autoStartNativeMicUntilTimestamp = System.currentTimeMillis() + 10_000L
        }

        fun requestPlayLatestSingleAudioInOpenChat(onClicked: ((Boolean) -> Unit)? = null) {
            onLatestSingleAudioClickedCallback = onClicked
            autoPlayLatestSingleAudioUntilTimestamp = System.currentTimeMillis() + 8_000L
        }

        fun requestInspectOpenChatForDay(dayFilter: String, onReady: (List<ScreenChatItem>) -> Unit) {
            val cleanDay = dayFilter.ifBlank { "hoy" }.lowercase().trim()
            requestedDayFilter = cleanDay
            onDayChatItemsReadyCallback = onReady
            // Para "hoy" permitimos hasta 8 scrolls hacia arriba (hasta encontrar el cartel "HOY" arriba de todo);
            // para "ayer", "viernes" u otros días anteriores permitimos hasta 20 scrolls hacia arriba para chats largos!
            instance?.scrollAttemptsLeft = if (cleanDay == "hoy") 8 else 20
            autoInspectDayChatUntilTimestamp = System.currentTimeMillis() + 20_000L
        }

        fun requestAutoPlayInYouTube() {
            autoPlayYouTubeUntilTimestamp = System.currentTimeMillis() + 10_000L
        }
    }
}
