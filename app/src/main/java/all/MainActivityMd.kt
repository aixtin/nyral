package io.github.aixtin.nyral
import android.text.Spanned
import android.widget.TextView
import android.util.Log
import android.view.View
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.ArrayList
import android.util.LruCache

/** Markdown 渲染/测量域(MainActivity 扩展): 预编译缓存/高度探测/漂移补偿/渲染回写 —— 自 MainActivity.kt 拆出 */
    /** 占位净化(09-28 气泡缩短修复): 长文本未命中/滑动中 defer 时上屏的占位, 剔除
     *  markdown 渲染后不占行的源码行(``` 围栏、表格分隔行), 使占位高度≈渲染后高度,
     *  消除"占位(高)→渲染(矮)"的二次刷新视觉缩短; 校验随之改为比较净化占位文本 */
    internal fun MainActivity.mdPlaceholder(md: String): String {
        val sb = StringBuilder(md.length)
        for (line in md.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("```")) continue
            if (t.contains("|") && t.contains("-") && t.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }) continue
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }
    /** 高度缓存 key: md 分片内容 + 当前气泡宽度(px) 分档, 宽度变化不串档 */
    internal fun MainActivity.heightKey(md: String) = md + "@" + chatMaxW()

    /** 探测 TextView(复用): 完全复刻真实 AiRich 分片气泡配置, 用于预渲染完成后离线测量最终高度 */
    internal fun MainActivity.ensureMdProbe(): TextView {
        mdProbeTv?.let { return it }
        return TextView(this).apply {
            // 必须有 LayoutParams: setText->checkForRelayout 会读 mLayoutParams.width, null 即 NPE
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            includeFontPadding = false
            setTextColor(BUBBLE_AI_TEXT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxWidth = chatMaxW()
        }.also { mdProbeTv = it }
    }
    /** 预渲染完成入队测量: 主线程串行消化, 每条双层 post(等表格二次 setText)后测量最终高度入 mdHeightCache */
    internal fun MainActivity.enqueueMdMeasure(md: String, spanned: Spanned) {
        if (mdHeightCache.get(heightKey(md)) != null) return
        mdMeasureQueue.add(md to spanned)
        if (!mdMeasureBusy) scheduleMdMeasure()
    }
    internal fun MainActivity.scheduleMdMeasure() {
        mdMeasureBusy = true
        chatRec.post {
            val item = mdMeasureQueue.poll()
            if (item == null) { mdMeasureBusy = false; return@post }
            val (md, spanned) = item
            val key = heightKey(md)
            if (mdHeightCache.get(key) != null) { scheduleMdMeasure(); return@post }
            val probe = ensureMdProbe()
            probe.maxWidth = chatMaxW()
            // setParsedMarkdown 内部对表格插件 post 二次 setText(布局最终高度), 双 post 确保其先执行再测量
            markwon.setParsedMarkdown(probe, spanned)
            chatRec.post {
                probe.measure(
                    View.MeasureSpec.makeMeasureSpec(probe.maxWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                mdHeightCache.put(key, probe.measuredHeight)
                scheduleMdMeasure()   // 串行处理下一条
            }
        }
    }
    /** 真实气泡渲染/替换完成后记录最终高度(双层 post 等表格二次测量后的最终布局) */
    internal fun MainActivity.recordMdHeight(tv: TextView, md: String) {
        if (tv.height <= 0) return
        chatRec.post {
            chatRec.post {
                if (tv.height > 0) mdHeightCache.put(heightKey(md), tv.height)
            }
        }
    }
    /** 历史气泡渲染统一入口: 命中预编译缓存直接 set(主线程零解析), 未命中同步渲染(旧路径)并回填缓存;
     *  所有路径挂高度漂移补偿, 抵御 Markwon 表格 span 布局后二次测量 */

    internal fun MainActivity.setMarkdownCached(tv: TextView, md: String, rendered: String? = null, writeback: ((Spanned) -> Unit)? = null) {
        // 方案B(2026-10-01): 历史渲染统一块化, 本入口只应收无表格文本;
        // 若仍收到表格语法说明有渲染点漏走块化预判 -> 告警便于发现(表格会退化为 span 旧路径)
        if (containsTableSyntax(md)) android.util.Log.w("NyralTbl", "setMarkdownCached got table md len=" + md.length)
        // 高度漂移补偿: 表格/复杂 span 的二次测量发生在首次布局之后(post 同文本再 setText),
        // 高度突增推挤视口内容 = 上翻"突然加速"; watcher 每帧 draw 前反向补偿钉住阅读位置
        // 09-27 修复: watcher 必须在文本设置之后注册——若先注册, 基线记的是 RV 复用残留的旧内容
        // (或空内容)高度, setMarkdown 替换文本瞬间的高度差(可达几百px)被误当"生长量"补偿,
        // 产生 delta=-236/+310 的上下跳闪(DRIFTDBG 实测)
        // 命中路径必须走 setParsedMarkdown(内部跑 beforeSetText/afterSetText 全流程),
        // 直接 tv.text=spanned 会绕过 Markwon 的 TextView 生命周期钩子, 列表等 span 测量异常 = 气泡右侧被截断
        // 阶段1 rendered 三级读取第②级: 内存 miss 时反序列化落库产物回填缓存(零 markwon 解析)

        // 阶段5 span 宽度现算: 表格行宽度固化在 span 里, 渲染/反序列化前必须注入当前 TextView 可用宽,
        // 与 AiBubbleHolder 一致, 保证池化复用/重启直读与现场渲染同宽(消除表格二次跳变)
        val renderW = if (tv.maxWidth > 0) tv.maxWidth else MdSpannable.tableMaxWidth
        if (mdCache.get(md) == null && !rendered.isNullOrBlank()) {
            val dec = RenderedCodec.decode(
                rendered,
                markwon.configuration().theme(),
                mdTableTheme,
                resources.displayMetrics.density,
                renderW
            )
            if (dec != null) {
                // 坏数据防护(10-02): 块化段落库 rendered 是"源码纯文本 JSON"(无 span 且 text==md),
                // decode 回显源码会覆盖正常渲染 -> 识别并忽略, 走现场 markwon 渲染
                val decPlain = dec.getSpans(0, dec.length, Any::class.java).isEmpty() && dec.toString().trim() == md.trim()
                if (!decPlain) { mdCache.put(md, dec); android.util.Log.i("DbgMd", "decode mdLen=" + md.length + " decLen=" + dec.length) }
                else android.util.Log.i("DbgMd", "decode bad mdLen=" + md.length + " ignore")
            }
        }
        mdCache.get(md)?.let {
            // 阶段5 bind 幂等: 池化复用同内容行时 tv 已上屏同 md 成品(非占位), 跳过重复 setParsedMarkdown
            // (每次 setParsedMarkdown 都会重建 span 树+触发 layout, 是同形态复用滑动的最大残余成本)
            if (tv.getTag(MainActivity.KEY_RENDER_MD) == md) {
                writeback?.invoke(it)
                return
            }
            // 阶段1: 内存缓存命中同样写回——prewarm/本进程渲染产物 DB 可能尚未落库,
            // 命中即视为"渲染完成", 幂等写回(内容相同会跳过)
            writeback?.invoke(it)
            if (sScrolling) {
                // 根治(09-27): 缓存命中路径滑动中同样 defer——直接 setParsedMarkdown 替换
                // 会产生占位→渲染高度突变(实测 delta=-755), 滑动中无人补偿会推挤视口=多气泡跳;
                // 挂起等 IDLE 统一替换+补偿
                // 修复(09-28 第三波): 挂起前必须先设纯文本占位(与长文本路径一致)——池化行复用后
                // tv 文本已被清空, 若保持空白, IDLE flush 的 text 校验(tv.text==md)必失败,
                // 替换被丢弃 = 正文永久空白气泡(用户实测: 只剩思考气泡, 正文全空白)
                val ph = mdPlaceholder(md)
                if (tv.text?.toString() != ph) tv.text = ph
                pendingMdReplacements.add {
                    if (tv.text?.toString() == ph) {
                        markwon.setParsedMarkdown(tv, it)
                        tv.setTag(MainActivity.KEY_RENDER_MD, md)
                    }
                    tryResumeBottomIfTrueBottom()
                    recordMdHeight(tv, md)
                }
                schedulePendingFlush()
            } else {
                markwon.setParsedMarkdown(tv, it)
                tv.setTag(MainActivity.KEY_RENDER_MD, md)
            }
            watchHeightDrift(tv)
            recordMdHeight(tv, md)
            return
        }
        if (md.length < 600) {   // 短文本同步渲染本就不卡, 直接走旧路径并回填缓存
            MdSpannable.tableMaxWidth = renderW
            synchronized(markwonRenderLock) { markwon.setMarkdown(tv, md) }
            watchHeightDrift(tv)
            (tv.text as? Spanned)?.let { mdCache.put(md, it); writeback?.invoke(it) }
            tv.setTag(MainActivity.KEY_RENDER_MD, md)
            recordMdHeight(tv, md)
            return
        }
        // 长文本未命中(预热未跑到: 切会话/冷启动后立刻上翻):
        // 09-28 一次到位三档处理:
        //  1) 高度缓存命中 -> 等高占位(minimumHeight=渲染高度), 异步替换瞬间高度不变=零跳变
        //  2) 无高度缓存且非滚动/非流式 -> 主线程同步渲染, 直接显示最终结果(消灭"过会儿气泡缩短")
        //  3) 滚动/流式中 -> 纯占位异步(旧路径), 替换高度突变由 drift watcher 补偿
        val ph = mdPlaceholder(md)
        val hKey = heightKey(md)
        val cachedH = mdHeightCache.get(hKey)
        if (cachedH != null) {
            // 档1: 等高占位
            tv.minimumHeight = cachedH
            tv.text = ph
            watchHeightDrift(tv)
            mdRenderPending.incrementAndGet()   // 占位已上屏, 渲染在途: 贴底判定冻结(09-27 修复2)
            mdExecutor.execute {
                MdSpannable.tableMaxWidth = renderW   // 阶段5: 异步现场渲染前注入同款表格宽度(与主线程现算一致)
                val spanned = try {
                    if (mdCache.get(md) == null) mdCache.put(md, synchronized(markwonRenderLock) { markwon.toMarkdown(md) })
                    mdCache.get(md)
                } catch (e: Exception) { null }
                if (spanned != null) runOnUiThread {
                    // holder 可能已被 RV 回收复用: 校验 tv 仍挂着本条占位文本才替换, 否则丢弃(幂等安全)
                    if (tv.text?.toString() != ph) { mdRenderPending.decrementAndGet(); return@runOnUiThread }
                    val replace = {
                        if (tv.text?.toString() == ph) {
                            markwon.setParsedMarkdown(tv, spanned)
                            tv.minimumHeight = 0   // 等高占位使命完成: 解除固定高度, 内容自然接管
                            tv.setTag(MainActivity.KEY_RENDER_MD, md)
                            writeback?.invoke(spanned)   // 阶段1: 渲染完成写回 rendered
                        }
                        mdRenderPending.decrementAndGet()   // 替换执行/丢弃均归还计数
                        tryResumeBottomIfTrueBottom()
                        recordMdHeight(tv, md)
                    }
                    if (sScrolling) {
                        // 滑动中 defer: 停止后 flush 统一替换, watchHeightDrift 补偿钉住阅读位置
                        pendingMdReplacements.add(replace)
                        schedulePendingFlush()
                    } else {
                        replace()
                    }
                } else {
                    mdRenderPending.decrementAndGet()   // 编译失败也归还计数(罕见)
                }
            }
            return
        }
        if (!sScrolling && !session.aiBusy) {
            // 档2: 首屏同步兜底——打开会话冷启动, 占位会带来"过会儿气泡缩短/跳变",
            // 直接主线程同步渲染显示最终结果, 从根上消灭占位→渲染高度突变;
            // 仅打开会话瞬间发生且 prewarm 通常已命中大部分, 少数同步渲染小卡可接受;
            // 滚动中/流式中不走(防掉帧)
            MdSpannable.tableMaxWidth = renderW   // 阶段5: 同步现场渲染前注入同款表格宽度
            synchronized(markwonRenderLock) { markwon.setMarkdown(tv, md) }
            watchHeightDrift(tv)
            (tv.text as? Spanned)?.let { mdCache.put(md, it); writeback?.invoke(it) }
            tv.setTag(MainActivity.KEY_RENDER_MD, md)
            recordMdHeight(tv, md)
            return
        }
        // 档3: 纯占位异步(滚动/流式中快速遇到未渲染长文, 旧路径)
        tv.minimumHeight = 0   // 防池化行复用残留上一轮等高占位的固定高度
        tv.text = ph
        watchHeightDrift(tv)
        mdRenderPending.incrementAndGet()   // 占位已上屏, 渲染在途: 贴底判定冻结(09-27 修复2)
        mdExecutor.execute {
            MdSpannable.tableMaxWidth = renderW   // 阶段5: 异步现场渲染前注入同款表格宽度(与主线程现算一致)
            val spanned = try {
                if (mdCache.get(md) == null) mdCache.put(md, synchronized(markwonRenderLock) { markwon.toMarkdown(md) })
                mdCache.get(md)
            } catch (e: Exception) { null }
            if (spanned != null) runOnUiThread {
                // holder 可能已被 RV 回收复用: 校验 tv 仍挂着本条占位文本才替换, 否则丢弃(幂等安全)
                if (tv.text?.toString() != ph) { mdRenderPending.decrementAndGet(); return@runOnUiThread }
                val replace = {
                    if (tv.text?.toString() == ph) {
                        markwon.setParsedMarkdown(tv, spanned)
                        tv.setTag(MainActivity.KEY_RENDER_MD, md)
                        writeback?.invoke(spanned)   // 阶段1: 渲染完成写回 rendered
                    }
                    mdRenderPending.decrementAndGet()   // 替换执行/丢弃均归还计数
                    tryResumeBottomIfTrueBottom()
                    recordMdHeight(tv, md)
                }
                if (sScrolling) {
                    // 根治(09-27): 滑动中不上屏替换——占位→markdown 高度突变(1500+px)在滑动中
                    // 无人补偿会推挤视口内多行内容=多气泡跳; 挂起到滚动停止统一替换, 停止后
                    // watchHeightDrift 正常补偿钉住阅读位置(与手势无打架)
                    // 注意: mdRenderPending 保持 >0, 渲染未上屏期间贴底判定继续冻结
                    pendingMdReplacements.add(replace)
                    schedulePendingFlush()
                } else {
                    replace()
                }
            } else {
                mdRenderPending.decrementAndGet()   // 编译失败也归还计数(罕见)
            }
        }
    }
    /** 阶段1: 渲染完成写回 rendered —— 内存 messages(下次落库携带) + DB 增量(防中途退出丢失)。
     *  ChatRow.id = sessionBaseSeq + msgIdx, msgIdx 与 messages 索引一一对应(全空消息同样占位) */
    internal fun MainActivity.writeBackRendered(seq: Long, spanned: Spanned) {
        val json = RenderedCodec.encode(spanned)
        if (json == null) { android.util.Log.w(TAG, "writeback encode null seq=$seq len=${spanned.length}"); return }
        if (json.isBlank()) return
        val idx = (seq - sessionBaseSeq).toInt()

        if (idx in messages.indices) {
            val m = messages[idx]
            if (m.rendered != json) {
                messages[idx] = m.copy(rendered = json, renderedVersion = RenderedCodec.VERSION)
                currentSessionId?.let { sid ->
                    try { db.updateRendered(sid, seq.toInt(), json, RenderedCodec.VERSION) }
                    catch (e: Exception) { android.util.Log.w(TAG, "db fail seq=$seq", e) }
                }
            }
        } else android.util.Log.w(TAG, "idx OOB seq=$seq idx=$idx")
    }
    /** 渲染替换完成/贴底后: 若用户曾上翻且此刻真贴底, 主动恢复自动追底(09-27) */
    internal fun MainActivity.tryResumeBottomIfTrueBottom() {
        // 替换完成瞬间高度已稳定(下一帧布局后): 若用户曾上翻且此刻真贴底,
        // 主动恢复自动追底; 在中间阅读位则守卫保持, 不被自动追底拉走
        if (scrollUserScrolled && !chatRec.canScrollVertically(1)) {
            chatRec.postOnAnimation {
                if (mdRenderPending.get() == 0 && !chatRec.canScrollVertically(1)) {
                    scrollUserScrolled = false
                    activeAiHolder?.resumeTypewriter()
                    updateJumpFab()
                }
            }
        }
    }
    internal fun MainActivity.watchHeightDrift(tv: TextView) {
        driftPreDraws.remove(tv)?.let { chatRec.viewTreeObserver.removeOnPreDrawListener(it) }
        driftAttaches.remove(tv)?.let { tv.removeOnAttachStateChangeListener(it) }
        var lastBottom = Int.MIN_VALUE
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (tv.height <= 0) return true
                val b = tv.bottom
                if (lastBottom != Int.MIN_VALUE && b != lastBottom) {
                    val delta = b - lastBottom
                    // 09-27 跳变修复2: 用户滑动/惯性中(DRAGGING/SETTLING)不做补偿——
                    // 滑动中 bind 历史长文(占位→渲染替换, delta 可达 1500+) 若仍 scrollBy,
                    // 会把正在滚动的列表猛推/拉回 = 录屏 8~9s 的上下反复跳变;
                    // 滚动中只更新基线, 停止后高度已稳定, 无增量自然不补, 位置即 RV 布局自然位
                    if (sScrolling) {
                        Log.d("DRIFTDBG", "skip-scrolling delta=" + delta + " b=" + b + " last=" + lastBottom + " watchers=" + driftPreDraws.size)
                        lastBottom = b; return true
                    }
                    // 09-27 终版(气泡顶跳回标题栏根因): 用户上翻历史浏览(scrollUserScrolled=true)时
                    // 阅读锚点是视口顶部, 底边钉住补偿的 scrollBy(+delta) 会把视口向底部猛拉
                    // (flush 时双气泡替换叠加实测 +1592px)= 气泡顶边从屏幕中间跳回标题栏下方;
                    // 历史浏览态不做补偿, RV 布局自然锚定首可见项顶, 替换气泡向下生长不扰动阅读位;
                    // 补偿仅保留给贴底追读态(scrollUserScrolled=false, 流式输出钉住最新气泡底边)
                    if (scrollUserScrolled) {
                        Log.d("DRIFTDBG", "skip-history delta=" + delta + " b=" + b + " last=" + lastBottom + " watchers=" + driftPreDraws.size)
                        lastBottom = b; return true
                    }
                    var row: android.view.View = tv
                    while (row.parent is android.view.View && row.parent !== chatRec) row = row.parent as android.view.View
                    if (row.parent === chatRec && row.top < chatRec.height && row.bottom > 0) {
                        // 09-28 修复: 占位→渲染大 delta(超长消息 8000px+) 直接 scrollBy 会
                        // 把视口猛推数屏(实测 +8005px≈4屏), 上翻历史时表现为列表跳动/气泡
                        // 异常("过一会儿气泡缩短"); 超过视口高度一半的 delta 视为内容级替换,
                        // 不做钉底补偿, 交由 RV 自然布局(气泡变高向下生长, 不扰动视口);
                        // 流式输出逐帧 delta 很小不受影响(贴底钉住语义保留)
                        val maxComp = chatRec.height / 2
                        if (delta > maxComp || delta < -maxComp) {
                            Log.d("DRIFTDBG", "skip-huge delta=" + delta + " b=" + b + " last=" + lastBottom + " h=" + chatRec.height)
                        } else {
                            Log.d("DRIFTDBG", "compensate delta=" + delta + " b=" + b + " last=" + lastBottom + " watchers=" + driftPreDraws.size)
                            chatRec.scrollBy(0, delta)
                        }
                    }
                }
                lastBottom = b
                return true
            }
        }
        val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                lastBottom = Int.MIN_VALUE   // 重置基线: 重挂后(如缓存行复用)表格会再调度二次测量
                chatRec.viewTreeObserver.addOnPreDrawListener(listener)
                driftPreDraws[tv] = listener   // 同步登记: detach 清 map 后 attach 复活 listener 仍需可去重
            }
            override fun onViewDetachedFromWindow(v: View) {
                chatRec.viewTreeObserver.removeOnPreDrawListener(listener)
                driftPreDraws.remove(tv)
                driftAttaches.remove(tv)
            }
        }
        driftPreDraws[tv] = listener
        driftAttaches[tv] = attach
        chatRec.viewTreeObserver.addOnPreDrawListener(listener)
        tv.addOnAttachStateChangeListener(attach)
    }
    internal fun MainActivity.prewarmMdCache() {
        // 纯文本模式不渲染 Markdown, 预热纯烧 CPU 还加剧会话切换卡顿
        if (ModeConfig.chatPlainText()) return
        val aiMsgs = messages.mapIndexedNotNull { i, m ->
            if (m.role != "user" && m.content.isNotBlank()) i to m else null
        }
        if (aiMsgs.isEmpty()) return
        val gen = ++mdPrewarmGen
        // 倒序拆成单消息粒度任务投并行池(最新→最旧): 3线程同时消化全量时间/3;
        // bind miss 的兜底任务与剩余预热并行执行, 不再排在整条预热循环之后数秒等待
        for ((idx, m) in aiMsgs.asReversed()) {
            mdExecutor.execute {
                if (gen != mdPrewarmGen) return@execute   // 已切走会话: 任务自杀
                // AiRich 分片路径与 contentRow 渲染一致(超长分片阈值), 用户消息不走 markwon
                val parts = if (m.content.length > SPLIT_CONTENT_LEN) splitLongContent(m.content) else listOf(m.content)
                // 阶段3 存量写回判定: 与 fillAiRich singleSeg 一致——timeline content 事件<=1 且
                // content 未分段时整条 rendered 的 span 区间才与单段文本匹配, 可安全写回落库
                val cEvts = if (m.timeline.isBlank()) 0 else runCatching {
                    parseTimeline(m.timeline).count { it[0] == "content" }
                }.getOrDefault(0)
                val writeableSeg = parts.size == 1 && cEvts <= 1 &&
                    (m.renderedVersion < 1 || m.rendered.isBlank())
                var segSpanned: Spanned? = null
                for (p in parts) {
                    val md = ModeConfig.stripChatProtocolPrefix(p.trim())
                    // 短文同步渲染本就不卡, 不走 prewarm 也不写回(bind 短文本路径已含 writeback)
                    if (md.length < 600) { segSpanned = null; break }
                    var spanned = mdCache.get(md)
                    if (spanned == null) {
                        // 阶段2 rendered 直读: DB 已有落库产物(renderedVersion>=1)时反序列化回填缓存,
                        // 打开已渲染会话零 markwon 解析(长文 markwon 最贵), decode 失败回退 markwon
                        if (m.renderedVersion >= 1 && m.rendered.isNotBlank()) {
                            try {
                                val dec = RenderedCodec.decode(m.rendered, markwon.configuration().theme(), mdTableTheme, resources.displayMetrics.density, chatMaxW())
                                if (dec != null) { mdCache.put(md, dec); spanned = dec }
                            } catch (e: Exception) { /* decode 失败回退 markwon 渲染 */ }
                        }
                        if (spanned == null) {
                            try {
                                spanned = synchronized(markwonRenderLock) { markwon.toMarkdown(md) }
                                mdCache.put(md, spanned)

                            } catch (e: Exception) { spanned = null }
                        }
                    } else {

                    }
                    if (spanned != null) {
                        segSpanned = spanned
                        enqueueMdMeasure(md, spanned)
                    } else { segSpanned = null; break }
                }
                // 阶段3: 单段长文且 DB 无 rendered → 渲染完成即写回(打开会话零等待, 防 updateSession 全量重写抹掉)
                if (writeableSeg && segSpanned != null) {
                    val sp = segSpanned!!
                    val seq = sessionBaseSeq + idx
                    runOnUiThread { writeBackRendered(seq.toLong(), sp) }
                }
            }
        }
    }
    /** 轻量预判 Markdown 表格语法(竖线表): 首行含 |, 次行为分隔行(|---|) */
    internal fun containsTableSyntax(md: String): Boolean {
        val lines = md.split('\n')
        if (lines.size < 2) return false
        for (i in 0 until lines.size - 1) {
            val l0 = lines[i].trim()
            if (l0.startsWith("|") && l0.count { it == '|' } >= 2) {
                val l1 = lines[i + 1].trim()
                if (l1.startsWith("|") && l1.count { it == '|' } >= 2 &&
                    l1.filter { it != '|' && it != '-' && it != ':' && it != ' ' }.isEmpty()) return true
            }
        }
        return false
    }

    /** 历史消息 -> 脉络时间线事件(think/tool), 供单行状态行摘要与展开渲染(09-24 新形态);
     *  优先 timeline 交错还原, 旧数据(无 timeline)回退 thinking/tools 扁平还原 */
    internal fun statusEventsOf(timelineJson: String, thinking: String, toolsJson: String): List<StatusEvent> {
        val out = ArrayList<StatusEvent>()
        parseTimeline(timelineJson).forEach { (type, text, name, arg, result) ->
            when (type) {
                "think" -> if (text.isNotBlank()) out.add(StatusEvent("think", text, "", "", ""))
                "tool" -> out.add(StatusEvent("tool", "", name, arg, result))
            }
        }
        if (out.isEmpty()) {
            if (thinking.isNotBlank()) out.add(StatusEvent("think", thinking, "", "", ""))
            parseTools(toolsJson).forEach { out.add(StatusEvent("tool", "", it.first, it.second, it.third)) }
        }
        return out
    }

    /** 长正文分片(阶段2 content 分片): 优先按段落边界切块, 每块不超过 SPLIT_CONTENT_LEN;
     *  单段落超长(无空行长文/大段代码)按字符硬切, 与流式 appendContent 自动封段阈值一致 */
    internal fun splitLongContent(raw: String): List<String> {
        if (raw.length <= SPLIT_CONTENT_LEN) return listOf(raw)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (para in raw.split("\n\n")) {
            if (sb.length + para.length + 2 > SPLIT_CONTENT_LEN && sb.isNotEmpty()) {
                out.add(sb.toString().trim())
                sb.setLength(0)
            }
            if (para.length > SPLIT_CONTENT_LEN) {
                // 单段落超长: 先清空缓冲, 再按字符硬切
                if (sb.isNotEmpty()) { out.add(sb.toString().trim()); sb.setLength(0) }
                var rest = para
                while (rest.length > SPLIT_CONTENT_LEN) {
                    out.add(rest.take(SPLIT_CONTENT_LEN))
                    rest = rest.drop(SPLIT_CONTENT_LEN)
                }
                sb.append(rest)
            } else {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(para)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out
    }

    /** 解析持久化的工具序列 JSON -> (name, arg, result) 列表 */
    internal fun parseTools(json: String): List<Triple<String, String, String>> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Triple(
                    o.optString("name", ""),
                    o.optString("arg", ""),
                    o.optString("result", "")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /** 解析交错时间线 JSON -> (type, text, arg, result) 列表; think 用 text, tool 用 name/arg/result */
    internal fun parseTimeline(json: String): List<Array<String>> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = o.optString("type", "")
                arrayOf(
                    type,
                    o.optString("text", ""),
                    o.optString("name", ""),
                    o.optString("arg", ""),
                    o.optString("result", "")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    internal fun MainActivity.schedulePendingFlush() {
        if (pendingFlushScheduled) return
        pendingFlushScheduled = true
        chatRec.postDelayed({
            pendingFlushScheduled = false
            if (!sScrolling && pendingMdReplacements.isNotEmpty()) {
                Log.d("DRIFTDBG", "flush-pending timeout n=" + pendingMdReplacements.size)
                flushPendingMdReplacements()
            }
        }, 1500)
    }

    internal fun MainActivity.flushPendingMdReplacements() {
        if (pendingMdReplacements.isEmpty() || sScrolling) return
        val n = minOf(FLUSH_BATCH, pendingMdReplacements.size)
        val pend = ArrayList<Runnable>(n)
        repeat(n) { pend.add(pendingMdReplacements.removeAt(0)) }
        for (r in pend) r.run()
        if (pendingMdReplacements.isNotEmpty()) {
            chatRec.post { flushPendingMdReplacements() }
        }
    }
