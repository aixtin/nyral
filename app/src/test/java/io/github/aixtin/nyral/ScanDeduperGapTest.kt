package io.github.aixtin.nyral

import io.github.aixtin.nyral.ScanDeduper.Cand
import org.junit.Test

/**
 * ScanDeduper 缺口用例补充(2026-10-06):
 * 补齐 Jacoco 分支缺口: dedup 的重叠<80%、等面积保留早采集;
 * dropContainers 的 ov==0、容器面积0、覆盖<60%、叶子空文本。
 */
class ScanDeduperGapTest {

    private fun c(
        id: Int, x: Int, y: Int, w: Int, h: Int, txt: String,
        interactive: Boolean = false, descendants: Set<Int> = emptySet()
    ) = Cand(id, x, y, w, h, txt, interactive, descendants)

    // ---------- dedup: 重叠不足 80% 不去重 ----------

    @Test
    fun `同文本重叠不足80保留两份`() {
        // 大块 200x50, 小块 100x30 但偏移大: overlap 仅覆盖小块一半(1500/3000=50%)
        val a = c(0, 0, 0, 200, 50, "加入购物车")
        val b = c(1, 150, 10, 100, 30, "加入购物车")
        val kept = ScanDeduper.dedup(listOf(a, b))
        check(kept.size == 2) { "重叠不足80%不应去重, 实际 ${kept.map { it.id }}" }
    }

    @Test
    fun `同文本重叠恰好80去重`() {
        // overlap 2400, smaller 3000 -> 80% 边界值, 应去重留小者
        val a = c(0, 0, 0, 200, 50, "按钮文字")
        val b = c(1, 120, 0, 100, 30, "按钮文字")
        val kept = ScanDeduper.dedup(listOf(a, b))
        check(kept.size == 1) { "重叠80%边界应去重, 实际 ${kept.map { it.id }}" }
        check(kept[0].id == 1) { "应保留面积小者, 实际 ${kept[0].id}" }
    }

    // ---------- dedup: 等面积保留先采集(id 小者) ----------

    @Test
    fun `等面积重叠保留先采集者`() {
        val first = c(0, 0, 0, 100, 30, "相同文本")
        val second = c(1, 5, 0, 100, 30, "相同文本")
        val kept = ScanDeduper.dedup(listOf(first, second))
        check(kept.size == 1) { "等面积重叠应去重, 实际 ${kept.map { it.id }}" }
        check(kept[0].id == 0) { "等面积应保留先采集(id小), 实际 ${kept[0].id}" }
    }

    @Test
    fun `等面积但后采集不反向覆盖`() {
        // second 面积与 first 相同且重叠, 但 second 是后采集(j>i): first 不应被 second 丢弃
        val first = c(0, 0, 0, 100, 30, "相同文本")
        val second = c(1, 5, 0, 100, 30, "相同文本")
        val kept = ScanDeduper.dedup(listOf(first, second))
        check(kept.contains(first)) { "后采集等面积不应丢弃先采集者" }
    }

    @Test
    fun `等面积不重叠均保留`() {
        val a = c(0, 0, 0, 100, 30, "相同文本")
        val b = c(1, 0, 40, 100, 30, "相同文本")
        val kept = ScanDeduper.dedup(listOf(a, b))
        check(kept.size == 2) { "等面积但不重叠不应去重" }
    }

    // ---------- dropContainers: 边界分支 ----------

    @Test
    fun `交互后代完全不重叠容器保留`() {
        // 后代交互但位置完全不重叠(ov==0): 不触发容器剔除
        val container = c(0, 0, 0, 300, 100, "商品卡片区域")
        val leaf = c(1, 500, 0, 100, 30, "加入购物车", interactive = true)
        val withRel = listOf(container.copy(descendants = setOf(1)), leaf)
        val kept = ScanDeduper.dropContainers(withRel)
        check(kept.size == 2) { "后代不重叠不应剔除容器, 实际 ${kept.map { it.id }}" }
    }

    @Test
    fun `容器面积零保留`() {
        // 容器 w=0(面积0): 跳过剔除, 保留容器
        val container = c(0, 0, 0, 0, 100, "异常容器")
        val leaf = c(1, 0, 0, 50, 30, "异常容器", interactive = true)
        val withRel = listOf(container.copy(descendants = setOf(1)), leaf)
        val kept = ScanDeduper.dropContainers(withRel)
        check(kept.size == 2) { "容器面积0应保留, 实际 ${kept.map { it.id }}" }
    }

    @Test
    fun `交互后代覆盖不足60容器保留`() {
        // 后代交互、是文本子串, 但覆盖 <60%: 不剔除
        val container = c(0, 0, 0, 300, 100, "长文本标题 加入购物车")
        val leaf = c(1, 0, 0, 100, 20, "加入购物车", interactive = true)
        val withRel = listOf(container.copy(descendants = setOf(1)), leaf)
        val kept = ScanDeduper.dropContainers(withRel)
        check(kept.size == 2) { "覆盖不足60%不应剔除容器, 实际 ${kept.map { it.id }}" }
    }

    @Test
    fun `交互后代空文本容器保留`() {
        // 后代交互、覆盖足够, 但文本为空串: bt.isNotEmpty 为 false, 不剔除
        val container = c(0, 0, 0, 300, 100, "商品标题")
        val leaf = c(1, 10, 10, 280, 80, "  ", interactive = true)
        val withRel = listOf(container.copy(descendants = setOf(1)), leaf)
        val kept = ScanDeduper.dropContainers(withRel)
        check(kept.size == 2) { "后代空文本不应剔除容器, 实际 ${kept.map { it.id }}" }
    }

    @Test
    fun `交互后代文本等于容器文本剔除`() {
        // 后代文本与容器完全相等(子串关系成立): 应剔除容器
        val container = c(0, 0, 0, 300, 100, "确认付款")
        val leaf = c(1, 10, 10, 280, 80, "确认付款", interactive = true)
        val withRel = listOf(container.copy(descendants = setOf(1)), leaf)
        val kept = ScanDeduper.dropContainers(withRel)
        check(kept.size == 1) { "后代文本等于容器文本应剔除容器" }
        check(kept[0].id == 1) { "应保留叶子, 实际 ${kept[0].id}" }
    }

    // ---------- process 链路 ----------

    @Test
    fun `process空列表`() {
        check(ScanDeduper.process(emptyList()).isEmpty()) { "空列表应返回空" }
    }

    @Test
    fun `process单元素保留`() {
        val a = c(0, 0, 0, 100, 30, "唯一元素")
        val kept = ScanDeduper.process(listOf(a))
        check(kept.size == 1 && kept[0].id == 0) { "单元素应保留" }
    }
}
