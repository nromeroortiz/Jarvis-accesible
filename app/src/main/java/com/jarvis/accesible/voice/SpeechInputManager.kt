package com.jarvis.accesible.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jarvis.accesible.core.AppPrefs

/**
 * Gestiona tanto la escucha de comandos manos libres (sin tener que mantener apretado ningún botón)
 * como el modo opcional de escucha continua por palabra de activación ("Jarvis" / "Asistente").
 */
class SpeechInputManager(
    private val context: Context,
    private val onCommandRecognized: (String) -> Unit,
    private val onWakeWordDetected: (String?) -> Unit,
    private val onListeningError: (Int) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    var isActivelyListeningCommand: Boolean = false
        private set

    @Volatile
    private var isWakeWordLoopActive: Boolean = false

    fun startCommandListening() {
        mainHandler.post {
            isWakeWordLoopActive = false
            isActivelyListeningCommand = true
            destroyRecognizer()

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                isActivelyListeningCommand = false
                onListeningError(-1)
                return@post
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isActivelyListeningCommand = false
                    }

                    override fun onError(error: Int) {
                        isActivelyListeningCommand = false
                        onListeningError(error)
                        scheduleWakeWordResumeIfEnabled()
                    }

                    override fun onResults(results: Bundle?) {
                        isActivelyListeningCommand = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val bestText = matches?.firstOrNull()?.trim() ?: ""
                        if (bestText.isNotEmpty()) {
                            onCommandRecognized(bestText)
                        } else {
                            onListeningError(SpeechRecognizer.ERROR_NO_MATCH)
                        }
                        scheduleWakeWordResumeIfEnabled()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                // Pausas amplias para que una persona mayor pueda hablar tranquila sin que se corte
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
            }

            try {
                speechRecognizer?.startListening(intent)
            } catch (_: Exception) {
                isActivelyListeningCommand = false
                onListeningError(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    fun syncWakeWordLoop() {
        mainHandler.post {
            if (AppPrefs.isWakeWordEnabled(context) && !isActivelyListeningCommand) {
                startWakeWordSingleCycle()
            } else if (!AppPrefs.isWakeWordEnabled(context) && !isActivelyListeningCommand) {
                isWakeWordLoopActive = false
                destroyRecognizer()
            }
        }
    }

    fun stopAllListening() {
        mainHandler.post {
            isActivelyListeningCommand = false
            isWakeWordLoopActive = false
            destroyRecognizer()
        }
    }

    private fun scheduleWakeWordResumeIfEnabled() {
        mainHandler.postDelayed({
            if (AppPrefs.isWakeWordEnabled(context) && !isActivelyListeningCommand) {
                startWakeWordSingleCycle()
            }
        }, 1200L)
    }

    private fun startWakeWordSingleCycle() {
        if (isActivelyListeningCommand || !AppPrefs.isWakeWordEnabled(context)) return
        isWakeWordLoopActive = true
        destroyRecognizer()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (AppPrefs.isWakeWordEnabled(context) && !isActivelyListeningCommand) {
                        mainHandler.postDelayed({ startWakeWordSingleCycle() }, 1800L)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val heard = matches?.firstOrNull()?.lowercase()?.trim() ?: ""
                    val wakeWord = AppPrefs.getWakeWordPhrase(context)

                    if (heard.contains(wakeWord) || heard.contains("jarvis") || heard.contains("asistente")) {
                        // Si ya dijo el pedido completo (ej. "Hola Jarvis tengo algún mensaje"), extraemos el comando
                        val remaining = heard
                            .replace("hola $wakeWord", "")
                            .replace("hola jarvis", "")
                            .replace("hola asistente", "")
                            .replace(wakeWord, "")
                            .replace("jarvis", "")
                            .replace("asistente", "")
                            .trim()
                        onWakeWordDetected(remaining.ifEmpty { null })
                    } else if (AppPrefs.isWakeWordEnabled(context) && !isActivelyListeningCommand) {
                        mainHandler.postDelayed({ startWakeWordSingleCycle() }, 800L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            mainHandler.postDelayed({ startWakeWordSingleCycle() }, 2500L)
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }
}
