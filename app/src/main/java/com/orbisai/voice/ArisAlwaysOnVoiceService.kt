package com.orbisai.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.orbisai.MainActivity
import com.orbisai.bridge.BridgePairingStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class ArisAlwaysOnVoiceService : Service(), RecognitionListener, TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var localTts: TextToSpeech? = null
    private lateinit var neuralTts: NeuralTtsPlayer
    private var localTtsReady = false
    private var localPersianReady = false
    private var running = false
    private var awaitingCommand = false
    private var processing = false
    private var speaking = false
    private lateinit var bridgeStore: BridgePairingStore

    override fun onCreate() {
        super.onCreate()
        bridgeStore = BridgePairingStore(this)
        neuralTts = NeuralTtsPlayer(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("آریس آماده است؛ بگو «آریس»"))
        running = true
        sendVoiceBeacon("voice_service_created")

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        }

        localTts = TextToSpeech(this, this).also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { sendVoiceBeacon("local_tts_started") }
                override fun onDone(utteranceId: String?) {
                    main.post {
                        speaking = false
                        sendVoiceBeacon("local_tts_done")
                        scheduleListen(300L)
                    }
                }
                override fun onError(utteranceId: String?) {
                    main.post {
                        speaking = false
                        sendVoiceBeacon("local_tts_error")
                        scheduleListen(500L)
                    }
                }
            })
        }
        scheduleListen(600L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            bridgeStore.voiceEnabled = false
            sendVoiceBeacon("voice_stopped_by_user")
            stopSelf()
            return START_NOT_STICKY
        }
        bridgeStore.voiceEnabled = true
        running = true
        sendVoiceBeacon(if (intent == null) "voice_service_restarted" else "voice_service_start")
        scheduleListen(250L)
        return START_STICKY
    }

    override fun onDestroy() {
        sendVoiceBeacon(if (bridgeStore.voiceEnabled) "voice_service_destroyed_unexpected" else "voice_service_destroyed")
        running = false
        processing = false
        speaking = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        neuralTts.close()
        localTts?.stop()
        localTts?.shutdown()
        localTts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleListen(delayMs: Long) {
        if (!running) return
        main.postDelayed({ startListening() }, delayMs)
    }

    private fun startListening() {
        if (!running || processing || speaking) return
        val r = recognizer ?: return
        runCatching {
            r.cancel()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            r.startListening(intent)
            updateNotification(if (awaitingCommand) "گوش می‌دم؛ دستورت رو بگو" else "بگو «آریس»")
        }.onFailure { scheduleListen(1200L) }
    }

    private fun handleTranscript(raw: String) {
        val text = PersianVoiceProfile.normalize(raw).trim()
        if (text.isBlank()) return scheduleListen(300L)

        if (awaitingCommand) {
            awaitingCommand = false
            askAria(text)
            return
        }

        val match = wakeWords.firstNotNullOfOrNull { wake ->
            text.indexOf(wake).takeIf { it >= 0 }?.let { it to wake }
        }
        if (match == null) return scheduleListen(250L)

        val (index, wake) = match
        val command = (text.substring(0, index) + " " + text.substring(index + wake.length)).trim()
        if (command.isNotBlank()) askAria(command)
        else {
            awaitingCommand = true
            speak("بله، گوش می‌دم.")
        }
    }

    private fun askAria(message: String) {
        processing = true
        recognizer?.cancel()
        updateNotification("در حال پاسخ به: ${message.take(45)}")
        thread(name = "aris-voice-chat", isDaemon = true) {
            val reply = requestPrimaryBackend(message)
                ?: requestHordeFallback(message)
                ?: "الان پاسخ‌گویی آنلاین در دسترس نیست. دوباره صدام کن."
            main.post {
                processing = false
                speak(reply.take(1800))
            }
        }
    }

    private fun requestPrimaryBackend(message: String): String? = runCatching {
        val conn = (URL(CHAT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 35_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "ARIA-AlwaysOn-Voice/0.9.1")
        }
        conn.outputStream.use {
            it.write(JSONObject().put("message", message).toString().toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) null else JSONObject(body).optString("text").trim().ifBlank { null }
    }.getOrNull()

    private fun requestHordeFallback(message: String): String? = runCatching {
        val modelsConn = (URL("$HORDE_OAI/v1/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("apikey", HORDE_KEY)
            setRequestProperty("Client-Agent", HORDE_AGENT)
        }
        val modelsBody = modelsConn.inputStream.bufferedReader().use { it.readText() }
        modelsConn.disconnect()
        val model = JSONObject(modelsBody).optJSONArray("data")?.optJSONObject(0)?.optString("id")
            .orEmpty().ifBlank { "default" }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "تو آریس هستی؛ یک دستیار فارسی صمیمی، روشن و کاربردی. فارسی طبیعی و محاوره‌ای جواب بده و بی‌دلیل تکرار نکن."))
            .put(JSONObject().put("role", "user").put("content", message))

        val conn = (URL("$HORDE_OAI/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("apikey", HORDE_KEY)
            setRequestProperty("Client-Agent", HORDE_AGENT)
            setRequestProperty("User-Agent", "ARIA-AlwaysOn-Voice-Fallback/0.9.1")
        }
        val payload = JSONObject().put("model", model).put("messages", messages).toString()
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) null else JSONObject(body)
            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.optString("content")?.trim()?.ifBlank { null }
    }.getOrNull()

    private fun speak(text: String) {
        if (!running || text.isBlank()) return
        recognizer?.cancel()
        speaking = true
        updateNotification("آریس در حال آماده‌کردن صداست…")
        sendVoiceBeacon("neural_tts_request")
        neuralTts.speak(
            text = text,
            onStarted = {
                updateNotification("آریس در حال صحبت است")
                sendVoiceBeacon("neural_tts_started")
            },
            onDone = {
                speaking = false
                sendVoiceBeacon("neural_tts_done")
                scheduleListen(300L)
            },
            onError = {
                if (localPersianReady) {
                    sendVoiceBeacon("neural_tts_fallback_fa")
                    localSpeak(text)
                } else {
                    speaking = false
                    updateNotification("صدای فارسی موقتاً در دسترس نیست")
                    sendVoiceBeacon("neural_tts_failed_no_local_fa")
                    scheduleListen(600L)
                }
            }
        )
    }

    private fun localSpeak(text: String) {
        val engine = localTts
        if (!localTtsReady || engine == null || !localPersianReady) {
            speaking = false
            updateNotification("خروجی صوتی فارسی در دسترس نیست")
            sendVoiceBeacon("tts_not_ready")
            scheduleListen(600L)
            return
        }
        val audio = getSystemService(AudioManager::class.java)
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
            speaking = false
            updateNotification("صدای Media روی صفر است")
            sendVoiceBeacon("media_volume_zero")
            scheduleListen(700L)
            return
        }
        configureLocalTts()
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "aris-local-${System.nanoTime()}")
        if (result != TextToSpeech.SUCCESS) {
            speaking = false
            sendVoiceBeacon("local_tts_speak_failed")
            scheduleListen(600L)
        } else {
            sendVoiceBeacon("local_tts_queued_fa")
        }
    }

    private fun configureLocalTts(): Boolean {
        val engine = localTts ?: return false
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setSpeechRate(0.97f)
        engine.setPitch(1.0f)
        var result = engine.setLanguage(Locale("fa", "IR"))
        var ok = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        val voices = engine.voices?.filter { it.locale.language.equals("fa", true) }.orEmpty()
        val preferred = voices.firstOrNull { !it.isNetworkConnectionRequired } ?: voices.firstOrNull()
        if (preferred != null) {
            runCatching { engine.voice = preferred }
            ok = true
        }
        localPersianReady = ok
        return ok
    }

    private fun sendVoiceBeacon(detail: String) {
        thread(name = "aris-voice-beacon", isDaemon = true) {
            runCatching {
                val device = bridgeStore.deviceId.replace(Regex("[^A-Za-z0-9_-]"), "_")
                val safe = detail.replace(Regex("[^A-Za-z0-9_-]"), "_")
                val conn = (URL("$VOICE_BEACON_BASE/$device/$safe").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("User-Agent", "ARIA-AlwaysOn-Voice/0.9.1")
                }
                conn.responseCode
                conn.disconnect()
            }
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, ArisAlwaysOnVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("آریس — Voice همیشه‌فعال")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "خاموش کردن", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "آریس Voice همیشه‌فعال", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onInit(status: Int) {
        localTtsReady = status == TextToSpeech.SUCCESS
        if (localTtsReady) {
            val ok = configureLocalTts()
            sendVoiceBeacon(if (ok) "local_tts_ready_fa" else "local_tts_ready_no_fa")
        } else sendVoiceBeacon("local_tts_init_failed")
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onError(error: Int) {
        if (!running || processing || speaking) return
        val delay = when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1400L
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 5000L
            else -> 650L
        }
        scheduleListen(delay)
    }

    override fun onResults(results: Bundle?) {
        if (processing || speaking) return
        val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        handleTranscript(candidates.firstOrNull().orEmpty())
    }

    companion object {
        const val ACTION_STOP = "com.orbisai.voice.STOP_ALWAYS_ON"
        private const val NOTIFICATION_ID = 3100
        private const val CHANNEL_ID = "aris_always_on_voice"
        private const val CHAT_URL = "https://aria-server-new-production.up.railway.app/api/chat"
        private const val VOICE_BEACON_BASE = "https://aria-server-new-production.up.railway.app/api/bridge/beacon/voice"
        private const val HORDE_OAI = "https://oai.aihorde.net"
        private const val HORDE_KEY = "0000000000"
        private const val HORDE_AGENT = "ARIA-Mobile:0.9.1:https://github.com/yasinyasin2338-max/ARIA-2"
        private val wakeWords = listOf("آریس", "اریس")
    }
}
