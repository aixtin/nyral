package io.github.aixtin.droidagent

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 自写 PDF 文本提取器(零依赖, 本地解析, 不接第三方服务):
 * 解析 FlateDecode 压缩的内容流 -> Inflater 解压 -> 提取 BT/ET 块中的 Tj/TJ 文本。
 * 适用于常规文本型 PDF; 扫描件/矢量图型 PDF 提取不到内容时返回空, 由调用方退化处理。
 */
object PdfTextExtractor {

    /** 提取 PDF 文本, 最多 maxChars 字符(防止注入过大); 提取不到返回空串 */
    fun extract(data: ByteArray, maxChars: Int = 8000): String {
        try {
            val text = String(data, Charsets.ISO_8859_1)
            val streamRe = Regex("stream\\r?\\n", RegexOption.IGNORE_CASE)
            val pages = mutableListOf<String>()
            for (m in streamRe.findAll(text)) {
                val start = m.range.last + 1
                val endMark = text.indexOf("endstream", start)
                if (endMark < 0) break
                // 该 stream 所属对象头是否声明 FlateDecode
                val objHead = text.substring(maxOf(0, m.range.first - 600), m.range.first)
                if (!objHead.contains("FlateDecode", ignoreCase = true)) continue
                val raw = text.substring(start, endMark)
                val inflated = tryInflate(raw)
                if (inflated != null) {
                    val pageTxt = extractTj(inflated)
                    if (pageTxt.isNotBlank()) pages.add(pageTxt)
                }
            }
            return pages.joinToString("\n").take(maxChars)
        } catch (e: Exception) {
            return ""
        }
    }

    private fun tryInflate(raw: String): String? {
        val bytes = raw.toByteArray(Charsets.ISO_8859_1)
        var end = bytes.size
        // 去除流数据末尾的换行/空白字节
        while (end > 0 && (bytes[end - 1] == 0x0a.toByte() || bytes[end - 1] == 0x0d.toByte() ||
                    bytes[end - 1] == 0x20.toByte())) end--
        val inflater = Inflater()
        inflater.setInput(bytes, 0, end)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.ISO_8859_1)
        } catch (e: Exception) {
            return null
        } finally {
            inflater.end()
        }
    }

    /** 提取 BT..ET 文本块中的 Tj / TJ 文本 */
    private fun extractTj(content: String): String {
        val sb = StringBuilder()
        try {
            val blocks = Regex("(?is)BT(.*?)ET").findAll(content)
            for (b in blocks) {
                val seg = b.groupValues[1]
                Regex("\\(((?:\\\\.|[^\\\\()])*)\\)\\s*Tj").findAll(seg).forEach {
                    sb.append(unescape(it.groupValues[1]))
                }
                Regex("\\[((?:\\\\.|[^\\\\\\[\\]])*)\\]\\s*TJ").findAll(seg).forEach { m ->
                    Regex("\\(((?:\\\\.|[^\\\\()])*)\\)").findAll(m.groupValues[1]).forEach {
                        sb.append(unescape(it.groupValues[1]))
                    }
                }
                sb.append('\n')
            }
        } catch (e: Exception) {
            // 单个块解析失败不影响整体
        }
        return sb.toString().trim()
    }

    /** 处理 PDF 字符串转义: \( \) \\ \n \r \t \ddd(八进制) */
    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                when (n) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    '(' -> { sb.append('('); i += 2 }
                    ')' -> { sb.append(')'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> if (n in '0'..'7') {
                        var v = 0
                        var j = i + 1
                        var cnt = 0
                        while (j < s.length && cnt < 3 && s[j] in '0'..'7') {
                            v = v * 8 + (s[j] - '0'); j++; cnt++
                        }
                        sb.append(v.toChar())
                        i = j
                    } else { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
