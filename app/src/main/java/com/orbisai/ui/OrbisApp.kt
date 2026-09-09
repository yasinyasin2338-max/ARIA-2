package com.orbisai.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.orbisai.ai.*
import com.orbisai.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }
data class Message(val id:Long,val agentId:String,val text:String,val user:Boolean)

class OrbisViewModel:ViewModel(){
    private val engine:AiEngine=DemoLocalEngine()
    private val _agent=MutableStateFlow(Agents.manager); val agent=_agent.asStateFlow()
    private val _messages=MutableStateFlow<List<Message>>(emptyList()); val messages=_messages.asStateFlow()
    private val _voice=MutableStateFlow(VoiceState.IDLE); val voice=_voice.asStateFlow()
    fun select(a:Agent){_agent.value=a}
    fun voiceToggle(){_voice.value=if(_voice.value==VoiceState.LISTENING)VoiceState.IDLE else VoiceState.LISTENING}
    fun send(raw:String){val text=raw.trim();if(text.isEmpty())return;val a=_agent.value
        _messages.value=_messages.value+Message(System.nanoTime(),a.id,text,true)
        viewModelScope.launch{_voice.value=VoiceState.THINKING;val r=engine.generate(AiRequest(text,a,Mode.AUTO));_messages.value=_messages.value+Message(System.nanoTime(),a.id,r.text,false);_voice.value=VoiceState.IDLE}
    }
}

@Composable fun OrbisApp(onRequestMicrophone:()->Unit,vm:OrbisViewModel=viewModel()){
    val agent by vm.agent.collectAsState(); val messages by vm.messages.collectAsState(); val voice by vm.voice.collectAsState(); var input by remember{mutableStateOf("")}
    MaterialTheme{Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFF8FAFC),Color(0xFFE8EDF4)))).padding(16.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(44.dp).background(Color.White,CircleShape),contentAlignment=Alignment.Center){Text("⚪")};Spacer(Modifier.width(10.dp));Column{Text("ORBIS AI",fontWeight=FontWeight.Bold);Text("Voice-first • Hybrid",style=MaterialTheme.typography.bodySmall)}}
        Spacer(Modifier.height(12.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Agents.all.forEach{a->Card(Modifier.weight(1f).clickable{vm.select(a)},RoundedCornerShape(16.dp)){Column(Modifier.padding(9.dp),horizontalAlignment=Alignment.CenterHorizontally){Text(a.emoji);Text(a.name,fontWeight=if(a.id==agent.id)FontWeight.Bold else FontWeight.Normal);Text(a.role,style=MaterialTheme.typography.labelSmall)}}}}
        val s=animateFloatAsState(if(voice==VoiceState.LISTENING)1.1f else 1f,label="orb")
        Box(Modifier.fillMaxWidth().height(170.dp),contentAlignment=Alignment.Center){Box(Modifier.size(120.dp).scale(s.value).background(Brush.radialGradient(listOf(Color.White,Color(0xFFE5EAF0),Color(0xFFCBD5E1))),CircleShape).clickable{vm.voiceToggle()},contentAlignment=Alignment.Center){Text("⚪",style=MaterialTheme.typography.headlineLarge)}}
        Text(when(voice){VoiceState.IDLE->"آماده‌ام";VoiceState.LISTENING->"دارم گوش می‌دم…";VoiceState.THINKING->"در حال پردازش…";VoiceState.SPEAKING->"در حال صحبت…"},Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp));LazyColumn(Modifier.weight(1f).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){items(messages,key={it.id}){m->Card(Modifier.fillMaxWidth(),RoundedCornerShape(14.dp)){Column(Modifier.padding(12.dp)){Text(if(m.user)"شما" else Agents.all.firstOrNull{it.id==m.agentId}?.name?:"AI",fontWeight=FontWeight.Bold);Text(m.text)}}}}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(input,{input=it},Modifier.weight(1f),placeholder={Text("پیامت را بنویس…")},singleLine=true);Spacer(Modifier.width(8.dp));Button({vm.send(input);input=""}){Text("ارسال")}}
        Spacer(Modifier.height(6.dp));Button(onRequestMicrophone,Modifier.fillMaxWidth()){Text("فعال‌سازی میکروفون")}
    }}}
}
