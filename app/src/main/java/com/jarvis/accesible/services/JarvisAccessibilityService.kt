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
import com.jarvis.accesible.actions.SystemActionsController
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
    val audioOrderInDay: Int,
    val audioDurationMs: Long = 6_000L,
    val isOutgoing: Boolean = false
)

class JarvisAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastVolumeUpDownTime = 0L
    private var pendingChatInspectRunnable: Runnable? = null
    private var scrollAttemptsLeft = 0
    private var scrollDownPagesLeft = 0
    private var searchAlternateTried = false
    private var silenceMonitorAudioRecord: AudioRecord? = null

    private fun screenHeightPx(): Int =
        resources.displayMetrics.heightPixels.coerceAtLeast(1280)

    private fun screenWidthPx(): Int =
        resources.displayMetrics.widthPixels.coerceAtLeast(720)

    private fun densityScale(): Float =
        resources.displayMetrics.density.coerceAtLeast(1.5f)

    private fun chatContentMinTopPx(): Int =
        (screenHeightPx() * 0.07f).toInt().coerceIn(90, 240)

    private fun chatContentMaxBottomPx(): Int =
        (screenHeightPx() * 0.93f).toInt().coerceAtLeast(1100)

    private fun rowDedupBucketPx(): Int =
        (12f * densityScale()).toInt().coerceIn(14, 34)

    @Volatile
    private var currentForegroundPackage: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        JarvisForegroundService.startServiceIfPossible(applicationContext)
        JarvisCoordinator.getInstance(applicationContext).speechInput.syncWakeWordLoop()
    }

    /**
     * Devuelve true si el usuario está usando Instagram, TikTok, YouTube, Spotify o Netflix
     * para que la escucha ambiental por voz ("Wally") no le pause los videos cada varios segundos.
     */
    fun isMediaAppInForeground(): Boolean {
        val pkg = (rootInActiveWindow?.packageName?.toString() ?: currentForegroundPackage).lowercase()
        return pkg.contains("instagram") ||
            pkg.contains("musically") ||
            pkg.contains("trill") ||
            pkg.contains("tiktok") ||
            pkg.contains("youtube") ||
            pkg.contains("spotify") ||
            pkg.contains("netflix")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (!AppPrefs.isVolumeButtonTriggerEnabled(applicationContext)) return false

        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeUpDownTime in 80L..650L) {
                lastVolumeUpDownTime = 0L
                if (isNativeWhatsAppRecordingActive) {
                    finishAndSendNativeWhatsAppRecordingNow()
                    return true
                }
                if (WhatsAppRepository.finishActiveVoiceNoteRecordingIfAny(applicationContext)) {
                    return true
                }
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
        if (!pkg.startsWith("com.android.systemui") && !pkg.startsWith("android")) {
            currentForegroundPackage = pkg
        }
        val now = System.currentTimeMillis()

        if (pkg.startsWith("com.whatsapp")) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                now > lastOutdatedWhatsAppTriggerTime + 180_000L
            ) {
                val rootForOutdated = rootInActiveWindow
                if (rootForOutdated != null && detectWhatsAppOutdatedScreen(rootForOutdated)) {
                    lastOutdatedWhatsAppTriggerTime = now
                    val coordinator = JarvisCoordinator.getInstance(applicationContext)
                    coordinator.speaker.speak("WhatsApp pide actualizarse. Abriendo Play Store para actualizarlo solo.") {
                        SystemActionsController.openWhatsAppInPlayStore(applicationContext) { statusMsg ->
                            coordinator.speaker.speak(statusMsg)
                        }
                    }
                    clickByContentDescriptionOrText(
                        rootForOutdated,
                        listOf("actualizar", "actualizar whatsapp", "descargar", "ir a google play", "update")
                    )
                    return
                }
            }

            val hasActiveWhatsAppTask = now < autoClickSendUntilTimestamp ||
                (now < autoSearchContactUntilTimestamp && pendingSearchContactName != null) ||
                now < autoStartNativeMicUntilTimestamp ||
                now < autoPlayLatestSingleAudioUntilTimestamp ||
                now < autoInspectDayChatUntilTimestamp ||
                now < autoInspectBottomRecentUntilTimestamp

            // Salida ultrarrápida para celulares de bajos recursos (A01 / A21s):
            // si no hay ninguna tarea activa de Wally en curso, no pedimos rootInActiveWindow ni recorremos nodos.
            if (!hasActiveWhatsAppTask) return

            // A) Clic automático en "Aceptar / OK" y luego en "Enviar" al mandar mensaje o audio
            if (now < autoClickSendUntilTimestamp) {
                val root = rootInActiveWindow ?: return
                if (findAndClickAcceptOrSendInWhatsApp(root)) {
                    // Seguimos verificando a los 600ms, 1200ms y 1800ms porque tras tocar "Aceptar" aparece la pantalla con el botón verde "Enviar"
                    for (delayMs in listOf(600L, 1200L, 1800L)) {
                        mainHandler.postDelayed({
                            val freshRoot = rootInActiveWindow
                            if (freshRoot != null) {
                                findAndClickAcceptOrSendInWhatsApp(freshRoot)
                            }
                        }, delayMs)
                    }
                    autoClickSendUntilTimestamp = 0L
                    pendingSendRecipientName = null
                } else if (!pendingSendRecipientName.isNullOrBlank()) {
                    val recipient = pendingSendRecipientName!!
                    if (clickNodeMatchingPhonetically(root, recipient, minTop = chatContentMinTopPx())) {
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
                    // Esperamos 400ms para que termine la animación de apertura del chat antes de apoyar el gesto en el micrófono
                    mainHandler.postDelayed({
                        val freshRoot = rootInActiveWindow
                        val freshBounds = if (freshRoot != null) findWhatsAppMicButtonBounds(freshRoot) ?: micBounds else micBounds
                        cb?.invoke(freshBounds)
                    }, 400L)
                }
                return
            }

            // D) Reproducir ÚNICAMENTE el último audio del chat abierto (para "¿Tengo mensajes?" / "Reproducilo")
            if (now < autoPlayLatestSingleAudioUntilTimestamp) {
                if (pendingChatInspectRunnable == null) {
                    val runnable = Runnable {
                        pendingChatInspectRunnable = null
                        val freshRoot = rootInActiveWindow ?: return@Runnable
                        if (clickLatestAudioInCurrentChatScreen(freshRoot)) {
                            autoPlayLatestSingleAudioUntilTimestamp = 0L
                            val cb = onLatestSingleAudioClickedCallback
                            onLatestSingleAudioClickedCallback = null
                            cb?.invoke(true)
                        }
                    }
                    pendingChatInspectRunnable = runnable
                    mainHandler.postDelayed(runnable, 360L)
                }
                return
            }

            // E) Inspeccionar el chat abierto para el día pedido ("hoy", "ayer", "viernes", etc.) subiendo hasta el principio del día
            if (now < autoInspectDayChatUntilTimestamp) {
                if (pendingChatInspectRunnable == null) {
                    val runnable = Runnable {
                        pendingChatInspectRunnable = null
                        val freshRoot = rootInActiveWindow ?: return@Runnable
                        processOpenChatForRequestedDay(freshRoot)
                    }
                    pendingChatInspectRunnable = runnable
                    mainHandler.postDelayed(runnable, 360L)
                }
                return
            }

            // F) Inspeccionar los últimos mensajes/audios recibidos al final del chat abierto ("el último", "anteúltimo", "últimos 3")
            if (now < autoInspectBottomRecentUntilTimestamp) {
                if (pendingChatInspectRunnable == null) {
                    val runnable = Runnable {
                        pendingChatInspectRunnable = null
                        val freshRoot = rootInActiveWindow ?: return@Runnable
                        val items = extractAllVisibleChatItemsFromCurrentScreen(freshRoot)
                        if (items.isNotEmpty()) {
                            autoInspectBottomRecentUntilTimestamp = 0L
                            val cb = onBottomRecentItemsReadyCallback
                            onBottomRecentItemsReadyCallback = null
                            cb?.invoke(items)
                        }
                    }
                    pendingChatInspectRunnable = runnable
                    mainHandler.postDelayed(runnable, 360L)
                }
                return
            }
        }

        // Automatización en Google Play Store para actualizar WhatsApp
        if (pkg == "com.android.vending" && now < autoUpdateWhatsAppUntilTimestamp) {
            val root = rootInActiveWindow ?: return
            handlePlayStoreWhatsAppUpdate(root, now)
            return
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

    private fun detectWhatsAppOutdatedScreen(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val allTexts = mutableListOf<String>()
        collectAllVisibleTexts(node, allTexts, maxNodes = 80)
        val joined = normalize(allTexts.joinToString(" "))
        val hasExpiredText = joined.contains("esta version de whatsapp caduco") ||
            joined.contains("whatsapp caduco") ||
            joined.contains("version de whatsapp vencio") ||
            joined.contains("actualiza whatsapp para seguir") ||
            joined.contains("actualizar whatsapp") ||
            joined.contains("descarga la version mas reciente de whatsapp") ||
            joined.contains("this version of whatsapp has expired")
        return hasExpiredText
    }

    private fun collectAllVisibleTexts(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        maxNodes: Int
    ) {
        if (node == null || out.size >= maxNodes) return
        val txt = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (txt.isNotEmpty()) out.add(txt)
        if (desc.isNotEmpty() && desc != txt) out.add(desc)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllVisibleTexts(child, out, maxNodes)
        }
    }

    private fun handlePlayStoreWhatsAppUpdate(root: AccessibilityNodeInfo, now: Long) {
        // 1. Si está el botón "Actualizar" / "Update", lo tocamos de inmediato
        if (clickByContentDescriptionOrText(root, listOf("actualizar", "update"))) {
            autoUpdateWhatsAppUntilTimestamp = 0L
            val cb = onWhatsAppUpdateStatusCallback
            onWhatsAppUpdateStatusCallback = null
            cb?.invoke("Actualizando WhatsApp. Esperá un minutito a que termine de descargarse e instalarse solo.")
            return
        }

        // 2. Si ya pasaron al menos 2.2 segundos desde que se abrió Play Store y vemos "Abrir" y "Desinstalar" (sin "Actualizar"), ya está al día
        if (now - autoUpdateWhatsAppStartedAt >= 2_200L) {
            val texts = mutableListOf<String>()
            collectAllVisibleTexts(root, texts, maxNodes = 90)
            val normTexts = texts.map { normalize(it) }
            val hasOpenBtn = normTexts.any { it == "abrir" || it == "open" }
            val hasUninstallBtn = normTexts.any { it == "desinstalar" || it == "uninstall" }
            val hasUpdateBtn = normTexts.any { it == "actualizar" || it == "update" }

            if (hasOpenBtn && hasUninstallBtn && !hasUpdateBtn) {
                autoUpdateWhatsAppUntilTimestamp = 0L
                val cb = onWhatsAppUpdateStatusCallback
                onWhatsAppUpdateStatusCallback = null
                cb?.invoke("WhatsApp ya está actualizado.")
            }
        }
    }

    /**
     * Busca el botón de micrófono en la pantalla actual de WhatsApp si el chat ya está abierto.
     */
    fun getVisibleWhatsAppMicBoundsNow(): Rect? {
        val root = rootInActiveWindow ?: return null
        return findWhatsAppMicButtonBounds(root)
    }

    /**
     * Inicia la grabación de un audio NATIVO de WhatsApp dentro del chat abierto:
     * Despacha primero el trazo de mantener presionado (strokeHold) y en su onCompleted despacha la continuación
     * deslizando hacia arriba al candado (strokeSwipe), que es la forma exigida por Android InputDispatcher.
     * Si el teléfono no permite el gesto, llama a onGestureFailedFallback para grabar y auto-aceptar sin dar error.
     */
    fun lockNativeWhatsAppMicAndRecord(
        micRect: Rect,
        onRecordingLocked: () -> Unit,
        onGestureFailedFallback: () -> Unit,
        onFinishedSending: (String) -> Unit
    ) {
        val cx = micRect.exactCenterX().coerceIn(80f, 1200f)
        val cy = micRect.exactCenterY().coerceIn(400f, 2500f)
        val lockY = (cy - 340f).coerceAtLeast(300f)

        val holdPath = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, cy)
        }
        val swipeUpPath = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, lockY)
        }

        isNativeWhatsAppRecordingActive = true
        nativeRecordingFinishedCallback = onFinishedSending

        // Paso 1: Mantener presionado el botón de micrófono durante 600ms con willContinue = true (en su propio GestureDescription)
        val strokeHold = GestureDescription.StrokeDescription(holdPath, 0L, 600L, true)
        val holdGesture = GestureDescription.Builder().addStroke(strokeHold).build()

        val dispatchedHold = dispatchGesture(holdGesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // Paso 2: Ahora sí despachamos el segundo GestureDescription que desliza hacia arriba hasta el candado
                val strokeSwipe = strokeHold.continueStroke(swipeUpPath, 0L, 450L, false)
                val swipeGesture = GestureDescription.Builder().addStroke(strokeSwipe).build()
                dispatchGesture(swipeGesture, object : GestureResultCallback() {
                    override fun onCompleted(gd: GestureDescription?) {
                        onRecordingLocked()
                        startNativeRecordingSilenceMonitor()
                    }

                    override fun onCancelled(gd: GestureDescription?) {
                        trySingleStrokeSwipeUp(cx, cy, lockY, onRecordingLocked, onGestureFailedFallback)
                    }
                }, mainHandler)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                // Si el dispositivo no soporta willContinue=true, probamos un único trazo continuo hacia el candado
                trySingleStrokeSwipeUp(cx, cy, lockY, onRecordingLocked, onGestureFailedFallback)
            }
        }, mainHandler)

        if (!dispatchedHold) {
            trySingleStrokeSwipeUp(cx, cy, lockY, onRecordingLocked, onGestureFailedFallback)
        }
    }

    private fun trySingleStrokeSwipeUp(
        cx: Float,
        cy: Float,
        lockY: Float,
        onRecordingLocked: () -> Unit,
        onGestureFailedFallback: () -> Unit
    ) {
        val singlePath = Path().apply {
            moveTo(cx, cy)
            lineTo(cx, cy - 10f)
            lineTo(cx, lockY)
        }
        val singleStroke = GestureDescription.StrokeDescription(singlePath, 0L, 1100L, false)
        val gesture = GestureDescription.Builder().addStroke(singleStroke).build()

        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // Verificamos a los 450ms si realmente quedó grabando en WhatsApp; si no, usamos el respaldo automático
                mainHandler.postDelayed({
                    val root = rootInActiveWindow
                    if (root != null && isWhatsAppVoiceRecorderUiVisible(root)) {
                        onRecordingLocked()
                        startNativeRecordingSilenceMonitor()
                    } else {
                        isNativeWhatsAppRecordingActive = false
                        nativeRecordingFinishedCallback = null
                        onGestureFailedFallback()
                    }
                }, 450L)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                isNativeWhatsAppRecordingActive = false
                nativeRecordingFinishedCallback = null
                onGestureFailedFallback()
            }
        }, mainHandler)

        if (!ok) {
            isNativeWhatsAppRecordingActive = false
            nativeRecordingFinishedCallback = null
            onGestureFailedFallback()
        }
    }

    private fun isWhatsAppVoiceRecorderUiVisible(root: AccessibilityNodeInfo): Boolean {
        val recorderIds = listOf(
            "com.whatsapp:id/voice_recorder_send_btn",
            "com.whatsapp:id/voice_note_cancel_btn",
            "com.whatsapp:id/voice_note_slide_to_cancel_animation"
        )
        for (id in recorderIds) {
            if (!root.findAccessibilityNodeInfosByViewId(id).isNullOrEmpty()) return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun startNativeRecordingSilenceMonitor() {
        stopSilenceMonitorAudioRecord()
        val startTime = System.currentTimeMillis()
        var lastSoundTime = startTime
        var peakAmplitude = 1400
        var hasDetectedSpeech = false

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
        val minTopForMic = (screenHeightPx() * 0.42f).toInt()
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
                    if (r.width() > 16 && r.height() > 16 && r.top > minTopForMic) {
                        return r
                    }
                }
            }
        }
        return findMicNodeByDescriptionRecursive(root, minTopForMic)
    }

    private fun findMicNodeByDescriptionRecursive(node: AccessibilityNodeInfo?, minTopForMic: Int): Rect? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        if (desc == "mensaje de voz" || desc == "botón de mensaje de voz" || desc == "voice message") {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.top > minTopForMic && r.width() > 16) return r
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findMicNodeByDescriptionRecursive(child, minTopForMic)
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
        val minTop = chatContentMinTopPx()
        if (clickNodeMatchingPhonetically(root, query, minTop = minTop)) {
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
                    if (rect.top > minTop && clickNodeOrParent(n)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun processOpenChatForRequestedDay(root: AccessibilityNodeInfo) {
        val targetDay = normalize(requestedDayFilter ?: "hoy")
        val extracted = extractChatItemsByDayFromTree(root, targetDay)

        val alreadyAboveToday = targetDay == "hoy" && extracted.sawOlderHeaderOnScreen
        val needsScrollUpToFindStartOfDay = !extracted.sawExactDayHeaderBanner &&
            !alreadyAboveToday &&
            scrollAttemptsLeft > 0

        if (needsScrollUpToFindStartOfDay) {
            scrollAttemptsLeft--
            if (scrollChatListBackward(root)) {
                autoInspectDayChatUntilTimestamp = System.currentTimeMillis() + 6000L
                pendingChatInspectRunnable?.let { mainHandler.removeCallbacks(it) }
                val nextInspect = Runnable {
                    pendingChatInspectRunnable = null
                    val fresh = rootInActiveWindow ?: return@Runnable
                    processOpenChatForRequestedDay(fresh)
                }
                pendingChatInspectRunnable = nextInspect
                mainHandler.postDelayed(nextInspect, 380L)
                return
            }
        }

        val lowHeaderThreshold = (screenHeightPx() * 0.65f).toInt()
        if (extracted.sawExactDayHeaderBanner && extracted.items.isEmpty() && extracted.headerTopY > lowHeaderThreshold) {
            if (scrollChatListForward(root)) {
                mainHandler.postDelayed({
                    val fresh = rootInActiveWindow ?: return@postDelayed
                    val afterScrollDown = extractChatItemsByDayFromTree(fresh, targetDay)
                    deliverExtractedDayItems(afterScrollDown.items)
                }, 480L)
                return
            }
        }

        deliverExtractedDayItems(extracted.items)
    }

    private fun deliverExtractedDayItems(items: List<ScreenChatItem>) {
        autoInspectDayChatUntilTimestamp = 0L
        pendingChatInspectRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingChatInspectRunnable = null
        val cb = onDayChatItemsReadyCallback
        onDayChatItemsReadyCallback = null
        cb?.invoke(items)
    }

    fun scrollDownForMoreItemsInSameDay(
        dayFilterRaw: String,
        previousScreenItems: List<ScreenChatItem>,
        alreadyReadTexts: Set<String>,
        onMoreItemsLoaded: (List<ScreenChatItem>) -> Unit
    ) {
        if (scrollDownPagesLeft <= 0) {
            onMoreItemsLoaded(emptyList())
            return
        }
        val root = rootInActiveWindow
        if (root == null || !scrollChatListForward(root)) {
            onMoreItemsLoaded(emptyList())
            return
        }
        scrollDownPagesLeft--
        val prevBottom = previousScreenItems.lastOrNull()
        val targetDay = normalize(dayFilterRaw.ifBlank { "hoy" })

        mainHandler.postDelayed({
            val fresh = rootInActiveWindow
            if (fresh == null) {
                onMoreItemsLoaded(emptyList())
                return@postDelayed
            }
            val extracted = extractChatItemsByDayFromTree(fresh, targetDay, allowWithoutBannerIfContinuing = true)
            val afterItems = extracted.items
            if (afterItems.isEmpty()) {
                onMoreItemsLoaded(emptyList())
                return@postDelayed
            }

            // 1. Si el último elemento de abajo está en la misma posición vertical y es igual al anterior,
            // significa que el chat ya llegó al fondo y no se movió nada -> cortamos para no entrar en bucle.
            if (prevBottom != null) {
                val afterBottom = afterItems.last()
                val sameBottomPos = abs(afterBottom.topY - prevBottom.topY) < (26f * densityScale()).toInt()
                if (sameBottomPos && afterBottom.isAudio == prevBottom.isAudio && afterBottom.text == prevBottom.text) {
                    onMoreItemsLoaded(emptyList())
                    return@postDelayed
                }

                // 2. Buscamos cuántos elementos del final de la pantalla anterior coinciden con el principio de la pantalla nueva
                val maxOverlap = minOf(previousScreenItems.size, afterItems.size)
                var matchedOverlap = 0
                for (k in maxOverlap downTo 1) {
                    var allMatch = true
                    for (idx in 0 until k) {
                        val prevElem = previousScreenItems[previousScreenItems.size - k + idx]
                        val nextElem = afterItems[idx]
                        if (prevElem.isAudio != nextElem.isAudio || prevElem.text != nextElem.text) {
                            allMatch = false
                            break
                        }
                    }
                    if (allMatch) {
                        matchedOverlap = k
                        break
                    }
                }

                if (matchedOverlap > 0) {
                    val strictlyNewItems = afterItems.drop(matchedOverlap)
                    onMoreItemsLoaded(strictlyNewItems)
                    return@postDelayed
                }
            }

            // 3. Si fue un salto completo de pantalla sin superposición, solo avanzamos si aparecieron textos nuevos no leídos
            val unreadTextsOnly = afterItems.filter { !it.isAudio && !alreadyReadTexts.contains(it.text) }
            if (unreadTextsOnly.isEmpty()) {
                onMoreItemsLoaded(emptyList())
            } else {
                val firstNewTopY = unreadTextsOnly.first().topY
                val itemsFromFirstNew = afterItems.filter {
                    it.topY >= firstNewTopY && (it.isAudio || !alreadyReadTexts.contains(it.text))
                }
                onMoreItemsLoaded(itemsFromFirstNew)
            }
        }, 480L)
    }

    private data class DayExtractionResult(
        val sawExactDayHeaderBanner: Boolean,
        val sawOlderHeaderOnScreen: Boolean,
        val headerTopY: Int,
        val items: List<ScreenChatItem>
    )

    private data class RawScreenElement(
        val type: String,
        val text: String,
        val topY: Int,
        val durationMs: Long = 0L,
        val isOutgoing: Boolean = false
    )

    fun extractAllVisibleChatItemsFromCurrentScreen(
        rootNode: AccessibilityNodeInfo? = rootInActiveWindow
    ): List<ScreenChatItem> {
        val root = rootNode ?: return emptyList()
        val rawElements = mutableListOf<RawScreenElement>()
        collectChatScreenElements(root, rawElements)

        val bucketPx = rowDedupBucketPx()
        val durationsOnScreen = rawElements.filter { it.type == "DURATION" && it.durationMs > 0L }
        val messageElements = rawElements
            .filter { it.type == "TEXT" || it.type == "AUDIO" }
            .sortedBy { it.topY }
            .distinctBy { "${it.type}_${it.topY / bucketPx}_${it.text.take(25)}" }

        val maxDurDistancePx = (85f * densityScale()).toInt().coerceAtLeast(130)
        var audioCounter = 0
        return messageElements.map { raw ->
            if (raw.type == "AUDIO") {
                val idx = audioCounter
                audioCounter++
                val nearbyDuration = durationsOnScreen
                    .minByOrNull { abs(it.topY - raw.topY) }
                    ?.takeIf { abs(it.topY - raw.topY) < maxDurDistancePx }
                    ?.durationMs
                val finalDurationMs = when {
                    raw.durationMs > 1000L -> raw.durationMs
                    nearbyDuration != null && nearbyDuration > 1000L -> nearbyDuration
                    else -> 6_000L
                }
                ScreenChatItem(
                    isAudio = true,
                    text = raw.text,
                    topY = raw.topY,
                    audioOrderInDay = idx,
                    audioDurationMs = finalDurationMs,
                    isOutgoing = raw.isOutgoing
                )
            } else {
                ScreenChatItem(
                    isAudio = false,
                    text = raw.text,
                    topY = raw.topY,
                    audioOrderInDay = -1,
                    audioDurationMs = 0L,
                    isOutgoing = raw.isOutgoing
                )
            }
        }
    }

    private fun extractChatItemsByDayFromTree(
        root: AccessibilityNodeInfo,
        targetDayNorm: String,
        allowWithoutBannerIfContinuing: Boolean = false
    ): DayExtractionResult {
        val rawElements = mutableListOf<RawScreenElement>()
        collectChatScreenElements(root, rawElements)

        val bucketPx = rowDedupBucketPx()
        val durationsOnScreen = rawElements.filter { it.type == "DURATION" && it.durationMs > 0L }
        val mainElements = rawElements
            .filter { it.type != "DURATION" }
            .sortedBy { it.topY }
            .distinctBy { "${it.type}_${it.topY / bucketPx}_${it.text.take(25)}" }

        val headers = mainElements.filter { it.type == "HEADER" }

        var currentSectionDay = if (headers.isEmpty()) targetDayNorm else "anterior_al_primer_cartel"
        var sawRequestedHeader = false
        var sawOlderHeader = false
        var requestedHeaderTopY = 0
        val matchingItems = mutableListOf<RawScreenElement>()

        for (elem in mainElements) {
            if (elem.type == "HEADER") {
                val hNorm = normalize(elem.text)
                currentSectionDay = hNorm
                if (headerMatchesDay(hNorm, targetDayNorm)) {
                    sawRequestedHeader = true
                    requestedHeaderTopY = elem.topY
                } else {
                    sawOlderHeader = true
                }
            } else {
                if (headerMatchesDay(currentSectionDay, targetDayNorm)) {
                    matchingItems.add(elem)
                }
            }
        }

        if (!sawRequestedHeader && matchingItems.isEmpty() && (scrollAttemptsLeft <= 0 || allowWithoutBannerIfContinuing || (targetDayNorm == "hoy" && sawOlderHeader))) {
            if (headers.isEmpty()) {
                if (targetDayNorm == "hoy" || allowWithoutBannerIfContinuing) {
                    matchingItems.addAll(mainElements.filter { it.type != "HEADER" })
                }
            } else {
                val firstH = headers.first()
                val firstHNorm = normalize(firstH.text)
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

        val maxDurDistancePx = (85f * densityScale()).toInt().coerceAtLeast(130)
        var audioCounter = 0
        val finalItems = matchingItems.map { raw ->
            if (raw.type == "AUDIO") {
                val idx = audioCounter
                audioCounter++
                val nearbyDuration = durationsOnScreen
                    .minByOrNull { abs(it.topY - raw.topY) }
                    ?.takeIf { abs(it.topY - raw.topY) < maxDurDistancePx }
                    ?.durationMs
                val finalDurationMs = when {
                    raw.durationMs > 1000L -> raw.durationMs
                    nearbyDuration != null && nearbyDuration > 1000L -> nearbyDuration
                    else -> 6_000L
                }
                ScreenChatItem(
                    isAudio = true,
                    text = raw.text,
                    topY = raw.topY,
                    audioOrderInDay = idx,
                    audioDurationMs = finalDurationMs,
                    isOutgoing = raw.isOutgoing
                )
            } else {
                ScreenChatItem(
                    isAudio = false,
                    text = raw.text,
                    topY = raw.topY,
                    audioOrderInDay = -1,
                    audioDurationMs = 0L,
                    isOutgoing = raw.isOutgoing
                )
            }
        }

        return DayExtractionResult(
            sawExactDayHeaderBanner = sawRequestedHeader,
            sawOlderHeaderOnScreen = sawOlderHeader,
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

    /**
     * Detecta si un mensaje o audio de WhatsApp fue enviado por nosotros (burbuja a la derecha o con tildes ✓✓ de estado)
     * para poder ignorarlo cuando el usuario pide "el último mensaje/audio de X".
     */
    private fun isOutgoingWhatsAppMessageNode(node: AccessibilityNodeInfo, nodeRect: Rect): Boolean {
        val screenW = screenWidthPx()
        if (screenW <= 0) return false

        // 1. Buscamos el contenedor de la fila completa (hijo directo del RecyclerView)
        // y el contenedor de la burbuja (el ancestro que no ocupa todo el ancho de la pantalla)
        var current: AccessibilityNodeInfo? = node
        var rowNode: AccessibilityNodeInfo? = null
        var bubbleNode: AccessibilityNodeInfo? = null
        var depth = 0

        val maxBubbleWidth = (screenW * 0.92f).toInt()
        val minBubbleWidth = (screenW * 0.15f).toInt()

        while (current != null && depth < 8) {
            val p = current.parent ?: break
            val pRect = Rect()
            p.getBoundsInScreen(pRect)

            if (p.isScrollable) {
                rowNode = current
                break
            }

            if (pRect.width() in minBubbleWidth..maxBubbleWidth && pRect.height() > 15) {
                bubbleNode = p
            }

            current = p
            depth++
        }

        if (rowNode == null) {
            rowNode = current
        }

        // 2. Si la fila completa contiene el ícono o texto de estado (tildes ✓✓ / entregado / leído), es 100% saliente (enviado por nosotros)
        if (rowNode != null && hasOutgoingStatusTickRecursive(rowNode, maxDepth = 8)) {
            return true
        }

        // 3. Si encontramos la burbuja del mensaje, comparamos sus márgenes izquierdo y derecho
        if (bubbleNode != null) {
            val bRect = Rect()
            bubbleNode.getBoundsInScreen(bRect)
            val leftMargin = bRect.left
            val rightMargin = (screenW - bRect.right).coerceAtLeast(0)

            // Burbuja alineada a la derecha: margen izquierdo significativamente mayor que el derecho
            if (leftMargin > rightMargin + (20f * densityScale()).toInt()) {
                return true
            }
            // En WhatsApp las burbujas enviadas tienen un margen izquierdo de al menos 18% del ancho de pantalla
            if (leftMargin > (screenW * 0.18f).toInt() && rightMargin < (screenW * 0.15f).toInt()) {
                return true
            }
            if (leftMargin < (screenW * 0.12f).toInt()) {
                return false
            }
        }

        // 4. Respaldo por la posición del nodo individual
        return nodeRect.left > (screenW * 0.35f).toInt()
    }

    private fun hasOutgoingStatusTickRecursive(node: AccessibilityNodeInfo?, maxDepth: Int): Boolean {
        if (node == null || maxDepth < 0) return false
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (viewId.endsWith(":id/status") || viewId.endsWith(":id/status_icon") || viewId.endsWith(":id/msg_status")) {
            return true
        }

        val desc = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
        if (desc == "leído" || desc == "leido" || desc == "entregado" || desc == "enviado" ||
            desc == "read" || desc == "delivered" || desc == "sent" || desc == "pendiente" || desc == "pending"
        ) {
            return true
        }

        if (maxDepth == 0) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasOutgoingStatusTickRecursive(child, maxDepth - 1)) return true
        }
        return false
    }

    private fun collectChatScreenElements(
        node: AccessibilityNodeInfo?,
        outList: MutableList<RawScreenElement>,
        minTop: Int = chatContentMinTopPx(),
        maxBottom: Int = chatContentMaxBottomPx()
    ) {
        if (node == null) return
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val lowerDesc = desc.lowercase()
        val lowerText = text.lowercase()

        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.top in minTop..maxBottom && rect.bottom > rect.top) {
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
                    DATE_TEXT_LONG_REGEX.matches(lowerText) ||
                    DATE_TEXT_SLASH_REGEX.matches(lowerText)
                )

            if (isDateDividerId || isDateBannerText) {
                if (text.isNotEmpty()) {
                    outList.add(RawScreenElement("HEADER", text, rect.top))
                }
                return
            }

            val isDateTimestampNode = viewId.endsWith(":id/date")
            if (!isDateTimestampNode) {
                val parsedDur = parseAudioDurationTextMs(text.ifEmpty { desc })
                if (parsedDur > 0L && (
                        viewId.endsWith(":id/description") ||
                            viewId.endsWith(":id/duration") ||
                            viewId.endsWith(":id/audio_duration") ||
                            lowerDesc.contains("mensaje de voz") ||
                            MM_SS_EXACT_REGEX.matches(text)
                        )
                ) {
                    outList.add(RawScreenElement("DURATION", text.ifEmpty { desc }, rect.top, parsedDur))
                }
            }

            val isAudioControl = viewId.endsWith(":id/control_btn") ||
                viewId.endsWith(":id/audio_play_pause_btn") ||
                lowerDesc == "reproducir" ||
                lowerDesc.startsWith("reproducir ") ||
                lowerDesc == "pausar" ||
                lowerDesc.startsWith("pausar ") ||
                lowerDesc == "play" ||
                lowerDesc == "pause" ||
                lowerText == "reproducir"

            if (isAudioControl) {
                val durFromDesc = parseAudioDurationTextMs(desc)
                val outgoing = isOutgoingWhatsAppMessageNode(node, rect)
                outList.add(RawScreenElement("AUDIO", "Audio de voz", rect.top, durFromDesc, outgoing))
                return
            }

            val isMessageTextView = viewId.endsWith(":id/message_text") ||
                viewId.endsWith(":id/caption")
            if (isMessageTextView && text.isNotEmpty() && !TIME_ONLY_MSG_REGEX.matches(text)) {
                val outgoing = isOutgoingWhatsAppMessageNode(node, rect)
                outList.add(RawScreenElement("TEXT", text, rect.top, 0L, outgoing))
                return
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectChatScreenElements(child, outList, minTop, maxBottom)
        }
    }

    private fun parseAudioDurationTextMs(raw: String): Long {
        if (raw.isBlank()) return 0L
        val mmSs = MM_SS_FIND_REGEX.find(raw)
        if (mmSs != null) {
            val mins = mmSs.groupValues[1].toLongOrNull() ?: return 0L
            val secs = mmSs.groupValues[2].toLongOrNull() ?: return 0L
            if (mins in 0..9 && secs in 0..59 && (mins > 0 || secs > 0)) {
                return (mins * 60L + secs) * 1000L
            }
        }
        val lower = raw.lowercase()
        val secMatch = SEC_FIND_REGEX.find(lower)
        if (secMatch != null) {
            val s = secMatch.groupValues[1].toLongOrNull() ?: 0L
            val mMatch = MIN_FIND_REGEX.find(lower)
            val m = mMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            if (m > 0 || s > 0) {
                return (m * 60L + s) * 1000L
            }
        }
        return 0L
    }

    fun clickLatestAudioInCurrentChatScreen(
        rootNode: AccessibilityNodeInfo? = rootInActiveWindow,
        fromBottomIndex: Int = 0,
        onlyIncoming: Boolean = true
    ): Boolean {
        val root = rootNode ?: return false
        val headers = mutableListOf<RawScreenElement>()
        val audioButtons = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectHeadersAndAudioButtons(root, headers, audioButtons)

        val bucketPx = rowDedupBucketPx()
        val sortedButtons = audioButtons
            .sortedBy { it.first }
            .distinctBy { it.first / bucketPx }

        val candidateButtons = if (onlyIncoming) {
            sortedButtons.filter { (_, node) ->
                val r = Rect()
                node.getBoundsInScreen(r)
                !isOutgoingWhatsAppMessageNode(node, r)
            }
        } else {
            sortedButtons
        }

        if (candidateButtons.isEmpty()) return false
        val targetIdx = (candidateButtons.lastIndex - fromBottomIndex).coerceIn(0, candidateButtons.lastIndex)
        val chosenButton = candidateButtons[targetIdx].second
        val desc = chosenButton.contentDescription?.toString()?.lowercase().orEmpty()
        if (desc.contains("paus")) return true

        lastVoiceNoteClickTime = System.currentTimeMillis()
        return clickAudioNodeWithFallback(chosenButton)
    }

    fun clickAudioInCurrentScreenByDayOrder(
        targetDayRaw: String,
        audioOrderInDay: Int,
        expectedTopY: Int = -1
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now < lastVoiceNoteClickTime + 300L) return false

        val root = rootInActiveWindow ?: return false
        val targetDayNorm = normalize(targetDayRaw.ifBlank { "hoy" })

        val headers = mutableListOf<RawScreenElement>()
        val audioButtons = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectHeadersAndAudioButtons(root, headers, audioButtons)

        val bucketPx = rowDedupBucketPx()
        val sortedHeaders = headers.sortedBy { it.topY }
        val sortedButtons = audioButtons
            .sortedBy { it.first }
            .distinctBy { it.first / bucketPx }

        val matchingButtonsWithTop: List<Pair<Int, AccessibilityNodeInfo>> = if (sortedHeaders.isEmpty()) {
            sortedButtons
        } else {
            val filtered = sortedButtons.filter { (btnTop, _) ->
                val headerAbove = sortedHeaders.lastOrNull { it.topY < btnTop }
                if (headerAbove != null) {
                    headerMatchesDay(normalize(headerAbove.text), targetDayNorm)
                } else {
                    (targetDayNorm == "hoy" && sortedHeaders.none { headerMatchesDay(normalize(it.text), "hoy") }) ||
                        (targetDayNorm == "ayer" && headerMatchesDay(normalize(sortedHeaders.first().text), "hoy"))
                }
            }
            filtered.ifEmpty { sortedButtons }
        }

        val maxTopDriftPx = (75f * densityScale()).toInt().coerceAtLeast(110)
        val byOrder = matchingButtonsWithTop.getOrNull(audioOrderInDay)
        val targetPair = when {
            byOrder != null && (expectedTopY <= 0 || abs(byOrder.first - expectedTopY) <= maxTopDriftPx) -> byOrder
            expectedTopY > 0 -> matchingButtonsWithTop.minByOrNull { abs(it.first - expectedTopY) }
                ?: byOrder
                ?: sortedButtons.getOrNull(audioOrderInDay)
            else -> byOrder ?: sortedButtons.getOrNull(audioOrderInDay)
        } ?: return false

        val targetNode = targetPair.second
        val desc = targetNode.contentDescription?.toString()?.lowercase().orEmpty()
        val txt = targetNode.text?.toString()?.lowercase().orEmpty()
        if (desc.contains("paus") || txt.contains("paus")) {
            return true
        }

        lastVoiceNoteClickTime = now
        return clickAudioNodeWithFallback(targetNode)
    }

    private fun clickAudioNodeWithFallback(node: AccessibilityNodeInfo): Boolean {
        if (clickNodeOrParent(node)) return true
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 10 && rect.height() > 10) {
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            val path = Path().apply {
                moveTo(cx, cy)
                lineTo(cx, cy)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    fun pauseAnyPlayingVoiceNoteOnScreen() {
        val root = rootInActiveWindow ?: return
        val audioBtnIds = listOf(
            "com.whatsapp:id/control_btn",
            "com.whatsapp:id/audio_play_pause_btn",
            "com.whatsapp.w4b:id/control_btn",
            "com.whatsapp.w4b:id/audio_play_pause_btn"
        )
        var checkedById = false
        for (id in audioBtnIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                checkedById = true
                for (n in nodes) {
                    val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
                    val txt = n.text?.toString()?.lowercase().orEmpty()
                    if (desc.contains("paus") || txt.contains("paus")) {
                        if (clickNodeOrParent(n)) return
                    }
                }
            }
        }
        if (!checkedById) {
            pausePlayingNodeRecursive(root)
        }
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
        val audioBtnIds = listOf(
            "com.whatsapp:id/control_btn",
            "com.whatsapp:id/audio_play_pause_btn",
            "com.whatsapp.w4b:id/control_btn",
            "com.whatsapp.w4b:id/audio_play_pause_btn"
        )
        var foundAnyAudioControlById = false
        for (id in audioBtnIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                foundAnyAudioControlById = true
                for (n in nodes) {
                    val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
                    val txt = n.text?.toString()?.lowercase().orEmpty()
                    if (desc.contains("paus") || txt.contains("paus")) return true
                }
            }
        }
        if (foundAnyAudioControlById) return false
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
        buttonsOut: MutableList<Pair<Int, AccessibilityNodeInfo>>,
        minTop: Int = chatContentMinTopPx(),
        maxBottom: Int = chatContentMaxBottomPx()
    ) {
        if (node == null) return
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val lowerDesc = desc.lowercase()
        val lowerText = text.lowercase()

        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.top in minTop..maxBottom && rect.bottom > rect.top) {
            val isDateDivider = viewId.endsWith(":id/conversation_row_date_divider") ||
                viewId.endsWith(":id/date_divider") ||
                (!node.isClickable && text.length in 3..32 && (
                    lowerText == "hoy" || lowerText == "ayer" ||
                        lowerText == "lunes" || lowerText == "martes" ||
                        lowerText == "miércoles" || lowerText == "miercoles" ||
                        lowerText == "jueves" || lowerText == "viernes" ||
                        lowerText == "sábado" || lowerText == "sabado" ||
                        lowerText == "domingo" ||
                        DATE_TEXT_LONG_REGEX.matches(lowerText) ||
                        DATE_TEXT_SLASH_REGEX.matches(lowerText)
                    ))
            if (isDateDivider && text.isNotEmpty()) {
                headersOut.add(RawScreenElement("HEADER", text, rect.top))
                return
            }

            val isAudioBtn = viewId.endsWith(":id/control_btn") ||
                viewId.endsWith(":id/audio_play_pause_btn") ||
                lowerDesc == "reproducir" ||
                lowerDesc.startsWith("reproducir ") ||
                lowerDesc == "pausar" ||
                lowerDesc.startsWith("pausar ") ||
                lowerDesc == "play" ||
                lowerDesc == "pause" ||
                lowerDesc.contains("paus") ||
                lowerText == "reproducir"

            if (isAudioBtn) {
                buttonsOut.add(Pair(rect.top, node))
                return
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectHeadersAndAudioButtons(child, headersOut, buttonsOut, minTop, maxBottom)
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
            .replace(REPEATED_LETTERS_REGEX, "$1")
            .replace(NON_ALPHANUM_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
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
            "com.whatsapp:id/button1",
            "com.whatsapp:id/ok",
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
        return clickByContentDescriptionOrText(root, listOf("aceptar", "ok", "enviar", "send", "continuar"))
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
            if (rect.top in chatContentMinTopPx()..chatContentMaxBottomPx() && (rect.bottom - rect.top) > 80) {
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
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val text = node.text?.toString()?.lowercase()?.trim() ?: ""

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
        return DIACRITICS_REGEX.replace(temp, "")
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
        private val DATE_TEXT_LONG_REGEX = Regex("\\d{1,2} de [a-záéíóú]+.*")
        private val DATE_TEXT_SLASH_REGEX = Regex("\\d{1,2}/\\d{1,2}/\\d{2,4}")
        private val MM_SS_EXACT_REGEX = Regex("^\\d{1,2}:\\d{2}$")
        private val MM_SS_FIND_REGEX = Regex("\\b(\\d{1,2}):(\\d{2})\\b")
        private val SEC_FIND_REGEX = Regex("(\\d+)\\s*seg")
        private val MIN_FIND_REGEX = Regex("(\\d+)\\s*min")
        private val TIME_ONLY_MSG_REGEX = Regex("^\\d{1,2}:\\d{2}(\\s*[ap]\\.?\\s*m\\.?)?$", RegexOption.IGNORE_CASE)
        private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
        private val REPEATED_LETTERS_REGEX = Regex("([a-z])\\1+")
        private val NON_ALPHANUM_REGEX = Regex("[^a-z0-9 ]")
        private val MULTI_SPACE_REGEX = Regex("\\s+")

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
        private var autoInspectBottomRecentUntilTimestamp: Long = 0L

        @Volatile
        private var onBottomRecentItemsReadyCallback: ((List<ScreenChatItem>) -> Unit)? = null

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

        @Volatile
        private var autoUpdateWhatsAppUntilTimestamp: Long = 0L

        @Volatile
        private var autoUpdateWhatsAppStartedAt: Long = 0L

        @Volatile
        private var onWhatsAppUpdateStatusCallback: ((String) -> Unit)? = null

        @Volatile
        private var lastOutdatedWhatsAppTriggerTime: Long = 0L

        fun requestAutoUpdateWhatsApp(onStatus: (String) -> Unit) {
            val now = System.currentTimeMillis()
            autoUpdateWhatsAppStartedAt = now
            autoUpdateWhatsAppUntilTimestamp = now + 18_000L
            onWhatsAppUpdateStatusCallback = onStatus
        }

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
            instance?.scrollAttemptsLeft = if (cleanDay == "hoy") 4 else 10
            instance?.scrollDownPagesLeft = 12
            autoInspectDayChatUntilTimestamp = System.currentTimeMillis() + 18_000L
            instance?.mainHandler?.postDelayed({
                if (autoInspectDayChatUntilTimestamp > 0L) {
                    val root = instance?.rootInActiveWindow ?: return@postDelayed
                    instance?.processOpenChatForRequestedDay(root)
                }
            }, 600L)
        }

        fun requestInspectBottomChatItems(onReady: (List<ScreenChatItem>) -> Unit) {
            onBottomRecentItemsReadyCallback = onReady
            autoInspectBottomRecentUntilTimestamp = System.currentTimeMillis() + 12_000L
            instance?.mainHandler?.postDelayed({
                if (autoInspectBottomRecentUntilTimestamp > 0L) {
                    val root = instance?.rootInActiveWindow ?: return@postDelayed
                    val items = instance?.extractAllVisibleChatItemsFromCurrentScreen(root).orEmpty()
                    if (items.isNotEmpty()) {
                        autoInspectBottomRecentUntilTimestamp = 0L
                        val cb = onBottomRecentItemsReadyCallback
                        onBottomRecentItemsReadyCallback = null
                        cb?.invoke(items)
                    }
                }
            }, 600L)
        }

        fun requestAutoPlayInYouTube() {
            autoPlayYouTubeUntilTimestamp = System.currentTimeMillis() + 10_000L
        }
    }
}
