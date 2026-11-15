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
    data class OpenWhatsAppChatAndAskDay(val senderName: String) : AgentDecision()
    data class StartUnifiedDayChatPlayback(
        val senderName: String?,
        val dayFilter: String
    ) : AgentDecision()
    data class PlayWhatsAppVoiceNote(
        val senderName: String? = null,
        val whichOffset: Int = 1
    ) : AgentDecision()
    data class NavigateQueue(
        val isNext: Boolean,
        val isRepeat: Boolean = false
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
            Pair(
                true,
                "🟢 IA Gratuita Incorporada activa (la clave ingresada no es de AI Studio, pero ya tenés la IA integrada funcionando sin clave)."
            )
        } catch (e: Exception) {
            Pair(true, "🟢 IA Gratuita Incorporada activa.")
        }
    }

    suspend fun interpretUserCommand(userText: String): AgentDecision = withContext(Dispatchers.IO) {
        val flexibleLocal = tryFlexibleLocalIntent(userText)
        if (flexibleLocal != null) return@withContext flexibleLocal

        val apiKeys = AppPrefs.getGeminiApiKeyList(context)
        for (key in apiKeys) {
            if (key.startsWith("AIza")) {
                try {
                    val result = callGeminiWithTools(key, userText)
                    if (result != null) {
                        if (result !is AgentDecision.SpeakOnly || !result.message.startsWith("CUOTA_AGOTADA")) {
                            return@withContext result
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        val builtInAiAnswer = SystemActionsController.askFreeBuiltInAi(userText)
        return@withContext AgentDecision.SpeakOnly(builtInAiAnswer)
    }

    private fun tryFlexibleLocalIntent(rawText: String): AgentDecision? {
        val norm = WhatsAppRepository.normalizeText(rawText)
        if (norm.isBlank()) return null

        // 0. "¿Qué podés hacer?" / "Enlistame las cosas que podés hacer" / "Ayuda"
        if (norm.contains("que podes hacer") ||
            norm.contains("cosas que podes hacer") ||
            norm.contains("enlistame") ||
            norm.contains("que funciones tenes") ||
            norm.contains("para que servis") ||
            norm == "ayuda" ||
            norm.contains("como te uso")
        ) {
            return AgentDecision.SpeakOnly(
                "Puedo abrir el chat de cualquier contacto y leerte o reproducir tus mensajes y audios del día de hoy o de ayer del primero al último diciendo siguiente y anterior, mandar audios, llamar en altavoz, poner música en YouTube, reconocer qué billete tenés en la mano, decirte la hora, la batería, el clima de cualquier día de la semana, poner alarmas y explicarte recetas paso a paso."
            )
        }

        // 1. Navegación rápida "Siguiente", "Anterior", "Repetir"
        if (norm == "siguiente" ||
            norm == "el siguiente" ||
            norm == "pasa al siguiente" ||
            norm == "el que sigue" ||
            norm == "otro" ||
            norm == "el otro" ||
            norm == "siguiente audio" ||
            norm == "siguiente mensaje"
        ) {
            return AgentDecision.NavigateQueue(isNext = true, isRepeat = false)
        }

        if (norm == "anterior" ||
            norm == "el anterior" ||
            norm == "volve al anterior" ||
            norm == "atras" ||
            norm == "el de antes" ||
            norm == "audio anterior" ||
            norm == "mensaje anterior"
        ) {
            return AgentDecision.NavigateQueue(isNext = false, isRepeat = false)
        }

        if (norm == "repetilo" ||
            norm == "repetir" ||
            norm == "de nuevo" ||
            norm == "otra vez" ||
            norm == "el mismo"
        ) {
            return AgentDecision.NavigateQueue(isNext = false, isRepeat = true)
        }

        // 2. Callar / Detener / Pausar música o alarma
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

        // 3. Reconocer Billetes por Cámara
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

        // 4. Clima / Temperatura / Pronóstico (hoy, mañana, el lunes, los próximos X días, etc.)
        if (norm.contains("clima") ||
            norm.contains("grados") ||
            norm.contains("temperatura") ||
            norm.contains("pronostico") ||
            norm.contains("llover") ||
            norm.contains("llueve") ||
            norm.contains("lluvia") ||
            norm.contains("hace frio") ||
            norm.contains("hace calor") ||
            norm.contains("como esta el tiempo") ||
            norm.contains("como va a estar el") ||
            norm.contains("como va a estar los") ||
            norm.contains("proximos") && norm.contains("dias") ||
            norm.contains("como esta afuera")
        ) {
            return AgentDecision.CheckWeather(rawQuery = rawText)
        }

        // 5. Hora sola
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
        val hasSendOrRecordVerb = norm.contains("manda") ||
            norm.contains("envia") ||
            norm.contains("graba") ||
            norm.contains("responde")
        val hasAudioWord = norm.contains("audio") || norm.contains("audios") || norm.contains("nota de voz") || norm.contains("mensaje de voz")

        // 10. Grabar y enviar un audio de voz por WhatsApp ("mandale un audio a Nico")
        if (hasSendOrRecordVerb && hasAudioWord && !norm.contains("reproduci") && !norm.contains("escucha") && !norm.contains("lee")) {
            val contact = extractContactFromCommand(rawText, norm)
            if (!contact.isNullOrBlank()) {
                return AgentDecision.StartWhatsAppVoiceNoteRecording(contact)
            }
        }

        // 11. Enviar mensaje de texto por WhatsApp
        val hasTextSendTrigger = (norm.contains("manda") || norm.contains("envia") || norm.contains("escribi") || norm.contains("decile")) &&
            (norm.contains("whatsapp") || norm.contains("mensaje") || norm.contains("que diga"))

        if (hasTextSendTrigger && !hasAudioWord) {
            val parsed = parseWhatsAppTextCommand(rawText)
            if (parsed != null) {
                return AgentDecision.ProposeWhatsAppTextMessage(parsed.first, parsed.second)
            }
        }

        // 12. "Abrí el chat de X persona" (sin especificar día -> lo abre y pregunta de qué día quiere escuchar)
        val isOpenChatCommand = (norm.contains("abri") || norm.contains("abrir") || norm.startsWith("chat de ") || norm.startsWith("el chat de ")) &&
            (norm.contains("chat") || norm.contains("whatsapp") || norm.contains("conversacion"))

        if (isOpenChatCommand) {
            val contact = extractContactFromCommand(rawText, norm)
            if (!contact.isNullOrBlank()) {
                return if (daySpec != null) {
                    // Si ya dijo el día ("abrí el chat de hoy de Nico"), arranca directo del primero al último de ese día
                    AgentDecision.StartUnifiedDayChatPlayback(senderName = contact, dayFilter = daySpec)
                } else {
                    // Si solo dijo "abrí el chat de Nico", abre el chat y le pregunta de qué día quiere escuchar
                    AgentDecision.OpenWhatsAppChatAndAskDay(senderName = contact)
                }
            }
        }

        // 13. Leer o reproducir mensajes/audios/chat de un día ("hoy", "ayer", etc.) o de una persona (DEL PRIMERO AL ÚLTIMO sin discriminar texto y audio)
        val hasPlayOrReadVerb = norm.contains("reproduci") ||
            norm.contains("escucha") ||
            norm.contains("pone") ||
            norm.contains("pasame") ||
            norm.contains("lee") ||
            norm.contains("leeme") ||
            norm.contains("mostrame")

        val mentionsWhatsAppContent = hasAudioWord ||
            norm.contains("mensaje") ||
            norm.contains("mensajes") ||
            norm.contains("chat") ||
            norm.contains("whatsapp") ||
            norm.contains("escribio") ||
            norm.contains("mando")

        if (mentionsWhatsAppContent) {
            val contact = extractContactFromCommand(rawText, norm)

            // Si pidió específicamente un día ("de hoy", "de ayer") o una persona ("de Nico"):
            // Recorre la lista unificada (textos + audios) de ese día DEL PRIMERO AL ÚLTIMO
            if (daySpec != null || !contact.isNullOrBlank() || norm.contains("lee")) {
                return AgentDecision.StartUnifiedDayChatPlayback(
                    senderName = contact,
                    dayFilter = daySpec ?: "hoy"
                )
            }

            // Si dijo "reproducí el audio" (en general), arranca la cola de hoy del primero al último
            if (hasAudioWord && hasPlayOrReadVerb) {
                return AgentDecision.PlayWhatsAppVoiceNote(senderName = null, whichOffset = 1)
            }

            // Si solo preguntó "¿tengo mensajes?"
            return AgentDecision.CheckUnreadWhatsApp()
        }

        // 14. Llamadas Telefónicas
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

        // 15. YouTube / Música (sin confundirse con recetas ni con chats)
        val isRecipeOrHowTo = norm.contains("paso a paso") || norm.contains("receta") || norm.contains("como hacer") || norm.contains("como se hace")
        if (!isRecipeOrHowTo && (
                norm.contains("youtube") ||
                    norm.contains("musica") ||
                    norm.contains("cancion") ||
                    norm.contains("tema ") ||
                    norm.startsWith("pone ") ||
                    norm.startsWith("poneme ") ||
                    norm.startsWith("reproduci ") ||
                    norm.startsWith("quiero escuchar ")
                )
        ) {
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
        // 1. Limpiamos primero las palabras de fecha ("de hoy", "de ayer", "del día de hoy") para que no tapen el nombre de la persona
        val withoutDays = normText
            .replace("del dia de hoy", " ")
            .replace("del dia de ayer", " ")
            .replace("de ayer y hoy", " ")
            .replace("de hoy y ayer", " ")
            .replace("de antes de ayer", " ")
            .replace("de hoy", " ")
            .replace("de ayer", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // 2. Buscamos primero luego de "de ", "chat de ", "chat con ", "audios de ", "mensajes de "
        val patterns = listOf(
            Regex("(?:chat de|chat con|conversacion con|audios? de|mensajes? de|escribio|mando)\\s+([a-zA-Z0-9áéíóúÁÉÍÓÚñÑ ]+)"),
            Regex("(?:llama a|llamar a|comunicame con|hablar con|audio a|audio para|whatsapp a|mensaje a|escribile a|decile a)\\s+([a-zA-Z0-9áéíóúÁÉÍÓÚñÑ ]+)")
        )
        for (regex in patterns) {
            val match = regex.find(withoutDays)
            if (match != null) {
                val candidate = match.groupValues[1]
                    .replace(Regex("^\\b(ayer|hoy|dia|el|la|los|las|y|de|persona)\\b\\s*"), "")
                    .replace(Regex("\\b(ayer|hoy|dia|y|que|por|en|el|la|los|las|un|una|ultimo|penultimo|anterior|antepenultimo|reproduci|escuchar|audio|audios|whatsapp)\\b.*"), "")
                    .trim()
                if (candidate.length >= 2) return candidate
            }
        }

        // 3. También verificamos si aparece algún contacto de la agenda del teléfono en la oración
        val fromAgenda = WhatsAppRepository.findMentionedContactInSentence(context, rawText)
        if (!fromAgenda.isNullOrBlank()) return fromAgenda

        return null
    }

    private fun parseWhatsAppTextCommand(rawText: String): Pair<String, String>? {
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

                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val txt = parts.optJSONObject(i)?.optString("text", "") ?: ""
                    if (txt.isNotBlank()) sb.append(txt).append(" ")
                }
                val spokenText = sb.toString().trim()
                if (spokenText.isNotEmpty()) {
                    return AgentDecision.SpeakOnly(spokenText)
                }
            }
        }
        return null
    }

    fun analyzeArgentineBanknote(jpegBytes: ByteArray): String? {
        val apiKeys = AppPrefs.getGeminiApiKeyList(context)
        for (apiKey in apiKeys) {
            if (apiKey.startsWith("AIza")) {
                val result = analyzeArgentineBanknoteWithKey(apiKey, jpegBytes)
                if (!result.isNullOrBlank()) return result
            }
        }
        return null
    }

    private fun analyzeArgentineBanknoteWithKey(apiKey: String, jpegBytes: ByteArray): String? {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val visionPrompt = """
            Sos los ojos de una persona no vidente en Argentina que te muestra un billete frente a la cámara.
            Identificá qué billete de Pesos Argentinos aparece:
            - 10, 20, 50, 100, 200, 500 (Yaguareté), 1000 (Hornero o San Martín), 2000 (Grierson y Carrillo), 10000 (Belgrano), 20000 (Alberdi).
            Respondé ÚNICAMENTE con la frase corta: "Es de 1000", "Es de 500", "Es de 2000", "Es de 10000", "Es de 20000", "Es de 200", "Es de 100", "Es de 50", "Es de 20" o "Es de 10".
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
}
