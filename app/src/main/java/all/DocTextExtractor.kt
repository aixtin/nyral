package io.github.aixtin.droidagent

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
}
