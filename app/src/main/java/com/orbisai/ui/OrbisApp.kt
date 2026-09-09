package com.orbisai.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbisai.ai.*
import com.orbisai.model.*
import com.orbisai.voice.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }
data class Message(val id:Long,val agentId:String,val text:String,val user:Boolean)

private val Navy=Color(0xFF101827); private val Slate=Color(0xFF526176); private val Cloud=Color(0xFFF5F7FA); private val Cyan=Color(0xFF55D6D0); private val Violet=Color(0xFF8275FF)

class OrbisViewModel(app:Application):AndroidViewModel(app){
 private val engine:AiEngine=DemoLocalEngine(); private val voiceEngine=AndroidVoiceEngine(app); private var responseJob:Job?=null
 private val _agent=MutableStateFlow(Agents.manager);val agent=_agent.asStateFlow(); private val _messages=MutableStateFlow<List<Message>>(emptyList());val messages=_messages.asStateFlow()
 private val _voice=MutableStateFlow(VoiceState.IDLE);val voice=_voice.asStateFlow();private val _partial=MutableStateFlow("");val partial=_partial.asStateFlow();private val _voiceMode=MutableStateFlow(false);val voiceMode=_voiceMode.asStateFlow();private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
 init{viewModelScope.launch{voiceEngine.events.collect{e->when(e){VoiceEvent.ListeningStarted->_voice.value=VoiceState.LISTENING;VoiceEvent.ListeningStopped->if(_voice.value==VoiceState.LISTENING)_voice.value=VoiceState.IDLE;is VoiceEvent.PartialTranscript->_partial.value=e.text;is VoiceEvent.FinalTranscript->{_partial.value="";send(e.text,true)};is VoiceEvent.SpeechStarted->_voice.value=VoiceState.SPEAKING;VoiceEvent.SpeechFinished->if(_voiceMode.value)startListening()else _voice.value=VoiceState.IDLE;is VoiceEvent.Error->{_error.value=e.message;_voice.value=VoiceState.IDLE};VoiceEvent.ThinkingStarted->_voice.value=VoiceState.THINKING}}}}
 fun select(a:Agent){_agent.value=a};fun toggleVoice(){if(_voiceMode.value){_voiceMode.value=false;viewModelScope.launch{voiceEngine.cancel()}}else{_error.value=null;_voiceMode.value=true;startListening()}};fun startListening(){viewModelScope.launch{voiceEngine.startListening()}}
 fun send(raw:String,speak:Boolean=_voiceMode.value){val text=PersianVoiceProfile.normalize(raw);if(text.isBlank())return;val a=_agent.value;_messages.value+=Message(System.nanoTime(),a.id,text,true);responseJob?.cancel();responseJob=viewModelScope.launch{_voice.value=VoiceState.THINKING;val r=engine.generate(AiRequest(text,a,Mode.AUTO));_messages.value+=Message(System.nanoTime(),a.id,r.text,false);if(speak)voiceEngine.speak(r.text)else _voice.value=VoiceState.IDLE}}
 override fun onCleared(){responseJob?.cancel();voiceEngine.release();super.onCleared()}
}

@Composable fun OrbisApp(onRequestMicrophone:()->Unit={},vm:OrbisViewModel=viewModel()){
 val agent by vm.agent.collectAsState();val messages by vm.messages.collectAsState();val state by vm.voice.collectAsState();val partial by vm.partial.collectAsState();val active by vm.voiceMode.collectAsState();val error by vm.error.collectAsState();val context=androidx.compose.ui.platform.LocalContext.current;var input by remember{mutableStateOf("")}
 val transition=rememberInfiniteTransition(label="orb");val pulse by transition.animateFloat(1f,1.10f,infiniteRepeatable(tween(1100,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="pulse")
 fun voice(){if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)vm.toggleVoice()else onRequestMicrophone()}
 MaterialTheme(colorScheme=lightColorScheme(primary=Navy,background=Cloud,surface=Color.White)){Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.White,Cloud,Color(0xFFE9EDF4))))){OfficeScene();Column(Modifier.fillMaxSize().padding(18.dp)){TopBar(active);Spacer(Modifier.height(14.dp));AgentRail(agent,vm);Spacer(Modifier.height(12.dp));VoiceHero(state,active,partial,pulse,::voice);if(error!=null)Text(error!!,color=Color(0xFFB42318),style=MaterialTheme.typography.labelSmall,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center);Spacer(Modifier.height(6.dp));Chat(messages,Modifier.weight(1f));Composer(input,{input=it},{vm.send(input);input=""},::voice,active)}}}
}

@Composable private fun OfficeScene(){Canvas(Modifier.fillMaxSize().alpha(.72f)){drawRect(Brush.verticalGradient(listOf(Color(0xFFFFFFFF),Color(0xFFE6EAF0))));val y=size.height*.68f;drawRect(Color(0x120F172A),Offset(0f,y),androidx.compose.ui.geometry.Size(size.width,size.height-y));for(i in 0..4){val x=size.width*(i/4f);drawLine(Color(0x160F172A),Offset(x,y),Offset(x-20f,y+size.height*.32f),3f)};drawCircle(Color(0x0E8275FF),size.minDimension*.34f,Offset(size.width*.86f,size.height*.17f))}}
@Composable private fun TopBar(active:Boolean){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(48.dp).background(Color.White,CircleShape),contentAlignment=Alignment.Center){OrbMark(Modifier.size(34.dp))};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("ORBIS",fontWeight=FontWeight.Black,color=Navy,style=MaterialTheme.typography.titleLarge);Text("AI COMMAND OFFICE  •  شخصی و هوشمند",style=MaterialTheme.typography.labelSmall,color=Slate)};Surface(shape=RoundedCornerShape(50),color=if(active)Color(0xFFE3FAF7)else Color.White,shadowElevation=2.dp){Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).background(if(active)Cyan else Color(0xFF98A2B3),CircleShape));Spacer(Modifier.width(7.dp));Text(if(active)"در حال گفتگو" else "آماده",fontWeight=FontWeight.Bold,color=Navy,style=MaterialTheme.typography.labelSmall)}}}}
@Composable private fun AgentRail(selected:Agent,vm:OrbisViewModel){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(9.dp)){Agents.all.forEach{a->val on=a.id==selected.id;Surface(Modifier.weight(1f).clickable{vm.select(a)},shape=RoundedCornerShape(20.dp),color=if(on)Navy.copy(alpha=.97f)else Color.White,shadowElevation=if(on)7.dp else 2.dp){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(38.dp).background(if(on)Color(0xFF27334A)else Cloud,CircleShape),contentAlignment=Alignment.Center){Text(a.emoji)};Spacer(Modifier.width(7.dp));Column{Text(a.name,fontWeight=FontWeight.Bold,color=if(on)Color.White else Navy);Text(a.role,style=MaterialTheme.typography.labelSmall,color=if(on)Color(0xFFCBD5E1)else Slate,maxLines=1)}}}}}}
@Composable private fun VoiceHero(state:VoiceState,active:Boolean,partial:String,pulse:Float,onVoice:()->Unit){Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.height(185.dp).fillMaxWidth(),contentAlignment=Alignment.Center){if(active){Box(Modifier.size(180.dp).scale(if(state==VoiceState.LISTENING)pulse else 1f).background(Color(0x148275FF),CircleShape));Box(Modifier.size(152.dp).background(Color(0x1055D6D0),CircleShape))};Box(Modifier.size(124.dp).background(Brush.radialGradient(listOf(Color.White,Color(0xFFE7EBF2),Color(0xFFB4BECC))),CircleShape).clickable{onVoice()},contentAlignment=Alignment.Center){OrbMark(Modifier.size(70.dp))}};Text(when(state){VoiceState.IDLE->"آماده‌ام برایت کار کنم";VoiceState.LISTENING->"گوش می‌دهم…";VoiceState.THINKING->"دارم فکر می‌کنم…";VoiceState.SPEAKING->"دارم با تو صحبت می‌کنم…"},style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=Navy);if(partial.isNotBlank())Text(partial,modifier=Modifier.padding(top=5.dp).fillMaxWidth(),textAlign=TextAlign.Center,color=Slate,maxLines=2);Text(if(active)"مکالمهٔ پیوستهٔ فارسی  •  قطع و ادامهٔ طبیعی" else "برای Voice Mode روی Orb ضربه بزن",style=MaterialTheme.typography.labelSmall,color=Slate)}}
@Composable private fun OrbMark(modifier:Modifier){Canvas(modifier){val c=Offset(size.width/2,size.height/2);drawCircle(Color.White,size.minDimension*.40f,c);drawCircle(Color(0xFF9AA6B6),size.minDimension*.40f,c,style=Stroke(size.minDimension*.065f));drawCircle(Color(0xFF344054),size.minDimension*.10f,c);drawCircle(Color(0xFF8275FF),size.minDimension*.045f,c)}}
@Composable private fun Chat(messages:List<Message>,modifier:Modifier){LazyColumn(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=6.dp)){items(messages,key={it.id}){m->Surface(shape=RoundedCornerShape(18.dp),color=if(m.user)Color(0xFFE8EDF4)else Color.White,shadowElevation=2.dp,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(if(m.user)"شما" else Agents.all.firstOrNull{it.id==m.agentId}?.name?:"Orbis",fontWeight=FontWeight.Bold,color=if(m.user)Slate else Violet,style=MaterialTheme.typography.labelSmall);Text(m.text,color=Navy,modifier=Modifier.padding(top=4.dp))}}}}}
@Composable private fun Composer(value:String,onValue:(String)->Unit,onSend:()->Unit,onVoice:()->Unit,active:Boolean){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(value,onValue,Modifier.weight(1f),placeholder={Text("پیام یا دستور خود را بنویس…")},singleLine=true,shape=RoundedCornerShape(20.dp));Spacer(Modifier.width(7.dp));FilledIconButton(onClick=onVoice,modifier=Modifier.size(52.dp),shape=CircleShape){Icon(if(active)Icons.Default.Stop else Icons.Default.Mic,contentDescription="Voice")};Spacer(Modifier.width(6.dp));FilledIconButton(onClick=onSend,enabled=value.isNotBlank(),modifier=Modifier.size(52.dp),shape=CircleShape){Icon(Icons.Default.ArrowUpward,contentDescription="Send")}}}
