# -*- coding: utf-8 -*-
"""Nyral 工作目录三级子目录分类补丁(2026-09-21):
日志/主日志、日志/辅日志、文件/、下载/、截图/; 根目录保留给 AI 工作区。
"""
import sys, io, shutil, os, time
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
BASE = "/home/ymz/Nyral/android-agent-app/app/src/main"
ALL = BASE + "/java/all"

fails = []

def patch(path, old, new):
    p = os.path.join(ALL if not path.endswith("strings.xml") else BASE, path) if os.path.sep not in path else path
    if not os.path.exists(p):
        # path 为完整相对路径时
        p = os.path.join(BASE, path.lstrip("/")) if "res/values" in path else os.path.join(ALL, path)
    if not os.path.exists(p):
        print(f"[MISS] {path}")
        fails.append(path)
        return
    with open(p, encoding='utf-8') as f:
        s = f.read()
    cnt = s.count(old)
    if cnt != 1:
        print(f"[SKIP-{cnt}] {path}: {old[:70]!r}")
        fails.append(path)
        return
    s = s.replace(old, new)
    with open(p, 'w', encoding='utf-8') as f:
        f.write(s)
    print(f"[OK] {path}")

# ---------- 备份 ----------
bk = os.path.join(ALL, "backups")
os.makedirs(bk, exist_ok=True)
ts = time.strftime("%Y%m%d_%H%M%S")
for name in ["WorkDir.kt", "LogActivity.kt", "FileListActivity.kt", "WebTools.kt", "SshTools.kt", "LocalEngine.kt", "UiControlService.kt"]:
    src = os.path.join(ALL, name)
    if os.path.exists(src):
        shutil.copy2(src, os.path.join(bk, f"{name}.{ts}.bak"))
shutil.copy2(os.path.join(BASE, "res/values/strings.xml"), os.path.join(bk, f"strings.xml.{ts}.bak"))
print(f"[BACKUP] -> {bk} ({ts})")

# ---------- WorkDir.kt ----------
patch("WorkDir.kt",
"""    const val RELATIVE = "Download/Nyral_work"
    val displayPath: String get() = "/storage/emulated/0/Download/Nyral_work/"
""",
"""    const val RELATIVE = "Download/Nyral_work"
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
""")

patch("WorkDir.kt",
"""                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("$RELATIVE%"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
""",
"""                "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?)",
                arrayOf(RELATIVE, "$RELATIVE/"),
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
""")

patch("WorkDir.kt",
"""                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("$RELATIVE%"),
                null
""",
"""                "(${MediaStore.Downloads.RELATIVE_PATH} = ? OR ${MediaStore.Downloads.RELATIVE_PATH} = ?)",
                arrayOf(RELATIVE, "$RELATIVE/"),
                null
""")

patch("WorkDir.kt",
"""    /** 按文件名定位已存在文件的 content uri */
    private fun findUri(context: Context, name: String): Uri? {
        if (!supported()) return null
        val resolver = context.contentResolver
        return try {
            resolver.query(
                collection(),
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf("$RELATIVE%", name),
                null
            )?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(collection(), c.getLong(0)) else null
            }
        } catch (e: Exception) {
            null
        }
    }
""",
"""    /** 按文件名+子目录定位已存在文件的 content uri(subDir 为空=根目录) */
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
""")

patch("WorkDir.kt",
"""    /** 写文件(同名覆盖): 返回 true 成功, false 失败 */
    fun write(context: Context, name: String, bytes: ByteArray): Boolean {
        if (!supported()) return false
        val safeName = sanitize(name)
        if (safeName.isEmpty()) return false
        val resolver = context.contentResolver
        return try {
            var existing = findUri(context, safeName)
            if (existing == null) {
                rescan(context)
                existing = findUri(context, safeName)
            }
""",
"""    /** 写文件(同名覆盖, 可指定子目录如 \"下载\"/\"文件\"; 空=根目录): 返回 true 成功, false 失败 */
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
""")

patch("WorkDir.kt",
"""                try {
                    val f = java.io.File(displayPath, safeName)
                    if (f.isFile) { f.writeBytes(bytes); return true }
                } catch (_: Exception) {
                }
""",
"""                try {
                    val f = java.io.File(outDir, safeName)
                    if (f.isFile) { f.writeBytes(bytes); return true }
                } catch (_: Exception) {
                }
""")

patch("WorkDir.kt",
"""                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE)
""",
"""                put(MediaStore.Downloads.RELATIVE_PATH, rel)
""")

# ---------- LogActivity.kt ----------
patch("LogActivity.kt",
"""        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val n1 = exportOne(LogStore.MAIN, "Nyral_主日志_" + stamp + ".txt")
        val n2 = exportOne(LogStore.MEM, "Nyral_辅助AI日志_" + stamp + ".txt")
        val msg = if (n1 != null && n2 != null) getString(R.string.log_13) + n1 + "、" + n2
                  else getString(R.string.log_14)
""",
"""        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val n1 = exportOne(LogStore.MAIN, "Nyral_主日志_" + stamp + ".txt", WorkDir.SUB_DIR_LOG_MAIN)
        val n2 = exportOne(LogStore.MEM, "Nyral_辅助AI日志_" + stamp + ".txt", WorkDir.SUB_DIR_LOG_MEM)
        val msg = if (n1 != null && n2 != null)
            getString(R.string.log_13) + WorkDir.displaySubPath(WorkDir.SUB_DIR_LOG_MAIN) + n1 + "、" + n2
                  else getString(R.string.log_14)
""")

patch("LogActivity.kt",
"""    /** 导出单个日志到系统下载目录, 返回实际文件名(可能带序号), 失败返回 null */
    private fun exportOne(tag: String, base: String): String? {
        val sb = StringBuilder()
        LogStore.history(tag).forEach { sb.append(it.line()).append('\\n') }
        val content = sb.toString()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val name = uniqueName29(base)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
""",
"""    /** 导出单个日志到工作目录子目录(日志/主日志|日志/辅日志), 返回实际文件名(可能带序号), 失败返回 null */
    private fun exportOne(tag: String, base: String, subDir: String): String? {
        val sb = StringBuilder()
        LogStore.history(tag).forEach { sb.append(it.line()).append('\\n') }
        val content = sb.toString()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val name = uniqueName29(base, subDir)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, WorkDir.relPath(subDir))
                }
""")

patch("LogActivity.kt",
"""            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val name = uniqueNameOld(dir, base)
                File(dir, name).writeText(content, Charsets.UTF_8)
                name
            }
""",
"""            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nyral_work/" + subDir)
                if (!dir.exists()) dir.mkdirs()
                val name = uniqueNameOld(dir, base)
                File(dir, name).writeText(content, Charsets.UTF_8)
                name
            }
""")

patch("LogActivity.kt",
"""    /** API29+ MediaStore 查重名 */
    private fun uniqueName29(base: String): String {
        var name = base
        var n = 2
        while (exists29(name)) {
            val dot = base.lastIndexOf('.')
            name = if (dot > 0) base.substring(0, dot) + "_" + n + base.substring(dot) else base + "_" + n
            n++
        }
        return name
    }

    private fun exists29(name: String): Boolean {
        val c = contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(name), null)
""",
"""    /** API29+ MediaStore 查重名(限定子目录) */
    private fun uniqueName29(base: String, subDir: String): String {
        var name = base
        var n = 2
        while (exists29(name, subDir)) {
            val dot = base.lastIndexOf('.')
            name = if (dot > 0) base.substring(0, dot) + "_" + n + base.substring(dot) else base + "_" + n
            n++
        }
        return name
    }

    private fun exists29(name: String, subDir: String): Boolean {
        val rel = WorkDir.relPath(subDir)
        val c = contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "(" + MediaStore.MediaColumns.RELATIVE_PATH + " = ? OR " + MediaStore.MediaColumns.RELATIVE_PATH + " = ?) AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
            arrayOf(rel, "$rel/", name), null)
""")

# ---------- FileListActivity.kt ----------
patch("FileListActivity.kt",
"""        val ok = WorkDir.write(this, outName, bytes)
        Toast.makeText(this, if (ok) getString(R.string.files_exported, "${WorkDir.displayPath}$outName")
            else getString(R.string.files_export_fail, ""), Toast.LENGTH_SHORT).show()
""",
"""        val ok = WorkDir.write(this, outName, bytes, WorkDir.SUB_DIR_FILES)
        Toast.makeText(this, if (ok) getString(R.string.files_exported, "${WorkDir.displaySubPath(WorkDir.SUB_DIR_FILES)}$outName")
            else getString(R.string.files_export_fail, ""), Toast.LENGTH_SHORT).show()
""")

# ---------- WebTools.kt ----------
patch("WebTools.kt",
"""            if (!WorkDir.write(context, name, bytes)) return DlResult(false, "写入工作目录失败: $name")
            return DlResult(true, "下载成功: ${WorkDir.displayPath}$name (${bytes.size} 字节)")
""",
"""            if (!WorkDir.write(context, name, bytes, WorkDir.SUB_DIR_DOWNLOADS)) return DlResult(false, "写入工作目录失败: $name")
            return DlResult(true, "下载成功: ${WorkDir.displaySubPath(WorkDir.SUB_DIR_DOWNLOADS)}$name (${bytes.size} 字节)")
""")

# ---------- SshTools.kt ----------
patch("SshTools.kt",
"""            if (!WorkDir.write(context, localName, bytes))
                return "错误: 写入工作目录失败: $localName"
            Log.i(tag, "download done: $remote -> $localName (${bytes.size} bytes)")
            "下载成功: $remote -> ${WorkDir.displayPath}$localName (${bytes.size} 字节)"
""",
"""            if (!WorkDir.write(context, localName, bytes, WorkDir.SUB_DIR_DOWNLOADS))
                return "错误: 写入工作目录失败: $localName"
            Log.i(tag, "download done: $remote -> $localName (${bytes.size} bytes)")
            "下载成功: $remote -> ${WorkDir.displaySubPath(WorkDir.SUB_DIR_DOWNLOADS)}$localName (${bytes.size} 字节)"
""")

# ---------- LocalEngine.kt ----------
patch("LocalEngine.kt",
"""                val ok = WorkDir.write(context, raw, bytes)
                if (ok) "已导出到工作目录 ${WorkDir.displayPath}$raw (用户可见可改)" else "错误: 导出失败(工作目录不可写)"
""",
"""                val ok = WorkDir.write(context, raw, bytes, WorkDir.SUB_DIR_FILES)
                if (ok) "已导出到工作目录 ${WorkDir.displaySubPath(WorkDir.SUB_DIR_FILES)}$raw (用户可见可改)" else "错误: 导出失败(工作目录不可写)"
""")

patch("LocalEngine.kt",
"""        ToolSpec("web_download", "下载网页/文件并保存到手机工作目录; 返回"下载成功"即表示文件已落盘, 直接向用户报告结果, 不要再调用 workdir 等工具重复验证; site_auth.json 已配置的域名 Cookie 会自动注入", "JSON: {"url":"https://...","name":"可选文件名"}"),
""",
"""        ToolSpec("web_download", "下载网页/文件并保存到手机工作目录 下载/ 子目录; 返回"下载成功"即表示文件已落盘, 直接向用户报告结果, 不要再调用 workdir 等工具重复验证; site_auth.json 已配置的域名 Cookie 会自动注入", "JSON: {"url":"https://...","name":"可选文件名"}"),
""")

# ---------- UiControlService.kt ----------
patch("UiControlService.kt",
"""            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nyral_work")
            dir.mkdirs()
            val f = File(dir, "app_screenshot_${System.currentTimeMillis()}.png")
""",
"""            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nyral_work/${WorkDir.SUB_DIR_SHOTS}")
            dir.mkdirs()
            val f = File(dir, "app_screenshot_${System.currentTimeMillis()}.png")
""")

# ---------- strings.xml ----------
patch("res/values/strings.xml",
'    <string name="log_13">已导出到 下载/</string>',
'    <string name="log_13">已导出到 </string>')

print("=" * 40)
if fails:
    print("FAILED:", fails)
    sys.exit(1)
print("ALL PATCHED OK")
