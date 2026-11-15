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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object SystemActionsController {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Configura una alarma en el reloj nativo de Android con EXTRA_SKIP_UI = true
     * para que nunca pida tocar la pantalla.
     */
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

    /**
     * Apaga o descarta cualquier alarma que esté sonando y detiene también cualquier música,
     * video de YouTube o nota de voz que esté reproduciéndose.
     */
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
     * Busca en YouTube el primer ID de video real que coincida con el pedido del tío
     * y lo reproduce directamente sin quedarse trabado en la lista de búsqueda.
     */
    suspend fun playOnYouTubeDirectly(context: Context, searchQuery: String): String {
        val cleanQuery = searchQuery.trim()
        if (cleanQuery.isEmpty()) return "Decime qué querés escuchar."

        val videoId = withContext(Dispatchers.IO) {
            fetchFirstYouTubeVideoId(cleanQuery)
        }

        return try {
            JarvisAccessibilityService.requestAutoPlayInYouTube()

            if (videoId != null) {
                val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(appIntent)
                } catch (_: Exception) {
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/watch?v=$videoId")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                }
                "Poniendo $cleanQuery."
            } else {
                val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", cleanQuery)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(searchIntent)
                "Abriendo $cleanQuery en YouTube."
            }
        } catch (e: Exception) {
            "No pude abrir YouTube."
        }
    }

    private fun fetchFirstYouTubeVideoId(query: String): String? {
        return try {
            val encoded = Uri.encode(query)
            val request = Request.Builder()
                .url("https://www.youtube.com/results?search_query=$encoded")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept-Language", "es-AR,es;q=0.9")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null
                val regex = Regex("\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
                regex.find(html)?.groupValues?.getOrNull(1)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Realiza una llamada telefónica directa al contacto indicado y activa el altavoz manos libres.
     */
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

    /**
     * Solo responde la hora actual, cortito y directo.
     */
    fun getCurrentTimeOnly(): String {
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return "Son las $timeStr."
    }

    /**
     * Solo responde qué día y fecha es hoy.
     */
    fun getCurrentDateOnly(): String {
        val dateStr = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "AR")).format(Date())
        return "Hoy es $dateStr."
    }

    /**
     * Solo responde el porcentaje de batería (y si está cargando).
     */
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

    /**
     * Solo responde el estado de conexión si el usuario pregunta específicamente por Wi-Fi o internet.
     */
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
     * Consulta la temperatura actual y el pronóstico del día usando la ubicación GPS del teléfono
     * (con respaldo por ubicación de red si el GPS está apagado). No requiere ninguna API Key.
     */
    suspend fun getWeatherReport(context: Context, includeDailyForecast: Boolean): String = withContext(Dispatchers.IO) {
        val coords = getBestDeviceCoordinates(context)
        val lat = coords.first
        val lon = coords.second

        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=1"

        return@withContext try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "No pude consultar el clima ahora."
                val body = response.body?.string() ?: return@withContext "No pude consultar el clima."
                val json = JSONObject(body)

                val current = json.optJSONObject("current")
                val tempNow = current?.optDouble("temperature_2m", Double.NaN) ?: Double.NaN
                val weatherCode = current?.optInt("weather_code", 0) ?: 0
                val conditionText = describeWeatherCode(weatherCode)

                val tempRounded = if (!tempNow.isNaN()) tempNow.roundToInt() else null

                if (!includeDailyForecast && tempRounded != null) {
                    return@withContext "Hacen $tempRounded grados, $conditionText."
                }

                val daily = json.optJSONObject("daily")
                val maxArr = daily?.optJSONArray("temperature_2m_max")
                val minArr = daily?.optJSONArray("temperature_2m_min")
                val rainArr = daily?.optJSONArray("precipitation_probability_max")

                val maxTemp = maxArr?.optDouble(0, Double.NaN)?.takeIf { !it.isNaN() }?.roundToInt()
                val minTemp = minArr?.optDouble(0, Double.NaN)?.takeIf { !it.isNaN() }?.roundToInt()
                val rainProb = rainArr?.optInt(0, 0) ?: 0

                val sb = StringBuilder()
                if (tempRounded != null) {
                    sb.append("Hacen $tempRounded grados, $conditionText. ")
                }
                if (maxTemp != null && minTemp != null) {
                    sb.append("Hoy máxima de $maxTemp y mínima de $minTemp. ")
                }
                if (rainProb >= 40) {
                    sb.append("Probabilidad de lluvia del $rainProb por ciento.")
                } else {
                    sb.append("Sin lluvias para hoy.")
                }
                sb.toString().trim()
            }
        } catch (_: Exception) {
            "No hay internet para ver el clima."
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

        // Respaldo automático por IP si el GPS del teléfono estaba desactivado
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

        // Respaldo por defecto: Buenos Aires, Argentina
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
     * Buscador gratuito sin API Key (Wikipedia en Español + Google Noticias Argentina)
     * para que las búsquedas en Google funcionen incluso si la clave de Gemini no está configurada.
     */
    suspend fun searchFreeWebWithoutApiKey(rawQuery: String): String = withContext(Dispatchers.IO) {
        val clean = rawQuery
            .replace(Regex("(?i)^(buscame|busca|buscar|en google|googlea|que es|quien es|quien fue|como salio|cuando juega|decime)\\s+"), "")
            .trim()
        if (clean.isEmpty()) return@withContext "Decime qué querés buscar."

        // 1. Si parece noticia actual o deporte ("cómo salió", "cuándo juega", "noticias", "dólar", etc.), buscamos en Google News Argentina
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

        // 2. Buscamos resumen directo en Wikipedia en Español
        val wikiResult = fetchWikipediaSummaryEs(clean)
        if (wikiResult != null) return@withContext wikiResult

        // 3. Si no estaba en Wikipedia, buscamos titular en Google Noticias Argentina
        val newsFallback = fetchGoogleNewsArgentinaHeadline(clean)
        if (newsFallback != null) return@withContext newsFallback

        return@withContext "No encontré resultados para $clean."
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
                // Tomamos las primeras 2 oraciones para que sea corto y claro
                val sentences = extract.split(Regex("(?<=\\.)\\s+")).take(2).joinToString(" ")
                sentences
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
