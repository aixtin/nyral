package io.github.aixtin.droidagent

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * 文件系统工具: 通过已配置的 SSH 连接操作远程文件
 * 参数统一为 JSON: {"conn":"连接名","path":"/绝对路径","content":"内容","append":false,"lines":200}
 * 内容用 base64 传输, 路径用单引号包裹, 防 shell 注入
 */
object FileTools {

    private const val MAX_READ_LINES = 500

    fun list(context: Context, arg: String): String {
        return try {
            val p = parse(arg)
            val path = p.optString("path", "")
            if (path.isEmpty()) return "缺少 path 参数"
            val cmd = "ls -la --time-style=long-iso ${q(path)} 2>&1"
            runRemote(context, p.optString("conn"), cmd)
        } catch (e: Exception) {
            "file_list 失败: ${e.message}"
        }
    }

    fun read(context: Context, arg: String): String {
        return try {
            val p = parse(arg)
            val path = p.optString("path", "")
            if (path.isEmpty()) return "缺少 path 参数"
            val lines = p.optInt("lines", 200).coerceIn(1, MAX_READ_LINES)
            val cmd = "head -n $lines ${q(path)} 2>&1"
            runRemote(context, p.optString("conn"), cmd)
        } catch (e: Exception) {
            "file_read 失败: ${e.message}"
        }
    }

    fun info(context: Context, arg: String): String {
        return try {
            val p = parse(arg)
            val path = p.optString("path", "")
            if (path.isEmpty()) return "缺少 path 参数"
            val cmd = "stat -c '类型:%F | 大小:%s字节 | 权限:%A | 修改:%y | 路径:%n' ${q(path)} 2>&1"
            runRemote(context, p.optString("conn"), cmd)
        } catch (e: Exception) {
            "file_info 失败: ${e.message}"
        }
    }

    fun write(context: Context, arg: String): String {
        return try {
            val p = parse(arg)
            val path = p.optString("path", "")
            val content = p.optString("content", "")
            if (path.isEmpty()) return "缺少 path 参数"
            val append = p.optBoolean("append", false)
            val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val op = if (append) ">>" else ">"
            val cmd = "echo '$b64' | base64 -d $op ${q(path)} && echo '[OK] 已${if (append) "追加" else "写入"}: $path (${content.length} 字符)' 2>&1"
            runRemote(context, p.optString("conn"), cmd)
        } catch (e: Exception) {
            "file_write 失败: ${e.message}"
        }
    }

    private fun parse(arg: String): JSONObject {
        val t = arg.trim()
        return if (t.startsWith("{")) {
            try {
                JSONObject(t)
            } catch (e: Exception) {
                JSONObject()
            }
        } else JSONObject()
    }

    /** ~ 放行交给远端 shell 展开, 其余路径单引号包裹 */
    private fun q(path: String): String {
        return if (path == "~" || path.startsWith("~/")) path else "'${shellQuote(path)}'"
    }

    private fun shellQuote(path: String): String {
        return path.replace("'", "'\\''")
    }

    private fun runRemote(context: Context, conn: String, cmd: String): String {
        val prefix = if (conn.isNotEmpty()) "$conn:" else ""
        return SshTools.run(context, "$prefix$cmd")
    }
}
