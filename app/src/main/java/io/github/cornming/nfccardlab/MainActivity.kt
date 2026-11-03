package io.github.cornming.nfccardlab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 讀卡驗證：刷卡 → 顯示 UID 與卡片資訊 → 判斷是否適合用 UID 辨識 → 可登錄成白名單。
 * 使用 NFC reader mode，App 在前景時才會讀卡。沒有網路權限。
 */
class MainActivity : Activity() {

    private lateinit var store: CardStore
    private val stability = StabilityTracker()
    private val history = ArrayDeque<String>()

    private var adapter: NfcAdapter? = null
    private var current: CardSnapshot? = null
    private var currentFindings: List<CardAnalyzer.Finding> = emptyList()
    private var currentStability: String = ""

    private lateinit var nfcStatus: TextView
    private lateinit var nfcSettingsBtn: Button
    private lateinit var banner: TextView
    private lateinit var detail: TextView
    private lateinit var findingsView: TextView
    private lateinit var stabilityView: TextView
    private lateinit var registeredView: TextView
    private lateinit var historyView: TextView
    private lateinit var registerBtn: Button
    private lateinit var unregisterBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var resetBtn: Button

    private val readerCallback = NfcAdapter.ReaderCallback { tag ->
        // 這個回呼在背景執行緒，要先解析再切回主執行緒更新畫面。
        val result = runCatching { TagReader.read(tag) }
        runOnUiThread {
            result.fold(onSuccess = ::onCard, onFailure = ::onReadError)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CardStore(this)
        setContentView(buildUi())
        showIdle()
        refreshRegistered()
        renderHistory()
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
        current = card
        currentFindings = CardAnalyzer.assess(card)
        val registered = store.contains(card.uidHex)
        val st = stability.record(card.uidHex)
        currentStability = StabilityTracker.message(st)

        if (registered) {
            setBanner("✅ 已登錄的卡\n${card.uidHex}", GREEN)
        } else {
            setBanner("❌ 未登錄的卡\n${card.uidHex}", ORANGE)
        }
        detail.text = CardAnalyzer.describe(card)
        findingsView.text = currentFindings.joinToString("\n\n") { "${icon(it.level)} ${it.text}" }
        stabilityView.text = "穩定性測試（共 ${st.scans} 次刷卡）\n$currentStability"

        registerBtn.isEnabled = !registered && CardAnalyzer.isUidCandidate(card)
        unregisterBtn.isEnabled = registered
        copyBtn.isEnabled = true

        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        history.addFirst("$time  ${card.uidHex}  ${if (registered) "已登錄" else "未登錄"}")
        while (history.size > MAX_HISTORY) history.removeLast()
        renderHistory()
    }

    private fun onReadError(e: Throwable) {
        setBanner("⚠️ 讀取失敗\n${e.message ?: e.javaClass.simpleName}", RED)
    }

    private fun showIdle() {
        setBanner("把卡片貼近手機背面\n（NFC 天線位置依機型而異）", GRAY)
        detail.text = "尚未刷卡"
        findingsView.text = ""
        stabilityView.text = ""
        registerBtn.isEnabled = false
        unregisterBtn.isEnabled = false
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
        store.add(c.uidHex)
        refreshRegistered()
        setBanner("✅ 已登錄的卡\n${c.uidHex}", GREEN)
        registerBtn.isEnabled = false
        unregisterBtn.isEnabled = true
        toast("已登錄")
    }

    private fun unregisterCurrent() {
        val c = current ?: return
        store.remove(c.uidHex)
        refreshRegistered()
        setBanner("❌ 未登錄的卡\n${c.uidHex}", ORANGE)
        registerBtn.isEnabled = CardAnalyzer.isUidCandidate(c)
        unregisterBtn.isEnabled = false
        toast("已取消登錄")
    }

    private fun copyReport() {
        val c = current ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NFC 讀卡結果", buildReport(c)))
        toast("已複製，可以貼給 Claude 或存到筆記")
    }

    private fun resetTest() {
        stability.reset()
        history.clear()
        renderHistory()
        stabilityView.text = ""
        toast("已重設穩定性測試與刷卡紀錄（已登錄的卡不受影響）")
    }

    private fun buildReport(c: CardSnapshot): String = buildString {
        appendLine("NFC 讀卡驗證 v${versionName()}")
        appendLine("手機：${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        appendLine("已登錄：${if (store.contains(c.uidHex)) "是" else "否"}")
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

    // ---------------------------------------------------------------- 畫面

    private fun refreshRegistered() {
        val all = store.all().sorted()
        registeredView.text = if (all.isEmpty()) {
            "已登錄的卡：無"
        } else {
            "已登錄的卡（${all.size} 張）\n" + all.joinToString("\n")
        }
    }

    private fun renderHistory() {
        historyView.text = if (history.isEmpty()) "刷卡紀錄：無" else "刷卡紀錄\n" + history.joinToString("\n")
    }

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

        registerBtn = button("登錄這張卡") { registerCurrent() }
        unregisterBtn = button("取消登錄") { unregisterCurrent() }
        column.addView(row(registerBtn, unregisterBtn), matchWidth(top = 12))

        copyBtn = button("複製結果") { copyReport() }
        resetBtn = button("重設測試") { resetTest() }
        column.addView(row(copyBtn, resetBtn), matchWidth(top = 4))

        registeredView = text("", 14f, mono = true).also { column.addView(it, matchWidth(top = 16)) }
        historyView = text("", 13f, mono = true).also { column.addView(it, matchWidth(top = 16)) }

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
        const val MAX_HISTORY = 20
        val GREEN = Color.parseColor("#2E7D32")
        val ORANGE = Color.parseColor("#EF6C00")
        val RED = Color.parseColor("#C62828")
        val GRAY = Color.parseColor("#757575")
    }
}
