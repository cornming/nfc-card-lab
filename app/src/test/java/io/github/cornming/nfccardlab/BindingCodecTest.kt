package io.github.cornming.nfccardlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BindingCodecTest {

    private val post = ActionConfig.HttpPost(
        url = "https://example.com/hook?token=secret",
        body = """{"uid":"{uid}"}""",
        contentType = "application/json",
        headersText = "Authorization: Bearer abc\nX-Id: 1",
    )

    @Test
    fun roundTripKeepsAllFields() {
        val list = listOf(
            CardBinding("AA:BB:CC:DD", "大門卡", post),
            CardBinding("11:22:33:44", "", ActionConfig.DryRun),
        )
        assertEquals(list, BindingCodec.decode(BindingCodec.encode(list)))
    }

    @Test
    fun emptyAndNullDecodeToEmptyList() {
        assertEquals(emptyList<CardBinding>(), BindingCodec.decode(null))
        assertEquals(emptyList<CardBinding>(), BindingCodec.decode(""))
        assertEquals(emptyList<CardBinding>(), BindingCodec.decode(BindingCodec.encode(emptyList())))
    }

    @Test
    fun corruptedTextDecodesToEmptyListInsteadOfCrashing() {
        assertEquals(emptyList<CardBinding>(), BindingCodec.decode("{not json"))
        assertEquals(emptyList<CardBinding>(), BindingCodec.decode("""{"cards": 5}"""))
    }

    @Test
    fun entriesWithoutUidAreSkippedAndUnknownTypeFallsBackToDryRun() {
        val text = """{"version":1,"cards":[{"label":"x"},{"uid":"AA","type":"future_type"}]}"""
        assertEquals(listOf(CardBinding("AA", "", ActionConfig.DryRun)), BindingCodec.decode(text))
    }

    @Test
    fun specialCharactersSurviveRoundTrip() {
        val b = CardBinding("AA", "名稱 \"含\" 引號\n換行", post.copy(body = "line1\nline2 \\ \""))
        assertEquals(listOf(b), BindingCodec.decode(BindingCodec.encode(listOf(b))))
    }

    @Test
    fun summaryNeverExposesPathQueryOrHeaders() {
        val s = post.summary()
        assertEquals("HTTP POST → https://example.com", s)
        assertFalse(s.contains("secret"))
        assertFalse(s.contains("Bearer"))
    }

    @Test
    fun summaryOfInvalidUrlIsSafe() {
        assertTrue(post.copy(url = "garbage").summary().contains("網址無效"))
    }

    @Test
    fun validateFindsProblems() {
        assertEquals(null, post.validate())
        assertTrue(post.copy(url = "http://example.com/x").validate() != null)
        assertTrue(post.copy(contentType = " ").validate() != null)
        assertTrue(post.copy(headersText = "bad header").validate() != null)
    }
}
