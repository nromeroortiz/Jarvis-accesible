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
    private var pendingDayForContact: String? = null
    private var pendingContinueToTodayForContact: String? = null

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
            } else if (pendingContinueToTodayForContact != null) {
                pendingContinueToTodayForContact = null
            } else if (pendingDayForContact != null) {
                val contact = pendingDayForContact
                pendingDayForContact = null
                if (!contact.isNullOrBlank()) {
                    startUnifiedDayPlaybackWithFollowUp(contact, "hoy")
                }
            } else {
                speaker.speak("No te escuché.")
            }
        },
        onListeningStateChanged = { listening ->
            isListeningUiState = listening
        },
        isBusySpeakingOrPlaying = {
            speaker.isSpeakingNow || WhatsAppRepository.isCurrentlyPlayingOrRecordingAudio()
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

    private fun startUnifiedDayPlaybackWithFollowUp(senderQuery: String?, dayFilter: String) {
        WhatsAppRepository.startDayChatPlaybackFromFirstToLast(
            context = context,
            senderQuery = senderQuery,
            dayFilterRaw = dayFilter,
            onPastDayFinished = { contactName, finishedDay ->
                pendingContinueToTodayForContact = contactName
                speaker.speak("Terminaron los mensajes de $finishedDay. ¿Querés seguir con los de hoy?") {
                    speaker.beepAndVibrate(startListening = true)
                    mainHandler.postDelayed({
                        speechInput.startCommandListening()
                    }, 350L)
                }
            }
        ) { text, onDone ->
            speaker.speak(text, onFinished = onDone)
        }
    }

    private fun handleUserSpokenText(userText: String) {
        isListeningUiState = false
        speaker.beepAndVibrate(startListening = false)

        // 1. Confirmación pendiente de envío de mensaje escrito
        val pending = pendingWhatsAppConfirmation
        if (pending != null) {
            pendingWhatsAppConfirmation = null
            val norm = WhatsAppRepository.normalizeText(userText)
            if (norm.contains("si") || norm.contains("mandalo") || norm.contains("enviar") || norm.contains("dale") || norm.contains("bueno") || norm.contains("correcto")) {
                val result = WhatsAppRepository.sendTextMessage(context, pending.first, pending.second)
                speaker.speak(result)
            } else {
                speaker.speak("Cancelado.")
            }
            return
        }

        // 2. Confirmación pendiente al terminar un día anterior: "¿Querés seguir con los de hoy?"
        val continueContact = pendingContinueToTodayForContact
        if (continueContact != null) {
            pendingContinueToTodayForContact = null
            val norm = WhatsAppRepository.normalizeText(userText)
            val otherDay = geminiAgent.extractDayFilterFromCommand(norm)
            if (norm == "si" || norm.startsWith("si ") || norm.contains("dale") || norm.contains("bueno") || norm.contains("segui") || norm.contains("hoy")) {
                startUnifiedDayPlaybackWithFollowUp(continueContact, "hoy")
                return
            } else if (otherDay != null) {
                startUnifiedDayPlaybackWithFollowUp(continueContact, otherDay)
                return
            } else if (norm == "no" || norm.startsWith("no ") || norm.contains("gracias") || norm.contains("basta") || norm.contains("nada mas")) {
                speaker.speak("Listo.")
                return
            }
            // Si dijo otro comando distinto (ej. "contestale"), dejamos que siga abajo y lo ejecute
        }

        // 3. Si había dicho "Abrí el chat de X" y Jarvis le preguntó "¿De qué día querés escuchar?"
        val contactWaitingDay = pendingDayForContact
        if (contactWaitingDay != null) {
            pendingDayForContact = null
            val norm = WhatsAppRepository.normalizeText(userText)
            val chosenDay = geminiAgent.extractDayFilterFromCommand(norm) ?: "hoy"
            startUnifiedDayPlaybackWithFollowUp(contactWaitingDay, chosenDay)
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

                is AgentDecision.OpenWhatsAppChatAndAskDay -> {
                    val displayName = WhatsAppRepository.openContactChatOnly(context, decision.senderName)
                    pendingDayForContact = displayName
                    speaker.speak("Chat de $displayName abierto. ¿De qué día querés escuchar?") {
                        speaker.beepAndVibrate(startListening = true)
                        mainHandler.postDelayed({
                            speechInput.startCommandListening()
                        }, 350L)
                    }
                }

                is AgentDecision.StartUnifiedDayChatPlayback -> {
                    startUnifiedDayPlaybackWithFollowUp(decision.senderName, decision.dayFilter)
                }

                is AgentDecision.PlayWhatsAppVoiceNote -> {
                    WhatsAppRepository.playSingleRequestedVoiceNote(
                        context = context,
                        senderHint = decision.senderName,
                        whichOffsetFromEnd = decision.whichOffset
                    ) { text, onDone ->
                        speaker.speak(text, onFinished = onDone)
                    }
                }

                is AgentDecision.NavigateQueue -> {
                    WhatsAppRepository.stepActiveQueue(
                        context = context,
                        isNext = decision.isNext,
                        isRepeat = decision.isRepeat
                    ) { text, onDone ->
                        speaker.speak(text, onFinished = onDone)
                    }
                }

                is AgentDecision.ProposeWhatsAppTextMessage -> {
                    val target = decision.recipientName.ifBlank { WhatsAppRepository.lastOpenedContactName.orEmpty() }
                    if (target.isBlank() || decision.messageText.isBlank()) {
                        speaker.speak("Decime para quién es y el mensaje.")
                    } else {
                        pendingWhatsAppConfirmation = Pair(target, decision.messageText)
                        speaker.speak(
                            "Mensaje para $target: \"${decision.messageText}\". ¿Lo mando? Decí sí o no."
                        ) {
                            speaker.beepAndVibrate(startListening = true)
                            mainHandler.postDelayed({
                                speechInput.startCommandListening()
                            }, 350L)
                        }
                    }
                }

                is AgentDecision.StartWhatsAppVoiceNoteRecording -> {
                    val target = decision.recipientName.ifBlank { WhatsAppRepository.lastOpenedContactName.orEmpty() }
                    if (target.isBlank()) {
                        speaker.speak("Decime para quién es el audio.")
                    } else {
                        val contact = WhatsAppRepository.findContactByName(context, target)
                        val displayName = contact?.first ?: target
                        speechInput.stopAllListening()
                        speaker.speak("Grabando audio para $displayName. Hablá después del tono.") {
                            WhatsAppRepository.recordAndSendRealVoiceNote(
                                context = context,
                                recipientName = displayName,
                                onRecordingStarted = {
                                    speaker.beepAndVibrate(startListening = true)
                                },
                                onCompleted = { statusMsg ->
                                    speaker.beepAndVibrate(startListening = false)
                                    speaker.speak(statusMsg) {
                                        speechInput.syncWakeWordLoop()
                                    }
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
