package io.github.cornming.nfccardlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardAnalyzerTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun card(
        uid: ByteArray,
        techs: List<String> = listOf("NfcA"),
        sak: Int? = null,
        classic: ClassicInfo? = null,
        ndefMaxSize: Int? = null,
    ) = CardSnapshot(
        uid = uid,
        techs = techs,
        atqa = null,
        sak = sak,
        classic = classic,
        isoDepHistorical = null,
        isoDepHiLayer = null,
        ndefType = null,
        ndefMaxSize = ndefMaxSize,
    )

    @Test
    fun hexFormatsUppercaseWithColon() {
        assertEquals("04:A1:FF:00", Hex.format(bytes(0x04, 0xA1, 0xFF, 0x00)))
        assertEquals("04A1", Hex.format(bytes(0x04, 0xA1), separator = ""))
    }

    @Test
    fun sakHintCoversKnownFamiliesOnly() {
        assertEquals("MIFARE Classic 1K", CardAnalyzer.sakHint(0x08))
        assertEquals("MIFARE Classic 4K", CardAnalyzer.sakHint(0x18))
        assertEquals("MIFARE Mini", CardAnalyzer.sakHint(0x09))
        assertEquals("MIFARE Ultralight / NTAG 系列", CardAnalyzer.sakHint(0x00))
        assertNull(CardAnalyzer.sakHint(0x77))
    }

    @Test
    fun randomUidDetectedOnlyForFourBytesStartingWith08() {
        assertTrue(CardAnalyzer.looksLikeRandomUid(bytes(0x08, 0x11, 0x22, 0x33)))
        assertFalse(CardAnalyzer.looksLikeRandomUid(bytes(0x04, 0x11, 0x22, 0x33)))
        // 7 bytes UID 即使第一個 byte 是 08 也不算
        assertFalse(CardAnalyzer.looksLikeRandomUid(bytes(0x08, 1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun randomUidIsNotACandidateAndGetsWarning() {
        val c = card(bytes(0x08, 0x11, 0x22, 0x33))
        assertFalse(CardAnalyzer.isUidCandidate(c))
        assertTrue(CardAnalyzer.assess(c).any { it.level == CardAnalyzer.Level.WARN && "隨機 UID" in it.text })
    }

    @Test
    fun normalUidIsACandidate() {
        val c = card(bytes(0x04, 0xA1, 0xB2, 0xC3, 0xD4, 0xE5, 0xF6))
        assertTrue(CardAnalyzer.isUidCandidate(c))
        assertTrue(CardAnalyzer.assess(c).none { it.level == CardAnalyzer.Level.WARN })
    }

    @Test
    fun emptyOrZeroUidIsNotACandidate() {
        assertFalse(CardAnalyzer.isUidCandidate(card(ByteArray(0))))
        assertFalse(CardAnalyzer.isUidCandidate(card(bytes(0, 0, 0, 0))))
    }

    @Test
    fun classicSakWithoutClassicTechGetsInfoNote() {
        val c = card(bytes(0x12, 0x34, 0x56, 0x78), sak = 0x08, classic = null)
        assertTrue(CardAnalyzer.assess(c).any { "沒有提供 MifareClassic" in it.text })
    }

    @Test
    fun ndefCapacityComparedWithEwelinkThreshold() {
        val big = card(bytes(1, 2, 3, 4), ndefMaxSize = 492)
        val small = card(bytes(1, 2, 3, 4), ndefMaxSize = 137)
        assertTrue(CardAnalyzer.assess(big).any { it.level == CardAnalyzer.Level.OK && "492" in it.text })
        assertTrue(CardAnalyzer.assess(small).any { it.level == CardAnalyzer.Level.WARN && "137" in it.text })
    }

    @Test
    fun describeContainsUidAndTech() {
        val c = card(bytes(0x04, 0xA1, 0xB2, 0xC3), techs = listOf("NfcA", "MifareClassic"), sak = 0x08)
        val text = CardAnalyzer.describe(c)
        assertTrue("04:A1:B2:C3" in text)
        assertTrue("NfcA, MifareClassic" in text)
        assertTrue("MIFARE Classic 1K" in text)
    }

    @Test
    fun stabilityTrackerCountsStreakAndChanges() {
        val t = StabilityTracker()
        assertNull(t.record("AA").sameAsPrevious)
        val second = t.record("AA")
        assertEquals(true, second.sameAsPrevious)
        assertEquals(2, second.streak)
        val third = t.record("BB")
        assertEquals(false, third.sameAsPrevious)
        assertEquals(1, third.streak)
        assertEquals(1, third.changes)
        assertEquals(3, third.scans)
    }

    @Test
    fun stabilityResetClearsState() {
        val t = StabilityTracker()
        t.record("AA")
        t.record("BB")
        t.reset()
        val s = t.record("AA")
        assertNull(s.sameAsPrevious)
        assertEquals(1, s.scans)
        assertEquals(0, s.changes)
    }
}
