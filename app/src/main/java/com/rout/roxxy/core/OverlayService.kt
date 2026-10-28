package com.rout.roxxy.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The floating ROXXY orb (JARVIS HUD) that sits over other apps.
 * Tap = talk, long-press = scan my screen. Also runs the experimental
 * "Hey Roxxy" wake loop when the user enables it in Settings.
 */
class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var executor: Executor
    private var voice: VoiceEngine? = null

    private var currentEmotion by mutableStateOf("happy")
    private var isListening by mutableStateOf(false)
    private var isSpeaking by mutableStateOf(false)
    private var subtitleText by mutableStateOf("Tap or say 'Hey Roxxy'")

    private var wakeLoopRunning = false
    private var busyWithCommand = false

    companion object {
        fun start(context: Context) {
            val i = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
        fun stop(context: Context) { context.stopService(Intent(context, OverlayService::class.java)) }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        startForegroundServiceNotification()

        executor = Executor(this) { task, b64 ->
            scope.launch { runCommand(task, b64) }
        }
        voice = VoiceEngine(
            context = this,
            onFinalResult = { spoken -> scope.launch { onHeard(spoken) } },
            onListeningState = { isListening = it },
            onSpeakingState = { speaking ->
                isSpeaking = speaking
                if (!speaking) maybeResumeWakeLoop()
            },
            onError = { maybeResumeWakeLoop() }
        )
        mountOrb()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        maybeStartWakeLoop()
    }

    private fun mountOrb() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 36
            y = 280
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)
            setContent {
                RoxxyOrbHud(
                    emotion = currentEmotion,
                    isListening = isListening,
                    isSpeaking = isSpeaking,
                    subtitle = subtitleText,
                    onTap = {
                        busyWithCommand = true
                        currentEmotion = "happy"
                        subtitleText = "Listening Boss..."
                        voice?.listen()
                    },
                    onLongPress = {
                        scope.launch {
                            busyWithCommand = true
                            currentEmotion = "thinking"
                            subtitleText = "Screen scan kar rahi hu..."
                            val b64 = RoxxyAccessibilityService.instance?.captureScreenJpegBase64()
                            if (b64 != null) {
                                runCommand("Look at my current screen and help me or summarize what's on it in 2 lines of Hinglish.", b64)
                            } else {
                                subtitleText = "Accessibility ON karo pehle Boss"
                                currentEmotion = "alert"
                                speakIfEnabled("Boss, screen dekhne ke liye ROXXY Accessibility service ON karo.")
                                busyWithCommand = false
                            }
                        }
                    },
                    onDrag = { dx, dy ->
                        params.x += dx.toInt()
                        params.y += dy.toInt()
                        windowManager.updateViewLayout(this, params)
                    }
                )
            }
        }
        windowManager.addView(overlayView, params)
    }

    private suspend fun onHeard(spoken: String) {
        val lower = spoken.lowercase()
        val wakeHit = lower.contains("roxxy") || lower.contains("roxy")
        if (!busyWithCommand && wakeHit) {
            // Wake loop heard "Hey Roxxy" - strip the wake phrase.
            busyWithCommand = true
            subtitleText = "Haan Boss, boliye?"
            val remainder = lower.replace("hey roxxy", "").replace("hi roxxy", "")
                .replace("hello roxxy", "").replace("ok roxxy", "")
                .replace("hey roxy", "").replace("roxxy", "").replace("roxy", "").trim()
            if (remainder.length > 3) {
                runCommand(remainder, null)
            } else {
                speakIfEnabled("Haan Boss?")
                voice?.listen()
            }
        } else {
            runCommand(spoken, null)
        }
    }

    private suspend fun runCommand(command: String, screenshotB64: String?) {
        currentEmotion = "thinking"
        subtitleText = "\"$command\""
        val reply = RoxxyCore.ask(this, command, screenshotB64)
        currentEmotion = reply.emotion
        subtitleText = reply.speech
        speakIfEnabled(reply.speech)
        executor.executeChain(reply.actions)
        if (reply.expectFollowUp) {
            delay(2500)
            busyWithCommand = true
            voice?.listen()
        } else {
            busyWithCommand = false
            maybeResumeWakeLoop()
        }
    }

    private fun speakIfEnabled(text: String) {
        if (KeyStore.isTtsEnabled(this)) voice?.speak(text)
    }

    // ---- Experimental wake loop: repeated short recognition windows. ----
    private fun maybeStartWakeLoop() {
        if (!KeyStore.isWakeWordEnabled(this) || wakeLoopRunning) return
        wakeLoopRunning = true
        scope.launch {
            while (wakeLoopRunning) {
                if (!busyWithCommand && !isSpeaking && !isListening &&
                    KeyStore.isWakeWordEnabled(this@OverlayService)) {
                    voice?.listen()
                }
                delay(9000)
            }
        }
    }

    private fun maybeResumeWakeLoop() {
        if (wakeLoopRunning && !busyWithCommand) {
            mainHandler.postDelayed({
                if (wakeLoopRunning && !busyWithCommand && !isSpeaking) voice?.listen()
            }, 1200)
        }
    }

    private fun startForegroundServiceNotification() {
        val channelId = "roxxy_orb_daemon"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "ROXXY Floating Orb", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ROXXY Orb Active")
            .setContentText("Tap orb to talk. Long-press to scan screen.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2026, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(2026, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        maybeStartWakeLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLoopRunning = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        scope.cancel()
        voice?.shutdown()
        overlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        KeyStore.setOrbEnabled(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

@Composable
fun RoxxyOrbHud(
    emotion: String,
    isListening: Boolean,
    isSpeaking: Boolean,
    subtitle: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "roxxyPulse")
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = if (isListening || isSpeaking) 1.38f else 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSpeaking) 340 else 950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloScale"
    )
    val primaryColor by animateColorAsState(
        targetValue = when {
            isListening -> Color(0xFF00E676)
            emotion == "alert" -> Color(0xFFFF1744)
            emotion == "witty" -> Color(0xFFE040FB)
            emotion == "caring" -> Color(0xFFFF4081)
            emotion == "thinking" -> Color(0xFFFFAB00)
            emotion == "happy" -> Color(0xFF00E5FF)
            else -> Color(0xFF7C4DFF)
        },
        label = "roxxyColor"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(76.dp).clip(CircleShape)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(primaryColor.copy(alpha = 0.78f), Color.Transparent)
                    ),
                    radius = (size.minDimension / 2f) * haloScale
                )
                drawCircle(color = primaryColor, radius = size.minDimension / 3.2f)
            }
            Text(text = "R", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        }
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .widthIn(max = 250.dp)
                    .background(Color(0xE60B0E17), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(text = subtitle, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
