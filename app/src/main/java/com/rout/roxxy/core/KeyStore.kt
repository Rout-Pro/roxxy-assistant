package com.rout.roxxy.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Stores the user's own Gemini API key encrypted on-device, plus ROXXY settings.
 * Falls back to plain SharedPreferences if the device keystore is unavailable.
 */
object KeyStore {
    private const val FILE = "roxxy_secure_prefs"
    private var prefs: SharedPreferences? = null

    @Synchronized
    private fun p(context: Context): SharedPreferences {
        prefs?.let { return it }
        val created = try {
            EncryptedSharedPreferences.create(
                FILE,
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
        prefs = created
        return created
    }

    fun getApiKey(context: Context): String = p(context).getString("gemini_api_key", "") ?: ""
    fun setApiKey(context: Context, key: String) { p(context).edit().putString("gemini_api_key", key.trim()).apply() }

    fun getModel(context: Context): String = p(context).getString("model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
    fun setModel(context: Context, model: String) { p(context).edit().putString("model", model.trim()).apply() }

    fun isTtsEnabled(context: Context): Boolean = p(context).getBoolean("tts_enabled", true)
    fun setTtsEnabled(context: Context, on: Boolean) { p(context).edit().putBoolean("tts_enabled", on).apply() }

    fun isWakeWordEnabled(context: Context): Boolean = p(context).getBoolean("wake_word_enabled", false)
    fun setWakeWordEnabled(context: Context, on: Boolean) { p(context).edit().putBoolean("wake_word_enabled", on).apply() }

    fun isGhostModeEnabled(context: Context): Boolean = p(context).getBoolean("ghost_mode_enabled", false)
    fun setGhostModeEnabled(context: Context, on: Boolean) { p(context).edit().putBoolean("ghost_mode_enabled", on).apply() }

    fun getGhostReplyText(context: Context): String =
        p(context).getString("ghost_reply_text", "Boss abhi busy hain, thodi der mein reply karenge. - ROXXY AI")
            ?: "Boss abhi busy hain, thodi der mein reply karenge. - ROXXY AI"
    fun setGhostReplyText(context: Context, text: String) { p(context).edit().putString("ghost_reply_text", text).apply() }

    fun isOrbEnabled(context: Context): Boolean = p(context).getBoolean("orb_enabled", false)
    fun setOrbEnabled(context: Context, on: Boolean) { p(context).edit().putBoolean("orb_enabled", on).apply() }
}
