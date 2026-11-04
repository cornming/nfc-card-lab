package io.github.cornming.nfccardlab

/** 動作執行時的上下文，用來填入 POST 內容裡的 {uid}、{label}、{time}。 */
data class ActionContext(
    val uidHex: String,
    val label: String,
    /** ISO-8601 時間，例如 2026-11-04T10:00:00+08:00。 */
    val timeIso: String,
)

object HeaderText {

    private val NAME = Regex("^[A-Za-z0-9-]+$")

    /** 每行一個「名稱: 值」，空行忽略。格式不對會丟出 [IllegalArgumentException]，訊息可直接顯示給使用者。 */
    fun parse(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            require(idx > 0) { "標頭格式要是「名稱: 值」：$line" }
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            require(NAME.matches(name)) { "標頭名稱只能用英數字與 - ：$name" }
            out += name to value
        }
        return out
    }
}

object Template {

    private val PLACEHOLDER = Regex("""\{(uid|label|time)\}""")

    /**
     * 把 {uid}、{label}、{time} 換成實際值。
     * 一次掃描完成，所以值裡面即使含有 {time} 這類字樣也不會被再次取代。
     * [json] 為 true 時，會先把值做 JSON 跳脫，避免破壞 JSON 結構。
     */
    fun render(template: String, ctx: ActionContext, json: Boolean): String =
        PLACEHOLDER.replace(template) { m ->
            val value = when (m.groupValues[1]) {
                "uid" -> ctx.uidHex
                "label" -> ctx.label
                else -> ctx.timeIso
            }
            if (json) jsonEscape(value) else value
        }

    fun jsonEscape(s: String): String = buildString {
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}
