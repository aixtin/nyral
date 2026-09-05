package io.github.aixtin.droidagent
import io.github.aixtin.droidagent.R

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
// ============================================================
// MainActivity UI 构建区（抽屉/搜索/弹窗/附件/模型选择等）
// 由 MainActivity.kt 拆分而来，全部为 MainActivity 扩展函数
// ============================================================

    internal fun MainActivity.buildDrawer() {
        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            translationX = -DRAWER_WIDTH.toFloat()
            elevation = dp(8).toFloat()
        }
        // 头部: 与主页标题栏同款白底深色, 去掉蓝色大色块; 右上角迷你搜索框, 点击向左展开成可输入态
        drawerPanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(12), dp(14))
            setBackgroundColor(Color.WHITE)
            // 左侧标题列(weight=1)
            addView(LinearLayout(this@buildDrawer).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@buildDrawer).apply {
                    text = TitleConfig.drawerTitle()
                    textSize = 20f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#1A1A1A"))
                }.also { drawerTitleText = it })
                addView(TextView(this@buildDrawer).apply {
                    text = TitleConfig.drawerNote()
                    textSize = 13f
                    setTextColor(Color.parseColor("#999999"))
                    setPadding(0, dp(4), 0, 0)
                }.also { drawerNoteText = it })
            })
            // 右上角迷你搜索框: 折叠态=仅右侧极简放大镜(无背景), 点击放大镜向左展开成输入框(果冻), 再点放大镜收起
            searchWrap = LinearLayout(this@buildDrawer).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            }
            searchEdit = EditText(this@buildDrawer).apply {
                hint = getString(R.string.mui_hint_search)
                setHintTextColor(Color.parseColor("#BBBBBB"))
                textSize = 13f
                setTextColor(Color.parseColor("#1A1A1A"))
                background = null
                setPadding(0, 0, 0, 0)
                setSingleLine(true)
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                visibility = View.GONE
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                        performSearch(text?.toString().orEmpty())
                        true
                    } else false
                }
            }
            searchWrap.addView(searchEdit, LinearLayout.LayoutParams(0, dp(30), 1f))
            // 极简放大镜图标(右侧, 无背景): 点击展开输入框, 再次点击收起
            searchWrap.addView(View(this@buildDrawer).apply {
                background = searchIconBg(resources.displayMetrics.density)
                isClickable = true
                setOnClickListener { toggleSearchBox() }
                Ui.press(this)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            })
            addView(searchWrap)
        })
        drawerPanel.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#F0F0F2"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        // 中间区域：新会话 + 会话记录
        sessionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drawerPanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(dp(16), dp(12), dp(16), 0)
            addView(TextView(this@buildDrawer).apply {
                text = "＋ 新会话"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                // 与主页模型切换按钮同款: 浅蓝底蓝字圆角
                setTextColor(Color.parseColor("#0B93F6"))
                gravity = Gravity.CENTER
                background = rounded(dp(16), Color.parseColor("#E8F3FE"))
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setOnClickListener { startNewSession() }
            })
            addView(TextView(this@buildDrawer).apply {
                text = getString(R.string.mui_records)
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
                setPadding(dp(20), dp(12), dp(20), dp(4))
            })
            addView(ScrollView(this@buildDrawer).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                isFillViewport = true
                addView(sessionList)
            })
        })
        drawerPanel.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#F0F0F2"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
        // 右下角设置入口
        drawerPanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundColor(Color.parseColor("#F7F7F8"))
            isClickable = true
            setOnClickListener {
                closeDrawer()
                startActivity(Intent(this@buildDrawer, SettingsActivity::class.java))
            }
            Ui.press(this)
            addView(TextView(this@buildDrawer).apply {
                text = "⚙ 设置"
                textSize = 15f
                setTextColor(Color.parseColor("#1A1A1A"))
            })
        })
    }

    internal fun MainActivity.drawerItem(title: String, subtitle: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true
            setOnClickListener { onClick() }
            Ui.press(this)
            addView(TextView(this@drawerItem).apply {
                text = title
                textSize = 16f
                setTextColor(Color.parseColor("#1A1A1A"))
            })
            addView(TextView(this@drawerItem).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#999999"))
                setPadding(0, dp(3), 0, 0)
            })
        }
    }

    internal fun MainActivity.openDrawer() {
        if (drawerOpen) return
        // 打开汉堡页前自动收起键盘，避免主页残留键盘顶起汉堡页内容
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        drawerOpen = true
        refreshSessionList()
        drawerMask.visibility = View.VISIBLE
        drawerMask.animate().alpha(1f).setDuration(200).start()
        drawerPanel.animate().translationX(0f).setDuration(220).start()
    }

    internal val MainActivity.searchCollapsedW get() = dp(34)

    /** 点击放大镜 toggle: 折叠->展开, 展开->收起 */
    internal fun MainActivity.toggleSearchBox() {
        if (searchExpanded) collapseSearchBox() else expandSearchBox()
    }

    /** 汉堡页右上角搜索框: 折叠态(仅放大镜) -> 点击放大镜向左展开成可输入态(果冻), 再点放大镜收起 */
    internal fun MainActivity.expandSearchBox() {
        if (searchExpanded) {
            // 已展开: 聚焦输入框等待输入
            searchEdit.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchEdit, InputMethodManager.SHOW_IMPLICIT)
            return
        }
        searchExpanded = true
        searchEdit.visibility = View.VISIBLE
        // 展开态: 补回圆角浅灰背景(仅展开时), 宽度再缩短避免挤压左侧标题
        searchWrap.background = rounded(dp(16), Color.parseColor("#EFEFF1"))
        searchWrap.setPadding(dp(10), 0, dp(6), 0)
        val targetW = dp(130)
        val anim = ValueAnimator.ofInt(searchCollapsedW, targetW).apply {
            duration = 300
            interpolator = OvershootInterpolator(2.2f)
            addUpdateListener {
                val w = it.animatedValue as Int
                searchWrap.layoutParams = searchWrap.layoutParams.apply { width = w }
                searchWrap.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    searchEdit.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showSoftInput(searchEdit, InputMethodManager.SHOW_IMPLICIT)
                }
            })
            start()
        }
    }

    /** 收起搜索框: 动画缩回放大镜态, 清空输入 */
    internal fun MainActivity.collapseSearchBox() {
        if (!searchExpanded) return
        searchExpanded = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchEdit.windowToken, 0)
        searchEdit.clearFocus()
        searchEdit.setText("")
        ValueAnimator.ofInt(searchWrap.width.coerceAtLeast(searchCollapsedW), searchCollapsedW).apply {
            duration = 220
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener {
                val w = it.animatedValue as Int
                searchWrap.layoutParams = searchWrap.layoutParams.apply { width = w }
                searchWrap.requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    searchEdit.visibility = View.GONE
                    // 折叠态: 移除展开时的背景与内边距, 恢复纯放大镜无背景
                    searchWrap.background = null
                    searchWrap.setPadding(0, 0, 0, 0)
                }
            })
            start()
        }
    }

    /** 执行搜索: 收起搜索框并弹出两栏结果(左会话/右AI思考) */
    internal fun MainActivity.performSearch(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        collapseSearchBox()
        val hits = db.searchChatMessages(kw)
        if (hits.isEmpty()) {
            Toast.makeText(this, getString(R.string.mui_toast_no_session, kw), Toast.LENGTH_SHORT).show()
            return
        }
        showSearchResults(kw, hits)
    }

    /** 搜索结果弹窗: 左栏「会话」content 命中, 右栏「AI思考」thinking 命中, 关键词高亮 */
    internal fun MainActivity.showSearchResults(kw: String, hits: List<MemoryDb.SearchHit>) {
        var sortBy = 0        // 0=相关性, 1=时间
        var asc = false       // false=倒序(默认), true=正序
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setCanceledOnTouchOutside(true)
        val w = (resources.displayMetrics.widthPixels * 0.94).toInt()
        val maxH = (resources.displayMetrics.heightPixels * 0.78).toInt()
        lateinit var cols: LinearLayout

        // 排序: 相关性=score 降序为主+时间降序兜底; 时间=updatedAt; asc=true 时整体反转为正序
        fun sortHits(list: List<MemoryDb.SearchHit>): List<MemoryDb.SearchHit> {
            val sorted = if (sortBy == 1)
                list.sortedWith(compareByDescending<MemoryDb.SearchHit> { it.updatedAt })
            else
                list.sortedWith(compareByDescending<MemoryDb.SearchHit> { it.score }.thenByDescending { it.updatedAt })
            return if (asc) sorted.reversed() else sorted
        }

        fun rebuildCols() {
            val c = cols ?: return
            val contentHits = sortHits(hits.filter { it.matchedField == 0 })
            val thinkHits = sortHits(hits.filter { it.matchedField == 1 })
            c.removeAllViews()
            c.addView(buildSearchColumn(getString(R.string.mui_col_session), kw, contentHits, d),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            c.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#F0F0F2"))
                layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    leftMargin = dp(10); rightMargin = dp(10)
                }
            })
            c.addView(buildSearchColumn(getString(R.string.mui_col_ai_think), kw, thinkHits, d),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = rounded(dp(18), Color.WHITE)
        }
        // 标题行: 左=搜索词+命中总数, 右=排序切换(字段/方向)
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = getString(R.string.mui_search_hits, kw, hits.size)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun sortBtn(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#0B93F6"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = Ui.rounded(Color.parseColor("#EAF4FE"), dp(10), this@showSearchResults)
            Ui.press(this)
        }
        val fieldBtn = sortBtn(getString(R.string.mui_field_relevance))
        val dirBtn = sortBtn(getString(R.string.mui_sort_desc))
        fieldBtn.setOnClickListener {
            sortBy = if (sortBy == 1) 0 else 1
            fieldBtn.text = if (sortBy == 1) getString(R.string.mui_field_time) else getString(R.string.mui_field_relevance)
            rebuildCols()
        }
        dirBtn.setOnClickListener {
            asc = !asc
            dirBtn.text = if (asc) getString(R.string.mui_sort_asc) else getString(R.string.mui_sort_desc)
            rebuildCols()
        }
        titleRow.addView(fieldBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        titleRow.addView(dirBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        root.addView(titleRow)
        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#F0F0F2"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12)
            }
        })
        // 两栏容器
        cols = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(10)
            }
        }
        rebuildCols()
        root.addView(cols)
        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(w, maxH)
        d.window?.setGravity(Gravity.CENTER)
        // 搜索结果弹窗: 无动画即开即显
        root.scaleX = 1f
        root.scaleY = 1f
        root.alpha = 1f
        d.show()
    }

    /** 构建一栏搜索结果: 栏标题 + 命中列表(关键词高亮, 点击跳转定位) */
    internal fun MainActivity.buildSearchColumn(
        label: String, kw: String, hits: List<MemoryDb.SearchHit>, d: Dialog
    ): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "$label (${hits.size})"
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
            setPadding(dp(2), 0, dp(2), dp(6))
        })
        if (hits.isEmpty()) {
            col.addView(TextView(this).apply {
                text = getString(R.string.mui_no_hits)
                textSize = 12f
                setTextColor(Color.parseColor("#BBBBBB"))
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return col
        }
        val sv = ScrollView(this).apply { isFillViewport = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hits.forEach { hit ->
            val snippet = hit.matchedField.takeIf { it == 0 }?.let { hit.content } ?: hit.thinking
            val title = hit.title.ifBlank { getString(R.string.mui_unnamed_session) }
            val roleTag = if (hit.role == "user") "我" else "AI"
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                background = rounded(dp(10), Color.parseColor("#F7F7F8"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(6)
                }
                setOnClickListener {
                    d.dismiss()
                    openSession(hit.sessionId, hit.seq)
                }
            }
            item.addView(TextView(this).apply {
                text = "$roleTag · $title"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#333333"))
                maxLines = 1
            })
            item.addView(TextView(this).apply {
                setText(highlightKeyword(snippet, kw))
                textSize = 12f
                setTextColor(Color.parseColor("#666666"))
                maxLines = 3
                setPadding(0, dp(3), 0, 0)
            })
            list.addView(item)
        }
        sv.addView(list)
        col.addView(sv)
        return col
    }

    /** Token 统计下拉面板: 标题栏下方, 向下展开 */
    internal fun MainActivity.buildTokenPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(dp(14), Color.WHITE)
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }
        fun row(title: String): TextView = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(2), 0, dp(2))
        }
        panel.addView(row(getString(R.string.mui_row_ctx_usage)))
        tokenPanelCtx = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(0, 0, 0, dp(10))
        }
        panel.addView(tokenPanelCtx)
        panel.addView(row(getString(R.string.mui_row_cur_usage)))
        tokenPanelSess = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
        }
        panel.addView(tokenPanelSess)
        panel.addView(TextView(this).apply {
            text = getString(R.string.mui_token_stat_hint)
            textSize = 11f
            setTextColor(Color.parseColor("#BBBBBB"))
            setPadding(0, dp(10), 0, 0)
        })
        tokenPanel = panel
        return panel
    }

    internal fun MainActivity.toggleTokenPanel() {
        val p = tokenPanel ?: return
        val show = p.visibility != View.VISIBLE
        if (show) {
            refreshTokenPanel()
            tokenMask.visibility = View.VISIBLE
            // 悬浮层定位: 顶边对齐标题栏底部(标题栏已布局, height 有效), 右缘对齐屏幕右缘
            val lp = p.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = titleBar.height
            p.layoutParams = lp
            // 果冻展开: 锚点=面板右上角(∑ 图标处), 向左下角弹性弹出
            p.pivotY = 0f
            p.scaleX = 0.15f
            p.scaleY = 0.15f
            p.alpha = 0f
            p.visibility = View.VISIBLE
            // 首次展开时 GONE 状态 width=0, post 到布局完成后再取宽设 pivot
            p.post {
                p.pivotX = p.width.toFloat()
                p.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(360)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }
        } else {
            hideTokenPanel()
        }
    }

    internal fun MainActivity.hideTokenPanel() {
        val p = tokenPanel ?: return
        if (p.visibility != View.VISIBLE) return
        p.animate().cancel()
        p.animate()
            .scaleX(0.15f).scaleY(0.15f).alpha(0f)
            .setDuration(180)
            .withEndAction {
                p.visibility = View.GONE
                tokenMask.visibility = View.GONE
            }
            .start()
    }

    internal fun MainActivity.refreshTokenPanel() {
        tokenPanelCtx.text = formatTokens(TokenStore.lastPrompt(this)) + " tokens"
        val (sp, sc) = TokenStore.sessionStats(this, currentSessionId)
        tokenPanelSess.text = formatTokens(sp + sc) + " tokens"
    }

    internal fun MainActivity.closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        drawerPanel.animate().translationX(-DRAWER_WIDTH.toFloat()).setDuration(220).start()
        drawerMask.animate().alpha(0f).setDuration(220).withEndAction {
            drawerMask.visibility = View.GONE
        }.start()
    }

    /** 会话长按操作菜单: 置顶/取消置顶 + 重命名 + 删除 */
    internal fun MainActivity.showSessionMenu(s: MemoryDb.SessionInfo) {
        val (dlg, box) = Ui.dialog(this, getString(R.string.mui_dlg_session_ops))
        box.addView(Ui.dialogText(this, "「${s.title}」"))
        box.addView(Ui.primaryBtn(this, if (s.pinned) getString(R.string.mui_unpin) else getString(R.string.mui_pin)) {
            dlg.dismiss()
            db.setPinned(s.id, !s.pinned)
            refreshSessionList()
            Toast.makeText(this, if (s.pinned) getString(R.string.mui_toast_unpinned) else getString(R.string.mui_toast_pinned), Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.lightBtn(this, getString(R.string.mui_rename)) {
            dlg.dismiss()
            showRenameDialog(s)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        box.addView(Ui.dangerBtn(this, getString(R.string.mui_delete_session)) {
            dlg.dismiss()
            confirmDeleteSession(s)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        box.addView(Ui.dialogCancelBtn(this, getString(R.string.mui_cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        dlg.show()
    }

    /** 重命名会话: 输入框 + 保存 */
    internal fun MainActivity.showRenameDialog(s: MemoryDb.SessionInfo) {
        val (dlg, box) = Ui.dialog(this, getString(R.string.mui_dlg_rename))
        box.addView(Ui.fieldLabel(this, getString(R.string.mui_field_new_name)))
        val input = Ui.input(this, getString(R.string.mui_hint_input_name))
        input.setText(s.title)
        box.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(Ui.primaryBtn(this, getString(R.string.mui_save)) {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, getString(R.string.mui_toast_name_empty), Toast.LENGTH_SHORT).show()
                return@primaryBtn
            }
            dlg.dismiss()
            db.renameSession(s.id, name)
            if (currentSessionId == s.id) currentSessionTitle = name
            refreshSessionList()
            Toast.makeText(this, getString(R.string.mui_toast_renamed), Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dialogCancelBtn(this, getString(R.string.mui_cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        dlg.show()
    }

    /** 删除会话: 二次确认后执行 */
    internal fun MainActivity.confirmDeleteSession(s: MemoryDb.SessionInfo) {
        val (dlg, box) = Ui.dialog(this, getString(R.string.mui_delete_session))
        box.addView(Ui.dialogText(this, getString(R.string.mui_dlg_confirm_delete, s.title)))
        box.addView(Ui.dangerBtn(this, getString(R.string.mui_delete)) {
            dlg.dismiss()
            db.deleteSession(s.id)
            refreshSessionList()
            Toast.makeText(this, getString(R.string.mui_toast_deleted), Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(Ui.dialogCancelBtn(this, getString(R.string.mui_cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        dlg.show()
    }

    /** 附件选择弹窗(PopupWindow 融合样式, 对齐模型弹窗): 相册/其他文件/音频; 相册与音频按当前模型能力置灰(预设=内置表, 手动=用户勾选) */
    internal fun MainActivity.showAttachSheet() {
        if (attachPopup?.isShowing == true) {
            dismissAttachPopup()
            return
        }
        val caps = ApiConfig.modelCapabilities(ApiConfig.providerId(), ApiConfig.model())
        val hasImage = ApiConfig.CAP_IMAGE in caps
        val hasAudio = ApiConfig.CAP_AUDIO in caps
        val hasVideo = ApiConfig.CAP_VIDEO in caps
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = rounded(dp(14), Color.WHITE)
            elevation = dp(10).toFloat()
        }
        fun item(label: String, enabled: Boolean, onClick: () -> Unit) {
            val tv = TextView(this).apply {
                text = label + if (enabled) "" else getString(R.string.mui_cap_unsupported_suffix)
                textSize = 14f
                isAllCaps = false
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setTextColor(if (enabled) Color.parseColor("#333333") else Color.parseColor("#BBBBBB"))
                alpha = if (enabled) 1f else 0.5f
                setOnClickListener {
                    if (!enabled) {
                        Toast.makeText(this@showAttachSheet, getString(R.string.mui_toast_cap_unsupported), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    dismissAttachPopup()
                    onClick()
                }
            }
            col.addView(tv)
        }
        item(getString(R.string.mui_attach_album), hasImage) { pickImage() }
        item(getString(R.string.mui_attach_video), hasVideo) { pickVideo() }
        item(getString(R.string.mui_attach_audio), hasAudio) { pickAudio() }
        item(getString(R.string.mui_attach_other), true) { pickFile() }
        col.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val popH = col.measuredHeight
        // focusable=false: 不抢输入框焦点, 键盘保持弹出; 弹窗浮在键盘上方
        attachPopup = PopupWindow(col, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            elevation = dp(10).toFloat()
            isTouchable = true
            isOutsideTouchable = true
            setBackgroundDrawable(GradientDrawable()) // 透明背景+非null: 才能收到 ACTION_OUTSIDE
            // 外部点击关闭(避开 attachBtn: 按钮点击走 toggle): focusable=false 收不到自动 dismiss,
            // 须靠 setTouchInterceptor 拦截 ACTION_OUTSIDE 自己关; 点 attachBtn 时直接收起并消费事件
            setTouchInterceptor { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) {
                    val p = IntArray(2)
                    attachBtn.getLocationOnScreen(p)
                    val inBtn = e.rawX >= p[0] && e.rawX <= p[0] + attachBtn.width &&
                            e.rawY >= p[1] && e.rawY <= p[1] + attachBtn.height
                    if (inBtn) {
                        dismissAttachPopup()
                        return@setTouchInterceptor true
                    }
                    dismissAttachPopup()
                }
                false
            }
        }
        attachPopup!!.showAsDropDown(attachBtn, 0, -(popH + attachBtn.height + dp(6)))
        // 果冻展开: 弹窗向上弹出(锚定 attachBtn), 锚点=弹窗左下角, 向右上弹性弹出
        col.pivotX = 0f
        col.pivotY = col.height.toFloat()
        col.scaleX = 0.15f
        col.scaleY = 0.15f
        col.alpha = 0f
        col.post {
            col.pivotX = 0f
            col.pivotY = col.height.toFloat()
            col.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(360)
                .setInterpolator(OvershootInterpolator(2.2f))
                .start()
        }
    }

    /** 附件弹窗收起: 果冻缩小动画结束后再 dismiss, 与展开对称 */
    internal fun MainActivity.dismissAttachPopup() {
        val p = attachPopup ?: return
        if (attachClosing) return
        attachClosing = true
        val v = p.contentView
        v.animate().cancel()
        v.animate()
            .scaleX(0.15f).scaleY(0.15f).alpha(0f)
            .setDuration(180)
            .withEndAction {
                attachClosing = false
                if (attachPopup === p) {
                    p.dismiss()
                    attachPopup = null
                }
            }
            .start()
    }

    /** 模型选择弹窗(思维链式): 点击供应商行向下展开子模型列表, 点击子模型切换 */
    internal fun MainActivity.showModelList() {
        // 未配置(API 且未填 Key)的供应商不展示; 本地模型无 Key, 始终展示
        val providers = ApiConfig.providers().filter { p -> p.type == "local" || p.key.isNotBlank() }
        if (providers.isEmpty()) return
        // 弹窗已显示时再次点击 modelBtn: 直接收起(开关切换), 不重复弹出
        if (modelPopup?.isShowing == true) {
            dismissModelPopup()
            return
        }
        var expandedId: String? = null

        fun rebuild() {
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = rounded(dp(14), Color.WHITE)
            }
            val curId = ApiConfig.providerId()
            // 思考强度区（手动模型=全量 7 档；预设模型按能力动态出档；已保存档位不适用时回退"自动"）
            val curP = ApiConfig.providerById(curId)
            val manual = curP?.isPreset != true
            val levels = if (manual) ApiConfig.THINK_LEVELS
                else ApiConfig.thinkingLevelsOf(curId, ApiConfig.currentModelOf(curId))
            val curEffort = ApiConfig.thinkingEffortOf(curId)
            val eff = if (levels.any { it.first == curEffort }) curEffort else ApiConfig.THINK_AUTO
            list.addView(TextView(this).apply {
                text = getString(R.string.mui_think_intensity)
                textSize = 11f
                setTextColor(Color.parseColor("#999999"))
                setPadding(dp(18), dp(8), dp(6), dp(2))
            })
            val effortRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(2), dp(14), dp(8))
            }
            levels.forEach { (key, name) ->
                val sel = key == eff
                effortRow.addView(TextView(this).apply {
                    text = name
                    textSize = 12f
                    isAllCaps = false
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    setTextColor(if (sel) Color.WHITE else Color.parseColor("#0B93F6"))
                    background = rounded(dp(15), if (sel) Color.parseColor("#0B93F6") else Color.parseColor("#E8F3FE"))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = dp(6)
                    }
                    setOnClickListener {
                        ApiConfig.setThinkingEffort(curId, key)
                        rebuild()
                    }
                })
            }
            list.addView(effortRow)
            list.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#EEEEF0"))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
            providers.forEach { p ->
                val isCur = p.id == curId
                val curModel = ApiConfig.currentModelOf(p.id)
                val hasSub = p.models.isNotEmpty()
                val expanded = p.id == expandedId
                // 供应商行: 点击展开/收起子模型
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                    setOnClickListener {
                        expandedId = if (expanded) null else p.id
                        rebuild()
                    }
                }
                row.addView(TextView(this).apply {
                    text = p.label + if (isCur) "  ✓" else ""
                    textSize = 14f
                    isAllCaps = false
                    setPadding(dp(18), dp(12), dp(6), dp(12))
                    setTextColor(if (isCur) Color.parseColor("#0B93F6") else Color.parseColor("#333333"))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                // 右侧: 当前子模型名 + 展开箭头
                row.addView(TextView(this).apply {
                    text = if (hasSub) curModel else ""
                    textSize = 11f
                    setPadding(dp(4), dp(12), dp(2), dp(12))
                    setTextColor(Color.parseColor("#999999"))
                })
                row.addView(TextView(this).apply {
                    text = if (hasSub) (if (expanded) "▾" else "›") else ""
                    textSize = 16f
                    setPadding(dp(8), dp(4), dp(10), dp(4))
                    setTextColor(Color.parseColor("#BBBBBB"))
                })
                list.addView(row)
                // 展开的子模型行（模型名 + 能力 chips）
                if (expanded && hasSub) {
                    p.models.forEach { m ->
                        // 仅当前生效供应商且为该供应商当前模型时高亮勾选（避免未选供应商的模型也显示✓）
                        val isCurModel = isCur && m == curModel
                        val caps = ApiConfig.modelCapabilities(p.id, m)
                        val subRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(40), dp(6), dp(18), dp(2))
                            setOnClickListener {
                                ApiConfig.setSelectedModel(p.id, m)
                                ApiConfig.setCurrent(p.id)
                                dismissModelPopup()
                                refreshVoiceButton()
                            }
                        }
                        subRow.addView(TextView(this).apply {
                            text = "· " + m + if (isCurModel) "  ✓" else ""
                            textSize = 13f
                            isAllCaps = false
                            setTextColor(if (isCurModel) Color.parseColor("#0B93F6") else Color.parseColor("#666666"))
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        list.addView(subRow)
                        // 能力 chips 行：文本固定展示，其余按实际能力渲染（参考 ModelEditActivity.renderSelectedModels）
                        val capsRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(dp(40), dp(2), dp(18), dp(8))
                        }
                        val allCaps = listOf(ApiConfig.CAP_TEXT) + ApiConfig.CAP_LABELS.keys.toList()
                        allCaps.forEach { cap ->
                            if (cap in caps) {
                                val label = if (cap == ApiConfig.CAP_TEXT) "文本" else ApiConfig.CAP_LABELS[cap] ?: cap
                                capsRow.addView(TextView(this).apply {
                                    text = label
                                    textSize = 10f
                                    isAllCaps = false
                                    setTextColor(Color.parseColor("#666666"))
                                    background = rounded(dp(10), Color.parseColor("#F0F2F5"))
                                    setPadding(dp(7), dp(2), dp(7), dp(2))
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                        rightMargin = dp(5)
                                    }
                                })
                            }
                        }
                        list.addView(capsRow)
                    }
                }
            }
            val wrap = ScrollView(this).apply {
                removeAllViews()
                addView(list)
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
            }
            wrap.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val maxH = dp(420)
            val popH = minOf(wrap.measuredHeight, maxH)
            if (modelPopup == null) {
                // focusable=false: 不抢输入框焦点, 键盘保持弹出; 弹窗浮在键盘上方
                modelPopup = PopupWindow(wrap, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
                    elevation = dp(10).toFloat()
                    isTouchable = true
                    isOutsideTouchable = true
                    setBackgroundDrawable(GradientDrawable()) // 透明背景+非null: 才能收到 ACTION_OUTSIDE
                    // 外部点击关闭(避开 modelBtn: 按钮点击走 toggle): focusable=false 收不到自动 dismiss,
                    // 须靠 setTouchInterceptor 拦截 ACTION_OUTSIDE 自己关; 点 modelBtn 时直接收起并消费事件,
                    // 避免事件穿透到 modelBtn 的 onClick 导致"点几次都重新弹出"
                    setTouchInterceptor { _, e ->
                        if (e.action == MotionEvent.ACTION_OUTSIDE) {
                            val p = IntArray(2)
                            modelBtn.getLocationOnScreen(p)
                            val inBtn = e.rawX >= p[0] && e.rawX <= p[0] + modelBtn.width &&
                                    e.rawY >= p[1] && e.rawY <= p[1] + modelBtn.height
                            if (inBtn) {
                                dismissModelPopup()
                                return@setTouchInterceptor true
                            }
                            dismissModelPopup()
                        }
                        false
                    }
                }
                modelPopup!!.showAsDropDown(modelBtn, 0, -(popH + modelBtn.height + dp(6)))
                // 果冻展开: 弹窗向上弹出(锚定 modelBtn), 锚点=弹窗左下角, 向右上弹性弹出
                wrap.pivotX = 0f
                wrap.pivotY = wrap.height.toFloat()
                wrap.scaleX = 0.15f
                wrap.scaleY = 0.15f
                wrap.alpha = 0f
                wrap.post {
                    wrap.pivotX = 0f
                    wrap.pivotY = wrap.height.toFloat()
                    wrap.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(360)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                }
            } else {
                // 已显示且要更新内容: 先关闭再重建, 避免 update 坐标语义导致弹窗跑位
                modelPopup!!.dismiss()
                modelPopup = null
                rebuild()
                return
            }
        }
        rebuild()
    }

    /** 模型弹窗收起: 果冻缩小动画结束后再 dismiss, 与展开对称 */
    internal fun MainActivity.dismissModelPopup() {
        val p = modelPopup ?: return
        if (modelClosing) return
        modelClosing = true
        val v = p.contentView
        v.animate().cancel()
        v.animate()
            .scaleX(0.15f).scaleY(0.15f).alpha(0f)
            .setDuration(180)
            .withEndAction {
                modelClosing = false
                if (modelPopup === p) {
                    p.dismiss()
                    modelPopup = null
                }
            }
            .start()
    }

