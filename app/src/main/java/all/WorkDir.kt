package io.github.aixtin.nyral

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WorkDir: 手机本地工作目录 (Download/Nyral_work/)。
 *
 * 用途: AI 从服务器拉文件 -> 本地改(绕开 SSH 命令行嵌套转义/少字符坑) -> 改完传回。
 * 网页下载/ssh_download 落盘均在此目录, 对用户公共可见可改(系统文件管理器可直接访问)。
 *
 * 实现: Android 10+ 用 MediaStore 写公共 Download 子目录(无需存储权限);
 *       Android 9- 不支持(返回明确错误), 实际部署设备为 Android 16 满足要求。
 */
object WorkDir {

    /** MediaStore 相对路径(不带尾斜杠, 查询时用 LIKE 前缀匹配兼容带斜杠存储) */
    const val RELATIVE = "Download/Nyral_work"
    val displayPath: String get() = "/storage/emulated/0/Download/Nyral_work/"

    /** 子目录分类(2026-09-21 三级目录): 用户可见导出物按类落子目录, 根目录保留给 AI 工作区 */
    const val SUB_DIR_LOG_MAIN = "日志/主日志"
    const val SUB_DIR_LOG_MEM = "日志/辅日志"
    const val SUB_DIR_FILES = "文件"
    const val SUB_DIR_DOWNLOADS = "下载"
    const val SUB_DIR_SHOTS = "截图"

    /** 子目录相对路径: "" -> Download/Nyral_work; "下载" -> Download/Nyral_work/下载 */
    fun relPath(subDir: String = ""): String =
        if (subDir.isBlank()) RELATIVE else "$RELATIVE/${subDir.trim('/')}"

    /** 子目录显示路径: "" -> /storage/emulated/0/Download/Nyral_work/ ; "下载" -> .../下载/ */
    fun displaySubPath(subDir: String = ""): String =
        if (subDir.isBlank()) displayPath else "/storage/emulated/0/${relPath(subDir)}/"

    /**
     * 是否有"所有文件访问"授权。作用域存储下无此授权时:
     * - MediaStore 查询只返回 owner 为自身包名的文件(通过本 App MediaStore 创建), 
     *   adb push / 其它方式落盘的文件(owner=NULL)不可见;
     * - File API 直读公共目录也会被拒。
     * 授权途径: 设置->应用->特殊访问->所有文件访问; 或 adb shell appops set 包名 MANAGE_EXTERNAL_STORAGE allow。
     */
    fun hasBroadFileAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** File API 直读工作目录(需已授权 MANAGE_EXTERNAL_STORAGE), 作为 MediaStore 查询不可见时的兜底 */
    private fun fileReadable(): Boolean = hasBroadFileAccess()

    /**
     * 触发系统媒体扫描。注意: 这只能把文件补进 MediaStore 索引(索引缺失场景),
     * 无法解决"文件已在索引但 owner=NULL 导致 App 查询不可见"的作用域存储过滤问题,
     * 后者必须靠 MANAGE_EXTERNAL_STORAGE 授权 + File 直读兜底。保留此方法兼容旧逻辑。
     */
    fun rescan(context: Context, timeoutMs: Long = 4000): Boolean {
        if (!supported()) return false
        val latch = CountDownLatch(1)
        val indexed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf("/storage/emulated/0/Download/Nyral_work"),
                null
            ) { _, uri -> if (uri != null) indexed.set(true); latch.countDown() }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            latch.countDown()
        }
        return indexed.get()
    }

    private fun supported(): Boolean = Build.VERSION.SDK_INT >= 29

    private fun collection() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** 列工作目录文件: 名称 | 大小 | 修改时间 */
    fun list(context: Context): String {
        if (!supported()) return "错误: 需要 Android 10+ 才能使用工作目录"
        // MediaStore 索引缺失自愈: adb push 等非 MediaStore 落盘的文件默认不在索引里,
        // 第一次查询为空时触发系统媒体扫描补索引后再查一次
        var result = queryList(context)
        if (result.isEmpty()) {
            rescan(context)
            result = queryList(context)
        }
        if (result.isEmpty()) {
            // 作用域存储 owner 过滤: MediaStore 查不到非本 App 创建的文件, 降级 File API 直读
            val fileResult = fileListText()
            if (fileResult != null) return fileResult
        }
        return if (result.isEmpty()) "(工作目录为空)" else "工作目录 $displayPath 文件:\n$result"
    }

    /** File API 直读列表(需 MANAGE_EXTERNAL_STORAGE); 无授权/失败返回 null */
    private fun fileListText(): String? {
        if (!fileReadable()) return null
        return try {
            val dir = java.io.File(displayPath)
            val files = dir.listFiles()?.filter { it.isFile } ?: return null
            if (files.isEmpty()) return null
            val sb = StringBuilder()
            val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            for (f in files.sortedByDescending { it.lastModified() }) {
                val size = f.length()
                val sizeStr = if (size >= 1024 * 1024) String.format("%.1fMB", size / 1024.0 / 1024.0)
                    else if (size >= 1024) String.format("%.1fKB", size / 1024.0)
                    else "${size}B"
                sb.append("${f.name} | $sizeStr | ${sdf.format(java.util.Date(f.lastModified()))}\n")
            }
            "工作目录 $displayPath 文件(File直读):\n${sb.toString().trim()}"
        } catch (_: Exception) {
            null
        }
    }

    private fun queryList(context: Context): String {
        val resolver = context.contentResolver
        val sb = StringBuilder()
        try {
            resolver.query(
                collection(),
                arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_MODIFIED),
                "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?)",
                arrayOf(RELATIVE, "$RELATIVE/"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val size = c.getLong(1)
                    val mod = c.getLong(2)
                    val modStr = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                        .format(java.util.Date(mod))
                    val sizeStr = if (size >= 1024 * 1024) String.format("%.1fMB", size / 1024.0 / 1024.0)
                        else if (size >= 1024) String.format("%.1fKB", size / 1024.0)
                        else "${size}B"
                    sb.append("$name | $sizeStr | $modStr\n")
                }
            }
        } catch (e: Exception) {
            return "workdir_list 失败: ${e.message}"
        }
        return sb.toString().trim()
    }

    /** 文件条目: 名称 + 大小(字节) */
    data class Entry(val name: String, val size: Long)

    /** 枚举工作目录全部文件(名称+大小), 供 grep/stats 等批量工具复用 */
    fun entries(context: Context): List<Entry> {
        if (!supported()) return emptyList()
        val resolver = context.contentResolver
        val out = ArrayList<Entry>()
        try {
            resolver.query(
                collection(),
                arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE),
                "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?)",
                arrayOf(RELATIVE, "$RELATIVE/"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    out.add(Entry(name, c.getLong(1)))
                }
            }
        } catch (_: Exception) {
        }
        // 作用域存储 owner 过滤兜底: MediaStore 只返回本 App 创建的文件, 用 File API 补齐其余
        if (out.isEmpty() && fileReadable()) {
            try {
                val dir = java.io.File(displayPath)
                dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                    if (out.none { it.name == f.name }) out.add(Entry(f.name, f.length()))
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** 按文件名+子目录定位已存在文件的 content uri(subDir 为空=根目录) */
    private fun findUri(context: Context, name: String, subDir: String = ""): Uri? {
        if (!supported()) return null
        val resolver = context.contentResolver
        val rel = relPath(subDir)
        return try {
            resolver.query(
                collection(),
                arrayOf(MediaStore.Downloads._ID),
                "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?) AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(rel, "$rel/", name),
                null
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(collection(), c.getLong(0)) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 写文件(同名覆盖, 可指定子目录如 "下载"/"文件"; 空=根目录): 返回 true 成功, false 失败 */
    fun write(context: Context, name: String, bytes: ByteArray, subDir: String = ""): Boolean {
        if (!supported()) return false
        val safeName = sanitize(name)
        if (safeName.isEmpty()) return false
        val resolver = context.contentResolver
        val rel = relPath(subDir)
        val outDir = displaySubPath(subDir)
        return try {
            var existing = findUri(context, safeName, subDir)
            if (existing == null) {
                rescan(context)
                existing = findUri(context, safeName, subDir)
            }
            if (existing != null) {
                // 覆盖已有文件: "wt" 截断写
                resolver.openOutputStream(existing, "wt")?.use { it.write(bytes) } ?: return false
                return true
            }
            // owner 过滤兜底: 文件在磁盘但 MediaStore 查不到(非本 App 创建), 已授权时 File 直写覆盖
            if (fileReadable()) {
                try {
                    val f = java.io.File(outDir, safeName)
                    if (f.isFile) { f.writeBytes(bytes); return true }
                } catch (_: Exception) {
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, AttachmentStore.mimeOf(safeName))
                put(MediaStore.Downloads.RELATIVE_PATH, rel)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection(), values) ?: return false
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            } finally {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 读文件内容; 不存在返回 null(索引/owner过滤时先重扫, 再 File 直读兜底) */
    fun read(context: Context, name: String): ByteArray? {
        if (!supported()) return null
        val n = sanitize(name)
        var uri = findUri(context, n)
        if (uri == null) {
            rescan(context)
            uri = findUri(context, n)
        }
        if (uri != null) {
            return try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        }
        // 作用域存储 owner 过滤兜底: 用 File API 直读(需 MANAGE_EXTERNAL_STORAGE)
        if (fileReadable()) {
            return try {
                java.io.File(displayPath, n).takeIf { it.isFile }?.readBytes()
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    /** 是否存在(索引/owner过滤时先重扫, 再 File 直读兜底) */
    fun exists(context: Context, name: String): Boolean {
        val n = sanitize(name)
        var found = findUri(context, n) != null
        if (!found) {
            rescan(context)
            found = findUri(context, n) != null
        }
        if (!found && fileReadable()) {
            try {
                found = java.io.File(displayPath, n).isFile
            } catch (_: Exception) {
            }
        }
        return found
    }

    /** 返回工作目录文件的 MediaStore content uri(不存在返回 null); 供文件 Provider 打开上传文件 */
    fun publicUri(context: Context, name: String): Uri? {
        if (!supported()) return null
        val n = sanitize(name)
        var uri = findUri(context, n)
        if (uri == null) { rescan(context); uri = findUri(context, n) }
        return uri
    }

    /**
     * 敏感凭据文件识别(AI 工具安全护栏, 2026-09-15):
     * 命中任一模式即禁止 AI 读取/外传, 防 prompt injection 诱导外泄。
     * 模式: 常见密钥/证书/凭据文件(大小写不敏感)。
     */
    fun isSensitiveName(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        val lower = n.lowercase()
        val patterns = listOf(
            "keys.txt", "key.txt", "secret", "credential", "passwd", "password",
            ".pem", ".key", ".jks", ".keystore", ".p12", ".pfx", ".cer", ".crt",
            "id_rsa", "id_ecdsa", "id_ed25519", ".ssh", "site_auth.json",
            ".env", "token", "apikey", "api_key", "auth.json"
        )
        return patterns.any { lower.contains(it) }
    }

    /** 文件名清洗: 去路径分隔与非法字符 */
    fun sanitize(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|\n\r]"), "_")
            .replace(Regex("[\u0000-\u001f]"), "")
            .trim().ifBlank { "" }
    }

    /** 冗余方法: 清理本地缓存目录(未用, 保留接口) */
    @Suppress("unused")
    fun legacyFile(context: Context): File? {
        if (Build.VERSION.SDK_INT >= 29) return null
        return context.getExternalFilesDir(null)?.resolve("workdir")
    }
}
