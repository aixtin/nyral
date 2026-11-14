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
class TimerTaskDb(context: Context) : SQLiteOpenHelper(context, "timer.db", null, 5) {

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
                "last_slot_ts INTEGER NOT NULL DEFAULT 0," +
                "created_ts INTEGER NOT NULL," +
                "updated_ts INTEGER NOT NULL)"
        )
        createRunsTable(db)
    }

    /**
     * 执行台账表(2026-11-14, 第四批)。
     * 为什么单独一张表而不是往 timer_tasks 上加字段: 任务行只存"最后一次结果", 丢掉了历史;
     * 而"到底有没有按时跑"这个问题必须靠时间序列回答(昨晚那次是没触发, 还是触发后失败了?)。
     * task_id 不做外键级联: 任务删除后台账保留, 否则"删了任务就查不到它老失败的证据"。
     */
    private fun createRunsTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS timer_runs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "task_id INTEGER NOT NULL DEFAULT 0," +
                "slot_ts INTEGER NOT NULL DEFAULT 0," +
                "start_ts INTEGER NOT NULL DEFAULT 0," +
                "end_ts INTEGER NOT NULL DEFAULT 0," +
                "ok INTEGER NOT NULL DEFAULT 0," +
                "catch_up INTEGER NOT NULL DEFAULT 0," +
                "result TEXT," +
                "created_ts INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_runs_start ON timer_runs(start_ts)")
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
        if (oldVersion < 4) {
            // 第二批(2026-11-14): 最近一次已认领的触发 slot, 与单行 UPDATE 配合做原子占位去重
            db.execSQL("ALTER TABLE timer_tasks ADD COLUMN last_slot_ts INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 5) {
            // 第四批(2026-11-14): 执行台账, 记录每次真实执行(含补跑)的起止/成败/摘要
            createRunsTable(db)
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
        val allowTools: String = "",
        /** 第二批(2026-11-14): 最近一次已认领的触发 slot, 0 表示尚未认领过 */
        val lastSlotTs: Long = 0L
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

    /**
     * 原子占位(2026-11-14, 第二批): 单行 UPDATE 同时完成"校验 + 认领", 返回 true 表示本次投递拿到执行权。
     * 条件 last_slot_ts < slotTs 保证同一 slot 只被认领一次 —— 即便闹钟被重复投递两次、
     * 或 Receiver 与服务各走一遍, 第二次 UPDATE 影响 0 行, 直接被判重复。SQLite 单条 UPDATE 天然串行,
     * 无需显式事务。原型: momoding ScheduledTaskManager 的 store.update(taskId){...} 单步取号。
     */
    fun tryClaimSlot(id: Long, slotTs: Long): Boolean {
        if (slotTs <= 0L) return false
        lock.withLock {
            return try {
                val rows = writableDatabase.update(
                    "timer_tasks",
                    android.content.ContentValues().apply { put("last_slot_ts", slotTs) },
                    "id = ? AND last_slot_ts < ?",
                    arrayOf(id.toString(), slotTs.toString())
                )
                rows > 0
            } catch (e: Exception) {
                android.util.Log.e("TimerTaskDb", "占位失败 id=" + id + " slot=" + slotTs + ": " + e.message)
                false
            }
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
                "SELECT id, name, goal, cron_desc, enabled, last_run_ts, last_result, session_id, mode, allow_tools, last_slot_ts FROM timer_tasks WHERE id = ?",
                arrayOf(id.toString())
            ).use { c ->
                if (c.moveToFirst()) row(c) else null
            }
        }
    }

    fun all(): List<Task> {
        lock.withLock {
            return readableDatabase.rawQuery(
                "SELECT id, name, goal, cron_desc, enabled, last_run_ts, last_result, session_id, mode, allow_tools, last_slot_ts FROM timer_tasks ORDER BY id DESC",
                null
            ).use { c ->
                val out = ArrayList<Task>()
                while (c.moveToNext()) out.add(row(c))
                out
            }
        }
    }

    /** 台账单行 */
    data class RunRecord(
        val id: Long,
        val taskId: Long,
        val slotTs: Long,
        val startTs: Long,
        val endTs: Long,
        val ok: Boolean,
        val catchUp: Boolean,
        val result: String?
    )

    /**
     * 追加一条执行台账(2026-11-14, 第四批)。
     * slotTs 由调用方从任务行的 lastSlotTs 取回 —— 认领时已由 TimerDispatch 写入, 这里不再重算,
     * 避免"记的 slot 和跑的 slot 不是同一个"导致去重判断错位。
     */
    fun recordRun(taskId: Long, slotTs: Long, startTs: Long, ok: Boolean, catchUp: Boolean, result: String?) {
        lock.withLock {
            try {
                val now = System.currentTimeMillis()
                writableDatabase.execSQL(
                    "INSERT INTO timer_runs(task_id, slot_ts, start_ts, end_ts, ok, catch_up, result, created_ts) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf(taskId.toString(), slotTs.toString(), startTs.toString(), now.toString(),
                        if (ok) "1" else "0", if (catchUp) "1" else "0",
                        result?.take(300), now.toString())
                )
                // 只留最近 200 条: 按每日 1 次算够看半年, 又不至于让库无限增长
                writableDatabase.execSQL(
                    "DELETE FROM timer_runs WHERE id NOT IN (SELECT id FROM timer_runs ORDER BY id DESC LIMIT 200)"
                )
            } catch (e: Exception) {
                android.util.Log.w("TimerTaskDb", "写执行台账失败: " + e.message)
            }
        }
    }

    /**
     * 运行中占位(2026-11-15): 认领 slot 成功、真正开跑之前先落一行 ok=-1。
     *
     * 修的是什么: 原先只有 onDone/onError 才会写台账, 一旦进程在执行途中被系统杀掉
     * (灭屏 Doze 内存回收、厂商清理、FGS 配额到期), 那一轮既没有 timer_runs 记录、
     * 也没有 last_run_ts 更新, 但 slot 早已被 tryClaimSlot 认领 —— 三者叠加后,
     * 任务看起来"从未运行", 且补跑判据把已认领当"跑过", 这一次就永久丢了。
     * (真机 2026-11-14 08:00 冕宁早报即此形态: last_slot=11-14 08:00, last_run 停在 11-13, 无产物无台账)
     *
     * @return 台账行 id; 失败返回 -1, 调用方退回"只写终态一行"的老路径
     */
    fun beginRun(taskId: Long, slotTs: Long, startTs: Long, catchUp: Boolean): Long {
        lock.withLock {
            return try {
                val now = System.currentTimeMillis()
                val cv = android.content.ContentValues().apply {
                    put("task_id", taskId)
                    put("slot_ts", slotTs)
                    put("start_ts", startTs)
                    put("end_ts", 0L)
                    put("ok", -1) // -1 = 运行中/未收尾
                    put("catch_up", if (catchUp) 1 else 0)
                    put("created_ts", now)
                }
                writableDatabase.insert("timer_runs", null, cv)
            } catch (e: Exception) {
                android.util.Log.w("TimerTaskDb", "写运行占位失败: " + e.message)
                -1L
            }
        }
    }

    /**
     * 收尾(2026-11-15): 把 beginRun 落的占位行改成终态。
     * runId<=0(占位失败或老调用点)时退回一次性追加, 保证任何路径都有终态台账。
     */
    fun finishRun(
        runId: Long, taskId: Long, slotTs: Long, startTs: Long,
        ok: Boolean, catchUp: Boolean, result: String?
    ) {
        if (runId <= 0L) {
            recordRun(taskId, slotTs, startTs, ok, catchUp, result)
            return
        }
        lock.withLock {
            try {
                val now = System.currentTimeMillis()
                writableDatabase.execSQL(
                    "UPDATE timer_runs SET end_ts = ?, ok = ?, result = ? WHERE id = ?",
                    arrayOf(now.toString(), if (ok) "1" else "0", result?.take(300), runId.toString())
                )
                writableDatabase.execSQL(
                    "DELETE FROM timer_runs WHERE id NOT IN (SELECT id FROM timer_runs ORDER BY id DESC LIMIT 200)"
                )
            } catch (e: Exception) {
                android.util.Log.w("TimerTaskDb", "收尾台账失败: " + e.message)
            }
        }
    }

    /**
     * 该 slot 是否已有终态台账(ok=0/1)。
     * ok=-1 的占位行不算 —— 它就是"认领了但没跑完"的证据, 补跑判据要靠它区分真假执行。
     */
    fun hasFinishedRun(taskId: Long, slotTs: Long): Boolean {
        if (slotTs <= 0L) return false
        lock.withLock {
            return try {
                readableDatabase.rawQuery(
                    "SELECT 1 FROM timer_runs WHERE task_id = ? AND slot_ts = ? AND ok >= 0 LIMIT 1",
                    arrayOf(taskId.toString(), slotTs.toString())
                ).use { it.moveToFirst() }
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * 强制重认领(2026-11-15): 供"僵尸认领"补跑使用。
     * tryClaimSlot 的 last_slot_ts < slot 条件会把"已被占但没跑"的 slot 永久挡住,
     * 补跑必须能重新拿到执行权, 因此这里不加条件直接写回。
     */
    fun reclaimSlot(id: Long, slotTs: Long): Boolean {
        if (slotTs <= 0L) return false
        lock.withLock {
            return try {
                val rows = writableDatabase.update(
                    "timer_tasks",
                    android.content.ContentValues().apply { put("last_slot_ts", slotTs) },
                    "id = ? AND last_slot_ts <= ?",
                    arrayOf(id.toString(), slotTs.toString())
                )
                rows > 0
            } catch (e: Exception) {
                android.util.Log.e("TimerTaskDb", "重认领失败 id=" + id + ": " + e.message)
                false
            }
        }
    }

    /** 近 N 天的执行记录(新->旧), 供体检页统计成败 */
    fun recentRuns(days: Int): List<RunRecord> {
        val since = System.currentTimeMillis() - days.toLong() * 24L * 3600_000L
        lock.withLock {
            return try {
                readableDatabase.rawQuery(
                    "SELECT id, task_id, slot_ts, start_ts, end_ts, ok, catch_up, result FROM timer_runs WHERE start_ts >= ? ORDER BY id DESC",
                    arrayOf(since.toString())
                ).use { c ->
                    val out = ArrayList<RunRecord>()
                    while (c.moveToNext()) {
                        out.add(RunRecord(
                            id = c.getLong(0), taskId = c.getLong(1), slotTs = c.getLong(2),
                            startTs = c.getLong(3), endTs = c.getLong(4),
                            ok = c.getInt(5) == 1, catchUp = c.getInt(6) == 1, result = c.getString(7)
                        ))
                    }
                    out
                }
            } catch (e: Exception) {
                ArrayList<RunRecord>()
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
        allowTools = c.getString(9),
        lastSlotTs = c.getLong(10)
    )
}
