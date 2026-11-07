package io.github.cornming.nfccardlab

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 讀卡驗證：刷卡 → 顯示 UID 與卡片資訊 → 判斷是否適合用 UID 辨識 → 可登錄成白名單。
 * 每張已登錄的卡可以綁一個「動作」（模擬、HTTP POST…），刷卡後依規則（冷卻時間）執行。
 *
 * 讀卡分兩條路：
 * 1. App 在前景（已開啟且 resumed）：用 NFC reader mode（[readerCallback]），onResume 啟動、onPause 關閉。
 * 2. App 不在前景（背景、或還沒開啟）：reader mode 沒有作用，要靠 AndroidManifest 裡宣告的
 *    TECH_DISCOVERED intent-filter，讓系統把卡片資料透過 Intent 一併送進來，
 *    由 [handleTagIntent] 在 onCreate（冷啟動）或 onNewIntent（App 還留在背景堆疊）處理。
 *    沒有這條路的話，App 不在前景時刷卡只會把 App 叫醒，卡片資料不會被處理，
 *    要等使用者在 App 開啟後再刷一次才會成功。
 *
 * 每筆操作紀錄都會標註是哪條路徑處理的（前景讀卡／冷啟動／背景喚回／手動測試），
 * 並可以用「複製紀錄」或「匯出紀錄」把完整紀錄整理好帶出去，不需要手動謄打畫面內容。
 */
class MainActivity : Activity() {

    private lateinit var store: CardStore
    private lateinit var opLog: OperationLog
    private val stability = StabilityTracker()
    private val policy = AccessPolicy(COOLDOWN_MS)

    private var adapter: NfcAdapter? = null
    private var current: CardSnapshot? = null
    private var currentFindings: List<CardAnalyzer.Finding> = emptyList()
    private var currentStability: String = ""
    /** 下一次 onCard／onReadError 要記錄的來源，在呼叫前設定（見 [handleTagIntent]、[readerCallback]）。 */
    private var pendingSource: String = SOURCE_FOREGROUND

    private lateinit var nfcStatus: TextView
    private lateinit var nfcSettingsBtn: Button
    private lateinit var banner: TextView
    private lateinit var detail: TextView
    private lateinit var findingsView: TextView
    private lateinit var stabilityView: TextView
    private lateinit var registeredView: TextView
    private lateinit var actionView: TextView
    private lateinit var logView: TextView
    private lateinit var editActionBtn: Button
    private lateinit var testActionBtn: Button
    private lateinit var registerBtn: Button
    private lateinit var unregisterBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var resetBtn: Button

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        // 這個回呼在背景執行緒，要先解析再切回主執行緒更新畫面。
        val result = runCatching { TagReader.read(tag) }
        runOnUiThread {
            pendingSource = SOURCE_FOREGROUND
            result.fold(onSuccess = ::onCard, onFailure = ::onReadError)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CardStore(this)
        opLog = OperationLog(this)
        setContentView(buildUi())
        showIdle()
        refreshRegistered()
        renderLog()
        // 冷啟動：如果是系統透過 TECH_DISCOVERED 把卡片資料連同 Intent 一起送進來的，立刻處理。
        // 一般從主畫面圖示開啟（LAUNCHER intent）不會帶卡片資料，這裡會直接跳過。
        handleTagIntent(intent, SOURCE_COLD_START)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // App 還留在背景堆疊（沒有被系統回收）時，singleTop 讓系統呼叫這裡而不是重新 onCreate。
        setIntent(intent)
        handleTagIntent(intent, SOURCE_RESUMED_INTENT)
    }

    /**
     * 處理 TECH_DISCOVERED Intent 裡夾帶的卡片（App 不在前景時，系統叫醒／喚回 App 的那次刷卡）。
     * 沒有夾帶卡片資料（例如一般開啟 App）就什麼都不做。source 會記在操作紀錄裡，
     * 方便事後判斷這次刷卡是冷啟動還是從背景堆疊喚回。
     */
    private fun handleTagIntent(intent: Intent?, source: String) {
        val tag = intent?.let { extractTag(it) } ?: return
        pendingSource = source
        val result = runCatching { TagReader.read(tag) }
        result.fold(onSuccess = ::onCard, onFailure = ::onReadError)
    }

    @Suppress("DEPRECATION")
    private fun extractTag(intent: Intent): Tag? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

    override fun onResume() {
        super.onResume()
        if (refreshNfcState()) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V
            runCatching { adapter?.enableReaderMode(this, readerCallback, flags, null) }
                .onFailure { nfcStatus.text = "無法啟動讀卡模式：${it.message}" }
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { adapter?.disableReaderMode(this) }
    }

    // ---------------------------------------------------------------- 刷卡處理

    private fun onCard(card: CardSnapshot) {
        // 立刻捕捉這次是哪條路徑進來的，避免之後非同步的動作結果（runBinding 的回呼）
        // 讀到被下一次刷卡覆蓋過的 pendingSource。
        val source = pendingSource
        current = card
        currentFindings = CardAnalyzer.assess(card)
        val binding = store.get(card.uidHex)
        val registered = binding != null
        val st = stability.record(card.uidHex)
        currentStability = StabilityTracker.message(st)

        when (policy.decide(card.uidHex, registered, SystemClock.elapsedRealtime())) {
            AccessPolicy.Decision.ALLOW -> {
                setBanner("▶ 已登錄的卡，執行動作…\n${card.uidHex}", GREEN)
                runBinding(binding!!, who = card.uidHex, source = source)
            }
            AccessPolicy.Decision.DENY_COOLDOWN -> {
                setBanner("⏳ 冷卻中，沒有重複執行\n${card.uidHex}", GRAY)
                logEvent(card.uidHex, "已登錄，但在 ${COOLDOWN_MS / 1000} 秒冷卻時間內，未執行", source)
            }
            AccessPolicy.Decision.DENY_UNREGISTERED -> {
                setBanner("❌ 未登錄的卡，已拒絕\n${card.uidHex}", ORANGE)
                logEvent(card.uidHex, "未登錄，已拒絕", source)
            }
        }
        detail.text = CardAnalyzer.describe(card)
        findingsView.text = currentFindings.joinToString("\n\n") { "${icon(it.level)} ${it.text}" }
        stabilityView.text = "穩定性測試（共 ${st.scans} 次刷卡）\n$currentStability"

        refreshCurrentCardUi()
        copyBtn.isEnabled = true
    }

    /** 依目前這張卡有沒有登錄，更新動作說明與按鈕狀態。 */
    private fun refreshCurrentCardUi() {
        val card = current
        val binding = card?.let { store.get(it.uidHex) }
        actionView.text = when {
            card == null -> "刷一張卡後，這裡會顯示它的動作。"
            binding == null -> "這張卡的動作：尚未登錄"
            else -> "這張卡的動作：${binding.label.ifEmpty { "（未命名）" }}\n→ ${binding.action.summary()}"
        }
        registerBtn.isEnabled = card != null && binding == null && CardAnalyzer.isUidCandidate(card)
        unregisterBtn.isEnabled = binding != null
        editActionBtn.isEnabled = binding != null
        testActionBtn.isEnabled = binding != null
    }

    /** 執行一張卡綁定的動作（結果在背景回呼，統一切回主執行緒處理）。 */
    private fun runBinding(binding: CardBinding, who: String, source: String) {
        val ctx = ActionContext(binding.uidHex, binding.label, isoNow())
        ActuatorFactory.create(binding.action).execute(ctx) { result ->
            runOnUiThread {
                result.fold(
                    onSuccess = {
                        setBanner("✅ $it\n${binding.uidHex}", GREEN)
                        logEvent(who, "${binding.action.summary()} → $it", source)
                    },
                    onFailure = {
                        val msg = it.message ?: it.javaClass.simpleName
                        setBanner("⚠️ 動作失敗：$msg\n${binding.uidHex}", RED)
                        logEvent(who, "${binding.action.summary()} → 失敗：$msg", source)
                    },
                )
            }
        }
    }

    private fun onReadError(e: Throwable) {
        val msg = e.message ?: e.javaClass.simpleName
        setBanner("⚠️ 讀取失敗\n$msg", RED)
        logEvent("（讀取失敗）", "讀取失敗：$msg", pendingSource)
    }

    private fun showIdle() {
        setBanner("把卡片貼近手機背面\n（NFC 天線位置依機型而異）", GRAY)
        detail.text = "尚未刷卡"
        findingsView.text = ""
        stabilityView.text = ""
        refreshCurrentCardUi()
        copyBtn.isEnabled = false
    }

    private fun refreshNfcState(): Boolean {
        adapter = NfcAdapter.getDefaultAdapter(this)
        val a = adapter
        return when {
            a == null -> {
                nfcStatus.text = "這支手機沒有 NFC，無法使用。"
                nfcSettingsBtn.visibility = View.GONE
                false
            }
            !a.isEnabled -> {
                nfcStatus.text = "NFC 目前是關閉的，請先開啟。"
                nfcSettingsBtn.visibility = View.VISIBLE
                false
            }
            else -> {
                nfcStatus.text = "NFC 已開啟，等待刷卡。"
                nfcSettingsBtn.visibility = View.GONE
                true
            }
        }
    }

    // ---------------------------------------------------------------- 按鈕動作

    private fun registerCurrent() {
        val c = current ?: return
        store.put(CardBinding(c.uidHex, "", ActionConfig.DryRun))
        refreshRegistered()
        refreshCurrentCardUi()
        setBanner("📋 已登錄\n${c.uidHex}", GRAY)
        showActionDialog(c.uidHex)
    }

    private fun unregisterCurrent() {
        val c = current ?: return
        store.remove(c.uidHex)
        refreshRegistered()
        refreshCurrentCardUi()
        setBanner("❌ 未登錄的卡\n${c.uidHex}", ORANGE)
        toast("已取消登錄")
    }

    private fun testCurrentAction() {
        val binding = current?.let { store.get(it.uidHex) }
        if (binding == null) {
            toast("請先刷一張已登錄的卡")
            return
        }
        setBanner("▶ 手動測試動作…\n${binding.uidHex}", GRAY)
        runBinding(binding, who = "手動 ${binding.uidHex}", source = SOURCE_MANUAL)
    }

    private fun copyReport() {
        val c = current ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NFC 讀卡結果", buildReport(c)))
        toast("已複製，可以貼給 Claude 或存到筆記")
    }

    /** 複製完整操作紀錄（不只目前這張卡），貼上即可直接丟給 Claude。 */
    private fun copyLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NFC 讀卡驗證操作紀錄", fullLogText()))
        toast("已複製全部紀錄，可以貼給 Claude 或存到筆記")
    }

    /** 用系統的分享選單匯出完整操作紀錄，可以傳到 Email、雲端硬碟、訊息等任何支援純文字的 App。 */
    private fun exportLog() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NFC 讀卡驗證操作紀錄")
            putExtra(Intent.EXTRA_TEXT, fullLogText())
        }
        startActivity(Intent.createChooser(shareIntent, "匯出操作紀錄"))
    }

    /** 完整操作紀錄，含手機與 App 版本等脈絡，方便事後判斷問題。不含任何 POST 的網址、標頭或內容。 */
    private fun fullLogText(): String = buildString {
        appendLine("NFC 讀卡驗證 操作紀錄匯出")
        appendLine("App 版本：v${versionName()}")
        appendLine("手機：${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        appendLine("已登錄的卡：${store.all().size} 張")
        appendLine("匯出時間：${isoNow()}")
        appendLine()
        val lines = opLog.all()
        appendLine(if (lines.isEmpty()) "操作紀錄：無" else "操作紀錄（最近 ${lines.size} 筆，由新到舊）")
        lines.forEach { appendLine(it) }
    }.trimEnd()

    private fun resetTest() {
        stability.reset()
        opLog.clear()
        renderLog()
        stabilityView.text = ""
        toast("已重設穩定性測試與操作紀錄（已登錄的卡不受影響）")
    }

    private fun buildReport(c: CardSnapshot): String = buildString {
        val binding = store.get(c.uidHex)
        appendLine("NFC 讀卡驗證 v${versionName()}")
        appendLine("手機：${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        appendLine("已登錄：${if (binding != null) "是" else "否"}")
        if (binding != null) appendLine("動作：${binding.action.summary()}，冷卻 ${COOLDOWN_MS / 1000} 秒")
        appendLine()
        appendLine(CardAnalyzer.describe(c))
        appendLine()
        appendLine("判斷：")
        currentFindings.forEach { appendLine("${icon(it.level)} ${it.text}") }
        appendLine()
        appendLine("穩定性：$currentStability")
    }.trimEnd()

    @Suppress("DEPRECATION")
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    // ---------------------------------------------------------------- 設定動作對話框

    private fun showActionDialog(uidHex: String) {
        val existing = store.get(uidHex) ?: return
        val post = existing.action as? ActionConfig.HttpPost

        val labelEdit = edit("卡片名稱（選填，例如：大門卡）", existing.label)
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("模擬（不控制任何裝置）", "HTTP POST"),
            )
            setSelection(if (post != null) 1 else 0)
        }
        val urlEdit = edit("網址（https://…）", post?.url ?: "", uri = true)
        val contentTypeEdit = edit("Content-Type", post?.contentType ?: "application/json")
        val headersEdit = edit("標頭（每行一個，格式：名稱: 值）", post?.headersText ?: "", multiline = true)
        val bodyEdit = edit("內容", post?.body ?: DEFAULT_BODY, multiline = true)
        val hint = text(
            "內容可以使用 {uid}（卡片 UID）、{label}（卡片名稱）、{time}（刷卡時間）。\n" +
                "https 可以連任何主機；http 只能連區域網路位址（例如 192.168.x.x）。\n" +
                "網址與標頭可能含密鑰，只會存在這支手機的 App 私有空間，不會顯示在紀錄裡。",
            12f,
        )
        val postGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(urlEdit)
            addView(contentTypeEdit)
            addView(headersEdit)
            addView(bodyEdit)
            addView(hint, matchWidth(top = 8))
        }

        fun syncVisibility() {
            postGroup.visibility = if (typeSpinner.selectedItemPosition == 1) View.VISIBLE else View.GONE
        }
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = syncVisibility()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        syncVisibility()

        val pad = dp(16)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(text("UID：$uidHex", 13f, mono = true))
            addView(labelEdit, matchWidth(top = 8))
            addView(text("動作種類", 13f), matchWidth(top = 12))
            addView(typeSpinner)
            addView(postGroup, matchWidth(top = 4))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("設定這張卡的動作")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("儲存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            // 自己處理按鈕，這樣欄位有問題時對話框不會被關掉。
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = labelEdit.text.toString().trim()
                val action: ActionConfig = if (typeSpinner.selectedItemPosition == 1) {
                    val cfg = ActionConfig.HttpPost(
                        url = urlEdit.text.toString().trim(),
                        body = bodyEdit.text.toString(),
                        contentType = contentTypeEdit.text.toString().trim().ifEmpty { "application/json" },
                        headersText = headersEdit.text.toString(),
                    )
                    val problem = cfg.validate()
                    if (problem != null) {
                        toast(problem)
                        return@setOnClickListener
                    }
                    cfg
                } else {
                    ActionConfig.DryRun
                }
                store.put(CardBinding(uidHex, label, action))
                refreshRegistered()
                refreshCurrentCardUi()
                logEvent(uidHex, "已設定動作：${action.summary()}", SOURCE_SETTING)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- 畫面

    private fun refreshRegistered() {
        val all = store.all()
        registeredView.text = if (all.isEmpty()) {
            "已登錄的卡：無"
        } else {
            "已登錄的卡（${all.size} 張）\n" + all.joinToString("\n") {
                "• ${it.label.ifEmpty { "（未命名）" }}\n  ${it.uidHex}\n  → ${it.action.summary()}"
            }
        }
    }

    private fun logEvent(who: String, text: String, source: String) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        opLog.add("$time [$source] $who  $text")
        renderLog()
    }

    private fun renderLog() {
        val lines = opLog.all().take(MAX_LOG_SHOWN)
        logView.text = if (lines.isEmpty()) {
            "操作紀錄：無"
        } else {
            "操作紀錄（最近 ${lines.size} 筆）\n" + lines.joinToString("\n")
        }
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    private fun setBanner(text: String, color: Int) {
        banner.text = text
        (banner.background as GradientDrawable).setColor(color)
    }

    private fun icon(level: CardAnalyzer.Level): String = when (level) {
        CardAnalyzer.Level.OK -> "✅"
        CardAnalyzer.Level.WARN -> "⚠️"
        CardAnalyzer.Level.INFO -> "ℹ️"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        column.addView(text("NFC 讀卡驗證", 22f, bold = true))
        nfcStatus = text("", 14f).also { column.addView(it) }
        nfcSettingsBtn = button("開啟 NFC 設定") {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        }.also {
            it.visibility = View.GONE
            column.addView(it)
        }

        banner = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(28), dp(16), dp(28))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat() }
        }
        column.addView(banner, matchWidth(top = 12))

        detail = text("", 14f, mono = true).also { column.addView(it, matchWidth(top = 12)) }
        findingsView = text("", 14f).also { column.addView(it, matchWidth(top = 12)) }
        stabilityView = text("", 14f).also { column.addView(it, matchWidth(top = 12)) }

        actionView = text("", 14f).also { column.addView(it, matchWidth(top = 16)) }

        registerBtn = button("登錄這張卡") { registerCurrent() }
        unregisterBtn = button("取消登錄") { unregisterCurrent() }
        column.addView(row(registerBtn, unregisterBtn), matchWidth(top = 8))

        editActionBtn = button("設定動作") { current?.let { showActionDialog(it.uidHex) } }
        testActionBtn = button("手動測試動作") { testCurrentAction() }
        column.addView(row(editActionBtn, testActionBtn), matchWidth(top = 4))

        copyBtn = button("複製結果") { copyReport() }
        resetBtn = button("重設測試") { resetTest() }
        column.addView(row(copyBtn, resetBtn), matchWidth(top = 4))

        val copyLogBtn = button("複製紀錄") { copyLog() }
        val exportLogBtn = button("匯出紀錄") { exportLog() }
        column.addView(row(copyLogBtn, exportLogBtn), matchWidth(top = 4))

        registeredView = text("", 14f, mono = true).also { column.addView(it, matchWidth(top = 16)) }
        logView = text("", 13f, mono = true).also { column.addView(it, matchWidth(top = 16)) }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(column)
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val (l, t, r, b) = systemBarInsets(insets)
            v.setPadding(dp(16) + l, dp(16) + t, dp(16) + r, dp(16) + b)
            insets
        }
        return scroll
    }

    @Suppress("DEPRECATION")
    private fun systemBarInsets(insets: WindowInsets): List<Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars())
            listOf(i.left, i.top, i.right, i.bottom)
        } else {
            listOf(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
        }

    private fun text(s: String, size: Float, bold: Boolean = false, mono: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.parseColor("#212121"))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (mono) typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

    private fun edit(hint: String, value: String, multiline: Boolean = false, uri: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 14f
            inputType = when {
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (multiline) {
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
            }
        }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun row(a: View, b: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun matchWidth(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private companion object {
        /** 已登錄的卡成功執行動作後，同一張卡在這段時間內不重複執行。 */
        const val COOLDOWN_MS = 5_000L
        const val MAX_LOG_SHOWN = 20
        const val DEFAULT_BODY = """{"uid":"{uid}","time":"{time}"}"""

        // 每筆操作紀錄標註的來源，用來判斷「App 不在前景時刷卡」的修正是否生效。
        const val SOURCE_FOREGROUND = "前景讀卡"
        const val SOURCE_COLD_START = "冷啟動"
        const val SOURCE_RESUMED_INTENT = "背景喚回"
        const val SOURCE_MANUAL = "手動測試"
        const val SOURCE_SETTING = "設定"

        val GREEN = Color.parseColor("#2E7D32")
        val ORANGE = Color.parseColor("#EF6C00")
        val RED = Color.parseColor("#C62828")
        val GRAY = Color.parseColor("#757575")
    }
}
