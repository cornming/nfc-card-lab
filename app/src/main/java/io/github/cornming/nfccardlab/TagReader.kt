package io.github.cornming.nfccardlab

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA

/** 把 Android 的 [Tag] 轉成純資料的 [CardSnapshot]。這裡只讀取探測階段就有的資料，不對卡片發送指令。 */
object TagReader {

    fun read(tag: Tag): CardSnapshot {
        val nfcA = NfcA.get(tag)
        val classic = MifareClassic.get(tag)?.let {
            ClassicInfo(classicTypeName(it.type), it.size, it.sectorCount)
        }
        val isoDep = IsoDep.get(tag)
        val ndef = Ndef.get(tag)

        return CardSnapshot(
            uid = tag.id ?: ByteArray(0),
            techs = tag.techList.map { it.substringAfterLast('.') },
            atqa = nfcA?.atqa,
            sak = nfcA?.sak?.toInt()?.and(0xFF),
            classic = classic,
            isoDepHistorical = isoDep?.historicalBytes,
            isoDepHiLayer = isoDep?.hiLayerResponse,
            ndefType = ndef?.type,
            ndefMaxSize = ndef?.maxSize,
        )
    }

    private fun classicTypeName(type: Int): String = when (type) {
        MifareClassic.TYPE_CLASSIC -> "Classic"
        MifareClassic.TYPE_PLUS -> "Plus"
        MifareClassic.TYPE_PRO -> "Pro"
        else -> "未知"
    }
}
