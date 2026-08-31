package io.github.aixtin.droidagent

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MemoryDb(context: Context) : SQLiteOpenHelper(context, "memory.db", null, 7) {

    companion object {
        const val DIM = 512
    }

    private val lock = ReentrantLock()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE memory (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "content TEXT NOT NULL," +
                "ts INTEGER NOT NULL," +
                "embedding BLOB NOT NULL," +
                "session_title TEXT)"
        )
        db.execSQL("CREATE INDEX idx_memory_ts ON memory(ts)")
        db.execSQL(
            "CREATE TABLE summary (" +
                "id INTEGER PRIMARY KEY," +
                "content TEXT NOT NULL," +
                "ts INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE pending (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "role TEXT NOT NULL," +
                "content TEXT NOT NULL," +
                "ts INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL," +
                "pinned INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE session_msgs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "session_id INTEGER NOT NULL," +
                "role TEXT NOT NULL," +
                "content TEXT NOT NULL," +
                "seq INTEGER NOT NULL," +
                "tools TEXT)"
        )
        db.execSQL("CREATE INDEX idx_sms_sid ON session_msgs(session_id)")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // 幂等兜底：每次打开都检查 session_msgs.thinking 列，缺失则补齐
        val cols = db.rawQuery("PRAGMA table_info(session_msgs)", null)
        var hasThinking = false
        var hasTools = false
        while (cols.moveToNext()) {
            when (cols.getString(1)) {
                "thinking" -> hasThinking = true
                "tools" -> hasTools = true
            }
        }
        cols.close()
        if (!hasThinking) {
            db.execSQL("ALTER TABLE session_msgs ADD COLUMN thinking TEXT")
        }
        // 幂等兜底：tools 列保存 AI 工具调用序列(name/arg/result)，缺失则补齐
        if (!hasTools) {
            db.execSQL("ALTER TABLE session_msgs ADD COLUMN tools TEXT")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS summary (" +
                    "id INTEGER PRIMARY KEY," +
                    "content TEXT NOT NULL," +
                    "ts INTEGER NOT NULL)"
            )
        }
        if (oldVersion < 3) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS pending (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "role TEXT NOT NULL," +
                    "content TEXT NOT NULL," +
                    "ts INTEGER NOT NULL)"
            )
        }
        if (oldVersion < 4) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS sessions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "title TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS session_msgs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "session_id INTEGER NOT NULL," +
                    "role TEXT NOT NULL," +
                    "content TEXT NOT NULL," +
                    "seq INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sms_sid ON session_msgs(session_id)")
        }
        if (oldVersion < 5) {
            // session_msgs 新增 thinking 列，保存 AI 思考内容
            val cols = db.rawQuery("PRAGMA table_info(session_msgs)", null)
            var hasThinking = false
            while (cols.moveToNext()) {
                if (cols.getString(1) == "thinking") {
                    hasThinking = true
                    break
                }
            }
            cols.close()
            if (!hasThinking) {
                db.execSQL("ALTER TABLE session_msgs ADD COLUMN thinking TEXT")
            }
        }
        if (oldVersion < 6) {
            // sessions 新增 pinned 置顶标记；memory 新增 session_title 会话名快照
            val sessCols = db.rawQuery("PRAGMA table_info(sessions)", null)
            var hasPinned = false
            while (sessCols.moveToNext()) {
                if (sessCols.getString(1) == "pinned") {
                    hasPinned = true
                    break
                }
            }
            sessCols.close()
            if (!hasPinned) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
            val memCols = db.rawQuery("PRAGMA table_info(memory)", null)
            var hasSessionTitle = false
            while (memCols.moveToNext()) {
                if (memCols.getString(1) == "session_title") {
                    hasSessionTitle = true
                    break
                }
            }
            memCols.close()
            if (!hasSessionTitle) {
                db.execSQL("ALTER TABLE memory ADD COLUMN session_title TEXT")
            }
        }
        if (oldVersion < 7) {
            // session_msgs 新增 tools 工具调用序列列(v7)
            val msgCols = db.rawQuery("PRAGMA table_info(session_msgs)", null)
            var hasTools = false
            while (msgCols.moveToNext()) {
                if (msgCols.getString(1) == "tools") {
                    hasTools = true
                    break
                }
            }
            msgCols.close()
            if (!hasTools) {
                db.execSQL("ALTER TABLE session_msgs ADD COLUMN tools TEXT")
            }
        }
    }

    data class Mem(val id: Long, val content: String, val ts: Long, val embedding: FloatArray, val sessionTitle: String?)

    fun add(content: String, embedding: FloatArray, sessionTitle: String? = null) {
        lock.withLock {
            val db = writableDatabase
            val bytes = FloatArrayToBytes(embedding)
            db.execSQL(
                "INSERT INTO memory(content, ts, embedding, session_title) VALUES(?, ?, ?, ?)",
                arrayOf(content, System.currentTimeMillis(), bytes, sessionTitle)
            )
        }
    }

    /**
     * 原子消费 pending：同一事务内"原文入库(带 content 去重) + 按 id 删除已消费的 pending"。
     * 要么全部成功(队列清空)，要么整体回滚(队列保留可重试)。
     * 修复 bug: 旧逻辑原文先入库、索引失败后 pending 未清，导致同一批 pending 被反复归档，
     * memory 表无限叠加相同内容(实测 2850 条相同记忆)。
     */
    fun consumePending(ids: List<Long>, chunks: List<Pair<String, FloatArray>>, sessionTitle: String? = null) {
        lock.withLock {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for ((content, vec) in chunks) {
                    // content 去重: 库中已存在完全相同内容则跳过, 防止重复叠加
                    val exists = db.rawQuery(
                        "SELECT 1 FROM memory WHERE content = ? LIMIT 1",
                        arrayOf(content)
                    ).use { it.moveToFirst() }
                    if (!exists) {
                        db.execSQL(
                            "INSERT INTO memory(content, ts, embedding, session_title) VALUES(?, ?, ?, ?)",
                            arrayOf(content, System.currentTimeMillis(), FloatArrayToBytes(vec), sessionTitle)
                        )
                    }
                }
                if (ids.isNotEmpty()) {
                    val ph = ids.joinToString(",") { "?" }
                    db.execSQL("DELETE FROM pending WHERE id IN ($ph)", ids.toTypedArray())
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** 删除单条记忆 */
    fun delete(id: Long) {
        lock.withLock {
            writableDatabase.execSQL("DELETE FROM memory WHERE id = ?", arrayOf(id.toString()))
        }
    }

    /** 批量删除指定 id 的记忆（事务保证原子性） */
    fun deleteMany(ids: List<Long>) {
        if (ids.isEmpty()) return
        lock.withLock {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (chunk in ids.chunked(500)) {
                    val ph = chunk.joinToString(",") { "?" }
                    db.execSQL("DELETE FROM memory WHERE id IN ($ph)", chunk.map { it.toString() }.toTypedArray())
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** 清空全部记忆 */
    fun clearAll() {
        lock.withLock {
            writableDatabase.execSQL("DELETE FROM memory")
        }
    }

    /** 修改单条记忆: 更新内容 + 语义向量 + 时间戳 */
    fun update(id: Long, content: String, embedding: FloatArray) {
        lock.withLock {
            writableDatabase.execSQL(
                "UPDATE memory SET content = ?, embedding = ?, ts = ? WHERE id = ?",
                arrayOf(content, FloatArrayToBytes(embedding), System.currentTimeMillis(), id.toString())
            )
        }
    }

    fun all(): List<Mem> {
        lock.withLock {
            val db = readableDatabase
            val list = mutableListOf<Mem>()
            db.rawQuery("SELECT id, content, ts, embedding, session_title FROM memory ORDER BY ts DESC", null).use { c ->
                while (c.moveToNext()) {
                    list.add(Mem(
                        id = c.getLong(0),
                        content = c.getString(1),
                        ts = c.getLong(2),
                        embedding = BytesToFloatArray(c.getBlob(3)),
                        sessionTitle = if (c.isNull(4)) null else c.getString(4)
                    ))
                }
            }
            return list
        }
    }

    fun searchKeyword(keyword: String, limit: Int = 5): List<Mem> {
        lock.withLock {
            val db = readableDatabase
            val list = mutableListOf<Mem>()
            db.rawQuery(
                "SELECT id, content, ts, embedding, session_title FROM memory WHERE content LIKE ? ORDER BY ts DESC LIMIT ?",
                arrayOf("%$keyword%", limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    list.add(Mem(
                        id = c.getLong(0),
                        content = c.getString(1),
                        ts = c.getLong(2),
                        embedding = BytesToFloatArray(c.getBlob(3)),
                        sessionTitle = if (c.isNull(4)) null else c.getString(4)
                    ))
                }
            }
            return list
        }
    }

    fun count(): Int {
        lock.withLock {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM memory", null).use { c ->
                c.moveToFirst(); return c.getInt(0)
            }
        }
    }

    /** 中期记忆: 保存/覆盖对话摘要(单条, id=1) */
    fun saveSummary(content: String) {
        lock.withLock {
            val db = writableDatabase
            db.execSQL(
                "INSERT OR REPLACE INTO summary(id, content, ts) VALUES(1, ?, ?)",
                arrayOf(content, System.currentTimeMillis())
            )
        }
    }

    fun loadSummary(): String? {
        lock.withLock {
            val db = readableDatabase
            db.rawQuery("SELECT content FROM summary WHERE id=1", null).use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
            return null
        }
    }

    /** 待压缩队列(持久化): 消息实时落盘, 防进程被杀丢记忆 */
    fun addPending(role: String, content: String) {
        lock.withLock {
            writableDatabase.execSQL(
                "INSERT INTO pending(role, content, ts) VALUES(?, ?, ?)",
                arrayOf(role, content, System.currentTimeMillis())
            )
        }
    }

    data class Pending(val id: Long, val role: String, val content: String)

    fun pendingAll(): List<Pending> {
        lock.withLock {
            val db = readableDatabase
            val list = mutableListOf<Pending>()
            db.rawQuery("SELECT id, role, content FROM pending ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    list.add(Pending(c.getLong(0), c.getString(1), c.getString(2)))
                }
            }
            return list
        }
    }

    fun pendingCount(): Int {
        lock.withLock {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM pending", null).use { c ->
                c.moveToFirst(); return c.getInt(0)
            }
        }
    }

    fun pendingLastTs(): Long {
        lock.withLock {
            readableDatabase.rawQuery("SELECT MAX(ts) FROM pending", null).use { c ->
                if (c.moveToFirst()) return c.getLong(0)
            }
            return -1L
        }
    }

    fun clearPending() {
        lock.withLock {
            writableDatabase.execSQL("DELETE FROM pending")
        }
    }

    private fun FloatArrayToBytes(fa: FloatArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(fa.size * 4)
        bb.asFloatBuffer().put(fa)
        return bb.array()
    }

    private fun BytesToFloatArray(bytes: ByteArray): FloatArray {
        val bb = java.nio.ByteBuffer.wrap(bytes)
        val fa = FloatArray(bytes.size / 4)
        bb.asFloatBuffer().get(fa)
        return fa
    }

    // ===================== 多会话 =====================

    data class SessionInfo(val id: Long, val title: String, val updatedAt: Long, val pinned: Boolean)

    /** 会话消息: role / content(正文) / thinking(AI 思考) / tools(工具调用序列 JSON) */
    data class SessionMsg(val role: String, val content: String, val thinking: String = "", val tools: String = "")

    /** 保存当前会话为新会话，返回 session id；assistant 消息附思考内容(可空) */
    fun saveSession(title: String, msgs: List<SessionMsg>): Long {
        lock.withLock {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val id = db.insert("sessions", null,
                    android.content.ContentValues().apply {
                        put("title", title)
                        put("updated_at", System.currentTimeMillis())
                    })
                msgs.forEachIndexed { i, m ->
                    db.insert("session_msgs", null,
                        android.content.ContentValues().apply {
                            put("session_id", id)
                            put("role", m.role)
                            put("content", m.content)
                            put("thinking", m.thinking.ifBlank { null })
                            put("tools", m.tools.ifBlank { null })
                            put("seq", i)
                        })
                }
                db.setTransactionSuccessful()
                return id
            } finally {
                db.endTransaction()
            }
        }
    }

    /** 列出会话（置顶优先，其次按更新时间倒序） */
    fun listSessions(limit: Int = 20): List<SessionInfo> {
        lock.withLock {
            val out = mutableListOf<SessionInfo>()
            readableDatabase.rawQuery(
                "SELECT id, title, updated_at, pinned FROM sessions ORDER BY pinned DESC, updated_at DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(SessionInfo(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3) != 0))
                }
            }
            return out
        }
    }

    /** 设置/取消会话置顶 */
    fun setPinned(id: Long, pinned: Boolean) {
        lock.withLock {
            writableDatabase.execSQL(
                "UPDATE sessions SET pinned = ? WHERE id = ?",
                arrayOf(if (pinned) 1 else 0, id)
            )
        }
    }

    /** 查询单个会话标题（用于记忆写入时快照会话名） */
    fun sessionTitleOf(id: Long): String? {
        lock.withLock {
            readableDatabase.rawQuery(
                "SELECT title FROM sessions WHERE id = ?",
                arrayOf(id.toString())
            ).use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
            return null
        }
    }

    /** 删除会话及其全部消息 */
    fun deleteSession(id: Long) {
        lock.withLock {
            val db = writableDatabase
            db.execSQL("DELETE FROM session_msgs WHERE session_id = ?", arrayOf(id.toString()))
            db.execSQL("DELETE FROM sessions WHERE id = ?", arrayOf(id.toString()))
        }
    }

    /** 更新已存在会话：标题、时间戳，并整体重写消息 */
    fun updateSession(id: Long, title: String, msgs: List<SessionMsg>) {
        lock.withLock {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM session_msgs WHERE session_id = ?", arrayOf(id.toString()))
                db.execSQL(
                    "UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?",
                    arrayOf(title, System.currentTimeMillis(), id)
                )
                msgs.forEachIndexed { i, m ->
                    db.insert("session_msgs", null,
                        android.content.ContentValues().apply {
                            put("session_id", id)
                            put("role", m.role)
                            put("content", m.content)
                            put("thinking", m.thinking.ifBlank { null })
                            put("tools", m.tools.ifBlank { null })
                            put("seq", i)
                        })
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** 读取某个会话的全部消息 (role, content, thinking, tools) */
    fun loadSessionMessages(id: Long): List<SessionMsg> {
        lock.withLock {
            val out = mutableListOf<SessionMsg>()
            readableDatabase.rawQuery(
                "SELECT role, content, thinking, tools FROM session_msgs WHERE session_id = ? ORDER BY seq ASC",
                arrayOf(id.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(SessionMsg(
                        c.getString(0),
                        c.getString(1),
                        if (c.isNull(2)) "" else c.getString(2),
                        if (c.isNull(3)) "" else c.getString(3)
                    ))
                }
            }
            return out
        }
    }

    /** 更新会话标题（以首条用户消息为准） */
    fun renameSession(id: Long, title: String) {
        lock.withLock {
            writableDatabase.execSQL(
                "UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?",
                arrayOf(title, System.currentTimeMillis(), id)
            )
        }
    }

    // ===================== 会话全文搜索 =====================

    /** 单条搜索命中：matchedField 0=正文(content) 1=AI思考(thinking) */
    data class SearchHit(
        val sessionId: Long,
        val title: String,
        val updatedAt: Long,
        val role: String,
        val seq: Int,
        val content: String,
        val thinking: String,
        val matchedField: Int,
        val score: Int
    )

    /**
     * 会话全文搜索：content 与 thinking 分别 LIKE 匹配（大小写不敏感），JOIN sessions 取标题/时间。
     * 相关性打分在 Java 侧完成（命中位置/次数/连续命中），结果按得分降序为主、时间降序兜底。
     */
    fun searchChatMessages(keyword: String, limit: Int = 60): List<SearchHit> {
        lock.withLock {
            val db = readableDatabase
            val out = mutableListOf<SearchHit>()
            val kw = keyword.trim()
            if (kw.isEmpty()) return out
            // 正文(content) 命中
            db.rawQuery(
                "SELECT m.session_id, s.title, s.updated_at, m.role, m.seq, m.content, m.thinking " +
                    "FROM session_msgs m JOIN sessions s ON s.id = m.session_id " +
                    "WHERE m.content LIKE ?",
                arrayOf("%$kw%")
            ).use { c ->
                while (c.moveToNext()) {
                    val content = c.getString(5)
                    val sc = scoreOf(content, kw)
                    if (sc <= 0) continue
                    out.add(SearchHit(
                        sessionId = c.getLong(0),
                        title = c.getString(1),
                        updatedAt = c.getLong(2),
                        role = c.getString(3),
                        seq = c.getInt(4),
                        content = content,
                        thinking = if (c.isNull(6)) "" else c.getString(6),
                        matchedField = 0,
                        score = sc
                    ))
                }
            }
            // AI思考(thinking) 命中
            db.rawQuery(
                "SELECT m.session_id, s.title, s.updated_at, m.role, m.seq, m.content, m.thinking " +
                    "FROM session_msgs m JOIN sessions s ON s.id = m.session_id " +
                    "WHERE m.thinking LIKE ?",
                arrayOf("%$kw%")
            ).use { c ->
                while (c.moveToNext()) {
                    val thinking = if (c.isNull(6)) "" else c.getString(6)
                    val sc = scoreOf(thinking, kw)
                    if (sc <= 0) continue
                    out.add(SearchHit(
                        sessionId = c.getLong(0),
                        title = c.getString(1),
                        updatedAt = c.getLong(2),
                        role = c.getString(3),
                        seq = c.getInt(4),
                        content = if (c.isNull(5)) "" else c.getString(5),
                        thinking = thinking,
                        matchedField = 1,
                        score = sc
                    ))
                }
            }
            // 排序: 得分降序为主, 时间降序兜底
            out.sortWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.updatedAt })
            return out.take(limit)
        }
    }

    /** 相关性打分: 命中位置越靠前分越高 + 命中次数 + 连续命中 + 关键词越长权重越高 */
    private fun scoreOf(text: String, kw: String): Int {
        if (kw.isEmpty()) return 0
        var idx = text.indexOf(kw, ignoreCase = true)
        if (idx < 0) return 0
        var score = 0
        var hits = 0
        var prevEnd = -1
        while (idx >= 0) {
            hits++
            // 位置加分: 命中越靠前(距开头越近)分越高
            score += ((text.length - idx) / 40).coerceAtLeast(10)
            // 连续命中加分: 与上一次命中紧邻(间距<=关键词长)视为连续
            if (prevEnd >= 0 && idx - prevEnd <= kw.length) score += 90
            prevEnd = idx + kw.length
            score += kw.length.coerceAtMost(30)
            idx = text.indexOf(kw, prevEnd, ignoreCase = true)
        }
        score += hits * 50
        return score
    }
}
