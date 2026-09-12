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
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var persianVoiceReady = false
    private var running = false
    private var awaitingCommand = false
    private var processing = false
    private lateinit var bridgeStore: BridgePairingStore

    override fun onCreate() {
        super.onCreate()
        bridgeStore = BridgePairingStore(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("آریس آماده شنیدن نام خودش است"))
        running = true

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        }

        tts = TextToSpeech(this, this).also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { sendVoiceBeacon("tts_started") }
                override fun onDone(utteranceId: String?) {
                    sendVoiceBeacon("tts_done")
                    scheduleListen(350L)
                }
                override fun onError(utteranceId: String?) {
                    sendVoiceBeacon("tts_error")
                    main.post { updateNotification("خروجی صوتی اجرا نشد؛ روی «تنظیم صدا» بزن") }
                    scheduleListen(700L)
                }
            })
        }
        scheduleListen(500L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        scheduleListen(200L)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleListen(delayMs: Long) {
        main.postDelayed({ startListening() }, delayMs)
    }

    private fun startListening() {
        if (!running || processing) return
        val r = recognizer ?: return
        runCatching {
            r.cancel()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            r.startListening(intent)
            updateNotification(if (awaitingCommand) "گوش می‌دم؛ دستورت رو بگو" else "بگو «آریس»")
        }.onFailure { scheduleListen(1200L) }
    }

    private fun handleTranscript(raw: String) {
        val text = PersianVoiceProfile.normalize(raw).trim()
        if (text.isBlank()) return scheduleListen(400L)

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
                ?: "الان سرویس پاسخ‌گویی در دسترس نیست. چند لحظه دیگه دوباره صدام کن."
            main.post {
                processing = false
                speak(reply.take(1200))
            }
        }
    }

    private fun requestPrimaryBackend(message: String): String? = runCatching {
        val conn = (URL(CHAT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "ARIA-AlwaysOn-Voice")
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
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("apikey", HORDE_KEY)
            setRequestProperty("Client-Agent", HORDE_AGENT)
        }
        val modelsBody = modelsConn.inputStream.bufferedReader().use { it.readText() }
        modelsConn.disconnect()
        val model = JSONObject(modelsBody).optJSONArray("data")?.optJSONObject(0)?.optString("id")
            .orEmpty().ifBlank { "default" }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "تو آریس هستی، دستیار فارسی صمیمی و کاربردی. کوتاه و روشن جواب بده."))
            .put(JSONObject().put("role", "user").put("content", message))

        val conn = (URL("$HORDE_OAI/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("apikey", HORDE_KEY)
            setRequestProperty("Client-Agent", HORDE_AGENT)
            setRequestProperty("User-Agent", "ARIA-AlwaysOn-Voice-Fallback")
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

    private fun configureTts(): Boolean {
        val engine = tts ?: return false
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setSpeechRate(0.96f)
        engine.setPitch(1.0f)

        var result = engine.setLanguage(Locale("fa", "IR"))
        var ok = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        val persianVoices = engine.voices?.filter { it.locale.language.equals("fa", true) }.orEmpty()
        val preferred = persianVoices.firstOrNull { !it.isNetworkConnectionRequired } ?: persianVoices.firstOrNull()
        if (preferred != null) {
            runCatching { engine.voice = preferred }
            ok = true
        }
        if (!ok) {
            result = engine.setLanguage(Locale("fa"))
            ok = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
        persianVoiceReady = ok
        return ok
    }

    private fun speak(text: String) {
        val engine = tts
        if (!ttsReady || engine == null) {
            updateNotification("موتور صدای گوشی آماده نیست؛ روی «تنظیم صدا» بزن")
            sendVoiceBeacon("tts_not_ready")
            return scheduleListen(700L)
        }

        val audio = getSystemService(AudioManager::class.java)
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
            updateNotification("صدای Media روی صفر است؛ صدای گوشی را بالا ببر")
            sendVoiceBeacon("media_volume_zero")
            return scheduleListen(900L)
        }

        configureTts()
        recognizer?.cancel()
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "aris-${System.nanoTime()}")
        if (result == TextToSpeech.SUCCESS) {
            updateNotification(if (persianVoiceReady) "آریس در حال صحبت است" else "بسته فارسی صدا پیدا نشد؛ «تنظیم صدا» را بررسی کن")
            sendVoiceBeacon(if (persianVoiceReady) "tts_queued_fa" else "tts_queued_no_fa")
        } else {
            updateNotification("پخش صدا ناموفق بود؛ روی «تنظیم صدا» بزن")
            sendVoiceBeacon("tts_speak_failed")
            scheduleListen(900L)
        }
    }

    private fun sendVoiceBeacon(detail: String) {
        thread(name = "aris-voice-beacon", isDaemon = true) {
            runCatching {
                val device = bridgeStore.deviceId.replace(Regex("[^A-Za-z0-9_-]"), "_")
                val safe = detail.replace(Regex("[^A-Za-z0-9_-]"), "_")
                val conn = (URL("$VOICE_BEACON_BASE/$device/$safe").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("User-Agent", "ARIA-AlwaysOn-Voice")
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
        val ttsSettingsIntent = PendingIntent.getActivity(
            this, 2, Intent("com.android.settings.TTS_SETTINGS"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("آریس — Voice همیشه‌فعال")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "تنظیم صدا", ttsSettingsIntent)
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
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            val ok = configureTts()
            sendVoiceBeacon(if (ok) "tts_ready_fa" else "tts_ready_no_fa")
        } else {
            sendVoiceBeacon("tts_init_failed")
            updateNotification("موتور صدای گوشی آماده نیست؛ روی «تنظیم صدا» بزن")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onError(error: Int) {
        if (!running || processing) return
        val delay = when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 5000L
            else -> 650L
        }
        scheduleListen(delay)
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        handleTranscript(text)
    }

    companion object {
        const val ACTION_STOP = "com.orbisai.voice.STOP_ALWAYS_ON"
        private const val NOTIFICATION_ID = 3100
        private const val CHANNEL_ID = "aris_always_on_voice"
        private const val CHAT_URL = "https://aria-server-new-production.up.railway.app/api/chat"
        private const val VOICE_BEACON_BASE = "https://aria-server-new-production.up.railway.app/api/bridge/beacon/voice"
        private const val HORDE_OAI = "https://oai.aihorde.net"
        private const val HORDE_KEY = "0000000000"
        private const val HORDE_AGENT = "ARIA-Mobile:0.8.2:https://github.com/yasinyasin2338-max/ARIA-2"
        private val wakeWords = listOf("آریس", "اریس")
    }
}
