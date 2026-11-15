package com.jarvis.accesible.ui

import android.Manifest
import android.content.ClipboardManager
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jarvis.accesible.core.AppPrefs
import com.jarvis.accesible.core.JarvisCoordinator
import com.jarvis.accesible.services.JarvisAccessibilityService
import com.jarvis.accesible.services.JarvisForegroundService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var resumeTrigger by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        JarvisForegroundService.startServiceIfPossible(this)

        if (intent?.action == Intent.ACTION_ASSIST && checkRuntimePermissions(this)) {
            JarvisCoordinator.getInstance(this).triggerHandsFreeListening(greetBeforeListening = false)
        }

        setContent {
            MaterialTheme {
                JarvisMainScreen(resumeKey = resumeTrigger)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTrigger++
        JarvisForegroundService.startServiceIfPossible(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST && checkRuntimePermissions(this)) {
            JarvisCoordinator.getInstance(this).triggerHandsFreeListening(greetBeforeListening = false)
        }
    }
}

@Composable
fun JarvisMainScreen(resumeKey: Int) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val coordinator = remember { JarvisCoordinator.getInstance(context) }

    var showSetupPanel by remember {
        mutableStateOf(!checkRuntimePermissions(context) || AppPrefs.getGeminiApiKey(context).isBlank())
    }

    var apiKey by remember { mutableStateOf(AppPrefs.getGeminiApiKey(context)) }
    var apiKeyStatusMessage by remember {
        mutableStateOf(
            if (AppPrefs.getGeminiApiKeyList(context).isNotEmpty()) {
                "🟢 Clave guardada (${AppPrefs.getGeminiApiKeyList(context).first().take(8)}...)"
            } else {
                "Todavía no hay ninguna clave guardada."
            }
        )
    }
    var isTestingKey by remember { mutableStateOf(false) }

    var wakeWordEnabled by remember { mutableStateOf(AppPrefs.isWakeWordEnabled(context)) }
    var volumeTriggerEnabled by remember { mutableStateOf(AppPrefs.isVolumeButtonTriggerEnabled(context)) }

    var isAccessibilityOk by remember { mutableStateOf(checkAccessibilityEnabled(context)) }
    var isNotificationOk by remember { mutableStateOf(checkNotificationListenerEnabled(context)) }
    var isAllFilesOk by remember { mutableStateOf(checkAllFilesPermission()) }
    var isBatteryOk by remember { mutableStateOf(checkBatteryOptimizationIgnored(context)) }
    var isRuntimePermsOk by remember { mutableStateOf(checkRuntimePermissions(context)) }

    LaunchedEffect(resumeKey) {
        isAccessibilityOk = checkAccessibilityEnabled(context)
        isNotificationOk = checkNotificationListenerEnabled(context)
        isAllFilesOk = checkAllFilesPermission()
        isBatteryOk = checkBatteryOptimizationIgnored(context)
        isRuntimePermsOk = checkRuntimePermissions(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        isRuntimePermsOk = checkRuntimePermissions(context)
        JarvisForegroundService.startServiceIfPossible(context)
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
            val isListening = coordinator.isListeningUiState
            val isProcessing = coordinator.isProcessingUiState

            val buttonBgColor = when {
                isListening -> Color(0xFF00E676)
                isProcessing -> Color(0xFFFF9100)
                else -> Color(0xFFFFD600)
            }

            val mainTitle = when {
                isListening -> "🎤 TE ESCUCHO...\nHABLÁ AHORA"
                isProcessing -> "⏳ PROCESANDO\nTU PEDIDO..."
                else -> "TOCÁ ACÁ PARA HABLAR"
            }

            // MODO TÍO: Botón gigante de extremo a extremo de alto contraste
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(buttonBgColor, shape = RoundedCornerShape(28.dp))
                    .semantics {
                        contentDescription =
                            "Hablar con el asistente Jarvis. Tocá cualquier parte de la pantalla o presioná dos veces el botón de Subir Volumen."
                    }
                    .clickable {
                        if (!checkRuntimePermissions(context)) {
                            showSetupPanel = true
                        } else {
                            coordinator.triggerHandsFreeListening(greetBeforeListening = false)
                        }
                    }
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = mainTitle,
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
                            onValueChange = { newText ->
                                // Quitamos espacios accidentales automáticamente mientras se escribe o pega
                                val cleaned = AppPrefs.sanitizeApiKeyInput(newText)
                                apiKey = cleaned
                                AppPrefs.setGeminiApiKey(context, cleaned)
                            },
                            label = {
                                Text(
                                    "Clave API (empieza con AIza... sin espacios)",
                                    color = Color(0xFFB0BEC5)
                                )
                            },
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFFD600),
                                unfocusedBorderColor = Color(0xFF484F58),
                                cursorColor = Color(0xFFFFD600)
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrect = false,
                                keyboardType = KeyboardType.Ascii
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            maxLines = 3
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val pasted = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                    if (pasted.isNotBlank()) {
                                        val cleaned = AppPrefs.sanitizeApiKeyInput(pasted)
                                        apiKey = cleaned
                                        AppPrefs.setGeminiApiKey(context, cleaned)
                                        apiKeyStatusMessage = "📋 Clave pegada sin espacios. Tocá 'Guardar y Probar' para verificarla."
                                    } else {
                                        apiKeyStatusMessage = "⚠️ El portapapeles está vacío. Copiá primero la clave."
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30363D)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("📋 Pegar copiada", color = Color.White, fontSize = 13.sp)
                            }

                            Button(
                                onClick = {
                                    apiKey = ""
                                    AppPrefs.setGeminiApiKey(context, "")
                                    apiKeyStatusMessage = "Campo limpio. Pegá tu clave nueva."
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30363D))
                            ) {
                                Text("Borrar", color = Color(0xFFFF7B72), fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                isTestingKey = true
                                apiKeyStatusMessage = "⏳ Guardando y probando conexión con Google Gemini..."
                                scope.launch {
                                    val cleaned = AppPrefs.sanitizeApiKeyInput(apiKey)
                                    apiKey = cleaned
                                    val (_, msg) = coordinator.geminiAgent.verifyAndSaveApiKey(cleaned)
                                    isTestingKey = false
                                    apiKeyStatusMessage = msg
                                }
                            },
                            enabled = !isTestingKey,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isTestingKey) "⏳ Probando clave..." else "💾 Guardar y Probar Clave",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = apiKeyStatusMessage,
                            color = if (apiKeyStatusMessage.startsWith("🟢")) Color(0xFF3FB950) else Color(0xFFFFD600),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
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
                                safeStartSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        )

                        PermissionRow(
                            title = "C. Lector de Notificaciones (Leer y responder WhatsApp)",
                            isGranted = isNotificationOk,
                            onClick = {
                                safeStartSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
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
                                    safeStartSettings(
                                        context,
                                        intent,
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    )
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
                                safeStartSettings(
                                    context,
                                    intent,
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                )
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

private fun safeStartSettings(context: Context, primary: Intent, fallback: Intent? = null) {
    try {
        context.startActivity(primary)
    } catch (_: Throwable) {
        if (fallback != null) {
            try {
                context.startActivity(fallback)
                return
            } catch (_: Throwable) {
            }
        }
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } catch (_: Throwable) {
        }
    }
}

private fun checkAccessibilityEnabled(context: Context): Boolean {
    return try {
        if (JarvisAccessibilityService.instance != null) return true
        val expected = "${context.packageName}/${JarvisAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        enabledServices.contains(expected) || enabledServices.contains(context.packageName)
    } catch (_: Throwable) {
        false
    }
}

private fun checkNotificationListenerEnabled(context: Context): Boolean {
    return try {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        flat.contains(context.packageName)
    } catch (_: Throwable) {
        false
    }
}

private fun checkAllFilesPermission(): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    } catch (_: Throwable) {
        false
    }
}

private fun checkBatteryOptimizationIgnored(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Throwable) {
        false
    }
}

private fun checkRuntimePermissions(context: Context): Boolean {
    return try {
        val required = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        required.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    } catch (_: Throwable) {
        false
    }
}
