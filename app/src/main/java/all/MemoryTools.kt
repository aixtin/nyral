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

    fun calc(expr: String): String {
        return try {
            // 简化求值, 仅支持数字和四则运算
            val cleaned = expr.replace("×", "*").replace("÷", "/").replace("x", "*")
            if (!cleaned.matches(Regex("[0-9+\\-*/().\\s]+"))) return "仅支持数字和四则运算"
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
    }

    /**
     * 双路召回: 语义相似 top3 + 关键词 LIKE 兜底
     */
    fun search(context: android.content.Context, query: String): String {
        return try {
            ensureInit(context)
            val e = embedder!!
            val d = db!!
            val kwHits = d.searchKeyword(query.trim().take(10), 3)

            val seen = HashSet<Long>()
            val merged = ArrayList<MemoryDb.Mem>()
            // 语义召回仅当本地模型就绪; 未就绪自动降级为关键词召回(不阻塞记忆检索)
            if (e.isReady()) {
                try {
                    val qv = e.embed(query)
                    val scored = d.all().map { mem ->
                        Triple(mem, e.cosine(qv, mem.embedding), mem.ts)
                    }.sortedWith(compareByDescending<Triple<MemoryDb.Mem, Float, Long>> { it.second }.thenByDescending { it.third })
                    for ((mem, _, _) in scored.take(3)) {
                        if (seen.add(mem.id)) merged.add(mem)
                    }
                } catch (ex: Exception) {
                    ModelUpdater.ensureDownloaded()
                }
            }
            for (mem in kwHits) {
                if (seen.add(mem.id)) merged.add(mem)
            }

            if (merged.isEmpty()) return "记忆中暂无相关内容。"
            // 冲突消解: 同主题多条记忆按时间新->旧注入, 并提示采用最新版本、旧版未覆盖细节仍有效
            val ordered = merged.sortedByDescending { it.ts }
            val df = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val sb = StringBuilder("相关记忆(多条相似记忆以时间最新为准, 旧版中未被新版覆盖的细节仍有效, 按时间新->旧):\n")
            ordered.forEachIndexed { i, mem ->
                sb.append("${i + 1}. [${df.format(Date(mem.ts))}] ${mem.content}\n")
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
            val v = e.embed(content)
            d.add(content, v, sessionTitleProvider?.invoke())
            "已保存到记忆 (共${d.count()}条): $content"
        } catch (e: Exception) {
            "记忆保存失败: ${e.message}"
        }
    }
}
