package com.jarvis.accesible.actions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.telecom.TelecomManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.accesible.services.JarvisAccessibilityService
import com.jarvis.accesible.whatsapp.WhatsAppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object SystemActionsController {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun setSilentAlarm(context: Context, hour: Int, minute: Int, label: String): String {
        val validHour = hour.coerceIn(0, 23)
        val validMinute = minute.coerceIn(0, 59)
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, validHour)
                putExtra(AlarmClock.EXTRA_MINUTES, validMinute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Alarma" })
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val minFormatted = validMinute.toString().padStart(2, '0')
            "Alarma puesta a las $validHour:$minFormatted."
        } catch (e: Exception) {
            "No pude poner la alarma."
        }
    }

    fun stopAnythingPlayingOrRinging(context: Context): String {
        WhatsAppRepository.stopAudioPlayback()

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)

            val pauseDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
            val pauseUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
            audioManager.dispatchMediaKeyEvent(pauseDown)
            audioManager.dispatchMediaKeyEvent(pauseUp)
        } catch (_: Exception) {
        }

        try {
            val dismissAlarm = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dismissAlarm)
        } catch (_: Exception) {
        }

        return "Listo, detenido."
    }

    /**
     * Busca en YouTube el primer video real que coincida con la canción pedida y lo reproduce directamente.
     */
    suspend fun playOnYouTubeDirectly(context: Context, searchQuery: String): String {
        val cleanQuery = searchQuery.trim()
        if (cleanQuery.isEmpty()) return "Decime qué canción querés escuchar."

        val videoId = withContext(Dispatchers.IO) {
            fetchFirstYouTubeVideoId(cleanQuery)
        }

        return try {
            JarvisAccessibilityService.requestAutoPlayInYouTube()

            if (videoId != null) {
                val watchUri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
                val appIntent = Intent(Intent.ACTION_VIEW, watchUri).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("force_fullscreen", false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(appIntent)
                } catch (_: Exception) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                }
                "Poniendo $cleanQuery."
            } else {
                val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", cleanQuery)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(searchIntent)
                "Poniendo $cleanQuery."
            }
        } catch (e: Exception) {
            "No pude abrir YouTube."
        }
    }

    private fun fetchFirstYouTubeVideoId(query: String): String? {
        return try {
            val encoded = Uri.encode(query)
            val request = Request.Builder()
                .url("https://www.youtube.com/results?search_query=$encoded&hl=es")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
                )
                .header("Cookie", "CONSENT=YES+cb.20210328-17-p0.es+FX+999")
                .header("Accept-Language", "es-AR,es;q=0.9")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null
                // Priorizamos videoRenderer para que sea siempre un video real reproducible y no un canal
                val rendererRegex = Regex("\"videoRenderer\":\\{\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
                val firstRenderer = rendererRegex.find(html)?.groupValues?.getOrNull(1)
                if (firstRenderer != null) return firstRenderer

                val fallbackRegex = Regex("\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
                fallbackRegex.find(html)?.groupValues?.getOrNull(1)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun makePhoneCallOnSpeaker(context: Context, contactName: String): String {
        val contact = WhatsAppRepository.findContactByName(context, contactName)
            ?: return "No encontré a $contactName en tus contactos."

        return try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(contact.second)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            mainHandler.postDelayed({
                try {
                    audioManager.mode = AudioManager.MODE_IN_CALL
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                } catch (_: Exception) {
                }
            }, 1500L)
            mainHandler.postDelayed({
                try {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                } catch (_: Exception) {
                }
            }, 3500L)

            "Llamando a ${contact.first}."
        } catch (e: SecurityException) {
            "Falta el permiso de llamadas."
        } catch (e: Exception) {
            "No pude llamar a ${contact.first}."
        }
    }

    @SuppressLint("MissingPermission")
    fun answerOrEndPhoneCall(context: Context, endCall: Boolean): String {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (endCall) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    telecomManager.endCall()
                }
                "Llamada cortada."
            } else {
                @Suppress("DEPRECATION")
                telecomManager.acceptRingingCall()
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                mainHandler.postDelayed({
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }, 1000L)
                "Llamada atendida."
            }
        } catch (_: Exception) {
            "No pude gestionar la llamada."
        }
    }

    fun getCurrentTimeOnly(): String {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return "Son las $timeStr."
    }

    fun getCurrentDateOnly(): String {
        val dateStr = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "AR")).format(Date())
        return "Hoy es $dateStr."
    }

    fun getBatteryOnly(context: Context): String {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        if (batteryPct < 0) return "No pude leer la batería."
        return if (isCharging) {
            "Batería al $batteryPct por ciento, cargando."
        } else {
            "Batería al $batteryPct por ciento."
        }
    }

    fun getConnectionOnly(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val netCaps = cm.getNetworkCapabilities(cm.activeNetwork)
        return when {
            netCaps == null -> "Sin conexión a internet."
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Conectado a Wi-Fi."
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Conectado con datos móviles."
            else -> "Conectado a internet."
        }
    }

    /**
     * Consulta el clima por GPS para:
     * - Temperatura actual ("¿cuántos grados hace?")
     * - Hoy ("¿cómo va a estar el clima hoy?")
     * - Mañana / Pasado mañana ("¿cómo va a estar mañana?")
     * - Un día puntual de la semana ("¿cómo va a estar el lunes?", "el sábado")
     * - Los próximos X días ("¿cómo va a estar los próximos 3 días?", "esta semana")
     */
    suspend fun getFlexibleWeatherReport(context: Context, rawUserQuery: String): String = withContext(Dispatchers.IO) {
        val norm = WhatsAppRepository.normalizeText(rawUserQuery)
        val coords = getBestDeviceCoordinates(context)
        val lat = coords.first
        val lon = coords.second

        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7"

        return@withContext try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "No pude consultar el clima ahora."
                val body = response.body?.string() ?: return@withContext "No pude consultar el clima."
                val json = JSONObject(body)

                val current = json.optJSONObject("current")
                val tempNow = current?.optDouble("temperature_2m", Double.NaN)?.takeIf { !it.isNaN() }?.roundToInt()
                val codeNow = current?.optInt("weather_code", 0) ?: 0
                val condNow = describeWeatherCode(codeNow)

                val daily = json.optJSONObject("daily") ?: return@withContext "Hacen ${tempNow ?: 20} grados."
                val datesArr = daily.optJSONArray("time")
                val codesArr = daily.optJSONArray("weather_code")
                val maxArr = daily.optJSONArray("temperature_2m_max")
                val minArr = daily.optJSONArray("temperature_2m_min")
                val rainArr = daily.optJSONArray("precipitation_probability_max")

                // 1. ¿Pidió los próximos X días (con número o palabra: dos, tres, cuatro, cinco...) o toda la semana?
                val multiDaysCount = parseMultiDaysCount(norm)
                if (multiDaysCount != null) {
                    val count = multiDaysCount.coerceIn(2, 6)
                    val sb = StringBuilder()
                    for (i in 1..count.coerceAtMost((maxArr?.length() ?: 1) - 1)) {
                        val dayName = getDayNameForOffset(i).replaceFirstChar { it.uppercase() }
                        val maxT = maxArr?.optDouble(i, Double.NaN)?.roundToInt() ?: continue
                        val minT = minArr?.optDouble(i, Double.NaN)?.roundToInt() ?: continue
                        val rainP = rainArr?.optInt(i, 0) ?: 0
                        val cond = describeWeatherCode(codesArr?.optInt(i, 0) ?: 0)
                        val rainTxt = if (rainP >= 40) "con lluvia ($rainP%)" else cond
                        sb.append("$dayName: máxima $maxT, mínima $minT, $rainTxt. ")
                    }
                    return@withContext sb.toString().trim()
                }

                // 2. ¿Pidió pasado mañana?
                if (norm.contains("pasado manana")) {
                    return@withContext formatSingleDayForecast("Pasado mañana", 2, codesArr, maxArr, minArr, rainArr)
                }

                // 3. ¿Pidió mañana?
                if (norm.contains("manana")) {
                    return@withContext formatSingleDayForecast("Mañana", 1, codesArr, maxArr, minArr, rainArr)
                }

                // 4. ¿Pidió un día de la semana específico ("el lunes", "el martes", "el sábado", etc.)?
                val targetDow = parseRequestedDayOfWeek(norm)
                if (targetDow != null && datesArr != null) {
                    val cal = Calendar.getInstance()
                    for (i in 1 until datesArr.length()) {
                        val checkCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, i) }
                        if (checkCal.get(Calendar.DAY_OF_WEEK) == targetDow) {
                            val label = getDayNameForOffset(i).replaceFirstChar { it.uppercase() }
                            return@withContext formatSingleDayForecast(label, i, codesArr, maxArr, minArr, rainArr)
                        }
                    }
                }

                // 5. ¿Pidió solo cuántos grados hace ahora?
                val wantsOnlyCurrentTemp = (norm.contains("cuantos grados") || norm.contains("que temperatura")) &&
                    !norm.contains("hoy") && !norm.contains("maxima") && !norm.contains("clima")
                if (wantsOnlyCurrentTemp && tempNow != null) {
                    return@withContext "Hacen $tempNow grados, $condNow."
                }

                // 6. Clima de hoy completo
                val maxToday = maxArr?.optDouble(0, Double.NaN)?.takeIf { !it.isNaN() }?.roundToInt()
                val minToday = minArr?.optDouble(0, Double.NaN)?.takeIf { !it.isNaN() }?.roundToInt()
                val rainToday = rainArr?.optInt(0, 0) ?: 0

                val sb = StringBuilder()
                if (tempNow != null) {
                    sb.append("Hacen $tempNow grados, $condNow. ")
                }
                if (maxToday != null && minToday != null) {
                    sb.append("Hoy máxima $maxToday y mínima $minToday. ")
                }
                sb.append(if (rainToday >= 40) "Probabilidad de lluvia $rainToday por ciento." else "Sin lluvias hoy.")
                sb.toString().trim()
            }
        } catch (_: Exception) {
            "No hay internet para ver el clima."
        }
    }

    private fun formatSingleDayForecast(
        dayLabel: String,
        index: Int,
        codesArr: JSONArray?,
        maxArr: JSONArray?,
        minArr: JSONArray?,
        rainArr: JSONArray?
    ): String {
        val maxT = maxArr?.optDouble(index, Double.NaN)?.takeIf { !it.isNaN() }?.roundToInt()
            ?: return "No tengo el pronóstico de ese día."
        val minT = minArr?.optDouble(index, Double.NaN)?.takeIf { !it.isNaN() }?.roundToInt() ?: 0
        val rainP = rainArr?.optInt(index, 0) ?: 0
        val cond = describeWeatherCode(codesArr?.optInt(index, 0) ?: 0)
        val rainTxt = if (rainP >= 40) "probabilidad de lluvia del $rainP por ciento" else "sin lluvias"
        return "$dayLabel: máxima $maxT, mínima $minT, $cond, $rainTxt."
    }

    private fun getDayNameForOffset(daysAhead: Int): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, daysAhead) }
        return SimpleDateFormat("EEEE", Locale("es", "AR")).format(cal.time)
    }

    private fun parseMultiDaysCount(norm: String): Int? {
        val regex = Regex("(?:proximos|siguientes|estos)\\s+(\\d+|dos|tres|cuatro|cinco|seis|siete)?\\s*dias")
        val match = regex.find(norm)
        if (match != null) {
            val token = match.groupValues.getOrNull(1).orEmpty().trim()
            return when (token) {
                "2", "dos" -> 2
                "3", "tres", "" -> 3
                "4", "cuatro" -> 4
                "5", "cinco" -> 5
                "6", "seis", "7", "siete" -> 6
                else -> token.toIntOrNull() ?: 3
            }
        }
        if (norm.contains("toda la semana") || norm.contains("esta semana") || norm.contains("proximos dias")) {
            return 4
        }
        return null
    }

    private fun parseRequestedDayOfWeek(norm: String): Int? {
        return when {
            norm.contains("lunes") -> Calendar.MONDAY
            norm.contains("martes") -> Calendar.TUESDAY
            norm.contains("miercoles") -> Calendar.WEDNESDAY
            norm.contains("jueves") -> Calendar.THURSDAY
            norm.contains("viernes") -> Calendar.FRIDAY
            norm.contains("sabado") -> Calendar.SATURDAY
            norm.contains("domingo") -> Calendar.SUNDAY
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBestDeviceCoordinates(context: Context): Pair<Double, Double> {
        try {
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (hasCoarse || hasFine) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
                var bestLocation: Location? = null
                for (provider in providers) {
                    val loc = try {
                        lm.getLastKnownLocation(provider)
                    } catch (_: Exception) {
                        null
                    }
                    if (loc != null) {
                        if (bestLocation == null || loc.time > bestLocation.time) {
                            bestLocation = loc
                        }
                    }
                }
                if (bestLocation != null) {
                    return Pair(bestLocation.latitude, bestLocation.longitude)
                }
            }
        } catch (_: Exception) {
        }

        try {
            val req = Request.Builder().url("http://ip-api.com/json/?fields=lat,lon").build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val j = JSONObject(resp.body?.string().orEmpty())
                    val lat = j.optDouble("lat", Double.NaN)
                    val lon = j.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) {
                        return Pair(lat, lon)
                    }
                }
            }
        } catch (_: Exception) {
        }

        return Pair(-34.6037, -58.3816)
    }

    private fun describeWeatherCode(code: Int): String {
        return when (code) {
            0 -> "cielo despejado"
            1, 2 -> "algo nublado"
            3 -> "nublado"
            45, 48 -> "con niebla"
            51, 53, 55 -> "con llovizna"
            61, 63, 65, 80, 81, 82 -> "con lluvia"
            71, 73, 75 -> "con nieve"
            95, 96, 99 -> "con tormenta"
            else -> "templado"
        }
    }

    /**
     * Inteligencia Artificial Gratuita Incorporada (Sin requerir API Key):
     * Permite que el tío pida recetas paso a paso ("cómo hacer huevo revuelto"), consejos cotidianos,
     * explicaciones o haga cualquier consulta general y reciba una respuesta inteligente en español argentino.
     */
    suspend fun askFreeBuiltInAi(userQuestion: String): String = withContext(Dispatchers.IO) {
        val norm = WhatsAppRepository.normalizeText(userQuestion)
        val wantsStepByStep = norm.contains("paso a paso") ||
            norm.contains("receta") ||
            norm.contains("como hacer") ||
            norm.contains("como hago") ||
            norm.contains("como se hace") ||
            norm.contains("como preparar") ||
            norm.contains("como preparo") ||
            norm.contains("explicame")

        val sysInstruction = if (wantsStepByStep) {
            "Sos un asistente de voz cálido en español rioplatense (Argentina) ayudando a una persona no vidente. " +
                "Explicá el paso a paso de forma muy clara, ordenada (Paso 1, Paso 2, etc.) y fácil de seguir de oído. No uses asteriscos ni Markdown."
        } else {
            "Sos un asistente de voz en español rioplatense (Argentina) para una persona no vidente. " +
                "Respondé de forma directa, natural y breve en 1 o 2 oraciones claras. No uses asteriscos ni Markdown."
        }

        // 1. Intentamos primero el endpoint OpenAI-compatible gratuito de Pollinations AI
        try {
            val payload = JSONObject().apply {
                put("model", "openai")
                put("messages", JSONArray()
                    .put(JSONObject().apply {
                        put("role", "system")
                        put("content", sysInstruction)
                    })
                    .put(JSONObject().apply {
                        put("role", "user")
                        put("content", userQuestion)
                    })
                )
            }
            val req = Request.Builder()
                .url("https://text.pollinations.ai/openai")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val content = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                        ?.trim()
                    if (!content.isNullOrBlank()) {
                        return@withContext content
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Segundo endpoint GET directo de Pollinations AI
        try {
            val fullPrompt = "$sysInstruction Pregunta: $userQuestion"
            val url = "https://text.pollinations.ai/${Uri.encode(fullPrompt)}?model=openai"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val txt = resp.body?.string()?.trim().orEmpty()
                    if (txt.isNotEmpty() && !txt.startsWith("<")) {
                        return@withContext txt
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 3. Respaldo final de noticias / Wikipedia si es una consulta de actualidad
        return@withContext searchFreeWebWithoutApiKey(userQuestion)
    }

    suspend fun searchFreeWebWithoutApiKey(rawQuery: String): String = withContext(Dispatchers.IO) {
        val clean = rawQuery
            .replace(Regex("(?i)^(buscame|busca|buscar|en google|googlea|que es|quien es|quien fue|como salio|cuando juega|decime)\\s+"), "")
            .trim()
        if (clean.isEmpty()) return@withContext "Decime qué querés buscar."

        val lower = rawQuery.lowercase()
        val isNewsOrSports = lower.contains("salio") ||
            lower.contains("salió") ||
            lower.contains("juega") ||
            lower.contains("partido") ||
            lower.contains("gano") ||
            lower.contains("ganó") ||
            lower.contains("dolar") ||
            lower.contains("dólar") ||
            lower.contains("noticia") ||
            lower.contains("ayer") ||
            lower.contains("hoy")

        if (isNewsOrSports) {
            val newsResult = fetchGoogleNewsArgentinaHeadline(clean)
            if (newsResult != null) return@withContext newsResult
        }

        val wikiResult = fetchWikipediaSummaryEs(clean)
        if (wikiResult != null) return@withContext wikiResult

        val newsFallback = fetchGoogleNewsArgentinaHeadline(clean)
        if (newsFallback != null) return@withContext newsFallback

        return@withContext "No te entendí, ¿repetís?"
    }

    private fun fetchWikipediaSummaryEs(query: String): String? {
        return try {
            val searchUrl = "https://es.wikipedia.org/w/api.php?action=query&list=search&srsearch=${Uri.encode(query)}&utf8=&format=json&srlimit=1"
            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "JarvisAccesible/1.0 (Android)")
                .build()

            val pageTitle = httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: return null)
                val searchArr = json.optJSONObject("query")?.optJSONArray("search")
                if (searchArr == null || searchArr.length() == 0) return null
                searchArr.optJSONObject(0)?.optString("title")
            } ?: return null

            val summaryUrl = "https://es.wikipedia.org/api/rest_v1/page/summary/${Uri.encode(pageTitle)}"
            val sumReq = Request.Builder()
                .url(summaryUrl)
                .header("User-Agent", "JarvisAccesible/1.0 (Android)")
                .build()

            httpClient.newCall(sumReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: return null)
                val extract = json.optString("extract", "").trim()
                if (extract.isEmpty()) return null
                extract.split(Regex("(?<=\\.)\\s+")).take(2).joinToString(" ")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchGoogleNewsArgentinaHeadline(query: String): String? {
        return try {
            val rssUrl = "https://news.google.com/rss/search?q=${Uri.encode(query)}&hl=es-419&gl=AR&ceid=AR:es-419"
            val req = Request.Builder().url(rssUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val xml = resp.body?.string() ?: return null
                val itemRegex = Regex("<item>.*?<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL))
                val matches = itemRegex.findAll(xml).take(2).mapNotNull { m ->
                    m.groupValues.getOrNull(1)
                        ?.replace("<![CDATA[", "")
                        ?.replace("]]>", "")
                        ?.replace("&quot;", "\"")
                        ?.replace("&amp;", "&")
                        ?.replace("&#39;", "'")
                        ?.substringBeforeLast(" - ")
                        ?.trim()
                }.toList()

                if (matches.isEmpty()) return null
                matches.joinToString(". ") + "."
            }
        } catch (_: Exception) {
            null
        }
    }
}
