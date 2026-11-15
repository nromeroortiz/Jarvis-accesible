package com.jarvis.accesible.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.jarvis.accesible.core.AppPrefs
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SpeakerManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    var isSpeakingNow: Boolean = false
        private set

    @Volatile
    var lastSpeechEndedTimestamp: Long = 0L
        private set

    fun isSpeakingOrRecentlyFinished(cooldownMs: Long = 1400L): Boolean {
        if (isSpeakingNow) return true
        return (System.currentTimeMillis() - lastSpeechEndedTimestamp) < cooldownMs
    }

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (_: Throwable) {
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val localeAr = Locale("es", "AR")
            val localeEs = Locale("es", "ES")
            val result = tts?.setLanguage(localeAr)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(localeEs)
            }
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeakingNow = true
                }

                override fun onDone(utteranceId: String?) {
                    completeUtterance(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    completeUtterance(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (!interrupted) {
                        completeUtterance(utteranceId)
                    } else {
                        isSpeakingNow = false
                        lastSpeechEndedTimestamp = System.currentTimeMillis()
                        abandonAudioFocus()
                    }
                }
            })
            isReady = true
        }
    }

    private fun completeUtterance(utteranceId: String?) {
        isSpeakingNow = false
        lastSpeechEndedTimestamp = System.currentTimeMillis()
        abandonAudioFocus()
        if (utteranceId != null) {
            val callback = pendingCallbacks.remove(utteranceId)
            if (callback != null) {
                mainHandler.post { callback.invoke() }
            }
        }
    }

    fun speak(text: String, flush: Boolean = true, onFinished: (() -> Unit)? = null) {
        val cleanText = text
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .trim()

        if (cleanText.isEmpty()) {
            onFinished?.invoke()
            return
        }

        if (!isReady) {
            mainHandler.postDelayed({ speak(cleanText, flush, onFinished) }, 400)
            return
        }

        if (flush) {
            pendingCallbacks.clear()
        }

        requestAudioFocus()
        tts?.setSpeechRate(AppPrefs.getSpeechRate(context))

        val utteranceId = UUID.randomUUID().toString()
        if (onFinished != null) {
            pendingCallbacks[utteranceId] = onFinished
            // Respaldo de seguridad por si el motor TTS de Android pierde el evento onDone al cambiar de ventana a WhatsApp
            val estimatedMs = (cleanText.length * 85L).coerceIn(2200L, 35000L) + 1500L
            mainHandler.postDelayed({
                if (pendingCallbacks.containsKey(utteranceId) && !(tts?.isSpeaking ?: false)) {
                    completeUtterance(utteranceId)
                }
            }, estimatedMs)
        }

        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        isSpeakingNow = true
        val res = tts?.speak(cleanText, queueMode, params, utteranceId) ?: TextToSpeech.ERROR
        if (res == TextToSpeech.ERROR) {
            completeUtterance(utteranceId)
        }
    }

    fun stopSpeaking() {
        pendingCallbacks.clear()
        isSpeakingNow = false
        lastSpeechEndedTimestamp = System.currentTimeMillis()
        tts?.stop()
        abandonAudioFocus()
    }

    fun beepAndVibrate(startListening: Boolean = true) {
        try {
            val tone = if (startListening) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK
            val toneGen = ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, 90)
            toneGen.startTone(tone, 180)
            mainHandler.postDelayed({ toneGen.release() }, 250)
        } catch (_: Exception) {
        }

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val duration = if (startListening) 120L else 70L
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun requestAudioFocus() {
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } catch (_: Exception) {
        }
    }

    private fun abandonAudioFocus() {
        try {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {
        }
    }
}
