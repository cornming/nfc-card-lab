package io.github.cornming.nfccardlab

import org.json.JSONArray
import org.json.JSONObject

/** 把卡片與動作的綁定存成 JSON 字串（存在 App 私有儲存空間）。讀取失敗時回傳空清單，不會讓 App 當掉。 */
object BindingCodec {

    fun encode(list: List<CardBinding>): String {
        val cards = JSONArray()
        for (b in list) {
            val o = JSONObject()
            o.put("uid", b.uidHex)
            o.put("label", b.label)
            when (val a = b.action) {
                is ActionConfig.DryRun -> o.put("type", "dryrun")
                is ActionConfig.HttpPost -> {
                    o.put("type", "http_post")
                    o.put("url", a.url)
                    o.put("body", a.body)
                    o.put("contentType", a.contentType)
                    o.put("headers", a.headersText)
                }
            }
            cards.put(o)
        }
        return JSONObject().put("version", 1).put("cards", cards).toString()
    }

    fun decode(text: String?): List<CardBinding> {
        if (text.isNullOrEmpty()) return emptyList()
        return try {
            val cards = JSONObject(text).getJSONArray("cards")
            (0 until cards.length()).mapNotNull { decodeOne(cards.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeOne(o: JSONObject): CardBinding? {
        val uid = o.optString("uid", "")
        if (uid.isEmpty()) return null
        val action: ActionConfig = when (o.optString("type", "")) {
            "http_post" -> ActionConfig.HttpPost(
                url = o.optString("url", ""),
                body = o.optString("body", ""),
                contentType = o.optString("contentType", "application/json"),
                headersText = o.optString("headers", ""),
            )
            else -> ActionConfig.DryRun
        }
        return CardBinding(uid, o.optString("label", ""), action)
    }
}
