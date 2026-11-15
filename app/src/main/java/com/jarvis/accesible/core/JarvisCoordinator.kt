package com.jarvis.accesible.core

import android.annotation.SuppressLint
import android.content.Context
import com.jarvis.accesible.actions.SystemActionsController
import com.jarvis.accesible.ai.AgentDecision
import com.jarvis.accesible.ai.GeminiAgent
import com.jarvis.accesible.vision.BillRecognizer
import com.jarvis.accesible.voice.SpeakerManager
import com.jarvis.accesible.voice.SpeechInputManager
import com.jarvis.accesible.whatsapp.WhatsAppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Coordinador principal que une la voz, el botón físico, la IA de Gemini y las acciones sobre WhatsApp/Android.
 * Incluye flujo de confirmación por voz antes de enviar un mensaje de texto por WhatsApp.
 */
class JarvisCoordinator private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val speaker = SpeakerManager(context)
    private val geminiAgent = GeminiAgent(context)

    // Si hay un mensaje de texto de WhatsApp esperando que el tío diga "Sí, mandalo" o "Cancelar"
    private var pendingWhatsAppConfirmation: Pair<String, String>? = null

    val speechInput = SpeechInputManager(
        context = context,
        onCommandRecognized = { recognizedText ->
            handleUserSpokenText(recognizedText)
        },
        onWakeWordDetected = { inlineCommand ->
            if (!inlineCommand.isNullOrBlank() && inlineCommand.length > 3) {
                speaker.beepAndVibrate(startListening = false)
                handleUserSpokenText(inlineCommand)
            } else {
                triggerHandsFreeListening(greetBeforeListening = true)
            }
        },
        onListeningError = {
            if (pendingWhatsAppConfirmation != null) {
                pendingWhatsAppConfirmation = null
                speaker.speak("No te escuché, cancelé el envío del mensaje.")
            } else {
                speaker.speak("No llegué a escucharte. Tocá el botón o llamame cuando quieras.")
            }
        }
    )

    /**
     * Se invoca cuando el tío toca el botón físico de Subir Volumen, toca el botón gigante en pantalla
     * o dice la palabra de activación "Hola Jarvis".
     * NO requiere mantener apretado ningún botón mientras habla.
     */
    fun triggerHandsFreeListening(greetBeforeListening: Boolean = false) {
        // Si Jarvis estaba hablando o había un audio de WhatsApp sonando, lo callamos primero
        speaker.stopSpeaking()
        WhatsAppRepository.stopAudioPlayback()

        if (greetBeforeListening) {
            speaker.speak("Sí, te escucho") {
                speaker.beepAndVibrate(startListening = true)
                speechInput.startCommandListening()
            }
        } else {
            speaker.beepAndVibrate(startListening = true)
            speechInput.startCommandListening()
        }
    }

    private fun handleUserSpokenText(userText: String) {
        speaker.beepAndVibrate(startListening = false)

        // ¿Estábamos esperando confirmación para enviar un mensaje de texto por WhatsApp?
        val pending = pendingWhatsAppConfirmation
        if (pending != null) {
            pendingWhatsAppConfirmation = null
            val norm = WhatsAppRepository.normalizeText(userText)
            if (norm.contains("si") || norm.contains("mandalo") || norm.contains("enviar") || norm.contains("dale") || norm.contains("correcto")) {
                val result = WhatsAppRepository.sendTextMessage(context, pending.first, pending.second)
                speaker.speak(result)
            } else {
                speaker.speak("Entendido, cancelé el mensaje para ${pending.first}.")
            }
            return
        }

        scope.launch {
            when (val decision = geminiAgent.interpretUserCommand(userText)) {
                is AgentDecision.SpeakOnly -> {
                    speaker.speak(decision.message)
                }

                is AgentDecision.CheckUnreadWhatsApp -> {
                    val summary = WhatsAppRepository.getUnreadSummary()
                    speaker.speak(summary)
                }

                is AgentDecision.ReadWhatsAppFromSender -> {
                    val report = WhatsAppRepository.getMessagesFromSender(decision.senderName)
                    speaker.speak(report)
                }

                is AgentDecision.PlayLatestWhatsAppVoiceNote -> {
                    speaker.speak("Reproduciendo el audio de WhatsApp.") {
                        WhatsAppRepository.playLatestWhatsAppVoiceNote(
                            context = context,
                            senderHint = decision.senderName
                        ) { _, finishMsg ->
                            speaker.speak("$finishMsg ¿Querés responderle con un audio o por texto?")
                        }
                    }
                }

                is AgentDecision.ProposeWhatsAppTextMessage -> {
                    if (decision.recipientName.isBlank() || decision.messageText.isBlank()) {
                        speaker.speak("Decime a quién querés escribirle y qué mensaje le ponemos.")
                    } else {
                        pendingWhatsAppConfirmation = Pair(decision.recipientName, decision.messageText)
                        speaker.speak(
                            "Le voy a mandar un WhatsApp a ${decision.recipientName} que dice: \"${decision.messageText}\". ¿Se lo mando? Decí sí o cancelar."
                        ) {
                            speaker.beepAndVibrate(startListening = true)
                            speechInput.startCommandListening()
                        }
                    }
                }

                is AgentDecision.StartWhatsAppVoiceNoteRecording -> {
                    if (decision.recipientName.isBlank()) {
                        speaker.speak("Decime a qué contacto le querés grabar el audio de WhatsApp.")
                    } else {
                        speaker.speak(
                            "Después del pitido, hablá para grabar tu audio para ${decision.recipientName}. Cuando termines de hablar, hacé silencio dos segundos y se enviará solo."
                        ) {
                            WhatsAppRepository.recordAndSendRealVoiceNote(
                                context = context,
                                recipientName = decision.recipientName,
                                onRecordingStarted = {
                                    speaker.beepAndVibrate(startListening = true)
                                },
                                onCompleted = { statusMsg ->
                                    speaker.beepAndVibrate(startListening = false)
                                    speaker.speak(statusMsg)
                                }
                            )
                        }
                    }
                }

                is AgentDecision.SetAlarm -> {
                    val msg = SystemActionsController.setSilentAlarm(
                        context,
                        decision.hour,
                        decision.minute,
                        decision.label
                    )
                    speaker.speak(msg)
                }

                is AgentDecision.PlayYouTube -> {
                    speaker.speak("Buscando y reproduciendo ${decision.searchQuery} en YouTube.") {
                        scope.launch {
                            SystemActionsController.playOnYouTubeDirectly(context, decision.searchQuery)
                        }
                    }
                }

                is AgentDecision.StopPlaybackOrAlarm -> {
                    val msg = SystemActionsController.stopAnythingPlayingOrRinging(context)
                    speaker.speak(msg)
                }

                is AgentDecision.MakePhoneCall -> {
                    val msg = SystemActionsController.makePhoneCallOnSpeaker(context, decision.contactName)
                    speaker.speak(msg)
                }

                is AgentDecision.EndOrAnswerCall -> {
                    val msg = SystemActionsController.answerOrEndPhoneCall(context, decision.endCall)
                    speaker.speak(msg)
                }

                is AgentDecision.CheckPhoneStatus -> {
                    val msg = SystemActionsController.getPhoneStatusReport(context)
                    speaker.speak(msg)
                }

                is AgentDecision.ToggleWakeWordListening -> {
                    AppPrefs.setWakeWordEnabled(context, decision.enable)
                    speechInput.syncWakeWordLoop()
                    val msg = if (decision.enable) {
                        "Activé la escucha continua por voz. Ahora podés llamarme diciendo Hola Jarvis."
                    } else {
                        "Desactivé la escucha continua por voz para ahorrar batería. Podés activarme tocando dos veces el botón de Subir Volumen."
                    }
                    speaker.speak(msg)
                }

                is AgentDecision.IdentifyBanknote -> {
                    speaker.speak("Mantené el billete frente a la cámara de atrás.") {
                        scope.launch {
                            val result = BillRecognizer.scanAndIdentifyBanknote(
                                context = context,
                                geminiAgent = geminiAgent,
                                onShutterTaken = {
                                    speaker.beepAndVibrate(startListening = false)
                                }
                            )
                            speaker.speak(result)
                        }
                    }
                }
            }
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: JarvisCoordinator? = null

        fun getInstance(context: Context): JarvisCoordinator {
            return instance ?: synchronized(this) {
                instance ?: JarvisCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }
}
