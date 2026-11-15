package com.jarvis.accesible.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jarvis.accesible.core.AppPrefs
import com.jarvis.accesible.core.JarvisCoordinator
import com.jarvis.accesible.services.JarvisAccessibilityService
import com.jarvis.accesible.services.JarvisForegroundService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisForegroundService.startServiceIfPossible(this)

        // Si se invocó como asistente predeterminado del sistema (manteniendo botón inicio/encendido), escucha directo
        if (intent?.action == Intent.ACTION_ASSIST) {
            JarvisCoordinator.getInstance(this).triggerHandsFreeListening(greetBeforeListening = false)
        }

        setContent {
            MaterialTheme {
                JarvisMainScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) {
            JarvisCoordinator.getInstance(this).triggerHandsFreeListening(greetBeforeListening = false)
        }
    }
}

@Composable
fun JarvisMainScreen() {
    val context = LocalContext.current
    val coordinator = remember { JarvisCoordinator.getInstance(context) }
    var showSetupPanel by remember { mutableStateOf(false) }

    var apiKey by remember { mutableStateOf(AppPrefs.getGeminiApiKey(context)) }
    var wakeWordEnabled by remember { mutableStateOf(AppPrefs.isWakeWordEnabled(context)) }
    var volumeTriggerEnabled by remember { mutableStateOf(AppPrefs.isVolumeButtonTriggerEnabled(context)) }

    var isAccessibilityOk by remember { mutableStateOf(checkAccessibilityEnabled(context)) }
    var isNotificationOk by remember { mutableStateOf(checkNotificationListenerEnabled(context)) }
    var isAllFilesOk by remember { mutableStateOf(checkAllFilesPermission()) }
    var isBatteryOk by remember { mutableStateOf(checkBatteryOptimizationIgnored(context)) }
    var isRuntimePermsOk by remember { mutableStateOf(checkRuntimePermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        isRuntimePermsOk = checkRuntimePermissions(context)
        JarvisForegroundService.startServiceIfPossible(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityOk = checkAccessibilityEnabled(context)
                isNotificationOk = checkNotificationListenerEnabled(context)
                isAllFilesOk = checkAllFilesPermission()
                isBatteryOk = checkBatteryOptimizationIgnored(context)
                isRuntimePermsOk = checkRuntimePermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .padding(16.dp)
    ) {
        // Barra superior para alternar entre el Botón Gigante del tío y el Panel de Configuración del sobrino
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Jarvis Accesible",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = { showSetupPanel = !showSetupPanel },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D))
            ) {
                Text(
                    text = if (showSetupPanel) "Ver Botón Gigante" else "Configuración (Sobrino)",
                    color = Color(0xFFFFD600),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!showSetupPanel) {
            // MODO TÍO: Botón gigante de extremo a extremo de alto contraste
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFFD600), shape = RoundedCornerShape(28.dp))
                    .semantics {
                        contentDescription =
                            "Hablar con el asistente Jarvis. Tocá cualquier parte de la pantalla o presioná dos veces el botón de Subir Volumen."
                    }
                    .clickable {
                        coordinator.triggerHandsFreeListening(greetBeforeListening = false)
                    }
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "TOCÁ ACÁ PARA HABLAR",
                        color = Color.Black,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        lineHeight = 44.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "O presioná 2 veces el botón físico de SUBIR VOLUMEN sin mirar la pantalla",
                        color = Color(0xFF1A1A1A),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // MODO CONFIGURACIÓN INICIAL (Para el sobrino)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "1. Clave de Google Gemini (IA)",
                            color = Color(0xFFFFD600),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                AppPrefs.setGeminiApiKey(context, it)
                            },
                            label = {
                                Text(
                                    "Pegá 1 o varias Gemini API Keys separadas por coma",
                                    color = Color.LightGray
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            maxLines = 3
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "2. Cómo activa tu tío al asistente",
                            color = Color(0xFFFFD600),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Doble toque en Subir Volumen (Manos libres)",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Toca dos veces Subir Volumen, suena un pitido y habla sin mantener apretado.",
                                    color = Color.LightGray,
                                    fontSize = 13.sp
                                )
                            }
                            Switch(
                                checked = volumeTriggerEnabled,
                                onCheckedChange = {
                                    volumeTriggerEnabled = it
                                    AppPrefs.setVolumeButtonTriggerEnabled(context, it)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Activar también por voz (\"Hola Jarvis\")",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Se puede apagar acá o por voz para ahorrar batería.",
                                    color = Color.LightGray,
                                    fontSize = 13.sp
                                )
                            }
                            Switch(
                                checked = wakeWordEnabled,
                                onCheckedChange = {
                                    wakeWordEnabled = it
                                    AppPrefs.setWakeWordEnabled(context, it)
                                    coordinator.speechInput.syncWakeWordLoop()
                                }
                            )
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "3. Permisos por única vez (Dejar los 5 en VERDE)",
                            color = Color(0xFFFFD600),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        PermissionRow(
                            title = "A. Micrófono, Cámara (Billetes), Contactos y Llamadas",
                            isGranted = isRuntimePermsOk,
                            onClick = {
                                val perms = mutableListOf(
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.READ_CONTACTS,
                                    Manifest.permission.CALL_PHONE,
                                    Manifest.permission.READ_PHONE_STATE
                                )
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                                    perms.add(Manifest.permission.READ_MEDIA_AUDIO)
                                } else {
                                    perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                                permissionLauncher.launch(perms.toTypedArray())
                            }
                        )

                        PermissionRow(
                            title = "B. Servicio de Accesibilidad (Botón físico y clic automático en WhatsApp)",
                            isGranted = isAccessibilityOk,
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        )

                        PermissionRow(
                            title = "C. Lector de Notificaciones (Leer y responder WhatsApp)",
                            isGranted = isNotificationOk,
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        )

                        PermissionRow(
                            title = "D. Acceso a Archivos (Para reproducir audios .opus de WhatsApp)",
                            isGranted = isAllFilesOk,
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                }
                            }
                        )

                        PermissionRow(
                            title = "E. Batería sin restricciones (Para que nunca se cierre solo)",
                            isGranted = isBatteryOk,
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, isGranted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = (if (isGranted) "🟢 " else "🔴 ") + title,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isGranted) Color(0xFF238636) else Color(0xFFDA3633)
            )
        ) {
            Text(text = if (isGranted) "Activo" else "Activar", color = Color.White)
        }
    }
}

private fun checkAccessibilityEnabled(context: Context): Boolean {
    if (JarvisAccessibilityService.instance != null) return true
    val expected = "${context.packageName}/${JarvisAccessibilityService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(expected) || enabledServices.contains(context.packageName)
}

private fun checkNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    return flat.contains(context.packageName)
}

private fun checkAllFilesPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun checkBatteryOptimizationIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun checkRuntimePermissions(context: Context): Boolean {
    val required = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE
    )
    return required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
