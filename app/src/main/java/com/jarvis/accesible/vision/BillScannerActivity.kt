package com.jarvis.accesible.vision

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Actividad automática manos libres que se abre por ~1.5 segundos cuando el tío dice "¿Qué billete es este?".
 * Garantiza acceso a la cámara trasera con flash/linterna y enfoque automático incluso si el celular
 * estaba en la pantalla de inicio, dentro de otra app o con la pantalla bloqueada en Android 10 a 15.
 */
class BillScannerActivity : ComponentActivity() {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var hasCompleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableLockScreenVisibility()

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "MIRANDO EL BILLETE...",
                            color = Color(0xFFFFD600),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Mantené el billete frente a la cámara de atrás un segundo",
                            color = Color.White,
                            fontSize = 22.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        startCameraAndCapture()
    }

    private fun enableLockScreenVisibility() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @SuppressLint("MissingPermission")
    private fun startCameraAndCapture() {
        val thread = HandlerThread("JarvisBillCameraThread").also { it.start() }
        cameraThread = thread
        val handler = Handler(thread.looper)
        cameraHandler = handler

        // Timeout de seguridad de 6 segundos por si la cámara tarda en responder
        handler.postDelayed({
            if (!hasCompleted) {
                deliverResultAndClose(null)
            }
        }, 6000L)

        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backCameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()

            if (backCameraId == null) {
                deliverResultAndClose(null)
                return
            }

            val chars = manager.getCameraCharacteristics(backCameraId)
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val reader = ImageReader.newInstance(1280, 960, ImageFormat.JPEG, 2)
            imageReader = reader

            reader.setOnImageAvailableListener({ ir ->
                val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    deliverResultAndClose(bytes)
                } catch (_: Exception) {
                    deliverResultAndClose(null)
                } finally {
                    image.close()
                }
            }, handler)

            manager.openCamera(backCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewAndCapture(camera, reader.surface, hasFlash, sensorOrientation, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    deliverResultAndClose(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    deliverResultAndClose(null)
                }
            }, handler)
        } catch (_: Exception) {
            deliverResultAndClose(null)
        }
    }

    private fun createPreviewAndCapture(
        camera: CameraDevice,
        jpegSurface: Surface,
        hasFlash: Boolean,
        sensorOrientation: Int,
        handler: Handler
    ) {
        try {
            val texture = SurfaceTexture(10).apply {
                setDefaultBufferSize(1280, 960)
            }
            dummySurfaceTexture = texture
            val previewSurface = Surface(texture)
            dummySurface = previewSurface

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(previewSurface, jpegSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            // 1. Iniciamos vista previa con linterna/flash y autofoco continuo durante 1.2s para estabilizar luz y foco
                            val previewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface)
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                )
                                set(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON
                                )
                                if (hasFlash) {
                                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                                }
                            }
                            session.setRepeatingRequest(previewBuilder.build(), null, handler)

                            // 2. Tras 1.2 segundos de enfoque e iluminación, tomamos la foto nítida del billete
                            handler.postDelayed({
                                try {
                                    val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(jpegSurface)
                                        set(
                                            CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                        )
                                        set(
                                            CaptureRequest.CONTROL_AE_MODE,
                                            CaptureRequest.CONTROL_AE_MODE_ON
                                        )
                                        if (hasFlash) {
                                            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                                        }
                                        set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                                        set(CaptureRequest.JPEG_QUALITY, 90.toByte())
                                    }
                                    session.capture(captureBuilder.build(), null, handler)
                                } catch (_: Exception) {
                                    deliverResultAndClose(null)
                                }
                            }, 1250L)
                        } catch (_: Exception) {
                            deliverResultAndClose(null)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        deliverResultAndClose(null)
                    }
                },
                handler
            )
        } catch (_: Exception) {
            deliverResultAndClose(null)
        }
    }

    @Synchronized
    private fun deliverResultAndClose(jpegBytes: ByteArray?) {
        if (hasCompleted) return
        hasCompleted = true
        cleanupCamera()
        BillRecognizer.onCameraFrameCaptured(jpegBytes)
        runOnUiThread {
            finish()
        }
    }

    private fun cleanupCamera() {
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            dummySurface?.release()
        } catch (_: Exception) {
        }
        dummySurface = null

        try {
            dummySurfaceTexture?.release()
        } catch (_: Exception) {
        }
        dummySurfaceTexture = null

        try {
            cameraThread?.quitSafely()
        } catch (_: Exception) {
        }
        cameraThread = null
    }

    override fun onDestroy() {
        if (!hasCompleted) {
            hasCompleted = true
            cleanupCamera()
            BillRecognizer.onCameraFrameCaptured(null)
        } else {
            cleanupCamera()
        }
        super.onDestroy()
    }
}
