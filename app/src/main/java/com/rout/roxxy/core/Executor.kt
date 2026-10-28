package com.rout.roxxy.core

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/** Executes ROXXY's action chains: system, telephony, messaging, UPI, media, hardware. */
class Executor(
    private val context: Context,
    private val onVisualRequery: (String, String) -> Unit = { _, _ -> }
) {
    private val appRegistry = mapOf(
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "instagram" to "com.instagram.android",
        "spotify" to "com.spotify.music",
        "chrome" to "com.android.chrome",
        "settings" to "com.android.settings",
        "camera" to "com.android.camera",
        "gpay" to "com.google.android.apps.nbu.paisa.user",
        "phonepe" to "com.phonepe.app",
        "paytm" to "net.one97.paytm",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps"
    )

    suspend fun executeChain(actions: List<RoxxyAction>) {
        val a11y = RoxxyAccessibilityService.instance
        for (act in actions) {
            val p = act.params
            try {
                when (act.type.uppercase()) {
                    "CLICK_NODE" -> a11y?.clickNodeWithAutoScroll(
                        p["targetText"]?.toString() ?: "", (p["scrollToFind"] as? Boolean) ?: true)
                    "LONG_PRESS_NODE" -> a11y?.longPressNode(
                        p["targetText"]?.toString() ?: "", (p["durationMs"] as? Number)?.toLong() ?: 1200L)
                    "CLICK_XY" -> a11y?.tapCoordinates(
                        (p["x"] as? Number)?.toFloat() ?: 540f, (p["y"] as? Number)?.toFloat() ?: 960f)
                    "DOUBLE_TAP_XY" -> a11y?.doubleTapCoordinates(
                        (p["x"] as? Number)?.toFloat() ?: 540f, (p["y"] as? Number)?.toFloat() ?: 960f)
                    "DRAG_PATH" -> a11y?.dragCoordinates(
                        (p["startX"] as? Number)?.toFloat() ?: 200f, (p["startY"] as? Number)?.toFloat() ?: 1000f,
                        (p["endX"] as? Number)?.toFloat() ?: 800f, (p["endY"] as? Number)?.toFloat() ?: 1000f,
                        (p["durationMs"] as? Number)?.toLong() ?: 400L)
                    "PINCH_ZOOM" -> a11y?.pinchZoom(p["mode"]?.toString() ?: "IN")
                    "SCROLL" -> {
                        val dir = p["direction"]?.toString() ?: "DOWN"
                        repeat((p["times"] as? Number)?.toInt() ?: 1) {
                            a11y?.scrollScreen(dir); delay(400)
                        }
                    }
                    "TYPE_TEXT" -> a11y?.typeIntoField(
                        p["text"]?.toString() ?: "", p["targetHint"]?.toString(),
                        (p["pressEnter"] as? Boolean) ?: false)
                    "WAIT_FOR_UI" -> a11y?.waitForUiElement(
                        p["targetText"]?.toString() ?: "", (p["timeoutMs"] as? Number)?.toLong() ?: 5000L)
                    "SCAN_SCREEN_VISION" -> {
                        val task = p["task"]?.toString() ?: "Analyze this screen for Boss"
                        val b64 = a11y?.captureScreenJpegBase64()
                        if (b64 != null) onVisualRequery(task, b64)
                    }
                    "OPEN_APP" -> {
                        val name = p["appName"]?.toString()?.lowercase() ?: ""
                        val pkg = p["package"]?.toString() ?: appRegistry[name] ?: resolvePackageByLabel(name)
                        if (!pkg.isNullOrBlank()) launchPackage(pkg)
                    }
                    "OPEN_UPI_SCANNER" -> {
                        val app = p["app"]?.toString()?.lowercase() ?: "gpay"
                        val pkg = appRegistry[app] ?: appRegistry["gpay"]!!
                        launchPackage(pkg)
                        delay(1800)
                        a11y?.clickNodeWithAutoScroll("Scan any QR", scrollToFind = false)
                    }
                    "REPLY_NOTIFICATION" -> RoxxyNotificationService.sendDirectBackgroundReply(
                        context, p["replyText"]?.toString() ?: "I am busy right now.")
                    "MAKE_CALL" -> makeCall(p["contactName"]?.toString() ?: "", p["phoneNumber"]?.toString() ?: "")
                    "SEND_WHATSAPP" -> sendWhatsApp(
                        p["contactName"]?.toString() ?: "", p["phoneNumber"]?.toString() ?: "",
                        p["message"]?.toString() ?: "")
                    "SET_ALARM" -> {
                        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(AlarmClock.EXTRA_HOUR, (p["hour"] as? Number)?.toInt() ?: 7)
                            putExtra(AlarmClock.EXTRA_MINUTES, (p["minute"] as? Number)?.toInt() ?: 0)
                            putExtra(AlarmClock.EXTRA_MESSAGE, p["label"]?.toString() ?: "Roxxy Alarm")
                            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        safeStart(intent)
                    }
                    "SET_TIMER" -> {
                        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                            putExtra(AlarmClock.EXTRA_LENGTH, (p["seconds"] as? Number)?.toInt() ?: 60)
                            putExtra(AlarmClock.EXTRA_MESSAGE, p["label"]?.toString() ?: "Roxxy Timer")
                            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        safeStart(intent)
                    }
                    "MEDIA_CONTROL" -> mediaControl(
                        p["command"]?.toString()?.uppercase() ?: "PLAY_PAUSE",
                        p["platform"]?.toString()?.lowercase() ?: "youtube",
                        p["query"]?.toString() ?: "")
                    "HARDWARE_TOGGLE" -> controlHardware(
                        p["command"]?.toString()?.uppercase() ?: "", (p["value"] as? Number)?.toInt() ?: 70)
                    "GLOBAL_NAVIGATION" -> a11y?.executeGlobalAction(p["action"]?.toString() ?: "HOME")
                    "CLIPBOARD_COPY" -> {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("RoxxyCopy", p["text"]?.toString() ?: ""))
                    }
                    "WAIT" -> delay((p["ms"] as? Number)?.toLong() ?: 1000L)
                }
            } catch (_: Exception) { /* one failed step must not kill the chain */ }
            delay(380)
        }
    }

    private fun launchPackage(pkg: String) {
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            safeStart(it)
        }
    }

    private fun safeStart(intent: Intent) {
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
    }

    private fun makeCall(contact: String, numberIn: String) {
        var number = numberIn
        if (number.isBlank() && contact.isNotBlank()) number = queryContactNumber(contact) ?: ""
        if (number.isBlank()) return
        val hasCallPerm = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val intent = if (hasCallPerm) Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                     else Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        safeStart(intent)
    }

    private suspend fun sendWhatsApp(contact: String, phoneIn: String, msg: String) {
        var phone = phoneIn
        if (phone.isBlank() && contact.isNotBlank()) phone = queryContactNumber(contact) ?: ""
        if (phone.isBlank()) return
        val formatted = phone.replace("[^0-9+]".toRegex(), "")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$formatted&text=${Uri.encode(msg)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        safeStart(intent)
        delay(1800)
        RoxxyAccessibilityService.instance?.clickNodeWithAutoScroll("Send", scrollToFind = false)
    }

    private fun mediaControl(cmd: String, platform: String, query: String) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (cmd) {
            "PLAY_PAUSE" -> sendMediaButton(audio, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "NEXT" -> sendMediaButton(audio, KeyEvent.KEYCODE_MEDIA_NEXT)
            "PREVIOUS" -> sendMediaButton(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "PLAY_SEARCH" -> {
                if (platform == "spotify") {
                    safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$query")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    val yt = Intent(Intent.ACTION_SEARCH).apply {
                        setPackage("com.google.android.youtube")
                        putExtra("query", query)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try { context.startActivity(yt) } catch (_: Exception) {
                        safeStart(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }

    private fun sendMediaButton(audio: AudioManager, keyCode: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun controlHardware(cmd: String, value: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (cmd) {
            "FLASHLIGHT_ON", "FLASHLIGHT_OFF" -> {
                try {
                    val cam = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cam.cameraIdList.firstOrNull { cam.getCameraCharacteristics(it)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                        ?: cam.cameraIdList.firstOrNull()
                    id?.let { cam.setTorchMode(it, cmd == "FLASHLIGHT_ON") }
                } catch (_: Exception) {}
            }
            "VOLUME_UP" -> audio.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "VOLUME_DOWN" -> audio.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "MUTE" -> audio.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "VOLUME_SET" -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    ((value.coerceIn(0, 100) / 100f) * max).toInt(), AudioManager.FLAG_SHOW_UI)
            }
            "BRIGHTNESS_SET" -> {
                if (Settings.System.canWrite(context)) {
                    val b = ((value.coerceIn(0, 100) / 100f) * 255).toInt()
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, b)
                }
            }
            "DND_ON" -> if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            "DND_OFF" -> if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            "WIFI_PANEL" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                safeStart(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "BLUETOOTH_PANEL" -> safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "HOTSPOT_PANEL" -> safeStart(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @SuppressLint("Range")
    private fun queryContactNumber(name: String): String? {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return null
        val cursor: Cursor? = try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null)
        } catch (_: Exception) { null }
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
            }
        }
        return null
    }

    private fun resolvePackageByLabel(appName: String): String? {
        if (appName.isBlank()) return null
        val pm = context.packageManager
        return try {
            for (info in pm.getInstalledApplications(0)) {
                if (pm.getApplicationLabel(info).toString().lowercase().contains(appName)) {
                    return info.packageName
                }
            }
            null
        } catch (_: Exception) { null }
    }
}
