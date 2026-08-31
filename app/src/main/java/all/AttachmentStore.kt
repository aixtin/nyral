package io.github.aixtin.droidagent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

/**
 * 附件持久化存储: 保存到 app 私有目录 files/attachments/。
 * 数据自托管, 不依赖外部存储/第三方服务; 消息记录里只存 att://<fileName> 引用,
 * 重启/切会话后附件文件仍在, 可点击查看。
 */
object AttachmentStore {
    private const val DIR = "attachments"

    /** 写入附件文件, 返回唯一 fileName(引用 key) */
    fun save(context: Context, name: String, mime: String, bytes: ByteArray): String {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|() ]"), "_")
            .ifBlank { "attachment" }
        val fileName = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().substring(0, 8)}_$safeName"
        File(dir, fileName).writeBytes(bytes)
        return fileName
    }

    /** 按引用 key 取文件; 防路径穿越只取纯文件名 */
    fun fileOf(context: Context, fileName: String): File? {
        val base = File(context.filesDir, DIR)
        val f = File(base, File(fileName).name)
        return if (f.exists() && f.parentFile?.absolutePath == base.absolutePath) f else null
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
}

/**
 * 极简文件 Provider(免 androidx): 让系统查看器通过 content:// 打开 app 私有目录里的附件。
 * authorities = <package>.files
 */
class LocalFileProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val fileName = uri.lastPathSegment ?: return null
        val f = AttachmentStore.fileOf(context ?: return null, fileName) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String =
        AttachmentStore.mimeOf(uri.lastPathSegment ?: "")

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
                       selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
}
