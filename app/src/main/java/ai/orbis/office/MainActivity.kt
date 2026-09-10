package ai.orbis.office

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("orbis_v2", Context.MODE_PRIVATE) }
    private lateinit var root: FrameLayout
    private lateinit var contentHost: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var commandInput: EditText
    private lateinit var commandAction: TextView
    private lateinit var voice: VoiceModeController

    private var selected: Agent = OrbisAgents.arian
    private var providerBase = "https://api.openai.com/v1"
    private var providerModel = "gpt-4o-mini"
    private var providerKey = ""
    private var activeTab = "office"
    private val histories = mutableMapOf<String, MutableList<Pair<String, String>>>()
    private val tasks = mutableListOf<OrbisTask>()
    private var pendingVoiceStart = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingVoiceStart) {
            pendingVoiceStart = false
            openVoiceMode()
        } else if (!granted) {
            pendingVoiceStart = false
            toast("برای Voice Mode باید اجازه میکروفون را بدهی.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(6, 9, 14)
        window.navigationBarColor = Color.rgb(6, 9, 14)
        providerBase = prefs.getString("base", providerBase) ?: providerBase
        providerModel = prefs.getString("model", providerModel) ?: providerModel
        providerKey = prefs.getString("key", "") ?: ""
        selected = OrbisAgents.byId(prefs.getString("agent", "arian"))
        loadHistory()
        loadTasks()
        voice = VoiceModeController(this)
        installCrashMarker()
        try {
            buildAppShell()
            showOffice()
        } catch (t: Throwable) {
            showFatalFallback(t)
        }
    }

    override fun onDestroy() {
        if (::voice.isInitialized) voice.close()
        super.onDestroy()
    }

    private fun installCrashMarker() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { prefs.edit().putString("last_crash", "${error.javaClass.simpleName}: ${error.message.orEmpty()}").apply() }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun buildAppShell() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 10, 16))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        setContentView(root)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        body.addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(72)))
        body.addView(buildCommandBar(), LinearLayout.LayoutParams(-1, dp(62)).apply { setMargins(dp(12), 0, dp(12), dp(8)) })
        contentHost = FrameLayout(this)
        body.addView(contentHost, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(buildBottomNav(), LinearLayout.LayoutParams(-1, dp(72)))

        val crash = prefs.getString("last_crash", null)
        if (!crash.isNullOrBlank()) {
            prefs.edit().remove("last_crash").apply()
            toast("نسخه جدید در حالت محافظت‌شده اجرا شد.")
        }
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(6))
        }
        val logo = TextView(this).apply {
            text = "♟♟♟"
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(17, 24, 35), 18, strokeColor = Color.rgb(64, 107, 188))
        }
        row.addView(logo, LinearLayout.LayoutParams(dp(52), dp(52)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        titles.addView(text("Orbis AI", 22f, Color.WHITE, true))
        statusText = text("دفتر هوش مصنوعی شما • ${selected.name} آماده", 11f, Color.rgb(152, 166, 186), false)
        titles.addView(statusText)
        row.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = actionChip("⚙", Color.rgb(22, 29, 40)) { showSettings() }
        row.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)))
        return row
    }

    private fun buildCommandBar(): View {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(8), dp(6))
            background = rounded(Color.rgb(20, 25, 35), 24, strokeColor = Color.rgb(52, 60, 76))
        }
        commandInput = EditText(this).apply {
            hint = "هر چیزی که می‌خواهی بگو…"
            setHintTextColor(Color.rgb(132, 143, 160))
            setTextColor(Color.WHITE)
            textSize = 14f
            singleLine = true
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            background = null
            setPadding(dp(10), 0, dp(10), 0)
        }
        commandAction = TextView(this).apply {
            text = "🎙"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(selected.accent, 18)
            setOnClickListener {
                val value = commandInput.text.toString().trim()
                if (value.isBlank()) requestVoiceMode() else sendFromCommand(value)
            }
        }
        shell.addView(commandInput, LinearLayout.LayoutParams(0, -1, 1f))
        shell.addView(commandAction, LinearLayout.LayoutParams(dp(48), dp(48)))
        return shell
    }

    private fun buildBottomNav(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(6), dp(7), dp(8))
            background = rounded(Color.rgb(11, 16, 24), 22, strokeColor = Color.rgb(32, 40, 53))
        }
        val items = listOf(
            Triple("خانه", "⌂", "office"),
            Triple("گفتگو", "◌", "chat"),
            Triple("وظایف", "☑", "tasks"),
            Triple("ابزارها", "▦", "tools")
        )
        items.forEach { (label, icon, id) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setOnClickListener {
                    activeTab = id
                    when (id) {
                        "office" -> showOffice()
                        "chat" -> showChat()
                        "tasks" -> showTasks()
                        else -> showTools()
                    }
                }
            }
            item.addView(text(icon, 19f, Color.rgb(219, 228, 242), false).apply { gravity = Gravity.CENTER })
            item.addView(text(label, 10f, Color.rgb(174, 186, 204), false).apply { gravity = Gravity.CENTER })
            bar.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
        }
        return bar
    }

    private fun showOffice() {
        activeTab = "office"
        contentHost.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(14))
        }
        scroll.addView(col, ScrollView.LayoutParams(-1, -2))
        contentHost.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val hero = FrameLayout(this).apply {
            background = rounded(Color.rgb(14, 19, 27), 24)
            clipToOutline = true
        }
        val image = ImageView(this).apply {
            setImageResource(R.drawable.office_scene)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "دفتر Orbis AI با سه کارمند"
        }
        hero.addView(image, FrameLayout.LayoutParams(-1, dp(345)))
        val shade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x22000000, 0x33000000, 0xE8000000.toInt())
            )
        }
        hero.addView(shade, FrameLayout.LayoutParams(-1, dp(345)))

        val heroInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        heroInfo.addView(text("تیم هوش مصنوعی شما", 20f, Color.WHITE, true))
        heroInfo.addView(text("سه متخصص مستقل، یک دفتر مشترک", 11f, Color.rgb(220, 226, 238), false))
        hero.addView(heroInfo, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val agentsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(10))
        }
        OrbisAgents.all.forEach { agent ->
            val card = compactAgentCard(agent)
            agentsRow.addView(card, LinearLayout.LayoutParams(0, dp(104), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        hero.addView(agentsRow, FrameLayout.LayoutParams(-1, dp(116), Gravity.BOTTOM))
        col.addView(hero, LinearLayout.LayoutParams(-1, dp(345)))

        col.addView(sectionTitle("دسترسی سریع"))
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(
            quickCard("🌐", "تحقیق وب", "آرین", OrbisAgents.arian) {
                selectAgent(OrbisAgents.arian)
                commandInput.setText("درباره این موضوع تحقیق کن: ")
                commandInput.requestFocus()
            },
            LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(0, 0, dp(5), 0) }
        )
        quick.addView(
            quickCard("✦", "ایده و محتوا", "رها", OrbisAgents.raha) {
                selectAgent(OrbisAgents.raha)
                commandInput.setText("برای این موضوع ایده بده: ")
                commandInput.requestFocus()
            },
            LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(5), 0, 0, 0) }
        )
        col.addView(quick)

        val quick2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        quick2.addView(
            quickCard("✓", "برنامه اجرا", "کیان", OrbisAgents.kian) {
                selectAgent(OrbisAgents.kian)
                commandInput.setText("این کار را به برنامه اجرایی تبدیل کن: ")
                commandInput.requestFocus()
            },
            LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(0, 0, dp(5), 0) }
        )
        quick2.addView(
            quickCard("🎙", "Voice Mode", selected.name, selected) { requestVoiceMode() },
            LinearLayout.LayoutParams(0, dp(92), 1f).apply { setMargins(dp(5), 0, 0, 0) }
        )
        col.addView(quick2)

        col.addView(sectionTitle("وضعیت دفتر"))
        val dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(16, 22, 31), 20, strokeColor = Color.rgb(36, 46, 61))
        }
        dashboard.addView(text("${tasks.count { !it.done }} کار باز  •  ${histories.values.sumOf { it.size / 2 }} گفت‌وگو  •  ${selected.name} فعال", 13f, Color.WHITE, true))
        dashboard.addView(
            text(
                "همه اقدام‌های حساس مثل پرداخت، خرید، انتشار و ارسال نهایی فقط با تأیید شما انجام می‌شوند.",
                10f,
                Color.rgb(153, 167, 187),
                false
            ).apply { setPadding(0, dp(6), 0, 0) }
        )
        col.addView(dashboard)
    }

    private fun compactAgentCard(agent: Agent): View {
        val selectedNow = selected.id == agent.id
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(6))
            background = rounded(
                if (selectedNow) withAlpha(agent.accent, 210) else Color.rgb(13, 18, 26),
                18,
                strokeColor = if (selectedNow) agent.accent else Color.rgb(80, 90, 105)
            )
            addView(text(agent.name, 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
            addView(text(agent.role, 10f, if (selectedNow) Color.WHITE else agent.accent, true).apply { gravity = Gravity.CENTER })
            addView(text("🎙 گفتگو", 10f, Color.rgb(225, 232, 242), false).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            })
            setOnClickListener {
                selectAgent(agent)
                showOffice()
            }
        }
    }

    private fun quickCard(icon: String, title: String, sub: String, agent: Agent, click: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = rounded(Color.rgb(15, 21, 30), 18, strokeColor = withAlpha(agent.accent, 130))
            addView(text("$icon  $title", 13f, Color.WHITE, true))
            addView(text(sub, 10f, agent.accent, false).apply { setPadding(0, dp(3), 0, 0) })
            setOnClickListener { click() }
        }
    }

    private fun showChat() {
        activeTab = "chat"
        contentHost.removeAllViews()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        contentHost.addView(container, FrameLayout.LayoutParams(-1, -1))
        container.addView(buildAgentSwitcher(), LinearLayout.LayoutParams(-1, dp(72)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(10))
        }
        scroll.addView(messages, ScrollView.LayoutParams(-1, -2))
        container.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val history = histories.getOrPut(selected.id) { mutableListOf() }
        if (history.isEmpty()) {
            addBubble(messages, "assistant", "من ${selected.name} هستم؛ ${selected.specialty}. چه کاری را شروع کنیم؟")
        } else {
            history.forEach { (role, value) -> addBubble(messages, role, value) }
        }

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(15, 21, 30), 22, strokeColor = Color.rgb(44, 54, 69))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val input = EditText(this).apply {
            hint = "پیام به ${selected.name}…"
            setHintTextColor(Color.rgb(128, 140, 158))
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 4
            background = null
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }
        val mic = actionChip("🎙", selected.accent) { requestVoiceMode() }
        val send = actionChip("➤", selected.accent) {
            val value = input.text.toString().trim()
            if (value.isNotBlank()) {
                input.setText("")
                sendChat(value, messages, scroll)
            }
        }
        composer.addView(mic, LinearLayout.LayoutParams(dp(44), dp(44)))
        composer.addView(input, LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
        composer.addView(send, LinearLayout.LayoutParams(dp(44), dp(44)))
        container.addView(composer, LinearLayout.LayoutParams(-1, dp(62)))
    }

    private fun buildAgentSwitcher(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(5))
        }
        OrbisAgents.all.forEach { agent ->
            val chip = TextView(this).apply {
                text = "${agent.name}\n${agent.role}"
                gravity = Gravity.CENTER
                textSize = 10f
                setTextColor(Color.WHITE)
                background = rounded(
                    if (selected.id == agent.id) withAlpha(agent.accent, 210) else Color.rgb(18, 24, 34),
                    17,
                    strokeColor = if (selected.id == agent.id) agent.accent else Color.rgb(48, 58, 72)
                )
                setOnClickListener {
                    selectAgent(agent)
                    if (activeTab == "chat") showChat()
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        return row
    }

    private fun sendFromCommand(value: String) {
        commandInput.setText("")
        activeTab = "chat"
        showChat()
        val container = contentHost.getChildAt(0) as? LinearLayout ?: return
        val scroll = container.getChildAt(1) as? ScrollView ?: return
        val messages = scroll.getChildAt(0) as? LinearLayout ?: return
        sendChat(value, messages, scroll)
    }

    private fun sendChat(value: String, messages: LinearLayout, scroll: ScrollView) {
        val history = histories.getOrPut(selected.id) { mutableListOf() }
        addBubble(messages, "user", value)
        history += "user" to value
        saveHistory()
        statusText.text = "${selected.name} • در حال فکر کردن…"
        val thinking = addBubble(messages, "assistant", "…")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        val agentSnapshot = selected
        val snapshot = history.dropLast(1).toList()
        val config = ProviderConfig(providerBase, providerModel, providerKey)
        thread {
            val result = OpenAiCompatibleClient().chat(config, agentSnapshot, snapshot, value)
            runOnUiThread {
                val reply = result.getOrElse { offlineFallback(agentSnapshot, value, it.message.orEmpty()) }
                thinking.text = reply
                history += "assistant" to reply
                saveHistory()
                statusText.text = "دفتر هوش مصنوعی شما • ${agentSnapshot.name} آماده"
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun offlineFallback(agent: Agent, text: String, error: String): String {
        if (providerKey.isBlank()) {
            return when (agent.id) {
                "arian" -> "برای تحلیل هوشمند آنلاین، سرویس AI را از ⚙ متصل کن. فعلاً می‌توانم ساختار کار را آماده کنم: موضوع «${text.take(60)}» را به هدف، اطلاعات لازم، معیار بررسی و نتیجه تقسیم کن."
                "raha" -> "برای تولید محتوای هوشمند کامل، سرویس AI را از ⚙ متصل کن. برای «${text.take(60)}» فعلاً سه مسیر داریم: ساده و مستقیم، حرفه‌ای و برندمحور، یا خلاق و متفاوت."
                else -> "برای اجرای هوشمند کامل، سرویس AI را از ⚙ متصل کن. شروع عملی برای «${text.take(60)}»: هدف را مشخص کن، کار را به مراحل کوتاه تقسیم کن، وابستگی‌ها را بررسی کن و بعد اجرا را مرحله‌به‌مرحله پیش ببر."
            }
        }
        return "اتصال به سرویس AI برقرار نشد.\n$error"
    }

    private fun showTasks() {
        activeTab = "tasks"
        contentHost.removeAllViews()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        contentHost.addView(col, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("وظایف و پروژه‌ها", 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, dp(54), 1f))
        header.addView(actionChip("＋", selected.accent) { promptAddTask() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        col.addView(header)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, ScrollView.LayoutParams(-1, -2))
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (tasks.isEmpty()) list.addView(emptyCard("هنوز کاری ثبت نشده. روی + بزن و اولین کار را بساز."))
        tasks.forEachIndexed { index, task -> list.addView(taskRow(index, task)) }
    }

    private fun promptAddTask() {
        val input = EditText(this).apply {
            hint = "مثلاً تحقیق بازار برای محصول جدید"
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("کار جدید")
            .setView(input)
            .setPositiveButton("افزودن") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotBlank()) {
                    tasks += OrbisTask(value, false, selected.id)
                    saveTasks()
                    showTasks()
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun taskRow(index: Int, task: OrbisTask): View {
        val agent = OrbisAgents.byId(task.agentId)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(16, 22, 31), 17, strokeColor = Color.rgb(38, 48, 62))
        }
        val check = CheckBox(this).apply {
            isChecked = task.done
            buttonTintList = android.content.res.ColorStateList.valueOf(agent.accent)
            setOnCheckedChangeListener { _, checked ->
                tasks[index] = task.copy(done = checked)
                saveTasks()
            }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(task.title, 13f, if (task.done) Color.rgb(130, 142, 158) else Color.WHITE, true))
        texts.addView(text("مسئول: ${agent.name} • ${agent.role}", 10f, agent.accent, false))
        row.addView(check, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnLongClickListener {
            tasks.removeAt(index)
            saveTasks()
            showTasks()
            true
        }
        return FrameLayout(this).apply {
            setPadding(0, 0, 0, dp(7))
            addView(row, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun showTools() {
        activeTab = "tools"
        contentHost.removeAllViews()
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(16))
        }
        scroll.addView(col, ScrollView.LayoutParams(-1, -2))
        contentHost.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        col.addView(sectionTitle("ابزارها و اتصال‌ها"))
        col.addView(toolCard("🌐", "وب و جستجو", "مرورگر را برای تحقیق باز می‌کند.") { openUrl(this, "https://www.google.com") })
        col.addView(toolCard("✈", "تلگرام", "باز کردن Telegram؛ اتصال خودکار نیازمند Bot/API رسمی است.") { openUrl(this, "https://t.me") })
        col.addView(toolCard("◉", "واتساپ", "باز کردن WhatsApp؛ پیام خودکار نیازمند API رسمی و تأیید شماست.") { openUrl(this, "https://wa.me") })
        col.addView(toolCard("◎", "اینستاگرام", "باز کردن Instagram؛ انتشار خودکار نیازمند API رسمی است.") { openUrl(this, "https://instagram.com") })
        col.addView(toolCard("⚙", "اتصال هوش مصنوعی", "OpenAI-compatible: Base URL، Model و API Key") { showSettings() })
        col.addView(sectionTitle("اصل ایمنی"))
        col.addView(emptyCard("Orbis می‌تواند برنامه‌ریزی، تحلیل و آماده‌سازی انجام دهد؛ خرید، پرداخت، انتشار عمومی و ارسال نهایی فقط بعد از تأیید صریح شما انجام می‌شوند."))
    }

    private fun toolCard(icon: String, title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(16, 22, 31), 18, strokeColor = Color.rgb(39, 50, 65))
            setOnClickListener { onClick() }
        }
        row.addView(text(icon, 22f, Color.WHITE, false).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 14f, Color.WHITE, true))
        texts.addView(text(sub, 10f, Color.rgb(150, 164, 184), false))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return FrameLayout(this).apply {
            setPadding(0, 0, 0, dp(7))
            addView(row, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun requestVoiceMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            openVoiceMode()
        } else {
            pendingVoiceStart = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun openVoiceMode() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val shell = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(6, 9, 14))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val office = ImageView(this).apply {
            setImageResource(R.drawable.office_scene)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.28f
        }
        shell.addView(office, FrameLayout.LayoutParams(-1, -1))
        val shade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x9906090E.toInt(), 0xE606090E.toInt(), 0xFF06090E.toInt())
            )
        }
        shell.addView(shade, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(28), dp(18), dp(24))
        }
        shell.addView(col, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = actionChip("×", Color.rgb(30, 37, 49)) {
            voice.stop()
            dialog.dismiss()
        }
        val title = text("Voice Mode • ${selected.name}", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        top.addView(close, LinearLayout.LayoutParams(dp(46), dp(46)))
        top.addView(title, LinearLayout.LayoutParams(0, dp(46), 1f))
        top.addView(View(this), LinearLayout.LayoutParams(dp(46), dp(46)))
        col.addView(top, LinearLayout.LayoutParams(-1, dp(52)))
        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 0.6f))

        val orb = TextView(this).apply {
            text = "◉"
            textSize = 78f
            gravity = Gravity.CENTER
            setTextColor(selected.accent)
            background = rounded(withAlpha(selected.accent, 38), 72, strokeColor = withAlpha(selected.accent, 180))
        }
        col.addView(orb, LinearLayout.LayoutParams(dp(152), dp(152)))
        val state = text("در حال آماده‌سازی…", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        col.addView(state)
        val live = text("حرف بزن؛ ${selected.name} گوش می‌دهد.", 14f, Color.rgb(190, 201, 218), false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), 0)
        }
        col.addView(live, LinearLayout.LayoutParams(-1, -2))
        val reply = text("", 13f, Color.rgb(226, 233, 244), false).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(18), dp(14), 0)
        }
        col.addView(reply, LinearLayout.LayoutParams(-1, -2))
        col.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val mute = actionChip("🔇", Color.rgb(28, 35, 47)) {
            voice.setMuted(!voice.isMuted())
            toast(if (voice.isMuted()) "Voice بی‌صدا شد" else "Voice فعال شد")
        }
        val end = TextView(this).apply {
            text = "پایان گفتگو"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(178, 48, 64), 22)
            setOnClickListener {
                voice.stop()
                dialog.dismiss()
            }
        }
        val keyboard = actionChip("⌨", Color.rgb(28, 35, 47)) {
            voice.stop()
            dialog.dismiss()
            activeTab = "chat"
            showChat()
            commandInput.requestFocus()
        }
        controls.addView(mute, LinearLayout.LayoutParams(dp(54), dp(54)).apply { setMargins(dp(7), 0, dp(7), 0) })
        controls.addView(end, LinearLayout.LayoutParams(dp(150), dp(54)).apply { setMargins(dp(7), 0, dp(7), 0) })
        controls.addView(keyboard, LinearLayout.LayoutParams(dp(54), dp(54)).apply { setMargins(dp(7), 0, dp(7), 0) })
        col.addView(controls)
        col.addView(text("مکالمه پیوسته • شنیدن → فکر کردن → پاسخ صوتی", 10f, Color.rgb(126, 140, 160), false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        voice.onState = { s ->
            runOnUiThread {
                state.text = when (s) {
                    VoiceModeController.State.LISTENING -> "دارم گوش می‌دهم…"
                    VoiceModeController.State.THINKING -> "${selected.name} در حال فکر کردن…"
                    VoiceModeController.State.SPEAKING -> "${selected.name} در حال صحبت…"
                    else -> "آماده"
                }
                orb.alpha = if (s == VoiceModeController.State.LISTENING) 1f else 0.78f
            }
        }
        voice.onPartial = { value -> runOnUiThread { live.text = value } }
        voice.onRms = { rms ->
            runOnUiThread {
                val size = 72f + rms.coerceIn(0f, 12f) * 1.5f
                orb.textSize = size
            }
        }
        voice.onErrorText = { e -> runOnUiThread { reply.text = e } }
        voice.onFinal = { userText ->
            runOnUiThread {
                live.text = userText
                reply.text = "…"
            }
            voice.markThinking()
            val agentSnapshot = selected
            val history = histories.getOrPut(agentSnapshot.id) { mutableListOf() }
            val snapshot = history.toList()
            history += "user" to userText
            saveHistory()
            thread {
                val result = OpenAiCompatibleClient().chat(
                    ProviderConfig(providerBase, providerModel, providerKey),
                    agentSnapshot,
                    snapshot,
                    userText
                )
                val answer = result.getOrElse { offlineFallback(agentSnapshot, userText, it.message.orEmpty()) }
                history += "assistant" to answer
                saveHistory()
                runOnUiThread {
                    reply.text = answer
                    voice.speak(answer)
                }
            }
        }
        dialog.setOnDismissListener { voice.stop() }
        dialog.setContentView(shell)
        dialog.show()
        voice.start(selected)
    }

    private fun selectAgent(agent: Agent) {
        selected = agent
        prefs.edit().putString("agent", agent.id).apply()
        statusText.text = "دفتر هوش مصنوعی شما • ${agent.name} آماده"
        commandAction.background = rounded(agent.accent, 18)
        if (::voice.isInitialized) voice.setAgent(agent)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val base = EditText(this).apply {
            setText(providerBase)
            hint = "Base URL مثل https://api.openai.com/v1"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val model = EditText(this).apply {
            setText(providerModel)
            hint = "Model"
        }
        val key = EditText(this).apply {
            setText(providerKey)
            hint = "API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(base)
        box.addView(model)
        box.addView(key)
        AlertDialog.Builder(this)
            .setTitle("اتصال هوش مصنوعی")
            .setMessage("Orbis به هر سرویس سازگار با OpenAI Chat Completions متصل می‌شود. خود برنامه اشتراک اجباری ندارد.")
            .setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                providerBase = base.text.toString().trim()
                providerModel = model.text.toString().trim()
                providerKey = key.text.toString().trim()
                prefs.edit().putString("base", providerBase).putString("model", providerModel).putString("key", providerKey).apply()
                toast("تنظیمات ذخیره شد")
            }
            .setNeutralButton("پاک کردن کلید") { _, _ ->
                providerKey = ""
                prefs.edit().remove("key").apply()
                toast("کلید پاک شد")
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun addBubble(parent: LinearLayout, role: String, value: String): TextView {
        val tv = text(value, 13f, Color.WHITE, false).apply {
            gravity = Gravity.RIGHT
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(
                if (role == "user") withAlpha(selected.accent, 185) else Color.rgb(17, 23, 33),
                17,
                strokeColor = if (role == "user") selected.accent else Color.rgb(42, 52, 67)
            )
        }
        val width = (resources.displayMetrics.widthPixels * 0.82).toInt()
        val wrap = LinearLayout(this).apply {
            gravity = if (role == "user") Gravity.LEFT else Gravity.RIGHT
            setPadding(0, dp(4), 0, dp(4))
            addView(tv, LinearLayout.LayoutParams(width, -2))
        }
        parent.addView(wrap, LinearLayout.LayoutParams(-1, -2))
        return tv
    }

    private fun sectionTitle(value: String) = text(value, 16f, Color.WHITE, true).apply {
        setPadding(dp(2), dp(16), 0, dp(9))
    }

    private fun emptyCard(value: String) = text(value, 12f, Color.rgb(173, 186, 205), false).apply {
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(Color.rgb(16, 22, 31), 18, strokeColor = Color.rgb(40, 51, 66))
    }

    private fun actionChip(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(color, 16)
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.RIGHT
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun saveHistory() {
        val root = JSONObject()
        histories.forEach { (id, list) ->
            val arr = JSONArray()
            list.takeLast(40).forEach { (role, message) ->
                arr.put(JSONObject().put("r", role).put("t", message))
            }
            root.put(id, arr)
        }
        prefs.edit().putString("history", root.toString()).apply()
    }

    private fun loadHistory() {
        val raw = prefs.getString("history", null) ?: return
        runCatching {
            val root = JSONObject(raw)
            OrbisAgents.all.forEach { agent ->
                val arr = root.optJSONArray(agent.id) ?: return@forEach
                val list = mutableListOf<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list += o.optString("r") to o.optString("t")
                }
                histories[agent.id] = list
            }
        }
    }

    private fun saveTasks() {
        val arr = JSONArray()
        tasks.forEach {
            arr.put(JSONObject().put("t", it.title).put("d", it.done).put("a", it.agentId))
        }
        prefs.edit().putString("tasks", arr.toString()).apply()
    }

    private fun loadTasks() {
        val raw = prefs.getString("tasks", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tasks += OrbisTask(o.optString("t"), o.optBoolean("d"), o.optString("a", "arian"))
            }
        }
    }

    private fun showFatalFallback(t: Throwable) {
        val tv = text(
            "Orbis AI\n\nبرنامه وارد حالت محافظت‌شده شد و بسته نشد.\n\n${t.javaClass.simpleName}: ${t.message.orEmpty()}\n\nاین متن را برای آریس بفرست تا علت دقیق مشخص شود.",
            15f,
            Color.WHITE,
            false
        ).apply {
            setBackgroundColor(Color.rgb(7, 10, 16))
            setPadding(dp(22), dp(48), dp(22), dp(22))
        }
        setContentView(tv)
    }
}

data class OrbisTask(val title: String, val done: Boolean, val agentId: String)
