package io.github.aixtin.nyral

/**
 * scan 候选去重/容器剔除纯函数(2026-10-05):
 * 与 BrowserPageScan.kt 注入 JS 的 dedup/dropContainers 算法一致(Kotlin 对照实现),
 * 锁"容器+叶子同列"行为规范, 供 JVM 单测覆盖; JS 侧后续重构可直接对照本实现回归。
 */
object ScanDeduper {

    /** 归一文本: 连续空白折叠为单空格, 去首尾(与 JS norm 一致) */
    fun normText(t: String): String = t.replace(Regex("\\s+"), " ").trim()

    /** 视觉候选(模拟 JS cands 条目; descendants 表示该候选的 DOM 后代 id 集合, 模拟 el.contains) */
    data class Cand(
        val id: Int,
        val x: Int, val y: Int, val w: Int, val h: Int,
        val txt: String,
        val interactive: Boolean,
        val descendants: Set<Int> = emptySet()
    ) {
        fun area(): Int = w * h
        fun overlap(other: Cand): Int {
            val x1 = maxOf(x, other.x); val y1 = maxOf(y, other.y)
            val x2 = minOf(x + w, other.x + other.w); val y2 = minOf(y + h, other.y + other.h)
            return if (x2 <= x1 || y2 <= y1) 0 else (x2 - x1) * (y2 - y1)
        }
    }

    /**
     * 视觉去重(对应 JS dedup): 归一文本相同且重叠区占较小者面积 >= 0.8 视为同一视觉块,
     * 只留面积最小(定位最精确)的一份; 面积相同保留先采集的一份(输入序 id 小者优先)。
     */
    fun dedup(list: List<Cand>): List<Cand> {
        val keep = ArrayList<Cand>()
        for ((i, a) in list.withIndex()) {
            var drop = false
            for ((j, b) in list.withIndex()) {
                if (i == j) continue
                if (normText(a.txt) != normText(b.txt)) continue
                val ov = a.overlap(b)
                if (ov == 0) continue
                val smaller = minOf(a.area(), b.area())
                if (smaller == 0) continue
                if (ov.toDouble() / smaller >= 0.8) {
                    if (b.area() < a.area()) drop = true
                    else if (b.area() == a.area() && j < i) drop = true
                }
            }
            if (!drop) keep.add(a)
        }
        return keep
    }

    /**
     * 容器剔除(对应 JS dropContainers): 候选 a 的 DOM 后代 b 为可交互元素,
     * b 覆盖 a 面积 >= 60% 且 b 的归一文本是 a 归一文本的子串时, a 视为容器剔除。
     */
    fun dropContainers(list: List<Cand>): List<Cand> =
        list.filter { a ->
            var keep = true
            for (b in list) {
                if (b === a) continue
                if (b.id !in a.descendants) continue
                if (!b.interactive) continue
                val ov = a.overlap(b)
                if (ov == 0) continue
                if (a.area() == 0) continue
                if (ov.toDouble() / a.area() < 0.6) continue
                val at = normText(a.txt); val bt = normText(b.txt)
                if (bt.isNotEmpty() && at.contains(bt)) { keep = false; break }
            }
            keep
        }

    /** 完整链路: 去重 -> 容器剔除(对应 JS collectAndReport 前两步) */
    fun process(list: List<Cand>): List<Cand> = dropContainers(dedup(list))
}
