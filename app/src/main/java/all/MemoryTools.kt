package io.github.aixtin.nyral

import org.mozilla.javascript.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MemoryTools {

    @Volatile
    private var db: MemoryDb? = null

    @Volatile
    private var embedder: MemoryEmbedder? = null

    /** 由 MainActivity 注册: 返回当前会话标题, 记忆落库时快照会话名 */
    @Volatile
    var sessionTitleProvider: (() -> String?)? = null

    private fun ensureInit(context: android.content.Context) {
        if (db == null) {
            synchronized(this) {
                if (db == null) {
                    val appCtx = context.applicationContext
                    db = MemoryDb(appCtx)
                    embedder = MemoryEmbedder(appCtx)
                }
            }
        }
    }

    fun getTime(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
    }

    private val NUMERIC_ONLY = Regex("[0-9+\\-*/().\\s]+")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_EXPR_CHARS = 1500
    fun calc(expr: String): String {
        // 简化求值, 仅支持数字和四则运算
        val cleaned = expr.replace("×", "*").replace("÷", "/").replace("x", "*")
        // 2026-11-07 缺陷3: Rhino IRFactory 对超长表达式递归建树会爆栈(6000 项加法实测把进程打崩),
        // 按"压缩空白后的有效字符数"设上限: 空白无语义也不增递归深度, 不该占额度
        val compact = cleaned.replace(WHITESPACE, "")
        return when {
            !cleaned.matches(NUMERIC_ONLY) -> "仅支持数字和四则运算"
            compact.length > MAX_EXPR_CHARS -> "表达式过长(上限 $MAX_EXPR_CHARS 字符)，请拆成多步计算"
            else -> evaluate(cleaned)
        }
    }

    private fun evaluate(cleaned: String): String = try {
        val cx = Context.enter()
        try {
            // Android 上 Rhino 必须用解释模式(-1), 否则动态字节码生成报"无法加载类文件"
            cx.optimizationLevel = -1
            val scope = ScriptEngine.secureScope(cx)
            cx.evaluateString(scope, cleaned, "calc", 1, null).toString()
        } finally {
            Context.exit()
        }
    } catch (e: Exception) {
        "计算失败: ${e.message}"
    }

    /** 关键词兜底命中的固定相关度(低于语义命中的常见分值, 排序时自然排在语义结果之后) */
    // 余弦阈值门: 校准完成前保持 false, 只埋点不拦截 RECALLSRC_V1
    private const val COS_GATE_ENABLED = false
    private const val MIN_COS = 0.55f
    private const val KEYWORD_ONLY_SCORE = 0.30f

    // ===== 本轮检索来源记录(甲方案: 仅内存, 重启即失效) ===== RECALLSRC_V1
    data class Recalled(val memId: Long, val score: Float, val keywordOnly: Boolean, val snippet: String)
    private val roundHits = LinkedHashMap<Long, Recalled>()

    /** 最近一次检索的最高余弦分(供 MemoryGate 判断是否值得注入) RECALLSRC_V1 */
    @Volatile
    var lastTopScore = 0f
        private set

    @Synchronized
    fun beginRound() {
        roundHits.clear()
    }

    @Synchronized
    fun collectRound(list: List<Recalled>) {
        for (r in list) if (!roundHits.containsKey(r.memId)) roundHits[r.memId] = r
    }

    @Synchronized
    fun takeRound(): List<Recalled> {
        val out = roundHits.values.toList()
        roundHits.clear()
        return out
    }


    /**
     * 双路召回: 语义相似 top3 + 关键词 LIKE 兜底。
     * v14: 出口带 #id 与相关度, 按相关度主序(旧版按时间重排会把"哪条最相关"抹平)
     */
    fun search(context: android.content.Context, query: String): String {
        return try {
            ensureInit(context)
            val e = embedder!!
            val d = db!!
            val kwHits = d.searchKeyword(query.trim().take(10), 3)

            // id -> (记忆, 相关度, 是否语义命中)
            val merged = LinkedHashMap<Long, Triple<MemoryDb.Mem, Float, Boolean>>()
            // 语义召回仅当本地模型就绪; 未就绪自动降级为关键词召回(不阻塞记忆检索)
            if (e.isReady()) {
                try {
                    val qv = e.embed(query)
                    val scored = d.allCached().map { mem ->
                        mem to e.cosine(qv, mem.embedding)
                    }.sortedWith(
                        compareByDescending<Pair<MemoryDb.Mem, Float>> { it.second }
                            .thenByDescending { it.first.ts }
                    )
                    for ((mem, score) in scored.take(3)) {
                        merged[mem.id] = Triple(mem, score, true)
                    }
                } catch (ex: Exception) {
                    ModelUpdater.ensureDownloaded()
                }
            }
            for (mem in kwHits) {
                if (!merged.containsKey(mem.id)) merged[mem.id] = Triple(mem, KEYWORD_ONLY_SCORE, false)
            }

            if (merged.isEmpty()) return "记忆中暂无相关内容。"
            // 相关度主序: 语义命中在前, 关键词兜底在后; 同分再按时间新->旧
            val ordered = merged.values.sortedWith(
                compareByDescending<Triple<MemoryDb.Mem, Float, Boolean>> { it.second }
                    .thenByDescending { it.first.ts }
            )
            val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val sb = StringBuilder(
                "相关记忆(按相关度排序, 越靠前越相关; 同主题多条以时间最新为准, 旧版未被覆盖的细节仍有效; #编号为该记忆的全局 id):\n"
            )
            val gated = if (COS_GATE_ENABLED) ordered.filter { it.third || it.second >= MIN_COS } else ordered
            val _top = ordered.maxOfOrNull { it.second } ?: 0f
            val _min = ordered.minOfOrNull { it.second } ?: 0f
            lastTopScore = _top
            LogStore.i(LogStore.MEM, "[recall] q=" + query.take(20) + " hits=" + gated.size + "/" + ordered.size +
                " top=" + String.format(java.util.Locale.US, "%.3f", _top) +
                " min=" + String.format(java.util.Locale.US, "%.3f", _min) +
                " gate=" + (if (COS_GATE_ENABLED) "on" else "off"))
            // 记录本轮命中, 供长按弹窗展示来源 RECALLSRC_V1
            collectRound(gated.map { item ->
                Recalled(item.first.id, item.second, !item.third,
                    item.first.content.take(24).replace("\n", " "))
            })
            gated.forEachIndexed { i, item ->
                val mem = item.first
                sb.append("${i + 1}. #${mem.id} [${df.format(Date(mem.ts))}] ")
                if (item.third) sb.append("(相关度 ").append(String.format(Locale.US, "%.2f", item.second)).append(") ")
                sb.append(mem.content).append("\n")
            }
            sb.toString().trim()
        } catch (e: Exception) {
            "记忆检索失败: ${e.message}"
        }
    }

    fun save(context: android.content.Context, content: String): String {
        return try {
            ensureInit(context)
            val e = embedder!!
            val d = db!!
            if (!e.isReady()) {
                // v2.9.7: 不再有 assets 兜底, 首次安装下载期间保存会走到这里
                ModelUpdater.ensureDownloaded()
                return "语义模型正在准备(后台下载中), 请稍后再保存这条记忆。"
            }
            val v = e.embed(content)
            d.add(content, v, sessionTitleProvider?.invoke())
            "已保存到记忆 (共${d.count()}条): $content"
        } catch (e: Exception) {
            "记忆保存失败: ${e.message}"
        }
    }
}
