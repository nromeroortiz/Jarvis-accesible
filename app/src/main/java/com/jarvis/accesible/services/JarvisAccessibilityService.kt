package com.jarvis.accesible.services

import android.accessibilityservice.AccessibilityService
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
 * 3. Toca automáticamente el botón "Enviar" en WhatsApp cuando se envía un audio grabado o un mensaje nuevo,
 *    para que el proceso nunca se corte esperando un toque en la pantalla.
 */
class JarvisAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastVolumeUpDownTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Asegura que el coordinador y el servicio en segundo plano estén activos
        JarvisForegroundService.startServiceIfPossible(applicationContext)
        JarvisCoordinator.getInstance(applicationContext).speechInput.syncWakeWordLoop()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (!AppPrefs.isVolumeButtonTriggerEnabled(applicationContext)) return false

        // Doble toque en el botón físico de Subir Volumen activa a Jarvis en modo manos libres:
        // Así un toque simple sigue sirviendo para subir el volumen normalmente,
        // y dos toques seguidos activan al asistente sin tener que mantener apretado el botón mientras habla.
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeUpDownTime in 80L..650L) {
                lastVolumeUpDownTime = 0L
                JarvisCoordinator.getInstance(applicationContext).triggerHandsFreeListening(greetBeforeListening = false)
                return true // Consumimos el segundo toque para que empiece a escuchar limpio
            }
            lastVolumeUpDownTime = now
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        // 1. Automatización en WhatsApp: clic automático en "Enviar" cuando se comparte un audio grabado o un texto
        if (pkg.startsWith("com.whatsapp")) {
            if (now < autoClickSendUntilTimestamp) {
                val root = rootInActiveWindow ?: return
                if (findAndClickSendButtonInWhatsApp(root)) {
                    autoClickSendUntilTimestamp = 0L
                    // Volvemos atrás suavemente después de 1.5s para que el teléfono quede limpio
                    mainHandler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }, 1500L)
                }
            } else if (now < autoPlayVoiceNoteUntilTimestamp) {
                val root = rootInActiveWindow ?: return
                if (findAndClickPlayVoiceNoteInWhatsApp(root)) {
                    autoPlayVoiceNoteUntilTimestamp = 0L
                }
            }
        }

        // 2. Automatización en YouTube: si abrió resultados de búsqueda o cartel de anuncio, toca el primer video / Saltar anuncio
        if (pkg.contains("youtube") && now < autoPlayYouTubeUntilTimestamp) {
            val root = rootInActiveWindow ?: return
            if (findAndClickFirstYouTubeItem(root)) {
                autoPlayYouTubeUntilTimestamp = 0L
            }
        }
    }

    private fun findAndClickSendButtonInWhatsApp(root: AccessibilityNodeInfo): Boolean {
        // Búsqueda por IDs oficiales de botón Enviar en WhatsApp
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

        // Búsqueda por descripción de accesibilidad ("Enviar" / "Send")
        return clickByContentDescriptionOrText(root, listOf("enviar", "send"))
    }

    private fun findAndClickPlayVoiceNoteInWhatsApp(root: AccessibilityNodeInfo): Boolean {
        return clickByContentDescriptionOrText(root, listOf("reproducir", "play"))
    }

    private fun findAndClickFirstYouTubeItem(root: AccessibilityNodeInfo): Boolean {
        // Primero si hay botón "Saltar anuncio" / "Omitir anuncio", lo toca
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
        private var autoPlayYouTubeUntilTimestamp: Long = 0L

        fun requestAutoClickSendInWhatsApp() {
            autoClickSendUntilTimestamp = System.currentTimeMillis() + 12_000L
        }

        fun requestAutoPlayVoiceNoteInWhatsApp() {
            autoPlayVoiceNoteUntilTimestamp = System.currentTimeMillis() + 10_000L
        }

        fun requestAutoPlayInYouTube() {
            autoPlayYouTubeUntilTimestamp = System.currentTimeMillis() + 10_000L
        }
    }
}
