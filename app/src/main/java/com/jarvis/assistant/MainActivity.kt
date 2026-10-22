package com.jarvis.assistant

import android.Manifest
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

private const val AI_ENDPOINT =
    "https://broad-art-b578faris-ai-bridge.faizanrazza840.workers.dev"

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(75, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .build()

private data class Message(
    val text: String,
    val user: Boolean,
    val time: Long = System.currentTimeMillis()
)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var ttsReady = false
    private var recognizedCallback: ((String) -> Unit)? = null
    private var speechStateCallback: ((String) -> Unit)? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                speechStateCallback?.invoke("Microphone permission zaroori hai.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)

            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    speechStateCallback?.invoke("Sun raha hoon…")
                }

                override fun onBeginningOfSpeech() {
                    speechStateCallback?.invoke("Sun raha hoon…")
                }

                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    speechStateCallback?.invoke("Samajh raha hoon…")
                }

                override fun onError(error: Int) {
                    speechStateCallback?.invoke(
                        "Voice input nahi mila. Dobara try karo."
                    )
                }

                override fun onResults(results: Bundle?) {
                    val spokenText = results
                        ?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )
                        ?.firstOrNull()
                        .orEmpty()

                    if (spokenText.isNotBlank()) {
                        recognizedCallback?.invoke(spokenText)
                    }

                    speechStateCallback?.invoke("Online • Ready")
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }

        setContent {
            JarvisApp(
                speak = ::speak,
                listen = { onResult, onState ->
                    recognizedCallback = onResult
                    speechStateCallback = onState

                    if (
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        startListening()
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                openApp = ::launchApp,
                openSettings = ::openSystemSettings,
                performSystemAction = ::performSystemAction,
                appNames = ::getLaunchableApps,
                recentNotifications = ::getRecentNotifications
            )
        }
    }

    private fun startListening() {
        val speechRecognizer = recognizer

        if (speechRecognizer == null) {
            speechStateCallback?.invoke(
                "Is device par speech recognition available nahi hai."
            )
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "JARVIS ko boliye")
        }

        try {
            speechRecognizer.startListening(intent)
        } catch (_: Exception) {
            speechStateCallback?.invoke("Voice start nahi ho payi.")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true

            val prefs = getSharedPreferences("jarvis_data", MODE_PRIVATE)
            val language = prefs.getString("voice_language", "hi-IN") ?: "hi-IN"

            setTtsLanguage(language)
            tts?.setSpeechRate(
                prefs.getFloat("voice_rate", 1.0f).coerceIn(0.75f, 1.25f)
            )
        }
    }

    private fun setTtsLanguage(language: String) {
        val locale = when (language) {
            "en-GB" -> Locale.UK
            "en-IN" -> Locale("en", "IN")
            else -> Locale("hi", "IN")
        }

        tts?.language = locale
    }

    private fun speak(text: String) {
        if (!ttsReady) return

        val prefs = getSharedPreferences("jarvis_data", MODE_PRIVATE)
        val language = prefs.getString("voice_language", "hi-IN") ?: "hi-IN"
        val rate = prefs.getFloat("voice_rate", 1.0f).coerceIn(0.75f, 1.25f)

        setTtsLanguage(language)
        tts?.setSpeechRate(rate)
        tts?.speak(
            text.take(2500),
            TextToSpeech.QUEUE_FLUSH,
            null,
            "jarvis_reply"
        )
    }

    private fun getLaunchableApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        return packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_ALL
        ).mapNotNull { info ->
            val pkg = info.activityInfo.packageName
            val label = info.loadLabel(packageManager).toString()

            if (pkg == packageName) null else label to pkg
        }.distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
            .take(100)
    }

    private fun launchApp(packageName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return false

        return try {
            startActivity(launchIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun openSystemSettings(which: String) {
        val intent = when (which) {
            "notifications" ->
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")

            "accessibility" ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

            "assistant" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = getSystemService(RoleManager::class.java)

                    if (roleManager != null &&
                        roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
                    ) {
                        roleManager.createRequestRoleIntent(
                            RoleManager.ROLE_ASSISTANT
                        )
                    } else {
                        Intent(Settings.ACTION_SETTINGS)
                    }
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
            }

            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }

        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                // Settings app unavailable.
            }
        }
    }

    private fun performSystemAction(action: String): Boolean {
        val service = JarvisAccessibilityService.activeService ?: return false

        return when (action) {
            "back" -> service.performGlobalAction(
                android.accessibilityservice.AccessibilityService
                    .GLOBAL_ACTION_BACK
            )

            "home" -> service.performGlobalAction(
                android.accessibilityservice.AccessibilityService
                    .GLOBAL_ACTION_HOME
            )

            "recents" -> service.performGlobalAction(
                android.accessibilityservice.AccessibilityService
                    .GLOBAL_ACTION_RECENTS
            )

            "notifications" -> service.performGlobalAction(
                android.accessibilityservice.AccessibilityService
                    .GLOBAL_ACTION_NOTIFICATIONS
            )

            else -> false
        }
    }

    private fun getRecentNotifications(): List<String> {
        val raw = getSharedPreferences("jarvis_data", MODE_PRIVATE)
            .getString("notifications", "[]")

        return try {
            val array = JSONArray(raw)

            (0 until minOf(array.length(), 10)).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null

                listOf(
                    obj.optString("title"),
                    obj.optString("text")
                ).filter { it.isNotBlank() }
                    .joinToString(": ")
                    .takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember {
        context.getSharedPreferences("jarvis_data", Context.MODE_PRIVATE)
    }

    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var voiceState by remember { mutableStateOf("Online • Ready") }
    var tab by remember { mutableStateOf("chat") }
    var apps by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var notifications by remember { mutableStateOf(emptyList<String>()) }
    var showSettings by remember { mutableStateOf(false) }
    var voiceLanguage by remember { mutableStateOf("hi-IN") }
    var voiceRate by remember { mutableFloatStateOf(1.0f) }

    LaunchedEffect(Unit) {
        voiceLanguage = prefs.getString("voice_language", "hi-IN") ?: "hi-IN"
        voiceRate = prefs.getFloat("voice_rate", 1.0f).coerceIn(0.75f, 1.25f)
    }

    val messages: SnapshotStateList<Message> = remember {
        mutableStateListOf<Message>().apply {
            val saved = try {
                JSONArray(prefs.getString("chat_history", "[]"))
            } catch (_: Exception) {
                JSONArray()
            }

            for (i in 0 until minOf(saved.length(), 80)) {
                val obj = saved.optJSONObject(i) ?: continue
                add(
                    Message(
                        text = obj.optString("text"),
                        user = obj.optBoolean("user"),
                        time = obj.optLong("time", System.currentTimeMillis())
                    )
                )
            }

            if (isEmpty()) {
                add(
                    Message(
                        "Namaste. Main JARVIS hoon. Bolo, main kaise madad karun?",
                        false
                    )
                )
            }
        }
    }

    fun persistHistory() {
        val array = JSONArray()

        messages.takeLast(80).forEach { message ->
            array.put(
                JSONObject()
                    .put("text", message.text)
                    .put("user", message.user)
                    .put("time", message.time)
            )
        }

        prefs.edit().putString("chat_history", array.toString()).apply()
    }

    fun sendMessage(text: String) {
        val question = text.trim()

        if (question.isBlank() || loading) return

        input = ""
        messages.add(Message(question, true))
        persistHistory()

        loading = true
        voiceState = "AI soch raha hai…"

        scope.launch {
            val answer = withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject()
                        .put("message", question)
                        .toString()
                        .toRequestBody(
                            "application/json; charset=utf-8".toMediaType()
                        )

                    val request = Request.Builder()
                        .url(AI_ENDPOINT)
                        .post(body)
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        val raw = response.body?.string().orEmpty()

                        val json = try {
                            JSONObject(raw)
                        } catch (_: Exception) {
                            JSONObject()
                        }

                        if (!response.isSuccessful) {
                            "AI service error (${response.code}): " +
                                json.optString(
                                    "error",
                                    raw.take(250).ifBlank { "Server error" }
                                )
                        } else {
                            json.optString("reply")
                                .takeIf { it.isNotBlank() }
                                ?: "AI service se khaali jawab mila."
                        }
                    }
                } catch (e: Exception) {
                    "JARVIS server se connect nahi ho paya. " +
                        "Internet ya AI service check karo. " +
                        "(${e.localizedMessage ?: "connection error"})"
                }
            }

            messages.add(Message(answer, false))
            persistHistory()

            loading = false
            voiceState = "Online • Ready"
            speak(answer)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF55E8FF),
            secondary = Color(0xFF6B8CFF),
            background = Color(0xFF060B17),
            surface = Color(0xFF101A2D)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF081329), Color(0xFF03060E))
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    Color(0xFF55E8FF),
                                    Color(0xFF214A85),
                                    Color.Transparent
                                )
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "J",
                        color = Color.White,
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        "J.A.R.V.I.S.",
                        color = Color(0xFF9CF3FF),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        voiceState,
                        color = Color(0xFF8DA7C7),
                        fontSize = 12.sp
                    )
                }

                TextButton(
                    onClick = {
                        tab = if (tab == "chat") "control" else "chat"
                        if (tab == "control") {
                            apps = appNames()
                            notifications = recentNotifications()
                        }
                    }
                ) {
                    Text(if (tab == "chat") "CONTROL" else "CHAT")
                }

                TextButton(onClick = { showSettings = true }) {
                    Text("SETTINGS")
                }
            }

            Spacer(Modifier.height(12.dp))

            if (tab == "chat") {
                LazyColumn(
                  
