package io.github.aixtin.nyral

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 可观测性 L1 事件(2026-11-18, P0 对账侧)
 *
 * 原则: 只观测"我们自己的代码有没有把事办成", 不观测别人的东西为什么办不成。
 * 对账侧(TimerReconciler)判定出"该跑没跑 / 跑了但结果不对"后调用 emit() 落本地队列,
 * 由本对象负责"不丢 + 批量发"两件事。
 *
 * 链路:
 *   事件 -> filesDir/obs_queue/evt_<ts>_<seq>.ndjson(一条事件一个文件, 每文件自身即一份可发送 body)
 *        -> 攒满 50 条 或 攒满 5 分钟 批量 POST -> 服务端按首行 "type: evt" 分流落盘 evt_*.txt
 *        -> 服务端返回 200 才删本地文件
 *   弱网 / 进程被杀 / 服务端 5xx: 文件留在队列, 下次冷启动或下个 5 分钟周期继续发。
 *
 * 容量闭环: 队列目录硬上限 5000 文件 / 30MB, 超限丢最旧; 与 event 30MB 的容量预算一致。
 * 去重: dedupKey 命中已发集合则直接丢弃, 保证同一槽位同一结论不重复上报(对账可反复扫)。
 */
object ObsEvent {
    private const val TAG = "ObsEvent"

    /** 与崩溃上报同一个接收端, 服务端按 body 首行 type: 分流 */
    private const val UPLOAD_URL = "https://atin.asia/crash"
    private const val QUEUE_DIR = "obs_queue"

    /** 批量粒度: 满 50 条即发, 或攒满 5 分钟发 */
    private const val BATCH_MAX = 50
    private const val PERIOD_MS = 5 * 60 * 1000L

    /** 队列硬上限, 与容量预算对齐 */
    private const val MAX_FILES = 5000
    private const val MAX_BYTES = 30L * 1024 * 1024
    private const val TRIM_TO = 4000

    /** 单字段截断, 保证整条事件 <= 1KB */
    private const val FIELD_HEAD = 120

    private const val DEDUP_PREFS = "obs_dedup"
    private const val DEDUP_MAX = 300
    private const val BLOCK_PREFS = "obs_send_block"
    private const val KEY_BLOCK_UNTIL = "block_until"

    /** why 四值收敛, 禁自由文本: 对不上的值一律收敛成 unknown */
    private val WHY_ALLOWED = setOf("alarm_absent", "perm_missing", "blocked", "unknown")

    private val io: java.util.concurrent.ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "obs-io").apply { isDaemon = true }
    }

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var started = false

    /** 冷启动调用一次: 起 5 分钟周期发送 + 补发上次没发完的 */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (started) return
        started = true
        io.execute {
            try {
                trimIfNeeded(app)
            } catch (e: Exception) {
                Log.w(TAG, "启动整理队列失败: " + e.message)
            }
        }
        io.scheduleWithFixedDelay({
            try {
                if (hasPending(app)) flushOnce(app)
            } catch (e: Exception) {
                Log.w(TAG, "周期发送失败: " + e.message)
            }
        }, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * 落一条事件。
     * @param kind 事件类型, 如 timer_missed / timer_stuck / timer_failed
     * @param why  四值之一, 非法值收敛为 unknown
     * @param fields 附加上报字段(已脱敏), 建议控制在 8 个以内
     * @param dedup 去重键, 相同键只发一次; 传 null 表示不去重
     */
    fun emit(ctx: Context, kind: String, why: String, fields: Map<String, String>, dedup: String? = null) {
        val app = appCtx ?: ctx.applicationContext.also { appCtx = it }
        val w = if (WHY_ALLOWED.contains(why)) why else "unknown"
        io.execute {
            try {
                if (dedup != null && isDuplicate(app, dedup)) {
                    Log.i(TAG, "事件已上报过, 跳过 kind=" + kind + " dedup=" + dedup)
                    return@execute
                }
                val file = write(app, kind, w, fields)
                if (dedup != null) markSent(app, dedup)
                Log.i(TAG, "事件落队列 " + (file?.name ?: "失败") + " kind=" + kind + " why=" + w)
                // 2026-11-18 修正: 这里原先无条件 flushOnce, 实测变成"一条一发", 与"满 50 条或满 5 分钟"
                // 的批量语义不符, 弱网下还容易把事件耗在逐条重试上。改成只有攒满一批才立刻发,
                // 否则交给 start() 里的 5 分钟周期任务统一发, 代价是最多滞后一个周期。
                if (pending(app).size >= BATCH_MAX) flushOnce(app)
            } catch (e: Exception) {
                Log.w(TAG, "事件落队列失败 kind=" + kind + ": " + e.message)
            }
        }
    }

    /** 有事件就尽快发(不阻塞调用方) */
    fun flushAsync(ctx: Context) {
        val app = appCtx ?: ctx.applicationContext.also { appCtx = it }
        io.execute {
            try {
                if (hasPending(app)) flushOnce(app)
            } catch (e: Exception) {
                Log.w(TAG, "手动发送失败: " + e.message)
            }
        }
    }

    // ---------------- 内部实现 ----------------

    private fun queueDir(ctx: Context): File =
        File(ctx.filesDir, QUEUE_DIR).apply { if (!exists()) mkdirs() }

    private fun pending(ctx: Context): List<File> {
        val dir = queueDir(ctx)
        val arr = dir.listFiles { f -> f.isFile && f.name.endsWith(".ndjson") } ?: return emptyList()
        return arr.sortedBy { it.name }
    }

    private fun hasPending(ctx: Context): Boolean = pending(ctx).isNotEmpty()

    /** 写一条事件, 返回落盘文件; 单文件本身即一份完整可发送 body */
    private fun write(ctx: Context, kind: String, why: String, fields: Map<String, String>): File? {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("type: evt\n")
        sb.append(buildJson(ctx, kind, why, fields, now))
        sb.append('\n')
        val body = sb.toString()
        val dir = queueDir(ctx)
        val f = File(dir, "evt_" + now + "_" + (seq++ % 100000) + ".ndjson")
        return try {
            f.writeText(body, Charsets.UTF_8)
            f
        } catch (e: Exception) {
            Log.w(TAG, "写文件失败: " + e.message)
            null
        }
    }

    private var seq = 0

    private fun buildJson(ctx: Context, kind: String, why: String, fields: Map<String, String>, now: Long): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("kind", kind)
        o.put("why", why)
        o.put("ts", now)
        o.put("app", versionName(ctx))
        o.put("vc", versionCode(ctx))
        o.put("dev", Build.MODEL ?: "")
        o.put("sdk", Build.VERSION.SDK_INT)
        val k = JSONObject()
        for ((a, b) in fields) {
            k.put(a, clip(b))
        }
        o.put("k", k)
        return o.toString()
    }

    private fun clip(s: String): String =
        if (s.length <= FIELD_HEAD) s else s.substring(0, FIELD_HEAD) + "..."

    private fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun versionCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    /** 取最多 BATCH_MAX 个文件拼一份 body 发出去, 200 才删本地 */
    private fun flushOnce(ctx: Context) {
        val blockedUntil = ctx.getSharedPreferences(BLOCK_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_BLOCK_UNTIL, 0L)
        if (System.currentTimeMillis() < blockedUntil) {
            Log.i(TAG, "服务端拒收冷却中, 队列保留")
            return
        }
        val all = pending(ctx)
        if (all.isEmpty()) return
        val batch = all.take(BATCH_MAX)
        val sb = StringBuilder()
        sb.append("type: evt\n")
        var n = 0
        for (f in batch) {
            val lines = try {
                f.readLines(Charsets.UTF_8)
            } catch (e: Exception) {
                emptyList()
            }
            for (i in 1 until lines.size) {
                val ln = lines[i].trim()
                if (ln.isNotEmpty()) {
                    sb.append(ln).append('\n')
                    n++
                }
            }
        }
        if (n == 0) {
            // 空文件(上次写到一半被杀)直接清掉, 避免堵住队列
            for (f in batch) f.delete()
            return
        }
        when (send(ctx, sb.toString())) {
            200 -> {
                for (f in batch) f.delete()
                Log.i(TAG, "批量发送成功 " + n + " 条 / " + batch.size + " 文件")
                // 每次 flush 只发一批: 积压时由下个周期继续, 避免一次唤醒长时间占网占电
                if (all.size > batch.size) Log.i(TAG, "队列仍有 " + (all.size - batch.size) + " 个文件待下个周期")
            }
            401, 413, 429 -> {
                ctx.getSharedPreferences(BLOCK_PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_BLOCK_UNTIL, System.currentTimeMillis() + 6 * 3600_000L).apply()
                Log.w(TAG, "服务端拒收, 6 小时内不再重试, 队列保留")
            }
            else -> Log.w(TAG, "发送失败, 队列保留等下次")
        }
    }

    private fun send(ctx: Context, body: String): Int {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = java.net.URL(UPLOAD_URL).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.setRequestProperty("X-Auth-Token", BuildConfig.CRASH_TOKEN)
            conn.setRequestProperty("X-Install-Id", installId(ctx))
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (e: Exception) {
            Log.w(TAG, "发送异常: " + e.message)
            -1
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    private fun installId(ctx: Context): String {
        val p = ctx.getSharedPreferences("crash_install", Context.MODE_PRIVATE)
        val cur = p.getString("install_id", null)
        if (cur != null) return cur
        val id = java.util.UUID.randomUUID().toString()
        p.edit().putString("install_id", id).apply()
        return id
    }

    // ---------------- 去重 ----------------

    private fun isDuplicate(ctx: Context, key: String): Boolean {
        val p = ctx.getSharedPreferences(DEDUP_PREFS, Context.MODE_PRIVATE)
        return p.getStringSet("keys", emptySet())?.contains(key) == true
    }

    private fun markSent(ctx: Context, key: String) {
        val p = ctx.getSharedPreferences(DEDUP_PREFS, Context.MODE_PRIVATE)
        val cur = HashSet(p.getStringSet("keys", emptySet()) ?: emptySet())
        if (cur.size >= DEDUP_MAX) cur.clear()
        cur.add(key)
        p.edit().putStringSet("keys", cur).apply()
    }

    // ---------------- 容量 ----------------

    private fun trimIfNeeded(ctx: Context) {
        val files = pending(ctx)
        var total = 0L
        for (f in files) total += f.length()
        if (files.size <= MAX_FILES && total <= MAX_BYTES) return
        val drop = if (files.size > MAX_FILES) files.size - TRIM_TO else files.size / 2
        var dropped = 0
        for (i in 0 until drop) {
            if (files[i].delete()) dropped++
        }
        Log.w(TAG, "队列超限, 丢弃最旧 " + dropped + " 个文件")
    }
}
