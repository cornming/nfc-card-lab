package io.github.cornming.nfccardlab

import java.net.URI

/** 刷卡後要執行的動作設定。每張已登錄的卡各自綁一個。 */
sealed class ActionConfig {

    /** 給列表與紀錄看的一行摘要。不能含密鑰（例如網址裡的 token、標頭）。 */
    abstract fun summary(): String

    /** 模擬：不會控制任何裝置，只用來測試流程。 */
    object DryRun : ActionConfig() {
        override fun summary(): String = "模擬（不控制任何裝置）"
    }

    /** 對指定網址送出 HTTP POST。 */
    data class HttpPost(
        val url: String,
        val body: String,
        val contentType: String,
        /** 每行一個「名稱: 值」。 */
        val headersText: String,
    ) : ActionConfig() {

        /** 只顯示協定與主機，避免把網址路徑或查詢字串裡的密鑰顯示出來。 */
        override fun summary(): String {
            val host = try {
                URI(url.trim()).let { u -> if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else null }
            } catch (_: Exception) {
                null
            }
            return "HTTP POST → ${host ?: "（網址無效）"}"
        }

        /** 回傳第一個發現的問題；沒有問題回傳 null。 */
        fun validate(): String? {
            (UrlPolicy.check(url) as? UrlPolicy.Check.Rejected)?.let { return it.reason }
            if (contentType.isBlank()) return "Content-Type 不能是空的"
            return try {
                HeaderText.parse(headersText)
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }
    }
}

/** 一張已登錄的卡：UID、名稱、動作。 */
data class CardBinding(
    val uidHex: String,
    val label: String,
    val action: ActionConfig,
)
