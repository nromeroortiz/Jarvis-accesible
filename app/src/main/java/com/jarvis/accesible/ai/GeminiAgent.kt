package com.jarvis.accesible.ai

import android.content.Context
import android.util.Base64
import com.jarvis.accesible.core.AppPrefs
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
    data class CheckUnreadWhatsApp(val dummy: Boolean = true) : AgentDecision()
    data class ReadWhatsAppFromSender(val senderName: String) : AgentDecision()
    data class PlayLatestWhatsAppVoiceNote(val senderName: String?) : AgentDecision()
    data class ProposeWhatsAppTextMessage(val recipientName: String, val messageText: String) : AgentDecision()
    data class StartWhatsAppVoiceNoteRecording(val recipientName: String) : AgentDecision()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : AgentDecision()
    data class PlayYouTube(val searchQuery: String) : AgentDecision()
    data class StopPlaybackOrAlarm(val dummy: Boolean = true) : AgentDecision()
    data class MakePhoneCall(val contactName: String) : AgentDecision()
    data class EndOrAnswerCall(val endCall: Boolean) : AgentDecision()
    data class CheckPhoneStatus(val dummy: Boolean = true) : AgentDecision()
    data class ToggleWakeWordListening(val enable: Boolean) : AgentDecision()
    data class IdentifyBanknote(val dummy: Boolean = true) : AgentDecision()
}

/**
 * Cliente para Google Gemini 2.5 Flash con:
 * 1. Function Calling (Herramientas para controlar WhatsApp, audios, llamadas, alarmas, YouTube y estado del celular).
 * 2. Búsqueda web en Google integrada (Google Search Grounding) para responder preguntas generales leídas en detalle.
 * 3. Intérprete local instantáneo de respaldo por si algún comando básico se dice sin conexión o antes de configurar la API Key.
 */
class GeminiAgent(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
        Sos Jarvis, un asistente de voz personal en español rioplatense (Argentina), cálido, paciente y sumamente claro.
        Tu usuario es una persona mayor que no puede ver (es no vidente) y maneja su celular 100% por voz sin tocar la pantalla.
        
        REGLAS DE ORO:
        1. NUNCA le pidas que mire la pantalla, que toque un botón ni que abra un link. Vos sos sus ojos y sus manos.
        2. Cuando te pida buscar algo en internet o hacer una consulta general, dale la información completa, detallada y bien explicada para ser escuchada en voz alta. No uses formato Markdown ni asteriscos.
        3. Si te pide realizar una acción del teléfono (revisar WhatsApp, reproducir audios recibidos, mandar un WhatsApp de texto, grabar un audio de voz para WhatsApp, llamar por teléfono, poner una alarma, poner música en YouTube, detener lo que está sonando o consultar hora/batería), invocá SIEMPRE la función correspondiente.
    """.trimIndent()

    suspend fun interpretUserCommand(userText: String): AgentDecision = withContext(Dispatchers.IO) {
        // Primero verificamos atajos directos súper rápidos que no necesitan esperar red
        val localShortcut = tryFastLocalIntent(userText)
        if (localShortcut != null) return@withContext localShortcut

        val apiKeys = AppPrefs.getGeminiApiKeyList(context)
        if (apiKeys.isEmpty()) {
            return@withContext AgentDecision.SpeakOnly(
                "Todavía falta colocar la clave gratuita de Google Gemini en la pantalla de configuración de Jarvis. Mientras tanto, igual podés pedirme: tengo algún mensaje, reproducí el audio, qué billete es este, qué hora es, o cuánta batería tengo."
            )
        }

        try {
            var lastDecision: AgentDecision? = null
            for (key in apiKeys) {
                val result = callGeminiWithTools(key, userText)
                lastDecision = result
                if (result !is AgentDecision.SpeakOnly || !result.message.startsWith("CUOTA_AGOTADA")) {
                    return@withContext result
                }
            }
            if (lastDecision is AgentDecision.SpeakOnly && lastDecision.message.startsWith("CUOTA_AGOTADA")) {
                return@withContext AgentDecision.SpeakOnly("Se agotó la cuota gratuita de las claves configuradas por hoy.")
            }
            lastDecision ?: AgentDecision.SpeakOnly("No pude procesar el pedido.")
        } catch (e: Exception) {
            AgentDecision.SpeakOnly("Hubo un problema de conexión a internet. Intentá de nuevo en unos segundos.")
        }
    }

    /**
     * Si la pregunta requiere buscar información actualizada en Google (noticias, clima, resultados de fútbol, recetas, etc.),
     * realiza una consulta con Google Search Grounding activado en Gemini 2.5 Flash.
     */
    private fun callGeminiSearchGrounded(apiKey: String, query: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        val payload = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put(
                    "text",
                    "Buscá información actualizada sobre esto y explicámelo en detalle pero de forma clara y conversacional para alguien que no puede ver la pantalla: $query"
                )))
            }))
            put("tools", JSONArray().put(JSONObject().apply {
                put("google_search", JSONObject())
            }))
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: return "No pude obtener la respuesta de Google en este momento."
            if (!response.isSuccessful) {
                return "No pude realizar la búsqueda en este momento."
            }
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
            return "No encontré resultados claros para esa búsqueda."
        }
    }

    private fun callGeminiWithTools(apiKey: String, userText: String): AgentDecision {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        val declarations = JSONArray()
            .put(tool("check_whatsapp_messages", "Revisa si hay mensajes nuevos o audios sin leer en WhatsApp"))
            .put(toolWithParams(
                "read_whatsapp_from_sender",
                "Lee los mensajes recientes de WhatsApp de una persona específica",
                mapOf("sender_name" to "Nombre del contacto o persona")
            ))
            .put(toolWithParams(
                "play_whatsapp_voice_note",
                "Reproduce el último audio o nota de voz recibido en WhatsApp",
                mapOf("sender_name" to "Nombre de la persona que envió el audio (opcional)")
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
                "Graba un audio con la voz real del usuario y lo envía por WhatsApp al contacto indicado",
                mapOf("recipient_name" to "Nombre del contacto al que se le enviará el audio grabado")
            ))
            .put(toolWithParams(
                "set_alarm",
                "Configura una alarma en el reloj del teléfono sin mostrar pantalla",
                mapOf(
                    "hour" to "Hora en formato 0 a 23 (número entero)",
                    "minute" to "Minutos de 0 a 59 (número entero)",
                    "label" to "Motivo de la alarma"
                )
            ))
            .put(toolWithParams(
                "play_youtube",
                "Busca y reproduce directamente una canción, artista o video en YouTube",
                mapOf("search_query" to "Canción, artista o video a reproducir")
            ))
            .put(tool("stop_media_or_alarm", "Pausa o apaga la música, YouTube, audio o alarma que esté sonando"))
            .put(toolWithParams(
                "make_phone_call",
                "Llama por línea telefónica en altavoz a un contacto de la agenda",
                mapOf("contact_name" to "Nombre de la persona a llamar")
            ))
            .put(tool("check_phone_status", "Dice qué hora es, cuánta batería tiene el celular y si está cargando"))
            .put(toolWithParams(
                "toggle_voice_wake_word",
                "Activa o desactiva la escucha continua por voz para ahorrar batería",
                mapOf("enable" to "true para activar, false para desactivar y ahorrar batería")
            ))
            .put(toolWithParams(
                "search_google_and_explain",
                "Realiza una búsqueda en Google sobre cualquier tema, noticia, clima, deporte o pregunta y se la explica detalladamente por voz",
                mapOf("query" to "Consulta completa a buscar en Google")
            ))
            .put(tool(
                "identify_banknote",
                "Abre la cámara trasera para mirar qué billete de pesos argentinos tiene el usuario en la mano y decirle el valor (por ejemplo: es de 1000, es de 500)"
            ))

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

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string()
                ?: return AgentDecision.SpeakOnly("No recibí respuesta del servidor.")
            if (response.code == 429 || response.code == 403) {
                return AgentDecision.SpeakOnly("CUOTA_AGOTADA")
            }
            if (!response.isSuccessful) {
                return AgentDecision.SpeakOnly("Revisa que la clave de Gemini en la configuración sea correcta.")
            }

            val json = JSONObject(bodyStr)
            val parts = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return AgentDecision.SpeakOnly("No llegué a entenderte bien, ¿me lo repetís?")

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
            return if (spokenText.isNotEmpty()) {
                AgentDecision.SpeakOnly(spokenText)
            } else {
                AgentDecision.SpeakOnly("¿Me podrías repetir qué necesitás?")
            }
        }
    }

    private fun mapFunctionCallToDecision(apiKey: String, name: String, args: JSONObject): AgentDecision {
        return when (name) {
            "check_whatsapp_messages" -> AgentDecision.CheckUnreadWhatsApp()
            "read_whatsapp_from_sender" -> AgentDecision.ReadWhatsAppFromSender(
                args.optString("sender_name", "")
            )
            "play_whatsapp_voice_note" -> AgentDecision.PlayLatestWhatsAppVoiceNote(
                args.optString("sender_name", "").ifBlank { null }
            )
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
            "check_phone_status" -> AgentDecision.CheckPhoneStatus()
            "toggle_voice_wake_word" -> {
                val enableStr = args.optString("enable", "true").lowercase()
                AgentDecision.ToggleWakeWordListening(enableStr == "true" || enableStr == "1" || enableStr == "si")
            }
            "search_google_and_explain" -> {
                val query = args.optString("query", "")
                val groundedAnswer = callGeminiSearchGrounded(apiKey, query)
                AgentDecision.SpeakOnly(groundedAnswer)
            }
            "identify_banknote" -> AgentDecision.IdentifyBanknote()
            else -> AgentDecision.SpeakOnly("No encontré esa función.")
        }
    }

    /**
     * Analiza la foto capturada por la cámara trasera usando Gemini 2.5 Flash Multimodal
     * para reconocer billetes de Pesos Argentinos (10, 20, 50, 100, 200, 500, 1000, 2000, 10000, 20000)
     * tanto por su número como por sus figuras (Hornero, San Martín, Yaguareté, Carrillo/Grierson, Belgrano, Alberdi, etc.).
     */
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
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val visionPrompt = """
            Sos los ojos de una persona no vidente en Argentina que te está mostrando un billete frente a la cámara del celular.
            Identificá qué billete de Pesos Argentinos (o de otra moneda si no es argentino) aparece en la imagen.
            Tené en cuenta todas las denominaciones y diseños de Argentina:
            - 10 pesos (Belgrano)
            - 20 pesos (Rosas o Guanaco)
            - 50 pesos (Sarmiento, Islas Malvinas o Cóndor)
            - 100 pesos (Roca, Evita o Taruca)
            - 200 pesos (Ballena Franca Austral)
            - 500 pesos (Yaguareté, color verde)
            - 1000 pesos (Hornero o José de San Martín, color naranja)
            - 2000 pesos (Cecilia Grierson y Ramón Carrillo)
            - 10000 pesos (Manuel Belgrano y María Remedios del Valle)
            - 20000 pesos (Juan Bautista Alberdi)
            
            REGLA ESTRICTA DE RESPUESTA:
            - Si ves un solo billete de pesos argentinos, respondé ÚNICAMENTE con la frase corta y directa: "Es de 1000", "Es de 500", "Es de 2000", "Es de 10000", "Es de 20000", "Es de 200", "Es de 100", "Es de 50", "Es de 20" o "Es de 10".
            - Si ves varios billetes juntos, decí cada uno y el total, por ejemplo: "Tenés uno de 1000 y uno de 500. En total son 1500."
            - Si no se llega a distinguir el billete porque está muy borroso, oscuro o no aparece en la foto, respondé: "No se llega a ver bien el billete. Apuntalo de nuevo a unos veinte centímetros de la cámara de atrás."
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

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: return null
                if (!response.isSuccessful) return null
                val json = JSONObject(bodyStr)
                val text = json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text", "")
                    ?.trim()
                if (!text.isNullOrBlank()) text else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryFastLocalIntent(rawText: String): AgentDecision? {
        val text = rawText.lowercase().trim()
        return when {
            text.contains("qué billete") ||
                text.contains("que billete") ||
                text.contains("de cuánto es este billete") ||
                text.contains("de cuanto es este billete") ||
                text.contains("de cuánto es el billete") ||
                text.contains("de cuanto es el billete") ||
                text.contains("mirá este billete") ||
                text.contains("mira este billete") ||
                text.contains("reconocé este billete") ||
                text.contains("reconoce este billete") ||
                text.contains("leé este billete") ||
                text.contains("lee este billete") ||
                text.contains("cuánta plata es") ||
                text.contains("cuanta plata es") -> AgentDecision.IdentifyBanknote()

            text.contains("tengo algún mensaje") ||
                text.contains("tengo algun mensaje") ||
                text.contains("tengo mensajes") ||
                text.contains("mensajes nuevos") ||
                text.contains("quién me escribió") ||
                text.contains("quien me escribio") -> AgentDecision.CheckUnreadWhatsApp()

            text.contains("reproducí el audio") ||
                text.contains("reproduci el audio") ||
                text.contains("escuchar el audio") ||
                text.contains("poné el audio") ||
                text.contains("pone el audio") ||
                text.contains("leeme el audio") -> AgentDecision.PlayLatestWhatsAppVoiceNote(null)

            text == "qué hora es" ||
                text == "que hora es" ||
                text.contains("cuánta batería") ||
                text.contains("cuanta bateria") ||
                text.contains("está cargando") ||
                text.contains("esta cargando") -> AgentDecision.CheckPhoneStatus()

            text == "pará" ||
                text == "para" ||
                text == "basta" ||
                text == "silencio" ||
                text.contains("apagá la música") ||
                text.contains("apaga la musica") ||
                text.contains("detené la música") ||
                text.contains("pausá youtube") ||
                text.contains("apagá la alarma") -> AgentDecision.StopPlaybackOrAlarm()

            else -> null
        }
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
