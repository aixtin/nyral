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
/** 工作目录文件整读上限(2026-11-10): 超限按读取失败处理, 避免 OOM */
private const val WORKDIR_MAX_BYTES = 64L * 1024 * 1024

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

    /**
     * 落盘 MIME(2026-11-15): 必须与 DISPLAY_NAME 的后缀一致, 否则 MediaProvider 会按 MIME 补扩展名
     * (md 若按 text/plain 落盘会被改名成 xxx.md.txt)。仅对"后缀语义明确"的类型做精确映射。
     */
    private fun mimeFor(name: String): String = when (name.substringAfterLast(".", "").lowercase()) {
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "xml" -> "text/xml"
        "yml", "yaml" -> "text/yaml"
        "py" -> "text/x-python"
        "kt", "kts" -> "text/x-kotlin"
        "sh" -> "text/x-sh"
        else -> AttachmentStore.mimeOf(name)
    }

    /**
     * 把条目 DISPLAY_NAME 校正为规范名(2026-11-15)。
     * 仅在目标名未被其它条目占用时执行, 避免触发 MediaProvider 的冲突去重再次改名。
     */
    private fun renameTo(resolver: android.content.ContentResolver, uri: Uri, safeName: String, subDir: String = "") {
        try {
            val actual = resolver.query(
                uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return
            if (actual == safeName) return
            // 目标名已被别的条目占用时放弃改名, 否则会触发 MediaProvider 冲突去重再次改名
            val rel = relPath(subDir)
            val cnt = resolver.query(
                collection(),
                arrayOf(MediaStore.Downloads._ID),
                "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?) AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(rel, "$rel/", safeName),
                null
            )?.use { c -> c.count } ?: return
            if (cnt > 0) return
            // 先对齐 MIME 再改 DISPLAY_NAME: 两者不一致时 MediaProvider 会按 MIME 重新补扩展名
            resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Downloads.MIME_TYPE, mimeFor(safeName))
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                },
                null, null
            )
        } catch (_: Exception) {
        }
    }

    /**
     * 找"同一族"旧文件(2026-11-15): 规范化后与目标名相同的历史副本
     * (a.md / a.md.txt / a.md (1).txt 视为同一份), 取最近修改的一份用于覆盖写,
     * 从而终止 MediaStore 冲突去重造成的 (n) 副本堆积。
     */
    private fun findFamilyUri(context: Context, safeName: String, subDir: String = ""): Uri? {
        if (!supported()) return null
        val rel = relPath(subDir)
        val target = normalizeName(safeName)
        if (target.isEmpty()) return null
        var bestUri: Uri? = null
        var bestMod = Long.MIN_VALUE
        try {
            context.contentResolver.query(
                collection(),
                arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.DATE_MODIFIED
                ),
                "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?)",
                arrayOf(rel, "$rel/"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val n = c.getString(1) ?: continue
                    if (n == safeName) continue // 精确同名会让位给 findUri
                    if (normalizeName(n) != target) continue
                    val mod = if (c.isNull(2)) 0L else c.getLong(2)
                    if (mod >= bestMod) {
                        bestMod = mod
                        bestUri = ContentUris.withAppendedId(collection(), id)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return bestUri
    }

    /** 已知文本类扩展名(2026-11-15): 用于纠正模型误叠的重复后缀 a.md.txt -> a.md */
    private val KNOWN_TEXT_EXTS = setOf(
        "txt", "md", "markdown", "log", "csv", "json", "xml", "html", "htm",
        "css", "js", "mjs", "ts", "java", "kt", "kts", "c", "cpp", "cc", "h",
        "hpp", "sh", "bash", "zsh", "py", "rb", "php", "go", "rs", "sql",
        "yml", "yaml", "toml", "ini", "cfg", "conf", "env", "properties",
        "bat", "cmd", "ps1", "gradle", "lock"
    )

    /**
     * 文件名规范化(2026-11-15): 命名归一化下沉到 WorkDir 层, 使 workdir_write / file_export /
     * ssh_download 等所有写入入口一致生效。
     *  1) 去掉 MediaStore 冲突去重尾巴: "报告 (1).md" -> "报告.md"
     *  2) 纠正模型误叠的重复文本后缀: "早报-2026-11-15.md.txt" -> "早报-2026-11-15.md"
     */
    fun normalizeName(raw: String): String {
        var n = raw.trim()
        if (n.isEmpty()) return n
        n = n.replace(Regex("\\s*\\(\\d+\\)(?=\\.[^.]+$)"), "")
        if (n.length > 4 && n.lowercase().endsWith(".txt")) {
            val head = n.substring(0, n.length - 4)
            val dot = head.lastIndexOf('.')
            if (dot > 0 && head.substring(dot + 1).lowercase() in KNOWN_TEXT_EXTS) n = head
        }
        return n
    }

    /** 写文件(同名覆盖, 可指定子目录如 "下载"/"文件"; 空=根目录): 返回 true 成功, false 失败 */
    fun write(context: Context, name: String, bytes: ByteArray, subDir: String = ""): Boolean {
        if (!supported()) return false
        val safeName = sanitize(normalizeName(name))
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
            // 2026-11-15: 精确同名不存在时, 找规范化后同名的历史副本(a.md.txt / a (1).md)覆盖,
            // 避免每次写入都被 MediaStore 冲突去重再生成一份新副本
            if (existing == null) existing = findFamilyUri(context, safeName, subDir)
            if (existing != null) {
                // 2026-11-15: 覆盖前先把 MIME_TYPE 对齐规范名后缀, 否则后续改名会被 MediaProvider 按旧 MIME 打回
                runCatching {
                    resolver.update(existing, ContentValues().apply {
                        put(MediaStore.Downloads.MIME_TYPE, mimeFor(safeName))
                    }, null, null)
                }
                // 覆盖已有文件: "wt" 截断写
                resolver.openOutputStream(existing, "wt")?.use { it.write(bytes) } ?: return false
                // 2026-11-15: 命中的是同族旧副本(a.md.txt / a.md (2).txt)时, 覆盖后一并改回规范名
                renameTo(resolver, existing, safeName, subDir)
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
                put(MediaStore.Downloads.MIME_TYPE, mimeFor(safeName))
                put(MediaStore.Downloads.RELATIVE_PATH, rel)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection(), values) ?: return false
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            } finally {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            // 2026-11-15: 落盘名校正。MediaProvider 可能按 MIME 补扩展名或冲突去重改名,
            // 这里把 DISPLAY_NAME 强制改回规范化后的期望名, 保证用户看到的文件名可预期
            renameTo(resolver, uri, safeName, subDir)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 写文件并返回真实落盘名(2026-11-15): MediaProvider 可能按 MIME 补扩展名/冲突改名,
     * renameTo 也可能因同族冲突放弃纠正, 所以写完回读 DISPLAY_NAME 才算数。
     * 失败返回 null; 回读不到时退回规范化后的期望名。
     */
    fun writeNamed(context: Context, name: String, bytes: ByteArray, subDir: String = ""): String? {
        if (!write(context, name, bytes, subDir)) return null
        val safeName = sanitize(normalizeName(name))
        return actualName(context, safeName, subDir) ?: safeName
    }

    /** 回读某个文件在 MediaStore 里的真实 DISPLAY_NAME */
    fun actualName(context: Context, name: String, subDir: String = ""): String? {
        val safeName = sanitize(normalizeName(name))
        if (safeName.isEmpty()) return null
        val uri = findUri(context, safeName, subDir) ?: findFamilyUri(context, safeName, subDir) ?: return null
        return try {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    // ==== 导出目录(2026-11-02): 产物"保存"按钮专用, 与 AI 工作区(Nyral_work)分离 ====
    const val EXPORT_IMG_REL = "Pictures/Nyral"     // 图片: 相册可见
    const val EXPORT_FILE_REL = "Download/Nyral"    // 其他: 独立导出目录(用户可直接管理)

    /**
     * 产物导出: 图片写 Pictures/Nyral(系统相册可见), 其余写 Download/Nyral。
     * 同名覆盖; 经 MediaStore 插入天然入索引, 不存在工作目录的扫描漏检问题。返回展示路径, 失败返回 null。
     */
    fun export(context: Context, name: String, bytes: ByteArray): String? {
        if (!supported()) return null
        val safeName = sanitize(name)
        if (safeName.isEmpty()) return null
        val mime = AttachmentStore.mimeOf(safeName)
        val rel = if (mime.startsWith("image/")) EXPORT_IMG_REL else EXPORT_FILE_REL
        val disp = "/storage/emulated/0/$rel/"
        return try {
            val resolver = context.contentResolver
            val uri = MediaStore.Files.getContentUri("external_primary")
            val existing = resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID),
                "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?) AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(rel, "$rel/", safeName), null
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(uri, c.getLong(0)) else null }
            if (existing != null) {
                // 同名覆盖: "wt" 截断写
                resolver.openOutputStream(existing, "wt")?.use { it.write(bytes) } ?: return null
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val newUri = resolver.insert(uri, values) ?: return null
                try {
                    resolver.openOutputStream(newUri)?.use { it.write(bytes) } ?: return null
                } finally {
                    resolver.update(newUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }
            }
            disp
        } catch (e: Exception) {
            null
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
                // 2026-11-10: 整读加封顶, 超限按读取失败处理, 不赌堆大小(长度未知时保持原行为)
                val declared = try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (e: Exception) { -1L }
                if (declared > WORKDIR_MAX_BYTES) null
                else context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        }
        // 作用域存储 owner 过滤兜底: 用 File API 直读(需 MANAGE_EXTERNAL_STORAGE)
        if (fileReadable()) {
            return try {
                java.io.File(displayPath, n)
                    .takeIf { it.isFile && it.length() <= WORKDIR_MAX_BYTES }?.readBytes()
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
        val s = name.replace(Regex("[\\\\/:*?\"<>|\n\r]"), "_")
            .replace(Regex("[\u0000-\u001f]"), "")
            .trim()
        // B8 修复(2026-10-20): 显式拒绝路径穿越名(../. 与空名)
        return if (s.isEmpty() || s == "." || s == "..") "" else s
    }

    /** 冗余方法: 清理本地缓存目录(未用, 保留接口) */
    @Suppress("unused")
    fun legacyFile(context: Context): File? {
        if (Build.VERSION.SDK_INT >= 29) return null
        return context.getExternalFilesDir(null)?.resolve("workdir")
    }
}
