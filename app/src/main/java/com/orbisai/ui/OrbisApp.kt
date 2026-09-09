package com.orbisai.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.animation.*
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val Navy=Color(0xFF101828)
private val Slate=Color(0xFF475467)
private val Cloud=Color(0xFFF4F6F8)
private val White=Color.White
private val Cyan=Color(0xFF24C7BD)
private val Violet=Color(0xFF7158FF)

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
    init { viewModelScope.launch { voiceEngine.events.collect { e ->
        when(e){
            VoiceEvent.ListeningStarted -> {_voice.value=VoiceState.LISTENING;_error.value=null}
            VoiceEvent.ListeningStopped -> {if(_voice.value==VoiceState.LISTENING)_voice.value=VoiceState.IDLE}
            is VoiceEvent.PartialTranscript -> _partial.value=e.text
            is VoiceEvent.FinalTranscript -> {_partial.value="";send(e.text,true)}
            VoiceEvent.ThinkingStarted -> _voice.value=VoiceState.THINKING
            is VoiceEvent.SpeechStarted -> _voice.value=VoiceState.SPEAKING
            VoiceEvent.SpeechFinished -> {if(_voiceMode.value)startListening() else _voice.value=VoiceState.IDLE}
            is VoiceEvent.Error -> {_error.value=e.message;_voice.value=VoiceState.IDLE}
        }
    } } }
    fun select(a:Agent){_agent.value=a}
    fun toggleVoice(){
        if(_voiceMode.value){_voiceMode.value=false;viewModelScope.launch{voiceEngine.cancel()};_voice.value=VoiceState.IDLE}
        else {_error.value=null;_voiceMode.value=true;startListening()}
    }
    fun startListening(){if(!_voiceMode.value)return;viewModelScope.launch{voiceEngine.startListening()}}
    fun send(raw:String,speak:Boolean=_voiceMode.value){
        val text=PersianVoiceProfile.normalize(raw);if(text.isBlank())return
        val a=_agent.value
        _messages.value=_messages.value+Message(System.nanoTime(),"user",text,true)
        responseJob?.cancel()
        responseJob=viewModelScope.launch{
            _voice.value=VoiceState.THINKING
            val r=engine.generate(AiRequest(text,a,Mode.AUTO))
            _messages.value=_messages.value+Message(System.nanoTime(),a.id,r.text,false)
            if(speak)voiceEngine.speak(r.text) else _voice.value=VoiceState.IDLE
        }
    }
    override fun onCleared(){responseJob?.cancel();voiceEngine.release();super.onCleared()}
}

@Composable
fun OrbisApp(onRequestMicrophone:()->Unit={},vm:OrbisViewModel=viewModel()){
    val agent by vm.agent.collectAsState(); val messages by vm.messages.collectAsState(); val state by vm.voice.collectAsState()
    val partial by vm.partial.collectAsState(); val active by vm.voiceMode.collectAsState(); val error by vm.error.collectAsState()
    val context=LocalContext.current; var input by remember{mutableStateOf("")}; var showAgents by remember{mutableStateOf(false)}
    val pulse=rememberInfiniteTransition(label="orb").animateFloat(1f,1.055f,infiniteRepeatable(tween(950,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="pulse")
    fun voice(){if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)vm.toggleVoice()else onRequestMicrophone()}
    MaterialTheme(colorScheme=lightColorScheme(primary=Navy,background=Cloud,surface=White)){
        Box(Modifier.fillMaxSize().background(Cloud)){
            OfficeScene()
            Column(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=10.dp)){
                TopBar(active)
                Spacer(Modifier.height(10.dp))
                ExecutiveBar(agent,showAgents,{showAgents=!showAgents})
                AnimatedVisibility(showAgents,enter=fadeIn()+expandVertically(),exit=fadeOut()+shrinkVertically()){ AgentSelector(agent,vm) }
                Spacer(Modifier.height(6.dp))
                VoiceConsole(state,active,partial,pulse.value,::voice)
                if(error!=null) ErrorPill(error.orEmpty())
                Spacer(Modifier.height(7.dp))
                if(messages.isEmpty()) Welcome(agent,Modifier.weight(1f)) else Chat(messages,Modifier.weight(1f))
                Spacer(Modifier.height(8.dp))
                Composer(input,{input=it},{vm.send(input);input=""},::voice,active)
                Spacer(Modifier.height(4.dp))
                Text("ORBiS  •  دستیار شخصی فارسی  •  محلی‌محور و حریم‌خصوصی‌دوست",modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center,color=Color(0xFF98A2B3),fontSize=9.sp)
            }
        }
    }
}

@Composable private fun TopBar(active:Boolean){
    Row(Modifier.fillMaxWidth().padding(top=2.dp),verticalAlignment=Alignment.CenterVertically){
        Surface(Modifier.size(48.dp),shape=CircleShape,color=White,shadowElevation=5.dp){Box(contentAlignment=Alignment.Center){OrbLogo(Modifier.size(38.dp))}}
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)){Text("ORBiS",fontSize=24.sp,fontWeight=FontWeight.Black,color=Navy);Text("PRIVATE AI COMMAND OFFICE",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Slate)}
        Surface(shape=RoundedCornerShape(30.dp),color=White,shadowElevation=2.dp){Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).background(if(active)Cyan else Color(0xFF98A2B3),CircleShape));Spacer(Modifier.width(6.dp));Text(if(active)"VOICE LIVE" else "READY",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Navy)}}
    }
}

@Composable private fun ExecutiveBar(agent:Agent,expanded:Boolean,onExpand:()->Unit){
    Surface(Modifier.fillMaxWidth().clickable{onExpand()},shape=RoundedCornerShape(22.dp),color=White.copy(alpha=.96f),shadowElevation=3.dp){
        Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.size(40.dp).background(Navy,CircleShape),contentAlignment=Alignment.Center){Text(agent.emoji,fontSize=19.sp)}
            Spacer(Modifier.width(9.dp));Column(Modifier.weight(1f)){Text(agent.name,fontWeight=FontWeight.ExtraBold,color=Navy);Text(agent.role,color=Slate,fontSize=10.sp)}
            Text(if(expanded)"بستن" else "تعویض ایجنت",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Violet);Icon(if(expanded)Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,null,tint=Violet)
        }
    }
}

@Composable private fun AgentSelector(selected:Agent,vm:OrbisViewModel){
    Row(Modifier.fillMaxWidth().padding(top=7.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)){
        Agents.all.forEach{a->val on=a.id==selected.id;Surface(Modifier.weight(1f).clickable{vm.select(a)},shape=RoundedCornerShape(17.dp),color=if(on)Navy else White,shadowElevation=2.dp){Column(Modifier.padding(9.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(a.emoji,fontSize=18.sp);Text(a.name,fontWeight=FontWeight.Bold,fontSize=11.sp,color=if(on)White else Navy);Text(a.role,fontSize=8.sp,maxLines=1,color=if(on)Color(0xFFD0D5DD)else Slate)}}}
    }
}

@Composable private fun VoiceConsole(state:VoiceState,active:Boolean,partial:String,pulse:Float,onVoice:()->Unit){
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){
        Box(Modifier.fillMaxWidth().height(185.dp),contentAlignment=Alignment.Center){
            if(active){
                VoiceRings(state)
                Box(Modifier.size(144.dp).scale(if(state==VoiceState.LISTENING)pulse else 1f).background(Color.White.copy(alpha=.98f),CircleShape).clickable{onVoice()},contentAlignment=Alignment.Center){OrbLogo(Modifier.size(92.dp))}
            } else Box(Modifier.size(120.dp).background(White,CircleShape).clickable{onVoice()},contentAlignment=Alignment.Center){OrbLogo(Modifier.size(78.dp))}
            if(active&&state==VoiceState.LISTENING) AudioBars(Modifier.align(Alignment.BottomCenter).padding(bottom=5.dp))
        }
        Text(when(state){VoiceState.IDLE->"آماده‌ام";VoiceState.LISTENING->"دارم می‌شنوم…";VoiceState.THINKING->"دارم فکر می‌کنم…";VoiceState.SPEAKING->"دارم صحبت می‌کنم…"},fontSize=18.sp,fontWeight=FontWeight.ExtraBold,color=Navy)
        Text(when{!active->"برای شروع گفت‌وگوی صوتی، روی Orb بزن";state==VoiceState.LISTENING->"فارسی را طبیعی صحبت کن • برای قطع صدا، شروع به صحبت کن";state==VoiceState.SPEAKING->"می‌توانی وسط پاسخ صحبت کنی";else->"Voice Mode فارسی • مکالمهٔ پیوسته"},fontSize=10.sp,color=Slate,modifier=Modifier.padding(top=2.dp))
        if(partial.isNotBlank())Surface(Modifier.fillMaxWidth().padding(top=5.dp),shape=RoundedCornerShape(14.dp),color=White.copy(alpha=.9f)){Text(partial,Modifier.padding(9.dp),fontSize=11.sp,color=Slate,maxLines=2,textAlign=TextAlign.Center)}
    }
}

@Composable private fun VoiceRings(state:VoiceState){
    Canvas(Modifier.size(210.dp).alpha(if(state==VoiceState.THINKING).55f else .9f)){val c=Offset(size.width/2,size.height/2);val base=if(state==VoiceState.SPEAKING)Violet else Cyan;for(i in 0..3){drawCircle(base.copy(alpha=.10f+(3-i)*.025f),size.minDimension*(.28f+i*.08f),c,style=Stroke(size.minDimension*.012f))}}
}

@Composable private fun AudioBars(modifier:Modifier){
    val t=rememberInfiniteTransition(label="bars");val a=t.animateFloat(0.25f,1f,infiniteRepeatable(tween(500),RepeatMode.Reverse),label="a").value
    Row(modifier.height(20.dp),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalAlignment=Alignment.CenterVertically){repeat(9){i->Box(Modifier.width(3.dp).height((6f+18f*((i%4+1)/4f)*a).dp).background(if(i%2==0)Cyan else Violet,RoundedCornerShape(3.dp)))}}
}

@Composable private fun Welcome(agent:Agent,modifier:Modifier){
    Column(modifier.fillMaxWidth().padding(top=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
        Text("دفتر کار هوشمند تو",fontSize=27.sp,fontWeight=FontWeight.Black,color=Navy)
        Text("من و سه همکارم آماده‌ایم کار را جلو ببریم.",fontSize=12.sp,color=Slate,modifier=Modifier.padding(top=5.dp))
        Row(Modifier.padding(top=18.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)){listOf("تحقیق و تحلیل","برنامه‌ریزی","ایده و طراحی").forEach{label->Surface(shape=RoundedCornerShape(14.dp),color=White,shadowElevation=1.dp){Text(label,Modifier.padding(horizontal=11.dp,vertical=9.dp),fontSize=10.sp,fontWeight=FontWeight.Bold,color=Navy)}}}
        Text("ایجنت فعال: ${agent.name}",fontSize=10.sp,color=Violet,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=15.dp))
    }
}

@Composable private fun Chat(messages:List<Message>,modifier:Modifier){
    LazyColumn(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(7.dp),contentPadding=PaddingValues(vertical=6.dp)){items(messages,key={it.id}){m->Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.user)Arrangement.End else Arrangement.Start){Surface(shape=RoundedCornerShape(18.dp),color=if(m.user)Navy else White,shadowElevation=2.dp){Column(Modifier.widthIn(max=335.dp).padding(11.dp)){Text(if(m.user)"شما" else Agents.all.firstOrNull{it.id==m.agentId}?.name?:"ORBiS",fontSize=9.sp,fontWeight=FontWeight.Bold,color=if(m.user)Color(0xFFD0D5DD)else Violet);Text(m.text,color=if(m.user)White else Navy,fontSize=12.sp,modifier=Modifier.padding(top=3.dp))}}}}}
}

@Composable private fun Composer(value:String,onValue:(String)->Unit,onSend:()->Unit,onVoice:()->Unit,active:Boolean){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(value,onValue,Modifier.weight(1f),placeholder={Text("دستور، سؤال یا ایده…",fontSize=12.sp)},singleLine=true,shape=RoundedCornerShape(19.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=White,focusedContainerColor=White));Spacer(Modifier.width(6.dp));FilledIconButton(onClick=onVoice,modifier=Modifier.size(50.dp),shape=CircleShape,colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(active)Violet else Navy)){Icon(if(active)Icons.Default.Stop else Icons.Default.Mic,contentDescription="Voice",tint=White)};Spacer(Modifier.width(5.dp));FilledIconButton(onClick=onSend,enabled=value.isNotBlank(),modifier=Modifier.size(50.dp),shape=CircleShape){Icon(Icons.Default.ArrowUpward,contentDescription="Send")}}
}

@Composable private fun ErrorPill(text:String){Surface(Modifier.fillMaxWidth().padding(top=5.dp),shape=RoundedCornerShape(12.dp),color=Color(0xFFFFEAEA)){Text(text,Modifier.padding(7.dp),color=Color(0xFFB42318),fontSize=9.sp,textAlign=TextAlign.Center)}}

@Composable private fun OfficeScene(){
    Canvas(Modifier.fillMaxSize()){
        drawRect(Brush.verticalGradient(listOf(Color(0xFFFDFEFE),Color(0xFFE7EBF0))))
        val w=size.width;val h=size.height;val floor=h*.72f
        drawRect(Color(0xFFE1E5EA),Offset(0f,floor),Size(w,h-floor));drawLine(Color(0xFFCDD3DB),Offset(0f,floor),Offset(w,floor),3f)
        for(i in 1..5){val x=w*i/6f;drawLine(Color(0x12FFFFFF),Offset(x,floor),Offset(w/2+(x-w/2)*1.22f,h),3f)}
        drawRoundRect(Color(0x18FFFFFF),Offset(w*.08f,h*.12f),Size(w*.26f,h*.24f),CornerRadius(18f,18f))
        drawRoundRect(Color(0x16FFFFFF),Offset(w*.67f,h*.12f),Size(w*.24f,h*.24f),CornerRadius(18f,18f))
        drawRect(Color(0x0E7158FF),Offset(w*.34f,h*.11f),Size(w*.30f,h*.26f))
        drawCircle(Color(0x0C24C7BD),w*.22f,Offset(w*.84f,h*.78f));drawCircle(Color(0x087158FF),w*.16f,Offset(w*.10f,h*.76f))
    }
}

@Composable private fun OrbLogo(modifier:Modifier){Canvas(modifier){val c=Offset(size.width/2,size.height/2);drawCircle(Color.White,size.minDimension*.41f,c);drawCircle(Color(0xFF8A96A8),size.minDimension*.41f,c,style=Stroke(size.minDimension*.035f));drawCircle(Color(0xFF172033),size.minDimension*.115f,c);drawCircle(Violet,size.minDimension*.048f,c);drawCircle(Cyan,size.minDimension*.022f,c+Offset(size.width*.115f,-size.height*.115f))}}
