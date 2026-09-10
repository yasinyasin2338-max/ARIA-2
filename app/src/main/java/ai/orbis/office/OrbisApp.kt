package ai.orbis.office

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Bg = Color(0xFF090C12)
private val Card = Color(0xE6121720)
private val Blue = Color(0xFF4A8CFF)
private val Purple = Color(0xFFA863FF)
private val Green = Color(0xFF22D7A2)

@Composable
fun OrbisOfficeApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(OrbisAgents.arian) }
    var input by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("آماده") }
    var messages by remember { mutableStateOf(listOf<Pair<String,String>>()) }
    var showSettings by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(context.getSharedPreferences("orbis", Context.MODE_PRIVATE).getString("base", "https://api.openai.com/v1") ?: "") }
    var model by remember { mutableStateOf(context.getSharedPreferences("orbis", Context.MODE_PRIVATE).getString("model", "gpt-4o-mini") ?: "") }
    var apiKey by remember { mutableStateOf("") }
    val client = remember { OpenAiCompatibleClient() }
    val voice = remember { VoiceController(context) }

    DisposableEffect(Unit) {
        voice.onPartial = { input = it }
        voice.onFinal = { text -> input = text }
        voice.onState = { state = it }
        voice.onErrorText = { msg -> messages = messages + ("assistant" to msg) }
        onDispose { voice.close() }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        val userText = text.trim(); input = ""; messages = messages + ("user" to userText); state = "در حال فکر کردن"
        scope.launch {
            val result = client.chat(ProviderConfig(baseUrl, model, apiKey), selected, messages.dropLast(1), userText)
            val reply = result.getOrElse { e -> "برای پاسخ هوشمند واقعی، سرویس AI را از تنظیمات متصل کن.\n${e.message.orEmpty()}" }
            messages = messages + ("assistant" to reply); state = "آماده"
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Card, primary = Blue)) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            Column(Modifier.fillMaxSize()) {
                Header(state = state, onSettings = { showSettings = true })
                OfficeHero(selected, onSelect = { selected = it })
                AgentStrip(selected, onSelect = { selected = it })
                Conversation(messages, selected, Modifier.weight(1f))
                Composer(input, onInput = { input = it }, onSend = { send(input) }, onVoice = { voice.listen() }, onTools = { showTools = true })
            }
        }
    }

    if (showSettings) SettingsDialog(baseUrl, model, onDismiss = { showSettings = false }, onSave = { b, m, k ->
        baseUrl = b; model = m; apiKey = k
        context.getSharedPreferences("orbis", Context.MODE_PRIVATE).edit().putString("base", b).putString("model", m).apply()
        showSettings = false
    })
    if (showTools) ToolsDialog(onDismiss = { showTools = false }, onOpen = { openUrl(context, it) })
}

@Composable
private fun Header(state: String, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) { Icon(Icons.Default.Groups, null, tint = Color.White) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text("Orbis AI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("دفتر هوش مصنوعی شما • $state", color = Color(0xFF9AA4B2), fontSize = 11.sp) }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "تنظیمات", tint = Color.White) }
    }
}

@Composable
private fun OfficeHero(selected: Agent, onSelect: (Agent) -> Unit) {
    Box(Modifier.fillMaxWidth().height(240.dp).padding(horizontal = 10.dp).clip(RoundedCornerShape(24.dp)).border(1.dp, Color.White.copy(.08f), RoundedCornerShape(24.dp))) {
        Image(painterResource(R.drawable.office_scene), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Bg.copy(.88f)))))
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OrbisAgents.all.forEachIndexed { i, a ->
                val c = listOf(Blue, Purple, Green)[i]
                Surface(modifier = Modifier.weight(1f).clickable { onSelect(a) }, shape = RoundedCornerShape(16.dp), color = if (selected.id == a.id) c.copy(.28f) else Color(0xB80A0D12), tonalElevation = 0.dp) {
                    Column(Modifier.padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(a.name, color = Color.White, fontWeight = FontWeight.Bold); Text(a.role, color = c, fontSize = 10.sp, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

@Composable
private fun AgentStrip(selected: Agent, onSelect: (Agent) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OrbisAgents.all.forEachIndexed { i, a ->
            val c = listOf(Blue, Purple, Green)[i]
            Surface(modifier = Modifier.weight(1f).clickable { onSelect(a) }, shape = RoundedCornerShape(16.dp), color = if (selected.id == a.id) c.copy(.18f) else Card) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).background(c.copy(.18f), CircleShape), contentAlignment = Alignment.Center) { Icon(if(i==0) Icons.Default.Search else if(i==1) Icons.Default.AutoAwesome else Icons.Default.SettingsSuggest, null, tint = c, modifier = Modifier.size(17.dp)) }
                    Spacer(Modifier.width(7.dp)); Column { Text(a.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp); Text(a.specialty, color = Color(0xFFA5AFBD), fontSize = 9.sp, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun Conversation(messages: List<Pair<String,String>>, selected: Agent, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(messages.size) { scroll.animateScrollTo(scroll.maxValue) }
    Column(modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (messages.isEmpty()) {
            Surface(shape = RoundedCornerShape(18.dp), color = Card) { Column(Modifier.padding(14.dp)) { Text("${selected.name} آماده است", color = Color.White, fontWeight = FontWeight.Bold); Text("${selected.specialty}\nپیامت را بنویس یا روی میکروفون بزن.", color = Color(0xFFADB7C5), fontSize = 12.sp, lineHeight = 18.sp) } }
        }
        messages.forEach { (role, text) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = if (role == "user") Arrangement.End else Arrangement.Start) { Surface(shape = RoundedCornerShape(17.dp), color = if (role == "user") Blue.copy(.25f) else Card, modifier = Modifier.fillMaxWidth(.88f)) { Text(text, color = Color.White, modifier = Modifier.padding(12.dp), fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.Right) } } }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Composer(input: String, onInput: (String)->Unit, onSend:()->Unit, onVoice:()->Unit, onTools:()->Unit) {
    Surface(color = Color(0xFF0D1118), tonalElevation = 8.dp) { Column(Modifier.padding(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onTools) { Icon(Icons.Default.AddCircle, "ابزارها", tint = Color(0xFFADB7C5)) }
            OutlinedTextField(value = input, onValueChange = onInput, placeholder = { Text("هر چیزی که می‌خواهی بگو…", color = Color(0xFF788493)) }, modifier = Modifier.weight(1f), minLines = 1, maxLines = 4, shape = RoundedCornerShape(20.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Blue, unfocusedBorderColor = Color.White.copy(.12f)))
            Spacer(Modifier.width(5.dp)); FilledIconButton(onClick = if (input.isBlank()) onVoice else onSend, colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (input.isBlank()) Purple else Blue)) { Icon(if (input.isBlank()) Icons.Default.Mic else Icons.Default.Send, null) }
        }
        Text("اقدام‌های حساس مثل خرید، پرداخت، انتشار و ارسال نهایی فقط با تأیید شما انجام می‌شوند.", color = Color(0xFF687384), fontSize = 9.sp, modifier = Modifier.fillMaxWidth().padding(top = 5.dp), textAlign = TextAlign.Center)
    } }
}

@Composable
private fun SettingsDialog(base: String, model: String, onDismiss:()->Unit, onSave:(String,String,String)->Unit) {
    var b by remember { mutableStateOf(base) }; var m by remember { mutableStateOf(model) }; var k by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("اتصال هوش مصنوعی") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Orbis از سرویس‌های سازگار با OpenAI استفاده می‌کند؛ می‌توانی سرویس شخصی، سرور محلی یا API دلخواهت را وارد کنی.", fontSize = 12.sp)
        OutlinedTextField(b, { b=it }, label={Text("Base URL")}); OutlinedTextField(m, { m=it }, label={Text("Model")}); OutlinedTextField(k, { k=it }, label={Text("API Key (در حافظه ذخیره نمی‌شود)")})
    } }, confirmButton = { Button(onClick={onSave(b,m,k)}){Text("ذخیره")}}, dismissButton = { TextButton(onClick=onDismiss){Text("بستن")}})
}

@Composable
private fun ToolsDialog(onDismiss:()->Unit, onOpen:(String)->Unit) {
    val tools = listOf("جستجوی وب" to "https://www.google.com", "تلگرام" to "https://t.me", "واتساپ" to "https://wa.me", "اینستاگرام" to "https://instagram.com")
    AlertDialog(onDismissRequest=onDismiss, title={Text("ابزارها و اتصال‌ها")}, text={ Column { tools.forEach { (n,u) -> ListItem(headlineContent={Text(n)}, leadingContent={Icon(Icons.Default.OpenInNew,null)}, modifier=Modifier.clickable{onOpen(u)}) } } }, confirmButton={TextButton(onClick=onDismiss){Text("بستن")}})
}
