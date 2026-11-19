package io.github.aixtin.nyral

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import java.io.File
import java.io.FileInputStream

/**
 * 通用文档文本提取器(零依赖, 本地解析, 不接第三方服务):
 * 按扩展名分发, 从常见办公/文档/压缩包中提取纯文本, 供模型读取。
 * 支持: 纯文本(txt/md/代码/json/csv 等)、PDF、Word(docx)、Excel(xlsx)、
 *       PPT(pptx)、压缩包(zip / tar / tar.gz / gz / 7z / rar)。
 * 提取失败或内容为空返回 null, 由调用方降级为普通附件。
 */
object DocTextExtractor {

    private const val MAX_TEXT = 8000

    private val TEXT_EXTS = setOf(
        "txt", "md", "markdown", "log", "json", "csv", "xml", "html", "htm",
        "yaml", "yml", "ini", "conf", "cfg", "properties", "toml", "rst",
        "java", "kt", "kts", "py", "js", "ts", "tsx", "jsx", "vue", "c", "h",
        "cpp", "hpp", "go", "rs", "sh", "bat", "ps1", "sql", "gradle", "rb", "php"
    )

    fun extract(name: String, data: ByteArray): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return try {
            when (ext) {
                "pdf" -> PdfTextExtractor.extract(data, MAX_TEXT).ifBlank { null }
                "docx" -> extractDocx(data)
                "xlsx", "xlsm" -> extractXlsx(data)
                "pptx" -> extractPptx(data)
                "zip" -> extractZip(data)
                "tar" -> parseTarText(data)
                "tgz" -> parseTarText(gunzip(data) ?: return null)
                "gz" -> decodeText(gunzip(data) ?: return null)
                "7z" -> extract7z(data)
                "rar" -> extractRar(data)
                else -> if (ext in TEXT_EXTS) decodeText(data) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 纯文本解码: 去 BOM, 优先 UTF-8, 出现大量替换符则试 GBK, 截断到 limit */
    private fun decodeText(bytes: ByteArray, limit: Int = MAX_TEXT): String? {
        var b = bytes
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            b = b.copyOfRange(3, b.size)
        }
        var s = String(b, Charsets.UTF_8)
        if (s.count { it == '\uFFFD' } > s.length / 20) {
            try { s = String(b, java.nio.charset.Charset.forName("GBK")) } catch (e: Exception) { /* 保持原样 */ }
        }
        return s.trim().take(limit)
    }

    /** docx: 解 zip 取 word/document.xml, 按段落提取 <w:t> 文本 */
    private fun extractDocx(data: ByteArray): String? {
        val doc = readZipEntry(data, "word/document.xml") ?: return null
        val xml = String(doc, Charsets.UTF_8)
        val textRe = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        xml.split("</w:p>").forEach { para ->
            val t = textRe.findAll(para).joinToString(" ") { it.groupValues[1].trim() }
            if (t.isNotBlank()) sb.append(t).append('\n')
        }
        return sb.toString().trim().take(MAX_TEXT).ifBlank { null }
    }

    /** xlsx: 解 zip 取 xl/sharedStrings.xml, 提取 <t> 文本 */
    private fun extractXlsx(data: ByteArray): String? {
        val ss = readZipEntry(data, "xl/sharedStrings.xml") ?: return null
        val xml = String(ss, Charsets.UTF_8)
        val textRe = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
        val out = textRe.findAll(xml).joinToString(" ") { it.groupValues[1].trim() }
        return out.take(MAX_TEXT).ifBlank { null }
    }

    /** pptx: 遍历 ppt/slides/slideN.xml 提取 <a:t> 文本 */
    private fun extractPptx(data: ByteArray): String? {
        val zip = ZipInputStream(ByteArrayInputStream(data))
        val sb = StringBuilder()
        val textRe = Regex("<a:t[^>]*>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
        try {
            var e = zip.nextEntry
            while (e != null) {
                if (e.name.startsWith("ppt/slides/slide") && e.name.endsWith(".xml")) {
                    val xml = String(zip.readBytes(), Charsets.UTF_8)
                    val t = textRe.findAll(xml).joinToString(" ") { it.groupValues[1].trim() }
                    if (t.isNotBlank()) sb.append(t).append('\n')
                }
                e = zip.nextEntry
            }
        } finally {
            zip.close()
        }
        return sb.toString().trim().take(MAX_TEXT).ifBlank { null }
    }

    /** 7z: 遍历条目, 提取小文本文件内容(前 5 个), 其余计数 */
    private fun extract7z(data: ByteArray): String? {
        val sb = StringBuilder()
        var textCount = 0
        var otherCount = 0
        val tmp = File.createTempFile("cc7z", ".7z")
        try {
            tmp.writeBytes(data)
            val sevenZ = SevenZFile(tmp)
            try {
                var e: SevenZArchiveEntry? = sevenZ.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val inner = e.name
                        val iext = inner.substringAfterLast('.', "").lowercase()
                        if (iext in TEXT_EXTS && e.size in 1..200_000) {
                            val buf = ByteArray(e.size.toInt())
                            var off = 0
                            while (off < buf.size) {
                                val n = sevenZ.read(buf, off, buf.size - off)
                                if (n < 0) break
                                off += n
                            }
                            val txt = decodeText(buf, 1500)
                            if (txt != null && txt.isNotBlank()) {
                                sb.append("── $inner\n$txt\n")
                                textCount++
                                if (textCount >= 5) break
                            }
                        } else {
                            otherCount++
                        }
                    }
                    e = sevenZ.nextEntry
                }
            } finally {
                sevenZ.close()
            }
        } catch (e: Exception) {
            return null
        } finally {
            tmp.delete()
        }
        val head = "【压缩包文件列表】含文本内容文件 $textCount 个, 其他文件/目录 $otherCount 个\n"
        return (head + sb.toString()).take(MAX_TEXT).ifBlank { null }
    }

    /** rar: 遍历条目, 提取小文本文件内容(前 5 个), 其余计数 */
    private fun extractRar(data: ByteArray): String? {
        val sb = StringBuilder()
        var textCount = 0
        var otherCount = 0
        val tmp = File.createTempFile("ccrar", ".rar")
        try {
            tmp.writeBytes(data)
            val rar = Archive(FileInputStream(tmp))
            try {
                var e: FileHeader? = rar.nextFileHeader()
                while (e != null) {
                    if (!e.isDirectory()) {
                        val inner = e.fileName.trim()
                        val iext = inner.substringAfterLast('.', "").lowercase()
                        if (iext in TEXT_EXTS && e.fullUnpackSize in 1..200_000) {
                            val content = rar.getInputStream(e).readBytes()
                            val txt = decodeText(content, 1500)
                            if (txt != null && txt.isNotBlank()) {
                                sb.append("── $inner\n$txt\n")
                                textCount++
                                if (textCount >= 5) break
                            }
                        } else {
                            otherCount++
                        }
                    }
                    e = rar.nextFileHeader()
                }
            } finally {
                rar.close()
            }
        } catch (e: Exception) {
            return null
        } finally {
            tmp.delete()
        }
        val head = "【压缩包文件列表】含文本内容文件 $textCount 个, 其他文件/目录 $otherCount 个\n"
        return (head + sb.toString()).take(MAX_TEXT).ifBlank { null }
    }

    /** zip: 列出清单 + 提取其中小文本文件内容(前 5 个), 其余计数 */
    private fun extractZip(data: ByteArray): String? {
        val zip = ZipInputStream(ByteArrayInputStream(data))
        val sb = StringBuilder()
        var textCount = 0
        var otherCount = 0
        try {
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val inner = e.name
                    val iext = inner.substringAfterLast('.', "").lowercase()
                    if (iext in TEXT_EXTS && e.size in 1..200_000) {
                        val txt = decodeText(zip.readBytes(), 1500)
                        if (txt != null && txt.isNotBlank()) {
                            sb.append("── $inner\n$txt\n")
                            textCount++
                            if (textCount >= 5) break
                        }
                    } else {
                        otherCount++
                    }
                }
                e = zip.nextEntry
            }
        } finally {
            zip.close()
        }
        val head = "【压缩包文件列表】含文本内容文件 $textCount 个, 其他文件/目录 $otherCount 个\n"
        return (head + sb.toString()).take(MAX_TEXT).ifBlank { null }
    }

    /** tar / tar.gz: 解析 tar 条目, 提取小文本文件内容(前 5 个), 其余计数 */
    private fun parseTarText(bytes: ByteArray): String? {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        var off = 0
        while (off + 512 <= bytes.size) {
            val header = bytes.copyOfRange(off, off + 512)
            if (header.all { it == 0.toByte() }) break
            val name = String(header.copyOfRange(0, 100), Charsets.UTF_8).trimEnd('\u0000', ' ')
            val sizeStr = String(header.copyOfRange(124, 136), Charsets.US_ASCII).trimEnd('\u0000', ' ')
            val size = sizeStr.toLongOrNull(8) ?: 0
            val typeFlag = if (header.size > 156) header[156].toInt() else 0
            val dataStart = off + 512
            if (typeFlag != 5 && name.isNotBlank()) {
                val content = if (size > 0 && dataStart + size <= bytes.size)
                    bytes.copyOfRange(dataStart, dataStart + size.toInt()) else ByteArray(0)
                entries.add(name to content)
            }
            val padded = size.toInt() + ((512 - (size % 512).toInt()) % 512)
            off = dataStart + padded
        }
        val sb = StringBuilder()
        var textCount = 0
        var otherCount = 0
        entries.forEach { (inner, content) ->
            val iext = inner.substringAfterLast('.', "").lowercase()
            if (iext in TEXT_EXTS && content.size in 1..200_000) {
                val txt = decodeText(content, 1500)
                if (txt != null && txt.isNotBlank()) {
                    sb.append("── $inner\n$txt\n")
                    textCount++
                }
            } else {
                otherCount++
            }
        }
        val head = "【压缩包文件列表】含文本内容文件 $textCount 个, 其他文件/目录 $otherCount 个\n"
        return (head + sb.toString()).take(MAX_TEXT).ifBlank { null }
    }

    private fun gunzip(data: ByteArray): ByteArray? {
        return try {
            val gz = GZIPInputStream(ByteArrayInputStream(data))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = gz.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            } finally {
                gz.close()
            }
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /** 解 zip 中指定条目为字节数组; 不存在返回 null */
    private fun readZipEntry(data: ByteArray, target: String): ByteArray? {
        val zip = ZipInputStream(ByteArrayInputStream(data))
        try {
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == target) {
                    return zip.readBytes()
                }
                e = zip.nextEntry
            }
        } finally {
            zip.close()
        }
        return null
    }

    // ===================== 2026-11-19: 大文件流式解析入口 =====================
    // 背景: 附件整读 ByteArray 时, 1.58GB 刷机包会 OOM 闪退。压缩包/纯文本本质上
    // 可以流式处理(只读中央目录或头部), 内存占用与文件体积无关, 因此超过整读上限
    // 的附件改走本入口 —— 不是拒绝, 而是照常给出结构清单 + 内部小文本内容。
    // 返回 null 表示该类型无法流式解析(如 pdf/docx 需要全文), 由调用方给出替代方案。

    private const val LIST_MAX_ENTRIES = 300        // 清单最多列出的条目数
    private const val LIST_TOP_LARGE = 10           // 额外列出最大的 N 个文件
    private const val INNER_TEXT_MAX = 200_000L     // 单个内部文本条目的解压上限(字节)
    private const val INNER_TEXT_FILES = 4          // 最多提取几个内部文本条目
    private const val TOP_DIRS = 25                 // 顶层目录最多列出数
    private const val PROBE_MAX = 8192L              // 无扩展名条目的文本探测读取上限

    /** 文本探测: 无扩展名的清单文件(如 OTA 包的 META-INF/metadata)也读进来。
     *  判据: 不含 NUL, 且控制字符占比 < 10%(二进制 protobuf/证书会被挡掉)。 */
    private fun looksLikeText(b: ByteArray): Boolean {
        if (b.isEmpty()) return false
        var bad = 0
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c == 0) return false
            val ok = c == 9 || c == 10 || c == 13 || c >= 32
            if (!ok) bad++
        }
        return bad.toDouble() / b.size < 0.10
    }

    /** 读流头部, 最多 limit 字节 */
    private fun readHead(input: java.io.InputStream, limit: Long): ByteArray {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(4 * 1024)
        var total = 0L
        try {
            while (total < limit) {
                val n = input.read(chunk, 0, minOf(chunk.size.toLong(), limit - total).toInt())
                if (n <= 0) break
                buf.write(chunk, 0, n)
                total += n
            }
        } catch (_: Throwable) {
        }
        return buf.toByteArray()
    }

    private fun fmtSize(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> String.format("%.2f GB", b / 1024.0 / 1024 / 1024)
        b >= 1024L * 1024 -> String.format("%.1f MB", b / 1024.0 / 1024)
        b >= 1024L -> String.format("%.1f KB", b / 1024.0)
        else -> "$b B"
    }

    fun extractFromFile(name: String, f: File): String? {
        val lower = name.lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return try {
            when {
                // OOXML 需精确抽取, 走 listing 只会读到 xml 噪声, 不在此列
                ext in setOf("zip", "apk", "jar", "epub", "aar") -> listZipFile(f)
                ext == "7z" -> list7zFile(f)
                ext == "rar" -> listRarFile(f)
                ext == "tar" || ext == "tgz" || lower.endsWith(".tar.gz") -> listTarFile(f)
                ext == "gz" -> headOfGzip(f)
                ext in TEXT_EXTS -> headOfText(f)
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** 兜底摘要头: 文件级信息 + 顶层结构 */
    private fun archiveHead(kind: String, f: File, total: Int, dirs: List<String>): String {
        val sb = StringBuilder()
        sb.append("【$kind 流式解析】${f.name}（${fmtSize(f.length())}, 共 $total 个文件条目, 未整包载入内存）\n")
        if (dirs.isNotEmpty()) {
            sb.append("顶层结构: ")
            sb.append(dirs.take(TOP_DIRS).joinToString(" / "))
            if (dirs.size > TOP_DIRS) sb.append(" ...(共 ${dirs.size} 项)")
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun topDirsOf(names: Collection<String>): List<String> {
        val set = LinkedHashSet<String>()
        for (n in names) {
            val i = n.indexOf('/')
            if (i > 0) set.add(n.substring(0, i) + "/")
            if (set.size > 200) break
        }
        return set.toList()
    }

    /** zip/apk/jar/epub: 读中央目录(ZipFile 随机访问), 内存与包体无关 */
    private fun listZipFile(f: File): String? {
        val zf = java.util.zip.ZipFile(f)
        try {
            val names = ArrayList<String>(256)
            val large = ArrayList<Pair<String, Long>>()
            val inner = StringBuilder()
            var total = 0
            var textCount = 0
            val en = zf.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                total++
                if (names.size < LIST_MAX_ENTRIES) names.add(e.name)
                val sz = if (e.size < 0) 0L else e.size
                large.add(e.name to sz)
                val iext = e.name.substringAfterLast('.', "").lowercase()
                // 2026-11-19: 除已知文本扩展名外, 对无扩展名/未知扩展名的小条目做一次文本探测,
                // 否则 OTA 包的 META-INF/com/android/metadata(含 ota-type/post-build)会被漏掉
                val probe = iext in TEXT_EXTS || (iext.isEmpty() || iext == "pb" || iext == "cert") && sz in 1..PROBE_MAX
                if (textCount < INNER_TEXT_FILES && probe && sz in 1..INNER_TEXT_MAX) {
                    val headBytes = zf.getInputStream(e).use { readHead(it, PROBE_MAX) }
                    val txt = if (iext in TEXT_EXTS || looksLikeText(headBytes)) decodeText(headBytes, 1500) else null
                    if (!txt.isNullOrBlank()) {
                        inner.append("── ${e.name}\n").append(txt.trim()).append('\n')
                        textCount++
                    }
                }
            }
            val sb = StringBuilder()
            sb.append(archiveHead("压缩包", f, total, topDirsOf(names)))
            if (names.isNotEmpty()) {
                sb.append("条目预览:\n")
                names.forEach { sb.append("  ").append(it).append('\n') }
                if (total > names.size) sb.append("  ...(其余 ${total - names.size} 个条目略)\n")
            }
            val big = large.sortedByDescending { it.second }.take(LIST_TOP_LARGE).filter { it.second > 0 }
            if (big.isNotEmpty()) {
                sb.append("最大条目:\n")
                big.forEach { sb.append("  ").append(it.first).append("  ").append(fmtSize(it.second)).append('\n') }
            }
            if (inner.isNotEmpty()) sb.append("内部文本内容:\n").append(inner)
            return sb.toString().take(MAX_TEXT * 2)
        } finally {
            zf.close()
        }
    }

    /** 7z: SevenZFile 直接读文件, 不落临时副本 */
    private fun list7zFile(f: File): String? {
        val sz = SevenZFile(f)
        try {
            val names = ArrayList<String>(128)
            val large = ArrayList<Pair<String, Long>>()
            val inner = StringBuilder()
            var total = 0
            var textCount = 0
            while (true) {
                val e = sz.nextEntry ?: break
                if (e.isDirectory) continue
                total++
                if (names.size < LIST_MAX_ENTRIES) names.add(e.name)
                val size = if (e.size < 0) 0L else e.size
                large.add(e.name to size)
                val iext = e.name.substringAfterLast('.', "").lowercase()
                val probe = iext in TEXT_EXTS || (iext.isEmpty() || iext == "pb" || iext == "cert") && size in 1..PROBE_MAX
                if (textCount < INNER_TEXT_FILES && probe && size in 1..INNER_TEXT_MAX) {
                    val want = minOf(size, PROBE_MAX).toInt()
                    val buf = ByteArray(want)
                    var off = 0
                    while (off < buf.size) {
                        val n = sz.read(buf, off, buf.size - off)
                        if (n < 0) break
                        off += n
                    }
                    val headBytes = buf.copyOf(off)
                    val txt = if (iext in TEXT_EXTS || looksLikeText(headBytes)) decodeText(headBytes, 1500) else null
                    if (!txt.isNullOrBlank()) {
                        inner.append("── ").append(e.name).append('\n').append(txt.trim()).append('\n')
                        textCount++
                    }
                }
            }
            return finishList("7z 压缩包", f, total, names, large, inner)
        } finally {
            sz.close()
        }
    }

    /** rar: junrar 流式遍历 */
    private fun listRarFile(f: File): String? {
        val rar = Archive(FileInputStream(f))
        try {
            val names = ArrayList<String>(128)
            val large = ArrayList<Pair<String, Long>>()
            val inner = StringBuilder()
            var total = 0
            var textCount = 0
            var e = rar.nextFileHeader()
            while (e != null) {
                if (!e.isDirectory) {
                    total++
                    val nm = e.fileName.trim()
                    if (names.size < LIST_MAX_ENTRIES) names.add(nm)
                    val size = e.fullUnpackSize
                    large.add(nm to size)
                    val iext = nm.substringAfterLast('.', "").lowercase()
                    val probe = iext in TEXT_EXTS || (iext.isEmpty() || iext == "pb" || iext == "cert") && size in 1..PROBE_MAX
                    if (textCount < INNER_TEXT_FILES && probe && size in 1..INNER_TEXT_MAX) {
                        val headBytes = rar.getInputStream(e).use { readHead(it, PROBE_MAX) }
                        val txt = if (iext in TEXT_EXTS || looksLikeText(headBytes)) decodeText(headBytes, 1500) else null
                        if (!txt.isNullOrBlank()) {
                            inner.append("── ").append(nm).append('\n').append(txt.trim()).append('\n')
                            textCount++
                        }
                    }
                }
                e = rar.nextFileHeader()
            }
            return finishList("rar 压缩包", f, total, names, large, inner)
        } finally {
            rar.close()
        }
    }

    /** tar / tar.gz: 512 字节头流式遍历, 不整读 */
    private fun listTarFile(f: File): String? {
        val lower = f.name.lowercase()
        val raw: java.io.InputStream = FileInputStream(f)
        val ins: java.io.InputStream =
            if (lower.endsWith(".gz") || lower.endsWith(".tgz")) GZIPInputStream(java.io.BufferedInputStream(raw, 64 * 1024))
            else java.io.BufferedInputStream(raw, 64 * 1024)
        try {
            val names = ArrayList<String>(128)
            val large = ArrayList<Pair<String, Long>>()
            val inner = StringBuilder()
            var total = 0
            var textCount = 0
            val header = ByteArray(512)
            while (true) {
                if (!readFully(ins, header, 512)) break
                var empty = true
                for (i in 0 until 512) if (header[i].toInt() != 0) { empty = false; break }
                if (empty) break
                var nm = String(header, 0, 100, Charsets.UTF_8).trim('\u0000', ' ')
                val prefix = String(header, 345, 155, Charsets.UTF_8).trim('\u0000', ' ')
                if (prefix.isNotEmpty()) nm = "$prefix/$nm"
                val sizeStr = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ')
                val size = try { java.lang.Long.parseLong(sizeStr, 8) } catch (e: Exception) { 0L }
                val type = header[156].toInt().toChar()
                val isFile = (type == '0' || type == '\u0000')
                val name = nm.trim().trimStart('.', '/')
                if (isFile && name.isNotEmpty()) {
                    total++
                    if (names.size < LIST_MAX_ENTRIES) names.add(name)
                    large.add(name to size)
                    val iext = name.substringAfterLast('.', "").lowercase()
                    val probe = iext in TEXT_EXTS || (iext.isEmpty() || iext == "pb" || iext == "cert") && size in 1..PROBE_MAX
                    if (textCount < INNER_TEXT_FILES && probe && size in 1..INNER_TEXT_MAX) {
                        val want = minOf(size, PROBE_MAX).toInt()
                        val buf = ByteArray(want)
                        if (readFully(ins, buf, buf.size)) {
                            val headBytes = buf
                            val txt = if (iext in TEXT_EXTS || looksLikeText(headBytes)) decodeText(headBytes, 1500) else null
                            if (!txt.isNullOrBlank()) {
                                inner.append("── ").append(name).append('\n').append(txt.trim()).append('\n')
                                textCount++
                            }
                        }
                        // 只读了头部 want 字节, 剩余按 tar 512 对齐一并跳过
                        skipFully(ins, (size - want) + ((512 - (size % 512)) % 512))
                        continue
                    }
                }
                skipFully(ins, size + ((512 - (size % 512)) % 512))
            }
            return finishList(if (lower.endsWith(".gz") || lower.endsWith(".tgz")) "tar.gz 压缩包" else "tar 包",
                f, total, names, large, inner)
        } finally {
            try { ins.close() } catch (_: Throwable) {}
        }
    }

    private fun readFully(ins: java.io.InputStream, buf: ByteArray, len: Int): Boolean {
        var off = 0
        while (off < len) {
            val n = ins.read(buf, off, len - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(ins: java.io.InputStream, len: Long) {
        var left = len
        val buf = ByteArray(32 * 1024)
        while (left > 0) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) return
            left -= n
        }
    }

    private fun finishList(
        kind: String, f: File, total: Int,
        names: List<String>, large: List<Pair<String, Long>>, inner: StringBuilder
    ): String {
        val sb = StringBuilder()
        sb.append(archiveHead(kind, f, total, topDirsOf(names)))
        if (names.isNotEmpty()) {
            sb.append("条目预览:\n")
            names.forEach { sb.append("  ").append(it).append('\n') }
            if (total > names.size) sb.append("  ...(其余 ${total - names.size} 个条目略)\n")
        }
        val big = large.sortedByDescending { it.second }.take(LIST_TOP_LARGE).filter { it.second > 0 }
        if (big.isNotEmpty()) {
            sb.append("最大条目:\n")
            big.forEach { sb.append("  ").append(it.first).append("  ").append(fmtSize(it.second)).append('\n') }
        }
        if (inner.isNotEmpty()) sb.append("内部文本内容:\n").append(inner)
        return sb.toString().take(MAX_TEXT * 2)
    }

    /** 纯文本大文件: 只读头部 */
    private fun headOfText(f: File): String? {
        FileInputStream(f).use { ins ->
            val buf = ByteArray(64 * 1024)
            var off = 0
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            if (off <= 0) return null
            val head = "【大文本流式解析】${f.name}（${fmtSize(f.length())}, 仅读取开头 ${fmtSize(off.toLong())}）\n"
            val txt = decodeText(buf.copyOf(off), MAX_TEXT) ?: return null
            return (head + txt).take(MAX_TEXT * 2)
        }
    }

    /** .gz 单文件: 解压头部 */
    private fun headOfGzip(f: File): String? {
        GZIPInputStream(java.io.BufferedInputStream(FileInputStream(f), 64 * 1024), 64 * 1024).use { gz ->
            val buf = ByteArray(64 * 1024)
            var off = 0
            while (off < buf.size) {
                val n = gz.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            if (off <= 0) return null
            val head = "【gzip 流式解析】${f.name}（${fmtSize(f.length())}, 解压后仅取开头 ${fmtSize(off.toLong())}）\n"
            val txt = decodeText(buf.copyOf(off), MAX_TEXT) ?: return null
            return (head + txt).take(MAX_TEXT * 2)
        }
    }

}
