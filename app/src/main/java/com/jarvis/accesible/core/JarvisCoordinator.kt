package com.jarvis.accesible.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Coordinador principal que une la voz, el botón físico, la IA y las acciones sobre WhatsApp/Android.
 */
class JarvisCoordinator private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    val speaker = SpeakerManager(context)
    val geminiAgent = GeminiAgent(context)

    var isListeningUiState by mutableStateOf(false)
        private set

    var isProcessingUiState by mutableStateOf(false)
        private set

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
            isListeningUiState = false
            isProcessingUiState = false
            if (pendingWhatsAppConfirmation != null) {
                pendingWhatsAppConfirmation = null
                speaker.speak("Cancelado.")
            } else {
                speaker.speak("No te escuché.")
            }
        },
        onListeningStateChanged = { listening ->
            isListeningUiState = listening
        }
    )

    fun triggerHandsFreeListening(greetBeforeListening: Boolean = false) {
        speaker.stopSpeaking()
        WhatsAppRepository.stopAudioPlayback()

        if (greetBeforeListening) {
            speaker.speak("Te escucho") {
                speaker.beepAndVibrate(startListening = true)
                mainHandler.postDelayed({
                    speechInput.startCommandListening()
                }, 350L)
            }
        } else {
            speaker.beepAndVibrate(startListening = true)
            mainHandler.postDelayed({
                speechInput.startCommandListening()
            }, 350L)
        }
    }

    private fun handleUserSpokenText(userText: String) {
        isListeningUiState = false
        speaker.beepAndVibrate(startListening = false)

        val pending = pendingWhatsAppConfirmation
        if (pending != null) {
            pendingWhatsAppConfirmation = null
            val norm = WhatsAppRepository.normalizeText(userText)
            if (norm.contains("si") || norm.contains("mandalo") || norm.contains("enviar") || norm.contains("dale") || norm.contains("correcto")) {
                val result = WhatsAppRepository.sendTextMessage(context, pending.first, pending.second)
                speaker.speak(result)
            } else {
                speaker.speak("Cancelado.")
            }
            return
        }

        scope.launch {
            isProcessingUiState = true
            val decision = try {
                geminiAgent.interpretUserCommand(userText)
            } finally {
                isProcessingUiState = false
            }

            when (decision) {
                is AgentDecision.SpeakOnly -> {
                    speaker.speak(decision.message)
                }

                is AgentDecision.CheckTimeOnly -> {
                    speaker.speak(SystemActionsController.getCurrentTimeOnly())
                }

                is AgentDecision.CheckDateOnly -> {
                    speaker.speak(SystemActionsController.getCurrentDateOnly())
                }

                is AgentDecision.CheckBatteryOnly -> {
                    speaker.speak(SystemActionsController.getBatteryOnly(context))
                }

                is AgentDecision.CheckConnectionOnly -> {
                    speaker.speak(SystemActionsController.getConnectionOnly(context))
                }

                is AgentDecision.CheckWeather -> {
                    val weatherMsg = SystemActionsController.getFlexibleWeatherReport(context, decision.rawQuery)
                    speaker.speak(weatherMsg)
                }

                is AgentDecision.CheckUnreadWhatsApp -> {
                    val summary = WhatsAppRepository.getUnreadSummary(context)
                    speaker.speak(summary)
                }

                is AgentDecision.ReadWhatsAppMessagesFiltered -> {
                    val report = WhatsAppRepository.readMessagesByFilter(
                        context = context,
                        senderQuery = decision.senderName,
                        dayFilter = decision.dayFilter,
                        onAsyncScreenRead = { screenText ->
                            speaker.speak(screenText)
                        }
                    )
                    speaker.speak(report)
                }

                is AgentDecision.PlayWhatsAppVoiceNote -> {
                    WhatsAppRepository.playWhatsAppVoiceNoteFlexible(
                        context = context,
                        senderHint = decision.senderName,
                        whichOffset = decision.whichOffset,
                        dayFilter = decision.dayFilter,
                        forceOpenChat = decision.forceOpenChat
                    ) { ok, finishMsg ->
                        if (!ok && finishMsg.isNotBlank()) {
                            speaker.speak(finishMsg)
                        }
                    }
                }

                is AgentDecision.NavigateQueue -> {
                    WhatsAppRepository.stepActiveQueue(
                        context = context,
                        isNext = decision.isNext,
                        isRepeat = decision.isRepeat,
                        onSpeakMessage = { msg ->
                            if (msg.isNotBlank()) {
                                speaker.speak(msg)
                            }
                        }
                    )
                }

                is AgentDecision.ProposeWhatsAppTextMessage -> {
                    if (decision.recipientName.isBlank() || decision.messageText.isBlank()) {
                        speaker.speak("Decime para quién es y el mensaje.")
                    } else {
                        pendingWhatsAppConfirmation = Pair(decision.recipientName, decision.messageText)
                        speaker.speak(
                            "Mensaje para ${decision.recipientName}: \"${decision.messageText}\". ¿Lo mando? Decí sí o no."
                        ) {
                            speaker.beepAndVibrate(startListening = true)
                            mainHandler.postDelayed({
                                speechInput.startCommandListening()
                            }, 350L)
                        }
                    }
                }

                is AgentDecision.StartWhatsAppVoiceNoteRecording -> {
                    if (decision.recipientName.isBlank()) {
                        speaker.speak("Decime para quién es el audio.")
                    } else {
                        speaker.speak("Hablá después del tono.") {
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
                    speaker.speak("Poniendo ${decision.searchQuery}.") {
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

                is AgentDecision.ToggleWakeWordListening -> {
                    AppPrefs.setWakeWordEnabled(context, decision.enable)
                    speechInput.syncWakeWordLoop()
                    val msg = if (decision.enable) "Escucha por voz activada." else "Escucha por voz desactivada."
                    speaker.speak(msg)
                }

                is AgentDecision.IdentifyBanknote -> {
                    speaker.speak("Apuntá al billete.") {
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
