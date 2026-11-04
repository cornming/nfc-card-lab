package io.github.cornming.nfccardlab

import android.content.Context

/**
 * 已登錄的卡與各自的動作設定。只存在這支手機的 App 私有儲存空間（且已關閉備份），不會上傳。
 * 注意：POST 動作的網址、標頭可能含 token，以明碼存在 App 私有空間；UID 也可以被複製，
 * 白名單只是辨識用，不是安全機制。
 */
class CardStore(context: Context) {

    private val prefs = context.getSharedPreferences("cards", Context.MODE_PRIVATE)

    init {
        migrateLegacy()
    }

    fun all(): List<CardBinding> =
        BindingCodec.decode(prefs.getString(KEY_BINDINGS, null))
            .sortedWith(compareBy({ it.label.lowercase() }, { it.uidHex }))

    fun get(uidHex: String): CardBinding? = all().firstOrNull { it.uidHex == uidHex }

    fun contains(uidHex: String): Boolean = get(uidHex) != null

    fun put(binding: CardBinding) {
        save(all().filterNot { it.uidHex == binding.uidHex } + binding)
    }

    fun remove(uidHex: String) {
        save(all().filterNot { it.uidHex == uidHex })
    }

    private fun save(list: List<CardBinding>) {
        prefs.edit().putString(KEY_BINDINGS, BindingCodec.encode(list)).apply()
    }

    /** 0.2.0 以前只存 UID 集合。第一次開啟新版時，把它們轉成「模擬」動作，白名單不會消失。 */
    private fun migrateLegacy() {
        if (prefs.contains(KEY_BINDINGS)) return
        val legacy = prefs.getStringSet(KEY_LEGACY_UIDS, null) ?: return
        save(legacy.sorted().map { CardBinding(it, "", ActionConfig.DryRun) })
        prefs.edit().remove(KEY_LEGACY_UIDS).apply()
    }

    private companion object {
        const val KEY_BINDINGS = "bindings"
        const val KEY_LEGACY_UIDS = "uids"
    }
}
