package com.jarvis.accesible.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.jarvis.accesible.core.AppPrefs

/**
 * Gestiona tanto la escucha de comandos manos libres (con doble toque de Subir Volumen o botón en pantalla)
 * como el modo opcional de activación por voz diciendo "Wally" / "Hola Wally".
 * No interrumpe videos de Instagram, TikTok ni YouTube.
 */
class SpeechInputManager(
    private val context: Context,
    private val onCommandRecognized: (String) -> Unit,
    private val onWakeWordDetected: (String?) -> Unit,
    private val onListeningError: (Int) -> Unit,
    private val onListeningStateChanged: ((Boolean) -> Unit)? = null,
    private val isBusySpeakingOrPlaying: (() -> Boolean)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var speechRecognizer: SpeechRecognizer? = null

    @Volatile
    var isActivelyListeningCommand: Boolean = false
        private set

    @Volatile
    private var isWakeWordLoopActive: Boolean = false

    private val wakeWordCycleRunnable = Runnable {
        startWakeWordSingleCycle()
    }

    private fun scheduleNextWakeWordCycle(delayMs: Long) {
        mainHandler.removeCallbacks(wakeWordCycleRunnable)
        if (AppPrefs.isWakeWordEnabled(context) && !isActivelyListeningCommand) {
            mainHandler.postDelayed(wakeWordCycleRunnable, delayMs)
        }
    }

    fun startCommandListening() {
        startCommandListeningInternal(hasRetried = false)
    }

    private fun startCommandListeningInternal(hasRetried: Boolean) {
        mainHandler.post {
            mainHandler.removeCallbacks(wakeWordCycleRunnable)
            isWakeWordLoopActive = false
            isActivelyListeningCommand = true
            onListeningStateChanged?.invoke(true)
            destroyRecognizer()

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                isActivelyListeningCommand = false
                onListeningStateChanged?.invoke(false)
                onListeningError(-1)
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                isActivelyListeningCommand = false
                onListeningStateChanged?.invoke(false)
                onListeningError(-1)
                return@post
            }

            val startTimestamp = System.currentTimeMillis()
            var lastPartialText = ""

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isActivelyListeningCommand = false
                        onListeningStateChanged?.invoke(false)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        if (!partial.isNullOrEmpty()) {
                            lastPartialText = partial
                        }
                    }

                    override fun onError(error: Int) {
                        val elapsed = System.currentTimeMillis() - startTimestamp

                        if (lastPartialText.isNotEmpty()) {
                            isActivelyListeningCommand = false
                            onListeningStateChanged?.invoke(false)
                            val recognized = lastPartialText
                            lastPartialText = ""
                            onCommandRecognized(recognized)
                            scheduleWakeWordResumeIfEnabled()
                            return
                        }

                        if (!hasRetried && elapsed < 1400L) {
                            mainHandler.postDelayed({
                                startCommandListeningInternal(hasRetried = true)
                            }, 350L)
                            return
                        }

                        isActivelyListeningCommand = false
                        onListeningStateChanged?.invoke(false)
                        onListeningError(error)
                        scheduleWakeWordResumeIfEnabled()
                    }

                    override fun onResults(results: Bundle?) {
                        isActivelyListeningCommand = false
                        onListeningStateChanged?.invoke(false)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val bestText = matches?.firstOrNull()?.trim().orEmpty().ifEmpty { lastPartialText }
                        lastPartialText = ""

                        if (bestText.isNotEmpty()) {
                            onCommandRecognized(bestText)
                        } else {
                            onListeningError(SpeechRecognizer.ERROR_NO_MATCH)
                        }
                        scheduleWakeWordResumeIfEnabled()
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-AR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            try {
                speechRecognizer?.startListening(intent)
            } catch (_: Exception) {
                isActivelyListeningCommand = false
                onListeningStateChanged?.invoke(false)
                onListeningError(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    fun syncWakeWordLoop() {
        mainHandler.post {
            mainHandler.removeCallbacks(wakeWordCycleRunnable)
            if (AppPrefs.isWakeWordEnabled(context) && !isActivelyListeningCommand) {
                scheduleNextWakeWordCycle(600L)
            } else if (!AppPrefs.isWakeWordEnabled(context) && !isActivelyListeningCommand) {
                isWakeWordLoopActive = false
                destroyRecognizer()
            }
        }
    }

    fun stopAllListening() {
        mainHandler.post {
            mainHandler.removeCallbacks(wakeWordCycleRunnable)
            isActivelyListeningCommand = false
            isWakeWordLoopActive = false
            onListeningStateChanged?.invoke(false)
            destroyRecognizer()
        }
    }

    private fun scheduleWakeWordResumeIfEnabled() {
        scheduleNextWakeWordCycle(2600L)
    }

    private fun startWakeWordSingleCycle() {
        mainHandler.removeCallbacks(wakeWordCycleRunnable)
        if (isActivelyListeningCommand || !AppPrefs.isWakeWordEnabled(context)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        // Si el usuario está viendo un video o escuchando música/audio (Instagram, TikTok, YouTube, Spotify, WhatsApp)
        // o si Wally está hablando (o recién terminó de hablar hace menos de 1.5s), NO abrimos el micrófono.
        val isMediaPlayingNow = try {
            audioManager.isMusicActive
        } catch (_: Exception) {
            false
        }
        if (isMediaPlayingNow || isBusySpeakingOrPlaying?.invoke() == true) {
            destroyRecognizer()
            scheduleNextWakeWordCycle(2800L)
            return
        }

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
                    val cooldown = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                        3500L
                    } else {
                        2400L
                    }
                    scheduleNextWakeWordCycle(cooldown)
                }

                override fun onResults(results: Bundle?) {
                    if (isBusySpeakingOrPlaying?.invoke() == true) {
                        scheduleNextWakeWordCycle(2600L)
                        return
                    }
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val heard = matches?.firstOrNull()?.lowercase()?.trim() ?: ""
                    val customWake = AppPrefs.getWakeWordPhrase(context).lowercase().trim()

                    // Solo activamos si la oración empieza explícitamente llamando a "Wally" (o "Jarvis")
                    val wakeRegex = Regex(
                        "^(?:hola\\s+|che\\s+|ok\\s+|hey\\s+|buen dia\\s+)?(?:wally|wali|waly|wall-e|wall e|guali|buali|jarvis|sharvis|${Regex.escape(customWake)})(?:\\b|[\\s,.:;!?]+)(.*)$",
                        RegexOption.IGNORE_CASE
                    )
                    val match = wakeRegex.find(heard)
                    if (match != null) {
                        val commandAfterWakeWord = match.groupValues.getOrNull(1)?.trim().orEmpty()
                        onWakeWordDetected(commandAfterWakeWord.ifEmpty { null })
                    } else {
                        scheduleNextWakeWordCycle(1500L)
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
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            scheduleNextWakeWordCycle(3000L)
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
