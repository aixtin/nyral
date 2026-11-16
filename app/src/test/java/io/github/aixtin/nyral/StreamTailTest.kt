package io.github.aixtin.nyral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 收口差值补写（missingTail）的边界用例。
 * 对应 2026-11-16 修复：停止/超时后"落库正文有尾巴、气泡里没有"。
 *
 * 第二版重点补的是多轮会话：引擎侧 CancelWithPartial.merge 用 full + "\n\n" + partial
 * 拼接，而气泡快照是连续文本或单换行，纯前缀比对会静默失效 —— 这些用例就是钉这条。
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
    fun `多轮会话双换行拼接也能补出后续轮次`() {
        val full = "第一轮回答\n\n第二轮答到一半"
        assertEquals("\n\n第二轮答到一半", missingTail(full, "第一轮回答"))
    }

    @Test
    fun `多轮会话快照带尾随空格时同样对齐`() {
        val full = "第一轮回答\n\n第二轮答到一半"
        assertEquals("\n\n第二轮答到一半", missingTail(full, "第一轮回答 "))
    }

    @Test
    fun `空白差异不算缺口`() {
        assertNull(missingTail("第一段 第二段", "第一段第二段"))
        assertNull(missingTail("第一段\n\n第二段", "第一段第二段"))
    }

    @Test
    fun `尾巴带前导空白时原样补出分隔符`() {
        val full = "正文结束\n\n补出来的尾句"
        assertEquals("\n\n补出来的尾句", missingTail(full, "正文结束"))
    }

    @Test
    fun `2000字长前缀仍然只返回缺口`() {
        val head = "a".repeat(2000)
        val tail = "漏斗尾巴"
        assertEquals(tail, missingTail(head + tail, head))
        // 分隔符必须原样补出: 气泡接着追加, 少掉 "\n\n" 就变成与落库正文不一致(两段粘连)
        assertEquals("\n\n$tail", missingTail(head + "\n\n" + tail, head))
    }

    @Test
    fun `掩码或显示形态改写后放弃补写`() {
        assertNull(missingTail("正文 A 尾巴", "正文 B"))
        assertNull(missingTail("abc尾巴", "abd"))
        assertNull(missingTail("正文[表情]", "正文[图片]"))
    }
}
