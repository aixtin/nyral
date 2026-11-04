package io.github.aixtin.nyral

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 删除审计存储(2026-11-04):
 * 记录附件删除前的文件快照, AI 可读、用户不可删、仅属文件管理器私有目录(files/audit/)。
 * 独立于 SecurityConfig.audit(那是安全决策审计, 这里是文件删除审计)。
 * 存储: JSON Lines 追加写, 有界保留最近 MAX_ENTRIES 条。
 */
object FileAuditStore {
    private const val DIR = "audit"
    private const val FILE = "file_delete_audit.log"
    const val MAX_ENTRIES = 500

    fun file(context: Context): File = File(File(context.filesDir, DIR), FILE)

    /**
     * 追加一条删除审计。entry 字段:
     * ts 删除时间(ms) / fileName 落库文件名 / displayName 显示名 / size 字节
     * mime / isArtifact 是否AI产物 / source 删除来源(user|user_batch|ai|...)
     */
    fun append(context: Context, entry: JSONObject): Boolean {
        return try {
            val f = file(context)
            f.parentFile?.mkdirs()
            f.appendText(entry.toString() + "\n")
            trim(context)
            true
        } catch (e: Exception) { false }
    }

    /** 有界: 超过 MAX_ENTRIES 时只保留最近 MAX_ENTRIES 条(截断重写) */
    private fun trim(context: Context) {
        val f = file(context)
        if (!f.exists()) return
        val lines = try { f.readLines() } catch (e: Exception) { return }
        if (lines.size <= MAX_ENTRIES) return
        try {
            f.writeText(lines.takeLast(MAX_ENTRIES).joinToString("\n") + "\n")
        } catch (e: Exception) { }
    }

    /** 读全部(按时间倒序), 失败返回空 */
    fun read(context: Context): List<JSONObject> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().mapNotNull { line ->
                try { JSONObject(line) } catch (e: Exception) { null }
            }.sortedByDescending { it.optLong("ts", 0) }
        } catch (e: Exception) { emptyList() }
    }

    /** AI 只读工具 read_file_audit 的返回文本: 最近 limit 条 JSON 数组 */
    fun readAsText(context: Context, limit: Int = 50): String {
        val all = read(context)
        val n = if (limit <= 0) all.size else minOf(limit, all.size)
        val sb = StringBuilder()
        sb.append("共 ${all.size} 条删除审计记录(仅文件管理器私有目录, 用户不可删); 以下为最近 $n 条:\n")
        all.take(n).forEachIndexed { idx, e ->
            val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(e.optLong("ts", 0)))
            sb.append("[${idx + 1}] ${ts} 删除: ")
            sb.append(e.optString("displayName", e.optString("fileName", "?")))
            sb.append(" (落库名=${e.optString("fileName", "?")}, 大小=${e.optLong("size", 0)}B, mime=${e.optString("mime", "?")}")
            if (e.optBoolean("isArtifact", false)) sb.append(", AI产物")
            sb.append(", 来源=${e.optString("source", "?")})")
            sb.append("\n")
        }
        return sb.toString()
    }
}
