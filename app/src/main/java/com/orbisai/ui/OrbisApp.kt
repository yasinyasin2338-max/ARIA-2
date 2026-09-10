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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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

private val Wall=Color(0xFFE7E1D8); private val Floor=Color(0xFFB99B7A); private val Wood=Color(0xFF6E4730)
private val WoodLight=Color(0xFF9A6B48); private val Navy=Color(0xFF17212B); private val Slate=Color(0xFF5D6870)
private val White=Color.White; private val Cyan=Color(0xFF19B7AE); private val Violet=Color(0xFF7257E8)

class OrbisViewModel(app:Application):AndroidViewModel(app){
 private val engine:AiEngine=DemoLocalEngine(); private val voiceEngine=AndroidVoiceEngine(app); private var job:Job?=null
 private val _agent=MutableStateFlow(Agents.manager); val agent=_agent.asStateFlow(); private val _messages=MutableStateFlow<List<Message>>(emptyList()); val messages=_messages.asStateFlow()
 private val _voice=MutableStateFlow(VoiceState.IDLE); val voice=_voice.asStateFlow(); private val _partial=MutableStateFlow(""); val partial=_partial.asStateFlow()
 private val _active=MutableStateFlow(false); val active=_active.asStateFlow(); private val _error=MutableStateFlow<String?>(null); val error=_error.asStateFlow()
 init{viewModelScope.launch{voiceEngine.events.collect{e->when(e){VoiceEvent.ListeningStarted->{_voice.value=VoiceState.LISTENING;_error.value=null};VoiceEvent.ListeningStopped->{if(_voice.value==VoiceState.LISTENING)_voice.value=VoiceState.IDLE};is VoiceEvent.PartialTranscript->_partial.value=e.text;is VoiceEvent.FinalTranscript->{_partial.value="";send(e.text,true)};VoiceEvent.ThinkingStarted->_voice.value=VoiceState.THINKING;is VoiceEvent.SpeechStarted->_voice.value=VoiceState.SPEAKING;VoiceEvent.SpeechFinished->{if(_active.value)startListening()else _voice.value=VoiceState.IDLE};is VoiceEvent.Error->{_error.value=e.message;_voice.value=VoiceState.IDLE}}}}}
 fun activate(a:Agent){_agent.value=a;if(!_active.value)toggleVoice()}; fun toggleVoice(){if(_active.value){_active.value=false;viewModelScope.launch{voiceEngine.cancel()};_voice.value=VoiceState.IDLE}else{_error.value=null;_active.value=true;startListening()}}
 fun startListening(){if(!_active.value)return;viewModelScope.launch{voiceEngine.startListening()}}
 fun send(raw:String,speak:Boolean=_active.value){val text=PersianVoiceProfile.normalize(raw);if(text.isBlank())return;val a=_agent.value;_messages.value=_messages.value+Message(System.nanoTime(),"user",text,true);job?.cancel();job=viewModelScope.launch{_voice.value=VoiceState.THINKING;val r=engine.generate(AiRequest(text,a,Mode.AUTO));_messages.value=_messages.value+Message(System.nanoTime(),a.id,r.text,false);if(speak)voiceEngine.speak(r.text)else _voice.value=VoiceState.IDLE}}
 override fun onCleared(){job?.cancel();voiceEngine.release();super.onCleared()}
}

@Composable fun OrbisApp(onRequestMicrophone:()->Unit={},vm:OrbisViewModel=viewModel()){
 val agent by vm.agent.collectAsState(); val messages by vm.messages.collectAsState(); val state by vm.voice.collectAsState(); val partial by vm.partial.collectAsState(); val active by vm.active.collectAsState(); val error by vm.error.collectAsState(); val context=LocalContext.current
 var input by remember{mutableStateOf("")}; val pulse=rememberInfiniteTransition(label="pulse").animateFloat(1f,1.06f,infiniteRepeatable(tween(850),RepeatMode.Reverse),label="p").value
 fun voice(){if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)vm.toggleVoice()else onRequestMicrophone()}
 MaterialTheme(colorScheme=lightColorScheme(primary=Navy,background=Color(0xFFF1EEE9),surface=White)){Box(Modifier.fillMaxSize()){OfficeRoom(vm,agent,active,pulse);Column(Modifier.fillMaxSize().padding(12.dp)){TopBar(active);Spacer(Modifier.height(6.dp));AgentBar(agent);Spacer(Modifier.height(5.dp));VoiceBar(state,active,partial,voice);if(error!=null)Text(error.orEmpty(),Modifier.fillMaxWidth().padding(4.dp),color=Color(0xFFB42318),fontSize=9.sp,textAlign=TextAlign.Center);Spacer(Modifier.height(4.dp));if(messages.isEmpty())Welcome(agent,Modifier.weight(1f))else Chat(messages,Modifier.weight(1f));Spacer(Modifier.height(5.dp));Composer(input,{input=it},{vm.send(input);input=""},::voice,active)}}}
}

@Composable private fun TopBar(active:Boolean){Surface(Modifier.fillMaxWidth(),RoundedCornerShape(17.dp),White.copy(.94f),shadowElevation=3.dp){Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically){Surface(Modifier.size(40.dp),CircleShape,Navy){Box(contentAlignment=Alignment.Center){Orb(Modifier.size(33.dp))}};Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f)){Text("ORBiS",fontSize=20.sp,fontWeight=FontWeight.Black,color=Navy);Text("AI OFFICE • اتاق فرمان هوشمند",fontSize=8.sp,fontWeight=FontWeight.Bold,color=Slate)};Text(if(active)"VOICE LIVE" else "READY",fontSize=9.sp,fontWeight=FontWeight.Bold,color=if(active)Violet else Slate)}}}
@Composable private fun AgentBar(a:Agent){Surface(Modifier.fillMaxWidth(),RoundedCornerShape(15.dp),White.copy(.92f),shadowElevation=2.dp){Row(Modifier.padding(7.dp),verticalAlignment=Alignment.CenterVertically){Text(a.emoji,fontSize=19.sp);Spacer(Modifier.width(7.dp));Column{Text("ایجنت فعال: ${a.name}",fontSize=11.sp,fontWeight=FontWeight.ExtraBold,color=Navy);Text(a.role,fontSize=8.sp,color=Slate)}}}}
@Composable private fun VoiceBar(s:VoiceState,active:Boolean,partial:String,onVoice:()->Unit){Surface(Modifier.fillMaxWidth(),RoundedCornerShape(15.dp),White.copy(.94f),shadowElevation=2.dp){Row(Modifier.padding(7.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(38.dp).clickable{onVoice()},contentAlignment=Alignment.Center){Orb(Modifier.size(30.dp))};Spacer(Modifier.width(6.dp));Column(Modifier.weight(1f)){Text(when(s){VoiceState.IDLE->"Voice Mode فارسی";VoiceState.LISTENING->"در حال شنیدن…";VoiceState.THINKING->"در حال تحلیل…";VoiceState.SPEAKING->"در حال صحبت…"},fontSize=10.sp,fontWeight=FontWeight.Bold,color=Navy);Text(if(partial.isBlank())"روی کارمند موردنظر بزن" else partial,fontSize=8.sp,color=Slate,maxLines=1)};FilledIconButton(onClick=onVoice,Modifier.size(38.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(active)Violet else Navy)){Icon(if(active)Icons.Default.Stop else Icons.Default.Mic,null,tint=White)}}}}
@Composable private fun Welcome(a:Agent,m:Modifier){Column(m.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Text("دفتر کار هوشمند ORBiS",fontSize=22.sp,fontWeight=FontWeight.Black,color=Navy);Text("۳ میز • ۳ کارمند • ۳ ایجنت",fontSize=11.sp,color=Slate,Modifier.padding(top=4.dp));Text("هر کارمند را لمس کن تا مغز همان ایجنت و Voice Mode آن فعال شود.",fontSize=9.sp,color=Violet,textAlign=TextAlign.Center,Modifier.padding(12.dp));Text("ایجنت فعال: ${a.name}",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Navy)}}
@Composable private fun Chat(ms:List<Message>,m:Modifier){LazyColumn(m,verticalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(vertical=3.dp)){items(ms,key={it.id}){x->Row(Modifier.fillMaxWidth(),horizontalArrangement=if(x.user)Arrangement.End else Arrangement.Start){Surface(RoundedCornerShape(14.dp),if(x.user)Navy else White,shadowElevation=1.dp){Column(Modifier.widthIn(max=315.dp).padding(8.dp)){Text(if(x.user)"شما" else Agents.all.firstOrNull{it.id==x.agentId}?.name?:"ORBiS",fontSize=8.sp,fontWeight=FontWeight.Bold,color=if(x.user)Color(0xFFD0D5DD)else Violet);Text(x.text,fontSize=10.sp,color=if(x.user)White else Navy)}}}}}}
@Composable private fun Composer(v:String,set:(String)->Unit,send:()->Unit,voice:()->Unit,active:Boolean){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(v,set,Modifier.weight(1f),placeholder={Text("دستور یا سؤال…",fontSize=10.sp)},singleLine=true,shape=RoundedCornerShape(16.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=White,focusedContainerColor=White));Spacer(Modifier.width(4.dp));FilledIconButton(onClick=voice,Modifier.size(45.dp),shape=CircleShape,colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(active)Violet else Navy)){Icon(if(active)Icons.Default.Stop else Icons.Default.Mic,null,tint=White)};Spacer(Modifier.width(3.dp));FilledIconButton(onClick=send,enabled=v.isNotBlank(),Modifier.size(45.dp),shape=CircleShape){Icon(Icons.Default.ArrowUpward,null)}}}

@Composable private fun OfficeRoom(vm:OrbisViewModel,current:Agent,active:Boolean,pulse:Float){Box(Modifier.fillMaxSize()){Canvas(Modifier.fillMaxSize()){drawRoom()};Row(Modifier.fillMaxWidth().padding(horizontal=4.dp).offset(y=270.dp),horizontalArrangement=Arrangement.SpaceEvenly){Agents.all.forEach{a->Employee(a,a.id==current.id&&active,pulse){vm.activate(a)}}}}}
@Composable private fun Employee(a:Agent,active:Boolean,pulse:Float,onClick:()->Unit){Column(Modifier.width(106.dp).clickable{onClick()},horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(82.dp),contentAlignment=Alignment.Center){if(active)Box(Modifier.size(78.dp).scale(pulse).background(Cyan.copy(.18f),CircleShape));Person(a,Modifier.size(70.dp));if(active)Surface(Modifier.align(Alignment.TopEnd),CircleShape,Violet){Icon(Icons.Default.Mic,null,Modifier.padding(5.dp).size(13.dp),tint=White)}};Text(a.name,fontSize=10.sp,fontWeight=FontWeight.Black,color=Navy);Text(if(active)"VOICE LIVE" else a.role,fontSize=7.sp,color=if(active)Violet else Slate,maxLines=1,textAlign=TextAlign.Center)}}
@Composable private fun Person(a:Agent,m:Modifier){Canvas(m){val w=size.width;val h=size.height;val skin=if(a.id=="manager")Color(0xFFB97858)else if(a.id=="researcher")Color(0xFFD79B78)else Color(0xFFE0A17F);val hair=if(a.id=="manager")Color(0xFF27221F)else if(a.id=="researcher")Color(0xFF3C2922)else Color(0xFF4A2B2C);val suit=if(a.id=="manager")Navy else if(a.id=="researcher")Color(0xFF506A72)else Color(0xFF6E547A);drawCircle(hair,w*.28f,Offset(w/2,h*.24f));drawCircle(skin,w*.20f,Offset(w/2,h*.28f));drawRoundRect(suit,Offset(w*.27f,h*.46f),Size(w*.46f,h*.43f),CornerRadius(w*.12f,w*.12f));drawCircle(White,w*.025f,Offset(w*.45f,h*.27f));drawCircle(White,w*.025f,Offset(w*.55f,h*.27f));drawCircle(Navy,w*.011f,Offset(w*.45f,h*.27f));drawCircle(Navy,w*.011f,Offset(w*.55f,h*.27f));drawLine(skin,Offset(w*.38f,h*.58f),Offset(w*.20f,h*.76f),w*.06f);drawLine(skin,Offset(w*.62f,h*.58f),Offset(w*.80f,h*.76f),w*.06f)}}

private fun DrawScope.drawRoom(){val w=size.width;val h=size.height;drawRect(Wall,Offset.Zero,Size(w,h*.73f));drawRect(Floor,Offset(0f,h*.73f),Size(w,h*.27f));drawRect(Color(0xFFF5F1EA),Offset(0f,h*.08f),Size(w,h*.04f));
 val wx=w*.31f;val wy=h*.15f;val ww=w*.38f;val wh=h*.28f;drawRoundRect(Color(0xFFB9D9E2),Offset(wx,wy),Size(ww,wh),CornerRadius(7f,7f));drawRect(Color(0xFF789BA5),Offset(wx+ww*.49f,wy),Size(ww*.015f,wh));drawRect(Color(0xFF789BA5),Offset(wx,wy+wh*.48f),Size(ww,wh*.015f));drawCircle(Color(0xFFF6D47A),w*.03f,Offset(wx+ww*.78f,wy+wh*.25f));
 drawRoundRect(Color(0xFFD9D0C5),Offset(wx-w*.075f,wy-w*.01f),Size(w*.10f,wh+w*.05f),CornerRadius(10f,10f));drawRoundRect(Color(0xFFD9D0C5),Offset(wx+ww-w*.025f,wy-w*.01f),Size(w*.10f,wh+w*.05f),CornerRadius(10f,10f));
 drawRoundRect(Color(0xFFF4EFE8),Offset(w*.06f,h*.20f),Size(w*.14f,h*.14f),CornerRadius(5f,5f));drawCircle(Color(0xFF6E8E7B),w*.03f,Offset(w*.13f,h*.27f));drawRect(Wood,Offset(w*.77f,h*.35f),Size(w*.16f,h*.018f));drawRoundRect(Color(0xFF80624D),Offset(w*.79f,h*.25f),Size(w*.12f,h*.09f),CornerRadius(4f,4f));
 val px=w*.91f;val py=h*.68f;drawRoundRect(Color(0xFF8D6145),Offset(px-w*.035f,py),Size(w*.07f,h*.08f),CornerRadius(5f,5f));drawLine(Color(0xFF587B56),Offset(px,py),Offset(px-w*.025f,py-w*.11f),w*.012f);drawLine(Color(0xFF587B56),Offset(px,py),Offset(px+w*.03f,py-w*.10f),w*.012f);drawCircle(Color(0xFF6F9464),w*.035f,Offset(px-w*.025f,py-w*.11f));drawCircle(Color(0xFF789B6D),w*.032f,Offset(px+w*.03f,py-w*.10f));
 listOf(w*.18f,w*.50f,w*.82f).forEach{x->val y=h*.66f;drawRoundRect(Color(0xFF4E3426),Offset(x-w*.12f,y),Size(w*.24f,h*.035f),CornerRadius(4f,4f));drawRoundRect(WoodLight,Offset(x-w*.105f,y-h*.045f),Size(w*.21f,h*.045f),CornerRadius(4f,4f));drawLine(Wood,Offset(x-w*.09f,y+h*.03f),Offset(x-w*.08f,h*.92f),w*.012f);drawLine(Wood,Offset(x+w*.09f,y+h*.03f),Offset(x+w*.08f,h*.92f),w*.012f);drawRoundRect(Color(0xFF39434A),Offset(x-w*.075f,h*.81f),Size(w*.15f,h*.025f),CornerRadius(7f,7f));drawRoundRect(Color(0xFF46515A),Offset(x-w*.06f,h*.76f),Size(w*.12f,h*.08f),CornerRadius(8f,8f));drawRoundRect(Color(0xFF202A30),Offset(x-w*.045f,y-h*.09f),Size(w*.09f,h*.07f),CornerRadius(3f,3f));drawRoundRect(Cyan.copy(.35f),Offset(x-w*.038f,y-h*.083f),Size(w*.076f,h*.055f),CornerRadius(2f,2f))};
 for(i in 0..7){val x=w*.12f+i*w*.11f;drawLine(Color(0x4A6B5545),Offset(w*.5f,h*.73f),Offset(x,h),w*.003f)};drawLine(Color(0x6A6B5545),Offset(0f,h*.84f),Offset(w,h*.84f),w*.004f)
}
@Composable private fun Orb(m:Modifier){Canvas(m){val c=Offset(size.width/2,size.height/2);val r=size.minDimension*.36f;drawCircle(Navy,r,c);drawCircle(Cyan.copy(.4f),r*.72f,c,style=Stroke(r*.08f));drawCircle(Violet.copy(.8f),r*.25f,Offset(c.x-r*.10f,c.y-r*.12f));drawLine(White,Offset(c.x-r*.18f,c.y+r*.20f),Offset(c.x+r*.18f,c.y+r*.20f),r*.045f);drawLine(White,Offset(c.x-r*.18f,c.y+r*.20f),Offset(c.x-r*.03f,c.y+r*.03f),r*.045f);drawLine(White,Offset(c.x+r*.18f,c.y+r*.20f),Offset(c.x+r*.03f,c.y+r*.03f),r*.045f)}}