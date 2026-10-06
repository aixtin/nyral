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
        app = context.applicationContext
        val p = prefs()
        version = p.getInt(K_VERSION, 0)
        state = if (p.getBoolean(K_READY, false)) ST_READY else ST_NOT_DOWNLOADED
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
            var done = 0
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val name = f.getString("name")
                val urls = jsonArrayToList(f.getJSONArray("urls"))
                val md5 = f.getString("md5")
                val size = f.getLong("size")
                currentTask = name
                notifyChanged()
                val target = File(ctx.filesDir, name)
                if (!downloadFileWithResume(target, urls, md5, size)) {
                    throw RuntimeException("下载失败: $name")
                }
                done++
                progress = (done * 100) / files.length()
                notifyChanged()
            }
            version = manifest.getInt("version")
            prefs().edit().putInt(K_VERSION, version).putBoolean(K_READY, true).apply()
            state = ST_READY
            currentTask = ""
            Log.i(TAG, "模型就绪 v$version")
            // 通知所有 MemoryEmbedder 实例重新加载
            MemoryEmbedder.reloadAll()
        } catch (e: Exception) {
            Log.e(TAG, "下载失败", e)
            lastError = e.message ?: "未知错误"
            state = ST_FAILED
        }
        notifyChanged()
    }

    private fun jsonArrayToList(a: JSONArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until a.length()) out.add(a.getString(i))
        return out
    }

    // ---- manifest 拉取 + ECDSA 签名校验(校验原始字节, 签名文件为 manifest.sig) ----
    private fun fetchVerifiedManifest(): JSONObject? {
        var lastErr = ""
        for (url in MANIFEST_URLS) {
            try {
                val raw = httpGetBytes(url) ?: continue
                val sigText = httpGetText(url + ".sig")?.trim() ?: continue
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
    private fun downloadFileWithResume(target: File, urls: List<String>, md5: String, size: Long): Boolean {
        if (target.exists() && target.length() == size && md5(target) == md5) return true
        if (target.exists()) target.delete()
        for (url in urls) {
            try {
                if (downloadResume(url, target, md5, size)) return true
            } catch (e: Exception) {
                Log.w(TAG, "下载源失败 $url: $e")
            }
        }
        return false
    }

    private fun downloadResume(urlStr: String, target: File, md5: String, size: Long): Boolean {
        var existing = if (target.exists()) target.length() else 0L
        var lastErr = ""
        var attempts = 0
        while (existing < size && attempts < 5) {
            attempts++
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("Range", "bytes=$existing-")
                conn.connect()
                val code = conn.responseCode
                if (code != 200 && code != 206) throw RuntimeException("HTTP $code")
                conn.inputStream.use { input ->
                    FileOutputStream(target, true).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            existing += n
                        }
                    }
                }
            } catch (e: Exception) {
                lastErr = e.message ?: "IO 异常"
                if (attempts >= 5) break
                Thread.sleep(1000L * attempts)
            }
        }
        if (existing < size) {
            lastError = lastErr
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
