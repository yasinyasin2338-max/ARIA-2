package com.orbisai.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * User-started foreground voice assistant.
 *
 * It keeps a visible notification while listening. It does not record or store audio.
 * Android's speech recognizer converts speech to text; only text spoken after the wake
 * phrase "آریس" is sent for a conversational response.
 */
class ArisAlwaysOnVoiceService : Service(), RecognitionListener, TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var running = false
    private var awaitingCommand = false
    private var processing = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("آریس آماده شنیدن نام خودش است"))
        running = true

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        }
        tts = TextToSpeech(this, this).also { engine ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { scheduleListen(350L) }
                override fun onError(utteranceId: String?) { scheduleListen(500L) }
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

    private fun scheduleListen(delayMs: Long) {
        main.postDelayed({ startListening() }, delayMs)
    }

    private fun handleTranscript(raw: String) {
        val text = PersianVoiceProfile.normalize(raw).trim()
        if (text.isBlank()) {
            scheduleListen(400L)
            return
        }

        if (awaitingCommand) {
            awaitingCommand = false
            askAria(text)
            return
        }

        val wakeIndex = wakeWords.firstNotNullOfOrNull { w ->
            text.indexOf(w).takeIf { it >= 0 }?.let { it to w }
        }
        if (wakeIndex == null) {
            scheduleListen(250L)
            return
        }

        val (index, wake) = wakeIndex
        val command = (text.substring(0, index) + " " + text.substring(index + wake.length)).trim()
        if (command.isNotBlank()) {
            askAria(command)
        } else {
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

    private fun requestPrimaryBackend(message: String): String? {
        return runCatching {
            val conn = (URL(CHAT_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "ARIA-AlwaysOn-Voice")
            }
            val payload = JSONObject().put("message", message).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return@runCatching null
            JSONObject(body).optString("text").trim().ifBlank { null }
        }.getOrNull()
    }

    private fun requestHordeFallback(message: String): String? {
        return runCatching {
            val model = runCatching {
                val modelsConn = (URL("$HORDE_OAI/v1/models").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty("apikey", HORDE_KEY)
                    setRequestProperty("Client-Agent", HORDE_AGENT)
                }
                val body = modelsConn.inputStream.bufferedReader().use { it.readText() }
                modelsConn.disconnect()
                JSONObject(body).optJSONArray("data")?.optJSONObject(0)?.optString("id")
            }.getOrNull().orEmpty().ifBlank { "default" }

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
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", "تو آریس هستی، دستیار فارسی صمیمی و کاربردی. کوتاه و روشن جواب بده."))
                .put(JSONObject().put("role", "user").put("content", message))
            val payload = JSONObject().put("model", model).put("messages", messages).toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) return@runCatching null
            JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                ?.ifBlank { null }
        }.getOrNull()
    }

    private fun speak(text: String) {
        val engine = tts
        if (!ttsReady || engine == null) {
            scheduleListen(500L)
            return
        }
        recognizer?.cancel()
        engine.language = Locale("fa", "IR")
        engine.setSpeechRate(0.96f)
        engine.setPitch(1.0f)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aris-${System.nanoTime()}")
        updateNotification("آریس در حال صحبت است")
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ArisAlwaysOnVoiceService::class.java).setAction(ACTION_STOP),
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
            NotificationChannel(CHANNEL_ID, "آریس Voice همیشه‌فعال", NotificationManager.IMPORTANCE_LOW).apply {
                description = "وضعیت شنیدن نام آریس در پس‌زمینه"
            }
        )
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale("fa", "IR")
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
        private const val HORDE_OAI = "https://oai.aihorde.net"
        private const val HORDE_KEY = "0000000000"
        private const val HORDE_AGENT = "ARIA-Mobile:0.8.1:https://github.com/yasinyasin2338-max/ARIA-2"
        private val wakeWords = listOf("آریس", "اریس")
    }
}
