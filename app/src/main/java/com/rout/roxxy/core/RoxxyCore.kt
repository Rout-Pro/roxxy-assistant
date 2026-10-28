package com.rout.roxxy.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** Shared orchestrator used by both the chat UI and the floating orb. */
object RoxxyCore {

    fun batteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (cap in 1..100) cap else 0
    }

    fun isCharging(context: Context): Boolean {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val status = context.registerReceiver(null, ifilter)
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun clipboardText(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: ""
        } catch (_: Exception) { "" }
    }

    /**
     * Full pipeline: gather live context, try local keyless commands first when
     * there is no API key or the network fails, otherwise ask Gemini.
     */
    suspend fun ask(context: Context, userText: String, screenshotBase64: String? = null): BrainResponse {
        val battery = batteryPercent(context)
        val apiKey = KeyStore.getApiKey(context)
        val appCtx = context.applicationContext

        if (apiKey.isBlank()) {
            LocalRouter.route(userText, battery)?.let { return it }
            return BrainResponse(
                "Boss, smart jawab ke liye Settings mein apni free Gemini API key daal dijiye. Time, date, battery, torch aur app kholna bina key ke bhi chalta hai.",
                "alert", false, emptyList())
        }

        val nodes = RoxxyAccessibilityService.instance?.dumpInteractiveScreenNodes() ?: emptyList()
        val notifications = synchronized(RoxxyNotificationService.liveNotifications) {
            RoxxyNotificationService.liveNotifications.toList()
        }
        val body = Brain.buildRequestBody(
            userText = userText,
            screenNodes = nodes,
            screenshotBase64 = screenshotBase64,
            battery = battery,
            isCharging = isCharging(appCtx),
            foregroundPackage = RoxxyAccessibilityService.foregroundPackage,
            clipboard = clipboardText(appCtx),
            notifications = notifications
        ).toString()

        val raw = httpPost(Brain.apiUrl(KeyStore.getModel(appCtx), apiKey), body)
        if (raw != null) {
            val text = Brain.extractTextFromApiResponse(raw)
            if (text != null) {
                val parsed = Brain.parseResponse(text)
                Brain.appendHistory(userText, parsed.speech)
                return parsed
            }
            return BrainResponse("Boss, AI ka reply samajh nahi aaya, ek baar fir bolo?", "alert", false, emptyList())
        }

        // Network or API failure: degrade gracefully to keyless commands.
        LocalRouter.route(userText, battery)?.let { return it }
        return BrainResponse("Boss, internet ya Gemini se link nahi ban pa rahi. Thodi der baad try karein.", "alert", false, emptyList())
    }

    private suspend fun httpPost(url: String, body: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
                connectTimeout = 20000
                readTimeout = 45000
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: return@withContext null
            if (code in 200..299) text else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
