package com.jarvis.accesible.actions

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.jarvis.accesible.services.JarvisAccessibilityService
import com.jarvis.accesible.whatsapp.WhatsAppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

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
                putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Alarma de Jarvis" })
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val minFormatted = validMinute.toString().padStart(2, '0')
            "Listo, configuré la alarma para las $validHour:$minFormatted."
        } catch (e: Exception) {
            "No pude programar la alarma en el reloj del teléfono."
        }
    }

    /**
     * Apaga o descarta cualquier alarma que esté sonando y detiene también cualquier música,
     * video de YouTube o nota de voz que esté reproduciéndose.
     */
    fun stopAnythingPlayingOrRinging(context: Context): String {
        // 1. Detener notas de voz de WhatsApp internas
        WhatsAppRepository.stopAudioPlayback()

        // 2. Pausar/detener reproductores multimedia externos (YouTube, Spotify, Radio, etc.)
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

        // 3. Intentar descartar alarmas activas
        try {
            val dismissAlarm = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dismissAlarm)
        } catch (_: Exception) {
        }

        return "Listo, detuve lo que estaba sonando."
    }

    /**
     * Busca en YouTube el primer ID de video real que coincida con el pedido del tío
     * y lo reproduce directamente sin quedarse trabado en la lista de búsqueda.
     */
    suspend fun playOnYouTubeDirectly(context: Context, searchQuery: String): String {
        val cleanQuery = searchQuery.trim()
        if (cleanQuery.isEmpty()) return "Decime qué canción o video querés escuchar en YouTube."

        val videoId = withContext(Dispatchers.IO) {
            fetchFirstYouTubeVideoId(cleanQuery)
        }

        return try {
            // Activamos la ayuda del Servicio de Accesibilidad por si YouTube requiere tocar el video o saltar anuncio
            JarvisAccessibilityService.requestAutoPlayInYouTube()

            if (videoId != null) {
                // Abre directamente la reproducción del video encontrado
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
                "Reproduciendo $cleanQuery en YouTube."
            } else {
                // Respaldo: abre la búsqueda y el Servicio de Accesibilidad toca el primer video automáticamente
                val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", cleanQuery)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(searchIntent)
                "Abriendo $cleanQuery en YouTube."
            }
        } catch (e: Exception) {
            "No pude abrir la aplicación de YouTube."
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
            ?: return "No encontré a $contactName en la agenda de contactos para llamarlo."

        return try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(contact.second)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)

            // Activar el altavoz automáticamente a los 1.5s y 3.5s de iniciada la llamada
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

            "Llamando por teléfono a ${contact.first} en altavoz."
        } catch (e: SecurityException) {
            "Falta activar el permiso de Llamadas Telefónicas en la pantalla de configuración de Jarvis."
        } catch (e: Exception) {
            "No pude iniciar la llamada a ${contact.first}."
        }
    }

    /**
     * Permite atender una llamada entrante o cortar una llamada en curso por voz o con el botón físico.
     */
    @SuppressLint("MissingPermission")
    fun answerOrEndPhoneCall(context: Context, endCall: Boolean): String {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            if (endCall) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    telecomManager.endCall()
                }
                "Llamada finalizada."
            } else {
                @Suppress("DEPRECATION")
                telecomManager.acceptRingingCall()
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                mainHandler.postDelayed({
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }, 1000L)
                "Llamada atendida en altavoz."
            }
        } catch (_: Exception) {
            "No pude gestionar la llamada telefónica."
        }
    }

    /**
     * Informa hora actual, nivel de batería, si está enchufado cargando y estado de internet.
     */
    fun getPhoneStatusReport(context: Context): String {
        val now = Date()
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val dateStr = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "AR")).format(now)

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val netCaps = cm.getNetworkCapabilities(cm.activeNetwork)
        val connectionType = when {
            netCaps == null -> "sin conexión a internet"
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "conectado a Wi-Fi"
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "conectado con datos móviles"
            else -> "conectado a internet"
        }

        val batteryText = if (batteryPct >= 0) {
            "La batería está al $batteryPct por ciento" +
                if (isCharging) " y el teléfono está enchufado cargando." else "."
        } else {
            ""
        }

        return "Son las $timeStr del $dateStr. $batteryText El teléfono está $connectionType."
    }
}
