package ai.orbis.office

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var chatBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var status: TextView
    private var selected: Agent = OrbisAgents.arian
    private val history = mutableListOf<Pair<String,String>>()
    private var providerBase = "https://api.openai.com/v1"
    private var providerModel = "gpt-4o-mini"
    private var providerKey = ""

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchVoice() else toast("برای Voice باید اجازه میکروفون را بدهی.")
    }

    private val speechResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        status.text = "دفتر هوش مصنوعی شما • آماده"
        if (result.resultCode == RESULT_OK) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) input.setText(text)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val p = getSharedPreferences("orbis", Context.MODE_PRIVATE)
            providerBase = p.getString("base", providerBase) ?: providerBase
            providerModel = p.getString("model", providerModel) ?: providerModel
            buildNativeUi()
        } catch (t: Throwable) {
            showStartupFallback(t)
        }
    }

    private fun buildNativeUi() {
        window.statusBarColor = Color.rgb(9,12,18)
        window.navigationBarColor = Color.rgb(9,12,18)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(9,12,18)); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(12)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); setContentView(scroll)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = TextView(this).apply { text = "● ● ●"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = round(Color.BLACK,14); setPadding(dp(10),dp(8),dp(10),dp(8)) }
        val titleWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10),0,0,0) }
        titleWrap.addView(label("Orbis AI",21f,Color.WHITE,true))
        status = label("دفتر هوش مصنوعی شما • آماده",11f,Color.rgb(160,170,185),false); titleWrap.addView(status)
        val settings = Button(this).apply { text="⚙"; textSize=20f; setTextColor(Color.WHITE); background=round(Color.rgb(22,28,38),14); setOnClickListener { showSettings() } }
        header.addView(logo); header.addView(titleWrap, LinearLayout.LayoutParams(0,dp(58),1f)); header.addView(settings,LinearLayout.LayoutParams(dp(50),dp(48))); root.addView(header)

        val hero = FrameLayout(this).apply { background=round(Color.rgb(20,26,36),24) }
        val office = ImageView(this).apply { setImageResource(R.drawable.office_scene); scaleType=ImageView.ScaleType.CENTER_CROP; contentDescription="دفتر Orbis" }
        hero.addView(office,FrameLayout.LayoutParams(-1,dp(245)))
        val overlay = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER; setPadding(dp(8),0,dp(8),dp(8)) }
        OrbisAgents.all.forEach { a -> val b=Button(this).apply { text="${a.name}\n${a.role}"; textSize=11f; setTextColor(Color.WHITE); isAllCaps=false; background=round(agentColor(a),16); setOnClickListener { selectAgent(a) } }; overlay.addView(b,LinearLayout.LayoutParams(0,dp(62),1f).apply { setMargins(dp(3),0,dp(3),0) }) }
        hero.addView(overlay,FrameLayout.LayoutParams(-1,dp(72),Gravity.BOTTOM)); root.addView(hero,LinearLayout.LayoutParams(-1,dp(245)).apply { topMargin=dp(8) })

        val agents=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(0,dp(9),0,dp(9)) }
        OrbisAgents.all.forEach { a -> val b=Button(this).apply { text="${a.name}\n${a.specialty}"; textSize=10f; setTextColor(Color.WHITE); isAllCaps=false; background=round(Color.rgb(18,24,34),16); setOnClickListener { selectAgent(a) } }; agents.addView(b,LinearLayout.LayoutParams(0,dp(64),1f).apply { setMargins(dp(3),0,dp(3),0) }) }; root.addView(agents)

        chatBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; root.addView(chatBox); addBubble("assistant","آرین آماده است\nجستجو، تحلیل، داده و گزارش\nپیامت را بنویس یا روی میکروفون بزن.")

        val composer=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(10),0,0) }
        val tools=Button(this).apply { text="＋"; textSize=20f; setTextColor(Color.WHITE); background=round(Color.rgb(22,28,38),16); setOnClickListener { showTools() } }
        input=EditText(this).apply { hint="هر چیزی که می‌خواهی بگو…"; setHintTextColor(Color.rgb(115,128,145)); setTextColor(Color.WHITE); textSize=14f; minLines=1; maxLines=4; gravity=Gravity.CENTER_VERTICAL or Gravity.RIGHT; setPadding(dp(14),dp(9),dp(14),dp(9)); background=round(Color.rgb(15,20,29),20) }
        val action=Button(this).apply { text="🎙"; textSize=18f; setTextColor(Color.WHITE); background=round(Color.rgb(122,76,220),20); setOnClickListener { if(input.text.isNullOrBlank()) startVoice() else sendMessage() } }
        composer.addView(tools,LinearLayout.LayoutParams(dp(48),dp(48))); composer.addView(input,LinearLayout.LayoutParams(0,dp(52),1f).apply { setMargins(dp(6),0,dp(6),0) }); composer.addView(action,LinearLayout.LayoutParams(dp(54),dp(52))); root.addView(composer)
        root.addView(label("اقدام‌های حساس مثل خرید، پرداخت، انتشار و ارسال نهایی فقط با تأیید شما انجام می‌شوند.",9f,Color.rgb(105,116,134),false).apply { gravity=Gravity.CENTER; setPadding(0,dp(6),0,0) })
    }

    private fun selectAgent(a:Agent){ selected=a; status.text="دفتر هوش مصنوعی شما • ${a.name} انتخاب شد"; addBubble("assistant","${a.name} آماده است — ${a.specialty}") }

    private fun sendMessage(){
        val text=input.text.toString().trim(); if(text.isBlank()) return
        input.setText(""); addBubble("user",text); status.text="دفتر هوش مصنوعی شما • در حال فکر کردن…"
        val snapshot=history.toList(); history += "user" to text
        thread {
            val result=runCatching { runBlocking { OpenAiCompatibleClient().chat(ProviderConfig(providerBase,providerModel,providerKey),selected,snapshot,text).getOrThrow() } }
            runOnUiThread { val reply=result.getOrElse { "برای پاسخ هوشمند واقعی، سرویس AI را از ⚙ تنظیمات متصل کن.\n${it.message.orEmpty()}" }; history += "assistant" to reply; addBubble("assistant",reply); status.text="دفتر هوش مصنوعی شما • آماده" }
        }
    }

    private fun startVoice(){ if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) micPermission.launch(Manifest.permission.RECORD_AUDIO) else launchVoice() }
    private fun launchVoice(){ status.text="دفتر هوش مصنوعی شما • در حال شنیدن…"; val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE,"fa-IR"); putExtra(RecognizerIntent.EXTRA_PROMPT,"با ${selected.name} صحبت کن") }; runCatching { speechResult.launch(i) }.onFailure { status.text="دفتر هوش مصنوعی شما • آماده"; toast("Voice روی این گوشی در دسترس نیست.") } }

    private fun addBubble(role:String,text:String){ if(!::chatBox.isInitialized)return; val tv=TextView(this).apply { this.text=text; textSize=13f; setTextColor(Color.WHITE); gravity=Gravity.RIGHT; setPadding(dp(12),dp(10),dp(12),dp(10)); background=round(if(role=="user")Color.rgb(35,70,125) else Color.rgb(18,24,34),17) }; val wrap=LinearLayout(this).apply { gravity=if(role=="user")Gravity.LEFT else Gravity.RIGHT; setPadding(0,dp(4),0,dp(4)); addView(tv,LinearLayout.LayoutParams(-2,-2)) }; chatBox.addView(wrap,LinearLayout.LayoutParams(-1,-2)) }

    private fun showSettings(){ val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),0,dp(18),0) }; val base=EditText(this).apply { setText(providerBase); hint="Base URL" }; val model=EditText(this).apply { setText(providerModel); hint="Model" }; val key=EditText(this).apply { hint="API Key"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }; box.addView(base); box.addView(model); box.addView(key); AlertDialog.Builder(this).setTitle("اتصال هوش مصنوعی").setView(box).setPositiveButton("ذخیره"){_,_-> providerBase=base.text.toString().trim(); providerModel=model.text.toString().trim(); providerKey=key.text.toString().trim(); getSharedPreferences("orbis",Context.MODE_PRIVATE).edit().putString("base",providerBase).putString("model",providerModel).apply(); toast("تنظیمات ذخیره شد") }.setNegativeButton("بستن",null).show() }
    private fun showTools(){ val names=arrayOf("جستجوی وب","تلگرام","واتساپ","اینستاگرام"); val urls=arrayOf("https://www.google.com","https://t.me","https://wa.me","https://instagram.com"); AlertDialog.Builder(this).setTitle("ابزارها و اتصال‌ها").setItems(names){_,which->openUrl(this,urls[which])}.setNegativeButton("بستن",null).show() }

    private fun showStartupFallback(t:Throwable){ val tv=TextView(this).apply { setBackgroundColor(Color.rgb(9,12,18)); setTextColor(Color.WHITE); textSize=15f; setPadding(dp(22),dp(40),dp(22),dp(22)); gravity=Gravity.RIGHT; text="Orbis AI\n\nبرنامه وارد حالت بازیابی شد و بسته نشد.\n\n${t.javaClass.simpleName}: ${t.message.orEmpty()}" }; setContentView(tv) }
    private fun label(text:String,size:Float,color:Int,bold:Boolean)=TextView(this).apply { this.text=text; textSize=size; setTextColor(color); gravity=Gravity.RIGHT; if(bold)setTypeface(typeface,Typeface.BOLD) }
    private fun round(color:Int,r:Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(r).toFloat() }
    private fun agentColor(a:Agent)=when(a.id){"arian"->Color.rgb(52,105,220);"raha"->Color.rgb(128,74,205);else->Color.rgb(24,150,118)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
}
