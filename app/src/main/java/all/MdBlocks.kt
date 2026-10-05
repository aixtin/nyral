package io.github.aixtin.nyral

/**
 * 块级缓存 (D路线第3步, 借鉴主流 IM 客户端的流式排版):
 * 块 key -> MdSpans, 双检锁; 流式 delta 只影响末块, 已解析块命中缓存不重解析。
 * 配合"截断尾部不完整块": 流式只组装完整块, 末块延后一拍, 结束 flush 全量收敛。
 */
class MdBlockCache {
    private val cache = HashMap<String, MdSpans>()
    private val lock = Any()

    fun get(key: String): MdSpans? = synchronized(lock) { cache[key] }

    fun put(key: String, spans: MdSpans) {
        synchronized(lock) { cache[key] = spans }
    }

    fun size(): Int = synchronized(lock) { cache.size }

    fun clear() = synchronized(lock) { cache.clear() }
}

/** 分块结果: 完整块列表 + 尾部未完成块(流式时延后解析) */
data class MdSplit(val blocks: List<MdBlockUnit>, val tail: String)

/** 单个完整块单元: key + 源文本 */
data class MdBlockUnit(val key: String, val text: String)

/** 分块工具: 按空行切块, 代码块 fence 内部空行不切 */
object MdBlocks {

    /** 块 key: 内容 hash + 长度, 内容不变则 key 稳定 */
    fun keyOf(text: String): String = "${text.hashCode()}:${text.length}"

    /**
     * 按空行切块(fence 感知)。
     * @param md 当前累积全文
     * @return 完整块列表(不含尾部未完成块) + 尾部文本
     */
    fun split(md: String): MdSplit {
        val lines = md.split("\n")
        val blocks = ArrayList<MdBlockUnit>()
        val sb = StringBuilder()
        var fence: String? = null
        var fenceLen = 0

        fun flushBlock() {
            if (sb.isEmpty()) return
            val t = sb.toString().trim('\n')
            if (t.isNotEmpty()) {
                blocks.add(MdBlockUnit(keyOf(t), t))
            }
            sb.setLength(0)
        }

        for (line in lines) {
            val trimmed = line.trimStart()
            if (fence != null) {
                // fence 内: 只关心闭合行
                sb.append(line).append('\n')
                val f = fence
                val fLen = fenceLen
                if (trimmed.startsWith(f) && trimmed.length >= fLen &&
                    trimmed.take(fLen).all { it == f[0] }
                ) {
                    fence = null
                }
                continue
            }
            // fence 外: 检测开启 fence (``` 或 ~~~, >=3 个)
            val fm = FENCE_REGEX.find(trimmed)
            if (fm != null) {
                // 前面已积累的普通块先结算
                flushBlock()
                fence = fm.value[0].toString()
                fenceLen = fm.value.length
                sb.append(line).append('\n')
                continue
            }
            if (trimmed.isEmpty()) {
                flushBlock()
                sb.append('\n') // 保留空行分隔信息
            } else {
                sb.append(line).append('\n')
            }
        }
        // 尾部剩余 = 未完整块(可能是不完整 fence / 最后一段), 流式时不解析
        val tail = sb.toString().trim('\n')
        // 去掉 blocks 末尾可能多出的空块
        while (blocks.isNotEmpty() && blocks.last().text.isEmpty()) blocks.removeAt(blocks.size - 1)
        return MdSplit(blocks, tail)
    }

    private val FENCE_REGEX = Regex("""^(`{3,}|~{3,})""")
}

/**
 * 块组装器: 把多个已解析块拼成单一 MdSpans (displayText + 偏移后的 spans)。
 * 块间以 \n\n 分隔, 保证段落间距与整体解析一致。
 */
object MdAssembler {

    fun assemble(units: List<Pair<MdBlockUnit, MdSpans>>): MdSpans {
        if (units.isEmpty()) return MdSpans("", emptyList())
        val sb = StringBuilder()
        val spans = ArrayList<MdSpan>()
        units.forEachIndexed { i, (_, s) ->
            if (i > 0) sb.append("\n\n")
            val base = sb.length
            sb.append(s.displayText)
            for (sp in s.spans) {
                spans += MdSpan(base + sp.start, base + sp.end, sp.type, sp.extra)
            }
        }
        return MdSpans(sb.toString(), spans)
    }
}
