package com.orbisai.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
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

private val Ink=Color(0xFF101828)
private val Muted=Color(0xFF667085)
private val Paper=Color(0xFFF6F8FB)
private val Aqua=Color(0xFF27C7BD)
private val Purple=Color(0xFF7357FF)

class OrbisViewModel(app:Application):AndroidViewModel(app){
    private val engine:AiEngine=DemoLocalEngine()
    private val voiceEngine=AndroidVoiceEngine(app)
    private var responseJob:Job?=null
    private val _agent=MutableStateFlow(Agents.manager); val agent=_agent.asStateFlow()
    private val _messages=MutableStateFlow<List<Message>>(emptyList()); val messages=_messages.asStateFlow()
    private val _voice=MutableStateFlow(VoiceState.IDLE); val voice=_voice.asStateFlow()
    private val _partial=MutableStateFlow(""); val partial=_partial.asStateFlow()
    private val _voiceMode=MutableStateFlow(false); val voiceMode=_voiceMode.asStateFlow()
    private val _error=MutableStateFlow<String?>(null); val error=_error.asStateFlow()
    init{viewModelScope.launch{voiceEngine.events.collect{e->when(e){
        VoiceEvent.ListeningStarted->{_voice.value=VoiceState.LISTENING;_error.value=null}
        VoiceEvent.ListeningStopped->{if(_voice.value==VoiceState.LISTENING)_voice.value=VoiceState.IDLE}
        is VoiceEvent.PartialTranscript->{_partial.value=e.text}
        is VoiceEvent.FinalTranscript->{_partial.value="";send(e.text,true)}
        VoiceEvent.ThinkingStarted->{_voice.value=VoiceState.THINKING}
        is VoiceEvent.SpeechStarted->{_voice.value=VoiceState.SPEAKING}
        VoiceEvent.SpeechFinished->{if(_voiceMode.value) startListening() else _voice.value=VoiceState.IDLE}
        is VoiceEvent.Error->{_error.value=e.message;_voice.value=VoiceState.IDLE}
    }}}}
    fun select(a:Agent){_agent.value=a}
    fun toggleVoice(){if(_voiceMode.value){_voiceMode.value=false;viewModelScope.launch{voiceEngine.cancel()};_voice.value=VoiceState.IDLE}else{_error.value=null;_voiceMode.value=true;startListening()}}
    fun startListening(){if(!_voiceMode.value)return;viewModelScope.launch{voiceEngine.startListening()}}
    fun send(raw:String,speak:Boolean=_voiceMode.value){
        val text=PersianVoiceProfile.normalize(raw); if(text.isBlank())return
        val a=_agent.value; _messages.value=_messages.value+Message(System.nanoTime(),"user",text,true)
        responseJob?.cancel(); responseJob=viewModelScope.launch{
            _voice.value=VoiceState.THINKING
            val r=engine.generate(AiRequest(text,a,Mode.AUTO))
            _messages.value=_messages.value+Message(System.nanoTime(),a.id,r.text,false)
            if(speak) voiceEngine.speak(r.text) else _voice.value=VoiceState.IDLE
        }
    }
    override fun onCleared(){responseJob?.cancel();voiceEngine.release();super.onCleared()}
}

@Composable
fun OrbisApp(onRequestMicrophone:()->Unit={},vm:OrbisViewModel=viewModel()){
    val agent by vm.agent.collectAsState(); val messages by vm.messages.collectAsState(); val state by vm.voice.collectAsState()
    val partial by vm.partial.collectAsState(); val active by vm.voiceMode.collectAsState(); val error by vm.error.collectAsState()
    val context=androidx.compose.ui.platform.LocalContext.current; var input by remember{mutableStateOf("")}
    val pulse=rememberInfiniteTransition(label="pulse").animateFloat(1f,1.08f,infiniteRepeatable(tween(900,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="orbPulse")
    fun voice(){if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)vm.toggleVoice()else onRequestMicrophone()}
    MaterialTheme(colorScheme=lightColorScheme(primary=Ink,background=Paper,surface=Color.White)){
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.White,Paper,Color(0xFFECEFF5))))){
            OfficeBackdrop()
            Column(Modifier.fillMaxSize().padding(horizontal=18.dp,vertical=14.dp)){
                Header(active,agent)
                Spacer(Modifier.height(12.dp))
                AgentDock(agent,vm)
                Spacer(Modifier.height(10.dp))
                VoiceStage(state,active,partial,pulse.value,::voice)
                AnimatedVisibility(error!=null){Text(error.orEmpty(),color=Color(0xFFB42318),style=MaterialTheme.typography.labelSmall,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)}
                Spacer(Modifier.height(6.dp))
                Chat(messages,Modifier.weight(1f))
                Spacer(Modifier.height(8.dp))
                Composer(input,{input=it},{vm.send(input);input=""},::voice,active)
            }
        }
    }
}

@Composable private fun OfficeBackdrop(){Canvas(Modifier.fillMaxSize().alpha(.72f)){drawRect(Brush.verticalGradient(listOf(Color.White,Color(0xFFE9EDF3))));val floor=size.height*.73f;drawRect(Color(0x130F172A),Offset(0f,floor),androidx.compose.ui.geometry.Size(size.width,size.height-floor));for(i in 0..5){val x=size.width*(i/5f);drawLine(Color(0x140F172A),Offset(x,floor),Offset(x-18f,floor+size.height*.27f),2f)};drawCircle(Color(0x127357FF),size.minDimension*.28f,Offset(size.width*.86f,size.height*.16f));drawCircle(Color(0x1027C7BD),size.minDimension*.18f,Offset(size.width*.12f,size.height*.20f))}}

@Composable private fun Header(active:Boolean,agent:Agent){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(50.dp).background(Color.White,CircleShape),contentAlignment=Alignment.Center){OrbLogo(Modifier.size(36.dp))};Spacer(Modifier.width(11.dp));Column(Modifier.weight(1f)){Text("ORBis",fontWeight=FontWeight.Black,fontSize=24.sp,color=Ink);Text("AI COMMAND OFFICE  ·  دفتر هوشمند شخصی",style=MaterialTheme.typography.labelSmall,color=Muted)};Surface(shape=RoundedCornerShape(50),color=if(active)Color(0xFFE4FAF7) else Color.White,shadowElevation=2.dp){Row(Modifier.padding(horizontal=11.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).background(if(active)Aqua else Color(0xFF98A2B3),CircleShape));Spacer(Modifier.width(6.dp));Text(if(active)"گفتگوی فعال" else "آماده",fontWeight=FontWeight.Bold,color=Ink,style=MaterialTheme.typography.labelSmall)}}}}

@Composable private fun AgentDock(selected:Agent,vm:OrbisViewModel){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Agents.all.forEach{a->val on=a.id==selected.id;Surface(Modifier.weight(1f).clickable{vm.select(a)},shape=RoundedCornerShape(18.dp),color=if(on)Ink else Color.White,shadowElevation=if(on)6.dp else 2.dp){Column(Modifier.padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(34.dp).background(if(on)Color(0xFF263249)else Color(0xFFF1F3F7),CircleShape),contentAlignment=Alignment.Center){Text(a.emoji)};Text(a.name,fontWeight=FontWeight.Bold,color=if(on)Color.White else Ink);Text(a.role,style=MaterialTheme.typography.labelSmall,color=if(on)Color(0xFFD0D5DD)else Muted,maxLines=1)}}}}}

@Composable private fun VoiceStage(state:VoiceState,active:Boolean,partial:String,pulse:Float,onVoice:()->Unit){Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.height(170.dp).fillMaxWidth(),contentAlignment=Alignment.Center){if(active){Box(Modifier.size(172.dp).scale(if(state==VoiceState.LISTENING)pulse else 1f).background(Color(0x147357FF),CircleShape));Box(Modifier.size(145.dp).background(Color(0x1027C7BD),CircleShape))};Box(Modifier.size(118.dp).background(Brush.radialGradient(listOf(Color.White,Color(0xFFE5EAF1),Color(0xFFB2BDCC))),CircleShape).clickable{onVoice()},contentAlignment=Alignment.Center){OrbLogo(Modifier.size(72.dp))}};Text(when(state){VoiceState.IDLE->"آماده‌ام";VoiceState.LISTENING->"می‌شنوم…";VoiceState.THINKING->"در حال پردازش…";VoiceState.SPEAKING->"در حال صحبت…"},style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=Ink);if(partial.isNotBlank())Text(partial,modifier=Modifier.padding(top=4.dp).fillMaxWidth(),textAlign=TextAlign.Center,color=Muted,maxLines=2);Text(if(active)"Voice Mode فارسی  ·  مکالمهٔ پیوسته · قطع با شروع صحبت شما" else "برای شروع مکالمه روی Orb بزن",style=MaterialTheme.typography.labelSmall,color=Muted)}}

@Composable private fun OrbLogo(modifier:Modifier){Canvas(modifier){val c=Offset(size.width/2,size.height/2);drawCircle(Color.White,size.minDimension*.40f,c);drawCircle(Color(0xFF7C8798),size.minDimension*.40f,c,style=Stroke(size.minDimension*.055f));drawCircle(Color(0xFF1D2939),size.minDimension*.105f,c);drawCircle(Color(0xFF7357FF),size.minDimension*.045f,c);drawCircle(Color(0xFF27C7BD),size.minDimension*.020f,c+Offset(size.width*.11f,-size.height*.11f))}}

@Composable private fun Chat(messages:List<Message>,modifier:Modifier){LazyColumn(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(7.dp),contentPadding=PaddingValues(vertical=5.dp)){items(messages,key={it.id}){m->Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.user)Arrangement.End else Arrangement.Start){Surface(shape=RoundedCornerShape(18.dp),color=if(m.user)Color(0xFFE8EDF4)else Color.White,shadowElevation=1.dp){Column(Modifier.widthIn(max=330.dp).padding(11.dp)){Text(if(m.user)"شما" else Agents.all.firstOrNull{it.id==m.agentId}?.name?:"Orbis",fontWeight=FontWeight.Bold,color=if(m.user)Muted else Purple,style=MaterialTheme.typography.labelSmall);Text(m.text,color=Ink,modifier=Modifier.padding(top=3.dp))}}}}}}

@Composable private fun Composer(value:String,onValue:(String)->Unit,onSend:()->Unit,onVoice:()->Unit,active:Boolean){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(value,onValue,Modifier.weight(1f),placeholder={Text("پیام یا دستور…")},singleLine=true,shape=RoundedCornerShape(19.dp));Spacer(Modifier.width(7.dp));FilledIconButton(onClick=onVoice,modifier=Modifier.size(52.dp),shape=CircleShape){Icon(if(active)Icons.Default.Stop else Icons.Default.Mic,contentDescription="Voice")};Spacer(Modifier.width(6.dp));FilledIconButton(onClick=onSend,enabled=value.isNotBlank(),modifier=Modifier.size(52.dp),shape=CircleShape){Icon(Icons.Default.ArrowUpward,contentDescription="Send")}}}
