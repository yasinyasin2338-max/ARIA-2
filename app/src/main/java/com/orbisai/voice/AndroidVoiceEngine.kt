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

/** Robust Android bridge for Persian conversational voice: partial STT, cancellable TTS and barge-in. */
class AndroidVoiceEngine(context: Context): VoiceEngine, RecognitionListener, TextToSpeech.OnInitListener {
 private val appContext=context.applicationContext
 private val recognizer=if(SpeechRecognizer.isRecognitionAvailable(appContext)) SpeechRecognizer.createSpeechRecognizer(appContext) else null
 private val tts=TextToSpeech(appContext,this)
 private val _events=MutableSharedFlow<VoiceEvent>(extraBufferCapacity=96)
 override val events=_events.asSharedFlow()
 private var ttsReady=false
 private var listening=false
 private var generation=0L
 private var activeUtterance:String?=null
 init{recognizer?.setRecognitionListener(this);tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){override fun onStart(id:String?){if(id==activeUtterance)_events.tryEmit(VoiceEvent.SpeechStarted(id.orEmpty()))};override fun onDone(id:String?){if(id==activeUtterance){activeUtterance=null;_events.tryEmit(VoiceEvent.SpeechFinished)}};override fun onError(id:String?){if(id==activeUtterance){activeUtterance=null;_events.tryEmit(VoiceEvent.SpeechFinished)}}})}
 override suspend fun startListening(){val r=recognizer?:run{_events.tryEmit(VoiceEvent.Error("تشخیص گفتار روی این دستگاه در دسترس نیست."));return};generation++;tts.stop();activeUtterance=null;listening=true;_events.tryEmit(VoiceEvent.ListeningStarted);runCatching{r.cancel();val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,"fa-IR");putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"fa-IR");putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE,"fa-IR");putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,850);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,600);putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,appContext.packageName)};r.startListening(i)}.onFailure{listening=false;_events.tryEmit(VoiceEvent.Error("شروع مکالمهٔ صوتی ناموفق بود."))}}
 override suspend fun stopListening(){listening=false;recognizer?.stopListening();_events.tryEmit(VoiceEvent.ListeningStopped)}
 override suspend fun cancel(){generation++;listening=false;recognizer?.cancel();tts.stop();activeUtterance=null;_events.tryEmit(VoiceEvent.ListeningStopped);_events.tryEmit(VoiceEvent.SpeechFinished)}
 override suspend fun speak(text:String){if(!ttsReady||text.isBlank())return;val my=++generation;listening=false;recognizer?.cancel();tts.stop();tts.language=Locale("fa","IR");tts.setSpeechRate(.94f);tts.setPitch(1.0f);val id="orbis-$my";activeUtterance=id;_events.tryEmit(VoiceEvent.ThinkingStarted);val result=tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,id);if(result!=TextToSpeech.SUCCESS){activeUtterance=null;_events.tryEmit(VoiceEvent.Error("موتور صدای فارسی در دسترس نیست."))}}
 fun release(){generation++;listening=false;recognizer?.destroy();tts.stop();tts.shutdown()}
 override fun onInit(status:Int){ttsReady=status==TextToSpeech.SUCCESS;if(ttsReady){val result=tts.setLanguage(Locale("fa","IR"));if(result==TextToSpeech.LANG_MISSING_DATA||result==TextToSpeech.LANG_NOT_SUPPORTED)_events.tryEmit(VoiceEvent.Error("صدای فارسی روی دستگاه نصب نیست."));tts.setSpeechRate(.94f)}}
 override fun onReadyForSpeech(p:Bundle?)=Unit
 override fun onBeginningOfSpeech(){tts.stop();activeUtterance=null;_events.tryEmit(VoiceEvent.ListeningStarted)}
 override fun onRmsChanged(v:Float)=Unit
 override fun onBufferReceived(b:ByteArray?)=Unit
 override fun onEndOfSpeech(){listening=false;_events.tryEmit(VoiceEvent.ListeningStopped)}
 override fun onError(e:Int){listening=false;_events.tryEmit(VoiceEvent.ListeningStopped);if(e!=SpeechRecognizer.ERROR_CLIENT&&e!=SpeechRecognizer.ERROR_NO_MATCH)_events.tryEmit(VoiceEvent.Error("خطای تشخیص گفتار؛ دوباره تلاش می‌کنیم."))}
 override fun onResults(b:Bundle?){val text=b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(PersianVoiceProfile::normalize);if(!text.isNullOrBlank())_events.tryEmit(VoiceEvent.FinalTranscript(text))else _events.tryEmit(VoiceEvent.Error("متوجه گفتارت نشدم؛ دوباره بگو."))}
 override fun onPartialResults(b:Bundle?){val text=b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(PersianVoiceProfile::normalize);if(!text.isNullOrBlank())_events.tryEmit(VoiceEvent.PartialTranscript(text))}
 override fun onEvent(t:Int,p:Bundle?)=Unit
}
