package io.github.cornming.nfccardlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TemplateTest {

    private val ctx = ActionContext(
        uidHex = "F1:7A:70:5C",
        label = "大門卡",
        timeIso = "2026-11-04T10:00:00+08:00",
    )

    @Test
    fun placeholdersAreReplaced() {
        val out = Template.render("""{"uid":"{uid}","who":"{label}","at":"{time}"}""", ctx, json = true)
        assertEquals("""{"uid":"F1:7A:70:5C","who":"大門卡","at":"2026-11-04T10:00:00+08:00"}""", out)
    }

    @Test
    fun unknownPlaceholdersAreLeftAlone() {
        assertEquals("{foo} F1:7A:70:5C", Template.render("{foo} {uid}", ctx, json = false))
    }

    @Test
    fun jsonValuesAreEscaped() {
        val tricky = ctx.copy(label = "a\"b\\c\nd")
        assertEquals("""{"who":"a\"b\\c\nd"}""", Template.render("""{"who":"{label}"}""", tricky, json = true))
    }

    @Test
    fun nonJsonValuesAreNotEscaped() {
        val tricky = ctx.copy(label = "a\"b")
        assertEquals("""who=a"b""", Template.render("who={label}", tricky, json = false))
    }

    @Test
    fun replacementIsSinglePass() {
        // 名稱裡的 {time} 不能被再次展開
        val tricky = ctx.copy(label = "{time}")
        assertEquals("L={time}", Template.render("L={label}", tricky, json = false))
    }

    @Test
    fun controlCharactersAreEscapedInJson() {
        assertEquals("a\\u0001b", Template.jsonEscape("a\u0001b"))
    }

    @Test
    fun headerTextParsesLinesAndIgnoresBlanks() {
        val headers = HeaderText.parse("Authorization: Bearer abc\n\n  X-Token : 123  \n")
        assertEquals(listOf("Authorization" to "Bearer abc", "X-Token" to "123"), headers)
    }

    @Test
    fun headerValueMayContainColons() {
        assertEquals(listOf("X-Time" to "10:00:00"), HeaderText.parse("X-Time: 10:00:00"))
    }

    @Test
    fun headerTextRejectsBadLines() {
        assertThrows(IllegalArgumentException::class.java) { HeaderText.parse("no colon here") }
        assertThrows(IllegalArgumentException::class.java) { HeaderText.parse(": value") }
        assertThrows(IllegalArgumentException::class.java) { HeaderText.parse("Bad Name: v") }
    }

    @Test
    fun emptyHeaderTextIsValid() {
        assertEquals(emptyList<Pair<String, String>>(), HeaderText.parse(""))
    }
}
