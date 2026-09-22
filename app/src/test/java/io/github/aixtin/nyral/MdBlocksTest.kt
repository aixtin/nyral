package io.github.aixtin.nyral

import org.junit.Test

/** D路线第3步验收: 块级切分 + 缓存 + 组装 */
class MdBlocksTest {

    @Test
    fun splitBlocksBasic() {
        val md = """
            第一段内容

            - 项一
            - 项二

            ```kotlin
            val a = 1

            val b = 2
            ```

            最后一段
        """.trimIndent()
        val r = MdBlocks.split(md)
        println("===== 完整块数: ${r.blocks.size} =====")
        r.blocks.forEach { println("key=${it.key} len=${it.text.length} text=[${it.text.take(30)}...]") }
        println("tail=[${r.tail.take(30)}...]")
        // 代码块内空行不切分: 代码块应作为一个完整块
        check(r.blocks.size == 3) { "块数错误: ${r.blocks.size}" }
        check(r.blocks[0].text.contains("第一段内容"))
        check(r.blocks[1].text.contains("项一"))
        check(r.blocks[2].text.contains("```kotlin")) { "代码块应完整成块" }
        check(r.blocks[2].text.contains("val b = 2")) { "代码块内空行不应切断" }
        check(r.tail.contains("最后一段")) { "末块应在 tail" }
        println("===== 验收通过: 分块 OK =====")
    }

    @Test
    fun cacheHit() {
        val cache = MdBlockCache()
        var parseCount = 0
        fun build(md: String): MdSpans {
            val split = MdBlocks.split(md)
            val units = split.blocks.map { b ->
                var s = cache.get(b.key)
                if (s == null) {
                    parseCount++
                    s = MdToSpans.parse(b.text)
                    cache.put(b.key, s)
                }
                b to s
            }
            return MdAssembler.assemble(units)
        }
        // 模拟流式追加: 第1次来3块(末尾空行收尾使其完整), 第2次追加第4块
        val md1 = "# 标题\n\n第一段内容\n\n第二段\n\n"
        val r1 = build(md1)
        check(r1.displayText.contains("第二段")) { "组装缺第2次块" }
        val firstParse = parseCount
        check(firstParse == 3) { "首次应解析3块, 实际$firstParse" }

        val md2 = "# 标题\n\n第一段内容\n\n第二段\n\n第三段内容\n\n"
        val r2 = build(md2)
        // 新增块解析1次, 旧块全部命中缓存
        check(parseCount == 4) { "缓存失效: 应解析4次(标题/一段/二段/三段), 实际$parseCount" }
        check(cache.size() == 4) { "缓存大小错误: ${cache.size()}" }
        check(r2.displayText.contains("第三段内容")) { "组装缺新块" }
        check(r2.displayText.contains("第一段内容")) { "组装缺旧块" }
        println("===== 验收通过: 缓存命中 + 组装 OK, parseCount=$parseCount =====")
    }

    @Test
    fun assembleSpansOffset() {
        val u1 = MdToSpans.parse("**粗体一**")
        val u2 = MdToSpans.parse("和 *斜体二*")
        val assembled = MdAssembler.assemble(listOf(
            MdBlockUnit("k1", "**粗体一**") to u1,
            MdBlockUnit("k2", "和 *斜体二*") to u2
        ))
        println("displayText=[${assembled.displayText}] len=${assembled.displayText.length}")
        assembled.spans.forEach { println("${it.type} [${it.start},${it.end})") }
        check(assembled.displayText == "粗体一\n\n和 斜体二") { "组装文本错误: ${assembled.displayText}" }
        val bold = assembled.spans.first { it.type == MdSpanType.BOLD }
        check(bold.start == 0 && bold.end == 3) { "BOLD 偏移错误 $bold" }
        val italic = assembled.spans.first { it.type == MdSpanType.ITALIC }
        check(italic.start == 7 && italic.end == 10) { "ITALIC 偏移错误 $italic" }
        println("===== 验收通过: spans 偏移 OK =====")
    }
}
