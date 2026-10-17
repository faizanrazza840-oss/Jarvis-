package com.jarvis.assistant

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val AI_ENDPOINT = "https://broad-art-b578faris-ai-bridge.faizanrazza840.workers.dev"
private val client = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(75, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .build()

private data class Message(val text: String, val user: Boolean, val time: Long = System.currentTimeMillis())

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsReady = false
    private var onRecognized: ((String) -> Unit)? = null
    private var onSpeechState: ((String) -> Unit)? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else onSpeechState?.invoke("Microphone permission zaroori hai.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { onSpeechState?.invoke("Sun raha hoon…") }
                override fun onBeginningOfSpeech() { onSpeechState?.invoke("Sun raha hoon…") }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { onSpeechState?.invoke("Samajh raha hoon…") }
                override fun onError(error: Int) { onSpeechState?.invoke("Voice input nahi mila. Dobara try karo.") }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) onRecognized?.invoke(text)
                    onSpeechState?.invoke("Ready")
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
        setContent {
            JarvisApp(
                speak = ::speak,
                listen = { recognized, state ->
                    onRecognized = recognized
                    onSpeechState = state
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startListening()
                    } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                openApp = ::launchApp,
                openSettings = ::openSettings,
                performSystemAction = ::performSystemAction,
                appNames = ::getLaunchableApps,
                recentNotifications = ::getRecentNotifications
            )
        }
    }

    private fun startListening() {
        val recognizer = speechRecognizer ?: run {
            onSpeechState?.invoke("Is phone par speech recognition available nahi hai.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "JARVIS ko boliye")
        }
        try { recognizer.startListening(intent) } catch (_: Exception) { onSpeechState?.invoke("Voice start nahi ho payi.") }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale("hi", "IN")
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text.take(2500), TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    private fun getLaunchableApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                val label = info.loadLabel(packageManager).toString()
                if (pkg == packageName) null else label to pkg
            }.distinctBy { it.second }.sortedBy { it.first.lowercase() }.take(100)
    }

    private fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return try { startActivity(intent); true } catch (_: ActivityNotFoundException) { false }
    }

    private fun openSettings(which: String) {
        val intent = when (which) {
            "notifications" -> Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "assistant" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                else Intent(Settings.ACTION_SETTINGS)
            } else Intent(Settings.ACTION_SETTINGS)
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun performSystemAction(action: String): Boolean {
        val service = JarvisAccessibilityService.activeService ?: return false
        return when (action) {
            "back" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            else -> false
        }
    }

    private fun getRecentNotifications(): List<String> {
        val raw = getSharedPreferences("jarvis_data", MODE_PRIVATE).getString("notifications", "[]")
        return try {
            val arr = JSONArray(raw)
            (0 until minOf(arr.length(), 10)).map { i ->
                val obj = arr.getJSONObject(i)
                listOf(obj.optString("title"), obj.optString("text")).filter { it.isNotBlank() }.joinToString(": ")
            }
        } catch (_: Exception) { emptyList() }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun JarvisApp(
    speak: (String) -> Unit,
    listen: ((String) -> Unit, (String) -> Unit) -> Unit,
    openApp: (String) -> Boolean,
    openSettings: (String) -> Unit,
    performSystemAction: (String) -> Boolean,
    appNames: () -> List<Pair<String, String>>,
    recentNotifications: () -> List<String>
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var voiceState by remember { mutableStateOf("Online • Ready") }
    var tab by remember { mutableStateOf("chat") }
    var apps by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var notifications by remember { mutableStateOf(emptyList<String>()) }
    val prefs = remember { context.getSharedPreferences("jarvis_data", Context.MODE_PRIVATE) }
    val messages = remember {
        mutableStateListOf<Message>().apply {
            val saved = try { JSONArray(prefs.getString("chat_history", "[]")) } catch (_: Exception) { JSONArray() }
            for (i in 0 until minOf(saved.length(), 80)) {
                val o = saved.optJSONObject(i) ?: continue
                add(Message(o.optString("text"), o.optBoolean("user"), o.optLong("time", System.currentTimeMillis())))
            }
            if (isEmpty()) add(Message("Namaste. Main JARVIS hoon. Bolo, main kaise madad karun?", false))
        }
    }

    fun persist() {
        val arr = JSONArray()
        messages.takeLast(80).forEach { arr.put(JSONObject().put("text", it.text).put("user", it.user).put("time", it.time)) }
        prefs.edit().putString("chat_history", arr.toString()).apply()
    }

    fun send(text: String) {
        val question = text.trim()
        if (question.isBlank() || loading) return
        input = ""
        messages.add(Message(question, true))
        persist()
        loading = true
        voiceState = "AI soch raha hai…"
        scope.launch {
            val answer = withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().put("message", question).toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                    val request = Request.Builder().url(AI_ENDPOINT).post(body).build()
                    client.newCall(request).execute().use { response ->
                        val raw = response.body?.string().orEmpty()
                        val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                        if (!response.isSuccessful) "AI service error (${response.code}): ${json.optString("error", "server check karna hoga")}"
                        else json.optString("reply").takeIf { it.isNotBlank() } ?: "AI service se khaali jawab mila."
                    }
                } catch (e: Exception) {
                    "JARVIS server se connect nahi ho paya. Internet ya AI service check karo. (${e.localizedMessage ?: "connection error"})"
                }
            }
            messages.add(Message(answer, false))
            persist()
            loading = false
            voiceState = "Online • Ready"
            speak(answer)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFF55E8FF),
        secondary = Color(0xFF6B8CFF),
        background = Color(0xFF060B17),
        surface = Color(0xFF101A2D)
    )) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF081329), Color(0xFF03060E))))
                .statusBarsPadding().navigationBarsPadding().padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(54.dp).background(Brush.radialGradient(listOf(Color(0xFF55E8FF), Color(0xFF214A85), Color.Transparent)), CircleShape), contentAlignment = Alignment.Center) {
                    Text("J", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black)
                }
                Column(Modifier.weight(1f)) {
                    Text("J.A.R.V.I.S.", color = Color(0xFF9CF3FF), fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(voiceState, color = Color(0xFF8DA7C7), fontSize = 12.sp)
                }
                TextButton(onClick = { tab = if (tab == "chat") "control" else "chat" }) {
                    Text(if (tab == "chat") "CONTROL" else "CHAT")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (tab == "chat") {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(messages) { msg ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.user) Arrangement.End else Arrangement.Start) {
                            Surface(
                                color = if (msg.user) Color(0xFF153D5A) else Color(0xFF111D32),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.widthIn(max = 310.dp)
                            ) {
                                Text(msg.text, color = Color(0xFFEAF7FF), modifier = Modifier.padding(13.dp), lineHeight = 21.sp)
                            }
                        }
                    }
                    if (loading) item { Text("JARVIS is processing…", color = Color(0xFF55E8FF)) }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("JARVIS ko message bolo…") }, maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF55E8FF), unfocusedBorderColor = Color(0xFF47617D)
                        )
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { listen({ input = it; send(it) }, { voiceState = it }) }, contentPadding = PaddingValues(12.dp)) { Text("🎙") }
                        Button(onClick = { send(input) }, enabled = input.isNotBlank() && !loading, contentPadding = PaddingValues(12.dp)) { Text("➤") }
                    }
                }
            } else {
                Text("PHONE CONTROL", color = Color(0xFF9CF3FF), fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Har control Android permissions ke andar kaam karta hai.", color = Color(0xFF8DA7C7), fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { ControlCard("Open installed apps", "Phone par launchable apps ki list", "LOAD APPS") { apps = appNames() } }
                    if (apps.isNotEmpty()) {
                        items(apps) { (label, pkg) ->
                            ControlCard(label, pkg, "OPEN") { if (!openApp(pkg)) voiceState = "$label open nahi ho paya." }
                        }
                    }
                    item { ControlCard("Accessibility controls", "Home, Back, Recents jaise system actions enable karne ke liye", "SET UP") { openSettings("accessibility") } }
                    item { ControlCard("Notification access", "Recent notifications ko JARVIS mein available karne ke liye", "SET UP") { openSettings("notifications") } }
                    item { ControlCard("Default assistant settings", "Agar device support kare to assistant role setup", "OPEN") { openSettings("assistant") } }
                    item { ControlCard("Wi-Fi settings", "Android ke official settings panel", "OPEN") { openSettings("wifi") } }
                    item { ControlCard("Bluetooth settings", "Android ke official settings panel", "OPEN") { openSettings("bluetooth") } }
                    item { ControlCard("Display settings", "Brightness aur display options ke liye", "OPEN") { openSettings("display") } }
                    item { ControlCard("Home action", "Accessibility service enabled hona chahiye", "RUN") { voiceState = if (performSystemAction("home")) "Home action done" else "Pehle Accessibility Controls enable karo." } }
                    item { ControlCard("Back action", "Accessibility service enabled hona chahiye", "RUN") { voiceState = if (performSystemAction("back")) "Back action done" else "Pehle Accessibility Controls enable karo." } }
                    item { ControlCard("Recent apps", "Accessibility service enabled hona chahiye", "RUN") { voiceState = if (performSystemAction("recents")) "Recents opened" else "Pehle Accessibility Controls enable karo." } }
                    item { ControlCard("Read recent notifications", "Notification access enable karne ke baad", "REFRESH") { notifications = recentNotifications() } }
                    items(notifications) { line -> Text("• $line", color = Color(0xFFD5E9FA), modifier = Modifier.padding(8.dp)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("JARVIS • Online AI • Local chat history", color = Color(0xFF607D9D), fontSize = 10.sp)
        }
    }
}

@Composable
private fun ControlCard(title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Surface(color = Color(0xFF101B2F), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color(0xFF8DA7C7), fontSize = 11.sp)
            }
            TextButton(onClick = onClick) { Text(action, color = Color(0xFF75EFFF), fontSize = 11.sp) }
        }
    }
}
