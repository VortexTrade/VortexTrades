package com.vortextrade.blackjackoverlay

import android.content.Context

/**
 * Stores the Anthropic API key on-device so a prebuilt APK can be used without rebuilding.
 * Falls back to the build-time BuildConfig value (from local.properties) if nothing is saved,
 * so the developer flow still works too.
 */
object ApiKeyStore {
    private const val PREFS = "blackjack_prefs"
    private const val KEY = "anthropic_api_key"

    fun get(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        return if (stored.isNotBlank()) stored else BuildConfig.ANTHROPIC_API_KEY
    }

    fun set(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, key.trim())
            .apply()
    }

    fun hasKey(context: Context): Boolean = get(context).isNotBlank()
}
