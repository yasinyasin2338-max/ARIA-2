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

private val Wall=Color(0xFFE7E1D8)
private val Floor=Color(0xFFB99B7A)
private val Wood=Color(0xFF6E4730)
private val WoodLight=Color(0xFF9A6B48)
private val Navy=Color(0xFF17212B)
private val Slate=Color(0xFF5D6870)
private val White=Color.White
private val Cyan=Color(0xFF19B7AE)
private val Violet=Color(0xFF7257E8)

class OrbisViewModel(app:Application):AndroidViewModel(app){
    private val engine:AiEngine=DemoLocalEngine()
    private val voiceEngine=AndroidVoiceEngine(app)
    private var responseJob:Job?=null
    private val _agent=MutableStateFlow(Agents.manager);val agent=_agent.asStateFlow()
    private val _messages=MutableStateFlow<List<Message>>(emptyList());val messages=_messages.asStateFlow()
    private val _voice=MutableStateFlow(VoiceState.IDLE);val voice=_voice.asStateFlow()
    private val _partial=MutableStateFlow("");val partial=_partial.asStateFlow()
    private val _voiceMode=MutableStateFlow(false);val voiceMode=_voiceMode.asStateFlow()
    private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
    init{viewModelScope.launch{voiceEngine.events.collect{e->when(e){
        VoiceEvent.ListeningStarted->{_voice.value=VoiceState.LISTENING;_error.value=null}
        VoiceEvent.ListeningStopped->{if(_voice.value==VoiceState.LISTENING)_voice.value=VoiceState.IDLE}
        is VoiceEvent.PartialTranscript->_partial.value=e.text
        is VoiceEvent.FinalTranscript->{_partial.value="";send(e.text,true)}
        VoiceEvent.ThinkingStarted->_voice.value=VoiceState.THINKING
        is VoiceEvent.SpeechStarted->_voice.value=VoiceState.SPEAKING
        VoiceEvent.SpeechFinished->{if(_voiceMode.value)startListening()else _voice.value=VoiceState.IDLE}
        is VoiceEvent.Error->{_error.value=e.message;_voice.value=VoiceState.IDLE}
    }}}}
    fun select(a:Agent){_agent.value=a}
    fun activateAgent(a:Agent){_agent.value=a;if(!_voiceMode.value)toggleVoice()}
    fun toggleVoice(){if(_voiceMode.value){_voiceMode.value=false;viewModelScope.launch{voiceEngine.cancel()};_voice.value=VoiceState.IDLE}else{_error.value=null;_voiceMode.value=true;startListening()}}
    fun startListening(){if(!_voiceMode.value)return;viewModelScope.launch{voiceEngine.startListening()}}
    fun send(raw:String,speak:Boolean=_voiceMode.value){val text=PersianVoiceProfile.normalize(raw);if(text.isBlank())return;val a=_agent.value
        _messages.value=_messages.value+Message(System.nanoTime(),"user",text,true);responseJob?.cancel();responseJob=viewModelScope.launch{_voice.value=VoiceState.THINKING
            val r=engine.generate(AiRequest(text,a,Mode.AUTO));_messages.value=_messages.value+Message(System.nanoTime(),a.id,r.text,false);if(speak)voiceEngine.speak(r.text)else _voice.value=VoiceState.IDLE}}
    override fun onCleared(){responseJob?.cancel();voiceEngine.release();super.onCleared()}
}

@Composable fun OrbisApp(onRequestMicrophone:()->Unit={},vm:OrbisViewModel=viewModel()){
    val agent by vm.agent.collectAsState();val messages by vm.messages.collectAsState();val state by vm.voice.collectAsState();val partial by vm.partial.collectAsState();val active by vm.voiceMode.collectAsState();val error by vm.error.collectAsState()
    val context=LocalContext.current;var input by remember{mutableStateOf("")};var showAgents by remember{mutableStateOf(false)}
    val pulse=rememberInfiniteTransition(label="pulse").animateFloat(1f,1.06f,infiniteRepeatable(tween(800,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="p").value
    fun voice(){if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)vm.toggleVoice()else onRequestMicrophone()}
    MaterialTheme(colorScheme=lightColorScheme(primary=Navy,background=Color(0xFFF1EEE9),surface=White)){
        Box(Modifier.fillMaxSize().background(Color(0xFFF1EEE9))){
            OfficeRoomScene(agent,state,active,pulse,vm)
            Column(Modifier.fillMaxSize().padding(horizontal=14.dp,vertical=8.dp)){
                Header(active);Spacer(Modifier.height(7.dp));AgentStrip(agent,{showAgents=!showAgents})
                AnimatedVisibility(showAgents,enter=fadeIn()+expandVertically(),exit=fadeOut()+shrinkVertically()){AgentPicker(agent,vm)}
                Spacer(Modifier.height(6.dp));VoiceMiniPanel(state,active,partial,voice);if(error!=null)ErrorPill(error.orEmpty());Spacer(Modifier.height(5.dp))
                if(messages.isEmpty())RoomWelcome(agent,Modifier.weight(1f))else Chat(messages,Modifier.weight(1f))
                Spacer(Modifier.height(6.dp));Composer(input,{input=it},{vm.send(input);input=""},voice,active)
            }
        }
    }
}

@Composable private fun Header(active:Boolean){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=White.copy(.94f),shadowElevation=4.dp){Row(Modifier.padding(9.dp),verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(42.dp),CircleShape,color=Navy,shadowElevation=3.dp){Box(contentAlignment=Alignment.Center){OrbLogo(Modifier.size(35.dp))}};Spacer(Modifier.width(9.dp));Column(Modifier.weight(1f)){Text("ORBiS",fontSize=21.sp,fontWeight=FontWeight.Black,color=Navy);Text("AI OFFICE • اتاق فرمان هوشمند",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Slate)};Surface(shape=RoundedCornerShape(20.dp),color=if(active)Color(0xFFDFF8F4)else Color(0xFFF2F4F7)){Row(Modifier.padding(horizontal=9.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).background(if(active)Cyan else Color(0xFF98A2B3),CircleShape));Spacer(Modifier.width(5.dp));Text(if(active)"VOICE LIVE" else "آماده",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Navy)}}}}}

@Composable private fun AgentStrip(agent:Agent,onOpen:()->Unit){Surface(Modifier.fillMaxWidth().clickable{onOpen()},shape=RoundedCornerShape(17.dp),color=White.copy(.92f),shadowElevation=3.dp){Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(35.dp).background(Navy,CircleShape),contentAlignment=Alignment.Center){Text(agent.emoji,fontSize=17.sp)};Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f)){Text("ایجنت فعال: ${agent.name}",fontSize=12.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text(agent.role,fontSize=9.sp,color=Slate)};Text("۳ ایجنت",fontSize=9.sp,fontWeight=FontWeight.Bold,color=Violet);Icon(Icons.Default.KeyboardArrowDown,null,tint=Violet)}}}

@Composable private fun AgentPicker(selected:Agent,vm:OrbisViewModel){Row(Modifier.fillMaxWidth().padding(top=6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){Agents.all.forEach{a->val on=a.id==selected.id;Surface(Modifier.weight(1f).clickable{vm.select(a)},shape=RoundedCornerShape(14.dp),color=if(on)Navy else White,shadowElevation=2.dp){Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(a.emoji,fontSize=17.sp);Text(a.name,fontSize=10.sp,fontWeight=FontWeight.Bold,color=if(on)White else Navy);Text(a.role,fontSize=7.sp,maxLines=1,color=if(on)Color(0xFFD0D5DD)else Slate)}}}}}

@Composable private fun VoiceMiniPanel(state:VoiceState,active:Boolean,partial:String,onVoice:()->Unit){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=White.copy(.91f),shadowElevation=2.dp){Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(38.dp).clickable{onVoice()},contentAlignment=Alignment.Center){if(active)VoiceRings(state)else OrbLogo(Modifier.size(30.dp))};Spacer(Modifier.width(7.dp));Column(Modifier.weight(1f)){Text(when(state){VoiceState.IDLE->"Voice Mode فارسی";VoiceState.LISTENING->"در حال شنیدن…";VoiceState.THINKING->"در حال تحلیل…";VoiceState.SPEAKING->"در حال صحبت…"},fontWeight=FontWeight.ExtraBold,fontSize=11.sp,color=Navy);Text(if(partial.isBlank())"روی یکی از کارمندها یا Orb بزن" else partial,maxLines=1,fontSize=9.sp,color=Slate)};FilledIconButton(onClick=onVoice,modifier=Modifier.size(39.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(active)Violet else Navy)){Icon(if(active)Icons.Default.Stop else Icons.Default.Mic,null,tint=White)}}}}

@Composable private fun RoomWelcome(agent:Agent,modifier:Modifier){Column(modifier.fillMaxWidth().padding(top=4.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("دفتر کار ORBiS",fontSize=24.sp,fontWeight=FontWeight.Black,color=Navy);Text("سه میز • سه کارمند • سه مغز هوشمند",fontSize=11.sp,color=Slate,modifier=Modifier.padding(top=3.dp));Text("روی هر کارمند بزن تا ایجنت همان میز فعال شود و Voice Mode او شروع شود.",fontSize=10.sp,color=Violet,textAlign=TextAlign.Center,modifier=Modifier.padding(top=10.dp).widthIn(max=310.dp));Text("ایجنت فعلی: ${agent.name}",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Navy,modifier=Modifier.padding(top=10.dp))}}

@Composable private fun Chat(messages:List<Message>,modifier:Modifier){LazyColumn(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(vertical=4.dp)){items(messages,key={it.id}){m->Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.user)Arrangement.End else Arrangement.Start){Surface(shape=RoundedCornerShape(15.dp),color=if(m.user)Navy else White,shadowElevation=2.dp){Column(Modifier.widthIn(max=320.dp).padding(9.dp)){Text(if(m.user)"شما" else Agents.all.firstOrNull{it.id==m.agentId}?.name?:"ORBiS",fontSize=8.sp,fontWeight=FontWeight.Bold,color=if(m.user)Color(0xFFD0D5DD)else Violet);Text(m.text,color=if(m.user)White else Navy,fontSize=11.sp,modifier=Modifier.padding(top=2.dp))}}}}}}

@Composable private fun Composer(value:String,onValue:(String)->Unit,onSend:()->Unit,onVoice:()->Unit,active:Boolean){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(value,onValue,Modifier.weight(1f),placeholder={Text("دستور یا سؤال…",fontSize=11.sp)},singleLine=true,shape=RoundedCornerShape(17.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=White,focusedContainerColor=White));Spacer(Modifier.width(5.dp));FilledIconButton(onClick=onVoice,modifier=Modifier.size(47.dp),shape=CircleShape,colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(active)Violet else Navy)){Icon(if(active)Icons.Default.Stop else Icons.Default.Mic,null,tint=White)};Spacer(Modifier.width(4.dp));FilledIconButton(onClick=onSend,enabled=value.isNotBlank(),modifier=Modifier.size(47.dp),shape=CircleShape){Icon(Icons.Default.ArrowUpward,null)}}}

@Composable private fun ErrorPill(text:String){Surface(Modifier.fillMaxWidth().padding(top=4.dp),shape=RoundedCornerShape(11.dp),color=Color(0xFFFFEAEA)){Text(text,Modifier.padding(6.dp),color=Color(0xFFB42318),fontSize=8.sp,textAlign=TextAlign.Center)}}

@Composable private fun OfficeRoomScene(agent:Agent,state:VoiceState,active:Boolean,pulse:Float,vm:OrbisViewModel){Box(Modifier.fillMaxSize()){Canvas(Modifier.fillMaxSize()){drawOfficeRoom()};Row(Modifier.fillMaxWidth().padding(start=8.dp,end=8.dp,top=300.dp),horizontalArrangement=Arrangement.SpaceEvenly){Agents.all.forEach{a->EmployeeHotspot(a,a.id==agent.id,active&&a.id==agent.id,pulse){vm.activateAgent(a)}}}}}

@Composable private fun EmployeeHotspot(a:Agent,selected:Boolean,voice:Boolean,pulse:Float,onClick:()->Unit){Column(Modifier.width(104.dp).clickable{onClick()},horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(88.dp),contentAlignment=Alignment.Center){if(voice)Box(Modifier.size(84.dp).scale(pulse).background(Cyan.copy(.18f),CircleShape));EmployeeFigure(a,selected,Modifier.size(76.dp));if(voice)Surface(Modifier.align(Alignment.TopEnd),CircleShape,color=Violet,shadowElevation=3.dp){Icon(Icons.Default.Mic,null,Modifier.padding(6.dp).size(14.dp),tint=White)}};Text(a.name,fontSize=10.sp,fontWeight=FontWeight.Black,color=Navy);Text(if(voice)"VOICE LIVE" else a.role,fontSize=7.sp,color=if(selected)Violet else Slate,maxLines=1,textAlign=TextAlign.Center)}}

@Composable private fun EmployeeFigure(a:Agent,selected:Boolean,modifier:Modifier){Canvas(modifier){val w=size.width;val h=size.height;val skin=if(a.id=="manager")Color(0xFFB97858)else if(a.id=="researcher")Color(0xFFD79B78)else Color(0xFFE0A17F);val hair=if(a.id=="manager")Color(0xFF27221F)else if(a.id=="researcher")Color(0xFF3C2922)else Color(0xFF4A2B2C);val suit=if(a.id=="manager")Navy else if(a.id=="researcher")Color(0xFF506A72)else Color(0xFF6E547A);drawCircle(hair,w*.28f,Offset(w*.5f,h*.25f));drawCircle(skin,w*.20f,Offset(w*.5f,h*.28f));drawRoundRect(suit,Offset(w*.27f,h*.46f),Size(w*.46f,h*.42f),CornerRadius(w*.12f,w*.12f));drawCircle(White,w*.025f,Offset(w*.45f,h*.27f));drawCircle(White,w*.025f,Offset(w*.55f,h*.27f));drawCircle(Navy,w*.011f,Offset(w*.45f,h*.27f));drawCircle(Navy,w*.011f,Offset(w*.55f,h*.27f));drawLine(skin,Offset(w*.38f,h*.58f),Offset(w*.20f,h*.76f),w*.07f);drawLine(skin,Offset(w*.62f,h*.58f),Offset(w*.80f,h*.76f),w*.07f);if(selected)drawRoundRect(Cyan,Offset(w*.08f,h*.08f),Size(w*.84f,h*.82f),CornerRadius(w*.14f,w*.14f),style=Stroke(w*.025f))}}

private fun DrawScope.drawOfficeRoom(){val w=size.width;val h=size.height;drawRect(Wall,Offset.Zero,Size(w,h*.72f));drawRect(Floor,Offset(0f,h*.72f),Size(w,h*.28f));drawRect(Color(0xFFF5F1EA),Offset(0f,h*.10f),Size(w,h*.035f));drawRect(Color(0xFFD0C8BE),Offset(0f,h*.135f),Size(w,h*.008f));val wx=w*.31f;val wy=h*.16f;val ww=w*.38f;val wh=h*.31f;drawRoundRect(Color(0xFFB9D9E2),Offset(wx,wy),Size(ww,wh),CornerRadius(7f,7f));drawRect(Color(0xFF8DB6C1),Offset(wx+ww*.49f,wy),Size(ww*.015f,wh));drawRect(Color(0xFF8DB6C1),Offset(wx,wy+wh*.48f),Size(ww,wh*.015f));drawCircle(Color(0xFFF6D47A),w*.035f,Offset(wx+ww*.78f,wy+wh*.25f));drawRoundRect(Color(0xFFD9D0C5),Offset(wx-w*.075f,wy-w*.015f),Size(w*.105f,wh+w*.06f),CornerRadius(10f,10f));drawRoundRect(Color(0xFFD9D0C5),Offset(wx+ww-w*.03f,wy-w*.015f),Size(w*.105f,wh+w*.06f),CornerRadius(10f,10f));for(i in 0..5)drawLine(Color(0xFFC3B9AE),Offset(wx-w*.055f+i*w*.016f,wy),Offset(wx-w*.055f+i*w*.016f,wy+wh+w*.04f),w*.004f);drawRoundRect(Color(0xFFF4EFE8),Offset(w*.055f,h*.21f),Size(w*.14f,h*.16f),CornerRadius(5f,5f));drawCircle(Color(0xFF6E8E7B),w*.035f,Offset(w*.125f,h*.29f));drawLine(Color(0xFFB58B65),Offset(w*.08f,h*.34f),Offset(w*.18f,h*.24f),w*.018f);drawRect(Wood,Offset(w*.76f,h*.34f),Size(w*.17f,h*.018f));drawRect(Color(0xFF80624D),Offset(w*.78f,h*.25f),Size(w*.12f,h*.09f));drawCircle(Color(0xFF6F8E65),w*.022f,Offset(w*.84f,h*.24f));for(i in 0..7){val x=w*.12f+i*w*.11f;drawLine(Color(0x4A6B5545),Offset(w*.5f,h*.72f),Offset(x,h),w*.003f)};drawLine(Color(0x6A6B5545),Offset(0f,h*.83f),Offset(w,h*.83f),w*.004f);val px=w*.91f;val py=h*.69f;drawRoundRect(Color(0xFF8D6145),Offset(px-w*.035f,py),Size(w*.07f,h*.09f),CornerRadius(5f,5f));drawLine(Color(0xFF587B56),Offset(px,py),Offset(px-w*.025f,py-w*.10f),w*.012f);drawLine(Color(0xFF587B56),Offset(px,py),Offset(px+w*.03f,py-w*.09f),w*.012f);drawCircle(Color(0xFF6F9464),w*.035f,Offset(px-w*.025f,py-w*.105f));drawCircle(Color(0xFF789B6B),w*.032f,Offset(px+w*.035f,py-w*.09f));drawCircle(Color(0xFF648A5B),w*.027f,Offset(px,py-w*.12f));val centers=listOf(w*.18f,w*.50f,w*.82f);centers.forEachIndexed{i,x->{val y=h*.68f;drawRoundRect(Color(0xFF4E3426),Offset(x-w*.12f,y),Size(w*.24f,h*.035f),CornerRadius(4f,4f));drawRoundRect(WoodLight,Offset(x-w*.105f,y-h*.045f),Size(w*.21f,h*.045f),CornerRadius(4f,4f));drawLine(Wood,Offset(x-w*.09f,y+h*.03f),Offset(x-w*.08f,h*.93f),w*.012f);drawLine(Wood,Offset(x+w*.09f,y+h*.03f),Offset(x+w*.08f,h*.93f),w*.012f);drawRoundRect(Color(0xFF39434A),Offset(x-w*.075f,h*.83f),Size(w*.15f,h*.025f),CornerRadius(7f,7f));drawRoundRect(Color(0xFF46515A),Offset(x-w*.06f,h*.78f),Size(w*.12f,h*.08f),CornerRadius(8f,8f));drawRect(Color(0xFF30383D),Offset(x-w*.055f,h*.88f),Size(w*.11f,h*.012f));drawRoundRect(Color(0xFF202A30),Offset(x-w*.045f,y-h*.09f),Size(w*.09f,h*.07f),CornerRadius(3f,3f));drawRoundRect(if(i==0)Cyan.copy(.45f)else if(i==1)Violet.copy(.45f)else Color(0xFF55A6A0).copy(.45f),Offset(x-w*.038f,y-h*.083f),Size(w*.076f,h*.055f),CornerRadius(2f,2f));drawRect(Color(0xFF30383D),Offset(x-w*.008f,y-h*.02f),Size(w*.016f,h*.02f))}};listOf(w*.18f,w*.82f).forEach{x->drawLine(Color(0xFF6B5A4A),Offset(x,h*.60f),Offset(x,h*.52f),w*.006f);drawCircle(Color(0xFFF4C56E).copy(.7f),w*.035f,Offset(x,h*.51f))}}

@Composable private fun VoiceRings(state:VoiceState){Canvas(Modifier.size(38.dp)){val c=Offset(size.width/2,size.height/2);val base=if(state==VoiceState.SPEAKING)Violet else Cyan;for(i in 0..2)drawCircle(base.copy(alpha=.12f+(2-i)*.05f),size.minDimension*(.28f+i*.14f),c,style=Stroke(size.minDimension*.035f))}}

@Composable private fun OrbLogo(modifier:Modifier){Canvas(modifier){val c=Offset(size.width/2,size.height/2);val r=size.minDimension*.36f;drawCircle(Color(0xFF17212B),r,c);drawCircle(Cyan.copy(.35f),r*.72f,c,style=Stroke(r*.08f));drawCircle(Violet.copy(.8f),r*.25f,Offset(c.x-r*.10f,c.y-r*.12f));drawLine(White,Offset(c.x-r*.18f,c.y+r*.20f),Offset(c.x+r*.18f,c.y+r*.20f),r*.045f);drawLine(White,Offset(c.x-r*.18f,c.y+r*.20f),Offset(c.x-r*.03f,c.y+r*.03f),r*.045f);drawLine(White,Offset(c.x+r*.18f,c.y+r*.20f),Offset(c.x+r*.03f,c.y+r*.03f),r*.045f)}}
