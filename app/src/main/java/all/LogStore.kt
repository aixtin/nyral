package io.github.aixtin.droidagent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 运行日志收集器（全局单例）。
 * - 双视图: MAIN=主对话运行日志, MEM=辅助AI(记忆索引)日志。
 * - 内存环形缓冲(最新 MAX_ENTRIES 条) + 追加写文件(filesDir/logs/{tag}.log)。
 * - 文件为权威历史, 内存为加速; LogActivity 合并展示时按文件末条时间戳去重。
 * - 所有写操作线程安全: 内存同步, 文件由单线程 executor 串行追加。
 */
object LogStore {
    const val MAIN = "main"
    const val MEM = "mem"

    private const val MAX_ENTRIES = 1000
    private const val MAX_FILE_LINES = 2000   // 文件滚动上限, 超限时截断保留后半

    data class Entry(val ts: Long, val tag: String, val level: String, val msg: String) {
        fun line(): String {
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ts))
            return "[$t] [$level] $msg"
        }
    }

    @Volatile private var app: Context? = null
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private val fileExec = Executors.newSingleThreadExecutor { r -> Thread(r, "logstore").apply { isDaemon = true } }

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun i(tag: String, msg: String) = append(tag, "I", msg)
    fun w(tag: String, msg: String) = append(tag, "W", msg)
    fun e(tag: String, msg: String) = append(tag, "E", msg)

    private fun append(tag: String, level: String, msg: String) {
        val e = Entry(System.currentTimeMillis(), tag, level, msg)
        synchronized(lock) {
            entries.addLast(e)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        fileExec.execute { writeToFile(e) }
    }

    /** 内存快照(当前进程内); tag 为空表示全部 */
    fun snapshot(tag: String?): List<Entry> = synchronized(lock) {
        if (tag == null) entries.toList() else entries.filter { it.tag == tag }
    }

    /** 合并文件历史 + 内存未落盘条目, 供日志页展示; tag 为空表示全部 */
    fun history(tag: String?): List<Entry> {
        val mem = synchronized(lock) { if (tag == null) entries.toList() else entries.filter { it.tag == tag } }
        val dir = app?.getDir("logs", Context.MODE_PRIVATE) ?: return mem
        val files = if (tag == null) listOf(File(dir, "$MAIN.log"), File(dir, "$MEM.log"))
                    else listOf(File(dir, "$tag.log"))
        val fromFiles = ArrayList<Entry>()
        var lastFileTs = 0L
        for (f in files) {
            if (!f.exists()) continue
            val fileTag = f.name.removeSuffix(".log")
            try {
                f.forEachLine { ln ->
                    parseLine(ln, fileTag)?.let {
                        fromFiles.add(it)
                        if (it.ts > lastFileTs) lastFileTs = it.ts
                    }
                }
            } catch (_: Exception) {
            }
        }
        val result = fromFiles + mem.filter { it.ts / 1000 > lastFileTs / 1000 }
        // 文件行尾去重(同一进程内文件与内存可能重合)
        return if (tag == null) result else result.filter { it.tag == tag }
    }

    /** 清空指定 tag(内存 + 文件); tag 为空清空全部 */
    fun clear(tag: String?) {
        synchronized(lock) {
            if (tag == null) entries.clear()
            else entries.removeAll { it.tag == tag }
        }
        fileExec.execute {
            val dir = app?.getDir("logs", Context.MODE_PRIVATE) ?: return@execute
            if (tag == null) {
                dir.listFiles()?.filter { it.name.endsWith(".log") }?.forEach { it.delete() }
            } else {
                File(dir, "$tag.log").delete()
            }
        }
    }

    // ---- 文件读写 ----

    private fun writeToFile(e: Entry) {
        val dir = app?.getDir("logs", Context.MODE_PRIVATE) ?: return
        try {
            val f = File(dir, "${e.tag}.log")
            f.appendText(e.line() + "\n")
            // 滚动截断: 超 MAX_FILE_LINES 时保留后半, 防止无限膨胀
            val lines = f.readLines()
            if (lines.size > MAX_FILE_LINES) {
                f.writeText(lines.takeLast(MAX_FILE_LINES).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    /** 解析文件行 "[HH:mm:ss] [LEVEL] msg" -> Entry(ts 用当天日期合成); tag 由文件名决定 */
    private fun parseLine(ln: String, tag: String): Entry? {
        if (ln.length < 16 || ln[0] != '[') return null
        val t = ln.substring(1, 9)                       // HH:mm:ss
        val lev = ln.substring(12, 13)                   // I/W/E
        val msg = if (ln.length > 16) ln.substring(16) else ""
        val base = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val ts = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse("$base $t").time
        } catch (_: Exception) { return null }
        return Entry(ts, tag, lev, msg)
    }
}
