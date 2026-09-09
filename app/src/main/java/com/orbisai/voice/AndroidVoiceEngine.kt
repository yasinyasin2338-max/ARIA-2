package com.orbisai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale

/** Android voice bridge: Persian STT with partial results + interruptible TTS. */
class AndroidVoiceEngine(context: Context) : VoiceEngine, RecognitionListener, TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val recognizer: SpeechRecognizer? = if (SpeechRecognizer.isRecognitionAvailable(appContext)) SpeechRecognizer.createSpeechRecognizer(appContext) else null
    private val tts = TextToSpeech(appContext, this)
    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 32)
    override val events = _events.asSharedFlow()
    private var ttsReady = false
    private var listening = false

    init { recognizer?.setRecognitionListener(this) }

    override suspend fun startListening() {
        tts.stop()
        _events.tryEmit(VoiceEvent.ListeningStarted)
        val r = recognizer ?: run { _events.tryEmit(VoiceEvent.Error("تشخیص گفتار روی این دستگاه در دسترس نیست.")); return }
        listening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        r.startListening(intent)
    }

    override suspend fun stopListening() {
        listening = false
        recognizer?.stopListening()
        _events.tryEmit(VoiceEvent.ListeningStopped)
    }

    override suspend fun cancel() {
        listening = false
        recognizer?.cancel()
        tts.stop()
        _events.tryEmit(VoiceEvent.ListeningStopped)
    }

    override suspend fun speak(text: String) {
        if (!ttsReady) return
        tts.stop()
        tts.language = Locale("fa", "IR")
        tts.setSpeechRate(0.96f)
        tts.setPitch(1.0f)
        _events.tryEmit(VoiceEvent.SpeechStarted(text))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "orbis-response-${System.nanoTime()}")
    }

    fun release() {
        recognizer?.destroy()
        tts.stop()
        tts.shutdown()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts.language = Locale("fa", "IR")
    }

    override fun onReadyForSpeech(params: Bundle?) { }
    override fun onBeginningOfSpeech() { }
    override fun onRmsChanged(rmsdB: Float) { }
    override fun onBufferReceived(buffer: ByteArray?) { }
    override fun onEndOfSpeech() { listening = false; _events.tryEmit(VoiceEvent.ListeningStopped) }
    override fun onError(error: Int) { listening = false; _events.tryEmit(VoiceEvent.ListeningStopped); _events.tryEmit(VoiceEvent.Error("خطای تشخیص گفتار ($error)")) }
    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(PersianVoiceProfile::normalize)
        if (!text.isNullOrBlank()) _events.tryEmit(VoiceEvent.FinalTranscript(text))
    }
    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(PersianVoiceProfile::normalize)
        if (!text.isNullOrBlank()) _events.tryEmit(VoiceEvent.PartialTranscript(text))
    }
    override fun onEvent(eventType: Int, params: Bundle?) { }
}
