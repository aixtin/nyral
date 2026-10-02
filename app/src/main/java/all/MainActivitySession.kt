package io.github.aixtin.nyral

import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 会话域(MainActivity 扩展): 新建会话/切换会话/会话保存/会话列表刷新 —— 自 MainActivity.kt 拆出 */

    internal fun MainActivity.startNewSession() {
        // AI 正在输出时切会话: 取消引擎 + 失效代际, 防止其把未完成的回复写进新会话历史
        cancelActiveRequest()
        maybeSaveCurrent()
        messages.clear()
        sessionBaseSeq = 0
        chatRows.clear(); chatAdapter.notifyDataSetChanged()
        currentSaved = true
        currentSessionId = null
        currentSessionTitle = null
        summary?.let { appendSys(getString(R.string.ma_sys_loaded_summary)) }
        appendWelcomeIntro()
        refreshSessionList()
        closeDrawer()
    }

    internal fun MainActivity.openSession(id: Long, locateSeq: Int? = null) {
        // AI 正在输出时切会话: 取消引擎 + 失效代际, 防止其把未完成的回复写进新会话历史
        cancelActiveRequest()
        maybeSaveCurrent()
        // 超长会话内存瘦身: 消息数 > MEM_WINDOW 时仅载入最近窗口(更早消息保留 DB 供搜索/回溯, 上下文由 summary 承担)
        val total = db.countSessionMessages(id)
        val msgs = if (total > MEM_WINDOW) {
            sessionBaseSeq = total - MEM_WINDOW
            db.loadSessionMessagesTail(id, MEM_WINDOW)
        } else {
            sessionBaseSeq = 0
            db.loadSessionMessages(id)
        }
        if (msgs.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_messages, Toast.LENGTH_SHORT).show()
            return
        }
        // 切会话: 旧会话表情帧动画实例立即统一终结(不等 detach 看门狗 10s), 名额立即归还,
        // 否则切回会话 10s 内新表情 attach 被旧实例占满 MAX_ACTIVE -> 全部降级缩略图不动(09-19 反馈)
        EmojiFrameAnimator.sActive.toList().forEach { c -> try { c.killSelf() } catch (_: Throwable) {} }
        messages.clear()
        // 阶段5 池化清理: 切会话即清形态 View 池, 旧会话 View 树(含文本/rendered Spanned)不滞留复用,
        // 避免串会话内容残留与内存驻留; 会话内全量重建(头像刷新/窗口外回退)不清池, 保留复用收益
        chatAdapter.clearPool()
        // 09-28 第五波: AiRich 分片/头像/wrap 三池一并清空——切会话后池中旧会话 View 树若被新会话复用,
        // 残留 KEY_RENDER_MD tag 会命中幂等跳过渲染(内容相同时)或滞留旧会话 Spanned 引用
        aiRichSegPool.clear()
        aiAvatarPool.clear()
        aiRichWrapPool.clear()
        messages.addAll(msgs)
        currentSaved = true
        currentSessionId = id
        currentSessionTitle = db.sessionTitleOf(id)
        // 吸底修复: 切会话重置用户滚动标记(旧会话的"正在阅读"不应带入新会话), 新会话默认追底
        scrollUserScrolled = false
        activeAiHolder?.resumeTypewriter()   // 切会话: 恢复慢打(09-25)
        session.resetStreamUi()
        updateJumpFab()
        // 滚动时机修复: ListAdapter.submitList 为异步 diff, 滚动必须等 diff 提交后执行,
        // 否则 itemCount 仍是旧会话值→滚到错误位置/直接不滚(表现为"切会话后不在最新, 像自己滚动")
        var scrolled = false
        val scrollAfterCommit = scrollAfterCommit@{
            if (scrolled) return@scrollAfterCommit
            scrolled = true
            if (locateSeq != null) {
                // 定位到命中消息(搜索/跳转): RecyclerView 直接滚到该行 + 短暂高亮
                chatRec.post {
                    if (locateSeq < sessionBaseSeq) {
                        Toast.makeText(this, R.string.toast_loaded_far_history, Toast.LENGTH_LONG).show()
                        // 窗口外命中: 临时全量加载该会话(仅本次, 定位后恢复窗口)
                        sessionBaseSeq = 0
                        val full = db.loadSessionMessages(id)
                        messages.clear(); messages.addAll(full)
                        buildRowsFromMessages()
                        val idx = locateSeq.coerceIn(0, chatRows.lastIndex)
                        chatRec.scrollToPosition(idx)
                        chatRec.post { chatAdapter.highlightRow(chatRows.getOrNull(idx)) }
                    } else {
                        val idx = (locateSeq - sessionBaseSeq).coerceIn(0, chatRows.lastIndex)
                        chatRec.scrollToPosition(idx)
                        chatRec.post { chatAdapter.highlightRow(chatRows.getOrNull(idx)) }
                    }
                }
            } else {
                // 吸底修复: AsyncListDiffer 的 onCommitted 可能迟到(用户切会话后已开始上翻),
                // 用户已触摸列表就让位, 不再拉底打断阅读(不触摸则正常定位底部)
                Log.d("SCROLLDBG", "openSession commit id=" + id + " scrolled=" + scrolled + " uScroll=" + scrollUserScrolled + " itemCount=" + chatAdapter.itemCount + " attached=" + chatRec.isAttachedToWindow + " h=" + chatRec.height)
                if (!scrollUserScrolled) scrollToBottom()
            }
        }
        buildRowsFromMessages {
            if (sessionBaseSeq > 0) {
                // 窗口化提示行插入到历史消息头部(与旧 ScrollView 行为一致: 提示在顶部), 其 diff 提交后再滚动
                chatAdapter.insert(0, ChatRow.Sys(nextTempRowId(), getString(R.string.ma_sys_window_hint, MEM_WINDOW))) { scrollAfterCommit() }
            } else {
                scrollAfterCommit()
            }
        }
        // 长气泡吸底跳跃修复: 后台预编译本会话历史 AI 消息的 markdown, 上翻浏览时 bind 直接命中缓存零解析
        prewarmMdCache()
        refreshSessionList()
        closeDrawer()
    }

    internal fun MainActivity.maybeSaveCurrent(mode: Int = ModeConfig.modeValue()) {
        if (!currentSaved && messages.isNotEmpty()) {
            val title = messages.firstOrNull { it.role == "user" }?.content
                ?.replace("\n", " ")?.take(20) ?: getString(R.string.ma_unnamed_session)
            val sid = currentSessionId
            if (sid != null) {
                db.updateSession(sid, title, messages, mode, sessionBaseSeq)
            } else {
                currentSessionId = db.saveSession(title, messages, mode)
            }
            currentSessionTitle = title
            currentSaved = true
        }
    }

    internal fun MainActivity.refreshSessionList() {
        val act = this
        sessionList.removeAllViews()
        val list = db.listSessions(20, ModeConfig.modeValue())
        if (list.isEmpty()) {
            sessionList.addView(TextView(this).apply {
                text = getString(R.string.ma_no_sessions)
                textSize = 12f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
            return
        }
        list.forEach { s ->
            sessionList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(12), dp(20), dp(12))
                isClickable = true
                setOnClickListener { openSession(s.id) }
                isLongClickable = true
                setOnLongClickListener {
                    showSessionMenu(s)
                    true
                }
                addView(TextView(act).apply {
                    text = (if (s.pinned) "📌 " else "") + s.title
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = 14f
                    setTextColor(Ui.TEXT)
                })
                addView(TextView(act).apply {
                    text = (if (s.pinned) getString(R.string.ma_pinned_prefix) else "") + fmtTime(s.updatedAt)
                    textSize = 11f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(2), 0, 0)
                })
            })
            sessionList.addView(View(this).apply {
                setBackgroundColor(Ui.DIVIDER)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
    }
