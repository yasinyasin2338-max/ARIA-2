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

/** Android voice bridge with Persian partial STT, cancellable TTS and session-safe barge-in. */
class AndroidVoiceEngine(context: Context):VoiceEngine,RecognitionListener,TextToSpeech.OnInitListener{
 private val appContext=context.applicationContext
 private val recognizer:SpeechRecognizer?=if(SpeechRecognizer.isRecognitionAvailable(appContext))SpeechRecognizer.createSpeechRecognizer(appContext)else null
 private val tts=TextToSpeech(appContext,this);private val _events=MutableSharedFlow<VoiceEvent>(extraBufferCapacity=64);override val events=_events.asSharedFlow();private var ttsReady=false;private var session=0L
 init{recognizer?.setRecognitionListener(this);tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){override fun onStart(id:String?){ };override fun onDone(id:String?){_events.tryEmit(VoiceEvent.SpeechFinished)};override fun onError(id:String?){_events.tryEmit(VoiceEvent.SpeechFinished)}})}
 override suspend fun startListening(){val r=recognizer?:run{_events.tryEmit(VoiceEvent.Error("تشخیص گفتار روی این دستگاه در دسترس نیست."));return};session++;tts.stop();_events.tryEmit(VoiceEvent.ListeningStarted);runCatching{r.cancel();val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,"fa-IR");putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"fa-IR");putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,900);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,650)};r.startListening(i)}.onFailure{_events.tryEmit(VoiceEvent.Error("شروع Voice Mode ناموفق بود."))}}
 override suspend fun stopListening(){recognizer?.stopListening();_events.tryEmit(VoiceEvent.ListeningStopped)}
 override suspend fun cancel(){session++;recognizer?.cancel();tts.stop();_events.tryEmit(VoiceEvent.ListeningStopped);_events.tryEmit(VoiceEvent.SpeechFinished)}
 override suspend fun speak(text:String){if(!ttsReady||text.isBlank())return;val my=++session;recognizer?.cancel();tts.stop();tts.language=Locale("fa","IR");tts.setSpeechRate(.94f);tts.setPitch(1f);_events.tryEmit(VoiceEvent.SpeechStarted(text));tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"orbis-$my")}
 fun release(){session++;recognizer?.destroy();tts.stop();tts.shutdown()}
 override fun onInit(status:Int){ttsReady=status==TextToSpeech.SUCCESS;if(ttsReady){tts.language=Locale("fa","IR");tts.setSpeechRate(.94f)}}
 override fun onReadyForSpeech(p:Bundle?)=Unit;override fun onBeginningOfSpeech(){tts.stop()};override fun onRmsChanged(v:Float)=Unit;override fun onBufferReceived(b:ByteArray?)=Unit;override fun onEndOfSpeech(){_events.tryEmit(VoiceEvent.ListeningStopped)}
 override fun onError(e:Int){_events.tryEmit(VoiceEvent.ListeningStopped);if(e!=SpeechRecognizer.ERROR_CLIENT)_events.tryEmit(VoiceEvent.Error("خطای تشخیص گفتار ($e)"))}
 override fun onResults(b:Bundle?){val text=b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(PersianVoiceProfile::normalize);if(!text.isNullOrBlank())_events.tryEmit(VoiceEvent.FinalTranscript(text))}
 override fun onPartialResults(b:Bundle?){val text=b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(PersianVoiceProfile::normalize);if(!text.isNullOrBlank())_events.tryEmit(VoiceEvent.PartialTranscript(text))}
 override fun onEvent(t:Int,p:Bundle?)=Unit
}
