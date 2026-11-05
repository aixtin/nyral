package io.github.aixtin.nyral

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语义模型热更新器。
 * - 从多源拉取已签名 manifest(ECDSA P-256, 公钥内置 res/raw/nyral_model_pub.der)
 * - 断点续传(Range)+MD5 校验下载模型文件, 主源失败自动切备用源
 * - 状态持久化, 配置页可查看/手动重试; 下载完成自动通知 MemoryEmbedder.reloadAll()
 */
object ModelUpdater {

    const val ST_NOT_DOWNLOADED = 0
    const val ST_DOWNLOADING = 1
    const val ST_READY = 2
    const val ST_FAILED = 3

    private const val TAG = "ModelUpdater"
    private val MANIFEST_URLS = listOf(
        "https://gitee.com/aixtin/nyral-models/releases/download/v1/manifest.json",
        "https://raw.githubusercontent.com/aixtin/nyral/main/models/bge/v1/manifest.json"
    )
    private const val PREF = "model_updater"
    private const val K_VERSION = "version"
    private const val K_READY = "ready"
    private const val K_LAST_FAIL = "last_fail_ts"
    /** 失败退避窗口: 24h 内不自动重试, 避免每次冷启动重复拉取 24MB 模型 */
    private const val FAIL_BACKOFF_MS = 24L * 60 * 60 * 1000

    @Volatile private var app: Context? = null
    @Volatile var state = ST_NOT_DOWNLOADED
        private set
    @Volatile var version = 0
        private set
    @Volatile var progress = 0
        private set
    @Volatile var lastError = ""
        private set
    @Volatile private var currentTask = ""
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "model-updater").apply { isDaemon = true } }

    fun init(context: Context) {
        if (app != null) return
        LogStore.i(LogStore.MEM, "ModelUpdater.init from ${context.javaClass.simpleName}")
        app = context.applicationContext
        val p = prefs()
        version = p.getInt(K_VERSION, 0)
        state = if (p.getBoolean(K_READY, false)) ST_READY else ST_NOT_DOWNLOADED
        // 冷启动自愈: 热更模型从未落地 -> 后台自动拉取(用户无需知道该模型存在)
        // 注意: assets 内置兜底会让 MemoryEmbedder.isReady() 恒为 true, 归档路径永远等不到,
        // 因此自动下载必须在 Application 初始化时发起, 不能依赖归档流程触发。
        if (state != ST_READY) {
            val lastFail = p.getLong(K_LAST_FAIL, 0L)
            if (System.currentTimeMillis() - lastFail > FAIL_BACKOFF_MS) {
                LogStore.i(LogStore.MEM, "冷启动检测到热更模型未落地, 触发后台下载")
                ensureDownloaded()
            } else {
                LogStore.i(LogStore.MEM, "热更模型上次下载失败, 处于退避窗口, 跳过自动下载")
            }
        }
    }

    fun isReady(): Boolean = state == ST_READY

    private fun prefs(): SharedPreferences =
        app!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun statusText(): String {
        if (app == null) return "语义模型未就绪"
        return when (state) {
            ST_DOWNLOADING -> "语义模型下载中 $progress% ($currentTask)"
            ST_READY -> "语义模型已就绪 v$version"
            ST_FAILED -> "语义模型下载失败，点击重试"
            else -> "语义模型未下载，点击下载"
        }
    }

    /** 检查并下载(后台线程); force=true 强制重新拉 manifest 比对版本 */
    fun ensureDownloaded(force: Boolean = false) {
        if (app == null) return
        if (running.get()) return
        if (!force && state == ST_READY) return
        executor.execute {
            if (!running.compareAndSet(false, true)) return@execute
            try {
                downloadAll()
            } finally {
                running.set(false)
            }
        }
    }

    private fun downloadAll() {
        val ctx = app ?: return
        state = ST_DOWNLOADING
        progress = 0
        lastError = ""
        notifyChanged()
        try {
            val manifest = fetchVerifiedManifest() ?: throw RuntimeException("manifest 拉取或签名校验失败: $lastError")
            val files = manifest.getJSONArray("files")
            // 总字节数用于按字节推进进度(而非按文件个数跳变)
            var totalBytes = 0L
            for (i in 0 until files.length()) totalBytes += files.getJSONObject(i).getLong("size")
            var doneBytes = 0L
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val remoteName = f.getString("name")
                val localName = localNameOf(remoteName)
                val urls = jsonArrayToList(f.getJSONArray("urls"))
                val md5 = f.getString("md5")
                val size = f.getLong("size")
                currentTask = localName
                notifyChanged()
                val target = File(ctx.filesDir, localName)
                val base = doneBytes
                val ok = downloadFileWithResume(target, urls, md5, size) { downloaded ->
                    val p = if (totalBytes > 0) (((base + downloaded) * 100) / totalBytes).toInt() else 0
                    val np = p.coerceIn(0, 100)
                    if (np != progress) {
                        progress = np
                        notifyChanged()
                    }
                }
                if (!ok) throw RuntimeException("下载失败: $remoteName")
                doneBytes += size
            }
            progress = 100
            notifyChanged()
            version = manifest.getInt("version")
            prefs().edit().putInt(K_VERSION, version).putBoolean(K_READY, true).remove(K_LAST_FAIL).apply()
            state = ST_READY
            currentTask = ""
            Log.i(TAG, "模型就绪 v$version")
            // 通知所有 MemoryEmbedder 实例重新加载
            MemoryEmbedder.reloadAll()
        } catch (e: Exception) {
            Log.e(TAG, "下载失败", e)
            lastError = e.message ?: "未知错误"
            state = ST_FAILED
            prefs().edit().putLong(K_LAST_FAIL, System.currentTimeMillis()).apply()
        }
        notifyChanged()
    }

    private fun jsonArrayToList(a: JSONArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until a.length()) out.add(a.getString(i))
        return out
    }

    // ---- manifest 拉取 + ECDSA 签名校验(校验原始字节, 签名文件为 manifest.sig) ----
    /** manifest.json 的签名文件与 manifest 同级, 即 manifest.sig, 不能简单拼接 ".sig" */
    private fun sigUrlOf(manifestUrl: String): String =
        if (manifestUrl.endsWith("manifest.json"))
            manifestUrl.dropLast("manifest.json".length) + "manifest.sig"
        else manifestUrl + ".sig"

    /** manifest 里的远程文件名 -> filesDir 下的本地文件名(必须与 MemoryEmbedder 读取名一致) */
    private fun localNameOf(remote: String): String = when (remote) {
        "model_quantized.onnx" -> "bge_model.onnx"
        "vocab.txt" -> "bge_vocab.txt"
        else -> remote
    }
    private fun fetchVerifiedManifest(): JSONObject? {
        var lastErr = ""
        for (url in MANIFEST_URLS) {
            try {
                val raw = httpGetBytes(url) ?: continue
                val sigText = httpGetText(sigUrlOf(url))?.trim() ?: continue
                if (verifySig(raw, sigText)) {
                    return JSONObject(String(raw, Charsets.UTF_8))
                } else {
                    lastErr = "签名校验失败"
                }
            } catch (e: Exception) {
                lastErr = e.message ?: "网络错误"
                Log.w(TAG, "manifest 源失败 $url: $e")
            }
        }
        lastError = lastErr
        return null
    }

    private fun verifySig(canonical: ByteArray, sigB64: String): Boolean {
        return try {
            val ctx = app ?: return false
            val der = ctx.resources.openRawResource(R.raw.nyral_model_pub).use { it.readBytes() }
            val pubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pubKey)
            sig.update(canonical)
            sig.verify(android.util.Base64.decode(sigB64, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            Log.e(TAG, "verifySig 异常", e)
            false
        }
    }

    // ---- 断点续传下载(Range) + MD5 ----
    private fun downloadFileWithResume(
        target: File,
        urls: List<String>,
        md5: String,
        size: Long,
        onProgress: (Long) -> Unit = {}
    ): Boolean {
        if (target.exists() && target.length() == size && md5(target) == md5) {
            onProgress(size)
            return true
        }
        // 一律先写 .part 临时文件: 主文件可能被 onnxruntime mmap 持有, 直接覆写/追加会内容错位
        val part = File(target.parentFile, target.name + ".part")
        for (url in urls) {
            try {
                if (downloadResume(url, part, md5, size, onProgress)) {
                    if (target.exists() && !target.delete()) {
                        // 删除失败也不能 append, 用覆盖写兜底
                        part.inputStream().use { input ->
                            target.outputStream().use { out -> input.copyTo(out) }
                        }
                        part.delete()
                    } else if (!part.renameTo(target)) {
                        part.inputStream().use { input ->
                            target.outputStream().use { out -> input.copyTo(out) }
                        }
                        part.delete()
                    }
                    onProgress(size)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "下载源失败 $url: $e")
            }
            if (part.exists()) part.delete()
            onProgress(0L)
        }
        return false
    }

    private fun downloadResume(
        urlStr: String,
        target: File,
        md5: String,
        size: Long,
        onProgress: (Long) -> Unit
    ): Boolean {
        var lastErr = ""
        var attempts = 0
        while (attempts < 6) {
            var existing = if (target.exists()) target.length() else 0L
            // 长度越界(历史脏数据/源异常)一律重来, 绝不在超长文件上继续追加
            if (existing > size) {
                target.delete()
                existing = 0L
            }
            if (existing == size) break
            attempts++
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Range", "bytes=$existing-")
                conn.connect()
                val code = conn.responseCode
                if (code != 200 && code != 206) throw RuntimeException("HTTP $code")
                if (code == 206) {
                    // 必须核对 Content-Range 起点: 部分 CDN 会忽略 Range 仍回完整内容,
                    // 直接 append 会把文件撑成两倍(线上实测 24MB 被写成 44MB)
                    val cr = conn.getHeaderField("Content-Range") ?: ""
                    val start = cr.substringAfter("bytes", "").trim().substringBefore("-").trim().toLongOrNull()
                    if (start != existing) {
                        target.delete()
                        onProgress(0L)
                        throw RuntimeException("Content-Range 起点异常: [$cr] 期望 $existing")
                    }
                } else if (existing > 0) {
                    // 服务端忽略 Range 回 200: 丢弃已下载部分重写
                    target.delete()
                    existing = 0L
                    onProgress(0L)
                }
                var written = existing
                var overflow = false
                conn.inputStream.use { input ->
                    FileOutputStream(target, true).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        var lastCb = written
                        while (input.read(buf).also { n = it } != -1) {
                            val room = size - written
                            if (room <= 0) { overflow = true; break }
                            val w = if (n.toLong() > room) room.toInt() else n
                            out.write(buf, 0, w)
                            written += w
                            if (written - lastCb >= 256 * 1024) {
                                lastCb = written
                                onProgress(written)
                            }
                        }
                        onProgress(written)
                    }
                }
                if (overflow) {
                    target.delete()
                    throw RuntimeException("响应超出 manifest 声明长度")
                }
                if (written < size) throw RuntimeException("连接提前结束: $written/$size")
            } catch (e: Exception) {
                lastErr = e.message ?: "IO 异常"
                if (attempts >= 6) break
                Thread.sleep(1000L * attempts)
            }
        }
        if (!target.exists() || target.length() != size) {
            lastError = lastErr.ifEmpty { "长度校验失败" }
            return false
        }
        if (md5(target) != md5) {
            target.delete()
            lastError = "MD5 校验失败"
            return false
        }
        return true
    }

    private fun httpGetText(urlStr: String): String? =
        httpGetBytes(urlStr)?.toString(Charsets.UTF_8)

    private fun httpGetBytes(urlStr: String): ByteArray? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.requestMethod = "GET"
        conn.connect()
        if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
        return conn.inputStream.use { it.readBytes() }
    }

    private fun md5(f: File): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun notifyChanged() {
        // 预留: 设置页等 UI 可注册刷新(当前用进入页面时读取状态的方式)
    }
}
