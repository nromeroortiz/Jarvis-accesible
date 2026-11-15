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
 * 1. Arranca solo al encender el teléfono sin que el tío tenga que abrir ninguna aplicación.
 * 2. Detecta cuando el tío toca 2 veces seguidas el botón físico de Subir Volumen (y suelta el botón),
 *    activando el micrófono manos libres sin tener que mantener apretado el botón mientras habla.
 * 3. En WhatsApp:
 *    - Toca automáticamente el botón "Enviar" cuando se envía un audio grabado o un mensaje de texto.
 *    - En el chat de un contacto, busca todos los audios en pantalla y toca "Reproducir" en el que pidió el tío
 *      (el último de abajo de todo, el penúltimo/anterior, el antepenúltimo, etc.).
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastVolumeUpDownTime = 0L

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
            } else if (now < autoPlayVoiceNoteUntilTimestamp) {
                val root = rootInActiveWindow ?: return
                // Esperamos 450ms a que WhatsApp termine de dibujar la lista de mensajes del chat
                mainHandler.postDelayed({
                    val freshRoot = rootInActiveWindow ?: root
                    if (now < autoPlayVoiceNoteUntilTimestamp &&
                        findAndClickTargetVoiceNoteInWhatsApp(freshRoot, targetVoiceNoteOffsetFromBottom)
                    ) {
                        autoPlayVoiceNoteUntilTimestamp = 0L
                    }
                }, 450L)
            }
        }

        // 2. Automatización en YouTube: toca el primer video o Salta anuncio
        if (pkg.contains("youtube") && now < autoPlayYouTubeUntilTimestamp) {
            val root = rootInActiveWindow ?: return
            if (findAndClickFirstYouTubeItem(root)) {
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
     * los ordena de arriba hacia abajo en la pantalla y hace clic en el que pidió el usuario:
     * offsetFromBottom = 1 -> el último audio (el de más abajo)
     * offsetFromBottom = 2 -> el penúltimo / anterior audio
     * offsetFromBottom = 3 -> el antepenúltimo audio (o para escuchar los últimos 3 seguidos)
     */
    private fun findAndClickTargetVoiceNoteInWhatsApp(
        root: AccessibilityNodeInfo,
        offsetFromBottom: Int
    ): Boolean {
        val playNodes = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectPlayVoiceNoteNodes(root, playNodes)

        if (playNodes.isEmpty()) return false

        // Ordenamos verticalmente por posición en pantalla (de más arriba a más abajo)
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

        val isPlayBtn = desc == "reproducir" ||
            desc.startsWith("reproducir ") ||
            desc == "play" ||
            text == "reproducir" ||
            viewId.endsWith(":id/control_btn") ||
            viewId.endsWith(":id/audio_play_pause_btn")

        if (isPlayBtn) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.top > 100 && rect.bottom > rect.top) {
                outList.add(Pair(rect.top, node))
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectPlayVoiceNoteNodes(child, outList)
        }
    }

    private fun findAndClickFirstYouTubeItem(root: AccessibilityNodeInfo): Boolean {
        if (clickByContentDescriptionOrText(root, listOf("saltar anuncio", "omitir anuncio", "skip ad"))) {
            return true
        }
        return false
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
        private var targetVoiceNoteOffsetFromBottom: Int = 1

        @Volatile
        private var autoPlayYouTubeUntilTimestamp: Long = 0L

        fun requestAutoClickSendInWhatsApp() {
            autoClickSendUntilTimestamp = System.currentTimeMillis() + 12_000L
        }

        fun requestAutoPlayVoiceNoteInWhatsApp(offsetFromBottom: Int = 1) {
            targetVoiceNoteOffsetFromBottom = offsetFromBottom.coerceIn(1, 10)
            autoPlayVoiceNoteUntilTimestamp = System.currentTimeMillis() + 10_000L
        }

        fun requestAutoPlayInYouTube() {
            autoPlayYouTubeUntilTimestamp = System.currentTimeMillis() + 10_000L
        }
    }
}
