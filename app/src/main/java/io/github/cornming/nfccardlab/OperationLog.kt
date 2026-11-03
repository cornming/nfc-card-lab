package io.github.cornming.nfccardlab

import android.content.Context

/**
 * 操作紀錄：每次刷卡的判斷結果與動作結果。只存在這支手機的 App 私有儲存空間，最新的在最前面。
 * 每一筆是單行文字，不能含換行。
 */
class OperationLog(context: Context, private val maxEntries: Int = 50) {

    private val prefs = context.getSharedPreferences("oplog", Context.MODE_PRIVATE)

    fun add(line: String) {
        val single = line.replace('\n', ' ')
        val lines = (listOf(single) + all()).take(maxEntries)
        prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun all(): List<String> =
        (prefs.getString(KEY, "") ?: "").split("\n").filter { it.isNotEmpty() }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "lines"
    }
}
