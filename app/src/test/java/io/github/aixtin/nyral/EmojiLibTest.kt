package io.github.aixtin.nyral

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiLibTest {

    private val known = setOf("开心", "大哭")

    @Test
    fun stripUnknownMarks_dropsUnknownNames() {
        val src = "阿巴阿巴？[表情:疑惑]\n\n正文。[表情:偷笑]"
        assertEquals("阿巴阿巴？\n\n正文。", EmojiLib.stripUnknownMarks(src, known))
        assertEquals("", EmojiLib.stripUnknownMarks("[表情:星星眼]", known))
    }

    @Test
    fun stripUnknownMarks_keepsKnownNames() {
        val src = "开头[表情:开心]中间[表情:大哭]结尾"
        assertEquals(src, EmojiLib.stripUnknownMarks(src, known))
    }

    @Test
    fun stripUnknownMarks_emptyLibStripsAll() {
        assertEquals("纯正文", EmojiLib.stripUnknownMarks("[表情:疑惑]纯正文[表情:星星眼]", emptySet()))
    }

    @Test
    fun maskMarks_knownBecomesPlaceholder_unknownRemoved() {
        val src = "看这里[表情:开心]然后[表情:疑惑]结束"
        assertEquals("看这里〔表情〕然后结束", EmojiLib.maskMarks(src, known))
    }

    @Test
    fun maskMarks_emptyLibRemovesAll() {
        assertEquals("干净正文", EmojiLib.maskMarks("[表情:疑惑]干净正文[表情:偷笑]", emptySet()))
    }

    @Test
    fun markRegex_doesNotTouchOtherBrackets() {
        val src = "参考[1][链接](http://a.b) 与 [表情:开心]"
        assertEquals("参考[1][链接](http://a.b) 与 [表情:开心]",
            EmojiLib.stripUnknownMarks(src, known))
    }
}
