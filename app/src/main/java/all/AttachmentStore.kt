package io.github.aixtin.nyral

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * 附件持久化存储: 保存到 app 私有目录 files/attachments/。
 * 数据自托管, 不依赖外部存储/第三方服务; 消息记录里只存 att://<fileName> 引用,
 * 重启/切会话后附件文件仍在, 可点击查看。
 */
object AttachmentStore {
    private const val DIR = "attachments"

    /** 写入附件文件, 返回唯一 fileName(引用 key) */
    fun save(context: Context, name: String, mime: String, bytes: ByteArray, metaJson: String? = null): String {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|()\\[\\] ]"), "_")
            .ifBlank { "attachment" }
        val fileName = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}_$safeName"
        File(dir, fileName).writeBytes(bytes)
        writeMimeMeta(dir, fileName, mime, metaJson)
        return fileName
    }

    /** 流式写入附件文件(不整文件进内存, 修复大视频 OOM); meta 规则同 save() */
    fun saveStream(context: Context, name: String, mime: String, input: InputStream, metaJson: String? = null): String {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|()\\[\\] ]"), "_")
            .ifBlank { "attachment" }
        val fileName = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}_$safeName"
        File(dir, fileName).outputStream().use { out -> input.copyTo(out) }
        writeMimeMeta(dir, fileName, mime, metaJson)
        return fileName
    }

    /** 统一写 meta: 优先保留调用方完整 metaJson, 否则写入最小 mime 记录(修复读取端按扩展名误判视频) */
    private fun writeMimeMeta(dir: File, fileName: String, mime: String, metaJson: String?) {
        if (!metaJson.isNullOrBlank()) {
            File(dir, "$fileName.meta.json").writeText(metaJson)
        } else {
            File(dir, "$fileName.meta.json").writeText("""{"mime":"$mime"}""")
        }
    }

    /** 按引用 key 取文件; 防路径穿越只取纯文件名 */
    fun fileOf(context: Context, fileName: String): File? {
        val base = File(context.filesDir, DIR)
        val f = File(base, File(fileName).name)
        if (f.exists() && f.parentFile?.absolutePath == base.absolutePath) return f
        // 表情库引用兼容(AI 表情气泡 2026-09-20): splitAiEmojiReply 拆分时按白名单 file 原样
        // 拼 att://emoji_lib/xxx 落库, 实体文件在 filesDir/emoji_lib/ 下; 这里做二次解析,
        // 否则 fileOf 只认 attachments/ 目录导致 AI 表情气泡解析失败回退文本渲染。
        // 仅当引用前缀确为 emoji_lib/ 且目标文件存在时放行, 不影响原防路径穿越语义。
        if (fileName.startsWith("emoji_lib/")) {
            val libBase = File(context.filesDir, "emoji_lib")
            val lib = File(libBase, File(fileName).name)
            if (lib.exists() && lib.parentFile?.absolutePath == libBase.absolutePath) return lib
        }
        return null
    }

    /**
     * 分块读取附件文本(供 AI attach_read 工具按需读取, 避免超大附件全文进上下文)。
     * 仅限 UTF-8 文本类(发送时超预算附件已落盘 *.txt); 二进制/非 UTF-8 返回 null。
     */
    fun readTextChunk(context: Context, fileName: String, offset: Int = 0, limit: Int = 4000): String? {
        val f = fileOf(context, fileName) ?: return null
        val bytes = try { f.readBytes() } catch (e: Exception) { return null }
        val text = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { return null }
        if (offset >= text.length) return null
        val start = offset.coerceAtLeast(0)
        val end = (start + limit.coerceIn(1, 50000)).coerceAtMost(text.length)
        return text.substring(start, end)
    }

    /** 附件文本总字符数(供 attach_read 汇报剩余量) */
    fun textLength(context: Context, fileName: String): Int {
        val f = fileOf(context, fileName) ?: return -1
        return try {
            String(f.readBytes(), Charsets.UTF_8).length
        } catch (e: Exception) { -1 }
    }

    /** 读取附件 meta(与附件同名的 *.meta.json), 不存在返回 null */
    fun metaOf(context: Context, fileName: String): String? {
        val f = fileOf(context, "$fileName.meta.json") ?: return null
        return try { f.readText() } catch (e: Exception) { null }
    }

    /** 附件显示名: 优先取 meta 中的原名(落库文件名含时间戳/UUID 前缀), 无 meta 回退文件名 */
    fun displayName(context: Context, fileName: String): String {
        val meta = metaOf(context, fileName)
        if (meta.isNullOrBlank()) return fileName
        return try {
            org.json.JSONObject(meta).optString("name").takeIf { it.isNotBlank() } ?: fileName
        } catch (e: Exception) { fileName }
    }

    /** 按文件名推断 mime */
    fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        // 常见文本/代码扩展名: 系统 MimeTypeMap 对 sh/py 等返回 null, 显式映射为 text/plain
        val textExts = setOf(
            "txt", "md", "markdown", "log", "csv", "json", "xml", "html", "htm",
            "css", "js", "mjs", "ts", "java", "kt", "kts", "c", "cpp", "cc", "h",
            "hpp", "sh", "bash", "zsh", "py", "rb", "php", "go", "rs", "sql",
            "yml", "yaml", "toml", "ini", "cfg", "conf", "env", "properties",
            "bat", "cmd", "ps1", "gradle", "lock"
        )
        if (ext in textExts) return "text/plain"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /** 读取端 mime: 优先用落盘时记录的 meta.mime(真实格式), 避免扩展名非标准(如转发视频 .bin/.wechat)被误判为 octet-stream */
    fun mimeOf(context: Context, fileName: String): String {
        val meta = metaOf(context, fileName)
        if (meta != null) {
            try {
                val m = org.json.JSONObject(meta).optString("mime")
                if (m.isNotEmpty() && m != "null") return m
            } catch (_: Exception) {}
        }
        return mimeOf(fileName)
    }

    /** 附件清单(不含 .meta.json, 按名与附件配对): 按修改时间倒序 */
    fun list(context: Context): List<File> {
        val dir = File(context.filesDir, DIR)
        return dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".meta.json") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** 删除附件及其配对 meta(同生共死), 返回是否成功 */
    fun delete(context: Context, fileName: String): Boolean {
        val f = fileOf(context, fileName) ?: return false
        val ok = try { f.delete() } catch (e: Exception) { false }
        if (ok) {
            try { fileOf(context, "$fileName.meta.json")?.delete() } catch (e: Exception) { }
        }
        return ok
    }
}

/**
 * 极简文件 Provider(免 androidx): 让系统查看器通过 content:// 打开 app 私有目录里的附件。
 * authorities = <package>.files
 */
class LocalFileProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val path = uri.path ?: return null
        val ctx = context ?: return null
        // 工作目录文件(content://<pkg>.files/work/<文件名>): 供浏览器上传 file input 使用
        if (path.startsWith("/work/")) {
            val name = Uri.decode(path.removePrefix("/work/"))
            // 防路径穿越: 仅允许纯文件名, 拒绝 ../、绝对路径与编码分隔符
            if (name.isEmpty() || name.contains('/') || name == ".." || name.contains("../") || java.io.File(name).name != name) return null
            val msUri = WorkDir.publicUri(ctx, name)
            if (msUri != null) {
                return runCatching { ctx.contentResolver.openFileDescriptor(msUri, "r") }.getOrNull()
            }
            // 兜底: MANAGE_EXTERNAL_STORAGE 已授权时 File 直读
            return runCatching {
                ParcelFileDescriptor.open(
                    java.io.File(WorkDir.displayPath, name), ParcelFileDescriptor.MODE_READ_ONLY)
            }.getOrNull()
        }
        val fileName = uri.lastPathSegment ?: return null
        val f = AttachmentStore.fileOf(ctx, fileName) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        val path = uri.path ?: return ""
        val name = if (path.startsWith("/work/")) Uri.decode(path.removePrefix("/work/"))
                   else uri.lastPathSegment ?: ""
        return AttachmentStore.mimeOf(name)
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
                       selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
}
