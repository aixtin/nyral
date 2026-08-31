package io.github.aixtin.droidagent

import android.content.Context
import org.json.JSONObject

/**
 * WorkTools: 手机本地工作目录(Download/DroidAgent_work/)的文本读写工具。
 * 用途: AI 从服务器拉文件 -> 本地查看/修改 -> 传回, 绕开 SSH 命令行嵌套转义。
 * 非文本文件请用 ssh_download / web_download 落盘, 不要用本工具读。
 */
object WorkTools {

    private const val MAX_READ_CHARS = 200000  // 单次最多返回 20 万字符, 防刷屏

    /** 列工作目录文件 */
    fun list(context: Context, arg: String): String = WorkDir.list(context)

    /**
     * 读取工作目录文本文件内容
     * 参数: {"name":"文件名"}
     */
    fun read(context: Context, arg: String): String {
        val name = parseName(arg)
        if (name.isEmpty()) return "错误: 缺少 name(文件名), 如 {\"name\":\"a.txt\"}"
        val bytes = WorkDir.read(context, name)
            ?: return "错误: 工作目录不存在该文件: $name (可用 workdir_list 查看)"
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            return "错误: 文件不是有效的 UTF-8 文本: $name"
        }
        if (text.length > MAX_READ_CHARS) {
            return text.substring(0, MAX_READ_CHARS) + "\n...[已截断, 共 ${text.length} 字符]"
        }
        return text
    }

    /**
     * 写入工作目录文本文件(同名覆盖)
     * 参数: {"name":"文件名","content":"内容"}
     */
    fun write(context: Context, arg: String): String {
        val p = try { JSONObject(arg) } catch (e: Exception) { JSONObject() }
        val name = p.optString("name").trim()
        val content = p.optString("content", "")
        if (name.isEmpty()) return "错误: 缺少 name(文件名), 如 {\"name\":\"a.txt\",\"content\":\"...\"}"
        if (name.contains('/')) return "错误: 文件名不能含路径分隔符, 只能填文件名"
        if (content.length > 10 * 1024 * 1024) return "错误: 内容超过 10MB 上限"
        val ok = WorkDir.write(context, name, content.toByteArray(Charsets.UTF_8))
        return if (ok) "已写入: ${WorkDir.displayPath}$name (${content.length} 字符)"
        else "错误: 写入失败: $name"
    }

    /** 单文件内容搜索上限(超过跳过该文件, 防读大文件卡死) */
    private const val GREP_MAX_FILE_BYTES = 8 * 1024 * 1024
    /** 每个文件最多返回命中行数 */
    private const val GREP_MAX_HITS_PER_FILE = 30
    /** 最多扫描文件数(防工作目录文件过多超时) */
    private const val GREP_MAX_FILES = 200

    /**
     * 全文搜索工作目录文本文件(批量, 一次代替多次 workdir_read)
     * 参数: {"kw":"关键词","ext":"可选按扩展名过滤如 .kt","case":true}
     * 返回: 文件名:行号: 命中行(去首尾空白), 按文件分块
     */
    fun grep(context: Context, arg: String): String {
        val p = try { JSONObject(arg) } catch (e: Exception) { JSONObject() }
        val kw = p.optString("kw").trim()
        val ext = p.optString("ext").trim().lowercase()
        val caseSensitive = p.optBoolean("case", false)
        if (kw.isEmpty()) return "错误: 缺少 kw(搜索关键词), 如 {\"kw\":\"TODO\"} 或 {\"kw\":\"fun main\",\"ext\":\".kt\"}"
        val files = WorkDir.entries(context)
        if (files.isEmpty()) return "(工作目录为空)"
        val sb = StringBuilder()
        var scanned = 0
        var hitFiles = 0
        for (f in files) {
            if (scanned >= GREP_MAX_FILES) break
            if (ext.isNotEmpty() && !f.name.lowercase().endsWith(ext)) continue
            if (f.size > GREP_MAX_FILE_BYTES) continue
            scanned++
            val bytes = WorkDir.read(context, f.name) ?: continue
            val text = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { continue }
            val hits = ArrayList<Pair<Int, String>>()
            val lines = text.split("\n")
            for ((idx, line) in lines.withIndex()) {
                val found = if (caseSensitive) line.contains(kw) else line.contains(kw, ignoreCase = true)
                if (found) hits.add((idx + 1) to line.trim())
                if (hits.size >= GREP_MAX_HITS_PER_FILE) break
            }
            if (hits.isNotEmpty()) {
                hitFiles++
                sb.append("[${f.name}]\n")
                for ((ln, lineText) in hits) {
                    val show = if (lineText.length > 200) lineText.take(200) + "…" else lineText
                    sb.append("  $ln: $show\n")
                }
            }
        }
        return if (hitFiles == 0) "未找到包含「$kw」的文件(扫描 $scanned 个文件)"
        else "搜索「$kw」命中 $hitFiles 个文件:\n${sb.toString().trim()}"
    }

    /**
     * 读取工作目录文件头部 N 行/前 N 字符(批量查看, 代替全文 workdir_read 防上下文爆炸)
     * 参数: {"name":"文件名","lines":50} 或 {"name":"文件名","chars":3000}
     * 返回: 文件总行数/大小 + 头部内容
     */
    fun head(context: Context, arg: String): String {
        val p = try { JSONObject(arg) } catch (e: Exception) { JSONObject() }
        val name = p.optString("name").trim()
        if (name.isEmpty()) return "错误: 缺少 name(文件名), 如 {\"name\":\"build.gradle\",\"lines\":50}"
        val bytes = WorkDir.read(context, name)
            ?: return "错误: 工作目录不存在该文件: $name (可用 workdir_list 查看)"
        val text = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) {
            return "错误: 文件不是有效的 UTF-8 文本: $name"
        }
        val totalLines = text.count { it == '\n' } + (if (text.isEmpty()) 0 else 1)
        val lines = p.optInt("lines", -1)
        val chars = p.optInt("chars", -1)
        val body = when {
            lines > 0 -> {
                val head = text.split("\n").take(lines).joinToString("\n")
                "前 $lines 行:\n$head"
            }
            chars > 0 -> {
                val head = text.take(chars)
                "前 $chars 字符:\n$head"
            }
            else -> "错误: 需指定 lines(行数) 或 chars(字符数), 如 {\"name\":\"a.kt\",\"lines\":80}"
        }
        return "[$name] 共 $totalLines 行 / ${bytes.size} 字节\n$body"
    }

    /**
     * 工作目录统计概览(文件数/总大小/按扩展名分布), 扫描前先看全貌
     * 参数: 无
     */
    fun stats(context: Context, arg: String): String {
        val files = WorkDir.entries(context)
        if (files.isEmpty()) return "(工作目录为空)"
        val totalBytes = files.sumOf { it.size }
        val byExt = LinkedHashMap<String, Int>()
        for (f in files) {
            val dot = f.name.lastIndexOf('.')
            val ext = if (dot > 0 && dot < f.name.length - 1) f.name.substring(dot + 1).lowercase() else "(无扩展名)"
            byExt[ext] = (byExt[ext] ?: 0) + 1
        }
        val sizeStr = if (totalBytes >= 1024 * 1024) String.format("%.1fMB", totalBytes / 1024.0 / 1024.0)
            else if (totalBytes >= 1024) String.format("%.1fKB", totalBytes / 1024.0) else "${totalBytes}B"
        val dist = byExt.entries.joinToString(", ") { "${it.key}×${it.value}" }
        return "工作目录共 ${files.size} 个文件, 总大小 $sizeStr\n按类型: $dist"
    }

    private fun parseName(arg: String): String {
        val t = arg.trim()
        return if (t.startsWith("{")) {
            try { JSONObject(t).optString("name").trim() } catch (e: Exception) { "" }
        } else t
    }
}
