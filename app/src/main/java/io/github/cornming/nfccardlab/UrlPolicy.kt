package io.github.cornming.nfccardlab

import java.net.URI

/**
 * POST 動作可以連到哪裡的規則。
 * - https：可以連任何主機。
 * - http：只允許區域網路位址（10.x、172.16–31.x、192.168.x、127.x、169.254.x）、localhost、*.local，
 *   避免明碼資料走到網際網路。
 * - 網址不能含帳號密碼（請改用標頭），也不支援其他協定。
 */
object UrlPolicy {

    sealed class Check {
        object Ok : Check()
        data class Rejected(val reason: String) : Check()
    }

    fun check(raw: String): Check {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            return Check.Rejected("網址格式不正確")
        }
        val scheme = uri.scheme?.lowercase() ?: return Check.Rejected("網址要以 https:// 開頭")
        val host = uri.host ?: return Check.Rejected("找不到主機名稱")
        if (uri.userInfo != null) return Check.Rejected("網址不能包含帳號密碼，請改用標頭")
        return when (scheme) {
            "https" -> Check.Ok
            "http" ->
                if (isLocalHost(host)) Check.Ok
                else Check.Rejected("http 只允許區域網路位址（例如 192.168.x.x），其他請用 https")
            else -> Check.Rejected("只支援 http 與 https")
        }
    }

    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local")) return true
        val m = IPV4.matchEntire(h) ?: return false
        val p = m.groupValues.drop(1).map { it.toInt() }
        if (p.any { it !in 0..255 }) return false
        return p[0] == 10 ||
            p[0] == 127 ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254)
    }

    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
}
