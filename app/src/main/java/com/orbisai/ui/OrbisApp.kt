package com.orbisai.ui

import android.app.Application
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbisai.ai.*
import com.orbisai.model.*
import com.orbisai.voice.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val Ink = Color(0xFF172033)
private val Muted = Color(0xFF6B7280)
private val Glass = Color(0xF7FFFFFF)

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }
data class Message(val id:Long,val agentId:String,val text:String,val user:Boolean)

class OrbisViewModel(app: Application): AndroidViewModel(app) {
    private val engine: AiEngine = DemoLocalEngine()
    private val voiceEngine = AndroidVoiceEngine(app)
    private val _agent = MutableStateFlow(Agents.manager); val agent = _agent.asStateFlow()
    private val _messages = MutableStateFlow<List<Message>>(emptyList()); val messages = _messages.asStateFlow()
    private val _voice = MutableStateFlow(VoiceState.IDLE); val voice = _voice.asStateFlow()
    private val _partial = MutableStateFlow(""); val partial = _partial.asStateFlow()
    private val _voiceMode = MutableStateFlow(false); val voiceMode = _voiceMode.asStateFlow()
    private val _error = MutableStateFlow<String?>(null); val error = _error.asStateFlow()

    init {
        viewModelScope.launch {
            voiceEngine.events.collect { event ->
                when(event) {
                    VoiceEvent.ListeningStarted -> _voice.value = VoiceState.LISTENING
                    VoiceEvent.ListeningStopped -> if (_voice.value == VoiceState.LISTENING) _voice.value = VoiceState.IDLE
                    is VoiceEvent.PartialTranscript -> _partial.value = event.text
                    is VoiceEvent.FinalTranscript -> { _partial.value = ""; send(event.text, speak = _voiceMode.value) }
                    VoiceEvent.SpeechStarted -> _voice.value = VoiceState.SPEAKING
                    VoiceEvent.SpeechFinished -> if (_voiceMode.value) startListening() else _voice.value = VoiceState.IDLE
                    is VoiceEvent.Error -> { _error.value = event.message; _voice.value = VoiceState.IDLE }
                    VoiceEvent.ThinkingStarted -> _voice.value = VoiceState.THINKING
                }
            }
        }
    }

    fun select(a: Agent) { _agent.value = a }

    fun toggleVoice() {
        if (_voiceMode.value) { _voiceMode.value = false; viewModelScope.launch { voiceEngine.cancel() } }
        else { _error.value = null; _voiceMode.value = true; startListening() }
    }

    fun startListening() { viewModelScope.launch { voiceEngine.startListening() } }

    fun send(raw:String, speak:Boolean = _voiceMode.value) {
        val text = PersianVoiceProfile.normalize(raw)
        if (text.isBlank()) return
        val a = _agent.value
        _messages.value = _messages.value + Message(System.nanoTime(), a.id, text, true)
        viewModelScope.launch {
            _voice.value = VoiceState.THINKING
            val r = engine.generate(AiRequest(text, a, Mode.AUTO))
            _messages.value = _messages.value + Message(System.nanoTime(), a.id, r.text, false)
            if (speak) voiceEngine.speak(r.text) else _voice.value = VoiceState.IDLE
        }
    }

    override fun onCleared() { voiceEngine.release(); super.onCleared() }
}

@Composable
fun OrbisApp(onRequestMicrophone:()->Unit = {}, vm:OrbisViewModel = viewModel()) {
    val agent by vm.agent.collectAsState(); val messages by vm.messages.collectAsState(); val voice by vm.voice.collectAsState()
    val partial by vm.partial.collectAsState(); val voiceMode by vm.voiceMode.collectAsState(); val error by vm.error.collectAsState()
    var input by remember { mutableStateOf("") }
    val pulse by rememberInfiniteTransition(label="pulse").animateFloat(1f,1.07f,infiniteRepeatable(tween(1200,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="pulse")

    MaterialTheme(colorScheme = lightColorScheme(background=Color(0xFFF3F5F8), surface=Color.White, primary=Color(0xFF1D2638))) {
        Box(Modifier.fillMaxSize()) {
            OfficeBackdrop()
            Column(Modifier.fillMaxSize().padding(horizontal=18.dp, vertical=14.dp)) {
                Header(voiceMode)
                Spacer(Modifier.height(12.dp))
                AgentRow(agent, vm)
                Spacer(Modifier.height(14.dp))
                VoiceStage(voice, voiceMode, partial, pulse) { vm.toggleVoice() }
                Spacer(Modifier.height(10.dp))
                if (error != null) Text(error!!, color=Color(0xFFB42318), style=MaterialTheme.typography.bodySmall, modifier=Modifier.fillMaxWidth(), textAlign=TextAlign.Center)
                Chat(messages, Modifier.weight(1f))
                Composer(input, {input=it}, { vm.send(input); input="" }, { vm.toggleVoice() }, voiceMode)
            }
        }
    }
}

@Composable private fun OfficeBackdrop() {
    Canvas(Modifier.fillMaxSize().alpha(.9f)) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFFF9FAFC),Color(0xFFE8ECF2))))
        drawRect(Color(0x14000000), topLeft=Offset(0f,size.height*.74f), size=androidx.compose.ui.geometry.Size(size.width,size.height*.26f))
        drawCircle(Color(0x12000000), size.minDimension*.32f, Offset(size.width*.82f,size.height*.16f))
        drawLine(Color(0x18000000), Offset(0f,size.height*.74f), Offset(size.width,size.height*.74f), 2f)
    }
}

@Composable private fun Header(voiceMode:Boolean) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).background(Color.White,CircleShape),contentAlignment=Alignment.Center) { OrbMark(Modifier.size(30.dp)) }
        Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text("ORBIS AI",fontWeight=FontWeight.Black,color=Ink); Text("دفتر هوشمند شخصی شما",style=MaterialTheme.typography.labelSmall,color=Muted) }
        Surface(shape=RoundedCornerShape(50),color=if(voiceMode) Color(0xFFE8F7EF) else Color.White) { Text(if(voiceMode) "●  Voice فعال" else "●  آماده",Modifier.padding(horizontal=12.dp,vertical=7.dp),style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,color=if(voiceMode) Color(0xFF087443) else Muted) }
    }
}

@Composable private fun AgentRow(selected:Agent, vm:OrbisViewModel) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) { Agents.all.forEach { a ->
        val active=a.id==selected.id
        Surface(Modifier.weight(1f).clickable{vm.select(a)},shape=RoundedCornerShape(18.dp),color=if(active) Color(0xFF202A3D) else Glass,shadowElevation=if(active) 5.dp else 1.dp) {
            Column(Modifier.padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally) { Text(a.emoji,style=MaterialTheme.typography.titleMedium); Text(a.name,fontWeight=FontWeight.Bold,color=if(active) Color.White else Ink); Text(a.role,style=MaterialTheme.typography.labelSmall,color=if(active) Color(0xFFD5DBE5) else Muted,maxLines=1) }
        }
    }}
}

@Composable private fun VoiceStage(state:VoiceState, active:Boolean, partial:String, pulse:Float, onTap:()->Unit) {
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.height(205.dp).fillMaxWidth(),contentAlignment=Alignment.Center) {
            if(active) Box(Modifier.size(174.dp).scale(if(state==VoiceState.LISTENING)pulse else 1f).background(Color(0x151D2638),CircleShape))
            Box(Modifier.size(138.dp).scale(if(state==VoiceState.LISTENING)pulse else 1f).background(Brush.radialGradient(listOf(Color.White,Color(0xFFE8EDF4),Color(0xFFB9C3D1))),CircleShape).clickable{onTap()},contentAlignment=Alignment.Center) { OrbMark(Modifier.size(72.dp)) }
        }
        Text(when(state){VoiceState.IDLE->if(active)"برای صحبت لمس کنید" else "آماده‌ام";VoiceState.LISTENING->"دارم گوش می‌دهم…";VoiceState.THINKING->"در حال فکر کردن…";VoiceState.SPEAKING->"دارم پاسخ می‌دهم…"},fontWeight=FontWeight.Bold,color=Ink)
        if(partial.isNotBlank()) Text(partial,Modifier.padding(top=4.dp).fillMaxWidth(),textAlign=TextAlign.Center,color=Muted,maxLines=2)
        Text(if(active)"گفت‌وگوی طبیعی • قطع صحبت با لمس گوی" else "Voice Mode را برای مکالمهٔ صوتی فعال کنید",style=MaterialTheme.typography.labelSmall,color=Muted)
    }
}

@Composable private fun OrbMark(modifier:Modifier) { Canvas(modifier) { val c=Offset(size.width/2,size.height/2); drawCircle(Color.White,size.minDimension*.42f,c); drawCircle(Color(0xFFB9C3D1),size.minDimension*.42f,c,style=androidx.compose.ui.graphics.drawscope.Stroke(size.minDimension*.07f)); drawCircle(Color(0xFF667085),size.minDimension*.10f,c) } }

@Composable private fun Chat(messages:List<Message>, modifier:Modifier) { LazyColumn(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(7.dp),contentPadding=PaddingValues(vertical=6.dp)) { items(messages,key={it.id}) { m -> Surface(shape=RoundedCornerShape(17.dp),color=if(m.user) Color(0xFFE9EEF5) else Glass,shadowElevation=1.dp,modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(11.dp)) { Text(if(m.user)"شما" else Agents.all.firstOrNull{it.id==m.agentId}?.name ?: "Orbis",fontWeight=FontWeight.Bold,color=Ink,style=MaterialTheme.typography.labelSmall); Text(m.text,color=Ink,modifier=Modifier.padding(top=3.dp)) } } } } }

@Composable private fun Composer(value:String,onValue:(String)->Unit,onSend:()->Unit,onVoice:()->Unit,voice:Boolean) { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { OutlinedTextField(value,onValue,Modifier.weight(1f),placeholder={Text("پیامت را بنویس…")},singleLine=true,shape=RoundedCornerShape(20.dp)); Spacer(Modifier.width(7.dp)); FilledIconButton(onClick=onVoice,modifier=Modifier.size(52.dp),shape=CircleShape) { Text(if(voice)"■" else "🎙",style=MaterialTheme.typography.titleMedium) }; Spacer(Modifier.width(5.dp)); Button(onClick=onSend,shape=RoundedCornerShape(18.dp),enabled=value.isNotBlank()) { Text("ارسال") } } }
