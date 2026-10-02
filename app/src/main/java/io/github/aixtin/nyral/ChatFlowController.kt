package io.github.aixtin.nyral

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.Spanned
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 会话流控制器: 从 MainActivity 抽出的"发送 -> 渲染 -> LocalEngine 流式回调 -> 收尾"编排。
 * 2026-10-03 行为等价搬迁, 不改任何逻辑; 持有宿主 MainActivity 引用访问其 internal 成员
 * 完成 UI 副作用与落库, 后续可继续拆分回调簇/UI 桥。
 */
internal class ChatFlowController(
    private val activity: MainActivity,
    private val session: ChatSessionState,
    private val uiScope: CoroutineScope,
    private val executor: Executor,
) {
    internal fun doSend(attachments: List<LocalEngine.Attachment>) {
        val text = activity.input.text.toString().trim()
        android.util.Log.i("Nyral", "onSend text=[$text] aiBusy=${session.aiBusy} attachments=${attachments.size}")
        if (text.isEmpty() && attachments.isEmpty()) return
        if (session.aiBusy) {
            Toast.makeText(activity, R.string.toast_ai_typing, Toast.LENGTH_SHORT).show()
            return
        }
        // 会话维度 Token 统计: 引擎 record 时读取
        TokenStore.currentSessionId = activity.currentSessionId
        // 发起新请求前清掉可能残留的取消标记(如切会话时 requestCancel 但引擎未在跑)
        LocalEngine.cancelRequested = false
        // 立即占住 AI 忙碌态: 附件路径走后台异步, 若不提前置位, 用户快速连发时第二个请求会穿透检查
        session.markBusy()   // 占住 AI 忙碌态(防连发穿透); 代际推进在 continueSend
        LogStore.i(LogStore.MAIN, "发送消息 len=${text.length} 附件=${attachments.size} 会话=${activity.currentSessionId}")
        // 前置轻量 UI 清理: 清空输入与附件预览(与耗时逻辑无关, 先做保证手感)
        activity.input.setText("")
        activity.pendingAttachments.clear()
        activity.attachPreviewRow.removeAllViews()
        activity.attachPreviewWrap.visibility = View.GONE
        // fix6: 发送后自动收起键盘(用户提议): 流式追底期间键盘高度全程稳定, 消除收回竞态
        // fix7: 原这里有"发送后主动弹回键盘"逻辑, 与收键盘意图相反造成"缩一下又弹出像重启", 已删
        // 收起时必须 clearFocus, 否则焦点残留时 IME 会在 layout 变化后自动弹回
        if (!activity.voiceMode) {
            activity.input.post {
                val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(activity.input.windowToken, 0)
                if (activity.input.isFocused) activity.input.clearFocus()
            }
        }
        // 前台服务+通知保活: 尽早启动(主线程此刻最空闲), 避免后续附件落盘/历史构建等重活
        // 阻塞主线程导致 startForegroundService 后约5s内未 startForeground, 触发
        // ForegroundServiceDidNotStartInTimeException 闪退; Android 13+ 先请求通知权限
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), activity.REQ_NOTIF)
        }
        try {
            TaskService.start(activity)
        } catch (e: Exception) {
            LogStore.e(LogStore.MAIN, "前台服务启动失败: ${e.message}")
        }
        // 附件落盘持久化(私有目录)移入后台线程: Base64 解码+写盘在大文件下很耗时,
        // 若留在主线程会阻塞 Service 的 startForeground 处理, 是闪退主因之一
        val doSave = {
            // 显示文本: 附件落盘后转可点击占位标记, 无文本时仅显示附件标记
            // 显示气泡: 附件与文字拆成两条独立气泡(图片/附件一条, 说明文字一条), 无文字时仅一条附件气泡
            val dispList = if (attachments.isEmpty()) listOf(text) else {
                // PDF 扫描件页图(pdfSourceName 非空)仅作发送附件, 不参与气泡显示, 避免多图网格"一通到底"
                val showAtts = attachments.filter { it.pdfSourceName == null }
                val marks = showAtts.map { a ->
                    val fileName = try {
                        // 已落库附件(stored): base64 为空, 直接以落库引用 key 做 att:// 链接, 不重复落盘
                        if (a.stored) a.name
                        else AttachmentStore.save(activity, a.name, a.mime, Base64.decode(a.base64, Base64.NO_WRAP))
                    } catch (e: Exception) {
                        null
                    }
                    val link = if (fileName != null) "(att://$fileName)" else ""
                    // 落库附件取 meta 原名(文件名含时间戳/UUID 前缀), 非落库直接用原名
                    val dispName = if (a.stored) AttachmentStore.displayName(activity, a.name) else a.name
                    when {
                        a.isEmoji -> "[表情:$dispName]$link"             // 表情库项: 独立表情气泡(96dp小图, 动图循环)
                        a.mime.startsWith("image/") -> "[图片]$link"
                        a.isVoice -> "[音频]$link"                       // 本地录音: 保留语音气泡形态
                        a.mime.startsWith("audio/") -> "[文件:$dispName]$link"  // 上传音频文件: 按文件卡片展示
                        a.mime.startsWith("video/") -> "[视频:$dispName]$link"
                        else -> "[文件:$dispName]$link"
                    }
                }
                // 每个附件独立成一张卡片气泡(含纯图片多图: 已废弃 3 列网格合并)
                val bubbles = marks
                if (text.isEmpty()) bubbles else bubbles + listOf(text)
            }
            dispList
        }
        // 无附件: 直接主线程走完; 有附件: 后台线程落盘后回主线程继续, 避免大附件阻塞主线程
        if (attachments.isEmpty()) {
            continueSend(text, listOf(text), attachments)
        } else {
            executor.execute {
                val dispList = doSave()
                uiScope.launch { continueSend(text, dispList, attachments) }
            }
        }
    }

    /**
     * doSend 的后半段: 渲染气泡 + 落库 + 构建历史 + 发起 AI 请求。
     * 拆出来以支持"附件后台落盘后回主线程继续"这一流程。
     */
    private fun continueSend(text: String, dispList: List<String>, attachments: List<LocalEngine.Attachment>) {
        // 09-24 追底回填: 发送即重置用户接管——上翻阅读旧消息的状态不带入新回复, 本轮流式恢复追底;
        // 发送后用户再上滑, RV onTouch(ACTION_DOWN)重新置位, 守卫即恢复生效
        activity.scrollUserScrolled = false
        activity.activeAiHolder?.resumeTypewriter()   // 发送: 恢复慢打(09-25)
        for (d in dispList) {
            activity.appendUser(d)
            activity.messages.add(MemoryDb.SessionMsg("user", d, "", "", "", System.currentTimeMillis()))
            MemoryKeeper.push("user", d)
        }
        activity.currentSaved = false
        // 即时落库: 不依赖 onStop 兜底, 防止发送后进程被杀(force-stop/划掉后台)导致最后一条消息丢失
        activity.maybeSaveCurrent()

        // 文档类附件本地解析文本: 预算闸门(定稿六.1): 估算 token = 字符数/3, 单轮 ≤2000 直进, 超限走索引卡
        // 直进路径保留原字符级兜底(activity.MAX_ATTACH_TEXT / activity.MAX_DOC_TOTAL); 超预算路径全文落盘, history 只放索引卡
        val ATT_BUDGET = 2000
        val docParts = ArrayList<String>()
        var docTotal = 0
        val docAtts = attachments.filter { !(it.text ?: "").isBlank() }
        val estTokens = docAtts.sumOf { (it.text?.length ?: 0) / 3 }
        if (estTokens <= ATT_BUDGET) {
            for (a in docAtts) {
                val t = a.text ?: ""
                val part = if (t.length > activity.MAX_ATTACH_TEXT) {
                    "[附件 ${a.name} 本地解析文本(已截断, 原件${t.length}字符)]\n${t.take(activity.MAX_ATTACH_TEXT)}"
                } else {
                    "[附件 ${a.name} 本地解析文本]\n$t"
                }
                if (docTotal + part.length > activity.MAX_DOC_TOTAL) {
                    docParts.add("[附件及其他] (已超总量上限, 其余内容跳过)")
                    break
                }
                docTotal += part.length
                docParts.add(part)
            }
        } else {
            // 超预算: 全文落盘 attachments/*.txt, history 只放索引卡(约300字预览), 需细节时调 attach_read 分块读取
            for (a in docAtts) {
                val t = a.text ?: ""
                val txtFile = try {
                    AttachmentStore.save(activity, a.name + ".txt", "text/plain", t.toByteArray(Charsets.UTF_8))
                } catch (e: Exception) { null }
                val loc = if (txtFile != null) "att://$txtFile" else "落盘失败"
                docParts.add("[附件 ${a.name} | ${a.mime} | 解析文本${t.length}字符 | 全文已落盘, 需要细节时调 attach_read 按 offset/limit 分块读取 | 存储 $loc]\n[摘要预览]\n${t.take(300)}")
            }
        }
        val docTexts = docParts.joinToString("\n")
        val history = if (docTexts.isBlank()) activity.buildHistory() else activity.buildHistory() + "\n$docTexts\n"
        val epoch = session.beginRequest()   // 占忙+推进代际: 上一轮迟到回调(若存在)全部失效
        activity.replySessionId = activity.currentSessionId  // 快照: 回调回来时若已切会话, 拒绝写入
        activity.attachBtn2.visibility = View.GONE
        activity.attachBtn.visibility = View.GONE
        activity.sendBtn.visibility = View.GONE
        activity.stopBtn.visibility = View.VISIBLE
        activity.stopBtn.isEnabled = true
        executor.execute {
            val holder = AiBubbleHolder(activity)
            activity.activeAiHolder = holder
            uiScope.launch {
                // 流式行: 新增 Streaming 占位行(回收传送带末位), AiBubbleHolder 气泡盒挂到该行 item 容器
                val row = ChatRow.Streaming(activity.nextTempRowId(), holder)
                session.attachStreaming(row)
                activity.chatAdapter.add(row) { activity.scrollToBottom(true) }
                val box = holder.createStreamingBox()
                row.bubbleBox = box
                activity.chatAdapter.attachStreaming(activity.chatRows.size - 1)
                holder.showLoading()
            }
            LocalEngine.chat(activity, history, object : LocalEngine.Callback {
                override fun onThinkingStart() {
                    LogStore.i(LogStore.MAIN, "开始思考")
                    activity.debugSseSink?.invoke("thinking_start", "")
                    uiScope.launch {
                        if (!session.isCurrent(epoch)) return@launch
                        session.setAiStage(1)
                        activity.updateJumpFab()
                        TaskService.updateStage(activity, activity.getString(R.string.ts_stage_thinking))
                        AITerminal.push("thinking", "开始思考…")
                        holder.showThinking()
                    }
                }
                override fun onThinkingDelta(text: String) {
                    activity.debugSseSink?.invoke("thinking", text)
                    uiScope.launch { if (!session.isCurrent(epoch)) return@launch; holder.appendThinking(text) }
                }
                override fun onThinkingEnd() {
                    AITerminal.push("thinking", "思考结束，进入作答")
                    activity.debugSseSink?.invoke("thinking_end", "")
                    uiScope.launch { if (!session.isCurrent(epoch)) return@launch; holder.collapseThinking() }
                }
                override fun onTool(name: String, arg: String) {
                    LogStore.i(LogStore.MAIN, "调用工具: $name")
                    activity.debugSseSink?.invoke("tool", "$name|$arg")
                    uiScope.launch {
                        if (!session.isCurrent(epoch)) return@launch
                        session.setAiStage(2)
                        activity.updateJumpFab()
                        TaskService.updateStage(activity, activity.getString(R.string.ts_stage_tool))
                        AITerminal.push("tool", "$name $arg")
                        holder.showTool(name, arg)
                        // 进入工具调用即表示本段思考已结束: 折叠思考区, 避免一直停在"思考中"
                        holder.collapseThinking()
                    }
                }
                override fun onToolResult(name: String, result: String) {
                    LogStore.i(LogStore.MAIN, "工具结果: $name")
                    AITerminal.push("tool_result", "$name → ${result.trim()}")
                    activity.debugSseSink?.invoke("tool_result", "$name|$result")
                    uiScope.launch { if (!session.isCurrent(epoch)) return@launch; holder.setToolResult(name, result) }
                }
                override fun onDelta(text: String) {
                    android.util.Log.i("Nyral", "onDelta=[$text]")
                    AITerminal.push("delta", text)
                    activity.debugSseSink?.invoke("delta", text)
                    // AI 表情标记流式掩码: 完整/半截 [表情:名] 均不直接暴露(显示〔表情〕占位),
                    // onDone 收尾拆分落库重建为独立表情气泡
                    val masked = activity.maskAiEmojiMarks(text)
                    uiScope.launch {
                        if (!session.isCurrent(epoch)) return@launch
                        // 正文块开始(首个 token): 阶段推进, 正文默认停滚(视口停留, 一键到底按钮接管)
                        if (session.aiStage != 3) {
                            session.setAiStage(3)
                            session.setContentFollow(false)
                            activity.updateJumpFab()
                        }
                        holder.appendContent(masked)
                        // 正文阶段停滚: 视口停留不跟随, 新内容在屏外增长;
                        // FAB 显隐随内容增长刷新(无滚动帧, onScrolled 兜底覆盖不到)
                        if (session.aiStage == 3 && !session.contentFollow) {
                            activity.updateJumpFab()
                        }
                    }
                }
                override fun onDone(reply: String) {
                    android.util.Log.i("Nyral", "onDone len=${reply.length}")
                    if (LocalEngine.cancelRequested) {
                        LogStore.w(LogStore.MAIN, "用户停止输出")
                        AITerminal.push("stop", "已停止")
                    } else {
                        LogStore.i(LogStore.MAIN, "回复完成 len=${reply.length} 会话=${activity.replySessionId}")
                        AITerminal.push("done", "回复完成 len=${reply.length}")
                    }
                    uiScope.launch {
                        // 代际校验(阶段2): 切会话/新请求已接管, 迟到回调直接丢弃, 不碰 holder/不写库/不动状态
                        if (!session.isCurrent(epoch)) return@launch
                        session.setAiStage(0)
                        session.setContentFollow(false)
                        activity.updateJumpFab()
                        // 阶段4 增量落库索引: 正文首条在 messages 中的位置(写回 rendered 用);
                        // 声明在最外层供 finishContent 后写回使用; 取消/无正文保持 -1 不写
                        var contentIdx = -1
                        if (LocalEngine.cancelRequested) {
                            // 用户主动停止: 不写入对话/记忆
                            holder.appendContent("\n(已停止)")
                            LocalEngine.cancelRequested = false
                        } else {
                            // 思考内容随回复一起持久化, 切回会话可恢复思考区
                            // 竞态防护: 回调排队期间用户可能已切会话, 不能把回复写进新会话历史
                            if (activity.replySessionId == activity.currentSessionId) {
                                // AI 表情气泡(2026-09-20): 按 [表情:名] 白名单标记把回复拆为 正文+独立表情气泡 多条消息;
                                // 第一条(通常正文)挂 thinking/tools 快照, 表情行独立无快照
                                val replyParts = activity.splitAiEmojiReply(reply)
                                contentIdx = if (replyParts.isEmpty()) -1 else activity.messages.size
                                for ((pi, p) in replyParts.withIndex()) {
                                    val snap = if (pi == 0) Triple(holder.thinkingSnapshot(), holder.toolsSnapshot(), holder.timelineSnapshot()) else Triple("", "", "")
                                    activity.messages.add(MemoryDb.SessionMsg("assistant", p, snap.first, snap.second, snap.third, System.currentTimeMillis()))
                                    MemoryKeeper.push("assistant", p)
                                }
                                activity.currentSaved = false
                                // 回复完成即时落库, 防止进程被杀丢失最后一条回复
                                activity.maybeSaveCurrent()
                                // 兜底: 引擎重试后仍无正文时给出明确提示, 避免"思考了但没输出"静默空白
                                // 仅在仍是原会话时追加, 防止切会话后提示写入新会话
                                if (reply.isBlank()) activity.appendSys(activity.getString(R.string.ma_sys_no_reply))
                            }
                        }
                        holder.finishContent()
                        // 阶段4 增量落库: 正文首条写回 rendered(排版产物所见即所得, 与 finishTypeRender
                        // 同一渲染路径), 防重启/重进二次渲染跳变; 取消(cancel)分支不落库不写回
                        if (contentIdx >= 0 && !LocalEngine.cancelRequested) {
                            val spanned = holder.renderedSnapshot()
                            if (spanned != null && spanned.isNotBlank()) {
                                try { activity.writeBackRendered((activity.sessionBaseSeq + contentIdx).toLong(), spanned as android.text.Spanned) }
                                catch (e: Exception) { android.util.Log.w(activity.TAG, "silent writeback fail", e) }
                            }
                        }
                        activity.activeAiHolder = null
                        // 流式行收尾: 已完成回复内容已落库至 messages, 移除 Streaming 行并重建为静态 AI 行;
                        // 不清理的话, 切模式/开会话全量重建时该行会被 buildRowsFromMessages 兜底再次塞回,
                        // 表现为"切 Agent 串消息 / 切回聊天 AI 回复变两条"(重启进程 streamingRow 归零即恢复)
                        val doneRow = session.detachStreaming()
                        if (!LocalEngine.cancelRequested && doneRow != null) {
                            activity.chatAdapter.remove(doneRow)
                            // 流式行移除后重建为静态 AI 行(挂快照); 重建提交(布局稳定)后未上翻
                            // 则统一精确贴底一次到位(09-25: 消除收尾帧中间态补偿造成的两段式抬升;
                            // 键盘收起同样追底——用户复现场景 imeShown=false 时旧条件直接跳过)
                            activity.buildRowsFromMessages(onCommitted = {
                                android.util.Log.i("NyralIme", "onDone.rebuild scroll=${activity.scrollUserScrolled} itemCount=${activity.chatAdapter.itemCount} imeShown=${activity.imeShown}")
                                if (!activity.scrollUserScrolled) activity.scrollToBottom(auto = true, force = true)
                                // 收尾渲染延迟增高(09-25): rebuild 后 markwon 静态渲染/收尾排版可能在
                                // align 之后才使气泡变高, pendingAlign 已扣到 0 不再补; 延迟再对齐一次
                                // 覆盖最终高度, 防最后几行被输入框遮住(用户现场: 末两行在输入框下边)
                                activity.chatRec.postDelayed({
                                    if (!activity.scrollUserScrolled) activity.scrollToBottom(auto = true, force = true)
                                }, 150)
                            })
                        }
                        session.endRequest()
                        TaskService.stop(activity)
                        activity.updateInputMode()
                        activity.stopBtn.visibility = View.GONE
                    }
                    activity.debugSseSink?.invoke("done", reply)
                    activity.debugChatDone?.invoke()
                }
                override fun onError(msg: String) {
                    LogStore.e(LogStore.MAIN, "错误: $msg")
                    AITerminal.push("error", msg)
                    uiScope.launch {
                        if (!session.isCurrent(epoch)) return@launch
                        holder.showError(activity.getString(R.string.ma_error_fmt, msg))
                        // 错误行收尾: 移除流式行, 错误提示以系统行保留(避免重建时僵尸行重复渲染)
                        val errRow = session.detachStreaming()
                        if (errRow != null) activity.chatAdapter.remove(errRow)
                        activity.appendSys(activity.getString(R.string.ma_error_fmt, msg))
                        LocalEngine.cancelRequested = false
                        session.endRequest()
                        TaskService.stop(activity)
                        activity.updateInputMode()
                        activity.stopBtn.visibility = View.GONE
                    }
                    activity.debugSseSink?.invoke("error", msg)
                    activity.debugChatDone?.invoke()
                }
            }, attachments)
        }
    }

    /**
     * 调试服务入口: 由 DebugServer(/v1/chat) 调用, 走与真实发送一致的完整链路
     * (渲染气泡 -> 落库 -> 构建历史 -> LocalEngine 工具循环 -> SSE 事件转发)。
     * 必须在主线程调用。返回 false 表示 AI 正忙, 请求被拒绝。
     */
    internal fun submitDebugChat(text: String, attachments: List<LocalEngine.Attachment> = emptyList(), onDone: () -> Unit): Boolean {
        if (session.aiBusy) return false
        activity.debugChatDone = onDone
        TokenStore.currentSessionId = activity.currentSessionId
        LocalEngine.cancelRequested = false
        session.markBusy()
        continueSend(text, listOf(text), attachments)
        return true
    }}
