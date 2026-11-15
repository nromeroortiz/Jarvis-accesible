package com.jarvis.accesible.services

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.accesible.core.AppPrefs
import com.jarvis.accesible.core.JarvisCoordinator

/**
 * Servicio de Accesibilidad de Android:
 * 1. Arranca solo al encender el teléfono.
 * 2. Detecta el doble toque en el botón físico de Subir Volumen.
 * 3. En WhatsApp:
 *    - Hace clic UNA SOLA VEZ en "Reproducir" del audio pedido (sin volver a tocarlo para que nunca lo ponga en pausa).
 *    - Toca "Enviar" automáticamente al mandar un audio o texto.
 *    - Lee los mensajes visibles en pantalla de un chat abierto aunque ya hayan sido leídos antes.
 * 4. En YouTube:
 *    - Si se abre en una lista de búsqueda, toca automáticamente el primer video para que empiece a sonar solo, y salta anuncios.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastVolumeUpDownTime = 0L
    private var pendingVoiceNoteClickRunnable: Runnable? = null

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

        // 1. Automatización en WhatsApp
        if (pkg.startsWith("com.whatsapp")) {
            if (now < autoClickSendUntilTimestamp) {
                val root = rootInActiveWindow ?: return
                if (findAndClickSendButtonInWhatsApp(root)) {
                    autoClickSendUntilTimestamp = 0L
                    mainHandler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }, 1500L)
                }
            } else if (now < autoPlayVoiceNoteUntilTimestamp && now > lastVoiceNoteClickTime + 3500L) {
                // Cancelamos cualquier intento previo pendiente para que NUNCA haga doble clic (Play -> Pausa)
                pendingVoiceNoteClickRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    val nowInner = System.currentTimeMillis()
                    if (nowInner < autoPlayVoiceNoteUntilTimestamp && nowInner > lastVoiceNoteClickTime + 3500L) {
                        val freshRoot = rootInActiveWindow ?: return@Runnable
                        // Apagamos la bandera ANTES del clic para garantizar que se ejecute 1 sola vez
                        val offset = targetVoiceNoteOffsetFromBottom
                        autoPlayVoiceNoteUntilTimestamp = 0L
                        lastVoiceNoteClickTime = nowInner
                        if (!findAndClickTargetVoiceNoteInWhatsApp(freshRoot, offset)) {
                            // Si aún estaba cargando el chat, permitimos un único intento más
                            autoPlayVoiceNoteUntilTimestamp = nowInner + 4000L
                            lastVoiceNoteClickTime = 0L
                        }
                    }
                }
                pendingVoiceNoteClickRunnable = runnable
                mainHandler.postDelayed(runnable, 550L)
            } else if (now < autoReadScreenChatUntilTimestamp) {
                val root = rootInActiveWindow ?: return
                val texts = extractVisibleIncomingMessagesFromChat(root)
                if (texts.isNotEmpty()) {
                    autoReadScreenChatUntilTimestamp = 0L
                    onScreenChatMessagesCaptured?.invoke(texts)
                    onScreenChatMessagesCaptured = null
                }
            }
        }

        // 2. Automatización en YouTube: toca el primer resultado de video si cayó en búsqueda o salta anuncios
        if (pkg.contains("youtube") && now < autoPlayYouTubeUntilTimestamp && now > lastYouTubeClickTime + 3000L) {
            val root = rootInActiveWindow ?: return
            if (findAndClickFirstYouTubeItem(root)) {
                lastYouTubeClickTime = now
                autoPlayYouTubeUntilTimestamp = 0L
            }
        }
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

    /**
     * Busca todos los botones "Reproducir" de notas de voz visibles en el chat abierto,
     * ignorando cualquiera que ya esté en estado "Pausar" para jamás pausar un audio en reproducción.
     */
    private fun findAndClickTargetVoiceNoteInWhatsApp(
        root: AccessibilityNodeInfo,
        offsetFromBottom: Int
    ): Boolean {
        val playNodes = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectPlayVoiceNoteNodes(root, playNodes)

        if (playNodes.isEmpty()) return false

        val sortedByTop = playNodes.sortedBy { it.first }.map { it.second }
        val targetIndex = (sortedByTop.size - offsetFromBottom.coerceAtLeast(1)).coerceIn(0, sortedByTop.lastIndex)
        return clickNodeOrParent(sortedByTop[targetIndex])
    }

    private fun collectPlayVoiceNoteNodes(
        node: AccessibilityNodeInfo?,
        outList: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        if (node == null) return
        val desc = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // Si ya dice "pausar" o "pause", NO lo tocamos para no pausar el audio
        if (desc.contains("paus") || text.contains("paus")) {
            return
        }

        val isPlayBtn = desc == "reproducir" ||
            desc.startsWith("reproducir ") ||
            desc == "play" ||
            text == "reproducir" ||
            ((viewId.endsWith(":id/control_btn") || viewId.endsWith(":id/audio_play_pause_btn")) && !desc.contains("paus"))

        if (isPlayBtn) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.top > 120 && rect.bottom > rect.top) {
                outList.add(Pair(rect.top, node))
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectPlayVoiceNoteNodes(child, outList)
        }
    }

    /**
     * Extrae los mensajes de texto recibidos visibles en la pantalla del chat de WhatsApp
     * (solo los del contacto, que están alineados al lado izquierdo de la pantalla).
     */
    private fun extractVisibleIncomingMessagesFromChat(root: AccessibilityNodeInfo): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        val msgNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text")
        if (!msgNodes.isNullOrEmpty()) {
            for (node in msgNodes) {
                val txt = node.text?.toString()?.trim() ?: continue
                if (txt.isEmpty()) continue
                val rect = Rect()
                node.getBoundsInScreen(rect)
                // En WhatsApp, los mensajes que envió la otra persona empiezan en el margen izquierdo (rect.left < 220px)
                if (rect.top > 140 && rect.left < 260) {
                    found.add(Pair(rect.top, txt))
                }
            }
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun findAndClickFirstYouTubeItem(root: AccessibilityNodeInfo): Boolean {
        // 1. Si hay cartel de "Saltar anuncio", lo toca
        if (clickByContentDescriptionOrText(root, listOf("saltar anuncio", "omitir anuncio", "skip ad"))) {
            return true
        }

        // 2. Si YouTube quedó en la lista de resultados de búsqueda, toca el primer video de la lista
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

        // Los videos en la lista de YouTube tienen descripciones largas con duración ("minutos", "segundos", "vistas")
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
        private var autoPlayVoiceNoteUntilTimestamp: Long = 0L

        @Volatile
        private var lastVoiceNoteClickTime: Long = 0L

        @Volatile
        private var targetVoiceNoteOffsetFromBottom: Int = 1

        @Volatile
        private var autoReadScreenChatUntilTimestamp: Long = 0L

        @Volatile
        private var onScreenChatMessagesCaptured: ((List<String>) -> Unit)? = null

        @Volatile
        private var autoPlayYouTubeUntilTimestamp: Long = 0L

        @Volatile
        private var lastYouTubeClickTime: Long = 0L

        fun requestAutoClickSendInWhatsApp() {
            autoClickSendUntilTimestamp = System.currentTimeMillis() + 12_000L
        }

        fun requestAutoPlayVoiceNoteInWhatsApp(offsetFromBottom: Int = 1) {
            targetVoiceNoteOffsetFromBottom = offsetFromBottom.coerceIn(1, 10)
            autoPlayVoiceNoteUntilTimestamp = System.currentTimeMillis() + 8_000L
        }

        fun requestReadVisibleChatInWhatsApp(onCaptured: (List<String>) -> Unit) {
            onScreenChatMessagesCaptured = onCaptured
            autoReadScreenChatUntilTimestamp = System.currentTimeMillis() + 7_000L
        }

        fun requestAutoPlayInYouTube() {
            autoPlayYouTubeUntilTimestamp = System.currentTimeMillis() + 10_000L
        }
    }
}
