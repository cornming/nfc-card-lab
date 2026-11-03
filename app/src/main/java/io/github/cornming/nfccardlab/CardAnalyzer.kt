package io.github.cornming.nfccardlab

/** MIFARE Classic 的識別資訊（只讀取 Android 提供的欄位，不做任何金鑰驗證）。 */
data class ClassicInfo(val typeName: String, val sizeBytes: Int, val sectors: Int)

/**
 * 一次刷卡讀到的資料快照。
 * 純資料、不依賴 Android 類別，方便在 JVM 上做單元測試。
 */
data class CardSnapshot(
    val uid: ByteArray,
    val techs: List<String>,
    val atqa: ByteArray?,
    val sak: Int?,
    val classic: ClassicInfo?,
    val isoDepHistorical: ByteArray?,
    val isoDepHiLayer: ByteArray?,
    val ndefType: String?,
    val ndefMaxSize: Int?,
) {
    val uidHex: String get() = Hex.format(uid)
}

object Hex {
    fun format(bytes: ByteArray, separator: String = ":"): String =
        bytes.joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }
}

object CardAnalyzer {

    enum class Level { OK, WARN, INFO }

    data class Finding(val level: Level, val text: String)

    /** eWeLink 官方說明對 NFC 標籤的容量要求：超過 200 bytes。 */
    const val EWELINK_MIN_NDEF_BYTES = 200

    /**
     * 依 SAK 對照卡片家族（NXP 的標準對照，僅供參考，不是保證）。
     * 只列出常見且明確的幾種，其餘回傳 null。
     */
    fun sakHint(sak: Int): String? = when (sak and 0xFF) {
        0x00 -> "MIFARE Ultralight / NTAG 系列"
        0x08 -> "MIFARE Classic 1K"
        0x09 -> "MIFARE Mini"
        0x18 -> "MIFARE Classic 4K"
        0x20 -> "支援 ISO 14443-4（例如 DESFire、Plus、部分金融卡）"
        else -> null
    }

    /** 4 bytes UID 且第一個 byte 為 0x08：依 ISO/IEC 14443-3 代表隨機 UID。 */
    fun looksLikeRandomUid(uid: ByteArray): Boolean =
        uid.size == 4 && (uid[0].toInt() and 0xFF) == 0x08

    fun describe(c: CardSnapshot): String = buildString {
        appendLine("UID：${c.uidHex}（${c.uid.size} bytes）")
        appendLine("技術：${c.techs.joinToString(", ").ifEmpty { "（無）" }}")
        if (c.atqa != null || c.sak != null) {
            val atqa = c.atqa?.let { Hex.format(it) } ?: "—"
            val sak = c.sak?.let { "0x%02X".format(it and 0xFF) } ?: "—"
            appendLine("ATQA：$atqa　SAK：$sak")
            c.sak?.let { s -> sakHint(s)?.let { appendLine("SAK 對照：$it（僅供參考）") } }
        }
        c.classic?.let {
            appendLine("MIFARE Classic：${it.typeName}，${it.sizeBytes} bytes，${it.sectors} 個磁區（只識別型號，未驗證金鑰）")
        }
        c.isoDepHistorical?.takeIf { it.isNotEmpty() }?.let {
            appendLine("ISO-DEP 歷史位元組：${Hex.format(it)}")
        }
        c.isoDepHiLayer?.takeIf { it.isNotEmpty() }?.let {
            appendLine("ISO-DEP 高層回應：${Hex.format(it)}")
        }
        if (c.ndefMaxSize != null) {
            appendLine("NDEF：${c.ndefType ?: "未知類型"}，容量 ${c.ndefMaxSize} bytes")
        }
    }.trimEnd()

    fun assess(c: CardSnapshot): List<Finding> {
        val out = mutableListOf<Finding>()

        if (c.uid.isEmpty()) {
            out += Finding(Level.WARN, "沒有讀到 UID，無法用來辨識這張卡。")
            return out
        }
        out += Finding(Level.OK, "讀到 UID（${c.uid.size} bytes）。能讀到代表這張卡是手機讀得到的 13.56 MHz 類型。")

        if (c.uid.all { it.toInt() == 0 }) {
            out += Finding(Level.WARN, "UID 全為 0，不能用來辨識。")
        }
        if (looksLikeRandomUid(c.uid)) {
            out += Finding(
                Level.WARN,
                "UID 第一個 byte 是 08，依 ISO/IEC 14443-3 通常代表「隨機 UID」，每次刷可能都不同。請用下方的穩定性測試確認。"
            )
        }
        if (c.uid.size !in setOf(4, 7, 10)) {
            out += Finding(Level.WARN, "UID 長度 ${c.uid.size} bytes 不是常見的 4、7、10 bytes。")
        }

        val sakText = c.sak?.let { sakHint(it) }
        if (sakText != null && sakText.startsWith("MIFARE Classic") && c.classic == null) {
            out += Finding(
                Level.INFO,
                "SAK 看起來像 MIFARE Classic，但這支手機沒有提供 MifareClassic 技術（可能是 NFC 晶片不支援）。你的手機仍然讀到了 UID，所以只用 UID 辨識不受影響。"
            )
        }

        if (c.ndefMaxSize != null) {
            if (c.ndefMaxSize > EWELINK_MIN_NDEF_BYTES) {
                out += Finding(
                    Level.OK,
                    "NDEF 容量 ${c.ndefMaxSize} bytes，超過 eWeLink 官方說明的 ${EWELINK_MIN_NDEF_BYTES} bytes 門檻（能否實際寫入仍需在 eWeLink 實測）。"
                )
            } else {
                out += Finding(
                    Level.WARN,
                    "NDEF 容量 ${c.ndefMaxSize} bytes，沒有超過 eWeLink 官方說明的 ${EWELINK_MIN_NDEF_BYTES} bytes 門檻。"
                )
            }
        } else if ("NdefFormatable" in c.techs) {
            out += Finding(Level.INFO, "卡片可格式化為 NDEF（NdefFormatable），容量要寫入後才知道。")
        } else {
            out += Finding(
                Level.INFO,
                "沒有偵測到 NDEF。eWeLink 內建 NFC 能否使用這張卡，要在 eWeLink 實測才知道。"
            )
        }
        return out
    }

    /** 這張卡能不能當「UID 白名單」的候選：有 UID、不是全 0、看起來不是隨機 UID。 */
    fun isUidCandidate(c: CardSnapshot): Boolean =
        c.uid.isNotEmpty() && !c.uid.all { it.toInt() == 0 } && !looksLikeRandomUid(c.uid)
}

/** 追蹤連續刷卡的 UID 是否穩定。 */
class StabilityTracker {

    data class Status(val scans: Int, val streak: Int, val changes: Int, val sameAsPrevious: Boolean?)

    private var last: String? = null
    private var scans = 0
    private var streak = 0
    private var changes = 0

    fun record(uidHex: String): Status {
        scans++
        val same: Boolean? = last?.let { it == uidHex }
        when (same) {
            null -> streak = 1
            true -> streak++
            false -> {
                streak = 1
                changes++
            }
        }
        last = uidHex
        return Status(scans, streak, changes, same)
    }

    fun reset() {
        last = null
        scans = 0
        streak = 0
        changes = 0
    }

    companion object {
        fun message(s: StabilityTracker.Status): String = when (s.sameAsPrevious) {
            null -> "第 1 次刷卡。再刷同一張卡幾次，確認 UID 每次都一樣。"
            true -> "與上一次相同，已連續 ${s.streak} 次一致。"
            false -> "與上一次不同（累計 ${s.changes} 次變化）。如果你刷的是同一張卡，代表它的 UID 不固定，不能用來辨識；如果換了另一張卡，這是正常的。"
        }
    }
}
