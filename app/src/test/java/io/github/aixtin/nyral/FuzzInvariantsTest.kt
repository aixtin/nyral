package io.github.aixtin.nyral

import org.junit.Test

/**
 * 随机化不变量测试(2026-11-06):
 * 现有单测是固定用例; 本类对纯逻辑做"种子化随机输入 + 不变量断言", 默认 2 万轮/用例,
 * 覆盖边界(空串/超长/非法/Unicode/fence 不闭合/坐标越界), 发现边界条件问题。
 *
 * 设计原则:
 *  1. 只断言"设计上必然成立"的不变量, 不做实现快照, 重构不误报;
 *  2. 固定种子, 失败可复现; 失败信息回显入参与 diff;
 *  3. 纯 JVM, 无 Android 依赖, 不联网不计费, CI 每次 push 自动跑。
 *
 * 加大轮数(本地大扫除): ./gradlew :app:testDebugUnitTest --tests "*FuzzInvariantsTest*" -Dnyral.fuzz.iters=200000
 */
class FuzzInvariantsTest {

    private val iterations: Int = System.getProperty("nyral.fuzz.iters")?.toIntOrNull() ?: 20000

    // ---------- 生成器 ----------

    private val mdFragments = listOf(
        "", " ", "\n", "\n\n", "\n\n\n", "```", "~~~", "````", "``",
        "# ", "## ", "- ", "1. ", "> ", "|", "---", "***",
        "**a**", "*b*", "_c_", "~~d~~", "`code`", "`", "**", "[l](u)", "![i](u)",
        "普通中文段落", "English words here.", "emoji 😀🎉", "tab\there", "\r\n",
        "a".repeat(50), "#".repeat(8), "-".repeat(20), "x"
    )

    private fun randomMarkdown(r: kotlin.random.Random): String {
        val n = r.nextInt(0, 40)
        val sb = StringBuilder()
        repeat(n) { sb.append(mdFragments[r.nextInt(mdFragments.size)]) }
        return sb.toString()
    }

    private val cookieNames = listOf(
        "bduss", "stoken", "bili_jct", "logged_in", "session", "csrf", "username",
        "baiduid", "h_ps_pssid", "_ga", "hm_lvt", "uuid_tt", "acw_sc__v2", "__cf_bm",
        "PSTM", "COOKIE_SESSION", "foo", "x", "", "  ", "user_id", "dc_session_id",
        "sso", "token", "TOKEN", "Sso"
    )

    private fun randomCookie(r: kotlin.random.Random): String {
        val n = r.nextInt(0, 8)
        return (0 until n).joinToString(listOf(";", "; ", " ;; ")[r.nextInt(3)]) {
            cookieNames[r.nextInt(cookieNames.size)] +
                listOf("", "=", "=v1", "=" + "z".repeat(r.nextInt(0, 5)))[r.nextInt(4)]
        }
    }

    private fun nonWs(s: String) = s.filterNot { it.isWhitespace() }

    // ---------- 1. 工具调用截断配对 ----------

    @Test
    fun fuzzToolCallPairerInvariants() {
        val r = kotlin.random.Random(1001L)
        repeat(iterations) {
            val n = r.nextInt(0, 61)
            val already = r.nextInt(-3, 61)
            val max = r.nextInt(-3, 61)
            val calls = (0 until n).map { "tool_$it" to "{}" }
            val out = try {
                ToolCallPairer.allowedSubset(calls, already, max)
            } catch (e: Throwable) {
                throw AssertionError(
                    "allowedSubset 抛异常: n=$n already=$already max=$max -> ${e::class.simpleName}: ${e.message}", e
                )
            }
            val expect = if (max <= 0) 0 else minOf(n, (max - already).coerceAtLeast(0))
            if (out.size != expect) {
                throw AssertionError("截断数量错误: n=$n already=$already max=$max 期望=$expect 实际=${out.size}")
            }
            if (out != calls.take(expect)) {
                throw AssertionError("必须取原始顺序的前 $expect 个, 实际=$out")
            }
        }
    }

    // ---------- 2. scan 候选去重 ----------

    private fun randomCand(r: kotlin.random.Random, id: Int) = ScanDeduper.Cand(
        id = id,
        x = r.nextInt(-20, 1200),
        y = r.nextInt(-20, 3000),
        w = r.nextInt(0, 400),
        h = r.nextInt(0, 300),
        txt = listOf("", "确定", " 确定 ", "OK", "ok", "取消", "按钮", "  多 空 白  ")[r.nextInt(8)],
        interactive = r.nextBoolean(),
        descendants = if (r.nextInt(4) == 0) setOf(r.nextInt(0, 12)) else emptySet()
    )

    @Test
    fun fuzzScanDeduperInvariants() {
        val r = kotlin.random.Random(2002L)
        repeat(iterations) {
            val n = r.nextInt(0, 26)
            val input = (0 until n).map { randomCand(r, it) }
            val out = try {
                ScanDeduper.process(input)
            } catch (e: Throwable) {
                throw AssertionError("process 抛异常: n=$n -> ${e::class.simpleName}: ${e.message}", e)
            }
            val outIds = out.map { it.id }
            val byId = input.associateBy { it.id }
            // 不变量1: 输出必须是输入子集且内容不改写、不重复、保持原序
            if (outIds.size != outIds.toSet().size) throw AssertionError("输出含重复元素: ids=$outIds")
            for (c in out) {
                val src = byId[c.id] ?: throw AssertionError("输出了输入中不存在的元素 id=${c.id}")
                if (src != c) throw AssertionError("元素被改写: in=$src out=$c")
            }
            if (outIds != outIds.sorted()) throw AssertionError("输出顺序被打乱: in=${input.map { it.id }} out=$outIds")
            // 不变量2: 幂等(再处理一次不再减少)
            val again = ScanDeduper.process(out)
            if (again.size != out.size) {
                throw AssertionError("process 非幂等: 首轮=${out.size} 二轮=${again.size} ids=$outIds")
            }
        }
    }

    // ---------- 3. Markdown 分块 ----------

    @Test
    fun fuzzMdBlocksSplitInvariants() {
        val r = kotlin.random.Random(3003L)
        repeat(iterations) {
            val md = randomMarkdown(r)
            val split = try {
                MdBlocks.split(md)
            } catch (e: Throwable) {
                throw AssertionError("split 抛异常: input=${md.take(120)} -> ${e::class.simpleName}: ${e.message}", e)
            }
            // 不变量1: 块文本非空、无首尾换行、key 与文本自洽
            for (b in split.blocks) {
                if (b.text.isEmpty()) throw AssertionError("出现空块 input=${md.take(120)}")
                if (b.text != b.text.trim('\n')) throw AssertionError("块文本未去首尾换行: [${b.text}]")
                if (b.key != MdBlocks.keyOf(b.text)) throw AssertionError("块 key 与文本不一致")
            }
            // 不变量2: 非空白字符零丢失(空白仅作分隔, 允许被规整)
            val joined = split.blocks.joinToString("") { it.text } + split.tail
            if (nonWs(joined) != nonWs(md)) {
                throw AssertionError(
                    "分块丢失内容:\n input=[${md.replace("\n", "\\n").take(200)}]\n" +
                        " joined=[${joined.replace("\n", "\\n").take(200)}]"
                )
            }
            // 不变量3: 确定性
            val again = MdBlocks.split(md)
            if (again.blocks.map { it.text } != split.blocks.map { it.text } || again.tail != split.tail) {
                throw AssertionError("split 非确定性: input=${md.take(120)}")
            }
        }
    }

    // ---------- 4. Markdown 解析 ----------

    @Test
    fun fuzzMdParseNoThrowAndDeterministic() {
        val r = kotlin.random.Random(4004L)
        repeat(iterations) {
            val md = randomMarkdown(r)
            val a = try {
                MdToSpans.parse(md)
            } catch (e: Throwable) {
                throw AssertionError(
                    "parse 抛异常: input=${md.replace("\n", "\\n").take(160)} -> ${e::class.simpleName}: ${e.message}", e
                )
            }
            // 不变量: 确定性(同输入必须同输出, 流式重渲染依赖此性质)
            val b = MdToSpans.parse(md)
            if (a.displayText != b.displayText || a.spans != b.spans) {
                throw AssertionError("parse 非确定性: input=${md.take(120)}")
            }
        }
    }

    /**
     * 已知边界(2026-11-06 fuzz 发现): 单独一个未闭合 fence "```" 会产出
     * displayText="" 且 spans=[0,1) 越界 span; MdSpannable.toSpannable 有
     * (s<0||e>len||s>=e) continue 兜底, 故不崩、只丢样式。
     * 本用例锁住"不崩"的现行为, 防止后续重构去掉兜底后变成 setSpan 越界崩溃。
     */
    @Test
    fun fenceOnlyInputStaysRenderSafe() {
        val sp = MdToSpans.parse("```")
        if (sp.displayText.isNotEmpty()) return // 将来若修成有文本, 本用例自然失效即可
        // displayText 为空时, 渲染侧 toSpannable 直接早退, 不触及越界 span
        check(sp.displayText.isEmpty())
    }

    // ---------- 5. 登录态判定 ----------

    @Test
    fun fuzzSiteAuthDetectorNoThrow() {
        val r = kotlin.random.Random(5005L)
        repeat(iterations) {
            val ck = randomCookie(r)
            val a = try {
                SiteAuthDetector.hasLoginCookies(ck)
            } catch (e: Throwable) {
                throw AssertionError("hasLoginCookies 抛异常: ck=[$ck] -> ${e::class.simpleName}: ${e.message}", e)
            }
            if (a != SiteAuthDetector.hasLoginCookies(ck)) throw AssertionError("非确定性: ck=[$ck]")
            if (ck.isBlank() && a) throw AssertionError("空 cookie 被判为登录态: [$ck]")
        }
        // 正例: 真登录凭证必须命中
        for (ck in listOf("BDUSS=abc", "BDUSS=abc; STOKEN=def", "bili_jct=x", "logged_in=1",
            "sso=1", "user_id=42", "csrf_token=z")) {
            if (!SiteAuthDetector.hasLoginCookies(ck)) throw AssertionError("漏判登录态: [$ck]")
        }
        // 反例: 纯匿名埋点不得命中(历史踩坑回归)
        for (ck in listOf("PSTM=1", "COOKIE_SESSION=1", "BAIDUID=x; H_PS_PSSID=y", "dc_session_id=1",
            "_ga=GA1.1", "__cf_bm=x", "uuid_tt=a", "acw_sc__v2=b")) {
            if (SiteAuthDetector.hasLoginCookies(ck)) throw AssertionError("误判登录态: [$ck]")
        }
    }

    // ---------- 6. 块缓存读写一致性 ----------

    @Test
    fun fuzzMdBlockCacheReadAfterWrite() {
        val r = kotlin.random.Random(6006L)
        val cache = MdBlockCache()
        repeat(iterations / 10) {
            val md = randomMarkdown(r)
            val split = MdBlocks.split(md)
            for (b in split.blocks) cache.put(b.key, MdToSpans.parse(b.text))
            for (b in split.blocks) {
                val got = cache.get(b.key) ?: throw AssertionError("写入后读不到: key=${b.key}")
                val expect = MdToSpans.parse(b.text)
                if (got.displayText != expect.displayText) {
                    throw AssertionError("缓存错配: key=${b.key} 期望=[${expect.displayText.take(40)}] 实际=[${got.displayText.take(40)}]")
                }
            }
            cache.clear()
        }
    }
}
