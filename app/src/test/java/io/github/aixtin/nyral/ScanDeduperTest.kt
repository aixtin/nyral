package io.github.aixtin.nyral

import io.github.aixtin.nyral.ScanDeduper.Cand
import org.junit.Test

/**
 * scan 去重/容器剔除 JVM 单测(2026-10-05):
 * 覆盖历史问题2"容器+叶子同列 -> 索引指向不稳定"的行为规范。
 */
class ScanDeduperTest {

    private fun c(
        id: Int, x: Int, y: Int, w: Int, h: Int, txt: String,
        interactive: Boolean = false, descendants: Set<Int> = emptySet()
    ) = Cand(id, x, y, w, h, txt, interactive, descendants)

    // ---------- 容器+叶子同列(历史问题2) ----------

    @Test
    fun containerWithClickableLeafIsDropped() {
        // 商品卡片 div(大, 文本含按钮文字) + 内部 button(小, 文本子串, 交互, 覆盖>60%)
        val container = c(0, 0, 0, 300, 100, "商品标题 加入购物车")
        val leaf = c(1, 10, 10, 280, 80, "加入购物车", interactive = true, descendants = emptySet())
        val list = listOf(container, leaf)
        // container.descendants 含 leaf.id: container.el.contains(leaf.el)
        val withRel = list.map { if (it.id == 0) it.copy(descendants = setOf(1)) else it }
        val kept = ScanDeduper.process(withRel)
        check(kept.size == 1) { "容器应被剔除只留叶子, 实际保留 ${kept.map { it.id }}" }
        check(kept[0].id == 1) { "应保留叶子 button, 实际 ${kept[0].id}" }
    }

    @Test
    fun containerWithoutRelationStays() {
        // 容器与叶子无 DOM 父子关系(非同列), 不应误删
        val a = c(0, 0, 0, 200, 80, "左边区块")
        val b = c(1, 300, 0, 100, 30, "加入购物车", interactive = true)
        val kept = ScanDeduper.process(listOf(a, b))
        check(kept.size == 2) { "无父子关系不应剔除, 实际 ${kept.map { it.id }}" }
    }

    @Test
    fun containerWithNonLeafTextKept() {
        // 叶子文本不是容器文本子串: 不是"容器+叶子"模式, 不剔除
        val container = c(0, 0, 0, 300, 100, "商品详情区域")
        val leaf = c(1, 20, 70, 100, 30, "完全无关文本", interactive = true)
        val withRel = listOf(container.copy(descendants = setOf(1)), leaf)
        val kept = ScanDeduper.process(withRel)
        check(kept.size == 2) { "叶子文本非子串时容器应保留, 实际 ${kept.map { it.id }}" }
    }

    @Test
    fun containerWithNonInteractiveChildStays() {
        // 后代不可交互(如纯装饰 div): 不触发容器剔除
        val container = c(0, 0, 0, 300, 100, "标题 副标题")
        val child = c(1, 10, 10, 280, 80, "副标题", interactive = false)
        val withRel = listOf(container.copy(descendants = setOf(1)), child)
        val kept = ScanDeduper.process(withRel)
        check(kept.size == 2) { "非交互后代不应剔除容器" }
    }

    // ---------- 视觉去重(dedup) ----------

    @Test
    fun duplicateOverlapKeepsSmallestArea() {
        // 同文本、重叠>=80%、面积一大一小: 只留面积小者
        val big = c(0, 0, 0, 200, 50, "加入购物车")
        val small = c(1, 10, 10, 100, 30, "加入购物车")
        val kept = ScanDeduper.dedup(listOf(big, small))
        check(kept.size == 1) { "同文本重叠应去重, 实际 ${kept.map { it.id }}" }
        check(kept[0].id == 1) { "应保留面积小的, 实际 ${kept[0].id}" }
    }

    @Test
    fun sameTextNoOverlapKeepsBoth() {
        // 同文本但位置不重叠(如列表两行同按钮): 保留两份
        val a = c(0, 0, 0, 100, 30, "加入购物车", interactive = true)
        val b = c(1, 0, 50, 100, 30, "加入购物车", interactive = true)
        val kept = ScanDeduper.dedup(listOf(a, b))
        check(kept.size == 2) { "无重叠不应去重" }
    }

    @Test
    fun normTextCollapsesWhitespace() {
        check(ScanDeduper.normText("  加入\n 购物车  ") == "加入 购物车") { "空白折叠错误" }
        check(ScanDeduper.normText("") == "") { "空串保持空" }
    }
}
