package io.github.cornming.nfccardlab

import android.content.Context

/**
 * 已登錄卡片（UID 白名單）。只存在這支手機的 App 私有儲存空間，不會上傳。
 * 注意：UID 可以被複製，白名單只是辨識用，不是安全機制。
 */
class CardStore(context: Context) {

    private val prefs = context.getSharedPreferences("cards", Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun contains(uidHex: String): Boolean = uidHex in all()

    fun add(uidHex: String) {
        prefs.edit().putStringSet(KEY, all() + uidHex).apply()
    }

    fun remove(uidHex: String) {
        prefs.edit().putStringSet(KEY, all() - uidHex).apply()
    }

    private companion object {
        const val KEY = "uids"
    }
}
