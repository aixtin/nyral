package io.github.aixtin.nyral

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 路径沉淀库(2026-10-11, 阶段3): 任务指纹 → 成功工具序列 → 命中复用。
 * 独立 pattern.db(version 1), 不影响 memory.db 版本号, 可独立回滚。
 * 防污染: 仅在任务实际调用工具时记录; 正样本加 ok_count, 失败加 fail_count 降权。
 */
class PatternDb(context: Context) : SQLiteOpenHelper(context, "pattern.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS patterns (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "signature TEXT NOT NULL," +
                "tool_seq TEXT NOT NULL," +
                "ok_count INTEGER NOT NULL DEFAULT 0," +
                "fail_count INTEGER NOT NULL DEFAULT 0," +
                "hit_count INTEGER NOT NULL DEFAULT 0," +
                "updated_ts INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_patterns_sig ON patterns(signature)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

object PatternStore {

    /** 容量上限: 防止 pattern.db 无限膨胀; 超出后按价值清理(见 prune) */
    private const val MAX_PATTERNS = 500

    private var db: PatternDb? = null
    private val lock = ReentrantLock()

    private fun db(c: Context): PatternDb {
        if (db == null) db = PatternDb(c.applicationContext)
        return db!!
    }

    data class Match(val toolSeq: List<String>, val okCount: Int, val failCount: Int)

    /** 从 buildHistory 文本提取最后一条用户消息(兼容 user[23:05]: 与 user: 两种格式, 跳过占位/空行) */
    fun lastUserText(history: String): String {
        val lines = history.split('\n')
        for (i in lines.indices.reversed()) {
            val line = lines[i].trim()
            if (!line.startsWith("user")) continue
            val content = when {
                line.contains("]: ") -> line.substring(line.indexOf("]: ") + 3)
                line.contains(": ") -> line.substring(line.indexOf(": ") + 2)
                else -> continue
            }.trim()
            if (content.isEmpty() || content == "(历史工具调用记录)") continue
            return content
        }
        return history.takeLast(200)
    }

    /** 任务指纹: 用户最近一条消息归一化(小写/去标点空白/截前 24 字符) */
    fun signatureOf(text: String): String {
        val norm = text.lowercase()
            .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
            .trim()
        return norm.take(24)
    }

    /** 命中: 指纹前 12 字符前缀匹配, 返回成功率最高的路径; 无命中返回 null */
    fun match(context: Context, sig: String): Match? {
        if (sig.length < 4) return null
        val prefix = sig.take(12)
        lock.withLock {
            return try {
                db(context).readableDatabase.rawQuery(
                    "SELECT tool_seq, ok_count, fail_count FROM patterns " +
                        "WHERE signature LIKE ? " +
                        "ORDER BY (ok_count * 1.0 / (ok_count + fail_count + 1)) DESC, hit_count DESC LIMIT 1",
                    arrayOf("$prefix%")
                ).use { c ->
                    if (c.moveToFirst()) {
                        val seq = try {
                            val arr = JSONArray(c.getString(0))
                            (0 until arr.length()).map { arr.getString(it) }
                        } catch (e: Exception) { emptyList() }
                        if (seq.isEmpty()) null else Match(seq, c.getInt(1), c.getInt(2))
                    } else null
                }
            } catch (e: Exception) { null }
        }
    }

    /** 命中计数(观察用, 不参与排序权重) */
    fun bumpHit(context: Context, sig: String) {
        if (sig.length < 4) return
        lock.withLock {
            try {
                db(context).writableDatabase.execSQL(
                    "UPDATE patterns SET hit_count = hit_count + 1 WHERE signature LIKE ?",
                    arrayOf("${sig.take(12)}%")
                )
            } catch (e: Exception) {}
        }
    }

    /** 记账: 同指纹+同序列聚合计数; 正样本 ok_count++, 失败 fail_count++ */
    fun record(context: Context, sig: String, toolSeq: List<String>, ok: Boolean) {
        if (sig.length < 4 || toolSeq.isEmpty()) return
        val seqJson = JSONArray().apply { toolSeq.forEach { put(it) } }.toString()
        lock.withLock {
            try {
                val d = db(context).writableDatabase
                val exists = d.rawQuery(
                    "SELECT id FROM patterns WHERE signature = ? AND tool_seq = ? LIMIT 1",
                    arrayOf(sig, seqJson)
                ).use { it.moveToFirst() }
                if (exists) {
                    d.execSQL(
                        "UPDATE patterns SET ok_count = ok_count + ?, fail_count = fail_count + ?, updated_ts = ? " +
                            "WHERE signature = ? AND tool_seq = ?",
                        arrayOf(if (ok) 1 else 0, if (ok) 0 else 1, System.currentTimeMillis(), sig, seqJson)
                    )
                } else {
                    d.execSQL(
                        "INSERT INTO patterns(signature, tool_seq, ok_count, fail_count, hit_count, updated_ts) VALUES(?,?,?,?,0,?)",
                        arrayOf(sig, seqJson, if (ok) 1 else 0, if (ok) 0 else 1, System.currentTimeMillis())
                    )
                }
            } catch (e: Exception) { /* 沉淀失败不影响主流程 */ }
        }
        prune(context)
    }

    /** 清理低价值记录: 总量超 MAX_PATTERNS 时, 删除失败占比高且命中少的旧记录, 保留高价值 top */
    private fun prune(context: Context) {
        lock.withLock {
            try {
                val d = db(context).writableDatabase
                val cnt = d.rawQuery("SELECT COUNT(*) FROM patterns", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                if (cnt <= MAX_PATTERNS) return
                // 第一轮: 删除负价值记录(失败>=成功); 若仍超限, 按价值升序删最旧
                d.execSQL(
                    "DELETE FROM patterns WHERE fail_count >= ok_count " +
                        "AND id NOT IN (SELECT id FROM patterns ORDER BY (ok_count * 10 + hit_count) DESC, updated_ts DESC LIMIT ?)",
                    arrayOf(MAX_PATTERNS.toString())
                )
                val cnt2 = d.rawQuery("SELECT COUNT(*) FROM patterns", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                if (cnt2 > MAX_PATTERNS) {
                    d.execSQL(
                        "DELETE FROM patterns WHERE id NOT IN " +
                            "(SELECT id FROM patterns ORDER BY (ok_count * 10 + hit_count) DESC, updated_ts DESC LIMIT ?)",
                        arrayOf(MAX_PATTERNS.toString())
                    )
                }
            } catch (e: Exception) { /* 清理失败不影响主流程 */ }
        }
    }
}
