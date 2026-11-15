package com.jarvis.accesible.vision

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.jarvis.accesible.ai.GeminiAgent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Reconocedor de billetes de Pesos Argentinos (10, 20, 50, 100, 200, 500, 1000, 2000, 10000, 20000).
 * Combina:
 * 1. Captura automática manos libres con linterna/flash y autofoco (BillScannerActivity).
 * 2. Visión Multimodal con Google Gemini 2.5 Flash (reconoce números, colores, próceres y animales autóctonos aunque el billete esté doblado).
 * 3. Reconocimiento óptico local (ML Kit Text Recognition) 100% offline como respaldo instantáneo si no hay internet.
 */
object BillRecognizer {

    @Volatile
    private var pendingCaptureDeferred: CompletableDeferred<ByteArray?>? = null

    fun onCameraFrameCaptured(jpegBytes: ByteArray?) {
        pendingCaptureDeferred?.complete(jpegBytes)
    }

    suspend fun scanAndIdentifyBanknote(
        context: Context,
        geminiAgent: GeminiAgent,
        onShutterTaken: () -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return@withContext "Todavía falta activar el permiso de Cámara en la configuración de Jarvis."
        }

        val deferred = CompletableDeferred<ByteArray?>()
        pendingCaptureDeferred = deferred

        // Lanzamos la captura automática manos libres con la cámara trasera y flash
        val intent = Intent(context, BillScannerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        context.startActivity(intent)

        val rawJpeg = withTimeoutOrNull(7500L) {
            deferred.await()
        }
        pendingCaptureDeferred = null

        if (rawJpeg == null || rawJpeg.isEmpty()) {
            return@withContext "No pude tomar la foto con la cámara. Intentá de nuevo."
        }

        // Emitimos un pitido/vibración apenas se tomó la foto para que el tío sepa que ya puede bajar la mano
        withContext(Dispatchers.Main) {
            onShutterTaken()
        }

        val bitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size)
            ?: return@withContext "No se pudo leer la imagen de la cámara. Intentá de nuevo."

        val optimizedJpeg = compressBitmapForVision(bitmap)

        // 1. Intentamos primero con Gemini 2.5 Flash Visión (reconoce números, próceres, animales y varios billetes)
        val geminiAnswer = geminiAgent.analyzeArgentineBanknote(optimizedJpeg)
        if (!geminiAnswer.isNullOrBlank()) {
            return@withContext geminiAnswer
        }

        // 2. Respaldo 100% Offline en el dispositivo (ML Kit OCR) por si el tío está en la calle sin internet
        val offlineAnswer = recognizeBanknoteOfflineWithMlKit(bitmap)
        if (offlineAnswer != null) {
            return@withContext offlineAnswer
        }

        return@withContext "No llegué a ver bien el billete. Ponelo a unos veinte centímetros de la cámara de atrás e intentá de nuevo."
    }

    private fun compressBitmapForVision(original: Bitmap): ByteArray {
        val maxDim = 1280
        val width = original.width
        val height = original.height
        val scaled = if (width > maxDim || height > maxDim) {
            val ratio = maxDim.toFloat() / maxOf(width, height).toFloat()
            Bitmap.createScaledBitmap(
                original,
                (width * ratio).toInt().coerceAtLeast(1),
                (height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            original
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    /**
     * Respaldo sin conexión a internet: lee el texto e importe impreso en el billete argentino.
     */
    private suspend fun recognizeBanknoteOfflineWithMlKit(bitmap: Bitmap): String? =
        suspendCancellableCoroutine { cont ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val detected = parseArgentineDenominationFromText(visionText.text)
                    if (cont.isActive) cont.resume(detected)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }

    private fun parseArgentineDenominationFromText(rawText: String): String? {
        if (rawText.isBlank()) return null
        val upper = rawText.uppercase()
        val tokens = Regex("\\b\\d+(?:[.,]\\d+)?\\b").findAll(upper).map {
            it.value.replace(".", "").replace(",", "")
        }.toList()

        return when {
            upper.contains("VEINTE MIL") || tokens.contains("20000") -> "Es de 20000"
            upper.contains("DIEZ MIL") || tokens.contains("10000") -> "Es de 10000"
            upper.contains("DOS MIL") || tokens.contains("2000") -> "Es de 2000"
            upper.contains("MIL PESOS") || tokens.contains("1000") -> "Es de 1000"
            upper.contains("QUINIENTOS") || tokens.contains("500") -> "Es de 500"
            upper.contains("DOSCIENTOS") || tokens.contains("200") -> "Es de 200"
            upper.contains("CIEN PESOS") || tokens.contains("100") -> "Es de 100"
            upper.contains("CINCUENTA") || tokens.contains("50") -> "Es de 50"
            upper.contains("VEINTE PESOS") || tokens.contains("20") -> "Es de 20"
            upper.contains("DIEZ PESOS") || tokens.contains("10") -> "Es de 10"
            else -> null
        }
    }
}
