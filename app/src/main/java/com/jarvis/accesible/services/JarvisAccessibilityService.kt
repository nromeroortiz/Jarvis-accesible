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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

data class ScreenChatItem(
    val isAudio: Boolean,
    val text: String,
    val topY: Int,
    val audioOrderInDay: Int, // Índice 0-based entre los audios de ese día (0 = primer audio del día)
    val audioDurationMs: Long = 12_000L // Duración real del audio en milisegundos leída de la burbuja (ej. 0:45 -> 45000ms)
)

/**
 * Servicio de Accesibilidad de Android:
 * 1. Doble toque en Subir Volumen para hablar manos libres.
 * 2. En WhatsApp:
 *    - Busca un contacto fonéticamente ("Daiana UNLa" <-> "Dayana UNLa") en la lista o con la lupa.
 *    - Analiza el chat abierto separando por los carteles de fecha ("HOY", "AYER", "VIERNES", fecha numérica),
 *      haciendo scroll hacia arriba automáticamente si el usuario pidió un día anterior ("ayer", "viernes", etc.).
 *    - Incluye tanto los mensajes recibidos como los enviados por el usuario, ordenados DEL PRIMERO AL ÚLTIMO del día.
 *    - Extrae la duración real de cada audio (ej. 0:45) para que Jarvis espere a que termine entero antes de pasar al siguiente.
 * 3. En YouTube:
 *    - Si se abre en búsqueda, hace clic en el primer video para que empiece a sonar solo.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastVolumeUpDownTime = 0L
    private var pendingChatInspectRunnable: Runnable? = null
    private var scrollAttemptsLeft = 0
    private var searchAlternateTried = false

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
                if ( com.jarvis.accesible.whatsapp.WhatsAppRepository.finishActiveVoiceNoteRecordingIfAny(applicationContext)) {
                    return true
                }
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
            // A) Clic automático en "Enviar" al mandar mensaje o audio (y selección de contacto si WhatsApp abre la pantalla "Enviar a...")
            if (now < autoClickSendUntilTimestamp) {
                val root = rootInActiveWindow ?: return
                if (findAndClickSendButtonInWhatsApp(root)) {
                    autoClickSendUntilTimestamp = 0L
                    pendingSendRecipientName = null
                    mainHandler.postDelayed({
                        val freshRoot = rootInActiveWindow
                        if (freshRoot != null) {
                            findAndClickSendButtonInWhatsApp(freshRoot)
                        }
                    }, 800L)
                    mainHandler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }, 2200L)
                } else if (!pendingSendRecipientName.isNullOrBlank()) {
                    // Si WhatsApp abrió la pantalla "Enviar a..." porque el contacto no estaba en el chip o el JID difiere,
                    // seleccionamos el contacto fonéticamente para que aparezca el botón verde de Enviar
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

            // C) Inspeccionar el chat abierto para el día pedido ("hoy", "ayer", "viernes", etc.)
            if (now < autoInspectDayChatUntilTimestamp) {
                pendingChatInspectRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    val freshRoot = rootInActiveWindow ?: return@Runnable
                    processOpenChatForRequestedDay(freshRoot)
                }
                pendingChatInspectRunnable = runnable
                mainHandler.postDelayed(runnable, 850L)
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
     * Busca un contacto en WhatsApp tolerando diferencias fonéticas ("Dayana UNLa" <-> "Daiana UNLa").
     */
    private fun handleWhatsAppSearchBarFlow(root: AccessibilityNodeInfo) {
        val query = pendingSearchContactName ?: return

        // Paso 0: Si el chat ya está visible en la lista principal de WhatsApp (comparando fonéticamente), lo tocamos directo
        val editNodes = mutableListOf<AccessibilityNodeInfo>()
        collectEditTexts(root, editNodes)
        val searchInput = editNodes.firstOrNull()

        if (searchInput == null) {
            if (clickNodeMatchingPhonetically(root, query, minTop = 220)) {
                completeSearchChatOpened()
                return
            }
        }

        // Paso 1: Si está abierto el cuadro de búsqueda, escribimos el nombre (o su variante fonética si la primera no dio resultados)
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

        // Paso 2: Tocar el botón de la lupa ("Buscar") en la pantalla principal de WhatsApp
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
        }, 500L)
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
     * Analiza el chat abierto en pantalla para el día pedido ("hoy", "ayer", "viernes", etc.),
     * hace scroll hacia arriba si todavía no aparece ese día, y entrega la lista completa
     * (textos y audios tanto recibidos como enviados) ordenada DEL PRIMERO AL ÚLTIMO de ese día.
     */
    private fun processOpenChatForRequestedDay(root: AccessibilityNodeInfo) {
        val targetDay = normalize(requestedDayFilter ?: "hoy")
        val extracted = extractChatItemsByDayFromTree(root, targetDay)

        // Si pidió un día anterior ("ayer", "viernes", etc.) y todavía no apareció el cartel de ese día en pantalla,
        // hacemos scroll hacia arriba hasta encontrarlo.
        if (!extracted.foundDayHeader && targetDay != "hoy" && scrollAttemptsLeft > 0) {
            scrollAttemptsLeft--
            if (scrollChatListBackward(root)) {
                autoInspectDayChatUntilTimestamp = System.currentTimeMillis() + 6000L
                return
            }
        }

        // Si encontramos el cartel del día pero quedó muy abajo sin mostrar sus mensajes, o si aún no hay items y podemos subir
        if (extracted.items.isEmpty() && scrollAttemptsLeft > 0) {
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
    }

    private data class DayExtractionResult(
        val foundDayHeader: Boolean,
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
        targetDayNorm: String
    ): DayExtractionResult {
        val rawElements = mutableListOf<RawScreenElement>()
        collectChatScreenElements(root, rawElements)

        val durationsOnScreen = rawElements.filter { it.type == "DURATION" && it.durationMs > 0L }
        val mainElements = rawElements
            .filter { it.type != "DURATION" }
            .sortedBy { it.topY }
            .distinctBy { "${it.type}_${it.topY / 28}_${it.text.take(25)}" }

        val headers = mainElements.filter { it.type == "HEADER" }

        var currentSectionDay = if (headers.isEmpty()) "hoy" else "anterior"
        var sawRequestedHeader = false
        val matchingItems = mutableListOf<RawScreenElement>()

        for (elem in mainElements) {
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

        // Si pidió "hoy" y el cartel "HOY" quedó más arriba fuera de la pantalla,
        // todos los elementos debajo del último header (o todos si no hay headers) pertenecen a hoy.
        if (targetDayNorm == "hoy" && !sawRequestedHeader && matchingItems.isEmpty()) {
            val afterLastHeader = if (headers.isEmpty()) {
                mainElements.filter { it.type != "HEADER" }
            } else {
                val lastH = headers.last()
                val lastHNorm = normalize(lastH.text)
                if (lastHNorm == "ayer" || !headerMatchesDay(lastHNorm, "hoy")) {
                    // Si el último cartel visible es "HOY", ya lo habría tomado arriba; si es "AYER" y hay mensajes muy abajo,
                    // solo tomamos si no hay carteles más abajo
                    emptyList()
                } else {
                    mainElements.filter { it.type != "HEADER" && it.topY > lastH.topY }
                }
            }
            matchingItems.addAll(afterLastHeader)
        }

        var audioCounter = 0
        val finalItems = matchingItems.map { raw ->
            if (raw.type == "AUDIO") {
                val idx = audioCounter
                audioCounter++
                // Buscamos la duración (ej. "0:45") en la misma burbuja (topY cercano)
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
            foundDayHeader = sawRequestedHeader || (targetDayNorm == "hoy" && finalItems.isNotEmpty()),
            items = finalItems
        )
    }

    /**
     * Compara el cartel de fecha de WhatsApp ("HOY", "AYER", "VIERNES", "13 DE NOVIEMBRE DE 2026", "13/11/2026")
     * con el día pedido por el usuario ("hoy", "ayer", "antes de ayer", "viernes", etc.).
     */
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

        // Ignorar la barra superior del contacto y la barra inferior de escribir mensaje
        if (rect.top in 165..2080 && rect.bottom > rect.top) {
            // 1. ¿Es un cartel divisor de fecha ("HOY", "AYER", "VIERNES", "13 DE NOVIEMBRE")?
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

            // 2. ¿Es un indicador de duración de audio (ej. "0:45", "1:15")?
            val parsedDur = parseAudioDurationTextMs(text.ifEmpty { desc })
            if (parsedDur > 0L && (viewId.endsWith(":id/description") || viewId.endsWith(":id/duration") || text.matches(Regex("^\\d{1,2}:\\d{2}$")))) {
                outList.add(RawScreenElement("DURATION", text, rect.top, parsedDur))
            }

            // 3. ¿Es un botón Reproducir/Pausar de un audio (tanto recibido como enviado por nosotros)?
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

            // 4. ¿Es un mensaje de texto (tanto recibido como enviado por nosotros)?
            val isMessageTextView = viewId.endsWith(":id/message_text") ||
                viewId.endsWith(":id/caption") ||
                viewId.endsWith(":id/quoted_text")
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
            // Evitar confundir la hora del mensaje (ej. 14:05) con la duración de un audio corto (normalmente 0:xx a 9:xx)
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
     * Hace clic en el audio de la posición indicada dentro del día pedido (0 = el primer audio de ese día, 1 = el segundo, etc.).
     * Si WhatsApp ya auto-avanzó a ese audio y ya está reproduciéndose (estado "Pausar"), devuelve true SIN tocarlo
     * para no ponerlo en pausa.
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
            sortedButtons.filter { (btnTop, _) ->
                val headerAbove = sortedHeaders.lastOrNull { it.topY < btnTop }
                if (headerAbove != null) {
                    headerMatchesDay(normalize(headerAbove.text), targetDayNorm)
                } else {
                    targetDayNorm == "hoy" && sortedHeaders.none { headerMatchesDay(normalize(it.text), "hoy") }
                }
            }.map { it.second }
        }

        val targetNode = matchingButtons.getOrNull(audioOrderInDay)
            ?: sortedButtons.getOrNull(audioOrderInDay)?.second
            ?: return false

        // Si este audio específico ya está sonando (su botón dice "Pausar"), NO lo tocamos para no pausarlo
        val desc = targetNode.contentDescription?.toString()?.lowercase().orEmpty()
        val txt = targetNode.text?.toString()?.lowercase().orEmpty()
        if (desc.contains("paus") || txt.contains("paus")) {
            return true
        }

        lastVoiceNoteClickTime = now
        return clickNodeOrParent(targetNode)
    }

    /**
     * Si el siguiente elemento en la cola es un TEXTO pero WhatsApp auto-inició el siguiente audio solo,
     * pausamos el audio en pantalla para poder leer el texto en voz alta sin superposición.
     */
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

        fun requestInspectOpenChatForDay(dayFilter: String, onReady: (List<ScreenChatItem>) -> Unit) {
            val cleanDay = dayFilter.ifBlank { "hoy" }.lowercase().trim()
            requestedDayFilter = cleanDay
            onDayChatItemsReadyCallback = onReady
            // Si pidió un día anterior ("ayer", "viernes", "jueves", etc.), permitimos hasta 6 scrolls hacia arriba
            instance?.scrollAttemptsLeft = if (cleanDay == "hoy") 0 else 6
            autoInspectDayChatUntilTimestamp = System.currentTimeMillis() + 12_000L
        }

        fun requestAutoPlayInYouTube() {
            autoPlayYouTubeUntilTimestamp = System.currentTimeMillis() + 10_000L
        }
    }
}
