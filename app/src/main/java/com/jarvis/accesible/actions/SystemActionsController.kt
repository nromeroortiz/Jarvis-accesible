package com.jarvis.accesible.actions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
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

    @Volatile
    private var isFlashlightCurrentlyOn = false

    @Volatile
    private var isTorchCallbackRegistered = false

    fun openWhatsAppInPlayStore(context: Context, onStatusSpoken: (String) -> Unit) {
        JarvisAccessibilityService.requestAutoUpdateWhatsApp(onStatusSpoken)
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.whatsapp")).apply {
                setPackage("com.android.vending")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(marketIntent)
        } catch (_: Exception) {
            try {
                val webIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.whatsapp")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            } catch (_: Exception) {
                onStatusSpoken("No pude abrir la Play Store.")
            }
        }
    }

    fun setOrCheckFlashlight(context: Context, turnOn: Boolean?): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (!isTorchCallbackRegistered) {
                isTorchCallbackRegistered = true
                try {
                    cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                            isFlashlightCurrentlyOn = enabled
                        }
                    }, mainHandler)
                } catch (_: Exception) {
                }
            }

            val backCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (backCameraId == null) {
                return "Este teléfono no tiene linterna."
            }

            when (turnOn) {
                true -> {
                    cameraManager.setTorchMode(backCameraId, true)
                    isFlashlightCurrentlyOn = true
                    "Linterna prendida."
                }
                false -> {
                    cameraManager.setTorchMode(backCameraId, false)
                    isFlashlightCurrentlyOn = false
                    "Linterna apagada."
                }
                null -> {
                    val newState = !isFlashlightCurrentlyOn
                    cameraManager.setTorchMode(backCameraId, newState)
                    isFlashlightCurrentlyOn = newState
                    if (newState) "Linterna prendida." else "Linterna apagada."
                }
            }
        } catch (e: Exception) {
            "No pude controlar la linterna."
        }
    }

    fun setSilentAlarm(context: Context, hour: Int, minute: Int, label: String): String {
        val validHour = hour.coerceIn(0, 23)
        val validMin = minute.coerceIn(0, 59)

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, validHour)
                putExtra(AlarmClock.EXTRA_MINUTES, validMin)
                putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Wally" })
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val minFormatted = validMin.toString().padStart(2, '0')
            "Alarma puesta a las $validHour:$minFormatted."
        } catch (e: Exception) {
            "No pude configurar la alarma."
        }
    }

    /**
     * Busca la canción en YouTube, obtiene el primer videoId real y lo abre reproduciéndose.
     * Además activa el servicio de accesibilidad para que, si se abre la lista de YouTube, toque el primer video automáticamente.
     */
    suspend fun playOnYouTubeDirectly(context: Context, searchQuery: String): String = withContext(Dispatchers.IO) {
        val cleanQuery = searchQuery.trim()
        if (cleanQuery.isEmpty()) {
            return@withContext "Decime qué querés escuchar."
        }

        val videoId = fetchFirstYouTubeVideoId(cleanQuery)
        JarvisAccessibilityService.requestAutoPlayInYouTube()

        return@withContext withContext(Dispatchers.Main) {
            try {
                if (videoId != null) {
                    val watchUri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
                    val watchIntent = Intent(Intent.ACTION_VIEW, watchUri).apply {
                        setPackage("com.google.android.youtube")
                        putExtra("force_fullscreen", false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(watchIntent)
                    } catch (_: Exception) {
                        val browserIntent = Intent(Intent.ACTION_VIEW, watchUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(browserIntent)
                    }
                } else {
                    val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                        setPackage("com.google.android.youtube")
                        putExtra("query", cleanQuery)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(searchIntent)
                    } catch (_: Exception) {
                        val webUri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleanQuery)}")
                        context.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
                "Poniendo $cleanQuery."
            } catch (e: Exception) {
                "No pude abrir YouTube."
            }
        }
    }

    private fun fetchFirstYouTubeVideoId(query: String): String? {
        return try {
            val url = "https://www.youtube.com/results?search_query=${Uri.encode(query)}&hl=es"
            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
                .header("Accept-Language", "es-AR,es;q=0.9")
                .header("Cookie", "CONSENT=YES+cb.20210328-17-p0.es+FX+999")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null
                val rendererRegex = Regex("\"videoRenderer\":\\{\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
                val match1 = rendererRegex.find(html)
                if (match1 != null) {
                    return match1.groupValues[1]
                }
                val fallbackRegex = Regex("\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
                fallbackRegex.find(html)?.groupValues?.getOrNull(1)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun stopAnythingPlayingOrRinging(context: Context): String {
        WhatsAppRepository.stopAudioPlayback()
        com.jarvis.accesible.services.JarvisAccessibilityService.instance?.pauseAnyPlayingVoiceNoteOnScreen()

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)
            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)

            val stopDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP)
            val stopUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP)
            audioManager.dispatchMediaKeyEvent(stopDown)
            audioManager.dispatchMediaKeyEvent(stopUp)
        } catch (_: Exception) {
        }

        try {
            val dismissIntent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dismissIntent)
        } catch (_: Exception) {
        }

        return "Listo."
    }

    @SuppressLint("MissingPermission")
    fun makePhoneCallOnSpeaker(context: Context, contactQuery: String): String {
        val contact = WhatsAppRepository.findContactByName(context, contactQuery)
            ?: return "No encontré a $contactQuery en tus contactos."

        return try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(contact.second)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            for (delayMs in listOf(1200L, 2500L, 4000L)) {
                mainHandler.postDelayed({
                    try {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                    } catch (_: Exception) {
                    }
                }, delayMs)
            }

            "Llamando a ${contact.first}."
        } catch (e: SecurityException) {
            "Falta el permiso de llamadas."
        } catch (e: Exception) {
            "No pude llamar."
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
        } catch (e: Exception) {
            "No pude gestionar la llamada."
        }
    }

    fun getCurrentTimeOnly(): String {
        val localeAr = Locale("es", "AR")
        val timeStr = SimpleDateFormat("HH:mm", localeAr).format(Date())
        return "Son las $timeStr."
    }

    fun getCurrentDateOnly(): String {
        val localeAr = Locale("es", "AR")
        val dayStr = SimpleDateFormat("EEEE d 'de' MMMM", localeAr).format(Date())
        return "Hoy es $dayStr."
    }

    fun getBatteryOnly(context: Context): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val statusInt = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusInt == BatteryManager.BATTERY_STATUS_FULL
        val chargeStr = if (isCharging) ", cargando" else ""
        return "Tenés $batteryPct por ciento de batería$chargeStr."
    }

    fun getConnectionOnly(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return when {
            caps == null -> "No tenés internet."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Estás conectado a Wi-Fi."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Estás con datos móviles."
            else -> "Estás conectado a internet."
        }
    }

    /**
     * Pronóstico extendido de 7 días por GPS (Open-Meteo, gratuito y sin clave).
     * Soporta números en dígitos ("3") y en letras ("dos", "tres", "cuatro", "cinco", "seis", "siete").
     */
    suspend fun getFlexibleWeatherReport(context: Context, rawUserQuery: String): String = withContext(Dispatchers.IO) {
        val coords = getBestDeviceCoordinates(context)
        val lat = coords.first
        val lon = coords.second
        val norm = WhatsAppRepository.normalizeText(rawUserQuery)

        try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&forecast_days=7" +
                "&timezone=America%2FArgentina%2FBuenos_Aires"

            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext "No pude consultar el clima ahora."
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val current = json.optJSONObject("current")
                val daily = json.optJSONObject("daily")

                val tempNow = current?.optDouble("temperature_2m", Double.NaN)?.takeIf { !it.isNaN() }?.roundToInt()
                val codeNow = current?.optInt("weather_code", 0) ?: 0
                val condNow = describeWeatherCode(codeNow)

                val datesArr = daily?.optJSONArray("time")
                val codesArr = daily?.optJSONArray("weather_code")
                val maxArr = daily?.optJSONArray("temperature_2m_max")
                val minArr = daily?.optJSONArray("temperature_2m_min")
                val rainArr = daily?.optJSONArray("precipitation_probability_max")

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

                if (norm.contains("pasado manana")) {
                    return@withContext formatSingleDayForecast("Pasado mañana", 2, codesArr, maxArr, minArr, rainArr)
                }

                if (norm.contains("manana")) {
                    return@withContext formatSingleDayForecast("Mañana", 1, codesArr, maxArr, minArr, rainArr)
                }

                val targetDow = parseRequestedDayOfWeek(norm)
                if (targetDow != null && datesArr != null) {
                    for (i in 1 until datesArr.length()) {
                        val checkCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, i) }
                        if (checkCal.get(Calendar.DAY_OF_WEEK) == targetDow) {
                            val label = getDayNameForOffset(i).replaceFirstChar { it.uppercase() }
                            return@withContext formatSingleDayForecast(label, i, codesArr, maxArr, minArr, rainArr)
                        }
                    }
                }

                val wantsOnlyCurrentTemp = (norm.contains("cuantos grados") || norm.contains("que temperatura")) &&
                    !norm.contains("hoy") && !norm.contains("maxima") && !norm.contains("clima")
                if (wantsOnlyCurrentTemp && tempNow != null) {
                    return@withContext "Hacen $tempNow grados, $condNow."
                }

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
     * Inteligencia Artificial Gratuita Incorporada (Sin Wikipedia para no tirar definiciones al azar):
     * 1. Si pide una receta o paso a paso clásico (ej. huevo revuelto, arroz, fideos, omelette, mate, etc.),
     *    tiene respuesta instantánea paso a paso pensada para una persona ciega.
     * 2. Para cualquier otra pregunta o conversación, consulta la IA conversacional (gen.pollinations.ai / text.pollinations.ai).
     * 3. Si es una noticia de actualidad o partido, consulta Google News Argentina.
     * 4. Si no se entendió la frase, responde corto: "No te entendí, ¿me lo repetís?" (nunca definiciones de Wikipedia).
     */
    suspend fun askFreeBuiltInAi(userQuestion: String): String = withContext(Dispatchers.IO) {
        val norm = WhatsAppRepository.normalizeText(userQuestion)

        // 0. Recetario y guía práctica instantánea paso a paso para personas no videntes
        val instantStepByStep = getInstantStepByStepGuide(norm)
        if (instantStepByStep != null) {
            return@withContext instantStepByStep
        }

        val wantsStepByStep = norm.contains("paso a paso") ||
            norm.contains("receta") ||
            norm.contains("como hacer") ||
            norm.contains("como hago") ||
            norm.contains("como se hace") ||
            norm.contains("como preparar") ||
            norm.contains("como preparo") ||
            norm.contains("explicame") ||
            norm.contains("enseñame") ||
            norm.contains("ensenname")

        val sysInstruction = if (wantsStepByStep) {
            "Sos Wally, un asistente de voz en español rioplatense (Argentina) ayudando a una persona ciega. " +
                "Explicá exactamente lo que pidió paso a paso (Paso 1, Paso 2, etc.) de forma clara, segura y fácil de seguir de oído. No uses asteriscos ni Markdown."
        } else {
            "Sos Wally, un asistente de voz en español rioplatense (Argentina) para una persona ciega. " +
                "Respondé exactamente a lo que te preguntó en 1 o 2 oraciones cortas y claras. No uses asteriscos ni Markdown."
        }

        // 1. Endpoint OpenAI-compatible actualizado (gen.pollinations.ai/v1/chat/completions y text.pollinations.ai/openai)
        val chatUrls = listOf(
            "https://gen.pollinations.ai/v1/chat/completions",
            "https://text.pollinations.ai/openai"
        )
        for (endpoint in chatUrls) {
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
                    .url(endpoint)
                    .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
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
                        if (isValidAiAnswer(content)) {
                            return@withContext cleanAiOutput(content!!)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 2. Endpoint GET directo (gen.pollinations.ai/text/... y text.pollinations.ai/...)
        val fullPrompt = "$sysInstruction Consulta: $userQuestion"
        val getUrls = listOf(
            "https://gen.pollinations.ai/text/${Uri.encode(fullPrompt)}?model=openai",
            "https://text.pollinations.ai/${Uri.encode(fullPrompt)}?model=openai"
        )
        for (url in getUrls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val txt = resp.body?.string()?.trim()
                        if (isValidAiAnswer(txt)) {
                            return@withContext cleanAiOutput(txt!!)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Solo si preguntó explícitamente por noticias, partidos o el dólar, consultamos titulares de Google News Argentina
        if (isExplicitNewsOrSportsQuery(norm)) {
            val news = fetchGoogleNewsArgentinaHeadline(userQuestion)
            if (news != null) return@withContext news
        }

        return@withContext "No te entendí bien, ¿me lo repetís?"
    }

    private fun isValidAiAnswer(text: String?): Boolean {
        if (text.isNullOrBlank() || text.length < 4) return false
        val lower = text.lowercase()
        if (lower.startsWith("<") || lower.startsWith("{")) return false
        if (lower.contains("important notice") || lower.contains("pollinations") || lower.contains("deprecated")) return false
        if (lower.contains("rate limit") || lower.contains("bad gateway") || lower.contains("cloudflare")) return false
        return true
    }

    private fun cleanAiOutput(text: String): String {
        return text
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun getInstantStepByStepGuide(norm: String): String? {
        val isRecipeOrHowTo = norm.contains("huevo") ||
            norm.contains("paso a paso") ||
            norm.contains("como hacer") ||
            norm.contains("como hago") ||
            norm.contains("como se hace") ||
            norm.contains("como preparar") ||
            norm.contains("como preparo") ||
            norm.contains("receta")

        if (!isRecipeOrHowTo) return null

        return when {
            norm.contains("huevo revuelto") || norm.contains("huevos revueltos") -> {
                "Para hacer huevo revuelto paso a paso: " +
                    "Paso 1: Cascá dos o tres huevos en un bol o taza, agregá una pizca de sal y batilos bien con un tenedor. " +
                    "Paso 2: Poné una sartén a fuego bajo con un chorrito de aceite o un pedacito de manteca durante medio minuto. " +
                    "Paso 3: Volcá los huevos batidos en la sartén y revolvé despacio y seguido con cuchara de madera o espátula durante unos dos minutos, hasta que sientas que espesaron y quedaron cremosos. " +
                    "Paso 4: Apagá el fuego enseguida para que no se sequen y servilos."
            }
            norm.contains("huevo duro") || norm.contains("huevos duros") || norm.contains("hervir huevo") -> {
                "Para hacer huevo duro paso a paso: " +
                    "Paso 1: Poné los huevos con cuidado en una olla chica y cubrilos con agua fría de la canilla. " +
                    "Paso 2: Prendé el fuego medio. Cuando escuches que el agua empieza a hervir con burbujas, contá 10 minutos (podes pedirme que te ponga una alarma en 10 minutos). " +
                    "Paso 3: Apagá el fuego, pasalos a agua fría un par de minutos para no quemarte y pelalos."
            }
            norm.contains("omelette") || norm.contains("omelet") || norm.contains("tortilla francesa") -> {
                "Para hacer un omelette paso a paso: " +
                    "Paso 1: Batí dos huevos en un bol con una pizca de sal. " +
                    "Paso 2: Calentá una sartén a fuego medio-bajo con un poquito de aceite o manteca y volcá el huevo. " +
                    "Paso 3: Dejalo cocinar dos minutos sin revolver hasta que cuaje la base, agregá queso o jamón en el medio, doblalo al medio con la espátula y apagá el fuego."
            }
            norm.contains("arroz") && (norm.contains("hacer") || norm.contains("hervir") || norm.contains("paso") || norm.contains("receta") || norm.contains("preparar")) -> {
                "Para hacer arroz blanco sin que se pase: " +
                    "Paso 1: Por cada taza de arroz, poné dos tazas de agua y una cucharadita de sal en una olla. " +
                    "Paso 2: Llevá a fuego fuerte hasta que escuches que hierve, y ahí bajá el fuego al mínimo. " +
                    "Paso 3: Dejalo cocinar entre 12 y 15 minutos hasta que absorba el agua, apagá el fuego y dejalo reposar tapado 3 minutos."
            }
            norm.contains("fideos") || norm.contains("pasta") -> {
                "Para hacer fideos paso a paso: " +
                    "Paso 1: Llená una olla con abundante agua y una cucharada de sal, y ponela a fuego fuerte tapada. " +
                    "Paso 2: Cuando escuches que el agua hierve fuerte, destapá con cuidado del vapor, echá los fideos y revolvé una vez con tenedor largo o cuchara de madera. " +
                    "Paso 3: Cocinalos entre 8 y 10 minutos según el paquete, apagá el fuego y colalos con cuidado en la pileta."
            }
            norm.contains("pure") && norm.contains("papa") -> {
                "Para hacer puré de papas paso a paso: " +
                    "Paso 1: Pelá y cortá las papas en cubos medianos, ponelas en una olla cubiertas de agua fría con sal y hervilas unos 20 minutos hasta que al pincharlas con un tenedor estén blandas. " +
                    "Paso 2: Escurrí el agua con cuidado, pisalas en caliente con el pisapapas y agregales un pedacito de manteca y un chorrito de leche revolviendo hasta que quede cremoso."
            }
            else -> null
        }
    }

    private fun isExplicitNewsOrSportsQuery(norm: String): Boolean {
        return norm.contains("como salio") ||
            norm.contains("cuando juega") ||
            norm.contains("partido") ||
            norm.contains("quien gano") ||
            norm.contains("dolar") ||
            norm.contains("noticias")
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
