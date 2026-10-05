package io.github.aixtin.nyral

/**
 * 流式 MD 渲染器 (D路线第4步, 借鉴主流 IM 客户端流式排版 + 尾部截断保护):
 * - 完整块 -> 块级缓存命中, 不重复解析
 * - 尾部未完成块 -> 每帧轻量 parse(半截语法自动退化为纯文本, 安全)
 * - att:// 附件卡在 parse 层占位, 收尾 markwon 渲染真实卡片
 * 流式结束(done)后由 finishTypeRender 走 markwon 全量排版, 本类不再使用。
 */
class MdStreamRenderer {
    private val cache = MdBlockCache()

    /** 渲染当前累积前缀(shownLen 截断处) */
    fun render(prefix: String): CharSequence {
        if (prefix.isEmpty()) return ""
        val split = MdBlocks.split(prefix)
        val units = ArrayList<Pair<MdBlockUnit, MdSpans>>()
        for (b in split.blocks) {
            val s = cache.get(b.key) ?: MdToSpans.parse(b.text).also { cache.put(b.key, it) }
            units.add(b to s)
        }
        if (split.tail.isNotBlank()) {
            // 末块(可能未闭合)每帧轻量解析; 半截语法(未闭合 **/`/[/fence) 由 commonmark 自动退化
            units.add(MdBlockUnit("tail:${MdBlocks.keyOf(split.tail)}", split.tail) to MdToSpans.parse(split.tail))
        }
        if (units.isEmpty()) return ""
        return MdSpannable.toSpannable(MdAssembler.assemble(units))
    }

    /** 段完成收尾后释放缓存, 避免历史气泡滞留解析结果 */
    fun clearCache() {
        cache.clear()
    }
}
