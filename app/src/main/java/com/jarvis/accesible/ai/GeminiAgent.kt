package com.jarvis.accesible.ai

import android.content.Context
import android.util.Base64
import com.jarvis.accesible.actions.SystemActionsController
import com.jarvis.accesible.core.AppPrefs
import com.jarvis.accesible.whatsapp.WhatsAppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class AgentDecision {
    data class SpeakOnly(val message: String) : AgentDecision()
    data class CheckTimeOnly(val dummy: Boolean = true) : AgentDecision()
    data class CheckDateOnly(val dummy: Boolean = true) : AgentDecision()
    data class CheckBatteryOnly(val dummy: Boolean = true) : AgentDecision()
    data class CheckConnectionOnly(val dummy: Boolean = true) : AgentDecision()
    data class CheckWeather(val rawQuery: String) : AgentDecision()
    data class CheckUnreadWhatsApp(val dummy: Boolean = true) : AgentDecision()
    data class PlayLatestSingleVoiceNote(val senderName: String? = null) : AgentDecision()
    data class OpenWhatsAppChatAndAskDay(val senderName: String) : AgentDecision()
    data class StartUnifiedDayChatPlayback(
        val senderName: String?,
        val dayFilter: String
    ) : AgentDecision()
    data class NavigateQueue(
        val isNext: Boolean,
        val isRepeat: Boolean = false
    ) : AgentDecision()
    data class ProposeWhatsAppTextMessage(val recipientName: String, val messageText: String) : AgentDecision()
    data class AskWhatsAppTextDictation(val recipientName: String) : AgentDecision()
    data class AskReplyAudioOrTextChoice(val recipientName: String) : AgentDecision()
    data class StartWhatsAppVoiceNoteRecording(val recipientName: String) : AgentDecision()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : AgentDecision()
    data class PlayYouTube(val searchQuery: String) : AgentDecision()
    data class StopPlaybackOrAlarm(val dummy: Boolean = true) : AgentDecision()
    data class MakePhoneCall(val contactName: String) : AgentDecision()
    data class EndOrAnswerCall(val endCall: Boolean) : AgentDecision()
    data class ToggleWakeWordListening(val enable: Boolean) : AgentDecision()
    data class IdentifyBanknote(val dummy: Boolean = true) : AgentDecision()
}

class GeminiAgent(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val candidateModels = listOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-1.5-flash"
    )

    private val systemPrompt = """
        Sos Jarvis, un asistente de voz en español rioplatense (Argentina) para una persona ciega.
        REGLAS ESTRICTAS:
        1. Si te pide una receta, instrucción o explicación paso a paso, explicala clara y ordenada paso por paso.
        2. Para todo lo demás, respondé MUY CORTO y directo en 1 o 2 oraciones.
        3. NUNCA pidas mirar la pantalla ni uses asteriscos o Markdown.
    """.trimIndent()

    suspend fun verifyAndSaveApiKey(rawInput: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        AppPrefs.setGeminiApiKey(context, rawInput)
        val keys = AppPrefs.getGeminiApiKeyList(context)
        if (keys.isEmpty()) {
            return@withContext Pair(true, "🟢 Modo IA Gratuita Automática activo (no requiere clave).")
        }

        val firstKey = keys.first()
        return@withContext try {
            for (model in candidateModels) {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$firstKey"
                val payload = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", "OK")))
                    }))
                }
                val request = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", firstKey)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext Pair(true, "🟢 ¡Clave de Gemini verificada y activa!")
                    }
                }
            }
            Pair(true, "🟢 Modo IA Gratuita Automática activo (respaldo sin clave listo).")
        } catch (e: Exception) {
            Pair(false, "❌ Sin conexión a internet.")
        }
    }

    suspend fun analyzeArgentineBanknote(jpegBytes: ByteArray): String? =
        identifyArgentineBanknoteFromJpeg(jpegBytes)

    suspend fun identifyArgentineBanknoteFromJpeg(jpegBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val keys = AppPrefs.getGeminiApiKeyList(context)
        if (keys.isEmpty()) return@withContext null

        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val prompt = """
            Mirá esta foto tomada por una persona ciega en Argentina y decime qué billete de pesos argentinos es.
            Respondé ÚNICAMENTE con una frase muy corta como: "Es de 1000", "Es de 2000", "Es de 10000", "Es de 20000", "Es de 500", "Es de 200", "Es de 100".
            Si no se llega a ver el número, respondé: "No se ve bien, dalo vuelta".
        """.trimIndent()

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", base64Image)
                        })
                    })
                )
            }))
        }

        for (apiKey in keys) {
            for (model in candidateModels) {
                try {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                    val request = Request.Builder()
                        .url(url)
                        .header("x-goog-api-key", apiKey)
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string().orEmpty()
                            val json = JSONObject(bodyStr)
                            val text = json.optJSONArray("candidates")
                                ?.optJSONObject(0)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts")
                                ?.optJSONObject(0)
                                ?.optString("text", "")
                                ?.trim()
                            if (!text.isNullOrBlank()) {
                                return@withContext text
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return@withContext null
    }

    suspend fun interpretUserCommand(userText: String): AgentDecision = withContext(Dispatchers.IO) {
        val localMatch = tryFlexibleLocalIntent(userText)
        if (localMatch != null) {
            return@withContext localMatch
        }

        val apiKeys = AppPrefs.getGeminiApiKeyList(context)
        if (apiKeys.isNotEmpty()) {
            for (apiKey in apiKeys) {
                try {
                    val decision = callGeminiWithTools(apiKey, userText)
                    if (decision != null && (decision !is AgentDecision.SpeakOnly || decision.message != "CUOTA_AGOTADA")) {
                        return@withContext decision
                    }
                } catch (_: Exception) {
                }
            }
        }

        val aiAnswer = SystemActionsController.askFreeBuiltInAi(userText)
        return@withContext AgentDecision.SpeakOnly(aiAnswer)
    }

    private fun tryFlexibleLocalIntent(rawText: String): AgentDecision? {
        val norm = WhatsAppRepository.normalizeText(rawText)
            .replace(Regex("^(che\\s+|jarvis\\s+|hola jarvis\\s+|asistente\\s+|por favor\\s+)+"), "")
            .trim()

        // 0. ¿Qué podés hacer? / Ayuda
        if (norm.contains("que podes hacer") ||
            norm.contains("que puedes hacer") ||
            norm.contains("que sabes hacer") ||
            norm.contains("enlistame") ||
            norm.contains("lista de comandos") ||
            norm.contains("para que servis") ||
            norm == "ayuda"
        ) {
            return AgentDecision.SpeakOnly(
                "Puedo leer tus mensajes nuevos diciendo tengo mensajes, leer el historial de cualquier día diciendo leeme los mensajes de ayer o del viernes, " +
                    "enviar audios o mensajes escritos por WhatsApp, llamar en altavoz, decirte la hora, la batería, el clima, " +
                    "reconocer billetes con la cámara, poner cualquier canción en YouTube y explicarte recetas paso a paso."
            )
        }

        // 1. Navegación rápida ("siguiente", "anterior" incluyendo homófonos como "interior", "repetir")
        if (norm == "siguiente" ||
            norm == "siguientes" ||
            norm == "el siguiente" ||
            norm == "la siguiente" ||
            norm == "sigiente" ||
            norm == "sigue" ||
            norm == "segui" ||
            norm == "proximo" ||
            norm == "el proximo" ||
            norm == "otro" ||
            norm == "el otro" ||
            norm == "pasalo" ||
            norm == "pasa" ||
            norm == "salta" ||
            norm == "saltar" ||
            norm == "adelante" ||
            norm == "el que sigue" ||
            norm == "pasa al siguiente" ||
            norm == "siguiente mensaje" ||
            norm == "siguiente audio"
        ) {
            return AgentDecision.NavigateQueue(isNext = true, isRepeat = false)
        }

        if (norm == "anterior" ||
            norm == "el anterior" ||
            norm == "la anterior" ||
            norm == "interior" ||
            norm == "el interior" ||
            norm == "al interior" ||
            norm == "enterior" ||
            norm == "atras" ||
            norm == "para atras" ||
            norm == "volver" ||
            norm == "volve" ||
            norm == "previo" ||
            norm == "el de antes" ||
            norm == "mensaje anterior" ||
            norm == "audio anterior"
        ) {
            return AgentDecision.NavigateQueue(isNext = false, isRepeat = false)
        }

        if (norm == "repeti" ||
            norm == "repetir" ||
            norm == "de nuevo" ||
            norm == "otra vez" ||
            norm == "repetilo"
        ) {
            return AgentDecision.NavigateQueue(isNext = false, isRepeat = true)
        }

        // 1B. "Reproducilo" / "Escuchalo" / "Leelo" -> Reproduce ÚNICAMENTE ese último audio recibido (sin ir al inicio del día)
        if (norm == "reproducilo" ||
            norm == "reproducila" ||
            norm == "escuchalo" ||
            norm == "escuchala" ||
            norm == "leelo" ||
            norm == "ponelo" ||
            norm == "reproduci el ultimo audio" ||
            norm == "reproducir el ultimo audio" ||
            norm == "escuchar el ultimo audio"
        ) {
            return AgentDecision.PlayLatestSingleVoiceNote(senderName = null)
        }

        // 2. Reconocimiento de billetes
        if (norm.contains("billete") ||
            norm.contains("cuanta plata") ||
            norm.contains("cuanto es este") ||
            norm.contains("que valor tiene") ||
            norm.contains("de cuanto es")
        ) {
            return AgentDecision.IdentifyBanknote()
        }

        // 3. Parar / Silenciar / Cortar llamada
        if (norm == "para" ||
            norm == "basta" ||
            norm == "silencio" ||
            norm == "callate" ||
            norm.contains("pausa") ||
            norm.contains("detene") ||
            norm.contains("apaga la musica") ||
            norm.contains("apaga la alarma")
        ) {
            return AgentDecision.StopPlaybackOrAlarm()
        }

        if (norm.contains("corta la llamada") || norm.contains("colga") || norm.contains("cortar llamada")) {
            return AgentDecision.EndOrAnswerCall(endCall = true)
        }
        if (norm.contains("atende") || norm.contains("atender llamada") || norm.contains("contesta la llamada")) {
            return AgentDecision.EndOrAnswerCall(endCall = false)
        }

        // 4. Clima / Temperatura / Pronóstico
        val hasWeatherKeyword = norm.contains("clima") ||
            norm.contains("temperatura") ||
            norm.contains("cuantos grados") ||
            norm.contains("hace frio") ||
            norm.contains("hace calor") ||
            norm.contains("va a llover") ||
            norm.contains("llueve") ||
            norm.contains("pronostico") ||
            norm.contains("tiempo")

        val hasMultiDaysPhrase = norm.contains("proximos") && norm.contains("dias") ||
            norm.contains("siguientes") && norm.contains("dias")

        val hasHowWillItBe = (norm.contains("como va a estar") || norm.contains("como estara")) &&
            !norm.contains("mensaje") && !norm.contains("whatsapp") && !norm.contains("audio") && !norm.contains("chat")

        if (hasWeatherKeyword || hasMultiDaysPhrase || hasHowWillItBe) {
            return AgentDecision.CheckWeather(rawText)
        }

        // 5. Hora sola
        val isAlarmRequest = norm.contains("alarma") || norm.contains("despertador") || norm.contains("despertame")
        if (!isAlarmRequest && (
                norm.contains("que hora") ||
                    norm.contains("decime la hora") ||
                    norm.contains("dime la hora") ||
                    norm == "la hora" ||
                    norm == "hora"
                )
        ) {
            return AgentDecision.CheckTimeOnly()
        }

        // 6. Día / Fecha sola
        if (norm.contains("que dia es") ||
            norm.contains("que fecha es") ||
            norm.contains("en que dia estamos") ||
            norm == "fecha" ||
            norm == "que dia"
        ) {
            return AgentDecision.CheckDateOnly()
        }

        // 7. Batería sola
        if (norm.contains("bateria") ||
            norm.contains("cuanta carga") ||
            norm.contains("esta cargando")
        ) {
            return AgentDecision.CheckBatteryOnly()
        }

        // 8. Wi-Fi / Internet
        if (norm.contains("wifi") ||
            norm.contains("wi fi") ||
            norm.contains("conectado a internet") ||
            norm.contains("tengo internet") ||
            norm.contains("hay internet")
        ) {
            return AgentDecision.CheckConnectionOnly()
        }

        // 9. Alarmas
        if (isAlarmRequest) {
            val timeMatch = Regex("(\\d{1,2})(?:\\s*(?::|y|con)\\s*(\\d{1,2}|media|cuarto))?").find(norm)
            if (timeMatch != null) {
                val h = timeMatch.groupValues[1].toIntOrNull() ?: 8
                val mRaw = timeMatch.groupValues.getOrNull(2).orEmpty()
                val m = when (mRaw) {
                    "media" -> 30
                    "cuarto" -> 15
                    "" -> 0
                    else -> mRaw.toIntOrNull() ?: 0
                }
                return AgentDecision.SetAlarm(h, m, "Alarma")
            }
        }

        val daySpec = extractDayFilterFromCommand(norm)
        val hasAudioWord = norm.contains("audio") || norm.contains("audios") || norm.contains("nota de voz") || norm.contains("mensaje de voz")
        val hasTextMessageWord = (norm.contains("mensaje") || norm.contains("mensajes") || norm.contains("texto") || norm.contains("whatsapp")) && !hasAudioWord

        // 10. Si ya dictó el texto en la misma frase ("enviale un mensaje a Nico que diga ya salgo")
        if (!hasAudioWord && (norm.contains("que diga") || norm.contains("diciendo"))) {
            val parsed = parseWhatsAppTextCommand(rawText)
            if (parsed != null) {
                return AgentDecision.ProposeWhatsAppTextMessage(parsed.first, parsed.second)
            }
        }

        val hasSendVerb = norm.contains("envia") ||
            norm.contains("enviar") ||
            norm.contains("manda") ||
            norm.contains("mandar") ||
            norm.contains("graba") ||
            norm.contains("grabar") ||
            norm.contains("escribi") ||
            norm.contains("escribir") ||
            norm.contains("decile")

        val hasReplyVerb = norm.contains("contesta") || norm.contains("responde")

        // 11A. "Enviale / mandale / grabale un AUDIO a X" (o "contestale con un audio a X") -> Graba audio nativo directo
        if ((hasSendVerb || hasReplyVerb) && hasAudioWord && !norm.contains("reproduci") && !norm.contains("escucha") && !norm.contains("lee")) {
            val contact = extractContactFromCommand(rawText, norm) ?: WhatsAppRepository.lastOpenedContactName.orEmpty()
            return AgentDecision.StartWhatsAppVoiceNoteRecording(contact)
        }

        // 11B. "Enviale / mandale un MENSAJE a X" o "Escribile a X" (sin "audio") -> Pregunta "¿Qué le querés decir?" para enviar texto escrito
        if ((hasSendVerb && (hasTextMessageWord || norm.contains("escribi") || norm.contains("decile"))) ||
            (hasReplyVerb && (norm.contains("mensaje") || norm.contains("texto")))
        ) {
            if (!norm.contains("reproduci") && !norm.contains("escucha") && !norm.contains("lee")) {
                val contact = extractContactFromCommand(rawText, norm) ?: WhatsAppRepository.lastOpenedContactName.orEmpty()
                return AgentDecision.AskWhatsAppTextDictation(contact)
            }
        }

        // 11C. "Contestale a X" / "Respondele a X" / "Contestale" (sin aclarar si es audio o mensaje) -> Pregunta "¿Audio o mensaje de texto?"
        if (hasReplyVerb && !norm.contains("reproduci") && !norm.contains("escucha") && !norm.contains("lee")) {
            val contact = extractContactFromCommand(rawText, norm) ?: WhatsAppRepository.lastOpenedContactName.orEmpty()
            return AgentDecision.AskReplyAudioOrTextChoice(contact)
        }

        // 12. "Abrí el chat de X persona" (sin especificar día -> lo abre y pregunta de qué día quiere escuchar)
        val isOpenChatCommand = (norm.contains("abri") || norm.contains("abrir") || norm.startsWith("chat de ") || norm.startsWith("el chat de ")) &&
            (norm.contains("chat") || norm.contains("whatsapp") || norm.contains("conversacion"))

        if (isOpenChatCommand) {
            val contact = extractContactFromCommand(rawText, norm)
            if (!contact.isNullOrBlank()) {
                return if (daySpec != null) {
                    AgentDecision.StartUnifiedDayChatPlayback(senderName = contact, dayFilter = daySpec)
                } else {
                    AgentDecision.OpenWhatsAppChatAndAskDay(senderName = contact)
                }
            }
        }

        // 13. Si el usuario solo dijo el día ("los de hoy", "de ayer", "del viernes") teniendo ya un chat abierto/reciente
        if (daySpec != null && !WhatsAppRepository.lastOpenedContactName.isNullOrBlank() &&
            (norm.startsWith("de ") || norm.startsWith("del ") || norm.startsWith("los de ") || norm.startsWith("el ") || norm == daySpec)
        ) {
            return AgentDecision.StartUnifiedDayChatPlayback(
                senderName = WhatsAppRepository.lastOpenedContactName,
                dayFilter = daySpec
            )
        }

        // 14. WhatsApp: separar "Mensajes nuevos en vivo" ("¿tengo mensajes?", "¿hay algún mensaje?", "reproducí el último audio de X")
        // de "Leer historial de un día" ("leeme los mensajes de hoy/ayer/viernes de X")
        val mentionsWhatsAppContent = hasAudioWord ||
            norm.contains("mensaje") ||
            norm.contains("mensajes") ||
            norm.contains("chat") ||
            norm.contains("whatsapp") ||
            norm.contains("escribio") ||
            norm.contains("mando")

        if (mentionsWhatsAppContent) {
            // A) ¿Preguntó "¿tengo mensajes?" / "¿hay algún mensaje?"? -> Lee o reproduce directamente los mensajes/audios nuevos
            val isGeneralUnreadCheck = (
                norm.contains("tengo mensaje") ||
                    norm.contains("hay mensaje") ||
                    norm.contains("hay algun mensaje") ||
                    norm.contains("algun mensaje") ||
                    norm.contains("quien me escribio") ||
                    norm.contains("mensajes nuevos")
                ) && daySpec == null && !norm.contains("lee") && !norm.contains("reproduci")

            if (isGeneralUnreadCheck) {
                return AgentDecision.CheckUnreadWhatsApp()
            }

            val contact = extractContactFromCommand(rawText, norm)

            // B) ¿Pidió específicamente "el último audio de X"? -> Reproduce solo ese último audio
            if (hasAudioWord && norm.contains("ultimo")) {
                return AgentDecision.PlayLatestSingleVoiceNote(senderName = contact)
            }

            // C) Si pidió leer/reproducir el chat/mensajes/audios de un día ("hoy", "ayer", "viernes") o de una persona
            if (daySpec != null || !contact.isNullOrBlank() || norm.contains("lee")) {
                return AgentDecision.StartUnifiedDayChatPlayback(
                    senderName = contact,
                    dayFilter = daySpec ?: "hoy"
                )
            }

            if (hasAudioWord) {
                return AgentDecision.PlayLatestSingleVoiceNote(senderName = null)
            }

            return AgentDecision.CheckUnreadWhatsApp()
        }

        // 15. Llamadas Telefónicas
        if (norm.contains("llama ") ||
            norm.startsWith("llama") ||
            norm.contains("llamar ") ||
            norm.contains("comunica") ||
            norm.contains("hablar por telefono con ")
        ) {
            val contact = extractContactFromCommand(rawText, norm)
            if (!contact.isNullOrBlank()) {
                return AgentDecision.MakePhoneCall(contact)
            }
        }

        // 16. YouTube / Música (CUALQUIER pedido con "reproducí", "reproducir", "poné", "poner", "escuchar" sin aclarar "en YouTube")
        val isRecipeOrHowTo = norm.contains("paso a paso") ||
            norm.contains("receta") ||
            norm.contains("como hacer") ||
            norm.contains("como hago") ||
            norm.contains("como se hace") ||
            norm.contains("como preparar")

        val hasMusicOrPlayTrigger = norm.contains("youtube") ||
            norm.contains("musica") ||
            norm.contains("cancion") ||
            norm.contains("tema ") ||
            norm.startsWith("pone ") ||
            norm.startsWith("poner ") ||
            norm.startsWith("poneme ") ||
            norm.startsWith("reproduci ") ||
            norm.startsWith("reproducir ") ||
            norm.startsWith("reproduce ") ||
            norm.startsWith("escucha ") ||
            norm.startsWith("escuchar ") ||
            norm.startsWith("quiero escuchar ")

        if (!isRecipeOrHowTo && hasMusicOrPlayTrigger) {
            val query = cleanYouTubeQuery(rawText)
            if (query.isNotEmpty()) {
                return AgentDecision.PlayYouTube(query)
            }
        }

        return null
    }

    fun extractDayFilterFromCommand(normText: String): String? {
        return when {
            normText.contains("antes de ayer") || normText.contains("anteayer") -> "antes de ayer"
            normText.contains("ayer") -> "ayer"
            normText.contains("hoy") || normText.contains("del dia") -> "hoy"
            normText.contains("lunes") -> "lunes"
            normText.contains("martes") -> "martes"
            normText.contains("miercoles") -> "miercoles"
            normText.contains("jueves") -> "jueves"
            normText.contains("viernes") -> "viernes"
            normText.contains("sabado") -> "sabado"
            normText.contains("domingo") -> "domingo"
            else -> null
        }
    }

    private fun extractContactFromCommand(rawText: String, normText: String): String? {
        val withoutDays = normText
            .replace("del dia de hoy", " ")
            .replace("del dia de ayer", " ")
            .replace("de ayer y hoy", " ")
            .replace("de hoy y ayer", " ")
            .replace("de antes de ayer", " ")
            .replace("de anteayer", " ")
            .replace("de hoy", " ")
            .replace("de ayer", " ")
            .replace(Regex("\\b(?:del|de)\\s+(?:lunes|martes|miercoles|jueves|viernes|sabado|domingo)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val fromAgenda = WhatsAppRepository.findMentionedContactInSentence(context, rawText)
        if (!fromAgenda.isNullOrBlank()) return fromAgenda

        val patterns = listOf(
            Regex("(?:enviale|enviarle|envia|enviar|mandale|mandar|manda|grabale|grabar|graba)\\s+(?:un\\s+)?(?:audio|mensaje de voz|nota de voz|mensaje|texto|whatsapp)\\s+(?:a|para)\\s+([a-zA-Z0-9áéíóúÁÉÍÓÚñÑ ]+)"),
            Regex("(?:contestale a|contestar a|respondele a|responder a|enviale a|enviarle a|mandale a|grabale a|grabar audio para|audio a|audio para|mensaje a|mensaje para|whatsapp a|escribile a|decile a)\\s+([a-zA-Z0-9áéíóúÁÉÍÓÚñÑ ]+)"),
            Regex("(?:chat de|chat con|conversacion con|audios? de|mensajes? de|escribio|mando)\\s+([a-zA-Z0-9áéíóúÁÉÍÓÚñÑ ]+)"),
            Regex("(?:llama a|llamar a|comunicame con|hablar con)\\s+([a-zA-Z0-9áéíóúÁÉÍÓÚñÑ ]+)")
        )
        for (regex in patterns) {
            val match = regex.find(withoutDays)
            if (match != null) {
                val candidate = match.groupValues[1]
                    .replace(Regex("^\\b(ayer|hoy|lunes|martes|miercoles|jueves|viernes|sabado|domingo|dia|el|la|los|las|y|de|persona)\\b\\s*"), "")
                    .replace(Regex("\\b(ayer|hoy|lunes|martes|miercoles|jueves|viernes|sabado|domingo|dia|que|por|en|un|una|ultimo|penultimo|anterior|reproduci|escuchar|audio|audios|whatsapp)\\b.*"), "")
                    .trim()
                if (candidate.length >= 2) return candidate
            }
        }

        return null
    }

    private fun parseWhatsAppTextCommand(rawText: String): Pair<String, String>? {
        val regex = Regex(
            "(?:mandale|manda|enviale|enviarle|enviar|escribile|decile|contestale|respondele)\\s+(?:un\\s+)?(?:whatsapp|mensaje|texto)?\\s*(?:a|para)?\\s*([a-zA-Z0-9áéíóúÁÉÍÓÚñÑ ]+?)\\s+(?:que diga|diciendo)\\s+(.+)",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(rawText.trim())
        if (match != null) {
            val recipient = match.groupValues[1].trim()
            val message = match.groupValues[2].trim()
            if (recipient.isNotEmpty() && message.isNotEmpty()) {
                return Pair(recipient, message)
            }
        }
        return null
    }

    private fun cleanYouTubeQuery(rawText: String): String {
        return rawText
            .replace(Regex("(?i)^(che\\s+|jarvis\\s+|hola jarvis\\s+|asistente\\s+|por favor\\s+)*"), "")
            .replace(Regex("(?i)^(poneme|pone|poné|poner|reproduci|reproducí|reproducir|reproduce|quiero escuchar|escucha|escuchá|escuchar|busca|buscame)\\s+"), "")
            .replace(Regex("(?i)^(algun tema de|un tema de|una cancion de|una canción de|la cancion|la canción|musica de|música de|videos de|el video de)\\s+"), "")
            .replace(Regex("(?i)\\s+(en youtube|por youtube|de youtube)$"), "")
            .trim()
    }

    private fun callGeminiWithTools(apiKey: String, userText: String): AgentDecision? {
        val payload = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userText)))
            }))
            put("tools", JSONArray().put(JSONObject().apply {
                put("google_search", JSONObject())
            }))
        }

        for (model in candidateModels) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.code == 429) {
                    return AgentDecision.SpeakOnly("CUOTA_AGOTADA")
                }
                if (!response.isSuccessful) {
                    return@use
                }

                val json = JSONObject(bodyStr)
                val parts = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?: return@use

                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val text = part.optString("text", "").trim()
                    if (text.isNotEmpty()) {
                        return AgentDecision.SpeakOnly(text)
                    }
                }
            }
        }
        return null
    }
}
