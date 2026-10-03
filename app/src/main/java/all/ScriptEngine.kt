package io.github.aixtin.nyral

import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 就地写脚本能力(2026-09-14, 阶段1: js_run 应用内 JS)
 * 决策: 记忆_2026-09-13_2300_就地写脚本两层定位.md
 *  - JS(本类 runJs) 管应用内就地: 纯计算/逻辑/数据操作, 断网可用, 不依赖服务器;
 *  - Shell+root(阶段2 sh_run) 管系统级就地: su 提权执行 SH 脚本.
 *
 * Rhino 用法参考 MemoryTools.calc: Android 上必须解释模式 optimizationLevel=-1,
 * 否则动态字节码生成报"无法加载类文件".
 */
object ScriptEngine {

    /**
     * 构建受限 JS 作用域(H1 安全修复, 2026-10-03):
     * 1) ClassShutter 全拒: 阻止任何 Java 类经 LiveConnect 暴露给脚本;
     * 2) 删除顶层 Java 访问对象: Packages/java/javax/org/com/JavaImporter/JavaAdapter/getClass。
     * 脚本仅剩纯 JS 能力(计算/字符串/数组/JSON), 无法反射调用 Runtime.exec 等逃逸。
     * 内部函数: MemoryTools.calc 复用同一安全底座。
     */
    internal fun secureScope(cx: Context): ScriptableObject {
        cx.setClassShutter(object : ClassShutter {
            override fun visibleToScripts(fullClassName: String?): Boolean = false
        })
        val scope = cx.initStandardObjects()
        for (p in listOf("Packages", "java", "javax", "org", "com", "JavaImporter", "JavaAdapter", "getClass")) {
            runCatching { ScriptableObject.deleteProperty(scope, p) }
        }
        return scope
    }

    /**
     * 执行 JS 脚本(应用内就地)
     * @param code  JS 脚本
     * @param timeoutMs 超时毫秒, 默认 8000; 防死循环卡死
     * @return 结果字符串(结果 JSON 序列化); 超时/异常返回错误描述
     */
    fun runJs(code: String, timeoutMs: Long = 8000L): String {
        if (code.isBlank()) return "脚本为空: 请提供 code"
        val task = FutureTask(Callable<String> {
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1
                val scope = secureScope(cx)
                val res = cx.evaluateString(scope, code, "js_run", 1, null)
                // 结果 JSON 序列化: 利用 Rhino 内置 JSON.stringify(版本无关, 支持对象/数组/字符串/数字)
                if (res == null || res is Undefined) {
                    "undefined"
                } else {
                    ScriptableObject.putProperty(scope, "__res", res)
                    val s = cx.evaluateString(scope, "JSON.stringify(__res)", "js_run", 1, null)
                    if (s is Undefined) res.toString() else s.toString()
                }
            } finally {
                Context.exit()
            }
        })
        // 每次独立线程执行: 单线程池会被死循环任务卡死, 独立线程超时后放弃等待即可隔离
        Thread(task, "js-run").apply { isDaemon = true; start() }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            "执行超时(>${timeoutMs}ms): 请检查脚本是否死循环"
        } catch (e: Exception) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause
            "执行失败: ${cause?.message ?: e.message}"
        }
    }

    // ================= 阶段2: sh_run 系统级 Shell =================
    // 两层定位(记忆_2026-09-13_2300): JS(runJs)管应用内就地, Shell+root 管系统级就地。
    // root 态 su -c 提权执行, 非 root 降级普通 sh; 危险命令黑名单整脚本拦截。
    // 新工具沿用既有四补丁模式注册: LocalEngine 四处 + DebugServer /v1/sh/run 端点。

    /** root 探测结果缓存(进程级): true/false */
    @Volatile private var rootChecked = false
    @Volatile private var rootOk = false

    /** 危险命令黑名单: 子串命中即整脚本拒绝执行(保守优先, 误伤可拆分脚本绕过) */
    private val DANGER_WORDS = listOf(
        "rm -rf /", "rm -fr /", "rm -rf /*", "rm -fr /*",
        "mkfs", "dd if=/dev/zero", "dd of=/dev/",
        "> /dev/sd", "> /dev/mmcblk", "format /", "wipe",
        "reboot", "poweroff", "shutdown", "halt",
        ":(){ :|:& };:", "chmod -R 777 /",
        // H4 加固(2026-10-03): 扩充破坏性命令/设备操作
        "fdisk", "blkdiscard", "parted", "mkswap", "cryptsetup", "pvcreate", "vgremove",
        "kill -9 1", "kill -9 0", "chown -R /", "chmod -R 777 /boot",
        "> /dev/disk", "> /dev/mapper", "> /dev/loop", "> /boot", "> /proc"
    )

    /** H4 规范化危险模式(2026-10-03): 去空白/反斜杠/引号后匹配, 防黑名单绕过
     *  (如 "rm -rf  /" / "r\m -rf /" / "dd if=/dev/ze ro" 等变体) */
    private val DANGER_NORMALIZED = listOf(
        "rm-rf/", "rm-fr/", "rm-rf/*",
        "mkfs", "ddiv=/dev/zero", "ddof=/dev/", "if=/dev/", "of=/dev/",
        ">/dev/sd", ">/dev/mmcblk", ">/dev/disk", ">/dev/mapper", ">/dev/loop", ">/boot", ">/proc",
        "format/", "wipe", "reboot", "poweroff", "shutdown", "halt",
        ":{(", "chmod-r777/", "chown-r/", "kill-91", "kill-90",
        "fdisk", "blkdiscard", "parted", "mkswap", "cryptsetup", "pvcreate", "vgremove"
    )

    /** H4: 脚本规范化(去空白/反斜杠/引号, 转小写), 用于防绕过匹配 */
    private fun normalizeDanger(s: String): String =
        s.filterNot { it.isWhitespace() || it == '\\' || it == '\'' || it == '"' }.lowercase()

    /** 输出截断上限(保留头尾, 中段折叠) */
    private const val OUT_CAP = 20000

    /**
     * 执行 SH 脚本(系统级就地, 阶段2)
     * @param argRaw JSON: {"script":"...","timeout_ms":15000}; 兼容裸字符串(整段当脚本)
     * @return 结果字符串: [root]/[shell(非root)] 前缀 + exit code + 输出; 危险命令/超时/异常返回对应描述
     */
    fun runSh(context: android.content.Context, argRaw: String): String {
        val script = parseScript(argRaw)
        if (script.isBlank()) return "脚本为空: 请提供 script"
        // 危险命令拦截(整脚本拒绝, 防止 root 下破坏)
        for (w in DANGER_WORDS) {
            if (script.contains(w)) return "已拦截: 脚本命中危险命令[$w], 拒绝执行(如需保留可拆分脚本绕过)"
        }
        // H4 加固: 规范化匹配, 防空白/转义变体绕过
        val norm = normalizeDanger(script)
        for (w in DANGER_NORMALIZED) {
            if (norm.contains(w)) return "已拦截: 脚本命中危险命令(规范化)[$w], 拒绝执行(如需保留可拆分脚本绕过)"
        }
        val timeoutMs = parseTimeout(argRaw)
        val dir = File(context.filesDir, "scripts")
        if (!dir.exists()) dir.mkdirs()
        val ts = System.currentTimeMillis()
        val scriptFile = File(dir, "$ts.sh")
        val outFile = File(dir, "$ts.out")
        return try {
            scriptFile.writeText(script)
            val root = isRootAvailable(context)
            // root: su -c 提权并把 stdout/stderr 落盘(out 重定向由 su 的 shell 解析); 非 root: 普通 sh
            val cmd = if (root) arrayOf("su", "-c", "sh ${scriptFile.absolutePath} > ${outFile.absolutePath} 2>&1")
                      else arrayOf("sh", "-c", "sh ${scriptFile.absolutePath} > ${outFile.absolutePath} 2>&1")
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                "${if (root) "[root]" else "[shell(非root)]"} 执行超时(>${timeoutMs}ms), 已强制终止"
            } else {
                val out = if (outFile.exists()) outFile.readText() else ""
                "${if (root) "[root]" else "[shell(非root)]"} exit=${proc.exitValue()}\n${capOutput(out)}"
            }
        } catch (e: Exception) {
            "执行失败: ${e.message}"
        } finally {
            try { scriptFile.delete() } catch (e: Exception) {}
            try { outFile.delete() } catch (e: Exception) {}
            cleanupOldScripts(dir)
        }
    }

    /** root 可用性探测: su -c id 输出含 uid=0 即 root; 结果进程级缓存(幂等, 多线程重复探测无害) */
    fun isRootAvailable(context: android.content.Context): Boolean {
        if (rootChecked) return rootOk
        val r = try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            if (!p.waitFor(3000, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); false }
            else p.inputStream.readBytes().toString(Charsets.UTF_8).contains("uid=0")
        } catch (e: Exception) { false }
        rootOk = r; rootChecked = true
        return r
    }

    private fun parseScript(argRaw: String): String {
        val t = argRaw.trim()
        return if (t.startsWith("{")) {
            try { org.json.JSONObject(t).optString("script", "") } catch (e: Exception) { t }
        } else t
    }

    private fun parseTimeout(argRaw: String): Long {
        val t = argRaw.trim()
        if (!t.startsWith("{")) return 15000L
        return try { org.json.JSONObject(t).optLong("timeout_ms", 15000L) } catch (e: Exception) { 15000L }
    }

    /** 输出截断: 保留头尾各半, 中段折叠 */
    private fun capOutput(s: String, max: Int = OUT_CAP): String =
        if (s.length <= max) s
        else s.take(max / 2) + "\n...[输出过长, 共${s.length}字符, 已截断]...\n" + s.takeLast(max / 2)

    /** 清理旧脚本/输出: 仅保留最近 20 份(按修改时间), 防 filesDir 膨胀 */
    private fun cleanupOldScripts(dir: File) {
        try {
            val all = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            if (all.size > 40) all.drop(40).forEach { it.delete() }
        } catch (e: Exception) {}
    }
}
