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
    data class CheckWeather(val includeForecast: Boolean) : AgentDecision()
    data class CheckUnreadWhatsApp(val dummy: Boolean = true) : AgentDecision()
    data class ReadWhatsAppFromSender(val senderName: String) : AgentDecision()
    data class PlayWhatsAppVoiceNote(
        val senderName: String? = null,
        val whichOffset: Int = 1,
        val stepBackward: Boolean = false,
        val repeatCurrent: Boolean = false,
        val forceOpenChat: Boolean = false
    ) : AgentDecision()
    data class ProposeWhatsAppTextMessage(val recipientName: String, val messageText: String) : AgentDecision()
    data class StartWhatsAppVoiceNoteRecording(val recipientName: String) : AgentDecision()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : AgentDecision()
    data class PlayYouTube(val searchQuery: String) : AgentDecision()
    data class StopPlaybackOrAlarm(val dummy: Boolean = true) : AgentDecision()
    data class MakePhoneCall(val contactName: String) : AgentDecision()
    data class EndOrAnswerCall(val endCall: Boolean) : AgentDecision()
    data class ToggleWakeWordListening(val enable: Boolean) : AgentDecision()
    data class IdentifyBanknote(val dummy: Boolean = true) : AgentDecision()
}

/**
 * Motor de comprensión de voz con doble capa:
 * 1. Intérprete Inteligente Flexible en Español (en el propio teléfono, 0 tokens, sin requerir frases exactas):
 *    Comprende variaciones naturales para WhatsApp (audios por contacto, último, anterior/penúltimo, abrir chat),
 *    YouTube, Llamadas, Clima por GPS, Hora sola, Batería sola, Wi-Fi, Alarmas y Billetes.
 * 2. Google Gemini (2.5 / 2.0 / 1.5 Flash) + Buscador Web Libre de respaldo (Wikipedia + Google Noticias Argentina)
 *    para responder cualquier pregunta general con frases cortas y directas.
 */
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
        1. Tus respuestas deben ser MUY CORTAS, directas y al grano (máximo 1 o 2 oraciones breves).
        2. NUNCA pidas mirar la pantalla ni tocar links. No uses asteriscos ni Markdown.
        3. Si el usuario pide usar una función del teléfono, invocá siempre la herramienta correspondiente.
    """.trimIndent()

    suspend fun verifyAndSaveApiKey(rawInput: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        AppPrefs.setGeminiApiKey(context, rawInput)
        val keys = AppPrefs.getGeminiApiKeyList(context)
        if (keys.isEmpty()) {
            return@withContext Pair(false, "El campo está vacío.")
        }

        val firstKey = keys.first()
        if (firstKey.length < 20) {
            return@withContext Pair(
                false,
                "La clave parece muy corta (${firstKey.length} caracteres)."
            )
        }

        return@withContext try {
            var lastErrorDetail = ""
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
                        return@withContext Pair(
                            true,
                            "🟢 ¡Clave guardada y verificada con éxito!"
                        )
                    }
                    val errBody = response.body?.string().orEmpty()
                    lastErrorDetail = "HTTP ${response.code}"
                    if (errBody.contains("API_KEY_INVALID", ignoreCase = true)) {
                        return@withContext Pair(
                            false,
                            "⚠️ Esa clave no es de Google AI Studio (las de AI Studio empiezan con AIza...). Igual todas las funciones de WhatsApp, Audios, Clima, YouTube, Llamadas y Billetes ya funcionan sin clave."
                        )
                    }
                }
            }
            Pair(
                false,
                "⚠️ Clave guardada ($lastErrorDetail). Nota: WhatsApp, Audios, Clima, YouTube, Llamadas y Billetes ya funcionan directo sin depender de la clave."
            )
        } catch (e: Exception) {
            Pair(false, "⚠️ Clave guardada (sin internet en este momento).")
        }
    }

    suspend fun interpretUserCommand(userText: String): AgentDecision = withContext(Dispatchers.IO) {
        // 1. Intérprete inteligente flexible en español (entiende intenciones y sinónimos sin frases exactas y sin gastar tokens)
        val flexibleLocal = tryFlexibleLocalIntent(userText)
        if (flexibleLocal != null) return@withContext flexibleLocal

        // 2. Si hay clave de Gemini configurada, consultamos a Gemini
        val apiKeys = AppPrefs.getGeminiApiKeyList(context)
        if (apiKeys.isNotEmpty()) {
            try {
                for (key in apiKeys) {
                    val result = callGeminiWithTools(key, userText)
                    if (result != null) {
                        if (result !is AgentDecision.SpeakOnly || !result.message.startsWith("CUOTA_AGOTADA")) {
                            return@withContext result
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Respaldo gratuito automático sin API Key para preguntas generales ("¿Cómo salió Boca?", "¿Qué es...", etc.)
        val freeWebAnswer = SystemActionsController.searchFreeWebWithoutApiKey(userText)
        if (!freeWebAnswer.startsWith("No encontré resultados")) {
            return@withContext AgentDecision.SpeakOnly(freeWebAnswer)
        }

        return@withContext AgentDecision.SpeakOnly("No te entendí, ¿repetís?")
    }

    /**
     * Analizador flexible por intención en español rioplatense:
     * Entiende qué quiere hacer el tío aunque lo diga con distintas palabras, orden o muletillas.
     */
    private fun tryFlexibleLocalIntent(rawText: String): AgentDecision? {
        val norm = WhatsAppRepository.normalizeText(rawText)
        if (norm.isBlank()) return null

        // 1. Callar / Detener / Pausar música o alarma
        if (norm == "para" ||
            norm == "basta" ||
            norm == "silencio" ||
            norm == "callate" ||
            norm == "pausa" ||
            norm == "cortala" ||
            norm.contains("apaga la musica") ||
            norm.contains("detene la musica") ||
            norm.contains("para la musica") ||
            norm.contains("saca la musica") ||
            norm.contains("pausa youtube") ||
            norm.contains("apaga youtube") ||
            norm.contains("apaga la alarma")
        ) {
            return AgentDecision.StopPlaybackOrAlarm()
        }

        // 2. Reconocer Billetes por Cámara
        if (norm.contains("billete") ||
            norm.contains("billetes") ||
            norm.contains("cuanta plata") ||
            norm.contains("que plata") ||
            norm.contains("cuantos pesos") ||
            norm.contains("de cuanto es este") ||
            norm.contains("de cuanto es esto") ||
            norm.contains("cuanto vale este")
        ) {
            return AgentDecision.IdentifyBanknote()
        }

        // 3. Clima / Temperatura / Pronóstico del día (por GPS)
        if (norm.contains("clima") ||
            norm.contains("grados") ||
            norm.contains("temperatura") ||
            norm.contains("pronostico") ||
            norm.contains("va a llover") ||
            norm.contains("llueve") ||
            norm.contains("lluvia") ||
            norm.contains("hace frio") ||
            norm.contains("hace calor") ||
            norm.contains("como esta el tiempo") ||
            norm.contains("como va a estar el dia") ||
            norm.contains("como esta el dia") ||
            norm.contains("como esta afuera")
        ) {
            val wantsFullForecast = norm.contains("clima") ||
                norm.contains("pronostico") ||
                norm.contains("hoy") ||
                norm.contains("dia") ||
                norm.contains("llov") ||
                norm.contains("lluv") ||
                norm.contains("tiempo") ||
                norm.contains("maxima")
            return AgentDecision.CheckWeather(includeForecast = wantsFullForecast)
        }

        // 4. Hora sola (sin batería ni wifi)
        val isAlarmRequest = norm.contains("alarma") || norm.contains("desperta")
        if (!isAlarmRequest && (
                norm.contains("que hora") ||
                    norm.contains("decime la hora") ||
                    norm.contains("tenes hora") ||
                    norm.contains("hora actual") ||
                    norm == "la hora" ||
                    norm == "hora"
                )
        ) {
            return AgentDecision.CheckTimeOnly()
        }

        // 5. Día / Fecha sola
        if (norm.contains("que dia es") ||
            norm.contains("que fecha es") ||
            norm.contains("en que dia estamos") ||
            norm == "fecha" ||
            norm == "que dia"
        ) {
            return AgentDecision.CheckDateOnly()
        }

        // 6. Batería sola
        if (norm.contains("bateria") ||
            norm.contains("cuanta carga") ||
            norm.contains("esta cargando")
        ) {
            return AgentDecision.CheckBatteryOnly()
        }

        // 7. Wi-Fi / Internet (solo si pregunta por eso)
        if (norm.contains("wifi") ||
            norm.contains("wi fi") ||
            norm.contains("conectado a internet") ||
            norm.contains("tengo internet") ||
            norm.contains("hay internet")
        ) {
            return AgentDecision.CheckConnectionOnly()
        }

        // 8. Alarmas ("poné una alarma a las 8 y media", "despertame a las 7")
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

        // 9. Grabar y enviar un audio de voz por WhatsApp ("mandale un audio a Nico", "grabar audio para María")
        val hasSendOrRecordVerb = norm.contains("manda") ||
            norm.contains("envia") ||
            norm.contains("graba") ||
            norm.contains("responde")
        val hasAudioWord = norm.contains("audio") || norm.contains("nota de voz") || norm.contains("mensaje de voz")

        if (hasSendOrRecordVerb && hasAudioWord && !norm.contains("reproduci") && !norm.contains("escucha")) {
            val contact = extractContactFromCommand(rawText, norm)
            if (!contact.isNullOrBlank()) {
                return AgentDecision.StartWhatsAppVoiceNoteRecording(contact)
            }
        }

        // 10. Reproducir / Navegar Audios de WhatsApp (último, anterior, penúltimo, repetir, o abrir chat de X y reproducir audio)
        val hasPlayVerb = norm.contains("reproduci") ||
            norm.contains("escucha") ||
            norm.contains("pone") ||
            norm.contains("pasame") ||
            norm.contains("abri") ||
            norm.contains("lee") ||
            norm.contains("repeti")

        val isQuickAudioNav = norm == "el anterior" ||
            norm == "audio anterior" ||
            norm == "el otro" ||
            norm == "el otro audio" ||
            norm == "el penultimo" ||
            norm == "penultimo audio" ||
            norm == "el antepenultimo" ||
            norm == "repetilo" ||
            norm == "de nuevo" ||
            norm == "otra vez"

        if (isQuickAudioNav || (hasAudioWord && (hasPlayVerb || norm.contains("ultimo") || norm.contains("anterior") || norm.contains("penultimo")))) {
            val forceChat = norm.contains("abri") || norm.contains("chat")
            val repeat = norm.contains("repeti") || norm.contains("de nuevo") || norm.contains("otra vez") || norm.contains("mismo")
            val whichOffset = when {
                norm.contains("antepenultimo") || norm.contains("tercer") || norm.contains("ultimos 3") || norm.contains("ultimos tres") -> 3
                norm.contains("penultimo") || norm.contains("anteultimo") || norm.contains("segundo") || norm.contains("ultimos 2") || norm.contains("ultimos dos") || norm.contains("anterior") || norm.contains("otro") -> 2
                else -> 1
            }
            val sender = extractContactFromCommand(rawText, norm)
            val stepBack = (norm.contains("anterior") || norm.contains("otro")) && sender.isNullOrBlank()

            return AgentDecision.PlayWhatsAppVoiceNote(
                senderName = sender,
                whichOffset = whichOffset,
                stepBackward = stepBack,
                repeatCurrent = repeat,
                forceOpenChat = forceChat || !sender.isNullOrBlank()
            )
        }

        // 11. Enviar mensaje de texto por WhatsApp ("mandale un whatsapp a Nico que diga hola", "escribile a Juan ya voy")
        val hasTextSendTrigger = (norm.contains("manda") || norm.contains("envia") || norm.contains("escribi") || norm.contains("decile")) &&
            (norm.contains("whatsapp") || norm.contains("mensaje") || norm.contains("que diga"))

        if (hasTextSendTrigger && !hasAudioWord) {
            val parsed = parseWhatsAppTextCommand(rawText, norm)
            if (parsed != null) {
                return AgentDecision.ProposeWhatsAppTextMessage(parsed.first, parsed.second)
            }
        }

        // 12. Consultar mensajes de WhatsApp o abrir el chat de alguien ("tengo mensajes?", "qué me mandó Nico?", "abrí el chat de Nico")
        if (norm.contains("mensaje") ||
            norm.contains("whatsapp") ||
            norm.contains("escribio") ||
            norm.contains("mandaron") ||
            norm.contains("mando algo") ||
            norm.contains("llego algo") ||
            norm.contains("tengo audio") ||
            norm.contains("chat de ") ||
            norm.contains("chat con ")
        ) {
            val contact = extractContactFromCommand(rawText, norm)
            return if (!contact.isNullOrBlank()) {
                AgentDecision.ReadWhatsAppFromSender(contact)
            } else {
                AgentDecision.CheckUnreadWhatsApp()
            }
        }

        // 13. Llamadas Telefónicas ("llamá a Nico", "comunicame con María", "llamar por teléfono a Juan")
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

        // 14. YouTube / Música ("poné Los Chalchaleros en YouTube", "quiero escuchar Sandro", "poneme música de...")
        if (norm.contains("youtube") ||
            norm.contains("musica") ||
            norm.contains("cancion") ||
            norm.contains("tema ") ||
            norm.startsWith("pone ") ||
            norm.startsWith("poneme ") ||
            norm.startsWith("reproduci ") ||
            norm.startsWith("quiero escuchar ")
        ) {
            val query = cleanYouTubeQuery(rawText)
            if (query.isNotEmpty()) {
                return AgentDecision.PlayYouTube(query)
            }
        }

        return null
    }

    private fun extractContactFromCommand(rawText: String, normText: String): String? {
        // 1. Primero verificamos si en la frase aparece el nombre exacto de algún contacto agendado
        val fromAgenda = WhatsAppRepository.findMentionedContactInSentence(context, rawText)
        if (!fromAgenda.isNullOrBlank()) return fromAgenda

        // 2. Si no, extraemos el nombre que sigue a "chat de", "audio de", "mensajes de", "llamá a", "mandale a", etc.
        val patterns = listOf(
            Regex("(?:chat de|chat con|audios? de|mensajes? de|escribio|mando)\\s+([a-zA-Z0-9áéíóúÁÉÍÓÚñÑ ]+)"),
            Regex("(?:llama a|llamar a|comunicame con|hablar con|audio a|audio para|whatsapp a|mensaje a|escribile a|decile a)\\s+([a-zA-Z0-9áéíóúÁÉÍÓÚñÑ ]+)")
        )
        for (regex in patterns) {
            val match = regex.find(normText)
            if (match != null) {
                val candidate = match.groupValues[1]
                    .replace(Regex("\\b(y|que|por|en|el|la|los|las|un|una|ultimo|penultimo|anterior|antepenultimo|reproduci|escuchar|audio|audios|whatsapp)\\b.*"), "")
                    .trim()
                if (candidate.length >= 2) return candidate
            }
        }
        return null
    }

    private fun parseWhatsAppTextCommand(rawText: String, normText: String): Pair<String, String>? {
        val regex = Regex(
            "(?:mandale|manda|enviale|enviar|escribile|decile)\\s+(?:un\\s+)?(?:whatsapp|mensaje|texto)?\\s*(?:a|para)?\\s*([a-zA-Z0-9áéíóúÁÉÍÓÚñÑ]+)\\s+(?:que diga|diciendo|que|:)\\s+(.+)",
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
            .replace(Regex("(?i)^(che\\s+|jarvis\\s+|asistente\\s+|por favor\\s+)*"), "")
            .replace(Regex("(?i)^(poneme|pone|poné|reproduci|reproducí|quiero escuchar|escuchar|busca|buscame)\\s+"), "")
            .replace(Regex("(?i)^(algun tema de|un tema de|una cancion de|una canción de|musica de|música de|videos de|el video de)\\s+"), "")
            .replace(Regex("(?i)\\s+(en youtube|por youtube|de youtube)$"), "")
            .trim()
    }

    private fun callGeminiSearchGrounded(apiKey: String, query: String): String {
        for (model in candidateModels) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

            val payload = JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put(
                        "text",
                        "Respondé en máximo 2 oraciones breves y directas para ser leídas en voz alta: $query"
                    )))
                }))
                put("tools", JSONArray().put(JSONObject().apply {
                    put("google_search", JSONObject())
                }))
            }

            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: return@use
                    if (!response.isSuccessful) return@use
                    val json = JSONObject(bodyStr)
                    val parts = json.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")

                    if (parts != null) {
                        val sb = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val text = parts.optJSONObject(i)?.optString("text", "") ?: ""
                            if (text.isNotBlank()) sb.append(text).append(" ")
                        }
                        val result = sb.toString().trim()
                        if (result.isNotEmpty()) return result
                    }
                }
            } catch (_: Exception) {
            }
        }
        return ""
    }

    private fun callGeminiWithTools(apiKey: String, userText: String): AgentDecision? {
        val declarations = JSONArray()
            .put(tool("check_whatsapp_messages", "Revisa si hay mensajes nuevos o audios sin leer en WhatsApp"))
            .put(toolWithParams(
                "read_whatsapp_from_sender",
                "Lee los mensajes recientes o abre el chat de WhatsApp de una persona específica",
                mapOf("sender_name" to "Nombre del contacto")
            ))
            .put(toolWithParams(
                "play_whatsapp_voice_note",
                "Reproduce un audio de voz de WhatsApp (el último, el penúltimo/anterior, o de un contacto)",
                mapOf(
                    "sender_name" to "Nombre del contacto (vacío si no especificó)",
                    "which_offset" to "1 para el último, 2 para el anterior/penúltimo, 3 para el antepenúltimo"
                )
            ))
            .put(toolWithParams(
                "send_whatsapp_text",
                "Prepara el envío de un mensaje de texto por WhatsApp a un contacto",
                mapOf(
                    "recipient_name" to "Nombre del destinatario",
                    "message_text" to "Texto del mensaje a enviar"
                )
            ))
            .put(toolWithParams(
                "record_whatsapp_voice_note",
                "Graba un audio con la voz del usuario y lo envía por WhatsApp al contacto indicado",
                mapOf("recipient_name" to "Nombre del contacto")
            ))
            .put(toolWithParams(
                "set_alarm",
                "Configura una alarma en el reloj del teléfono",
                mapOf(
                    "hour" to "Hora de 0 a 23",
                    "minute" to "Minutos de 0 a 59",
                    "label" to "Etiqueta"
                )
            ))
            .put(toolWithParams(
                "play_youtube",
                "Reproduce una canción, artista o video en YouTube",
                mapOf("search_query" to "Canción o artista")
            ))
            .put(tool("stop_media_or_alarm", "Detiene la música, YouTube, audio o alarma"))
            .put(toolWithParams(
                "make_phone_call",
                "Llama por teléfono en altavoz a un contacto",
                mapOf("contact_name" to "Nombre del contacto")
            ))
            .put(tool("check_time", "Dice únicamente qué hora es"))
            .put(tool("check_battery", "Dice únicamente cuánta batería tiene"))
            .put(tool("check_weather", "Dice la temperatura actual y el clima del día"))
            .put(toolWithParams(
                "search_google_and_explain",
                "Busca en Google cualquier pregunta o noticia y responde de forma muy breve",
                mapOf("query" to "Consulta a buscar")
            ))
            .put(tool("identify_banknote", "Mira con la cámara qué billete de pesos argentinos tiene en la mano"))

        val payload = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userText)))
            }))
            put("tools", JSONArray().put(JSONObject().apply {
                put("function_declarations", declarations)
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
                    val fnCall = part.optJSONObject("functionCall")
                    if (fnCall != null) {
                        val name = fnCall.optString("name")
                        val args = fnCall.optJSONObject("args") ?: JSONObject()
                        return mapFunctionCallToDecision(apiKey, name, args)
                    }
                }

                val spokenText = parts.optJSONObject(0)?.optString("text", "")?.trim() ?: ""
                if (spokenText.isNotEmpty()) {
                    return AgentDecision.SpeakOnly(spokenText)
                }
            }
        }
        return null
    }

    private fun mapFunctionCallToDecision(apiKey: String, name: String, args: JSONObject): AgentDecision {
        return when (name) {
            "check_whatsapp_messages" -> AgentDecision.CheckUnreadWhatsApp()
            "read_whatsapp_from_sender" -> AgentDecision.ReadWhatsAppFromSender(
                args.optString("sender_name", "")
            )
            "play_whatsapp_voice_note" -> {
                val sender = args.optString("sender_name", "").ifBlank { null }
                val offset = args.optString("which_offset", "1").toIntOrNull() ?: 1
                AgentDecision.PlayWhatsAppVoiceNote(
                    senderName = sender,
                    whichOffset = offset,
                    forceOpenChat = sender != null
                )
            }
            "send_whatsapp_text" -> AgentDecision.ProposeWhatsAppTextMessage(
                recipientName = args.optString("recipient_name", ""),
                messageText = args.optString("message_text", "")
            )
            "record_whatsapp_voice_note" -> AgentDecision.StartWhatsAppVoiceNoteRecording(
                recipientName = args.optString("recipient_name", "")
            )
            "set_alarm" -> AgentDecision.SetAlarm(
                hour = args.optString("hour", "8").toIntOrNull() ?: 8,
                minute = args.optString("minute", "0").toIntOrNull() ?: 0,
                label = args.optString("label", "Alarma")
            )
            "play_youtube" -> AgentDecision.PlayYouTube(
                searchQuery = args.optString("search_query", "")
            )
            "stop_media_or_alarm" -> AgentDecision.StopPlaybackOrAlarm()
            "make_phone_call" -> AgentDecision.MakePhoneCall(
                contactName = args.optString("contact_name", "")
            )
            "check_time" -> AgentDecision.CheckTimeOnly()
            "check_battery" -> AgentDecision.CheckBatteryOnly()
            "check_weather" -> AgentDecision.CheckWeather(includeForecast = true)
            "search_google_and_explain" -> {
                val query = args.optString("query", "")
                val groundedAnswer = callGeminiSearchGrounded(apiKey, query)
                if (groundedAnswer.isNotBlank()) {
                    AgentDecision.SpeakOnly(groundedAnswer)
                } else {
                    AgentDecision.SpeakOnly("Buscando $query.")
                }
            }
            "identify_banknote" -> AgentDecision.IdentifyBanknote()
            else -> AgentDecision.SpeakOnly("No te entendí, ¿repetís?")
        }
    }

    fun analyzeArgentineBanknote(jpegBytes: ByteArray): String? {
        val apiKeys = AppPrefs.getGeminiApiKeyList(context)
        if (apiKeys.isEmpty()) return null

        for (apiKey in apiKeys) {
            val result = analyzeArgentineBanknoteWithKey(apiKey, jpegBytes)
            if (!result.isNullOrBlank()) return result
        }
        return null
    }

    private fun analyzeArgentineBanknoteWithKey(apiKey: String, jpegBytes: ByteArray): String? {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val visionPrompt = """
            Sos los ojos de una persona no vidente en Argentina que te muestra un billete frente a la cámara.
            Identificá qué billete de Pesos Argentinos aparece:
            - 10, 20, 50, 100, 200, 500 (Yaguareté), 1000 (Hornero o San Martín), 2000 (Grierson y Carrillo), 10000 (Belgrano), 20000 (Alberdi).
            REGLA ESTRICTA:
            - Respondé ÚNICAMENTE con la frase corta: "Es de 1000", "Es de 500", "Es de 2000", "Es de 10000", "Es de 20000", "Es de 200", "Es de 100", "Es de 50", "Es de 20" o "Es de 10".
            - Si no se ve ningún billete claro, respondé: "No se ve bien, apuntalo de nuevo."
        """.trimIndent()

        val partsArray = JSONArray()
            .put(JSONObject().put("text", visionPrompt))
            .put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", base64Image)
                })
            })

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", partsArray)
            }))
        }

        for (model in candidateModels) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: return@use
                    if (!response.isSuccessful) return@use
                    val json = JSONObject(bodyStr)
                    val text = json.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "")
                        ?.trim()
                    if (!text.isNullOrBlank()) return text
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun tool(name: String, description: String): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("description", description)
        }
    }

    private fun toolWithParams(name: String, description: String, params: Map<String, String>): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        params.forEach { (key, desc) ->
            properties.put(key, JSONObject().apply {
                put("type", "STRING")
                put("description", desc)
            })
            required.put(key)
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", properties)
                put("required", required)
            })
        }
    }
}
