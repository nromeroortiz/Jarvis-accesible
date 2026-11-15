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
import com.jarvis.accesible.services.JarvisAccessibilityService
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
    private var pendingTextDictationForContact: String? = null
    private var pendingAudioOrTextChoiceForContact: String? = null
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
            when {
                pendingWhatsAppConfirmation != null -> {
                    pendingWhatsAppConfirmation = null
                    speaker.speak("Cancelado.")
                }
                pendingTextDictationForContact != null -> {
                    pendingTextDictationForContact = null
                    speaker.speak("Cancelado.")
                }
                pendingAudioOrTextChoiceForContact != null -> {
                    pendingAudioOrTextChoiceForContact = null
                    speaker.speak("Cancelado.")
                }
                pendingContinueToTodayForContact != null -> {
                    pendingContinueToTodayForContact = null
                }
                pendingDayForContact != null -> {
                    val contact = pendingDayForContact
                    pendingDayForContact = null
                    if (!contact.isNullOrBlank()) {
                        startUnifiedDayPlaybackWithFollowUp(contact, "hoy")
                    }
                }
                else -> {
                    speaker.speak("No te escuché.")
                }
            }
        },
        onListeningStateChanged = { listening ->
            isListeningUiState = listening
        },
        isBusySpeakingOrPlaying = {
            speaker.isSpeakingNow ||
                WhatsAppRepository.isCurrentlyPlayingOrRecordingAudio() ||
                (JarvisAccessibilityService.instance?.isMediaAppInForeground() == true)
        }
    )

    fun triggerHandsFreeListening(greetBeforeListening: Boolean = false) {
        // Pausamos cualquier lectura o audio en pantalla de WhatsApp para que el micrófono escuche limpio ("Siguiente", "Anterior", etc.)
        speaker.stopSpeaking()
        WhatsAppRepository.stopAudioPlayback()
        JarvisAccessibilityService.instance?.pauseAnyPlayingVoiceNoteOnScreen()

        if (greetBeforeListening) {
            speaker.speak("Te escucho") {
                openMicAfterBeep()
            }
        } else {
            openMicAfterBeep()
        }
    }

    private fun openMicAfterBeep() {
        speaker.beepAndVibrate(startListening = true)
        mainHandler.postDelayed({
            speechInput.startCommandListening()
        }, 380L)
    }

    private fun startNativeAudioRecordingForContact(targetRaw: String) {
        val displayName = WhatsAppRepository.openContactChatOnly(context, targetRaw)
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

    private fun askTextDictationForContact(targetRaw: String) {
        val contact = WhatsAppRepository.findContactByName(context, targetRaw)
        val displayName = contact?.first ?: targetRaw
        pendingTextDictationForContact = displayName
        speaker.speak("¿Qué mensaje le querés escribir a $displayName?") {
            openMicAfterBeep()
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
                    openMicAfterBeep()
                }
            }
        ) { text, onDone ->
            speaker.speak(text, onFinished = onDone)
        }
    }

    private fun handleUserSpokenText(userText: String) {
        isListeningUiState = false
        speaker.beepAndVibrate(startListening = false)

        // 1. Confirmación pendiente de envío de mensaje escrito ("¿Lo mando? Decí sí o no")
        val pendingConfirm = pendingWhatsAppConfirmation
        if (pendingConfirm != null) {
            pendingWhatsAppConfirmation = null
            val norm = WhatsAppRepository.normalizeText(userText)
            if (norm.contains("si") || norm.contains("mandalo") || norm.contains("enviar") || norm.contains("dale") || norm.contains("bueno") || norm.contains("correcto")) {
                val result = WhatsAppRepository.sendTextMessage(context, pendingConfirm.first, pendingConfirm.second)
                speaker.speak(result)
            } else {
                speaker.speak("Cancelado.")
            }
            return
        }

        // 2. Si le habíamos preguntado "¿Qué mensaje le querés escribir a X?"
        val dictationContact = pendingTextDictationForContact
        if (dictationContact != null) {
            pendingTextDictationForContact = null
            val cleanDictation = userText.trim()
            if (cleanDictation.isEmpty() || WhatsAppRepository.normalizeText(cleanDictation) == "cancelar") {
                speaker.speak("Cancelado.")
            } else {
                pendingWhatsAppConfirmation = Pair(dictationContact, cleanDictation)
                speaker.speak("Mensaje para $dictationContact: \"$cleanDictation\". ¿Lo mando? Decí sí o no.") {
                    openMicAfterBeep()
                }
            }
            return
        }

        // 3. Si dijo "Contestale a X" y le preguntamos "¿Audio o mensaje de texto?"
        val choiceContact = pendingAudioOrTextChoiceForContact
        if (choiceContact != null) {
            pendingAudioOrTextChoiceForContact = null
            val norm = WhatsAppRepository.normalizeText(userText)
            when {
                norm.contains("audio") || norm.contains("voz") -> {
                    startNativeAudioRecordingForContact(choiceContact)
                }
                norm.contains("mensaje") || norm.contains("texto") || norm.contains("escrito") || norm.contains("escribir") -> {
                    askTextDictationForContact(choiceContact)
                }
                else -> {
                    // Si directamente empezó a dictar lo que quería decirle, lo tomamos como mensaje escrito
                    pendingWhatsAppConfirmation = Pair(choiceContact, userText.trim())
                    speaker.speak("Mensaje para $choiceContact: \"${userText.trim()}\". ¿Lo mando? Decí sí o no.") {
                        openMicAfterBeep()
                    }
                }
            }
            return
        }

        // 4. Confirmación pendiente al terminar un día anterior: "¿Querés seguir con los de hoy?"
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
        }

        // 5. Si había dicho "Abrí el chat de X" y Jarvis le preguntó "¿De qué día querés escuchar?"
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
                    WhatsAppRepository.checkAndPlayUnreadImmediately(context) { text, onDone ->
                        speaker.speak(text, onFinished = onDone)
                    }
                }

                is AgentDecision.PlayLatestSingleVoiceNote -> {
                    WhatsAppRepository.playLatestSingleAudioOnly(
                        context = context,
                        senderHint = decision.senderName
                    ) { text, onDone ->
                        speaker.speak(text, onFinished = onDone)
                    }
                }

                is AgentDecision.OpenWhatsAppChatAndAskDay -> {
                    val displayName = WhatsAppRepository.openContactChatOnly(context, decision.senderName)
                    pendingDayForContact = displayName
                    speaker.speak("Chat de $displayName abierto. ¿De qué día querés escuchar?") {
                        openMicAfterBeep()
                    }
                }

                is AgentDecision.StartUnifiedDayChatPlayback -> {
                    startUnifiedDayPlaybackWithFollowUp(decision.senderName, decision.dayFilter)
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
                        val contact = WhatsAppRepository.findContactByName(context, target)
                        val displayName = contact?.first ?: target
                        pendingWhatsAppConfirmation = Pair(displayName, decision.messageText)
                        speaker.speak(
                            "Mensaje para $displayName: \"${decision.messageText}\". ¿Lo mando? Decí sí o no."
                        ) {
                            openMicAfterBeep()
                        }
                    }
                }

                is AgentDecision.AskWhatsAppTextDictation -> {
                    val target = decision.recipientName.ifBlank { WhatsAppRepository.lastOpenedContactName.orEmpty() }
                    if (target.isBlank()) {
                        speaker.speak("Decime para quién es el mensaje.")
                    } else {
                        askTextDictationForContact(target)
                    }
                }

                is AgentDecision.AskReplyAudioOrTextChoice -> {
                    val target = decision.recipientName.ifBlank { WhatsAppRepository.lastOpenedContactName.orEmpty() }
                    if (target.isBlank()) {
                        speaker.speak("Decime a quién le querés contestar.")
                    } else {
                        val contact = WhatsAppRepository.findContactByName(context, target)
                        val displayName = contact?.first ?: target
                        pendingAudioOrTextChoiceForContact = displayName
                        speaker.speak("¿Le querés mandar audio o mensaje de texto a $displayName?") {
                            openMicAfterBeep()
                        }
                    }
                }

                is AgentDecision.StartWhatsAppVoiceNoteRecording -> {
                    val target = decision.recipientName.ifBlank { WhatsAppRepository.lastOpenedContactName.orEmpty() }
                    if (target.isBlank()) {
                        speaker.speak("Decime para quién es el audio.")
                    } else {
                        startNativeAudioRecordingForContact(target)
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

                is AgentDecision.ControlFlashlight -> {
                    val msg = SystemActionsController.setOrCheckFlashlight(context, decision.turnOn)
                    speaker.speak(msg)
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
