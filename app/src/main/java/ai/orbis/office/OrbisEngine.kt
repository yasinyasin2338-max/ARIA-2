package ai.orbis.office

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class Agent(
    val id: String,
    val name: String,
    val role: String,
    val specialty: String,
    val systemPrompt: String,
    val pitch: Float,
    val rate: Float,
    val accent: Int,
)

object OrbisAgents {
    val arian = Agent(
        id = "arian",
        name = "آرین",
        role = "تحقیق و تحلیل",
        specialty = "جستجو، تحلیل، داده و گزارش",
        systemPrompt = """تو آرین، تحلیل‌گر ارشد Orbis AI هستی. پاسخ را فارسی، دقیق، منظم و کاربردی بده. واقعیت را از حدس جدا کن. اگر داده کافی نیست صریح بگو. برای تحقیق، منابع یا روش بررسی را پیشنهاد بده. برای تصمیم‌های حساس هیچ اقدام برگشت‌ناپذیری را بدون تأیید کاربر توصیه به اجرای مستقیم نکن.""",
        pitch = 0.86f,
        rate = 0.94f,
        accent = 0xFF3479F6.toInt(),
    )
    val raha = Agent(
        id = "raha",
        name = "رها",
        role = "خلاقیت و محتوا",
        specialty = "نوشتن، طراحی، تصویر، ایده و برند",
        systemPrompt = """تو رها، مدیر خلاقیت Orbis AI هستی. فارسی طبیعی، حرفه‌ای و خلاق بنویس. هدف، مخاطب و لحن را در نظر بگیر و برای کار خلاق چند گزینه قوی پیشنهاد بده. وقتی ابهام مهمی وجود دارد آن را کوتاه مشخص کن.""",
        pitch = 1.08f,
        rate = 1.02f,
        accent = 0xFF9B4DFF.toInt(),
    )
    val kian = Agent(
        id = "kian",
        name = "کیان",
        role = "اجرا و اتوماسیون",
        specialty = "برنامه‌ریزی، کارها، اتصال‌ها و اجرا",
        systemPrompt = """تو کیان، مدیر اجرایی Orbis AI هستی. درخواست‌ها را به برنامه عملی، مراحل، وابستگی‌ها و خروجی تبدیل کن. وضعیت و ریسک‌ها را روشن کن. قبل از خرید، پرداخت، انتشار عمومی، ارسال نهایی یا هر اقدام حساس تأیید صریح کاربر را لازم بدان.""",
        pitch = 0.96f,
        rate = 0.98f,
        accent = 0xFF18C896.toInt(),
    )
    val all = listOf(arian, raha, kian)
    fun byId(id: String?): Agent = all.firstOrNull { it.id == id } ?: arian
}

data class ProviderConfig(val baseUrl: String, val model: String, val apiKey: String)

class OpenAiCompatibleClient {
    fun chat(config: ProviderConfig, agent: Agent, history: List<Pair<String, String>>, text: String): Result<String> = runCatching {
        require(config.baseUrl.isNotBlank()) { "آدرس سرویس هوش مصنوعی تنظیم نشده است." }
        require(config.model.isNotBlank()) { "نام مدل تنظیم نشده است." }
        val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", agent.systemPrompt))
        history.takeLast(18).forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", text))
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", if (agent.id == "raha") 0.82 else 0.42)
            .toString()
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 75_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (config.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) error("خطای سرویس AI ($code): ${raw.take(220)}")
        val json = JSONObject(raw)
        json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
    }
}

class VoiceModeController(private val activity: Activity) : RecognitionListener {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var active = false
    private var muted = false
    private var agent: Agent = OrbisAgents.arian
    private var lastListenAt = 0L

    var onState: (State) -> Unit = {}
    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onRms: (Float) -> Unit = {}
    var onErrorText: (String) -> Unit = {}

    fun start(selected: Agent) {
        agent = selected
        active = true
        ensureSpeechRecognizer()
        ensureTts()
        listenSoon(200)
    }

    fun setAgent(selected: Agent) {
        agent = selected
        if (active) {
            cancelRecognition()
            listenSoon(250)
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
        if (muted) {
            runCatching { tts?.stop() }
            cancelRecognition()
            onState(State.IDLE)
        } else if (active) {
            listenSoon(200)
        }
    }

    fun isMuted(): Boolean = muted

    fun markThinking() {
        if (!active) return
        cancelRecognition()
        onState(State.THINKING)
    }

    fun speak(text: String) {
        if (!active) return
        if (muted) {
            listenSoon(250)
            return
        }
        ensureTts()
        val engine = tts
        if (!ttsReady || engine == null) {
            onErrorText("موتور صدای فارسی هنوز آماده نیست؛ پاسخ به صورت متن نمایش داده شد.")
            listenSoon(400)
            return
        }
        cancelRecognition()
        runCatching {
            engine.language = Locale.forLanguageTag("fa-IR")
            chooseVoice(engine, agent)
            engine.setPitch(agent.pitch)
            engine.setSpeechRate(agent.rate)
            onState(State.SPEAKING)
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "orbis-${System.nanoTime()}")
            if (result == TextToSpeech.ERROR) {
                onErrorText("پخش صدا شروع نشد؛ پاسخ متنی آماده است.")
                listenSoon(400)
            }
        }.onFailure {
            onErrorText("پخش پاسخ صوتی روی این دستگاه در دسترس نیست.")
            listenSoon(400)
        }
    }

    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        cancelRecognition()
        runCatching { tts?.stop() }
        onState(State.IDLE)
    }

    fun close() {
        stop()
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    private fun ensureSpeechRecognizer() {
        if (recognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            onErrorText("سرویس تشخیص گفتار روی گوشی پیدا نشد.")
            return
        }
        recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(activity).also { it.setRecognitionListener(this) }
        }.getOrElse {
            onErrorText("راه‌اندازی تشخیص گفتار ناموفق بود.")
            null
        }
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = runCatching {
            TextToSpeech(activity.applicationContext) { status ->
                main.post {
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (ttsReady) {
                        runCatching { tts?.language = Locale.forLanguageTag("fa-IR") }
                    }
                }
            }.also { engine ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        main.post { if (active && !muted) listenSoon(280) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post {
                            onErrorText("پخش صدا کامل نشد.")
                            if (active && !muted) listenSoon(350)
                        }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        main.post {
                            onErrorText("پخش صدا کامل نشد.")
                            if (active && !muted) listenSoon(350)
                        }
                    }
                })
            }
        }.getOrElse {
            onErrorText("موتور تبدیل متن به گفتار راه‌اندازی نشد.")
            null
        }
    }

    private fun chooseVoice(engine: TextToSpeech, selected: Agent) {
        val voices = runCatching {
            engine.voices?.filter { it.locale?.language == "fa" }?.sortedBy { it.name }.orEmpty()
        }.getOrDefault(emptyList())
        if (voices.isNotEmpty()) {
            val index = when (selected.id) { "arian" -> 0; "raha" -> 1; else -> 2 } % voices.size
            runCatching { engine.voice = voices[index] }
        }
    }

    private fun listenSoon(delay: Long) {
        if (!active || muted) return
        main.postDelayed({ beginListening() }, delay)
    }

    private fun beginListening() {
        if (!active || muted) return
        ensureSpeechRecognizer()
        val r = recognizer ?: return
        val now = System.currentTimeMillis()
        if (now - lastListenAt < 250) {
            listenSoon(300)
            return
        }
        lastListenAt = now
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        }
        runCatching {
            r.cancel()
            r.startListening(intent)
            onState(State.LISTENING)
        }.onFailure {
            onErrorText("شروع شنیدن ناموفق بود؛ دوباره تلاش می‌کنم.")
            listenSoon(700)
        }
    }

    private fun cancelRecognition() {
        runCatching { recognizer?.cancel() }
    }

    override fun onReadyForSpeech(params: Bundle?) { if (active) onState(State.LISTENING) }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) { if (active) onRms(rmsdB) }
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() { if (active) onState(State.THINKING) }
    override fun onError(error: Int) {
        if (!active || muted) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenSoon(400)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> listenSoon(800)
            SpeechRecognizer.ERROR_CLIENT -> listenSoon(600)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onErrorText("اجازه میکروفون داده نشده است.")
            else -> {
                onErrorText("Voice موقتاً قطع شد؛ دوباره تلاش می‌کنم.")
                listenSoon(900)
            }
        }
    }
    override fun onResults(results: Bundle?) {
        if (!active) return
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
        if (text.isNotBlank()) onFinal(text) else listenSoon(350)
    }
    override fun onPartialResults(partialResults: Bundle?) {
        if (!active) return
        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
            if (it.isNotBlank()) onPartial(it)
        }
    }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
