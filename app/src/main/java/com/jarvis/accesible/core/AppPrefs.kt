package com.jarvis.accesible.core

import android.content.Context
import android.content.SharedPreferences

object AppPrefs {
    private const val PREFS_NAME = "jarvis_accesible_prefs"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
    private const val KEY_WAKE_WORD_PHRASE = "wake_word_phrase"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_VOLUME_BUTTON_TRIGGER = "volume_button_trigger"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getGeminiApiKey(context: Context): String =
        prefs(context).getString(KEY_GEMINI_API_KEY, "")?.trim() ?: ""

    /**
     * Permite colocar 1 sola clave o varias claves de distintas cuentas separadas por coma.
     * Si una cuenta se queda sin cuota diaria, Jarvis rota automáticamente a la siguiente.
     */
    fun getGeminiApiKeyList(context: Context): List<String> =
        getGeminiApiKey(context)
            .split(",", ";", "\n", " ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun setGeminiApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_GEMINI_API_KEY, apiKey.trim()).apply()
    }

    fun isWakeWordEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAKE_WORD_ENABLED, false)

    fun setWakeWordEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
    }

    fun getWakeWordPhrase(context: Context): String =
        prefs(context).getString(KEY_WAKE_WORD_PHRASE, "jarvis")?.lowercase()?.trim() ?: "jarvis"

    fun setWakeWordPhrase(context: Context, phrase: String) {
        prefs(context).edit().putString(KEY_WAKE_WORD_PHRASE, phrase.lowercase().trim()).apply()
    }

    fun getSpeechRate(context: Context): Float =
        prefs(context).getFloat(KEY_SPEECH_RATE, 0.95f)

    fun setSpeechRate(context: Context, rate: Float) {
        prefs(context).edit().putFloat(KEY_SPEECH_RATE, rate).apply()
    }

    fun isVolumeButtonTriggerEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOLUME_BUTTON_TRIGGER, true)

    fun setVolumeButtonTriggerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOLUME_BUTTON_TRIGGER, enabled).apply()
    }
}
