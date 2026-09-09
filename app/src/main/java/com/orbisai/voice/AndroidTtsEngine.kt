package com.orbisai.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Android TTS adapter with Persian locale, cancellation, and per-agent prosody. */
class AndroidTtsEngine(context: Context) : AutoCloseable {
    private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts.language = Locale.forLanguageTag(PersianVoiceProfile.localeTag)
        }
    }

    suspend fun speak(text: String, voice: PersianAgentVoice): Boolean = suspendCancellableCoroutine { cont ->
        if (!ready) { cont.resume(false); return@suspendCancellableCoroutine }
        tts.language = Locale.forLanguageTag(voice.localeTag)
        tts.setSpeechRate(voice.rate)
        tts.setPitch(voice.pitch)
        val utteranceId = "orbis-${System.nanoTime()}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { if (id == utteranceId && cont.isActive) cont.resume(true) }
            override fun onError(id: String?) { if (id == utteranceId && cont.isActive) cont.resume(false) }
        })
        tts.speak(PersianVoiceProfile.normalize(text), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        cont.invokeOnCancellation { tts.stop() }
    }

    fun stop() { if (::tts.isInitialized) tts.stop() }
    override fun close() { if (::tts.isInitialized) { tts.stop(); tts.shutdown() } }
}
