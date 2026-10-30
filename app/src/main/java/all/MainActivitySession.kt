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
        CapabilityScope.setSessionKind(MemoryDb.KIND_NORMAL)
        summary?.let { appendSys(getString(R.string.ma_sys_loaded_summary)) }
        appendWelcomeIntro()
        refreshSessionList()
        closeDrawer()
    }

    /**
     * 设备面板「问 AI」唤入(2026-10-30 老板拍定): 不每次新开一条会话 ——
     * 固定绑定「设备分析」这一条, 空白会话当新会话用, 有历史则续接; 带进来的只有本次真实读数。
     */
    internal fun MainActivity.consumePanelIntent(i: android.content.Intent?) {
        val ask = i?.getStringExtra(DeviceAssist.EXTRA_ASK) ?: return
        val sid = i.getLongExtra(DeviceAssist.EXTRA_SESSION, 0L)
        if (sid <= 0L) return
        i.removeExtra(DeviceAssist.EXTRA_ASK)
        if (db.loadSessionMessages(sid).isEmpty()) {
            cancelActiveRequest()
            maybeSaveCurrent()
            messages.clear()
            sessionBaseSeq = 0
            chatRows.clear(); chatAdapter.notifyDataSetChanged()
            currentSaved = true
            currentSessionId = sid
            currentSessionTitle = db.sessionTitleOf(sid) ?: DeviceAssist.TITLE
            CapabilityScope.setSessionKind(MemoryDb.KIND_NORMAL)
            scrollUserScrolled = false
            session.resetStreamUi()
            updateJumpFab()
            refreshSessionList()
            closeDrawer()
        } else if (currentSessionId != sid) {
            openSession(sid)
        }
        input.setText(ask)
        input.setSelection(input.text.length)
        window.decorView.post { onSend() }
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
        // 能力域(2026-10-30 P3): 体检会话只读, 普通/任务会话恢复常规执行域
        // 设备面板绑定会话(2026-10-30): 库里仍是普通会话(要出现在会话列表), 但能力域降为只读,
        // 面板问 AI 只做排查不写盘/不跑 shell, 避免每轮弹安全确认
        CapabilityScope.setSessionKind(
            if (DeviceAssist.isPanelSession(this, id)) MemoryDb.KIND_DIAG else db.sessionKindOf(id)
        )
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
            val sid0 = currentSessionId
            // 面板绑定会话(2026-10-30): 不按首条消息改名, 否则抽屉标题会变成 "[设备] 22041216UC ..." 认不出来
            val title = if (sid0 != null && DeviceAssist.isPanelSession(this, sid0)) {
                DeviceAssist.TITLE
            } else {
                messages.firstOrNull { it.role == "user" }?.content
                    ?.replace("\n", " ")?.take(20) ?: getString(R.string.ma_unnamed_session)
            }
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
        val mode = ModeConfig.modeValue()
        // 分组取数(2026-10-30 P1): 各组独立 20 条, 互不挤占; 普通组直铺, 任务/体检进折叠组
        val normal = db.listSessions(20, mode, MemoryDb.KIND_NORMAL)
        val timers = db.listSessions(20, mode, MemoryDb.KIND_TIMER)
        if (normal.isEmpty() && timers.isEmpty()) {
            // 空态兜底(2026-10-29): 图标+标题+副文案三级层级, 取代单行灰字
            sessionList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(48), 0, dp(48))
                addView(android.widget.ImageView(act).apply {
                    setImageResource(R.drawable.lucide_message_square)
                    imageTintList = android.content.res.ColorStateList.valueOf(Ui.SUB)
                    alpha = 0.55f
                }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(TextView(act).apply {
                    text = getString(R.string.ma_no_sessions)
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Ui.SUB)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                })
                addView(TextView(act).apply {
                    text = getString(R.string.ma_no_sessions_hint)
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    alpha = 0.75f
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                })
            })
            return
        }
        // 顺序(2026-10-30 老板反馈): 两个分组头置顶, 普通会话在下——普通会话再多也顶不掉这两个入口
        addSessionGroup("定时任务", MemoryDb.KIND_TIMER, timers)
        normal.forEach { addSessionRow(sessionList, it) }
    }

    /** 单条会话行(标题 + 时间), 渲染到指定容器, 末尾带分隔线 */
    internal fun MainActivity.addSessionRow(box: LinearLayout, s: MemoryDb.SessionInfo) {
        val act = this
        box.addView(LinearLayout(this).apply {
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
        box.addView(View(this).apply {
            setBackgroundColor(Ui.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
    }

    /** 分组头 + 折叠子列表(2026-10-30 P1): 状态存内存不落盘; 空组也渲染, 避免入口消失 */
    internal fun MainActivity.addSessionGroup(title: String, kind: Int, list: List<MemoryDb.SessionInfo>) {
        val act = this
        val collapsed = kind in sessionCollapsedKinds
        val children = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (collapsed) View.GONE else View.VISIBLE
        }
        val arrow = TextView(this).apply {
            text = if (collapsed) "▸" else "▾"
            textSize = 12f
            setTextColor(Ui.SUB)
            setPadding(0, 0, dp(6), 0)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isClickable = true
            addView(arrow)
            addView(TextView(act).apply {
                text = title
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Ui.SUB)
            })
            addView(TextView(act).apply {
                text = "  " + list.size
                textSize = 11f
                setTextColor(Ui.SUB)
                alpha = 0.7f
            })
            setOnClickListener {
                val expand = children.visibility != View.VISIBLE
                children.visibility = if (expand) View.VISIBLE else View.GONE
                arrow.text = if (expand) "▾" else "▸"
                if (expand) sessionCollapsedKinds.remove(kind) else sessionCollapsedKinds.add(kind)
            }
        }
        sessionList.addView(header)
        sessionList.addView(children)
        sessionList.addView(View(this).apply {
            setBackgroundColor(Ui.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
        if (list.isEmpty()) {
            children.addView(TextView(this).apply {
                text = "暂无"
                textSize = 12f
                setTextColor(Ui.SUB)
                alpha = 0.6f
                setPadding(dp(34), dp(8), dp(20), dp(10))
            })
            return
        }
        list.forEach { addSessionRow(children, it) }
    }
