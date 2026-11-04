package io.github.cornming.nfccardlab

/**
 * 刷卡後要不要執行動作的規則。純邏輯，不依賴 Android，方便單元測試。
 *
 * 目前的規則：
 * 1. 未登錄的卡一律拒絕。
 * 2. 已登錄的卡，在**同一張卡**上一次「成功執行」後的 [cooldownMs] 毫秒內不再執行（避免重複觸發）。
 *    不同的卡各自計算冷卻，被拒絕的刷卡也不會延長冷卻時間。
 */
class AccessPolicy(private val cooldownMs: Long) {

    enum class Decision { ALLOW, DENY_UNREGISTERED, DENY_COOLDOWN }

    private val lastAllowedAt = HashMap<String, Long>()

    /** [nowMs] 要用單調時鐘（例如 SystemClock.elapsedRealtime），不受手機改時間影響。 */
    fun decide(uidHex: String, registered: Boolean, nowMs: Long): Decision {
        if (!registered) return Decision.DENY_UNREGISTERED
        val last = lastAllowedAt[uidHex]
        if (last != null && nowMs - last < cooldownMs) return Decision.DENY_COOLDOWN
        lastAllowedAt[uidHex] = nowMs
        return Decision.ALLOW
    }
}
