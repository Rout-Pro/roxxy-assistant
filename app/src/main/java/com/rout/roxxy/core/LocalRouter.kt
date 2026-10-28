package com.rout.roxxy.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keyless local commands: work without any Gemini API key, fully on-device.
 * Returns null when the text is not a recognized local command.
 */
object LocalRouter {

    private val KNOWN_APPS = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "instagram" to "com.instagram.android",
        "spotify" to "com.spotify.music",
        "chrome" to "com.android.chrome",
        "settings" to "com.android.settings",
        "camera" to "com.android.camera",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "google pay" to "com.google.android.apps.nbu.paisa.user",
        "phonepe" to "com.phonepe.app",
        "paytm" to "net.one97.paytm",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps"
    )

    fun route(rawText: String, batteryPercent: Int): BrainResponse? {
        val text = rawText.lowercase().trim()

        if (text.matches(Regex(".*(time|samay|waqt|kitne baje|kya time).*")) && !text.contains("timer")) {
            val now = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            return BrainResponse("Boss, abhi $now baj rahe hain.", "happy", false, emptyList())
        }
        if (text.matches(Regex(".*(date|tarikh|tareekh|kaun sa din|aaj ka din).*"))) {
            val today = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
            return BrainResponse("Boss, aaj $today hai.", "happy", false, emptyList())
        }
        if (text.matches(Regex(".*battery.*"))) {
            return BrainResponse("Boss, battery abhi $batteryPercent percent pe hai.", "caring", false, emptyList())
        }
        if (text.contains("flashlight on") || text.contains("torch on") || text.contains("torch jalao") || text.contains("flashlight chalu")) {
            return BrainResponse("Torch on kar di Boss.", "happy", false,
                listOf(RoxxyAction("HARDWARE_TOGGLE", mapOf("command" to "FLASHLIGHT_ON"))))
        }
        if (text.contains("flashlight off") || text.contains("torch off") || text.contains("torch band")) {
            return BrainResponse("Torch band kar di Boss.", "happy", false,
                listOf(RoxxyAction("HARDWARE_TOGGLE", mapOf("command" to "FLASHLIGHT_OFF"))))
        }
        if (text.contains("volume up") || text.contains("volume badha")) {
            return BrainResponse("Volume badha diya Boss.", "happy", false,
                listOf(RoxxyAction("HARDWARE_TOGGLE", mapOf("command" to "VOLUME_UP"))))
        }
        if (text.contains("volume down") || text.contains("volume kam")) {
            return BrainResponse("Volume kam kar diya Boss.", "happy", false,
                listOf(RoxxyAction("HARDWARE_TOGGLE", mapOf("command" to "VOLUME_DOWN"))))
        }

        // "open whatsapp" or "whatsapp kholo" style
        val hasOpenWord = text.contains("open") || text.contains("kholo") || text.contains("khol do") ||
            text.contains("khol ") || text.endsWith("khol") || text.contains("chalu karo") || text.contains("start karo")
        if (hasOpenWord) {
            for ((name, pkg) in KNOWN_APPS) {
                if (text.contains(name)) {
                    return BrainResponse("$name khol rahi hu Boss.", "happy", false,
                        listOf(RoxxyAction("OPEN_APP", mapOf("package" to pkg))))
                }
            }
            val afterCmd = Regex("(?:open|kholo na|kholo|khol do|chalu karo|start karo)\\s+([a-z0-9 ]+)").find(text)
            val beforeCmd = Regex("^([a-z0-9 ]+?)\\s*(?:kholo|khol do|open karo|open|chalu karo|start karo)$").find(text)
            val appName = (afterCmd?.groupValues?.getOrNull(1) ?: beforeCmd?.groupValues?.getOrNull(1) ?: "").trim()
            if (appName.isNotEmpty()) {
                return BrainResponse("$appName dhoondh rahi hu Boss.", "thinking", false,
                    listOf(RoxxyAction("OPEN_APP", mapOf("appName" to appName))))
            }
        }
        return null
    }
}
