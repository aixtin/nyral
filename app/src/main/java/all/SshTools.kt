package io.github.aixtin.nyral

import android.content.Context
import android.util.Log
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
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

    /** H2(2026-10-03): 待确认主机密钥缓存(check 回调写入, trustHost 消费) */
    @Volatile private var pendingHostKey: Pair<String, HostKey>? = null

    /** R3-3(2026-10-04): 待确认主机指纹(确认 UI 展示用); 无待确认主机时返回 null */
    fun pendingFingerprint(): String? = pendingHostKey?.second?.getFingerPrint(JSch())

    /** H2: 指纹白名单主机密钥仓库: 未知主机返回 NOT_INCLUDED(拒绝连接), 防中间人 */
    private class PrefsHostKeyRepository(private val ctx: Context) : HostKeyRepository {
        private val prefs = ctx.getSharedPreferences("ssh_known_hosts", Context.MODE_PRIVATE)
        private val jschFp = JSch()
        override fun getKnownHostsRepositoryID(): String = "nyral-prefs"
        private fun trusted(): Set<String> = prefs.getStringSet("trusted", emptySet()) ?: emptySet()
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        override fun check(host: String?, key: ByteArray): Int {
            val h = host ?: return HostKeyRepository.NOT_INCLUDED
            val hk = HostKey(h, key)
            pendingHostKey = h to hk
            return if (trusted().contains("$h ${hk.getFingerPrint(jschFp)}")) HostKeyRepository.OK else HostKeyRepository.NOT_INCLUDED
        }
        override fun add(hostkey: HostKey, ui: UserInfo?) {
            val cur = trusted().toMutableSet()
            cur.add("${hostkey.host} ${hostkey.getFingerPrint(jschFp)}")
            prefs.edit().putStringSet("trusted", cur).apply()
        }
        override fun remove(host: String, type: String) {
            val cur = trusted().filterNot { it.startsWith("$host ") }.toMutableSet()
            prefs.edit().putStringSet("trusted", cur).apply()
        }
        override fun remove(host: String, type: String, key: ByteArray?) {
            if (key == null) { remove(host, type); return }
            val hk = HostKey(host, key)
            val cur = trusted().filterNot { it == "$host ${hk.getFingerPrint(jschFp)}" }.toMutableSet()
            prefs.edit().putStringSet("trusted", cur).apply()
        }
    }

    /**
     * H2: 信任待确认主机(把 pending 主机密钥指纹写入白名单); 返回结果描述。
     * 触发路径: 首次连接未知主机 -> SSH 安全告警 -> 用户确认 -> security_set {"ssh_trust":"连接名"}
     */
    fun trustHost(context: Context, nameOrHost: String): String {
        val p = pendingHostKey ?: return "当前没有待确认的主机密钥(需先触发一次 SSH 连接产生安全告警)"
        // 校验传入连接名与待确认主机一致(防串信任): 名字必须匹配配置且 host 一致
        val match = SshConfigStore.load(context).firstOrNull { it.name == nameOrHost }
            ?: return "未找到连接名 [$nameOrHost]，请确认 SSH 配置中的连接名"
        val host = p.first
        if (match.host != host) {
            return "连接 [$nameOrHost] 的主机地址(${match.host}) 与待确认主机($host) 不一致，已拒绝信任"
        }
        val fp = p.second.getFingerPrint(JSch())
        val cur = (context.getSharedPreferences("ssh_known_hosts", Context.MODE_PRIVATE)
            .getStringSet("trusted", emptySet()) ?: emptySet()).toMutableSet()
        cur.add("$host $fp")
        context.getSharedPreferences("ssh_known_hosts", Context.MODE_PRIVATE)
            .edit().putStringSet("trusted", cur).apply()
        pendingHostKey = null
        return "已信任主机 $host (指纹 $fp), 请重新执行原 SSH 命令"
    }

    /** H2: 未知主机密钥安全告警提示(含指纹) */
    private fun unknownHostPrompt(context: Context, cfg: SshConfigStore.SshConfig, e: Exception): String {
        val fp = Regex("fingerprint is ([0-9a-f:]+)").find(e.message ?: "")?.groupValues?.get(1)
            ?: pendingHostKey?.second?.getFingerPrint(JSch())
            ?: "未知"
        return "SSH 安全告警: 主机 [${cfg.host}:${cfg.port}] 不在信任列表, 已拒绝连接(防中间人攻击)。" +
            "主机密钥指纹: $fp\n" +
            "若确认是可信主机, 请回复「信任主机 ${cfg.name}」或调用 security_set {\"ssh_trust\":\"${cfg.name}\"} 完成信任后重试。"
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
        val body = runOn(context, cfg, realCmd.ifEmpty { "echo no-command" })
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

    /** 标记"远端命令尚未开始执行"的失败: 仅这类失败允许自动重试。
     *  修复(2026-10-31): 旧实现 execOnce 一抛异常就整体重跑, 非幂等命令
     *  (追加写/发请求)在"远端已执行、结果丢失"的断线场景下副作用翻倍。 */
    private class NotStartedException(cause: Exception) : Exception(cause.message, cause)

    fun runOn(context: Context, cfg: SshConfigStore.SshConfig, command: String): String {
        val tag = "SshTools"
        Log.i(tag, "runOn: conn=" + cfg.name + " target=" + cfg.user + "@" + cfg.host + ":" + cfg.port +
                " proxy=" + (if (cfg.hasProxy) cfg.proxyHost + ":" + cfg.proxyPort else "none") + " cmdLen=" + command.length)
        // 断线自动重连: 仅"命令尚未开始执行"的失败(连接/建通道阶段)自动重试一次;
        // 执行中断线的失败不重试 —— 远端往往已执行完, 重跑会双执行
        return try {
            execOnce(context, cfg, command)
        } catch (e: Exception) {
            val cause = (e as? NotStartedException)?.cause ?: e
            val msg = cause.message ?: ""
            if (cause is JSchException && msg.contains("UnknownHostKey")) {
                return unknownHostPrompt(context, cfg, cause)
            }
            if (e !is NotStartedException) {
                Log.e(tag, "runOn FAILED(命令可能已开始执行, 不自动重试)", e)
                return "SSH 错误: ${e.message}\n(连接中断且命令可能已在远端开始执行, 已跳过自动重试以防重复执行; 如需重试请再次调用)"
            }
            Log.w(tag, "runOn attempt1 FAILED before start, auto-reconnect once: " + msg)
            try {
                execOnce(context, cfg, command)
            } catch (e2: Exception) {
                Log.e(tag, "runOn FAILED after retry", e2)
                "SSH 错误: ${e2.message}"
            }
        }
    }

    /** 单次 SSH 执行, 失败抛异常由 runOn 决定是否重连重试 */
    private fun execOnce(context: Context, cfg: SshConfigStore.SshConfig, command: String): String {
        val tag = "SshTools"
        Log.i(tag, "execOnce: conn=" + cfg.name + " target=" + cfg.user + "@" + cfg.host + ":" + cfg.port +
                " proxy=" + (if (cfg.hasProxy) cfg.proxyHost + ":" + cfg.proxyPort else "none") + " cmdLen=" + command.length)
        // 2026-10-31 停止链路: 给远端命令打唯一标记(注释式 no-op, 不影响命令语义),
        // 客户端断链后按标记在目标机整组清理, 防 nohup/& 起的远端进程继续跑
        val mid = "NYRAL_TASK_" + System.currentTimeMillis().toString(36) + "_" + (System.nanoTime() % 997L).toString(36)
        // 打标: 导出 NYRAL_TASK_ID 环境变量(供 nohup/setsid 起的子进程继承, 后续按 /proc/*/environ 兜底清理)
        //      + 写 pid 文件(供停止时按进程组整组 KILL, 正常结束顺手删掉防 /tmp 残留堆积)
        val marked = "sh -c 'export NYRAL_TASK_ID=" + mid + "; echo $$ > /tmp/.nyral_" + mid + ".pid; " +
                command.replace("'", "'\\''") + "'; rc=\$?; rm -f /tmp/.nyral_" + mid + ".pid; exit \$rc"
        var started = false
        try {
            val session = connect(context, cfg)
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(marked)
            channel.setInputStream(null)
            // 不调用 setErrStream(System.err): 它会关闭内部 pipe 导致 stderr 永远读不到
            val out = channel.inputStream
            val errStream = channel.errStream
            Log.i(tag, "connecting channel...")
            channel.connect(15000)
            // 命令已下发到远端: 此后失败不再允许上层自动重试(防双执行)
            started = true
            Log.i(tag, "channel connected, waiting output")
            val buf = ByteArray(1024)
            val sb = StringBuilder()
            val errSb = StringBuilder()
            val idleTimeoutMs = 8000L   // 连续无新输出超过该时长视为命令结束
            val totalTimeoutMs = 300000L // 总硬超时兜底(5min, B3 修复 2026-10-20 调大以容纳静默命令), 防止隧道下 close 信号丢失导致死等
            val startTime = System.currentTimeMillis()
            var lastDataTime = System.currentTimeMillis()
            var timedOut = false
            while (true) {
                // 2026-10-31: 停止按下立刻收口(旧实现只能等 idle 8s 或总超时 5min, 停不下来)
                if (LocalEngine.cancelRequested) {
                    Log.i(tag, "execOnce cancelled by user, mark=" + mid)
                    try { channel.disconnect() } catch (_: Exception) {}
                    try { session.disconnect() } catch (_: Exception) {}
                    remoteKill(context, cfg, mid)
                    return "[已停止] 用户中断执行(远端已按标记清理)"
                }
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
                    // B3 修复(2026-10-20): idle 超时不再直接判定结束——通道未关闭(exitStatus<0)说明是静默命令仍在运行, 刷新计时继续等待
                    if (channel.isClosed || channel.exitStatus >= 0) {
                        timedOut = true
                        break
                    }
                    lastDataTime = System.currentTimeMillis()
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
            return truncate(body)
        } catch (e: Exception) {
            Log.e(tag, "execOnce FAILED started=" + started, e)
            // 只有"命令尚未开始执行"的失败包装后允许上层自动重试
            if (!started) throw NotStartedException(e)
            throw e
        }
    }

    /** 长输出截断: 超过上限只保留头尾, 中间用省略行标注, 防单次结果刷爆上下文 */

    /** 远端兜底清理(2026-10-31): 原通道已断, 新开独立连接按标记 pkill + 进程组 KILL */
    private fun remoteKill(context: Context, cfg: SshConfigStore.SshConfig, mid: String) {
        val t = Thread {
            var s: com.jcraft.jsch.Session? = null
            var ch: ChannelExec? = null
            try {
                s = connect(context, cfg)
                ch = s.openChannel("exec") as ChannelExec
                // 清理策略(2026-10-31 跨机实测后重写, 见下):
                //   旧实现先 kill -9 -pgid 整组杀, 实测暴露致命缺陷 —— pid 文件里的进程若属某 sshd 会话进程组
                //   (pgid != pid 的常态), 组杀会把该会话连同"清理命令自身"一起带走, 后面的 environ / pgrep 层根本
                //   没机会执行, setsid 逃逸的孙进程(pid==pgid==sid)100% 存活(188 跨机实测残留 sleep 600 实证)。
                //   新策略: 全程只按单 PID 精确终止, 绝不整组杀(零误伤), 顺序按覆盖面从大到小:
                //     ① environ 两遍扫描 —— 覆盖 nohup/setsid/双 fork 逃逸(只要继承环境变量就能抓到, 这是唯一能抓
                //        setsid 孙进程的层); 且清理命令自身命令行不含标记值, 不会自伤, 更不会波及 sshd 会话。
                //     ② pid 文件 + 其直接子进程 —— environ 不可读(跨 uid / root 任务由非 root 清理)时的兜底。
                //     ③ pgrep 按标记单杀 —— 命令行含标记但环境变量被清空的场景(如 exec env -i)。
                val pf = "/tmp/.nyral_" + mid + ".pid"
                val pat = "[" + mid.substring(0, 1) + "]" + mid.substring(1)   // 方括号避免匹配清理命令自身
                val envTag = "NYRAL_TASK_ID=" + mid
                ch.setCommand(
                    "K=" + pf + "; P=\$(cat \$K 2>/dev/null); " +
                    // ① 环境变量标记: 两遍(第一遍杀父后, 第二轮收拢刚被 init 收养的孙子)
                    "for i in 1 2; do " +
                        "for e in /proc/[0-9]*/environ; do " +
                            "if grep -qa '" + envTag + "' \$e 2>/dev/null; then " +
                                "p=\${e#/proc/}; p=\${p%/environ}; kill -9 \$p 2>/dev/null; " +
                            "fi; " +
                        "done; " +
                    "done; " +
                    // ② pid 文件单杀 + 直接子进程
                    "if [ -n \"\$P\" ]; then " +
                        "for c in \$(pgrep -P \$P 2>/dev/null); do kill -9 \$c 2>/dev/null; done; " +
                        "kill -9 \$P 2>/dev/null; rm -f \$K; " +
                    "fi; " +
                    // ③ 按标记 pgrep 单杀(排除自身与父 shell)
                    "for p in \$(pgrep -f '" + pat + "' 2>/dev/null); do " +
                        "if [ \"\$p\" = \"\$\$\" ] || [ \"\$p\" = \"\$PPID\" ]; then continue; fi; " +
                        "kill -9 \$p 2>/dev/null; " +
                    "done; " +
                    "echo REMOTE_KILL_DONE")
                ch.setInputStream(null)
                val ins = ch.inputStream
                ch.connect(8000)
                val buf = ByteArray(512)
                val sb = StringBuilder()
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    if (ins.available() > 0) {
                        val n = ins.read(buf, 0, buf.size)
                        if (n < 0) break
                        sb.append(String(buf, 0, n))
                    } else if (ch.isClosed) {
                        break
                    } else {
                        Thread.sleep(50)
                    }
                }
                Log.i("SshTools", "remoteKill done: " + sb.toString().trim())
            } catch (e: Exception) {
                Log.w("SshTools", "remoteKill failed: " + e.message)
            } finally {
                try { ch?.disconnect() } catch (_: Exception) {}
                try { s?.disconnect() } catch (_: Exception) {}
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun truncate(body: String): String {
        val maxChars = 20000
        if (body.length <= maxChars) return body
        val head = body.take(maxChars / 2)
        val tail = body.takeLast(maxChars / 2)
        return "$head\n\n... [输出共 ${body.length} 字符, 已截断中间部分; 若需完整结果, 用 ssh_run 把输出重定向到文件再 ssh_download 拉取] ...\n\n$tail"
    }

    /** 建立已连接的 Session(含跳板机隧道), 供 exec/SFTP 复用 */
    private fun connect(context: Context, cfg: SshConfigStore.SshConfig): Session {
        val tag = "SshTools"
        val jsch = JSch()
        // H2(2026-10-03): 主机密钥白名单校验(指纹存 SharedPreferences), 未知主机拒绝连接
        jsch.hostKeyRepository = PrefsHostKeyRepository(context)
        if (!cfg.privateKey.isNullOrEmpty()) {
            // 安全审查修复(2026-10-03): 移除私钥内容前缀日志, 仅保留长度(不落盘敏感内容)
            Log.i(tag, "addIdentity: key len=" + cfg.privateKey.length)
            jsch.addIdentity("cfg_key", cfg.privateKey.toByteArray(), null,
                cfg.passphrase?.toByteArray())
        }
        val session: Session = jsch.getSession(cfg.user, cfg.host, cfg.port)
        if (!cfg.password.isNullOrEmpty()) session.setPassword(cfg.password)
        session.setConfig("StrictHostKeyChecking", "yes")
        if (cfg.hasProxy) {
            // 经跳板机: 先连跳板机, 再通过 direct-tcpip 隧道连目标
            if (cfg.proxyPrivateKey.isNullOrEmpty() && cfg.proxyPassword.isNullOrEmpty() &&
                cfg.privateKey.isNullOrEmpty() && cfg.password.isNullOrEmpty()) {
                Log.e(tag, "proxy credential missing: 跳板机与目标机均未配置凭据")
                throw IllegalStateException("SSH 错误: 跳板机与目标机均未配置私钥/密码, 请填写跳板机私钥/密码(或目标机私钥/密码与跳板共用)")
            }
            session.setProxy(JschProxyJump(context, cfg))
        }
        session.setTimeout(20000)
        // SSH 保活: 每 30s 发一次存活探针, 连续 3 次无响应才判定断线,
        // 避免长连接空闲被中间设备(路由器/防火墙/运营商NAT)静默踢掉
        session.setServerAliveInterval(30000)
        session.setServerAliveCountMax(3)
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
        if (WorkDir.isSensitiveName(local))
            return "错误: 安全拦截 — 敏感凭据文件($local)禁止 AI 上传, 如需上传请由用户在手机端手动操作"
        val bytes = WorkDir.read(context, local)
            ?: return "错误: 工作目录不存在该文件: $local (可用 workdir_list 查看)"
        if (bytes.isEmpty()) return "错误: 文件内容为空: $local"
        if (bytes.size > 200 * 1024 * 1024) return "错误: 文件超过 200MB 上限"
        val tag = "SshTools"
        return try {
            val session = connect(context, cfg)
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
            val session = connect(context, cfg)
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
            if (!WorkDir.write(context, localName, bytes, WorkDir.SUB_DIR_DOWNLOADS))
                return "错误: 写入工作目录失败: $localName"
            Log.i(tag, "download done: $remote -> $localName (${bytes.size} bytes)")
            "下载成功: $remote -> ${WorkDir.displaySubPath(WorkDir.SUB_DIR_DOWNLOADS)}$localName (${bytes.size} 字节)"
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
            val session = connect(context, cfg)
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(15000)
            val sb = StringBuilder("[${cfg.name}: $path]\n")
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
        private val ctx: Context,
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
            // H2: 跳板机同样做主机密钥白名单校验
            jsch.hostKeyRepository = PrefsHostKeyRepository(ctx)
            // 跳板机凭据优先用跳板专属字段; 跳板字段未填时回退复用目标机凭据(常见: 跳板与目标同一把钥匙/同一密码)
            val proxyKey = cfg.proxyPrivateKey
            val proxyPw = cfg.proxyPassword
            val proxyPhrase = cfg.proxyPassphrase
            val proxyUser = cfg.proxyUser ?: cfg.user
            val useFallback = proxyKey.isNullOrEmpty() && proxyPw.isNullOrEmpty()
            val effKey = if (!proxyKey.isNullOrEmpty()) proxyKey else if (useFallback) cfg.privateKey else null
            val effPw = if (!proxyPw.isNullOrEmpty()) proxyPw else if (useFallback) cfg.password else null
            val effPhrase = if (!proxyPhrase.isNullOrEmpty()) proxyPhrase else if (useFallback) cfg.passphrase else null
            Log.i("SshTools", "proxy key: user=" + proxyUser + " keyLen=" + (effKey?.length ?: 0) + " hasPw=" + (!effPw.isNullOrEmpty()))
            if (!effKey.isNullOrEmpty()) {
                jsch.addIdentity("proxy_key", effKey.toByteArray(), null, effPhrase?.toByteArray())
            }
            val s = jsch.getSession(proxyUser, cfg.proxyHost, cfg.proxyPort)
            if (!effPw.isNullOrEmpty()) s.setPassword(effPw)
            s.setConfig("StrictHostKeyChecking", "yes")
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