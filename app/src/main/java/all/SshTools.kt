package io.github.aixtin.nyral

import android.content.Context
import android.util.Log
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import org.json.JSONObject
import java.util.Vector

object SshTools {

    init {
        // Android JCE 无 Ed25519 算法(需 Java15+), 注册 BouncyCastle Provider 供 jsch 使用
        try {
            if (java.security.Security.getProvider("BC") == null) {
                java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            }
        } catch (_: Exception) {
        }
    }

    /** 从配置存储读取, 若命令含连接名则优先匹配; 匹配失败返回可用列表, 绝不静默连第一个 */
    fun run(context: Context, command: String): String {
        val configs = SshConfigStore.load(context)
        if (configs.isEmpty()) return "未配置 SSH 连接，请先在「SSH 配置」中添加"
        val cfg = pickConfig(configs, command)
        if (cfg == null) {
            val names = configs.joinToString("、") { it.name }
            return "未找到匹配的连接。可用连接：$names。请用格式「连接名: 命令」（如 \"${configs.first().name}: ls /\"）重新执行。"
        }
        // 去掉命令里可能带的目标前缀, 如 "vps: ls /" / "去连接vps:ls /"
        // 无冒号但含空格: 首个空格前是连接名(已被 pickConfig 匹配), 其后是命令本体, 如 "vps ls /"
        val realCmd = if (command.contains(":")) command.substringAfter(":").trim()
        else if (command.contains(" ")) command.substringAfter(" ").trim()
        else ""
        val body = runOn(cfg, realCmd.ifEmpty { "echo no-command" })
        // 结果头部带实际连接目标, 让模型能如实回答"连的哪个IP"
        val target = "[${cfg.name}: ${cfg.user}@${cfg.host}:${cfg.port}" +
            (if (cfg.hasProxy) " 经跳板 ${cfg.proxyHost}:${cfg.proxyPort}" else "") + "]"
        return "$target\n$body"
    }

    private fun pickConfig(configs: List<SshConfigStore.SshConfig>, command: String): SshConfigStore.SshConfig? {
        // 取冒号前的部分作为连接名候选(兼容无冒号写法: "去连接cccSSH")
        var name = command.substringBefore(":").trim().lowercase().replace(" ", "")
        // 剥离常见引导词: "去连接cccSSH" / "连接 ccc SSH" / "连到vps" / "ssh到dev" / "ssh vps"
        for (prefix in listOf("去连接", "连接到", "连接", "连到", "连", "ssh到", "ssh", "使用", "用")) {
            if (name.startsWith(prefix)) {
                name = name.removePrefix(prefix)
                break
            }
        }
        if (name.isEmpty()) return null
        val normalized = configs.map { it to it.name.lowercase().replace(" ", "") }
        // 1) 完全相等 2) 连接名以候选开头(如 "ccc" -> "cccSSH") 3) 候选以连接名开头(如 "cccSSH查磁盘" -> cccSSH)
        return normalized.firstOrNull { it.second == name }?.first
            ?: normalized.firstOrNull { it.second.startsWith(name) }?.first
            ?: normalized.firstOrNull { name.startsWith(it.second) }?.first
    }

    fun runOn(cfg: SshConfigStore.SshConfig, command: String): String {
        val tag = "SshTools"
        Log.i(tag, "runOn: conn=" + cfg.name + " target=" + cfg.user + "@" + cfg.host + ":" + cfg.port +
                " proxy=" + (if (cfg.hasProxy) cfg.proxyHost + ":" + cfg.proxyPort else "none") + " cmd=" + command)
        return try {
            val session = connect(cfg)
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setInputStream(null)
            // 不调用 setErrStream(System.err): 它会关闭内部 pipe 导致 stderr 永远读不到
            val out = channel.inputStream
            val errStream = channel.errStream
            Log.i(tag, "connecting channel...")
            channel.connect(15000)
            Log.i(tag, "channel connected, waiting output")
            val buf = ByteArray(1024)
            val sb = StringBuilder()
            val errSb = StringBuilder()
            val idleTimeoutMs = 8000L   // 连续无新输出超过该时长视为命令结束
            val totalTimeoutMs = 60000L // 总硬超时兜底, 防止隧道下 close 信号丢失导致死等
            val startTime = System.currentTimeMillis()
            var lastDataTime = System.currentTimeMillis()
            var timedOut = false
            while (true) {
                if (out.available() > 0) {
                    val n = out.read(buf, 0, buf.size)
                    if (n < 0) break
                    sb.append(String(buf, 0, n))
                    lastDataTime = System.currentTimeMillis()
                } else if (errStream.available() > 0) {
                    val n = errStream.read(buf, 0, buf.size)
                    if (n < 0) break
                    errSb.append(String(buf, 0, n))
                    lastDataTime = System.currentTimeMillis()
                } else if (channel.isClosed) {
                    // 收到 close 后把剩余数据读尽再退出
                    if (out.available() == 0 && errStream.available() == 0) break
                    Thread.sleep(50)
                } else if (System.currentTimeMillis() - lastDataTime > idleTimeoutMs) {
                    timedOut = true
                    break
                } else if (System.currentTimeMillis() - startTime > totalTimeoutMs) {
                    timedOut = true
                    break
                } else {
                    Thread.sleep(100)
                }
            }
            // drain 剩余输出(close 竞态下可能还有残留)
            try {
                while (out.available() > 0) {
                    val n = out.read(buf, 0, buf.size)
                    if (n < 0) break
                    sb.append(String(buf, 0, n))
                }
                while (errStream.available() > 0) {
                    val n = errStream.read(buf, 0, buf.size)
                    if (n < 0) break
                    errSb.append(String(buf, 0, n))
                }
            } catch (_: Exception) {
            }
            Log.i(tag, "channel done timedOut=" + timedOut + " exit=" + channel.exitStatus +
                    " outBytes=" + sb.length + " errBytes=" + errSb.length)
            try { channel.disconnect() } catch (_: Exception) {}
            try { session.disconnect() } catch (_: Exception) {}
            val result = sb.toString().trim()
            val err = errSb.toString().trim()
            val body = (if (result.isNotEmpty()) result else "") +
                (if (err.isNotEmpty()) (if (result.isNotEmpty()) "\n[stderr]\n" else "") + err else "") +
                (if (result.isEmpty() && err.isEmpty()) "(无输出, 退出码 ${channel.exitStatus})" else "")
            truncate(body)
        } catch (e: Exception) {
            Log.e(tag, "runOn FAILED", e)
            "SSH 错误: ${e.message}"
        }
    }

    /** 长输出截断: 超过上限只保留头尾, 中间用省略行标注, 防单次结果刷爆上下文 */
    private fun truncate(body: String): String {
        val maxChars = 20000
        if (body.length <= maxChars) return body
        val head = body.take(maxChars / 2)
        val tail = body.takeLast(maxChars / 2)
        return "$head\n\n... [输出共 ${body.length} 字符, 已截断中间部分; 若需完整结果, 用 ssh_run 把输出重定向到文件再 ssh_download 拉取] ...\n\n$tail"
    }

    /** 建立已连接的 Session(含跳板机隧道), 供 exec/SFTP 复用 */
    private fun connect(cfg: SshConfigStore.SshConfig): Session {
        val tag = "SshTools"
        val jsch = JSch()
        if (!cfg.privateKey.isNullOrEmpty()) {
            Log.i(tag, "addIdentity target key len=" + cfg.privateKey.length + " head=" + cfg.privateKey.take(27).replace("\n", "|"))
            jsch.addIdentity("cfg_key", cfg.privateKey.toByteArray(), null,
                cfg.passphrase?.toByteArray())
        }
        val session: Session = jsch.getSession(cfg.user, cfg.host, cfg.port)
        if (!cfg.password.isNullOrEmpty()) session.setPassword(cfg.password)
        session.setConfig("StrictHostKeyChecking", "no")
        if (cfg.hasProxy) {
            // 经跳板机: 先连跳板机, 再通过 direct-tcpip 隧道连目标
            if (cfg.proxyPrivateKey.isNullOrEmpty() && cfg.proxyPassword.isNullOrEmpty() &&
                cfg.privateKey.isNullOrEmpty() && cfg.password.isNullOrEmpty()) {
                Log.e(tag, "proxy credential missing: 跳板机与目标机均未配置凭据")
                throw IllegalStateException("SSH 错误: 跳板机与目标机均未配置私钥/密码, 请填写跳板机私钥/密码(或目标机私钥/密码与跳板共用)")
            }
            session.setProxy(JschProxyJump(cfg))
        }
        session.setTimeout(20000)
        Log.i(tag, "connecting session...")
        session.connect(15000)
        Log.i(tag, "session connected")
        return session
    }

    /** 解析 SFTP 类工具入参: JSON 或 k:v 兼容 */
    private fun parseJson(arg: String): JSONObject {
        val t = arg.trim()
        return if (t.startsWith("{")) {
            try { JSONObject(t) } catch (e: Exception) { JSONObject() }
        } else JSONObject()
    }

    /**
     * SFTP 上传: 本地工作目录文件 -> 远端
     * 参数: {"conn":"连接名","local":"工作目录文件名","remote":"远端绝对路径"}
     */
    fun upload(context: Context, arg: String): String {
        val p = parseJson(arg)
        val conn = p.optString("conn").ifEmpty { p.optString("conn_name") }
        val local = p.optString("local").ifEmpty { p.optString("file") }
        val remote = p.optString("remote").ifEmpty { p.optString("path") }
        if (conn.isEmpty() || local.isEmpty() || remote.isEmpty())
            return "错误: 参数需含 conn(连接名)/local(工作目录文件名)/remote(远端路径), 如 {\"conn\":\"vps\",\"local\":\"a.txt\",\"remote\":\"/tmp/a.txt\"}"
        val cfg = pickConfig(SshConfigStore.load(context), conn + ":")
            ?: return "未找到连接: $conn"
        val bytes = WorkDir.read(context, local)
            ?: return "错误: 工作目录不存在该文件: $local (可用 workdir_list 查看)"
        if (bytes.isEmpty()) return "错误: 文件内容为空: $local"
        if (bytes.size > 200 * 1024 * 1024) return "错误: 文件超过 200MB 上限"
        val tag = "SshTools"
        return try {
            val session = connect(cfg)
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(15000)
            try {
                sftp.put(java.io.ByteArrayInputStream(bytes), remote)
            } finally {
                sftp.disconnect()
                session.disconnect()
            }
            Log.i(tag, "upload done: $local -> $remote (${bytes.size} bytes)")
            "上传成功: $local -> $remote (${bytes.size} 字节)"
        } catch (e: Exception) {
            Log.e(tag, "upload FAILED", e)
            "上传失败: ${e.message}"
        }
    }

    /**
     * SFTP 下载: 远端 -> 本地工作目录
     * 参数: {"conn":"连接名","remote":"远端绝对路径","local":"可选本地文件名(默认取远端文件名)"}
     */
    fun download(context: Context, arg: String): String {
        val p = parseJson(arg)
        val conn = p.optString("conn").ifEmpty { p.optString("conn_name") }
        val remote = p.optString("remote").ifEmpty { p.optString("path") }
        val localRaw = p.optString("local")
        if (conn.isEmpty() || remote.isEmpty())
            return "错误: 参数需含 conn(连接名)/remote(远端路径), 如 {\"conn\":\"vps\",\"remote\":\"/tmp/a.txt\"}"
        val cfg = pickConfig(SshConfigStore.load(context), conn + ":")
            ?: return "未找到连接: $conn"
        val localName = if (localRaw.isNotBlank()) localRaw else
            remote.substringAfterLast('/').ifEmpty { "download.bin" }
        val tag = "SshTools"
        return try {
            val session = connect(cfg)
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(15000)
            val out = java.io.ByteArrayOutputStream()
            try {
                sftp.get(remote, out)
            } finally {
                sftp.disconnect()
                session.disconnect()
            }
            val bytes = out.toByteArray()
            if (bytes.size > 200 * 1024 * 1024) return "错误: 文件超过 200MB 上限"
            if (!WorkDir.write(context, localName, bytes))
                return "错误: 写入工作目录失败: $localName"
            Log.i(tag, "download done: $remote -> $localName (${bytes.size} bytes)")
            "下载成功: $remote -> ${WorkDir.displayPath}$localName (${bytes.size} 字节)"
        } catch (e: Exception) {
            Log.e(tag, "download FAILED", e)
            "下载失败: ${e.message}"
        }
    }

    /**
     * SFTP 列目录: 只列一级, 区分目录/文件
     * 参数: {"conn":"连接名","path":"远端目录(默认 ~)"}
     */
    fun ls(context: Context, arg: String): String {
        val p = parseJson(arg)
        val conn = p.optString("conn").ifEmpty { p.optString("conn_name") }
        val path = p.optString("path").ifEmpty { p.optString("dir") }.ifEmpty { "." }
        if (conn.isEmpty()) return "错误: 缺少 conn(连接名)"
        val cfg = pickConfig(SshConfigStore.load(context), conn + ":")
            ?: return "未找到连接: $conn"
        val tag = "SshTools"
        return try {
            val session = connect(cfg)
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(15000)
            val sb = StringBuilder("[$cfg.name: $path]\n")
            try {
                val list = sftp.ls(path) as? Vector<*>
                if (list == null) return "错误: 无法列目录 $path"
                @Suppress("UNCHECKED_CAST")
                for (e in list as List<ChannelSftp.LsEntry>) {
                    val name = e.filename
                    if (name == "." || name == "..") continue
                    val isDir = e.attrs.isDir()
                    val size = if (isDir) "" else e.attrs.getSize().toString()
                    val mod = try {
                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                            .format(java.util.Date(e.attrs.getMTime().toLong() * 1000L))
                    } catch (_: Exception) { "" }
                    sb.append(if (isDir) "[D] " else "    ").append(name)
                        .append(if (isDir) "/" else "").append(" | ").append(size).append(" | ").append(mod).append("\n")
                }
            } finally {
                sftp.disconnect()
                session.disconnect()
            }
            val r = sb.toString().trim()
            if (r.endsWith("]")) "目录为空: $path" else r
        } catch (e: Exception) {
            Log.e(tag, "ls FAILED", e)
            "ls 失败: ${e.message}"
        }
    }

    /**
     * jsch 无原生 ProxyJump, 用 Proxy 接口实现:
     * 先连跳板机(proxy 配置), 再开 direct-tcpip 通道打通到目标 host:port,
     * 目标机的 SSH 协议流量经该隧道传输。
     */
    private class JschProxyJump(
        private val cfg: SshConfigStore.SshConfig
    ) : Proxy {
        private var session: Session? = null
        private var channel: ChannelDirectTCPIP? = null
        // 关键: 流只能取一次并缓存复用! Channel.getInputStream() 每次调用都会新建 pipe 覆盖 io.out,
        // 若 connect 后再调一次, 先到达的服务器 banner 留在旧 pipe, 主 session 读新 pipe 永远等不到数据(握手卡死)
        private var tunnelIn: java.io.InputStream? = null
        private var tunnelOut: java.io.OutputStream? = null

        override fun connect(socketFactory: com.jcraft.jsch.SocketFactory?, host: String, port: Int, timeout: Int) {
            Log.i("SshTools", "proxy connect: proxy=" + cfg.proxyHost + ":" + cfg.proxyPort + " -> target=" + host + ":" + port)
            val jsch = JSch()
            // 跳板机凭据优先用跳板专属字段; 跳板字段未填时回退复用目标机凭据(常见: 跳板与目标同一把钥匙/同一密码)
            val proxyKey = cfg.proxyPrivateKey
            val proxyPw = cfg.proxyPassword
            val proxyPhrase = cfg.proxyPassphrase
            val proxyUser = cfg.proxyUser ?: cfg.user
            val useFallback = proxyKey.isNullOrEmpty() && proxyPw.isNullOrEmpty()
            val effKey = if (!proxyKey.isNullOrEmpty()) proxyKey else if (useFallback) cfg.privateKey else null
            val effPw = if (!proxyPw.isNullOrEmpty()) proxyPw else if (useFallback) cfg.password else null
            val effPhrase = if (!proxyPhrase.isNullOrEmpty()) proxyPhrase else if (useFallback) cfg.passphrase else null
            Log.i("SshTools", "proxy key: user=" + proxyUser + " keyLen=" + (effKey?.length ?: 0) +
                    " keyHead=" + (effKey?.take(27)?.replace("\n", "|") ?: "NONE") + " hasPw=" + (!effPw.isNullOrEmpty()))
            if (!effKey.isNullOrEmpty()) {
                jsch.addIdentity("proxy_key", effKey.toByteArray(), null, effPhrase?.toByteArray())
            }
            val s = jsch.getSession(proxyUser, cfg.proxyHost, cfg.proxyPort)
            if (!effPw.isNullOrEmpty()) s.setPassword(effPw)
            s.setConfig("StrictHostKeyChecking", "no")
            s.setTimeout(if (timeout > 0) timeout else 15000)
            s.connect(if (timeout > 0) timeout else 15000)
            val ch = s.openChannel("direct-tcpip") as ChannelDirectTCPIP
            ch.setHost(host)
            ch.setPort(port)
            // 注意: 不要调用 setInputStream(空流)! 空流会让 connect 走"启动 run 线程"分支,
            // run 线程读到 EOF 会向远端发 CHANNEL_EOF, 可能中断隧道;
            // 不设置输入流时 connect 走同步 sendChannelOpen, 数据发送走 getOutputStream() 不依赖 run 线程。
            // 且必须在 connect 前一次性取出输入/输出流缓存, 之后 getInputStream/getOutputStream 只返回缓存。
            tunnelIn = ch.getInputStream()
            tunnelOut = ch.getOutputStream()
            ch.connect(if (timeout > 0) timeout else 15000)
            // jsch 的 channel connect 是异步的(立即返回), 必须等 open confirmation 处理完(rmpsize>0)
            // 再让主 session 使用通道流, 否则写流时 buffer 初始化失败抛 "channel is not opened"
            try {
                val rmpsizeField = Channel::class.java.getDeclaredField("rmpsize")
                rmpsizeField.isAccessible = true
                val deadline = System.currentTimeMillis() + 10000
                while (System.currentTimeMillis() < deadline) {
                    if (rmpsizeField.getInt(ch) > 0) break
                    Thread.sleep(20)
                }
                if (rmpsizeField.getInt(ch) <= 0) throw java.io.IOException("tunnel not ready")
            } catch (e: java.io.IOException) {
                throw e
            } catch (e: Exception) {
                // 反射失败时退化为短暂等待
                Thread.sleep(1000)
            }
            session = s
            channel = ch
            Log.i("SshTools", "proxy connected: tunnel via " + cfg.proxyHost + ":" + cfg.proxyPort +
                    " -> " + host + ":" + port)
        }

        override fun getInputStream(): java.io.InputStream = tunnelIn!!

        override fun getOutputStream(): java.io.OutputStream = tunnelOut!!

        override fun getSocket(): java.net.Socket? = null

        override fun close() {
            try { channel?.disconnect() } catch (_: Exception) {}
            try { session?.disconnect() } catch (_: Exception) {}
        }
    }
}