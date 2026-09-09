package com.orbisai.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale

/** Real Android voice bridge: Persian partial STT + cancellable TTS + speech completion events. */
class AndroidVoiceEngine(context: Context) : VoiceEngine, RecognitionListener, TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val recognizer: SpeechRecognizer? = if (SpeechRecognizer.isRecognitionAvailable(appContext)) SpeechRecognizer.createSpeechRecognizer(appContext) else null
    private val tts = TextToSpeech(appContext, this)
    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 32)
    override val events = _events.asSharedFlow()
    private var ttsReady = false

    init {
        recognizer?.setRecognitionListener(this)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { _events.tryEmit(VoiceEvent.SpeechFinished) }
            override fun onError(utteranceId: String?) { _events.tryEmit(VoiceEvent.SpeechFinished) }
        })
    }

    override suspend fun startListening() {
        tts.stop()
        val r = recognizer ?: run { _events.tryEmit(VoiceEvent.Error("تشخیص گفتار روی این دستگاه در دسترس نیست.")); return }
        _events.tryEmit(VoiceEvent.ListeningStarted)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        r.startListening(intent)
    }

    override suspend fun stopListening() {
        recognizer?.stopListening()
        _events.tryEmit(VoiceEvent.ListeningStopped)
    }

    override suspend fun cancel() {
        recognizer?.cancel()
        tts.stop()
        _events.tryEmit(VoiceEvent.ListeningStopped)
        _events.tryEmit(VoiceEvent.SpeechFinished)
    }

    override suspend fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        recognizer?.cancel()
        tts.stop()
        tts.language = Locale("fa", "IR")
        tts.setSpeechRate(0.96f)
        tts.setPitch(1.0f)
        _events.tryEmit(VoiceEvent.SpeechStarted(text))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "orbis-${System.nanoTime()}")
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

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() { _events.tryEmit(VoiceEvent.ListeningStopped) }
    override fun onError(error: Int) {
        _events.tryEmit(VoiceEvent.ListeningStopped)
        _events.tryEmit(VoiceEvent.Error("خطای تشخیص گفتار ($error)"))
    }
    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(PersianVoiceProfile::normalize)
        if (!text.isNullOrBlank()) _events.tryEmit(VoiceEvent.FinalTranscript(text))
    }
    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(PersianVoiceProfile::normalize)
        if (!text.isNullOrBlank()) _events.tryEmit(VoiceEvent.PartialTranscript(text))
    }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
