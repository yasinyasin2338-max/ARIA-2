package ai.orbis.office

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
)

object OrbisAgents {
    val arian = Agent("arian", "آرین", "تحقیق و تحلیل", "جستجو، تحلیل، داده و گزارش", "تو آرین هستی؛ تحلیل‌گر دقیق تیم Orbis. پاسخ‌ها را به فارسی، مستند، مرحله‌بندی‌شده و کاربردی بده. اگر داده کافی نیست صریح بگو. برای تصمیم‌های حساس فقط پیشنهاد بده و بدون تأیید کار برگشت‌ناپذیر انجام نده.", 0.92f, 0.96f)
    val raha = Agent("raha", "رها", "خلاقیت و محتوا", "نوشتن، طراحی، تصویر، ایده و برند", "تو رها هستی؛ مدیر خلاقیت تیم Orbis. به فارسی طبیعی، خلاق و حرفه‌ای پاسخ بده. برای محتوا و طراحی چند گزینه قوی بده و هدف کاربر را در اولویت قرار بده.", 1.08f, 1.02f)
    val kian = Agent("kian", "کیان", "اجرا و اتوماسیون", "برنامه‌ریزی، کارها، اتصال‌ها و اجرا", "تو کیان هستی؛ مدیر اجرایی تیم Orbis. درخواست‌ها را به برنامه عملی قابل اجرا تبدیل کن، ریسک‌ها و وابستگی‌ها را مشخص کن و قبل از خرید، پرداخت، انتشار یا ارسال نهایی تأیید کاربر را بگیر.", 0.98f, 0.98f)
    val all = listOf(arian, raha, kian)
}

data class ProviderConfig(val baseUrl: String, val model: String, val apiKey: String)

class OpenAiCompatibleClient {
    suspend fun chat(config: ProviderConfig, agent: Agent, history: List<Pair<String,String>>, text: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.baseUrl.isNotBlank()) { "آدرس سرویس هوش مصنوعی تنظیم نشده است." }
            require(config.model.isNotBlank()) { "نام مدل تنظیم نشده است." }
            val endpoint = config.baseUrl.trimEnd('/') + "/chat/completions"
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", agent.systemPrompt))
            history.takeLast(12).forEach { (role, content) -> messages.put(JSONObject().put("role", role).put("content", content)) }
            messages.put(JSONObject().put("role", "user").put("content", text))
            val body = JSONObject().put("model", config.model).put("messages", messages).put("temperature", if (agent.id == "raha") 0.85 else 0.45).toString()
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (config.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("خطای سرویس AI ($code): ${raw.take(180)}")
            JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        }
    }
}

class VoiceController(private val context: Context) : RecognitionListener, TextToSpeech.OnInitListener {
    private val recognizer = runCatching {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }.getOrNull()
    private var tts: TextToSpeech? = null
    private var ready = false
    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onState: (String) -> Unit = {}
    var onErrorText: (String) -> Unit = {}

    init {
        runCatching { recognizer?.setRecognitionListener(this) }
        // Some Samsung TTS engines can invoke onInit very quickly. Keep the field nullable
        // so a synchronous callback cannot dereference an unassigned property.
        tts = runCatching { TextToSpeech(context.applicationContext, this) }.getOrNull()
    }

    fun listen() {
        val r = recognizer ?: run { onErrorText("تشخیص گفتار روی این دستگاه در دسترس نیست."); return }
        tts?.stop()
        onState("در حال شنیدن")
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { r.startListening(i) }.onFailure {
            onState("آماده")
            onErrorText("شروع Voice ناموفق بود.")
        }
    }

    fun stop() {
        runCatching { recognizer?.stopListening() }
        onState("آماده")
    }

    fun speak(text: String, agent: Agent) {
        val engine = tts
        if (!ready || engine == null) { onErrorText("صدای فارسی دستگاه آماده نیست."); return }
        runCatching { recognizer?.cancel() }
        onState("در حال صحبت")
        engine.language = Locale.forLanguageTag("fa-IR")
        engine.setPitch(agent.pitch)
        engine.setSpeechRate(agent.rate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "orbis-${System.nanoTime()}")
    }

    fun close() {
        runCatching { recognizer?.destroy() }
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) runCatching { tts?.language = Locale.forLanguageTag("fa-IR") }
    }
    override fun onReadyForSpeech(params: Bundle?) { onState("در حال شنیدن") }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() { onState("در حال پردازش") }
    override fun onError(error: Int) { onState("آماده"); if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_CLIENT) onErrorText("خطای Voice؛ دوباره تلاش کن.") }
    override fun onResults(results: Bundle?) { val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); onState("آماده"); if (t.isNotBlank()) onFinal(t) }
    override fun onPartialResults(partialResults: Bundle?) { partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial) }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
