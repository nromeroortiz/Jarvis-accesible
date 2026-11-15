package com.jarvis.accesible.services

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.accesible.core.AppPrefs
import com.jarvis.accesible.core.JarvisCoordinator
import java.text.Normalizer
import java.util.Locale

data class ScreenChatItem(
    val isAudio: Boolean,
    val text: String,
    val topY: Int,
    val audioOrderInDay: Int // Índice 0-based entre los audios de ese día (de arriba hacia abajo: 0 = primero del día)
)

/**
 * Servicio de Accesibilidad de Android:
 * 1. Doble toque en Subir Volumen para hablar manos libres.
 * 2. En WhatsApp:
 *    - Busca un contacto con la lupa si no estaba en la agenda del teléfono.
 *    - Analiza el chat abierto separando por los carteles de fecha ("HOY", "AYER", día de semana),
 *      haciendo scroll hacia arriba automáticamente si el usuario pidió los de "AYER".
 *    - Extrae todos los elementos del día pedido en orden cronológico DEL PRIMERO AL ÚLTIMO
 *      (sin discriminar entre texto y audio).
 *    - Reproduce cualquier audio del día haciendo un único clic limpio (sin pausarlo jamás).
 * 3. En YouTube:
 *    - Si se abre en búsqueda, hace clic en el primer video para que empiece a sonar solo.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastVolumeUpDownTime = 0L
    private var pendingChatInspectRunnable: Runnable? = null
    private var scrollAttemptsLeft = 0

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
            // A) Clic automático en "Enviar" al mandar mensaje o audio
            if (now < autoClickSendUntilTimestamp) {
                val root = rootInActiveWindow ?: return
                if (findAndClickSendButtonInWhatsApp(root)) {
                    autoClickSendUntilTimestamp = 0L
                    mainHandler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }, 1500L)
                }
                return
            }

            // B) Búsqueda de un contacto con la lupa de WhatsApp si no estaba en la agenda del chip
            if (now < autoSearchContactUntilTimestamp && pendingSearchContactName != null) {
                val root = rootInActiveWindow ?: return
                handleWhatsAppSearchBarFlow(root)
                return
            }

            // C) Inspeccionar el chat abierto para un día específico ("hoy", "ayer", etc.) del primero al último
            if (now < autoInspectDayChatUntilTimestamp) {
                pendingChatInspectRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    val freshRoot = rootInActiveWindow ?: return@Runnable
                    processOpenChatForRequestedDay(freshRoot)
                }
                pendingChatInspectRunnable = runnable
                mainHandler.postDelayed(runnable, 600L)
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
     * Busca un contacto usando la lupa de WhatsApp cuando no estaba agendado en Contactos del teléfono.
     */
    private fun handleWhatsAppSearchBarFlow(root: AccessibilityNodeInfo) {
        val query = pendingSearchContactName ?: return

        // Paso 1: Si ya está abierto el cuadro de texto de búsqueda, escribimos el nombre y tocamos el primer resultado
        val editNodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditTexts(root, editNodes)
        val searchInput = editNodes.firstOrNull()

        if (searchInput != null) {
            val currentText = searchInput.text?.toString()?.trim().orEmpty()
            if (!currentText.equals(query, ignoreCase = true)) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                }
                searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                return
            } else {
                // Ya escribimos el nombre: buscamos en la lista de resultados debajo del buscador
                mainHandler.postDelayed({
                    val fresh = rootInActiveWindow ?: return@postDelayed
                    if (clickFirstSearchResultInWhatsApp(fresh, query)) {
                        autoSearchContactUntilTimestamp = 0L
                        pendingSearchContactName = null
                        val cb = onSearchChatOpenedCallback
                        onSearchChatOpenedCallback = null
                        cb?.invoke()
                    }
                }, 650L)
                return
            }
        }

        // Paso 2: Si estamos en la pantalla principal de WhatsApp, tocamos el botón de la lupa ("Buscar")
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

    private fun clickFirstSearchResultInWhatsApp(root: AccessibilityNodeInfo, query: String): Boolean {
        val normQuery = normalize(query)
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
                    if (rect.top > 220 && clickNodeOrParent(n)) {
                        return true
                    }
                }
            }
        }
        return clickNodeMatchingSubstring(root, normQuery, minTop = 240)
    }

    /**
     * Analiza el chat abierto en pantalla para el día pedido (ej: "hoy", "ayer"),
     * hace scroll hacia arriba si todavía no aparece ese día, y entrega la lista ordenada
     * del PRIMER mensaje/audio de ese día al ÚLTIMO.
     */
    private fun processOpenChatForRequestedDay(root: AccessibilityNodeInfo) {
        val targetDay = normalize(requestedDayFilter ?: "hoy")
        val extracted = extractChatItemsByDayFromTree(root, targetDay)

        if (extracted.foundDayHeader || extracted.items.isNotEmpty()) {
            // Si estábamos buscando "ayer" (u otro día anterior) y solo apareció "hoy" en pantalla, subimos con scroll
            if (!extracted.foundDayHeader && targetDay != "hoy" && scrollAttemptsLeft > 0) {
                scrollAttemptsLeft--
                if (scrollChatListBackward(root)) {
                    autoInspectDayChatUntilTimestamp = System.currentTimeMillis() + 6000L
                    return
                }
            }

            autoInspectDayChatUntilTimestamp = 0L
            val cb = onDayChatItemsReadyCallback
            onDayChatItemsReadyCallback = null
            cb?.invoke(extracted.items)
        } else if (scrollAttemptsLeft > 0) {
            scrollAttemptsLeft--
            if (scrollChatListBackward(root)) {
                autoInspectDayChatUntilTimestamp = System.currentTimeMillis() + 6000L
                return
            }
            autoInspectDayChatUntilTimestamp = 0L
            val cb = onDayChatItemsReadyCallback
            onDayChatItemsReadyCallback = null
            cb?.invoke(emptyList())
        } else {
            autoInspectDayChatUntilTimestamp = 0L
            val cb = onDayChatItemsReadyCallback
            onDayChatItemsReadyCallback = null
            cb?.invoke(emptyList())
        }
    }

    private data class DayExtractionResult(
        val foundDayHeader: Boolean,
        val items: List<ScreenChatItem>
    )

    private data class RawScreenElement(
        val type: String, // "HEADER", "TEXT", "AUDIO"
        val text: String,
        val topY: Int
    )

    private fun extractChatItemsByDayFromTree(
        root: AccessibilityNodeInfo,
        targetDayNorm: String
    ): DayExtractionResult {
        val rawElements = mutableListOf<RawScreenElement>()
        collectChatScreenElements(root, rawElements)

        val sorted = rawElements
            .sortedBy { it.topY }
            .distinctBy { "${it.type}_${it.topY / 25}_${it.text}" }

        // Identificamos los carteles divisores de fecha ("HOY", "AYER", "VIERNES", etc.)
        val headers = sorted.filter { it.type == "HEADER" }

        var currentSectionDay = if (headers.isEmpty()) "hoy" else "anterior"
        var sawRequestedHeader = false
        val matchingItems = mutableListOf<RawScreenElement>()

        for (elem in sorted) {
            if (elem.type == "HEADER") {
                val hNorm = normalize(elem.text)
                currentSectionDay = hNorm
                if (headerMatchesDay(hNorm, targetDayNorm)) {
                    sawRequestedHeader = true
                }
            } else {
                if (headerMatchesDay(currentSectionDay, targetDayNorm)) {
                    matchingItems.add(elem)
                }
            }
        }

        // Si pidió "hoy" y el cartel "HOY" quedó más arriba fuera de la pantalla (y no hay cartel "HOY" abajo),
        // todos los elementos después del último header (o todos si no hay headers) son de hoy.
        if (targetDayNorm == "hoy" && !sawRequestedHeader && matchingItems.isEmpty()) {
            val afterLastHeader = if (headers.isEmpty()) {
                sorted.filter { it.type != "HEADER" }
            } else {
                val lastH = headers.last()
                if (normalize(lastH.text) == "ayer") {
                    emptyList()
                } else {
                    sorted.filter { it.type != "HEADER" && it.topY > lastH.topY }
                }
            }
            matchingItems.addAll(afterLastHeader)
        }

        var audioCounter = 0
        val finalItems = matchingItems.map { raw ->
            if (raw.type == "AUDIO") {
                val idx = audioCounter
                audioCounter++
                ScreenChatItem(
                    isAudio = true,
                    text = raw.text,
                    topY = raw.topY,
                    audioOrderInDay = idx
                )
            } else {
                ScreenChatItem(
                    isAudio = false,
                    text = raw.text,
                    topY = raw.topY,
                    audioOrderInDay = -1
                )
            }
        }

        return DayExtractionResult(
            foundDayHeader = sawRequestedHeader || (targetDayNorm == "hoy" && finalItems.isNotEmpty()),
            items = finalItems
        )
    }

    private fun headerMatchesDay(headerNorm: String, targetDayNorm: String): Boolean {
        if (headerNorm == targetDayNorm) return true
        if (headerNorm.contains(targetDayNorm) || targetDayNorm.contains(headerNorm)) return true
        return false
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

        // Ignorar barra superior del nombre del contacto y barra inferior de escribir mensaje
        if (rect.top in 160..2100 && rect.bottom > rect.top) {
            // 1. ¿Es un cartel divisor de fecha ("HOY", "AYER", "LUNES", "14 DE NOVIEMBRE")?
            val isDateDividerId = viewId.endsWith(":id/conversation_row_date_divider") ||
                viewId.endsWith(":id/date_divider")
            val isDateBannerText = !node.isClickable && text.length in 3..28 && (
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
                    lowerText.matches(Regex("\\d{1,2} de [a-záéíóú]+.*"))
                )

            if (isDateDividerId || isDateBannerText) {
                if (text.isNotEmpty()) {
                    outList.add(RawScreenElement("HEADER", text, rect.top))
                }
                return
            }

            // 2. ¿Es un botón Reproducir de un audio recibido? (Alineado a la mitad izquierda de la pantalla)
            val isAudioPlayControl = (
                lowerDesc == "reproducir" ||
                    lowerDesc.startsWith("reproducir ") ||
                    lowerDesc == "play" ||
                    lowerText == "reproducir" ||
                    viewId.endsWith(":id/control_btn") ||
                    viewId.endsWith(":id/audio_play_pause_btn")
                ) && !lowerDesc.contains("paus")

            if (isAudioPlayControl && rect.left < 520) {
                outList.add(RawScreenElement("AUDIO", "Audio de voz", rect.top))
                return
            }

            // 3. ¿Es un mensaje de texto recibido de la otra persona? (Alineado a la izquierda: rect.left < 260)
            if (viewId.endsWith(":id/message_text") && text.isNotEmpty() && rect.left < 260) {
                outList.add(RawScreenElement("TEXT", text, rect.top))
                return
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectChatScreenElements(child, outList)
        }
    }

    /**
     * Hace clic en el audio de la posición indicada dentro del día pedido (0 = el primer audio de ese día, 1 = el segundo, etc.).
     * Garantiza hacer UN SOLO CLIC y jamás tocar si ya dice "Pausar".
     */
    fun clickAudioInCurrentScreenByDayOrder(targetDayRaw: String, audioOrderInDay: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now < lastVoiceNoteClickTime + 1200L) return false

        val root = rootInActiveWindow ?: return false
        val targetDayNorm = normalize(targetDayRaw.ifBlank { "hoy" })

        val headers = mutableListOf<RawScreenElement>()
        val playButtons = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectHeadersAndPlayButtons(root, headers, playButtons)

        val sortedHeaders = headers.sortedBy { it.topY }
        val sortedButtons = playButtons
            .sortedBy { it.first }
            .distinctBy { it.first / 25 }

        val matchingButtons = if (sortedHeaders.isEmpty()) {
            sortedButtons.map { it.second }
        } else {
            sortedButtons.filter { (btnTop, _) ->
                val headerAbove = sortedHeaders.lastOrNull { it.topY < btnTop }
                if (headerAbove != null) {
                    headerMatchesDay(normalize(headerAbove.text), targetDayNorm)
                } else {
                    targetDayNorm == "hoy" && sortedHeaders.none { normalize(it.text) == "hoy" }
                }
            }.map { it.second }
        }

        val targetNode = matchingButtons.getOrNull(audioOrderInDay)
            ?: sortedButtons.getOrNull(audioOrderInDay)?.second
            ?: return false

        lastVoiceNoteClickTime = now
        return clickNodeOrParent(targetNode)
    }

    /**
     * Devuelve true si en la pantalla actual de WhatsApp hay un audio reproduciéndose (botón en estado "Pausar").
     */
    fun isAnyVoiceNoteCurrentlyPlayingOnScreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        return hasPauseButtonNode(root)
    }

    private fun hasPauseButtonNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val desc = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
        if (desc == "pausar" || desc == "pause") return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasPauseButtonNode(child)) return true
        }
        return false
    }

    private fun collectHeadersAndPlayButtons(
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

        if (rect.top in 160..2100 && rect.bottom > rect.top) {
            val isDateDivider = viewId.endsWith(":id/conversation_row_date_divider") ||
                (!node.isClickable && text.length in 3..28 && (
                    lowerText == "hoy" || lowerText == "ayer" ||
                        lowerText == "lunes" || lowerText == "martes" ||
                        lowerText == "miércoles" || lowerText == "miercoles" ||
                        lowerText == "jueves" || lowerText == "viernes" ||
                        lowerText == "sábado" || lowerText == "sabado" ||
                        lowerText == "domingo"
                    ))
            if (isDateDivider && text.isNotEmpty()) {
                headersOut.add(RawScreenElement("HEADER", text, rect.top))
                return
            }

            if (!lowerDesc.contains("paus") && !lowerText.contains("paus")) {
                val isPlayBtn = lowerDesc == "reproducir" ||
                    lowerDesc.startsWith("reproducir ") ||
                    lowerDesc == "play" ||
                    lowerText == "reproducir" ||
                    viewId.endsWith(":id/control_btn") ||
                    viewId.endsWith(":id/audio_play_pause_btn")

                if (isPlayBtn && rect.left < 520) {
                    buttonsOut.add(Pair(rect.top, node))
                    return
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectHeadersAndPlayButtons(child, headersOut, buttonsOut)
        }
    }

    private fun scrollChatListBackward(root: AccessibilityNodeInfo): Boolean {
        val listNodes = root.findAccessibilityNodeInfosByViewId("android:id/list")
        if (!listNodes.isNullOrEmpty()) {
            for (n in listNodes) {
                if (n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                    return true
                }
            }
        }
        return scrollAnyScrollableBackward(root)
    }

    private fun scrollAnyScrollableBackward(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollAnyScrollableBackward(child)) return true
        }
        return false
    }

    private fun collectEditTexts(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText", ignoreCase = true)) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditTexts(child, out)
        }
    }

    private fun clickNodeMatchingSubstring(
        node: AccessibilityNodeInfo?,
        normSub: String,
        minTop: Int
    ): Boolean {
        if (node == null) return false
        val txt = normalize(node.text?.toString().orEmpty())
        val desc = normalize(node.contentDescription?.toString().orEmpty())
        if ((txt.contains(normSub) || desc.contains(normSub)) && txt != normSub) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.top >= minTop && clickNodeOrParent(node)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickNodeMatchingSubstring(child, normSub, minTop)) return true
        }
        return false
    }

    private fun findAndClickSendButtonInWhatsApp(root: AccessibilityNodeInfo): Boolean {
        val sendIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_container",
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
        return clickByContentDescriptionOrText(root, listOf("enviar", "send"))
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
        private var autoPlayYouTubeUntilTimestamp: Long = 0L

        @Volatile
        private var lastYouTubeClickTime: Long = 0L

        fun requestAutoClickSendInWhatsApp() {
            autoClickSendUntilTimestamp = System.currentTimeMillis() + 12_000L
        }

        fun requestSearchAndOpenChatInWhatsApp(contactName: String, onOpened: (() -> Unit)? = null) {
            pendingSearchContactName = contactName.trim()
            onSearchChatOpenedCallback = onOpened
            autoSearchContactUntilTimestamp = System.currentTimeMillis() + 10_000L
        }

        fun requestInspectOpenChatForDay(dayFilter: String, onReady: (List<ScreenChatItem>) -> Unit) {
            requestedDayFilter = dayFilter.ifBlank { "hoy" }
            onDayChatItemsReadyCallback = onReady
            instance?.scrollAttemptsLeft = if (dayFilter.lowercase().contains("ayer")) 2 else 0
            autoInspectDayChatUntilTimestamp = System.currentTimeMillis() + 8_000L
        }

        fun requestAutoPlayInYouTube() {
            autoPlayYouTubeUntilTimestamp = System.currentTimeMillis() + 10_000L
        }
    }
}
