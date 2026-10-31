package io.github.aixtin.nyral

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 进程级刹车(2026-10-31 停止链路加固)
 *
 * 旧实现的问题: ProcessBuilder(su -c ...).waitFor(timeoutMs) 只对顶层 su/sh 调 destroyForcibly(),
 * su 派生的 shell 变孤儿被 init 收养后继续跑(真机上出现过脚本占 CPU 数小时);
 * 且用户停止时 worker 阻塞在 waitFor(), 根本走不到取消分支, 停止点击等于无效。
 *
 * 现方案:
 *  1. 脚本用 setsid 独立会话启动(pid == pgid), 停止/超时统一按进程组 TERM -> KILL, 子孙一起收口;
 *  2. 输出与退出码落盘, 脚本脱离父进程后仍可观测, 进程崩溃重启后能识别残留;
 *  3. 运行中的任务 id/pid/root 写 procs.json, App 启动时巡检回收;
 *  4. 轮询等待(100ms), 每轮检查 LocalEngine.cancelRequested, 停止按下后最迟 100ms 收口。
 */
object ProcGuard {
    private const val TASK_FILE = "procs.json"
    private const val DIR = "scripts"

    private class Rec(
        var id: String,
        var pid: Int,
        var root: Boolean,
        var ts: Long,
        var script: String,
        var out: String,
        var rc: String
    )

    private val running = ConcurrentHashMap<String, Rec>()
    private val killer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nyral-prockill").apply { isDaemon = true }
    }
    @Volatile private var swept = false

    private fun dirOf(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 执行脚本(全部 sh_run 走这里): 阻塞至结束/超时/取消, 返回工具结果文本 */
    fun runSh(context: Context, script: String, timeoutMsRaw: Long): String {
        val r = runShInner(context, script, timeoutMsRaw)
        // 模型常传超大 timeout_ms, 实际按 120s 上限执行: 显式回告, 避免用户以为脚本被莫名杀掉
        return if (timeoutMsRaw > 120_000L)
            r + "\n[timeout_ms 请求 " + timeoutMsRaw + "ms, 已按 120s 上限执行]" else r
    }

    private fun runShInner(context: Context, script: String, timeoutMsRaw: Long): String {
        if (LocalEngine.cancelRequested) return "[已停止] 收到停止指令, 脚本未执行"
        val root = try { ScriptEngine.isRootAvailable(context) } catch (e: Throwable) { false }
        val timeoutMs = timeoutMsRaw.coerceIn(1000L, 120_000L)
        val d = dirOf(context)
        val id = "t" + System.currentTimeMillis().toString(36) + Integer.toHexString((Math.random() * 0xffff).toInt())
        val scriptFile = File(d, "$id.sh")
        val wrapFile = File(d, "$id.wrap")
        val bootFile = File(d, "$id.boot")
        val outFile = File(d, "$id.out")
        val rcFile = File(d, "$id.rc")
        val tag = if (root) "[root]" else "[shell(非root)]"
        var pid = -1
        var rec: Rec? = null
        try {
            scriptFile.writeText(script)
            // wrap: 真正执行的壳, 输出与退出码落盘(脱离父进程后仍可观测)
            wrapFile.writeText(
                "export NYRAL_TASK_ID=$id\n" +
                    "sh '${scriptFile.absolutePath}' > '${outFile.absolutePath}' 2>&1\n" +
                    "echo \$? > '${rcFile.absolutePath}'\n"
            )
            // boot: setsid 建独立会话(pid==pgid), 立刻回显 pid 后自身退出
            bootFile.writeText(
                "setsid sh '${wrapFile.absolutePath}' >/dev/null 2>&1 &\n" +
                    "echo \$!\n"
            )
            val bootCmd = if (root) arrayOf("su", "-c", "sh '${bootFile.absolutePath}'")
                          else arrayOf("sh", bootFile.absolutePath)
            val bootProc = ProcessBuilder(*bootCmd).redirectErrorStream(true).start()
            pid = try {
                val line = bootProc.inputStream.bufferedReader().readLine()?.trim().orEmpty()
                line.toIntOrNull() ?: -1
            } catch (e: Exception) { -1 }
            try { bootProc.waitFor(3, TimeUnit.SECONDS) } catch (_: Exception) {}
            if (pid <= 0) {
                val out = if (outFile.exists()) outFile.readText() else ""
                return "$tag 启动失败(未取到进程号)\n${cap(out)}"
            }
            rec = Rec(id, pid, root, System.currentTimeMillis(),
                scriptFile.absolutePath, outFile.absolutePath, rcFile.absolutePath)
            running[id] = rec
            persist(context)

            // 轮询等待: 停止/退出码落盘/超时 三种收口
            val start = System.currentTimeMillis()
            var killedReason = ""
            while (true) {
                if (LocalEngine.cancelRequested) { killedReason = "cancel"; break }
                if (rcFile.exists() && rcFile.length() > 0L) break
                if (!File("/proc/$pid").exists()) {
                    // 进程已退出(可能被外部杀掉): 给退出码落盘留 800ms 窗口
                    var waited = 0
                    while (waited < 800 && !(rcFile.exists() && rcFile.length() > 0L)) {
                        Thread.sleep(50); waited += 50
                    }
                    break
                }
                if (System.currentTimeMillis() - start > timeoutMs) { killedReason = "timeout"; break }
                Thread.sleep(100)
            }
            if (killedReason.isNotEmpty()) {
                killGroup(pid, root)
                val out = if (outFile.exists()) outFile.readText() else ""
                val head = if (killedReason == "cancel") "$tag 已停止(用户中断, 进程组已终止)" else "$tag 执行超时(>${timeoutMs}ms, 进程组已终止)"
                return if (out.isBlank()) head else "$head\n[中断前输出]\n${cap(out)}"
            }
            val rc = try { rcFile.readText().trim() } catch (e: Exception) { "?" }
            val out = try { if (outFile.exists()) outFile.readText() else "" } catch (e: Exception) { "" }
            return "$tag exit=$rc\n${cap(out)}"
        } catch (e: Exception) {
            return "$tag 执行失败: ${e.message}"
        } finally {
            if (rec != null) { running.remove(id); persist(context) }
            try { scriptFile.delete() } catch (_: Exception) {}
            try { wrapFile.delete() } catch (_: Exception) {}
            try { bootFile.delete() } catch (_: Exception) {}
            try { outFile.delete() } catch (_: Exception) {}
            try { rcFile.delete() } catch (_: Exception) {}
            cleanupOld(d)
        }
    }

    // ---------- 收口 ----------

    /** 进程组整组终止: TERM 给机会清理, 300ms 后 KILL 收尸 */
    private fun killGroup(pid: Int, root: Boolean) {
        if (pid <= 0) return
        execShell(root, "kill -TERM -$pid 2>/dev/null; kill -TERM $pid 2>/dev/null")
        try { Thread.sleep(300) } catch (_: InterruptedException) {}
        execShell(root, "kill -KILL -$pid 2>/dev/null; kill -KILL $pid 2>/dev/null")
    }

    /** 异步杀掉全部登记进程(停止按钮第一击; 不阻塞 UI 线程) */
    fun killAllAsync() {
        val snapshot = running.values.toList()
        if (snapshot.isEmpty()) return
        killer.execute { snapshot.forEach { killGroup(it.pid, it.root) } }
    }

    /** 同步强杀(第二击/超时兜底): 立刻向所有进程组发 KILL */
    fun killAllNow() {
        val snapshot = running.values.toList()
        if (snapshot.isEmpty()) return
        snapshot.forEach {
            execShell(it.root, "kill -KILL -${it.pid} 2>/dev/null; kill -KILL ${it.pid} 2>/dev/null")
        }
    }

    // ---------- P3 后台任务面板 ----------

    /** 运行中脚本进程的快照(id / pid / 是否 root / 启动时间 / 脚本路径) */
    data class TaskView(val id: String, val pid: Int, val root: Boolean, val ts: Long, val script: String)

    fun tasks(): List<TaskView> =
        running.values.map { TaskView(it.id, it.pid, it.root, it.ts, it.script) }

    /** 单个任务终止: 按进程组整组 TERM -> KILL(与停止按钮同一套收口) */
    fun killOne(id: String) {
        val r = running[id] ?: return
        killer.execute { killGroup(r.pid, r.root) }
    }

    private fun execShell(root: Boolean, cmd: String) {
        try {
            val p = if (root) ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                    else ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            p.waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }

    // ---------- 残留巡检 ----------

    /** App 启动时调用: 回收上一次运行遗留的脚本进程(崩溃/被杀/断电后仍占 CPU 的场景) */
    fun sweepAsync(context: Context) {
        if (swept) return
        swept = true
        killer.execute {
            try {
                val f = File(context.filesDir, TASK_FILE)
                if (!f.exists()) return@execute
                val arr = JSONArray(f.readText())
                var killed = 0
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val pid = o.optInt("pid", -1)
                    val root = o.optBoolean("root", false)
                    val rc = o.optString("rc", "")
                    if (pid <= 0) continue
                    if (rc.isNotEmpty() && File(rc).exists()) continue  // 已正常结束
                    if (!File("/proc/$pid").exists()) continue          // 已消失(pid 可能被复用, 不误杀)
                    execShell(root, "kill -KILL -$pid 2>/dev/null; kill -KILL $pid 2>/dev/null")
                    killed++
                }
                f.delete()
                if (killed > 0) android.util.Log.i("Nyral", "ProcGuard 启动巡检回收残留脚本进程 $killed 个")
            } catch (_: Exception) {}
        }
    }

    private fun persist(context: Context) {
        try {
            val arr = JSONArray()
            running.values.forEach {
                arr.put(JSONObject().put("id", it.id).put("pid", it.pid).put("root", it.root)
                    .put("ts", it.ts).put("rc", it.rc))
            }
            File(context.filesDir, TASK_FILE).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    // ---------- 杂项 ----------

    private fun cap(s: String, max: Int = 20000): String =
        if (s.length <= max) s else s.take(max / 2) + "\n...[输出过长, 共${s.length}字符, 已截断]...\n" + s.takeLast(max / 2)

    private fun cleanupOld(dir: File) {
        try {
            val all = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            if (all.size > 40) all.drop(40).forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
