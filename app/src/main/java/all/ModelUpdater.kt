package io.github.aixtin.nyral

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.InputStream
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
    /** 分片通道: Gitee 附件 CDN 实测忽略 Range(回 200 全量), 单文件断流只能从 0 重来,
     *  故按 4MB 切片独立下载, 弱网/反复开关 App 只重下未完成的那一片 */
    private const val PART_CHUNK = 4L * 1024 * 1024
    private const val PART_BASE = "https://gitee.com/aixtin/nyral-models/releases/download/v1/"
    private const val COPY_BUF = 64 * 1024
    private const val PREF = "model_updater"
    private const val K_VERSION = "version"
    private const val K_READY = "ready"
    private const val K_LAST_FAIL = "last_fail_ts"
    /** 连续失败次数: 用于分级退避, 下载成功即清零 */
    private const val K_FAIL_COUNT = "fail_count"
    /** 断点分片最大重试次数(单次连接层), 超过交给外层退避与网络回调 */
    private const val MAX_ATTEMPTS = 8

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
        // 注意: v2.9.7 前 assets 内置兜底会让 MemoryEmbedder.isReady() 恒为 true, 归档路径永远等不到,
        // 因此自动下载必须在 Application 初始化时发起, 不能依赖归档流程触发。
        registerNetworkCallback()
        if (state != ST_READY) {
            // 2026-11-10: 从未失败过时 K_LAST_FAIL=0, 直接 epoch 相减会算出 1794247362441ms(56年) 的假值;
            // 用 MAX 表示"无失败记录/等待无限久", 与原来"不受退避限制"的语义一致
            val lastFail = p.getLong(K_LAST_FAIL, 0L)
            val waited = if (lastFail <= 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastFail
            val backoff = backoffMs(p.getInt(K_FAIL_COUNT, 0))
            val part = partFile()
            val hasPart = part.exists() && part.length() > 0
            // 存在断点分片说明上次被中途打断(关 App / 断流), 不套长退避, 直接续传
            if (hasPart || waited >= backoff) {
                val waitedText = if (waited == Long.MAX_VALUE) "无失败记录" else "${waited}ms"
                LogStore.i(LogStore.MEM, "冷启动触发模型下载(断点=${hasPart}, 退避=${backoff}ms, 已等=${waitedText})")
                ensureDownloaded()
            } else {
                LogStore.i(LogStore.MEM, "模型下载退避中, 还需 ${(backoff - waited) / 1000}s, 联网或手动重试可提前")
            }
        }
    }

    /** 连续失败次数 -> 退避窗口 60s/5min/30min/2h/24h: 不再"失败一次锁一整天" */
    private fun backoffMs(fails: Int): Long = when {
        fails <= 0 -> 0L
        fails == 1 -> 60_000L
        fails == 2 -> 5 * 60_000L
        fails == 3 -> 30 * 60_000L
        fails == 4 -> 2 * 60 * 60_000L
        else -> 24 * 60 * 60_000L
    }

    private fun partFile(): File = File(app!!.filesDir, "bge_model.onnx.part")

    /** 网络恢复即自动续传: 除冷启动外多一条自愈路径, 用户不必反复开关 App */
    private fun registerNetworkCallback() {
        val ctx = app ?: return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
            val req = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (state == ST_READY || running.get()) return
                    val p = prefs()
                    // 2026-11-10: 从未失败过时 K_LAST_FAIL=0, 直接 epoch 相减会算出 1794247362441ms(56年) 的假值;
                    // 用 MAX 表示"无失败记录/等待无限久", 与原来"不受退避限制"的语义一致
                    val lastFail = p.getLong(K_LAST_FAIL, 0L)
                    val waited = if (lastFail <= 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastFail
                    if (waited < backoffMs(p.getInt(K_FAIL_COUNT, 0))) return
                    LogStore.i(LogStore.MEM, "网络已恢复, 触发模型续传")
                    ensureDownloaded()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "注册网络回调失败", e)
        }
    }

    /** 失败文案是否属于"网络暂时不通"(大小写无关): 这类不惩罚退避, 等网络回来即可 */
    private fun isOfflineText(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("unable to resolve host") || m.contains("network is unreachable") ||
            m.contains("failed to connect") || m.contains("connect timed out") ||
            m.contains("connection abort") || m.contains("connection reset") ||
            m.contains("no address associated with hostname") || m.contains("timeout")
    }

    /** 判定"暂时没网"类失败: 不计入退避, 等网络回来即可 */
    private fun isOffline(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            val n = c.javaClass.simpleName
            if (n.contains("UnknownHost") || n.contains("ConnectException") ||
                n.contains("NoRouteToHost") || n.contains("SocketTimeout") || n.contains("SocketException") ||
                n.contains("SSLException") || n.contains("EOFException")
            ) return true
            if (isOfflineText(c.message ?: "")) return true
            c = c.cause
        }
        return try {
            val cm = app?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            caps == null || !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (ex: Exception) {
            false
        }
    }

    fun isReady(): Boolean = state == ST_READY

    private fun prefs(): SharedPreferences =
        app!!.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun statusText(): String {
        if (app == null) return "语义模型未就绪"
        return when (state) {
            ST_DOWNLOADING -> "语义模型下载中 $progress% ($currentTask)" +
                partFile().let { if (it.exists()) " ${it.length() / 1048576}MB" else "" }
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
            prefs().edit().putInt(K_VERSION, version).putBoolean(K_READY, true)
                .remove(K_LAST_FAIL).remove(K_FAIL_COUNT).apply()
            state = ST_READY
            currentTask = ""
            Log.i(TAG, "模型就绪 v$version")
            // 通知所有 MemoryEmbedder 实例重新加载
            MemoryEmbedder.reloadAll()
        } catch (e: Exception) {
            Log.e(TAG, "下载失败", e)
            lastError = e.message ?: "未知错误"
            state = ST_FAILED
            val p = prefs()
            if (isOffline(e)) {
                // 离线不记失败次数: 否则没网时启动一次就被罚退避一整天, 反复开关也不会重试
                p.edit().remove(K_LAST_FAIL).apply()
                LogStore.i(LogStore.MEM, "网络不可达, 保留断点 ${partFile().length()}B, 联网后自动续传")
            } else {
                val fails = p.getInt(K_FAIL_COUNT, 0) + 1
                p.edit().putInt(K_FAIL_COUNT, fails).putLong(K_LAST_FAIL, System.currentTimeMillis()).apply()
                LogStore.i(LogStore.MEM, "下载失败第 $fails 次, 退避 ${backoffMs(fails) / 1000}s")
            }
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
        // 两轮: WiFi/移动数据切换的瞬间首个请求常被 abort, 立刻重来一次即可, 不必等下次冷启动
        for (round in 1..2) {
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
                    Log.w(TAG, "manifest 源失败(第 $round 轮) $url: $e")
                }
            }
            if (round == 1) {
                // 断网时别把两轮耗完, 直接交给外层"离线不惩罚"分支
                if (isOfflineText(lastErr)) {
                    lastError = lastErr
                    return null
                }
                Thread.sleep(2000L)
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
        // 大文件优先走分片通道(各片独立落盘, 天然断点续传); 分片缺失或失败自动回退整文件
        val remoteName = urls.firstOrNull()?.substringAfterLast('/') ?: target.name
        if (size > PART_CHUNK && downloadByParts(target, remoteName, md5, size, onProgress)) return true
        return downloadWhole(target, urls, md5, size, onProgress)
    }

    /** 整文件多源续传: 一律先写 .part, 校验通过后原子替换主文件 */
    private fun downloadWhole(
        target: File,
        urls: List<String>,
        expectMd5: String,
        size: Long,
        onProgress: (Long) -> Unit
    ): Boolean {
        // 主文件可能被 onnxruntime mmap 持有, 直接覆写/追加会内容错位, 故统一写 .part
        val part = File(target.parentFile, target.name + ".part")
        for (url in urls) {
            try {
                if (downloadResume(url, part, expectMd5, size, onProgress)) {
                    replaceWithPart(target, part)
                    onProgress(size)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "下载源失败 $url: $e")
            }
            // 保留已下分片: 备用源是同一份文件, 换源后 Range 起点照样可复用, 不必从 0 再来
            if (part.exists() && part.length() >= size) part.delete()
            onProgress(part.length())
        }
        return false
    }

    /** .part 校验通过后替换正式文件; 删除/重命名失败时用覆盖写兜底 */
    private fun replaceWithPart(target: File, part: File) {
        if (target.exists() && !target.delete()) {
            part.inputStream().use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            part.delete()
            return
        }
        if (!part.renameTo(target)) {
            part.inputStream().use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            part.delete()
        }
    }

    /** 按 4MB 分片下载: 每片单独校验长度并落盘, 已完成的分片在重启后直接复用 */
    private fun downloadByParts(
        target: File,
        remoteName: String,
        expectMd5: String,
        size: Long,
        onProgress: (Long) -> Unit
    ): Boolean {
        val ctx = app ?: return false
        val dir = File(ctx.filesDir, "model_parts").apply { mkdirs() }
        val count = ((size + PART_CHUNK - 1) / PART_CHUNK).toInt()
        val chunks = (0 until count).map { File(dir, "$remoteName.p$it") }
        var done = chunks.filter { it.exists() && it.length() <= PART_CHUNK }.sumOf { it.length() }
        if (done > 0) {
            onProgress(progressOf(done, size))
            LogStore.i(LogStore.MEM, "分片续传: 本地已有 ${done / 1048576}MB/共 ${size / 1048576}MB")
        }
        for (i in 0 until count) {
            val expected = if (i == count - 1) size - PART_CHUNK * i else PART_CHUNK
            val f = chunks[i]
            if (f.exists() && f.length() == expected) continue
            if (f.exists()) {
                done -= f.length()
                f.delete()
            }
            // -1 表示该分片不可用(旧包/未上传/重试耗尽), 直接回退整文件通道, 别把下载拖住
            val got = fetchChunkWithRetry(PART_BASE + remoteName + ".p" + i, f, expected)
            if (got < 0) return false
            done += got
            onProgress(progressOf(done, size))
        }
        return assembleParts(target, chunks, size, expectMd5, onProgress)
    }

    /** 单片下载, 最多 3 次; 返回 -1 表示该分片不可用(资源缺失或重试耗尽), 调用方回退整文件 */
    private fun fetchChunkWithRetry(url: String, dest: File, expected: Long): Long {
        for (attempt in 1..3) {
            try {
                return fetchChunk(url, dest, expected)
            } catch (e: PartMissingException) {
                LogStore.i(LogStore.MEM, "分片资源不可用(${e.message}), 回退整文件下载")
                break
            } catch (e: Exception) {
                Log.w(TAG, "分片下载第 $attempt 次失败: ${e.message}")
                if (attempt < 3) Thread.sleep(if (isOffline(e)) 3000L else 1000L * attempt)
            }
        }
        return -1L
    }

    private class PartMissingException : Exception("part not available")

    /** 拉取单个分片到 .tmp, 长度不符直接判失败, 成功后再改名落盘 */
    private fun fetchChunk(url: String, dest: File, expected: Long): Long {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Nyral-Android")
            conn.connect()
            val code = conn.responseCode
            if (code == 404 || code == 403) throw PartMissingException()
            if (code != 200) throw IOException("HTTP $code")
            val got = conn.inputStream.use { input ->
                tmp.outputStream().use { out -> copyTo(input, out) }
            }
            if (got != expected) throw IOException("分片长度异常 $got/$expected")
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            return got
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** 64KB 块复制, 返回累计字节数 */
    private fun copyTo(input: InputStream, out: OutputStream): Long {
        val buf = ByteArray(COPY_BUF)
        var total = 0L
        var n: Int
        while (input.read(buf).also { n = it } != -1) {
            out.write(buf, 0, n)
            total += n
        }
        return total
    }

    /** 按序拼接全部分片到 dest */
    private fun concatParts(chunks: List<File>, dest: File) {
        dest.outputStream().use { out ->
            for (f in chunks) f.inputStream().use { copyTo(it, out) }
        }
    }

    /** 拼接全部分片并按 manifest 的 MD5 校验, 通过后原子替换主文件 */
    private fun assembleParts(
        target: File,
        chunks: List<File>,
        size: Long,
        expectMd5: String,
        onProgress: (Long) -> Unit
    ): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        try {
            concatParts(chunks, part)
            if (part.length() != size) throw IOException("拼接长度异常 ${part.length()}/$size")
            if (md5(part) != expectMd5) throw IOException("拼接 MD5 异常")
            replaceWithPart(target, part)
            for (f in chunks) f.delete()
            onProgress(100L)
            return true
        } catch (e: Exception) {
            part.delete()
            Log.w(TAG, "分片拼接失败: ${e.message}")
            return false
        }
    }

    private fun progressOf(done: Long, size: Long): Long =
        if (size <= 0) 0L else ((done * 100) / size).coerceIn(0L, 100L)

    private fun downloadResume(
        urlStr: String,
        target: File,
        md5: String,
        size: Long,
        onProgress: (Long) -> Unit
    ): Boolean {
        var lastErr = ""
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
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
                // 弱网放宽读超时: 30s 在移动网络抖动下过短, 断一次就浪费已下载进度
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Range", "bytes=$existing-")
                // 部分 CDN 无 UA 直接拒绝; 显式 identity 避免 gzip 让字节数与 manifest 对不上
                conn.setRequestProperty("User-Agent", "Nyral-Android")
                conn.setRequestProperty("Accept-Encoding", "identity")
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
                if (attempts >= MAX_ATTEMPTS) break
                // 指数退避 1s/2s/4s/8s/16s; 离线只短等, 把机会留给网络恢复
                val backoff = if (isOffline(e)) 2000L else ((1000L shl (attempts - 1)).coerceAtMost(16000L))
                Log.w(TAG, "第 $attempts 次下载中断: ${e.message}, ${backoff}ms 后重试")
                Thread.sleep(backoff)
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

    private fun httpGetBytes(urlStr: String, connectTimeoutMs: Int = 8000, readTimeoutMs: Int = 20000): ByteArray? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        // manifest/sig 只有几 KB, 短超时足够: raw.githubusercontent 国内常年不可达,
        // 长等会把"该换源了"的判断拖成半分钟, 用户侧表现为下载迟迟不动
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Nyral-Android")
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
