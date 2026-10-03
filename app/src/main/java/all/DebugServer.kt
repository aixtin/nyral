package io.github.aixtin.nyral

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 开发者调试服务: 本机内置轻量 HTTP 服务器(ServerSocket 手写, 无第三方依赖)。
 *
 * 端点:
 *  - POST /v1/chat   body {"message":"..."} 走完整 LocalEngine 链路(含工具循环), SSE 流式返回
 *  - GET  /v1/state   读 会话/记忆/工具/token 统计
 *  - GET  /v1/logs    拉运行日志(替代 adb logcat)
 *  - POST /v1/mem/search  body {"query":"..."} 测记忆检索
 *  - GET  /v1/screen  截当前 Activity 窗口 PNG(base64), 云端可"亲眼看到" UI
 *  - POST /v1/touch   body {"type":"tap","x":..,"y":..} / {"type":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"duration":..} 模拟手势(含惯性 fling)
 *  - POST /v1/key     body {"action":"back"|"home"|"keycode","code":..} 模拟按键
 *  - POST /v1/input   body {"text":".."} 向当前焦点 EditText 追加文本
 *
 * 安全底线:
 *  - 默认关闭(设置开关), 非 debuggable 构建(release)直接拒绝启动
 *  - Token 鉴权: 仅请求头 X-Auth-Token（query 参数 token 已移除, 防日志泄露）
 *  - GET  /v1/file/read  path=<app私有filesDir或公共工作目录内绝对路径>  只读文件(base64, ≤5MB)(M4)
 *  - 默认仅绑定 127.0.0.1(本机/adb forward 可访问); 设置开启"局域网访问"后绑定 0.0.0.0
 */
object DebugServer {

    private const val PREFS = "debug_server"
    private const val K_ENABLED = "enabled"
    private const val K_TOKEN = "token"
    private const val K_PORT = "port"
    private const val K_LAN = "lan"
    private const val K_UNLOCKED = "unlocked"

    private const val DEFAULT_PORT = 8765

    @Volatile private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "debug-http").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    /** /v1/chat 并发互斥: 同一时刻仅允许一个客户端持有 SSE sink/回调, 防止 debugSseSink 单引用被并发覆盖导致串话/丢事件(2026-09-13 压测发现) */
    private val chatLock = AtomicBoolean(false)
    private val acceptThread = LinkedBlockingQueue<Thread>()

    @Volatile private var app: Context? = null
    @Volatile private var main: MainActivity? = null

    // ================= 配置 =================
    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(K_ENABLED, false)
    fun setEnabled(c: Context, v: Boolean) { prefs(c).edit().putBoolean(K_ENABLED, v).apply() }
    fun port(c: Context): Int = prefs(c).getInt(K_PORT, DEFAULT_PORT)
    fun setPort(c: Context, v: Int) { prefs(c).edit().putInt(K_PORT, v).apply() }
    fun lanEnabled(c: Context): Boolean = prefs(c).getBoolean(K_LAN, false)
    fun setLan(c: Context, v: Boolean) { prefs(c).edit().putBoolean(K_LAN, v).apply() }
    fun unlocked(c: Context): Boolean = prefs(c).getBoolean(K_UNLOCKED, false)
    fun setUnlocked(c: Context, v: Boolean) { prefs(c).edit().putBoolean(K_UNLOCKED, v).apply() }

    /** 隐藏调试服务（总开关）：关闭启用开关、停止服务并撤销设置页入口；需再次连点版本号 7 次才恢复 */
    fun hideDebug(c: Context) {
        setEnabled(c, false)
        setUnlocked(c, false)
        stop()
    }

    fun token(c: Context): String {
        val p = prefs(c)
        var t = p.getString(K_TOKEN, null)
        if (t.isNullOrBlank()) {
            t = "droid-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            p.edit().putString(K_TOKEN, t).apply()
        }
        return t
    }

    fun resetToken(c: Context) {
        val t = "droid-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        prefs(c).edit().putString(K_TOKEN, t).apply()
    }

    fun statusText(c: Context): String {
        if (!isEnabled(c)) return "未开启"
        val s = if (running.get()) "运行中" else "未运行"
        return "$s · 端口 " + port(c) + (if (lanEnabled(c)) " · 局域网" else " · 仅本机")
    }

    // ================= 生命周期 =================
    fun init(activity: MainActivity) {
        main = activity
        app = activity.applicationContext
        val debuggable = (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            Log.i("Nyral", "DebugServer: release 构建, 不启动")
            return
        }
        if (!isEnabled(activity)) return
        start(activity)
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }

    fun detach(activity: MainActivity) {
        if (main === activity) main = null
    }

    fun running(): Boolean = running.get()

    /** 设置变更后重启服务(仅当已启用且当前进程为 debug 构建时) */
    fun restart(c: Context) {
        if (!isEnabled(c)) return
        if ((c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        stop()
        start(c.applicationContext)
    }

    private fun start(c: Context) {
        if (running.get()) return
        val p = port(c)
        val lan = lanEnabled(c)
        try {
            val bindAddr = if (lan) null else InetAddress.getByName("127.0.0.1")
            val ss = if (bindAddr == null) ServerSocket(p) else ServerSocket(p, 50, bindAddr)
            serverSocket = ss
            running.set(true)
            Log.i("Nyral", "DebugServer 启动: ${if (lan) "0.0.0.0" else "127.0.0.1"}:$p")
            if (lan) {
                // 安全审查加固(2026-10-03): 局域网模式醒目告警
                Log.w("Nyral", "DebugServer: 局域网模式已启用, 服务暴露于 0.0.0.0:$p, 同网段任意设备可访问; 仅应在可信网络下使用, 所有请求均需 X-Auth-Token 鉴权")
            }
            val t = Thread {
                while (running.get()) {
                    try {
                        val s = ss.accept()
                        pool.execute { handle(s, c.applicationContext) }
                    } catch (e: Exception) {
                        if (running.get()) Log.w("Nyral", "DebugServer accept: ${e.message}")
                    }
                }
            }
            t.name = "debug-accept"
            t.isDaemon = true
            t.start()
        } catch (e: Exception) {
            Log.e("Nyral", "DebugServer 启动失败: ${e.message}")
            running.set(false)
            try { serverSocket?.close() } catch (e2: Exception) {}
            serverSocket = null
        }
    }

    fun mainActivity(): MainActivity? = main

    // ================= HTTP 处理 =================
    private fun handle(s: Socket, c: Context) {
        try {
            s.soTimeout = 180_000
            // 安全审查加固(2026-10-03): 非本机来源连接打醒目告警(局域网模式暴露提示, 请求仍需 X-Auth-Token 鉴权)
            val peer = s.remoteSocketAddress?.toString() ?: "?"
            if (!peer.startsWith("/127.0.0.1") && !peer.startsWith("/::1") && peer != "?") {
                Log.w("Nyral", "DebugServer: 收到非本机连接 ${peer}; 若未启用局域网模式请检查网络暴露面, 所有请求均需 X-Auth-Token 鉴权")
            }
            val input = s.getInputStream()
            val out = s.getOutputStream()
            val readLine = fun(): String? {
                // 按字节读一行(到 \n), 兼容 \r\n, 返回去掉行尾换行的字符串
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (b == '\n'.code) break
                    if (b != '\r'.code) sb.append(b.toChar())
                }
                return sb.toString()
            }

            // keep-alive(吞吐优化): 仅 GET 普通端点复用连接(降 TCP 握手/线程创建开销); POST/SSE 处理完关闭
            // 单连接请求数上限 200, 防长连接泄漏
            var keepAlive = true
            var reqs = 0
            while (keepAlive && reqs < 200) {
                reqs++
                val reqLine = readLine() ?: break
                if (reqLine.isBlank()) break
                val parts = reqLine.split(" ")
                if (parts.size < 2) break
                val method = parts[0].uppercase()
                var path = parts[1]
                val qIdx = path.indexOf('?')
                var query = ""
                if (qIdx >= 0) { query = path.substring(qIdx + 1); path = path.substring(0, qIdx) }

                // headers
                var contentLength = 0
                var authToken: String? = null
                var clientKeepAlive = false
                while (true) {
                    val line = readLine() ?: break
                    if (line.isBlank()) break
                    val ci = line.indexOf(':')
                    if (ci > 0) {
                        val k = line.substring(0, ci).trim().lowercase()
                        val v = line.substring(ci + 1).trim()
                        if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                        if (k == "x-auth-token") authToken = v
                        if (k == "connection") clientKeepAlive = v.equals("keep-alive", ignoreCase = true)
                    }
                }
                val body = if (contentLength > 0) {
                    // Content-Length 是字节数, 必须按字节读满, UTF-8 中文(3字节/字)不能按字符数读
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""

                // 鉴权: 仅 header X-Auth-Token; query token 已移除(避免经代理/日志泄露)
                val expect = token(c)
                val got = authToken
                if (got != expect) {
                    writeJson(out, 401, JSONObject().put("error", "unauthorized"), false)
                    break
                }

                // 本请求是否可复用连接: GET 普通端点且客户端声明 keep-alive
                val reuse = method == "GET" && clientKeepAlive

                when {
                    method == "GET" && path == "/v1/state" -> writeJson(out, 200, stateJson(c), reuse)
                    method == "GET" && path == "/v1/ui" -> writeJson(out, 200, uiJson(c), reuse)
                    method == "GET" && path == "/v1/logs" -> writeJson(out, 200, logsJson(query), reuse)
                    method == "POST" && path == "/v1/mem/search" -> memSearch(c, out, body)
                    method == "POST" && path == "/v1/chat" -> { chat(c, out, body); keepAlive = false }
                    method == "GET" && path == "/v1/screen" -> writeJson(out, 200, screenJson(c), reuse)
                    method == "POST" && path == "/v1/touch" -> touch(c, out, body)
                    method == "POST" && path == "/v1/key" -> key(c, out, body)
                    method == "POST" && path == "/v1/input" -> inputText(c, out, body)
                    method == "GET" && path == "/v1/ping" -> writeJson(out, 200, JSONObject().put("pong", true).put("time", System.currentTimeMillis()), reuse)
                    // 浏览器页调试(完整闭环: 状态快照 / open / close / status / think / scan / highlight / click / type)
                    method == "GET" && path == "/v1/browser" -> writeJson(out, 200, browserJson(), reuse)
                    method == "POST" && path == "/v1/browser/open" -> browserOpen(out, body)
                    method == "POST" && path == "/v1/browser/close" -> browserCmd(out, body) { p, _ -> p.close() }
                    method == "POST" && path == "/v1/browser/status" -> browserCmd(out, body) { p, s -> p.setStatus(s) }
                    method == "POST" && path == "/v1/browser/think" -> browserCmd(out, body) { p, s -> p.setThink(s) }
                    method == "POST" && path == "/v1/browser/scan" -> browserScan(out)
                    method == "POST" && path == "/v1/browser/highlight" -> browserHighlight(out, body)
                    method == "POST" && path == "/v1/browser/highlight/xy" -> browserHighlightXY(out, body)
                    method == "POST" && path == "/v1/browser/click" -> browserClick(out, body)
                    method == "POST" && path == "/v1/browser/type" -> browserType(out, body)
                    method == "POST" && path == "/v1/browser/eval" -> browserEval(out, body)
                    method == "POST" && path == "/v1/app/scan" -> appScan(out)
                    method == "POST" && path == "/v1/app/click" -> appClick(out, body)
                    method == "POST" && path == "/v1/app/type" -> appType(out, body)
                    method == "POST" && path == "/v1/app/back" -> appBack(out)
                    method == "POST" && path == "/v1/app/home" -> appHome(out)
                    method == "POST" && path == "/v1/app/launch" -> appLaunch(out, body)
                    method == "POST" && path == "/v1/app/installed" -> appInstalled(out)
                    method == "POST" && path == "/v1/app/tap" -> appTap(out, body)
                    method == "GET" && path == "/v1/app/screenshot" -> appScreenshot(out)
                    method == "POST" && path == "/v1/js/run" -> jsRun(c, out, body)
                    method == "POST" && path == "/v1/sh/run" -> shRun(c, out, body)
                    method == "GET" && path == "/v1/status" -> writeJson(out, 200, statusJson(c))
                    method == "GET" && path == "/v1/file/read" -> writeJson(out, 200, fileRead(c, query), reuse)
                    else -> {
                        writeJson(out, 404, JSONObject().put("error", "not found"), false)
                        keepAlive = false
                    }
                }
                if (!reuse) keepAlive = false
            }
        } catch (e: Exception) {
            Log.w("Nyral", "DebugServer handle: ${e.message}")
        } finally {
            try { s.close() } catch (e: Exception) {}
        }
    }

    /** M4: 只读文件端点(白名单: app filesDir 私有目录或公共工作目录), base64 返回, 上限 5MB */
    private fun fileRead(c: Context, query: String): JSONObject {
        val p = queryParam(query, "path") ?: return JSONObject().put("error", "path required")
        // N1(2026-10-03): canonicalPath 规范化后再校验, 防 filesDir/../ 穿越
        val private = try { File(c.filesDir, "").canonicalPath } catch (e: Exception) { File(c.filesDir, "").absolutePath }
        val work = try { File(WorkDir.displayPath).canonicalPath } catch (e: Exception) { WorkDir.displayPath.trimEnd('/') }
        val abs = try { File(p).canonicalPath } catch (e: Exception) { File(p).absolutePath }
        val inPrivate = abs == private || abs.startsWith(private + File.separator)
        val inWork = abs == work || abs.startsWith(work + File.separator)
        if (!inPrivate && !inWork) return JSONObject().put("error", "path not allowed")
        val f = File(abs)
        if (!f.isFile) return JSONObject().put("error", "not a file")
        if (f.length() > 5L * 1024 * 1024) return JSONObject().put("error", "too large")
        return try {
            val bytes = f.readBytes()
            JSONObject().put("ok", true).put("name", f.name).put("size", bytes.size)
                .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        } catch (e: Exception) { JSONObject().put("error", e.message ?: "read failed") }
    }

    private fun queryParam(query: String, key: String): String? {
        return query.split("&").mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i > 0 && kv.substring(0, i) == key) kv.substring(i + 1) else null
        }.firstOrNull()
    }

    private fun writeJson(out: OutputStream, code: Int, obj: JSONObject, keepAlive: Boolean = false) {
        val bytes = obj.toString().toByteArray()
        val conn = if (keepAlive) "keep-alive" else "close"
        val head = "HTTP/1.1 $code OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: $conn\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    // ================= /v1/state =================
    private fun stateJson(c: Context): JSONObject {
        val o = JSONObject()
        try {
            val m = JSONObject()
            m.put("provider", ApiConfig.providerId())
            m.put("model", ApiConfig.model())
            m.put("chat_url", ApiConfig.chatUrl())
            m.put("api_key_set", ApiConfig.apiKey().isNotBlank())
            m.put("is_local", ApiConfig.isCurrentLocal())
            o.put("model", m)
        } catch (e: Exception) { o.put("model_error", e.message) }

        try {
            val db = MemoryDb(c)
            val mem = JSONObject()
            mem.put("count", db.count())
            mem.put("pending", db.pendingCount())
            db.loadSummary()?.let { mem.put("summary", it.take(300)) }
            o.put("memory", mem)
        } catch (e: Exception) { o.put("memory_error", e.message) }

        try {
            val tools = JSONArray()
            for (t in LocalEngine.toolList()) {
                tools.put(JSONObject().put("name", t.name).put("desc", t.desc))
            }
            o.put("tools", tools)
        } catch (e: Exception) { o.put("tools_error", e.message) }

        try {
            // 仅暴露连接元信息, 不泄露密码/私钥
            val sshArr = JSONArray()
            for (cfg in SshConfigStore.load(c)) {
                sshArr.put(JSONObject()
                    .put("name", cfg.name)
                    .put("host", cfg.host)
                    .put("port", cfg.port)
                    .put("user", cfg.user)
                    .put("has_proxy", cfg.hasProxy))
            }
            o.put("ssh", sshArr)
        } catch (e: Exception) { o.put("ssh_error", e.message) }

        try {
            val s = TokenStore.stats(c)
            val tk = JSONObject()
            tk.put("total_prompt", s.totalPrompt)
            tk.put("total_completion", s.totalCompletion)
            tk.put("total", s.total)
            tk.put("count", s.count)
            tk.put("day_prompt", s.dayPrompt)
            tk.put("day_completion", s.dayCompletion)
            o.put("token", tk)
        } catch (e: Exception) { o.put("token_error", e.message) }

        try {
            val sess = JSONObject()
            val act = main
            if (act != null) {
                sess.put("current_session_id", act.currentSessionId)
                sess.put("current_session_title", act.currentSessionTitle)
                sess.put("ai_busy", act.aiBusy)
                sess.put("message_count", act.messages.size)
            } else {
                sess.put("error", "MainActivity not alive")
            }
            o.put("session", sess)
        } catch (e: Exception) { o.put("session_error", e.message) }

        o.put("server", JSONObject()
            .put("enabled", isEnabled(c))
            .put("running", running.get())
            .put("port", port(c))
            .put("lan", lanEnabled(c)))
        return o
    }

    /**
     * /v1/ui: UI 态快照(减少对截图/OCR 的依赖):
     *  - window: DA 前台态 + 当前前台 Activity 简单类名
     *  - overlay: 悬浮终端可见性/尺寸/行数(主线程真实读)
     *  - terminal: 悬浮终端开关 + 服务运行态
     *  - permissions: 关键运行时权限/特殊权限快照
     */
    private fun uiJson(c: Context): JSONObject {
        val o = JSONObject()
        try {
            val win = JSONObject()
            val act = main
            if (act != null) {
                win.put("da_in_foreground", TerminalGate.daInForeground())
                win.put("activity", TerminalGate.foregroundActivityName() ?: JSONObject.NULL)
            } else {
                win.put("da_in_foreground", false)
                win.put("activity", JSONObject.NULL)
                win.put("note", "MainActivity not alive")
            }
            o.put("window", win)
        } catch (e: Exception) { o.put("window_error", e.message) }

        try {
            o.put("overlay", AITerminalService.overlayInfo())
        } catch (e: Exception) { o.put("overlay_error", e.message) }

        try {
            val term = JSONObject()
            term.put("enabled", AITerminal.isEnabled(c))
            term.put("service_running", AITerminal.serviceRunning)
            o.put("terminal", term)
        } catch (e: Exception) { o.put("terminal_error", e.message) }

        try {
            val perms = JSONObject()
            perms.put("overlay", Settings.canDrawOverlays(c))
            perms.put("all_files", Environment.isExternalStorageManager())
            perms.put("install_unknown", c.packageManager.canRequestPackageInstalls())
            perms.put("post_notifications", c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            perms.put("record_audio", c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            o.put("permissions", perms)
        } catch (e: Exception) { o.put("permissions_error", e.message) }
        return o
    }

    /** /v1/logs: 支持 query 参数 tag(来源tag)、level(I/W/E)、tail(条数, 默认300, 上限1000) */
    private fun logsJson(query: String): JSONObject {
        val tag = queryParam(query, "tag")
        val tail = (queryParam(query, "tail") ?: "300").toIntOrNull()?.coerceIn(1, 1000) ?: 300
        val level = queryParam(query, "level")
        var list = LogStore.snapshot(tag).takeLast(tail)
        if (!level.isNullOrBlank()) {
            val lv = level.uppercase()
            list = list.filter { it.level == lv }
        }
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().put("ts", e.ts).put("tag", e.tag).put("level", e.level).put("msg", e.msg))
        }
        return JSONObject().put("count", arr.length()).put("logs", arr)
    }

    private fun memSearch(c: Context, out: OutputStream, body: String) {
        val q = try { JSONObject(body).optString("query", "") } catch (e: Exception) { "" }
        if (q.isBlank()) {
            writeJson(out, 400, JSONObject().put("error", "query required"))
            return
        }
        val result = MemoryTools.search(c, q)
        writeJson(out, 200, JSONObject().put("query", q).put("result", result))
    }

    // ================= UI 操作（/v1/screen /v1/touch /v1/key /v1/input） =================

    /** 应用已获 root(KernelSU/Magisk): 系统级注入优先, 对 Compose/RecyclerView 滚动、全局按键最可靠 */
    private fun rootOk(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id -u"))
            val ok = p.waitFor() == 0
            if (ok) Log.i("Nyral", "DebugServer: root injection available")
            ok
        } catch (e: Exception) { false }
    }

    /** 以 root 执行 shell 命令, 返回是否成功(exit 0) */
    private fun rootExec(cmd: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor() == 0
        } catch (e: Exception) { false }
    }

    /** input text 参数转义: 空格用 %s; 仅纯 ASCII 走 root, 含非 ASCII 交给 EditText 注入 */
    private fun rootInputText(text: String): Boolean {
        if (!text.all { it.code < 128 }) return false
        val esc = text.replace(" ", "%s")
            .replace("&", "\\&").replace("|", "\\|").replace(";", "\\;")
            .replace("(", "\\(").replace(")", "\\)").replace("\"", "\\\"")
            .replace("'", "\\'").replace("\$", "\\\$")
        return rootExec("input text '$esc'")
    }

    /** 在 UI 主线程执行 block, 等待完成(最多 5s); 用于所有涉及 View/Activity 的操作 */
    private fun runOnMain(block: () -> Unit): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(true)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { block() } catch (e: Exception) { ok.set(false); Log.w("Nyral", "DebugServer UI op: ${e.message}") }
            finally { latch.countDown() }
        }
        return try { latch.await(5, TimeUnit.SECONDS); ok.get() } catch (e: Exception) { false }
    }

    /** /v1/screen: 截当前 Activity DecorView 为 PNG(base64), 云端可直接看图 */
    private fun screenJson(c: Context): JSONObject {
        val result = JSONObject()
        val act = main
        if (act == null) return result.put("error", "MainActivity not alive")
        val ok = runOnMain {
            try {
                val view = act.window.decorView
                val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                view.draw(canvas)
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                result.put("ok", true)
                result.put("width", view.width)
                result.put("height", view.height)
                result.put("image_png_base64", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                bmp.recycle()
            } catch (e: Exception) {
                result.put("ok", false).put("error", e.message)
            }
        }
        if (!ok && !result.has("ok")) result.put("ok", false).put("error", "main thread timeout")
        return result
    }

    /** /v1/touch: 模拟手势。tap: {type,x,y}; swipe: {type,x1,y1,x2,y2,duration}; longpress: {type,x,y,duration} */
    private fun touch(c: Context, out: OutputStream, body: String) {
        val o = try { JSONObject(body) } catch (e: Exception) { null }
        if (o == null) { writeJson(out, 400, JSONObject().put("error", "bad json")); return }
        val act = main
        if (act == null) { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        val result = JSONObject()
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val done = CountDownLatch(1)
        val type = o.optString("type", "tap")
        val actOk = AtomicBoolean(true)
        try {
            when (type) {
                "tap" -> {
                    val x = o.optDouble("x", 0.0).toFloat()
                    val y = o.optDouble("y", 0.0).toFloat()
                    if (rootExec("input tap ${x.toInt()} ${y.toInt()}")) {
                        result.put("ok", true).put("root", true).put("type", "tap").put("x", x).put("y", y)
                        done.countDown()
                    } else {
                        h.post {
                            try {
                                val view = act.window.decorView
                                val now = SystemClock.uptimeMillis()
                                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                                view.dispatchTouchEvent(down); down.recycle()
                                val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0)
                                view.dispatchTouchEvent(up); up.recycle()
                                result.put("ok", true).put("type", "tap").put("x", x).put("y", y)
                            } catch (e: Exception) { actOk.set(false); result.put("ok", false).put("error", e.message) }
                            finally { done.countDown() }
                        }
                    }
                }
                "swipe" -> {
                    val x1 = o.optDouble("x1", 0.0).toFloat()
                    val y1 = o.optDouble("y1", 0.0).toFloat()
                    val x2 = o.optDouble("x2", 0.0).toFloat()
                    val y2 = o.optDouble("y2", 0.0).toFloat()
                    val dur = o.optLong("duration", 300L).coerceIn(50L, 5000L)
                    if (rootExec("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $dur")) {
                        result.put("ok", true).put("root", true).put("type", "swipe")
                            .put("from", "$x1,$y1").put("to", "$x2,$y2").put("duration", dur)
                        done.countDown()
                    } else {
                        val view = act.window.decorView
                        val now = SystemClock.uptimeMillis()
                        // DOWN 立即派发
                        h.post {
                            try {
                                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x1, y1, 0)
                                view.dispatchTouchEvent(down); down.recycle()
                            } catch (e: Exception) { actOk.set(false); result.put("ok", false).put("error", e.message) }
                        }
                        // 尾段 ease-out: 末段位移大, UP 时速度感真实, 可触发 fling 惯性
                        val steps = (dur / 16).toInt().coerceIn(2, 300)
                        for (i in 1..steps) {
                            val t = now + dur * i / steps
                            val frac = i.toFloat() / steps
                            val eased = 1f - (1f - frac) * (1f - frac)
                            val mx = x1 + (x2 - x1) * eased
                            val my = y1 + (y2 - y1) * eased
                            h.postDelayed({
                                try {
                                    val mv = MotionEvent.obtain(now, t, MotionEvent.ACTION_MOVE, mx, my, 0)
                                    view.dispatchTouchEvent(mv); mv.recycle()
                                } catch (e: Exception) { actOk.set(false); result.put("ok", false).put("error", e.message) }
                            }, t - now)
                        }
                        // UP 最后派发, 结束手势
                        h.postDelayed({
                            try {
                                val up = MotionEvent.obtain(now, now + dur, MotionEvent.ACTION_UP, x2, y2, 0)
                                view.dispatchTouchEvent(up); up.recycle()
                                result.put("ok", true).put("type", "swipe")
                                    .put("from", "$x1,$y1").put("to", "$x2,$y2").put("duration", dur)
                            } catch (e: Exception) { actOk.set(false); result.put("ok", false).put("error", e.message) }
                            finally { done.countDown() }
                        }, dur + 30)
                    }
                }
                "longpress" -> {
                    val x = o.optDouble("x", 0.0).toFloat()
                    val y = o.optDouble("y", 0.0).toFloat()
                    val dur = o.optLong("duration", 600L).coerceIn(200L, 3000L)
                    if (rootExec("input swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} $dur")) {
                        result.put("ok", true).put("root", true).put("type", "longpress").put("x", x).put("y", y).put("duration", dur)
                        done.countDown()
                    } else {
                        val view = act.window.decorView
                        val now = SystemClock.uptimeMillis()
                        h.post {
                            try {
                                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                                view.dispatchTouchEvent(down); down.recycle()
                            } catch (e: Exception) { actOk.set(false); result.put("ok", false).put("error", e.message) }
                        }
                        h.postDelayed({
                            try {
                                val up = MotionEvent.obtain(now, now + dur, MotionEvent.ACTION_UP, x, y, 0)
                                view.dispatchTouchEvent(up); up.recycle()
                                result.put("ok", true).put("type", "longpress").put("x", x).put("y", y).put("duration", dur)
                            } catch (e: Exception) { actOk.set(false); result.put("ok", false).put("error", e.message) }
                            finally { done.countDown() }
                        }, dur + 30)
                    }
                }
                else -> { result.put("ok", false).put("error", "unknown type: $type"); done.countDown() }
            }
            done.await(if (type == "swipe") o.optLong("duration", 300L).coerceIn(50L, 5000L) + 3000 else 5000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            result.put("ok", false).put("error", e.message)
            done.countDown()
        }
        if (!result.has("ok") && actOk.get()) result.put("ok", false).put("error", "touch timeout")
        writeJson(out, if (result.optBoolean("ok", false)) 200 else 400, result)
    }

    /** /v1/key: 模拟按键。{action:"back"|"home"|"keycode", code:..} */
    private fun key(c: Context, out: OutputStream, body: String) {
        val o = try { JSONObject(body) } catch (e: Exception) { null }
        if (o == null) { writeJson(out, 400, JSONObject().put("error", "bad json")); return }
        val act = main
        if (act == null) { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        val result = JSONObject()
        // root 系统级按键最可靠, 优先; 失败(无 root/未授权)再回退 app 内 View 注入
        val action = o.optString("action", "keycode") // 防呆: 缺省走 keycode, 避免误触发 back 退出应用
        val code = o.optInt("code", KeyEvent.KEYCODE_ENTER)
        val cmd = when (action) {
            "back" -> "input keyevent 4"
            "home" -> "input keyevent 3"
            "keycode" -> "input keyevent $code"
            else -> null
        }
        if (cmd != null && rootExec(cmd)) {
            result.put("ok", true).put("root", true).put("action", action)
            if (action == "keycode") result.put("code", code)
            writeJson(out, 200, result)
            return
        }
        val ok = runOnMain {
            try {
                when (action) {
                    "back" -> { act.onBackPressed(); result.put("ok", true).put("action", "back") }
                    "home" -> {
                        val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
                        act.startActivity(i)
                        result.put("ok", true).put("action", "home")
                    }
                    "keycode" -> {
                        act.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        act.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                        result.put("ok", true).put("action", "keycode").put("code", code)
                    }
                    else -> result.put("ok", false).put("error", "unknown action: " + action)
                }
            } catch (e: Exception) {
                result.put("ok", false).put("error", e.message)
            }
        }
        if (!ok && !result.has("ok")) result.put("ok", false).put("error", "main thread timeout")
        writeJson(out, if (result.optBoolean("ok", false)) 200 else 400, result)
    }

    /** /v1/input: 向当前焦点 EditText 追加文本 {text:".."} */
    private fun inputText(c: Context, out: OutputStream, body: String) {
        val o = try { JSONObject(body) } catch (e: Exception) { null }
        if (o == null) { writeJson(out, 400, JSONObject().put("error", "bad json")); return }
        val act = main
        if (act == null) { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        val text = o.optString("text", "")
        val result = JSONObject()
        // root 下 ASCII 文本直接走 input text(系统级, 不依赖焦点); 中文/失败回退 EditText 注入
        if (text.isNotEmpty() && rootInputText(text)) {
            result.put("ok", true).put("root", true).put("text_len", text.length)
            writeJson(out, 200, result)
            return
        }
        val ok = runOnMain {
            try {
                val focus = act.currentFocus
                if (focus is android.widget.EditText) {
                    val start = focus.selectionStart.coerceAtLeast(0)
                    val end = focus.selectionEnd.coerceAtLeast(start)
                    val cur = focus.text ?: android.text.Editable.Factory.getInstance().newEditable("")
                    cur.replace(start, end, text)
                    focus.setSelection(start + text.length)
                    result.put("ok", true).put("text_len", text.length)
                } else {
                    result.put("ok", false).put("error", "no EditText focused")
                }
            } catch (e: Exception) {
                result.put("ok", false).put("error", e.message)
            }
        }
        if (!ok && !result.has("ok")) result.put("ok", false).put("error", "main thread timeout")
        writeJson(out, if (result.optBoolean("ok", false)) 200 else 400, result)
    }

    // ================= /v1/browser (浏览器页调试闭环) =================
    private fun browserJson(): JSONObject {
        val act = main ?: return JSONObject().put("ok", false).put("error", "MainActivity not alive")
        var url = ""; var open = false; var count = 0; var highlighted = -1
        val elements = JSONArray()
        val latch = CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (act.browserPageReady()) {
                val p = act.browserPage
                url = p.currentUrl; open = p.open; count = p.elementCount; highlighted = p.highlightedIndex
                for (m in p.elementsSnapshot()) {
                    val o = JSONObject()
                    for ((k, v) in m) o.put(k, v)
                    elements.put(o)
                }
            }
            latch.countDown()
        }
        try { latch.await(1, TimeUnit.SECONDS) } catch (e: Exception) {}
        return JSONObject().put("ok", true)
            .put("open", open).put("url", url)
            .put("count", count).put("highlighted", highlighted)
            .put("elements", elements)
    }

    private fun browserOpen(out: OutputStream, body: String) {
        val act = main ?: run { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        val url = try { JSONObject(body).optString("url", "") } catch (e: Exception) { "" }
        val latch = CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (act.browserPageReady()) act.browserPage.open(url.ifBlank { null })
            latch.countDown()
        }
        try { latch.await(1, TimeUnit.SECONDS) } catch (e: Exception) {}
        // 等待页面加载完成(onPageFinished)后同步扫描回填, 避免重站点/慢网下快照读到旧页串页
        val loaded = act.browserPage.waitLoaded(10_000)
        val count = if (loaded) act.browserPage.scanSync(4000) else 0
        writeJson(out, 200, JSONObject().put("ok", true).put("open", true).put("url", url).put("loaded", loaded).put("count", count))
    }

    /** /v1/browser/scan: 同步等待 JS 扫描器回填(onElements)完成再返回元素数, 消除异步回填竞态(scan 后 elements 被 clear 尚未回填时快照读到 0/旧) */
    private fun browserScan(out: OutputStream) {
        val act = main ?: run { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        if (!act.browserPageReady()) {
            writeJson(out, 200, JSONObject().put("ok", false).put("count", 0).put("message", "浏览器页未初始化"))
            return
        }
        val count = act.browserPage.scanSync(4000)
        writeJson(out, 200, JSONObject().put("ok", true).put("count", count))
    }

    private fun browserCmd(out: OutputStream, body: String, op: (BrowserPage, String) -> Unit) {
        val act = main ?: run { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        val v = try { if (body.isBlank()) "" else JSONObject(body).let { it.optString("status", it.optString("think", "")) } } catch (e: Exception) { "" }
        val latch = CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (act.browserPageReady()) op(act.browserPage, v)
            latch.countDown()
        }
        try { latch.await(1, TimeUnit.SECONDS) } catch (e: Exception) {}
        writeJson(out, 200, JSONObject().put("ok", true))
    }

    private fun browserHighlight(out: OutputStream, body: String) {
        val act = main ?: run { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        val idx = try { JSONObject(body).optInt("index", -1) } catch (e: Exception) { -1 }
        val latch = CountDownLatch(1); var msg = ""
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (act.browserPageReady()) {
                val p = act.browserPage
                msg = when {
                    p.elementCount == 0 -> "页面暂无元素"
                    idx < 0 || idx >= p.elementCount -> "索引 $idx 越界(共 ${p.elementCount} 个)"
                    else -> { p.highlightIndex(idx); "已高亮 $idx" }
                }
            } else msg = "浏览器页未初始化"
            latch.countDown()
        }
        try { latch.await(1, TimeUnit.SECONDS) } catch (e: Exception) {}
        writeJson(out, 200, JSONObject().put("ok", true).put("message", msg))
    }

    private fun browserHighlightXY(out: OutputStream, body: String) {
        val act = main ?: run { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        val x = try { JSONObject(body).optInt("x", -1) } catch (e: Exception) { -1 }
        val y = try { JSONObject(body).optInt("y", -1) } catch (e: Exception) { -1 }
        val latch = CountDownLatch(1); var msg = ""
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (act.browserPageReady()) { msg = act.browserPage.highlightNear(x, y) } else msg = "浏览器页未初始化"
            latch.countDown()
        }
        try { latch.await(1, TimeUnit.SECONDS) } catch (e: Exception) {}
        writeJson(out, 200, JSONObject().put("ok", true).put("message", msg))
    }

    private fun browserClick(out: OutputStream, body: String) {
        val act = main ?: run { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        val idx = try { JSONObject(body).optInt("index", -1) } catch (e: Exception) { -1 }
        val latch = CountDownLatch(1); var msg = ""
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (act.browserPageReady()) msg = act.browserPage.clickIndex(idx) else msg = "浏览器页未初始化"
            latch.countDown()
        }
        try { latch.await(1, TimeUnit.SECONDS) } catch (e: Exception) {}
        writeJson(out, 200, JSONObject().put("ok", true).put("message", msg))
    }

    // ================= 无障碍控制第三方 App (/v1/app/*, 桥接 UiControlService) =================
    private fun appScan(out: OutputStream) {
        writeJson(out, 200, JSONObject().put("ok", true).put("result", UiControlService.scan()))
    }

    private fun appClick(out: OutputStream, body: String) {
        val idx = try { JSONObject(body).optInt("index", -1) } catch (e: Exception) { -1 }
        val result = UiControlService.click(idx)
        writeJson(out, 200, JSONObject().put("ok", true).put("result", result))
    }

    private fun appType(out: OutputStream, body: String) {
        val o = try { JSONObject(body) } catch (e: Exception) { null }
        val idx = o?.optInt("index", -1) ?: -1
        val text = o?.optString("text", "").orEmpty()
        writeJson(out, 200, JSONObject().put("ok", true).put("result", UiControlService.type(idx, text)))
    }

    private fun appBack(out: OutputStream) {
        writeJson(out, 200, JSONObject().put("ok", true).put("result", UiControlService.back()))
    }

    private fun appHome(out: OutputStream) {
        writeJson(out, 200, JSONObject().put("ok", true).put("result", UiControlService.home()))
    }

    private fun appLaunch(out: OutputStream, body: String) {
        val act = main ?: run { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        val pkg = try { JSONObject(body).optString("pkg", "") } catch (e: Exception) { "" }
        writeJson(out, 200, JSONObject().put("ok", true).put("result", UiControlService.launch(act, pkg)))
    }

    private fun appInstalled(out: OutputStream) {
        val act = main ?: run { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        writeJson(out, 200, JSONObject().put("ok", true).put("result", UiControlService.installed(act)))
    }

    private fun appTap(out: OutputStream, body: String) {
        val o = try { JSONObject(body) } catch (e: Exception) { null }
        val x = o?.optInt("x", -1) ?: -1
        val y = o?.optInt("y", -1) ?: -1
        writeJson(out, 200, JSONObject().put("ok", true).put("result", UiControlService.tap(x, y)))
    }

    private fun appScreenshot(out: OutputStream) {
        writeJson(out, 200, JSONObject().put("ok", true).put("result", UiControlService.screenshot()))
    }

    private fun jsRun(c: Context, out: OutputStream, body: String) {
        val o = try { JSONObject(body) } catch (e: Exception) { null }
        val code = o?.optString("code", "").orEmpty()
        val timeout = o?.optLong("timeoutMs", 8000L) ?: 8000L
        if (code.isBlank()) { writeJson(out, 400, JSONObject().put("error", "code required")); return }
        // 安全审查修复(2026-10-03): DebugServer 直调 js_run 也过 H3 硬门禁(与 executeTool 同款确认+票据)
        if (SecurityConfig.dangerConfirm(c) && !SecurityConfig.hasTicket(c, "js_run", body)) {
            when (SecurityUi.requestConfirm(c, "js_run", body)) {
                true -> { /* 票据已签发 */ }
                false -> { writeJson(out, 403, JSONObject().put("error", "用户拒绝执行 js_run, 已停止")); return }
                null -> { writeJson(out, 503, JSONObject().put("error", "无前台界面可弹出确认框, 请在前台打开 App 后重试")); return }
            }
        }
        writeJson(out, 200, JSONObject().put("ok", true).put("result", ScriptEngine.runJs(code, timeout)))
    }

    /** /v1/sh/run: 就地执行 Shell 脚本(与 LocalEngine.sh_run 工具同源, 含黑名单+H3 门禁) */
    private fun shRun(c: Context, out: OutputStream, body: String) {
        val r = ScriptEngine.runShConfirmed(c, body)
        writeJson(out, 200, JSONObject().put("result", r))
    }

    /** /v1/status: 就地脚本能力状态(root 可用性, 供调试侧判断提权能力) */
    private fun statusJson(c: Context): JSONObject {
        val o = JSONObject()
        o.put("sh_run", JSONObject()
            .put("root", ScriptEngine.isRootAvailable(c))
            .put("danger_block", true))
        return o
    }

    private fun browserEval(out: OutputStream, body: String) {
        val act = main ?: run { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        val script = try { JSONObject(body).optString("script", "") } catch (e: Exception) { "" }
        if (script.isBlank()) { writeJson(out, 400, JSONObject().put("error", "script required")); return }
        val latch = CountDownLatch(1); var msg = ""
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (act.browserPageReady()) act.browserPage.evalScript(script, latch) else latch.countDown()
        }
        try { latch.await(2, TimeUnit.SECONDS) } catch (e: Exception) {}
        msg = if (act.browserPageReady()) act.browserPage.lastActionResult() else "浏览器页未初始化"
        writeJson(out, 200, JSONObject().put("ok", true).put("result", msg))
    }

    private fun browserType(out: OutputStream, body: String) {
        val act = main ?: run { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        val idx = try { JSONObject(body).optInt("index", -1) } catch (e: Exception) { -1 }
        val text = try { JSONObject(body).optString("text", "") } catch (e: Exception) { "" }
        if (text.isBlank()) { writeJson(out, 400, JSONObject().put("error", "text required")); return }
        val latch = CountDownLatch(1); var msg = ""
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (act.browserPageReady()) msg = act.browserPage.typeIndex(idx, text) else msg = "浏览器页未初始化"
            latch.countDown()
        }
        try { latch.await(1, TimeUnit.SECONDS) } catch (e: Exception) {}
        writeJson(out, 200, JSONObject().put("ok", true).put("message", msg))
    }

    // ================= /v1/chat (SSE) =================
    private fun chat(c: Context, out: OutputStream, body: String) {
        val message = try { JSONObject(body).optString("message", "") } catch (e: Exception) { "" }
        val atts = try {
            val arr = JSONObject(body).optJSONArray("attachments")
            if (arr == null) emptyList() else (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LocalEngine.Attachment(
                    mime = o.optString("mime", "text/plain"),
                    base64 = o.optString("base64", ""),
                    name = o.optString("name", "attachment"),
                    text = o.optString("text", "")
                )
            }
        } catch (e: Exception) { emptyList() }
        if (message.isBlank() && atts.isEmpty()) {
            writeJson(out, 400, JSONObject().put("error", "message required"))
            return
        }
        val act = main ?: run {
            writeJson(out, 503, JSONObject().put("error", "MainActivity not alive"))
            return
        }
        // 并发互斥: CAS 原子抢占, 同一时刻仅一个 /v1/chat 连接占用 sink/回调
        if (!chatLock.compareAndSet(false, true)) {
            writeJson(out, 409, JSONObject().put("error", "已有其他调试客户端对话中, 请稍后再试"))
            return
        }
        if (act.aiBusy) {
            writeJson(out, 409, JSONObject().put("error", "AI 正忙, 稍后再试"))
            chatLock.set(false)
            return
        }

        // SSE 响应头
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.flush()

        // 事件队列: MainActivity 回调(工作线程) -> SSE writer(本线程)
        val queue = LinkedBlockingQueue<Pair<String, String>>()  // (event, data)
        val sink: (String, String) -> Unit = { event, data -> queue.offer(event to data) }
        val oldSink = act.debugSseSink
        act.debugSseSink = sink

        val finished = AtomicBoolean(false)
        val accepted = AtomicBoolean(true)
        try {
            // 触发主链路(UI 线程), 完成后自行停止 TaskService
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val ok = act.submitDebugChat(message, atts) {
                    queue.offer("_sys_done" to "")
                }
                if (!ok) {
                    accepted.set(false)
                    queue.offer("error" to "AI 正忙, 请求被拒绝")
                    queue.offer("_sys_done" to "")
                }
            }
            var done = false
            var idle = 0
            while (!done) {
                val ev = queue.poll(5, TimeUnit.SECONDS)
                if (ev == null) {
                    // 主链路未产生事件(理论不会), 防死循环: 连续 36 次空轮询(180s)视为异常
                    // 延长阈值: ask_user 澄清弹窗会阻塞主链路等待用户点选(最长120s), 30s 会误判超时
                    idle++
                    if (idle >= 36) {
                        queue.offer("error" to "调试链路超时")
                        queue.offer("_sys_done" to "")
                    }
                    continue
                }
                idle = 0
                val (event, data) = ev
                if (event == "_sys_done") { done = true; continue }
                out.write("event: $event\n".toByteArray())
                out.write("data: $data\n\n".toByteArray())
                out.flush()
                if (event == "error" || event == "done") done = true
            }
            finished.set(true)
        } catch (e: Exception) {
            Log.w("Nyral", "DebugServer chat SSE: ${e.message}")
        } finally {
            act.debugSseSink = oldSink
            // 仅"主链路未接受"时取消; 调试客户端断开(如 curl 超时)不取消——App 是独立主体,
            // 生成应继续跑完并入库, 否则中断整轮工具循环且 onDone 走取消分支不写库, UI 气泡全部丢失(2026-09-18)
            if (!accepted.get()) {
                try { LocalEngine.requestCancel() } catch (e: Exception) {}
            }
            try { out.flush() } catch (e: Exception) {}
            chatLock.set(false)
        }
    }
}
