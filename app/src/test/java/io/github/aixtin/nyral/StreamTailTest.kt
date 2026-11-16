package io.github.aixtin.nyral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 收口差值补写（missingTail）的边界用例。
 * 对应 2026-11-16 修复：停止/超时后"落库正文有尾巴、气泡里没有"。
 */
class StreamTailTest {

    @Test
    fun `已渲染是完整正文的前缀时补出缺口`() {
        val full = "第一段\n第二段还没换行就被掐断的半句"
        val visible = "第一段\n第二段还没换行"
        assertEquals("就被掐断的半句", missingTail(full, visible))
    }

    @Test
    fun `完全没有渲染过时整段补写`() {
        assertEquals("整段内容", missingTail("整段内容", ""))
    }

    @Test
    fun `完全一致时不补`() {
        assertNull(missingTail("一样的内容", "一样的内容"))
    }

    @Test
    fun `已渲染比正文长时不补`() {
        assertNull(missingTail("短", "短得多"))
    }

    @Test
    fun `前缀对不上时放弃补写`() {
        // 表情掩码 / 换行归一化会让两侧文本不同源，宁缺不错
        assertNull(missingTail("正文 A 尾巴", "正文 B"))
        assertNull(missingTail("abc尾巴", "abd"))
    }

    @Test
    fun `正文为空时不补`() {
        assertNull(missingTail("", ""))
        assertNull(missingTail("", "已有内容"))
    }

    @Test
    fun `尾部只差一个换行也不补(长度不足即退出)`() {
        // trim 之后两侧一致，说明尾巴就是空白，补了反而多空行
        assertNull(missingTail("结尾无换行", "结尾无换行"))
    }

    @Test
    fun `长文本尾部补写只返回缺口`() {
        val head = "a".repeat(2000)
        val tail = "漏斗尾巴"
        assertEquals(tail, missingTail(head + tail, head))
    }
}
