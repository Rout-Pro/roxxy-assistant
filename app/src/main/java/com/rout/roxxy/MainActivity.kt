package com.rout.roxxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.rout.roxxy.core.BrainResponse
import com.rout.roxxy.core.Executor
import com.rout.roxxy.core.KeyStore
import com.rout.roxxy.core.OverlayService
import com.rout.roxxy.core.RoxxyAccessibilityService
import com.rout.roxxy.core.RoxxyCore
import com.rout.roxxy.core.RoxxyNotificationService
import com.rout.roxxy.core.VoiceEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

data class ChatMsg(val text: String, val fromUser: Boolean, val emotion: String = "neutral")

class MainActivity : ComponentActivity() {

    private val messages = mutableStateListOf<ChatMsg>()
    private var voice: VoiceEngine? = null
    private lateinit var executor: Executor
    private var isThinking = mutableStateOf(false)
    private var statusLine = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = Executor(this) { task, b64 ->
            askBrain(task, b64)
        }
        voice = VoiceEngine(
            context = this,
            onFinalResult = { spoken ->
                addUser(spoken)
                askBrain(spoken, null)
            },
            onListeningState = {},
            onSpeakingState = {}
        )
        setContent { RoxxyApp() }
    }

    private fun addUser(text: String) { messages.add(ChatMsg(text, true)) }
    private fun addRoxxy(resp: BrainResponse) { messages.add(ChatMsg(resp.speech, false, resp.emotion)) }

    private fun askBrain(text: String, screenshotB64: String?) {
        isThinking.value = true
        kotlinx.coroutines.MainScope().launch {
            val reply = RoxxyCore.ask(this@MainActivity, text, screenshotB64)
            isThinking.value = false
            addRoxxy(reply)
            if (KeyStore.isTtsEnabled(this@MainActivity)) voice?.speak(reply.speech)
            executor.executeChain(reply.actions)
        }
    }

    private fun refreshStatus() {
        val a11y = RoxxyAccessibilityService.isConnected()
        val notif = RoxxyNotificationService.isListenerEnabled(this)
        val keySet = KeyStore.getApiKey(this).isNotBlank()
        statusLine.value = "Accessibility: ${if (a11y) "ON" else "OFF"} | Notifications: ${if (notif) "ON" else "OFF"} | Gemini key: ${if (keySet) "SET" else "MISSING"}"
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        voice?.shutdown()
        super.onDestroy()
    }

    @Composable
    fun RoxxyApp() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        MaterialTheme(colorScheme = darkColorScheme(
            primary = Color(0xFF00E5FF),
            background = Color(0xFF0B0E17),
            surface = Color(0xFF151A28)
        )) {
            var tab by remember { mutableStateOf(0) }
            Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0E17))) {
                RoxxyHeader(statusLine.value)
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    TabButton("Chat", tab == 0) { tab = 0 }
                    Spacer(Modifier.width(8.dp))
                    TabButton("Setup", tab == 1) { tab = 1 }
                    Spacer(Modifier.width(8.dp))
                    TabButton("Settings", tab == 2) { tab = 2 }
                }
                when (tab) {
                    0 -> ChatTab()
                    1 -> SetupTab { refreshStatus() }
                    2 -> SettingsTab()
                }
            }
        }
    }

    @Composable
    fun RoxxyHeader(status: String) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("ROXXY", color = Color(0xFF00E5FF), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("Your Hinglish AI OS Controller", color = Color(0xFF8B93A7), fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(status, color = Color(0xFF6EE7B7), fontSize = 11.sp)
        }
    }

    @Composable
    fun TabButton(label: String, active: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) Color(0xFF00E5FF) else Color(0xFF1E2436),
                contentColor = if (active) Color(0xFF0B0E17) else Color(0xFF8B93A7)
            ),
            shape = RoundedCornerShape(10.dp)
        ) { Text(label, fontSize = 13.sp) }
    }

    @Composable
    fun ChatTab() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var input by remember { mutableStateOf("") }
        var partial by remember { mutableStateOf("") }
        val listState = rememberLazyListState()
        val thinking by isThinking

        val cameraFile = remember { File(context.cacheDir, "camera/shot.jpg") }
        var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
        val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) {
                val b64 = encodeImageFile(cameraFile)
                if (b64 != null) {
                    addUser("[photo bheji]")
                    askBrain("Dekho mere samne kya hai - is photo ko 2 line Hinglish mein describe karo.", b64)
                } else addRoxxy(BrainResponse("Boss, photo process nahi ho payi.", "alert", false, emptyList()))
            }
        }

        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) { refreshStatus() }

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(vertical = 24.dp)) {
                            Text("Namaste Boss!", color = Color(0xFF00E5FF), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Bolo ya likho: \"time kya hai\", \"torch on karo\", \"WhatsApp kholo\", \"subah 6 baje ka alarm lagao\". " +
                                    "Gemini key set karne ke baad poora smart Hinglish brain chalega.",
                                color = Color(0xFF8B93A7), fontSize = 14.sp)
                        }
                    }
                }
                items(messages) { msg -> ChatBubble(msg) }
                if (thinking) {
                    item { Text("Roxxy soch rahi hai...", color = Color(0xFFFFAB00), fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                }
                if (partial.isNotBlank()) {
                    item { Text(partial, color = Color(0xFF6EE7B7), fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Boss, likho yahan...", fontSize = 13.sp) },
                    singleLine = true
                )
                Spacer(Modifier.width(6.dp))
                RoundAction("🎤") {
                    permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED) {
                        voice = VoiceEngine(
                            context = context,
                            onPartial = { partial = it },
                            onFinalResult = { spoken ->
                                partial = ""
                                addUser(spoken)
                                askBrain(spoken, null)
                            },
                            onListeningState = {},
                            onSpeakingState = {},
                            onError = { partial = "" }
                        )
                        voice?.listen()
                    }
                }
                Spacer(Modifier.width(6.dp))
                RoundAction("📷") {
                    permLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) {
                        cameraFile.parentFile?.mkdirs()
                        val uri = FileProvider.getUriForFile(context, "com.rout.roxxy.fileprovider", cameraFile)
                        pendingCameraUri = uri
                        takePicture.launch(uri)
                    }
                }
                Spacer(Modifier.width(6.dp))
                RoundAction("➤") {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        input = ""
                        addUser(text)
                        scope.launch { askBrain(text, null) }
                    }
                }
            }
        }
    }

    @Composable
    fun ChatBubble(msg: ChatMsg) {
        val bg = if (msg.fromUser) Color(0xFF1E2436) else Color(0xFF0E2A33)
        val align = if (msg.fromUser) Alignment.End else Alignment.Start
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = align) {
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .background(bg, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(msg.text, color = Color.White, fontSize = 14.sp)
            }
        }
    }

    @Composable
    fun RoundAction(label: String, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            shape = CircleShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            modifier = Modifier.size(42.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2436))
        ) { Text(label, fontSize = 16.sp) }
    }

    @Composable
    fun SetupTab(onChanged: () -> Unit) {
        val context = LocalContext.current
        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("ROXXY Setup", color = Color(0xFF00E5FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Har permission ke neeche kya milta hai likha hai. Jo chahiye wahi on karo.",
                color = Color(0xFF8B93A7), fontSize = 12.sp)

            PermRow("Accessibility (screen control)",
                "Screen dekhna, buttons dabana, scroll, type, screenshot - iske bina touch control nahi chalega.",
                RoxxyAccessibilityService.isConnected()) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PermRow("Notification access",
                "Incoming WhatsApp/messages padhna aur reply karna (Ghost mode ke liye).",
                RoxxyNotificationService.isListenerEnabled(context)) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PermRow("Floating orb (overlay)",
                "Har app ke upar ROXXY orb - tap to talk, long-press to scan screen.",
                Settings.canDrawOverlays(context)) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PermRow("Microphone, camera, contacts",
                "Voice commands, camera vision, aur contact name se call/WhatsApp.",
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.READ_CONTACTS)
                if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                permLauncher.launch(perms.toTypedArray())
            }
            PermRow("Write settings (brightness)",
                "Brightness jaise system settings badalne ke liye.",
                Settings.System.canWrite(context)) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PermRow("Do Not Disturb access",
                "DND on/off routines ke liye.",
                (context.getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .isNotificationPolicyAccessGranted) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    @Composable
    fun PermRow(title: String, desc: String, granted: Boolean, onGrant: () -> Unit) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151A28)), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(desc, color = Color(0xFF8B93A7), fontSize = 11.sp)
                    }
                    Text(if (granted) "ON" else "OFF",
                        color = if (granted) Color(0xFF6EE7B7) else Color(0xFFFF8A80),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                if (!granted) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onGrant) { Text("Grant", fontSize = 12.sp) }
                }
            }
        }
    }

    @Composable
    fun SettingsTab() {
        val context = LocalContext.current
        var apiKey by remember { mutableStateOf(KeyStore.getApiKey(context)) }
        var model by remember { mutableStateOf(KeyStore.getModel(context)) }
        var tts by remember { mutableStateOf(KeyStore.isTtsEnabled(context)) }
        var wake by remember { mutableStateOf(KeyStore.isWakeWordEnabled(context)) }
        var ghost by remember { mutableStateOf(KeyStore.isGhostModeEnabled(context)) }
        var ghostText by remember { mutableStateOf(KeyStore.getGhostReplyText(context)) }
        var orb by remember { mutableStateOf(KeyStore.isOrbEnabled(context)) }
        val notifications = remember { mutableStateListOf<Map<String, String>>() }

        DisposableEffect(Unit) {
            val listener: () -> Unit = {
                notifications.clear()
                notifications.addAll(RoxxyNotificationService.liveNotifications.toList())
            }
            RoxxyNotificationService.feedListeners.add(listener)
            listener()
            onDispose { RoxxyNotificationService.feedListeners.remove(listener) }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", color = Color(0xFF00E5FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151A28))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gemini API key (encrypted on this phone)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Free key: aistudio.google.com/apikey se banao. Kabhi share mat karo.",
                        color = Color(0xFF8B93A7), fontSize = 11.sp)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        placeholder = { Text("AIza...", fontSize = 13.sp) }
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Model", fontSize = 12.sp) }
                    )
                    Button(onClick = {
                        KeyStore.setApiKey(context, apiKey)
                        KeyStore.setModel(context, model.ifBlank { "gemini-2.5-flash" })
                        refreshStatus()
                    }) { Text("Save key", fontSize = 13.sp) }
                }
            }

            SettingSwitch("Voice replies (TTS)", tts) {
                tts = it; KeyStore.setTtsEnabled(context, it); if (!it) voice?.stopSpeaking()
            }
            SettingSwitch("Floating ROXXY orb", orb) {
                orb = it; KeyStore.setOrbEnabled(context, it)
                if (it) {
                    if (Settings.canDrawOverlays(context)) OverlayService.start(context)
                    else context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")))
                } else OverlayService.stop(context)
            }
            SettingSwitch("\"Hey Roxxy\" wake word (experimental)", wake) {
                wake = it; KeyStore.setWakeWordEnabled(context, it)
                if (it && !orb) {
                    orb = true; KeyStore.setOrbEnabled(context, true)
                    if (Settings.canDrawOverlays(context)) OverlayService.start(context)
                }
            }
            Text("Wake word orb chalne ke saath kaam karta hai aur battery use karta hai.",
                color = Color(0xFF8B93A7), fontSize = 11.sp)

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151A28))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingSwitch("Ghost auto-reply (WhatsApp)", ghost) {
                        ghost = it; KeyStore.setGhostModeEnabled(context, it)
                    }
                    OutlinedTextField(
                        value = ghostText,
                        onValueChange = { ghostText = it; KeyStore.setGhostReplyText(context, it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Ghost reply message", fontSize = 12.sp) }
                    )
                    Text("Sirf tab reply karta hai jab notification mein reply button ho. 60 sec per sender limit.",
                        color = Color(0xFF8B93A7), fontSize = 11.sp)
                }
            }

            if (notifications.isNotEmpty()) {
                Text("Recent notifications", color = Color(0xFF00E5FF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                notifications.takeLast(6).reversed().forEach { n ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151A28))) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Text("${n["sender"]} (${n["app"]?.substringAfterLast('.')})",
                                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(n["message"] ?: "", color = Color(0xFF8B93A7), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF151A28), RoundedCornerShape(12.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF00E5FF)))
        }
    }

    private fun encodeImageFile(file: File): String? {
        return try {
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val scaled = if (bmp.width > 720) {
                val r = 720f / bmp.width
                Bitmap.createScaledBitmap(bmp, 720, (bmp.height * r).toInt(), true)
            } else bmp
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }
}
