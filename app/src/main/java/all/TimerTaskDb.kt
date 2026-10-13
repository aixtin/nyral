package io.github.aixtin.nyral

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 定时岗位任务表(2026-10-11): 独立 SQLite 库 timer.db, 避免升级 memory.db 主库版本。
 * MVP 字段: 任务名/目标/触发描述/开关/上次执行时间/上次结果摘要。
 */
class TimerTaskDb(context: Context) : SQLiteOpenHelper(context, "timer.db", null, 3) {

    private val lock = ReentrantLock()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS timer_tasks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "goal TEXT NOT NULL," +
                "cron_desc TEXT NOT NULL," +
                "enabled INTEGER NOT NULL DEFAULT 1," +
                "last_run_ts INTEGER NOT NULL DEFAULT 0," +
                "last_result TEXT," +
                "session_id INTEGER NOT NULL DEFAULT 0," +
                "mode INTEGER NOT NULL DEFAULT 0," +
                "allow_tools TEXT NOT NULL DEFAULT ''," +
                "created_ts INTEGER NOT NULL," +
                "updated_ts INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE timer_tasks ADD COLUMN session_id INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE timer_tasks ADD COLUMN mode INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // 能力预授权(2026-10-13): 逗号分隔的危险工具 key 白名单, 静默执行命中即放行
            db.execSQL("ALTER TABLE timer_tasks ADD COLUMN allow_tools TEXT NOT NULL DEFAULT ''")
        }
    }

    data class Task(
        val id: Long,
        val name: String,
        val goal: String,
        val cronDesc: String,
        val enabled: Boolean,
        val lastRunTs: Long,
        val lastResult: String?,
        val sessionId: Long = 0L,
        val mode: Int = 0,
        val allowTools: String = ""
    )

    fun insert(name: String, goal: String, cronDesc: String, mode: Int = 0, allowTools: String = ""): Long {
        lock.withLock {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            db.execSQL(
                "INSERT INTO timer_tasks(name, goal, cron_desc, enabled, mode, allow_tools, created_ts, updated_ts) VALUES(?, ?, ?, 1, ?, ?, ?, ?)",
                arrayOf(name, goal, cronDesc, mode.toString(), allowTools, now.toString(), now.toString())
            )
            return db.rawQuery("SELECT last_insert_rowid()", null).use {
                if (it.moveToFirst()) it.getLong(0) else -1L
            }
        }
    }

    fun update(id: Long, name: String, goal: String, cronDesc: String, allowTools: String = "") {
        lock.withLock {
            writableDatabase.execSQL(
                "UPDATE timer_tasks SET name = ?, goal = ?, cron_desc = ?, allow_tools = ?, updated_ts = ? WHERE id = ?",
                arrayOf(name, goal, cronDesc, allowTools, System.currentTimeMillis().toString(), id.toString())
            )
        }
    }

    /** 能力预授权(2026-10-13): 单独更新危险工具白名单 */
    fun updateAllowTools(id: Long, allowTools: String) {
        lock.withLock {
            writableDatabase.execSQL(
                "UPDATE timer_tasks SET allow_tools = ?, updated_ts = ? WHERE id = ?",
                arrayOf(allowTools, System.currentTimeMillis().toString(), id.toString())
            )
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        lock.withLock {
            writableDatabase.execSQL(
                "UPDATE timer_tasks SET enabled = ?, updated_ts = ? WHERE id = ?",
                arrayOf(if (enabled) 1 else 0, System.currentTimeMillis().toString(), id.toString())
            )
        }
    }

    /** 绑定专属会话(2026-10-13): 任务创建时建会话并回写 session_id; 旧任务首次触发时补绑 */
    fun bindSession(id: Long, sessionId: Long) {
        lock.withLock {
            writableDatabase.execSQL(
                "UPDATE timer_tasks SET session_id = ?, updated_ts = ? WHERE id = ?",
                arrayOf(sessionId.toString(), System.currentTimeMillis().toString(), id.toString())
            )
        }
    }

    fun setLastRun(id: Long, ts: Long, result: String) {
        lock.withLock {
            writableDatabase.execSQL(
                "UPDATE timer_tasks SET last_run_ts = ?, last_result = ?, updated_ts = ? WHERE id = ?",
                arrayOf(ts.toString(), result, System.currentTimeMillis().toString(), id.toString())
            )
        }
    }

    fun delete(id: Long) {
        lock.withLock {
            writableDatabase.execSQL("DELETE FROM timer_tasks WHERE id = ?", arrayOf(id.toString()))
        }
    }

    fun get(id: Long): Task? {
        lock.withLock {
            return readableDatabase.rawQuery(
                "SELECT id, name, goal, cron_desc, enabled, last_run_ts, last_result, session_id, mode, allow_tools FROM timer_tasks WHERE id = ?",
                arrayOf(id.toString())
            ).use { c ->
                if (c.moveToFirst()) row(c) else null
            }
        }
    }

    fun all(): List<Task> {
        lock.withLock {
            return readableDatabase.rawQuery(
                "SELECT id, name, goal, cron_desc, enabled, last_run_ts, last_result, session_id, mode, allow_tools FROM timer_tasks ORDER BY id DESC",
                null
            ).use { c ->
                val out = ArrayList<Task>()
                while (c.moveToNext()) out.add(row(c))
                out
            }
        }
    }

    private fun row(c: android.database.Cursor) = Task(
        id = c.getLong(0),
        name = c.getString(1),
        goal = c.getString(2),
        cronDesc = c.getString(3),
        enabled = c.getInt(4) == 1,
        lastRunTs = c.getLong(5),
        lastResult = c.getString(6),
        sessionId = c.getLong(7),
        mode = c.getInt(8),
        allowTools = c.getString(9)
    )
}
