package com.rout.roxxy.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** ROXXY's eyes and hands on the screen. User enables it in Android Accessibility settings. */
class RoxxyAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: RoxxyAccessibilityService? = null
        @Volatile var foregroundPackage: String = "Home"
        val statusListeners = mutableListOf<(Boolean) -> Unit>()

        fun isConnected(): Boolean = instance != null
        fun notifyStatus() {
            val connected = isConnected()
            statusListeners.toList().forEach { it(connected) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        notifyStatus()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { foregroundPackage = it }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        notifyStatus()
        super.onDestroy()
    }

    fun dumpInteractiveScreenNodes(): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        val root = rootInActiveWindow ?: return list

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40) return
            val t = node.text?.toString()?.trim() ?: ""
            val d = node.contentDescription?.toString()?.trim() ?: ""
            val id = node.viewIdResourceName?.substringAfterLast("/") ?: ""
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (node.isVisibleToUser && (t.isNotEmpty() || d.isNotEmpty() || node.isClickable || node.isEditable)) {
                list.add(mapOf(
                    "text" to t.take(120),
                    "desc" to d.take(120),
                    "id" to id,
                    "clickable" to node.isClickable.toString(),
                    "editable" to node.isEditable.toString(),
                    "x" to bounds.centerX().toString(),
                    "y" to bounds.centerY().toString()
                ))
            }
            if (list.size >= 120) return
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return list
    }

    /** Visible text on screen, joined - used by the screen summarizer. */
    fun dumpVisibleText(maxChars: Int = 4000): String {
        val sb = StringBuilder()
        val root = rootInActiveWindow ?: return ""
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40 || sb.length >= maxChars) return
            val t = node.text?.toString()?.trim() ?: ""
            if (node.isVisibleToUser && t.isNotEmpty()) {
                sb.append(t).append('\n')
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().take(maxChars)
    }

    suspend fun clickNodeWithAutoScroll(target: String, scrollToFind: Boolean = true): Boolean {
        repeat(if (scrollToFind) 3 else 1) { attempt ->
            val root = rootInActiveWindow ?: return false
            val match = locateNode(root, target)
            if (match != null) {
                var current: AccessibilityNodeInfo? = match
                var depth = 0
                while (current != null && depth < 6) {
                    if (current.isClickable) {
                        return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    current = current.parent
                    depth++
                }
                val r = Rect()
                match.getBoundsInScreen(r)
                tapCoordinates(r.centerX().toFloat(), r.centerY().toFloat())
                return true
            }
            if (scrollToFind && attempt < 2) {
                scrollScreen("DOWN")
                delay(550)
            }
        }
        return false
    }

    fun longPressNode(target: String, durationMs: Long = 1200L): Boolean {
        val root = rootInActiveWindow ?: return false
        val match = locateNode(root, target) ?: return false
        if (match.isLongClickable) {
            return match.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
        val r = Rect()
        match.getBoundsInScreen(r)
        val p = Path().apply { moveTo(r.centerX().toFloat(), r.centerY().toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
        return true
    }

    private fun locateNode(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        val t = node.text?.toString() ?: ""
        val d = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        if ((t.isNotEmpty() && t.contains(query, true)) ||
            (d.isNotEmpty() && d.contains(query, true)) ||
            (id.isNotEmpty() && id.contains(query, true))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = locateNode(node.getChild(i), query)
            if (found != null) return found
        }
        return null
    }

    suspend fun waitForUiElement(targetText: String, timeoutMs: Long = 6000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null && locateNode(root, targetText) != null) return true
            delay(250)
        }
        return false
    }

    fun tapCoordinates(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 75))
            .build()
        dispatchGesture(g, null, null)
    }

    fun doubleTapCoordinates(x: Float, y: Float) {
        val p1 = Path().apply { moveTo(x, y) }
        val p2 = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, 50))
            .addStroke(GestureDescription.StrokeDescription(p2, 110, 50))
            .build()
        dispatchGesture(g, null, null)
    }

    fun dragCoordinates(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long = 400L) {
        val p = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        dispatchGesture(g, null, null)
    }

    fun pinchZoom(mode: String) {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val f1 = Path()
        val f2 = Path()
        if (mode.equals("IN", true)) {
            f1.moveTo(cx - 90f, cy - 90f); f1.lineTo(cx - 360f, cy - 360f)
            f2.moveTo(cx + 90f, cy + 90f); f2.lineTo(cx + 360f, cy + 360f)
        } else {
            f1.moveTo(cx - 360f, cy - 360f); f1.lineTo(cx - 90f, cy - 90f)
            f2.moveTo(cx + 360f, cy + 360f); f2.lineTo(cx + 90f, cy + 90f)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(f1, 0, 400))
            .addStroke(GestureDescription.StrokeDescription(f2, 0, 400))
            .build()
        dispatchGesture(g, null, null)
    }

    fun scrollScreen(direction: String) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        when (direction.uppercase()) {
            "DOWN" -> dragCoordinates(w / 2, h * 0.78f, w / 2, h * 0.22f, 320)
            "UP" -> dragCoordinates(w / 2, h * 0.22f, w / 2, h * 0.78f, 320)
            "LEFT" -> dragCoordinates(w * 0.85f, h / 2, w * 0.15f, h / 2, 320)
            "RIGHT" -> dragCoordinates(w * 0.15f, h / 2, w * 0.85f, h / 2, 320)
        }
    }

    suspend fun typeIntoField(text: String, targetHint: String? = null, pressEnter: Boolean = false): Boolean {
        if (!targetHint.isNullOrBlank()) {
            clickNodeWithAutoScroll(targetHint, scrollToFind = false)
            delay(350)
        }
        val root = rootInActiveWindow ?: return false
        val editable = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditable(root)
        if (editable != null) {
            editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (pressEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                delay(200)
                editable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            return ok
        }
        return false
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val res = findFirstEditable(node.getChild(i))
            if (res != null) return res
        }
        return null
    }

    fun executeGlobalAction(action: String) {
        when (action.uppercase()) {
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "SPLIT_SCREEN" -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            "LOCK_SCREEN" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "TAKE_SCREENSHOT" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        }
    }

    /** Live screenshot via Accessibility (Android 11+). Returns JPEG base64 or null. */
    suspend fun captureScreenJpegBase64(): String? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        if (soft != null) {
                            val scaled = if (soft.width > 720) {
                                val ratio = 720f / soft.width
                                Bitmap.createScaledBitmap(soft, 720, (soft.height * ratio).toInt(), true)
                            } else soft
                            val bos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 65, bos)
                            cont.resume(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                        } else cont.resume(null)
                    }
                    override fun onFailure(errorCode: Int) { cont.resume(null) }
                }
            )
        } else cont.resume(null)
    }
}
