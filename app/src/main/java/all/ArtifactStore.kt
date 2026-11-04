package io.github.aixtin.nyral

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 产物通道(2026-11-02): AI 产物的登记与落盘。
 * - 实体文件复用私有附件库(files/attachments/), 用户无感; meta 写 artifact_name 标记来源
 * - 消息流只存 [产物:名称](att://fileName) 引用, 与附件同一套卡片/预览基建
 * - 同名再登记 = 覆盖同一实体文件并升版本, 历史卡片打开始终展示最新内容
 */
object ArtifactStore {
    private const val MAX_BYTES = 512 * 1024

    /** 按扩展名推断产物 mime(文本类为主, 图片生成另行扩展) */
    fun mimeOf(name: String): String = when (name.lowercase().substringAfterLast('.', "")) {
        "html", "htm" -> "text/html"
        "svg" -> "image/svg+xml"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "py" -> "text/x-python"
        "kt", "kts" -> "text/x-kotlin"
        "java" -> "text/x-java"
        "sh" -> "text/x-sh"
        "csv" -> "text/csv"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "zip" -> "application/zip"
        else -> "text/plain"
    }

    private fun metaFile(context: Context, fileName: String): File =
        File(File(context.filesDir, "attachments"), "$fileName.meta.json")

    /** 找同名产物记录(扫 meta 的 artifact_name 标记), 返回引用 key 或 null */
    private fun findExisting(context: Context, artName: String): String? {
        val dir = File(context.filesDir, "attachments")
        if (!dir.isDirectory) return null
        val metas = dir.listFiles { f -> f.name.endsWith(".meta.json") } ?: return null
        for (mf in metas) {
            try {
                val jo = JSONObject(mf.readText())
                if (jo.optString("artifact_name") == artName) {
                    val fn = mf.name.removeSuffix(".meta.json")
                    if (File(dir, fn).exists()) return fn
                }
            } catch (_: Exception) { /* 单个 meta 损坏不影响其余 */ }
        }
        return null
    }

    /**
     * 登记产物。同名覆盖升版本(历史卡片指向同一文件, 打开即最新), 首次登记新建附件库记录。
     * 成功返回 fileName(引用 key); 失败返回以"错误:"开头的说明。
     */
    fun save(context: Context, name: String, content: String): String {
        val artName = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "artifact" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return "错误: 产物内容为空"
        if (bytes.size > MAX_BYTES) return "错误: 产物过大(${bytes.size / 1024}KB), 上限 512KB; 请精简内容或拆分登记"
        val existing = findExisting(context, artName)
        return try {
            if (existing != null) {
                val f = AttachmentStore.fileOf(context, existing)
                    ?: return "错误: 产物实体丢失, 请换一个文件名重新登记"
                f.writeBytes(bytes)
                val mf = metaFile(context, existing)
                val meta = try { JSONObject(mf.readText()) } catch (_: Exception) { JSONObject() }
                val v = meta.optInt("artifact_version", 1) + 1
                meta.put("artifact_version", v).put("artifact_ts", System.currentTimeMillis())
                mf.writeText(meta.toString())
                existing
            } else {
                val mime = mimeOf(artName)
                val meta = JSONObject()
                    .put("mime", mime)
                    .put("artifact_name", artName)
                    .put("artifact_version", 1)
                    .put("artifact_ts", System.currentTimeMillis())
                AttachmentStore.save(context, artName, mime, bytes, meta.toString())
            }
        } catch (e: Exception) {
            "错误: 产物登记失败: ${e.message}"
        }
    }

    /**
     * 二进制产物登记(2026-11-02): 面向 PNG/JPG/GIF/WEBP/MP3/WAV/MP4/ZIP 等非文本格式,
     * 由 artifact_save_bin 工具先做 Base64 解码, 这里直接落盘原始字节。
     * 同名覆盖升版本逻辑与文本 save 一致。
     */
    fun saveBytes(context: Context, name: String, bytes: ByteArray, maxBytes: Int = MAX_BYTES): String {
        val artName = name.trim().replace(Regex("[\\/:*?\"<>|]"), "_").ifBlank { "artifact" }
        if (bytes.isEmpty()) return "错误: 产物内容为空"
        if (bytes.size > maxBytes) return "错误: 产物过大(${bytes.size / 1024}KB), 上限 ${maxBytes / 1024}KB; 请压缩或换小文件"
        val existing = findExisting(context, artName)
        return try {
            if (existing != null) {
                val f = AttachmentStore.fileOf(context, existing)
                    ?: return "错误: 产物实体丢失, 请换一个文件名重新登记"
                f.writeBytes(bytes)
                val mf = metaFile(context, existing)
                val meta = try { JSONObject(mf.readText()) } catch (_: Exception) { JSONObject() }
                val v = meta.optInt("artifact_version", 1) + 1
                meta.put("artifact_version", v).put("artifact_ts", System.currentTimeMillis())
                mf.writeText(meta.toString())
                existing
            } else {
                val mime = mimeOf(artName).let { if (it == "text/plain") guessBinMime(artName) else it }
                val meta = JSONObject()
                    .put("mime", mime)
                    .put("artifact_name", artName)
                    .put("artifact_version", 1)
                    .put("artifact_ts", System.currentTimeMillis())
                AttachmentStore.save(context, artName, mime, bytes, meta.toString())
            }
        } catch (e: Exception) {
            "错误: 产物登记失败: ${e.message}"
        }
    }

    /** 二进制扩展名兜底 mime(文本表未覆盖时) */
    private fun guessBinMime(name: String): String = when (name.lowercase().substringAfterLast('.', "")) {
        "bin" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    /** 读产物 meta(带 artifact_name 标记才是产物, 普通附件返回 null) */
    fun metaOf(context: Context, fileName: String): JSONObject? {
        val mf = metaFile(context, fileName)
        if (!mf.exists()) return null
        return try {
            val jo = JSONObject(mf.readText())
            if (jo.has("artifact_name")) jo else null
        } catch (_: Exception) { null }
    }
}
