package io.github.aixtin.nyral

import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 流式 UI 桥: 从 ChatFlowController 抽出的"流式行创建 + LocalEngine 流式回调簇"。
 * 2026-10-03 行为等价搬迁, 不改任何逻辑。
 * 职责:
 *  - prepareStreamingRow: 创建 Streaming 占位行并挂载 AiBubbleHolder 气泡盒(须在 UI 协程中调用)
 *  - createCallback: 构造 LocalEngine.Callback(思考/工具/正文增量/收尾/错误全分支), epoch 用于代际校验
 * 回调内仍通过 activity. 访问宿主成员完成 UI 副作用与落库收尾。
 */
internal class StreamUiBridge(
    private val activity: MainActivity,
    private val session: ChatSessionState,
    private val uiScope: CoroutineScope,
) {
    /** 创建流式占位行: 新增 Streaming 占位行(回收传送带末位), AiBubbleHolder 气泡盒挂到该行 item 容器 */
    fun prepareStreamingRow(holder: AiBubbleHolder) {
        // 流式行: 新增 Streaming 占位行(回收传送带末位), AiBubbleHolder 气泡盒挂到该行 item 容器
        val row = ChatRow.Streaming(activity.nextTempRowId(), holder)
        session.attachStreaming(row)
        activity.chatAdapter.add(row) { activity.scrollToBottom(true) }
        val box = holder.createStreamingBox()
        row.bubbleBox = box
        activity.chatAdapter.attachStreaming(activity.chatRows.size - 1)
        holder.showLoading()
    }

    /** 构造流式回调簇(思考/工具/正文/收尾/错误); epoch 用于代际校验 */
    fun createCallback(epoch: Long, holder: AiBubbleHolder): LocalEngine.Callback = object : LocalEngine.Callback {
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
                android.util.Log.i("Nyral", "onDelta len=${text.length} (M6 脱敏)")
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
    }
}
