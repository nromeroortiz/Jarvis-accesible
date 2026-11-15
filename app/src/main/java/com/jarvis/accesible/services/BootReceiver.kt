package com.jarvis.accesible.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Inicia Jarvis automáticamente apenas se enciende o reinicia el celular,
 * sin que el tío tenga que tocar la pantalla ni abrir ninguna aplicación.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action.contains("QUICKBOOT_POWERON")
        ) {
            JarvisForegroundService.startServiceIfPossible(context)
        }
    }
}
