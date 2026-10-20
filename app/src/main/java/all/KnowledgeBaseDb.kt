package io.github.aixtin.nyral

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 知识库独立存储(2026-10-19): 独立 SQLite 库 knowledge.db, 与 memory.db / timer.db 零交集。
 * 结构: 书 → 章 → 条目; 每条目含 MD 正文/来源会话/版本号; 会话级游标支撑增量沉淀; 审计表留痕。
 * MVP 阶段1: 存储骨架 + 游标 + 审计; 软删标记就位供阶段3 回收区使用。
 */
class KnowledgeBaseDb(context: Context) : SQLiteOpenHelper(context, "knowledge.db", null, 1) {

    private val lock = ReentrantLock()

    override fun onCreate(db: SQLiteDatabase) {
        // 书: 一个讨论主题一本书
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS kb_books (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "keywords TEXT NOT NULL DEFAULT ''," +
                "summary TEXT NOT NULL DEFAULT ''," +
                "deleted INTEGER NOT NULL DEFAULT 0," +
                "created_ts INTEGER NOT NULL," +
                "updated_ts INTEGER NOT NULL)"
        )
        // 章: 章节目录(章名+关键词+摘要)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS kb_chapters (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "book_id INTEGER NOT NULL," +
                "title TEXT NOT NULL," +
                "keywords TEXT NOT NULL DEFAULT ''," +
                "summary TEXT NOT NULL DEFAULT ''," +
                "seq INTEGER NOT NULL DEFAULT 0," +
                "deleted INTEGER NOT NULL DEFAULT 0," +
                "created_ts INTEGER NOT NULL," +
                "updated_ts INTEGER NOT NULL)"
        )
        // 条目: 沉淀的知识正文(MD), 图片引用留位
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS kb_entries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "chapter_id INTEGER NOT NULL," +
                "title TEXT NOT NULL," +
                "content TEXT NOT NULL DEFAULT ''," +
                "img_refs TEXT NOT NULL DEFAULT ''," +
                "source_session_id INTEGER NOT NULL DEFAULT 0," +
                "source_msg_ids TEXT NOT NULL DEFAULT ''," +
                "version INTEGER NOT NULL DEFAULT 1," +
                "deleted INTEGER NOT NULL DEFAULT 0," +
                "created_ts INTEGER NOT NULL," +
                "updated_ts INTEGER NOT NULL)"
        )
        // 游标: 每会话已沉淀到的消息边界(幂等推进)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS kb_cursors (" +
                "session_id INTEGER PRIMARY KEY," +
                "book_id INTEGER NOT NULL DEFAULT 0," +
                "last_msg_id INTEGER NOT NULL DEFAULT 0," +
                "updated_ts INTEGER NOT NULL)"
        )
        // 审计: 变更留痕(谁/何时/做了什么)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS kb_audit (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "entity_type TEXT NOT NULL," +
                "entity_id INTEGER NOT NULL DEFAULT 0," +
                "action TEXT NOT NULL," +
                "summary TEXT NOT NULL DEFAULT ''," +
                "ts INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---------- 书 ----------
    data class Book(val id: Long, val title: String, val keywords: String, val summary: String, val deleted: Boolean, val createdTs: Long, val updatedTs: Long)

    fun createBook(title: String, keywords: String = "", summary: String = ""): Long = lock.withLock {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.execSQL("INSERT INTO kb_books(title, keywords, summary, created_ts, updated_ts) VALUES(?,?,?,?,?)",
            arrayOf(title, keywords, summary, now.toString(), now.toString()))
        val id = lastId(db)
        audit(db, "book", id, "create", "新建书: $title")
        id
    }

    fun listBooks(includeDeleted: Boolean = false): List<Book> = lock.withLock {
        val cond = if (includeDeleted) "" else " WHERE deleted = 0"
        readableDatabase.rawQuery("SELECT id,title,keywords,summary,deleted,created_ts,updated_ts FROM kb_books$cond ORDER BY updated_ts DESC, id DESC", null).use { c ->
            val out = ArrayList<Book>()
            while (c.moveToNext()) out.add(bookRow(c))
            out
        }
    }

    /** 按标题找书(话题归并): 归一化后精确匹配; 无则尝试包含匹配; 都没有返回 null */
    fun findBookByTitle(title: String): Book? = lock.withLock {
        val norm = title.trim()
        if (norm.isEmpty()) return null
        readableDatabase.rawQuery(
            "SELECT id,title,keywords,summary,deleted,created_ts,updated_ts FROM kb_books WHERE deleted=0 AND title=?",
            arrayOf(norm)).use { c ->
            if (c.moveToFirst()) return bookRow(c)
        }
        // 包含匹配兜底: 书名互相包含视为同一话题(如"Kotlin协程" vs "协程")
        readableDatabase.rawQuery(
            "SELECT id,title,keywords,summary,deleted,created_ts,updated_ts FROM kb_books WHERE deleted=0 AND (title LIKE ? OR ? LIKE title)",
            arrayOf("%$norm%", norm)).use { c ->
            if (c.moveToFirst()) return bookRow(c)
        }
        null
    }

    fun getBook(id: Long): Book? = lock.withLock {
        readableDatabase.rawQuery("SELECT id,title,keywords,summary,deleted,created_ts,updated_ts FROM kb_books WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) bookRow(c) else null
        }
    }

    fun updateBook(id: Long, title: String, keywords: String = "", summary: String = "") {
        lock.withLock {
            writableDatabase.execSQL("UPDATE kb_books SET title=?, keywords=?, summary=?, updated_ts=? WHERE id=?",
                arrayOf(title, keywords, summary, System.currentTimeMillis().toString(), id.toString()))
            audit(writableDatabase, "book", id, "update", "更新书: $title")
        }
    }

    /** 沉淀落库后刷新书更新时间(驱动书列表按最近更新倒序), 仅改时间戳不审计 */
    fun touchBook(id: Long) {
        lock.withLock {
            writableDatabase.execSQL("UPDATE kb_books SET updated_ts=? WHERE id=?", arrayOf(System.currentTimeMillis().toString(), id.toString()))
        }
    }

    fun softDeleteBook(id: Long) {
        lock.withLock {
            val db = writableDatabase
            db.execSQL("UPDATE kb_books SET deleted=1, updated_ts=? WHERE id=?", arrayOf(System.currentTimeMillis().toString(), id.toString()))
            audit(db, "book", id, "delete", "移入回收区")
        }
    }

    fun restoreBook(id: Long) {
        lock.withLock {
            val db = writableDatabase
            db.execSQL("UPDATE kb_books SET deleted=0, updated_ts=? WHERE id=?", arrayOf(System.currentTimeMillis().toString(), id.toString()))
            audit(db, "book", id, "restore", "从回收区恢复")
        }
    }

    // ---------- 章 ----------
    data class Chapter(val id: Long, val bookId: Long, val title: String, val keywords: String, val summary: String, val seq: Int, val deleted: Boolean, val createdTs: Long, val updatedTs: Long)

    fun createChapter(bookId: Long, title: String, keywords: String = "", summary: String = ""): Long = lock.withLock {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val seq = db.rawQuery("SELECT COALESCE(MAX(seq),0)+1 FROM kb_chapters WHERE book_id=? AND deleted=0", arrayOf(bookId.toString())).use { if (it.moveToFirst()) it.getInt(0) else 1 }
        db.execSQL("INSERT INTO kb_chapters(book_id,title,keywords,summary,seq,created_ts,updated_ts) VALUES(?,?,?,?,?,?,?)",
            arrayOf(bookId.toString(), title, keywords, summary, seq.toString(), now.toString(), now.toString()))
        val id = lastId(db)
        audit(db, "chapter", id, "create", "新建章: $title")
        id
    }

    fun listChapters(bookId: Long, includeDeleted: Boolean = false): List<Chapter> = lock.withLock {
        val cond = if (includeDeleted) "" else " AND deleted = 0"
        readableDatabase.rawQuery("SELECT id,book_id,title,keywords,summary,seq,deleted,created_ts,updated_ts FROM kb_chapters WHERE book_id=?$cond ORDER BY updated_ts DESC, id DESC", arrayOf(bookId.toString())).use { c ->
            val out = ArrayList<Chapter>()
            while (c.moveToNext()) out.add(chapterRow(c))
            out
        }
    }

    fun updateChapter(id: Long, title: String, keywords: String = "", summary: String = "") {
        lock.withLock {
            writableDatabase.execSQL("UPDATE kb_chapters SET title=?, keywords=?, summary=?, updated_ts=? WHERE id=?",
                arrayOf(title, keywords, summary, System.currentTimeMillis().toString(), id.toString()))
            audit(writableDatabase, "chapter", id, "update", "更新章: $title")
        }
    }

    fun softDeleteChapter(id: Long) {
        lock.withLock {
            val db = writableDatabase
            db.execSQL("UPDATE kb_chapters SET deleted=1, updated_ts=? WHERE id=?", arrayOf(System.currentTimeMillis().toString(), id.toString()))
            audit(db, "chapter", id, "delete", "移入回收区")
        }
    }

    fun restoreChapter(id: Long) {
        lock.withLock {
            val db = writableDatabase
            db.execSQL("UPDATE kb_chapters SET deleted=0, updated_ts=? WHERE id=?", arrayOf(System.currentTimeMillis().toString(), id.toString()))
            audit(db, "chapter", id, "restore", "从回收区恢复")
        }
    }

    // ---------- 条目 ----------
    data class Entry(val id: Long, val chapterId: Long, val title: String, val content: String, val imgRefs: String, val sourceSessionId: Long, val sourceMsgIds: String, val version: Int, val deleted: Boolean, val createdTs: Long, val updatedTs: Long)

    fun createEntry(chapterId: Long, title: String, content: String, sourceSessionId: Long = 0L, sourceMsgIds: String = ""): Long = lock.withLock {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.execSQL("INSERT INTO kb_entries(chapter_id,title,content,source_session_id,source_msg_ids,created_ts,updated_ts) VALUES(?,?,?,?,?,?,?)",
            arrayOf(chapterId.toString(), title, content, sourceSessionId.toString(), sourceMsgIds, now.toString(), now.toString()))
        val id = lastId(db)
        audit(db, "entry", id, "create", "新建条目: $title")
        id
    }

    fun listEntries(chapterId: Long, includeDeleted: Boolean = false): List<Entry> = lock.withLock {
        val cond = if (includeDeleted) "" else " AND deleted = 0"
        readableDatabase.rawQuery("SELECT id,chapter_id,title,content,img_refs,source_session_id,source_msg_ids,version,deleted,created_ts,updated_ts FROM kb_entries WHERE chapter_id=?$cond ORDER BY created_ts DESC, id DESC", arrayOf(chapterId.toString())).use { c ->
            val out = ArrayList<Entry>()
            while (c.moveToNext()) out.add(entryRow(c))
            out
        }
    }

    fun getEntry(id: Long): Entry? = lock.withLock {
        readableDatabase.rawQuery("SELECT id,chapter_id,title,content,img_refs,source_session_id,source_msg_ids,version,deleted,created_ts,updated_ts FROM kb_entries WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) entryRow(c) else null
        }
    }

    /** 更新条目: 版本号 +1, 留痕 */
    fun updateEntry(id: Long, title: String, content: String) {
        lock.withLock {
            val db = writableDatabase
            val old = getEntry(id)
            db.execSQL("UPDATE kb_entries SET title=?, content=?, version=version+1, updated_ts=? WHERE id=?",
                arrayOf(title, content, System.currentTimeMillis().toString(), id.toString()))
            val ver = if (old != null) " (v${old.version}→v${old.version + 1})" else ""
            audit(db, "entry", id, "update", "更新条目: $title$ver")
        }
    }

    /** 软删除(回收区): 标记不物理删, 可恢复 */
    fun softDeleteEntry(id: Long) {
        lock.withLock {
            val db = writableDatabase
            db.execSQL("UPDATE kb_entries SET deleted=1, updated_ts=? WHERE id=?", arrayOf(System.currentTimeMillis().toString(), id.toString()))
            audit(db, "entry", id, "delete", "移入回收区")
        }
    }

    fun restoreEntry(id: Long) {
        lock.withLock {
            val db = writableDatabase
            db.execSQL("UPDATE kb_entries SET deleted=0, updated_ts=? WHERE id=?", arrayOf(System.currentTimeMillis().toString(), id.toString()))
            audit(db, "entry", id, "restore", "从回收区恢复")
        }
    }

    fun deletedEntries(): List<Entry> = lock.withLock {
        readableDatabase.rawQuery("SELECT id,chapter_id,title,content,img_refs,source_session_id,source_msg_ids,version,deleted,created_ts,updated_ts FROM kb_entries WHERE deleted = 1 ORDER BY updated_ts DESC", null).use { c ->
            val out = ArrayList<Entry>()
            while (c.moveToNext()) out.add(entryRow(c))
            out
        }
    }

    // ---------- 展示层统计(阶段3, 2026-10-19) ----------
    fun getChapter(id: Long): Chapter? = lock.withLock {
        readableDatabase.rawQuery("SELECT id,book_id,title,keywords,summary,seq,deleted,created_ts,updated_ts FROM kb_chapters WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) chapterRow(c) else null
        }
    }

    fun chapterCount(bookId: Long): Int = lock.withLock {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM kb_chapters WHERE book_id=? AND deleted=0", arrayOf(bookId.toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun entryCount(chapterId: Long): Int = lock.withLock {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM kb_entries WHERE chapter_id=? AND deleted=0", arrayOf(chapterId.toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun entryCountOfBook(bookId: Long): Int = lock.withLock {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM kb_entries e JOIN kb_chapters c ON e.chapter_id=c.id WHERE c.book_id=? AND e.deleted=0 AND c.deleted=0",
            arrayOf(bookId.toString())).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 彻底删除条目(回收区): 物理删除 + 审计留痕 */
    fun purgeEntry(id: Long) {
        lock.withLock {
            val db = writableDatabase
            val old = getEntry(id)
            db.delete("kb_entries", "id=?", arrayOf(id.toString()))
            audit(db, "entry", id, "purge", "彻底删除条目: ${old?.title ?: id}")
        }
    }

    // ---------- 游标(增量沉淀幂等推进) ----------
    data class KbCursor(val sessionId: Long, val bookId: Long, val lastMsgId: Long, val updatedTs: Long)

    fun getCursor(sessionId: Long): KbCursor? = lock.withLock {
        readableDatabase.rawQuery("SELECT session_id,book_id,last_msg_id,updated_ts FROM kb_cursors WHERE session_id=?", arrayOf(sessionId.toString())).use { c ->
            if (c.moveToFirst()) KbCursor(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)) else null
        }
    }

    /** 游标前进: 同一会话仅推进不倒退(幂等) */
    fun advanceCursor(sessionId: Long, bookId: Long, lastMsgId: Long) {
        lock.withLock {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val cur = getCursor(sessionId)
            if (cur == null) {
                db.execSQL("INSERT INTO kb_cursors(session_id,book_id,last_msg_id,updated_ts) VALUES(?,?,?,?)",
                    arrayOf(sessionId.toString(), bookId.toString(), lastMsgId.toString(), now.toString()))
            } else {
                db.execSQL("UPDATE kb_cursors SET book_id=?, last_msg_id=MAX(last_msg_id,?), updated_ts=? WHERE session_id=?",
                    arrayOf(bookId.toString(), lastMsgId.toString(), now.toString(), sessionId.toString()))
            }
        }
    }

    // ---------- 审计 ----------
    data class Audit(val id: Long, val entityType: String, val entityId: Long, val action: String, val summary: String, val ts: Long)

    fun audits(entityType: String? = null, entityId: Long? = null, limit: Int = 100): List<Audit> = lock.withLock {
        val sb = StringBuilder("SELECT id,entity_type,entity_id,action,summary,ts FROM kb_audit")
        val args = ArrayList<String>()
        if (entityType != null) { sb.append(" WHERE entity_type=?"); args.add(entityType) }
        if (entityId != null) {
            sb.append(if (entityType != null) " AND" else " WHERE").append(" entity_id=?")
            args.add(entityId.toString())
        }
        sb.append(" ORDER BY id DESC LIMIT ").append(limit)
        readableDatabase.rawQuery(sb.toString(), args.toTypedArray()).use { c ->
            val out = ArrayList<Audit>()
            while (c.moveToNext()) out.add(Audit(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getString(4), c.getLong(5)))
            out
        }
    }

    // ---------- 工具 ----------
    private fun lastId(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT last_insert_rowid()", null).use { if (it.moveToFirst()) it.getLong(0) else -1L }

    private fun audit(db: SQLiteDatabase, entityType: String, entityId: Long, action: String, summary: String) {
        db.execSQL("INSERT INTO kb_audit(entity_type,entity_id,action,summary,ts) VALUES(?,?,?,?,?)",
            arrayOf(entityType, entityId.toString(), action, summary, System.currentTimeMillis().toString()))
    }

    private fun bookRow(c: Cursor) = Book(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1, c.getLong(5), c.getLong(6))
    private fun chapterRow(c: Cursor) = Chapter(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5), c.getInt(6) == 1, c.getLong(7), c.getLong(8))
    private fun entryRow(c: Cursor) = Entry(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getString(6), c.getInt(7), c.getInt(8) == 1, c.getLong(9), c.getLong(10))
}
