package io.github.aixtin.nyral

import android.content.Context.INPUT_METHOD_SERVICE
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 底部附件面板: 拍照 / 相册 / 位置 / 文件 四宫格。
 * (原表情抽屉的 emoji 网格、表情库、最近常用副栏已于 2026-11-19 整体切除)
 *
 * 占位模型: 面板挂 dockContent 底部, 高度动画 0↔targetH, 展开时自动顶起输入框(微信式)
 * 锚定输入框整体(dockContent)顶部, 随键盘 insets 压缩 bodyWrap 同步上移, 与 bodyWrap 压缩彻底解耦。
 */
internal var attachPanel: LinearLayout? = null
internal var panelOpen = false
/** 收面板弹键盘后的抑制窗: 期间点击空白不收键盘(防止长按抬手时面板下滑导致落点判为面板外, 被 dispatchTouchEvent 反杀) */
internal var suppressHideImeUntil = 0L

/** 面板切键盘联动挂起标记: 面板先不动, 等键盘 insets 动画 onPrepare 再同步启动高度动画(防面板先收输入框先掉再被顶回) */
internal var panelDropPending = false
/** 键盘->面板中间交接挂起标记: 键盘开着时面板弹出挂起, 等键盘收回动画 onPrepare 同步启动(时长=键盘动画) */
internal var panelPopPending = false
/** 面板高度动画器: 高度 0↔panelTargetH, 展开时顶起输入框(占位式) */
internal var panelHAnim: android.animation.ValueAnimator? = null
/** 小面板当前展开目标高(px): 档位A=30%屏高-dockContent高, 档位B=键盘高(imeHeightField)-dockContent高 */
internal var panelTargetH = 0
/** 一体式互斥基准高(px): 键盘高(showAttachPanel 时快照); 互斥公式 edd = 基准 - imeH, 动画全程输入框零位移 */
internal var panelKbH = 0
/** 面板固定结构高参考(px): 四宫格两行+padding≈200dp; 内容区不足时内部滚动, 不再作为展开目标 */
internal var panelFixedH = 0

internal fun MainActivity.toggleAttachPanel() {
    if (panelOpen) {
        // 切回键盘: 按钮点击可能已让 EditText 失焦(Button 抢焦点), 先确保聚焦再收面板,
        // 否则 hideAttachPanel 按 input.isFocused 判断 willShowIme=false -> 键盘不弹、输入框缩回
        if (!input.isFocused) input.requestFocus()
        hideAttachPanel()
    } else {
        showAttachPanel()
    }
}

/** 判断触摸点是否落在附件面板可视区域内(用于点击面板外空白收面板); 面板未展开/不可见时返回 false */
internal fun MainActivity.isTouchInsideAttachPanel(rawX: Float, rawY: Float): Boolean {
    val d = attachPanel ?: return false
    if (d.visibility != View.VISIBLE) return false
    val loc = IntArray(2)
    d.getLocationOnScreen(loc)
    return rawX >= loc[0] && rawX <= loc[0] + d.width &&
            rawY >= loc[1] && rawY <= loc[1] + d.height
}

/** 判断触摸点是否落在附件预览条可视区域内(点击预览条/×按钮只删附件, 不应收键盘或收面板) */
internal fun MainActivity.isTouchInsideAttachPreview(rawX: Float, rawY: Float): Boolean {
    val v = attachPreviewWrap
    if (v.visibility != View.VISIBLE) return false
    val loc = IntArray(2)
    v.getLocationOnScreen(loc)
    return rawX >= loc[0] && rawX <= loc[0] + v.width &&
            rawY >= loc[1] && rawY <= loc[1] + v.height
}

internal fun MainActivity.showAttachPanel() {
    if (panelOpen) return
    panelOpen = true
    panelHAnim?.cancel(); panelHAnim = null
    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
    // 键盘是否真的在显示: 实时读 insets ime 高(>80dp 视为弹出)。imm.isActive 仅表示 IME 会话活跃,
    // 键盘收起后仍为 true, 误判会导致走"收起键盘+350ms 兜底"分支 -> 直开面板停顿(用户 bug 现场)
    val kbOpen = if (android.os.Build.VERSION.SDK_INT < 30) {
        // API29-: ADJUST_RESIZE 下无 ime inset, 用根视图高度差反推的键盘高判断
        legacyImeH > dp(80)
    } else try {
        all.InsetsX.imeBottom(window.decorView.rootWindowInsets) > dp(80)
    } catch (e: Exception) { false }
    android.util.Log.i("NyralIme", "showPanel kbOpen=$kbOpen isActive=${imm?.isActive} imeShown=$imeShown imeHeightField=$imeHeightField")
    attachPanel?.let { d ->
        d.visibility = View.VISIBLE
        d.animate().cancel()
        // 一体式: 面板目标高 = 真实键盘高减导航栏(键盘态 bodyWrap 底=root-imeH, 面板态底=root-sb,
        // 减 sb 后输入框零位移) 或 固定兜底高(首次无键盘记录);
        // 键盘收回后由面板顶替原键盘空间, 输入框(大抽屉顶)零位移
        panelKbH = imeHeightField
        val sbNav = try {
            all.InsetsX.systemBarsBottom(window.decorView.rootWindowInsets)
        } catch (e: Exception) { 0 }
        // API29-: imeHeightField 是 bodyWrap 被压缩的高度差, 即纯键盘高(内容区本就在导航栏上方),
        // 再减 sbNav 属重复扣减; 且键盘态下 systemBars().bottom 读数会膨胀(实测 48dp→130dp),
        // 减去后 target 被 coerceAtLeast(dp(160)) 截断 -> "先开键盘再切面板, 面板很矮"。
        panelTargetH = if (imeHeightField > dp(80)) {
            if (android.os.Build.VERSION.SDK_INT < 30) imeHeightField.coerceAtLeast(dp(160))
            else (imeHeightField - sbNav).coerceAtLeast(dp(160))
        } else panelFixedH.coerceAtLeast(dp(160))
        if (kbOpen) {
            // 键盘开着: 收起键盘, 面板撑高由 insets 动画逐帧驱动(互斥公式 h = target - imeH)
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
            // 兜底: 键盘瞬时收回无 insets 动画(onApply 不触发)时, 超时直接撑高
            // 不依赖 panelOpen: showAttachPanel 置 true 后, hideSoftInput 触发的键盘动画
            // onPrepare 可能把 panelOpen 误清, 导致兜底永不执行 -> 面板高度 0、聊天不顶起。
            // 改判"面板可见且尚未撑起"。
            window.decorView.postDelayed({
                val dd = attachPanel ?: return@postDelayed
                // fix(11-17): 兜底(legacy 桥无信号时)改为瞬时到位, 不再走 220ms 长高动画:
                // 窗口已复原的单帧里再从 0 长高, 同样会先"掉底再弹上来"。
                if (panelOpen && dd.visibility == View.VISIBLE && dd.height <= dp(1)) {
                    setPanelHeight(panelTargetH.coerceAtLeast(dp(160)))
                    alignChatToViewport()
                }
            }, 400)
        } else {
            // 无键盘: 直接撑到目标高
            startPanelPopAnim(220)
        }
    }
    // 悬浮浏览器态展开时隐藏 browserBar
    setBrowserBarVisible(false)
}

/** 面板统一设高: 只设置面板高度; 一体式下面板在 dockContent 内, 高度变化直接改变大抽屉总高(输入框+面板)。
 * 设高后同帧把让位推给聊天区(不等布局回调), 消除逐帧滞后。 */
internal fun MainActivity.setPanelHeight(h: Int) {
    val d = attachPanel ?: return
    val lp = d.layoutParams
    if (lp.height != h) { lp.height = h; d.layoutParams = lp }
    applyBottomOcclusion(h)
}

/** 面板高度展开动画(一体式): 0→panelTargetH; 反悔打断可 cancel 后反向 */
internal fun MainActivity.startPanelPopAnim(durationMs: Long) {
    val d = attachPanel ?: return
    d.translationY = 0f
    panelHAnim?.cancel()
    val startH = d.height
    val targetH = panelTargetH
    android.util.Log.i("NyralIme", "popAnim startH=$startH target=$targetH open=$panelOpen vis=${d.visibility}")
    if (startH >= targetH) return
    panelHAnim = android.animation.ValueAnimator.ofInt(startH, targetH).apply {
        duration = durationMs
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { a ->
            val v = a.animatedValue as Int
            setPanelHeight(v)
            // fix(09-22): 展开动画期间聊天区视口随面板撑高逐帧变小, 消息必须同帧跟随贴底,
            // 否则输入框+面板先就位、消息动画结束后才滚上来(10x 慢放下"后跟上"断层明显)。
            // 与键盘 insets onProgress 逐帧驱动同语义: setPanelHeight 触发布局后,
            // post 到下一帧按真实视口算 gap 锚定末条, 全程无跳变
            alignChatToViewport()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // fix(09-22): 面板展开压缩聊天区视口后, 聊天区停留在旧滚动位置,
                // 最新消息被面板遮住(截图显示旧消息"需要点正经的, 我可以:")
                // 展开到位后滚到最新一条(同键盘 insets 路径的贴底语义)
                scrollToBottom()
            }
        })
        start()
    }
}

/** 聊天区视口尺寸变化(面板高度动画/键盘恢复)后末条贴底:
 * 末条可见按真实像素 gap 拉底(长文/变高消息也精确); 末条不可见(用户正翻历史)保持阅读位置不动 */
internal fun MainActivity.alignChatToViewport() {
    // 合并节流: 掉帧时 post 队列里堆多个补偿会依次执行, 表现为消息"下去又上来";
    // 只保留最新一次(下一帧按最新视口重算)。
    if (chatAlignPending) return
    chatAlignPending = true
    chatRec.post {
        chatAlignPending = false
        if (chatRec.scrollState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) return@post
        val lm = chatRec.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return@post
        val lastPos = (chatRec.adapter?.itemCount ?: 0) - 1
        val lv = lm.findViewByPosition(lastPos) ?: return@post
        val gap = lv.bottom - (chatRec.height - chatRec.paddingBottom)
        if (gap != 0) chatRec.scrollBy(0, gap)
    }
}

internal fun MainActivity.hideAttachPanel() {
    if (!panelOpen) return
    panelOpen = false
    panelHAnim?.cancel(); panelHAnim = null  // 反悔打断: 取消正在弹出的面板动画, 停中间值供反向继续
    // 一体式: 点+收面板时输入框仍聚焦 -> 弹键盘, 面板高度由 insets 动画逐帧驱动收起
    // (互斥公式 h = target - imeH, 键盘弹出面板同步收起, 输入框零位移), 不跑独立收起动画防抖动。
    val willShowIme = input.isFocused
    android.util.Log.i("NyralIme", "hidePanel willShow=$willShowIme open=$panelOpen")
    attachPanel?.let { d ->
        d.animate().cancel()
        if (willShowIme) {
            // 抑制窗: 弹键盘请求发出后 500ms 内, dispatchTouchEvent 的"点空白收键盘"分支不得反杀
            // (长按进附件: 面板下滑中抬手, 落点可能已判为面板外, 643 分支 post 的 hideSoftInput+clearFocus 会把刚弹的键盘收掉 = 概率性不弹根因)
            suppressHideImeUntil = android.os.SystemClock.uptimeMillis() + 500
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            // API29-: 没有 insets 动画驱动面板收起(API30 靠 startPanelDropSync), 自行收起,
            // 否则 panelOpen 已置 false 但高度停在原位 -> 面板收不回去、点击无反应
            if (android.os.Build.VERSION.SDK_INT < 30) {
                // fix(11-17b): 键盘即将提起时不能先跑收起动画。面板先收缩会让输入栏跟着往下滑
                // (实测 dockY 430 -> 438 -> 465 -> 507, 再被键盘顶回 431): 慢速下看不出来, 1x 下就是闪抖。
                // 这里保持满高不动, 等键盘真正顶上来那一帧由 onLegacyImeChanged / preDraw 同步收到 0,
                // 输入栏全程零位移。
                d.postDelayed({
                    if (!panelOpen && d.visibility == View.VISIBLE && d.height > 1) {
                        startPanelDropAnim(180)   // 键盘始终没弹起(被系统拒绝)的兜底, 防面板悬空
                    }
                }, 600)
            }
            val ok = imm?.showSoftInput(input, 0) == true
            android.util.Log.i("NyralIme", "showIme ok=$ok open=$panelOpen")
            if (!ok) {
                startPanelDropAnim(180)  // 键盘未弹起兜底: 直接收起面板恢复 chatArea 撑满, 防止输入框悬空
            }
        } else {
            // 无键盘收起(点空白/再点+): 直接收起面板
            startPanelDropAnim(180)
        }
    }
    // 浏览器控制条恢复显隐以浏览器是否打开为准(勿无条件显示, 否则普通聊天收面板会冒出"欢迎回来"条)
    setBrowserBarVisible(browserOpen())
}

/** 面板高度收起动画: targetH→0, 面板收回让位(大抽屉缩回输入框+面板, 输入框随 chatArea 回落) */
internal fun MainActivity.startPanelDropAnim(durationMs: Long) {
    val d = attachPanel ?: return
    panelHAnim?.cancel()
    val startH = d.height
    if (startH <= 0) return
    panelHAnim = android.animation.ValueAnimator.ofInt(startH, 0).apply {
        duration = durationMs
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { a ->
            val v = a.animatedValue as Int
            setPanelHeight(v)
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                val dd = attachPanel ?: return
                dd.translationY = 0f  // 收完复位补偿, 防残留偏移影响下次展开
                dd.visibility = View.GONE
                panelHAnim = null
                // fix(09-22): 面板收起后聊天区视口恢复变高, 若展开期间滚过底,
                // 恢复后视口底部会多出空白, 末条贴底对齐(用户翻历史时末条不可见则不动)
                alignChatToViewport()
            }
        })
        start()
    }
}

/** 键盘 insets 动画开始时同步启动面板收起联动(onPrepare 调用): 与键盘动画同时长同时启动,
 * 面板高度递减与键盘升起同帧, 降到 0 即收完(键盘接管 bodyWrap 压缩, 输入框不掉底) */
internal fun MainActivity.startPanelDropSync(durationMs: Long) {
    android.util.Log.i("NyralIme", "dropSync dur=$durationMs pend=$panelDropPending open=$panelOpen")
    // 键盘弹出必须无条件收面板(含直接点输入框弹键盘场景):
    // 面板保持展开会在 bodyWrap 压缩后占位, 把输入框顶到键盘上方/输入框掉底
    if (!panelDropPending && !panelOpen) return
    panelDropPending = false
    panelOpen = false
    startPanelDropAnim(durationMs)
}

/** 面板是否半开残留(可见且高度未到收起位): 反悔打断后键盘未重弹时, MainActivity onEnd 据此收尾。
 * fix(09-21): 判据收紧——弹出动画运行中或已展开到位均属正常弹出, 不算残留;
 * 否则键盘收回 onEnd 会把"键盘→面板切换"中正在弹出的面板误判为半开残留并 finishAttachPanelDrop 掐掉,
 * 表现为"点附件按钮概率性直接收回输入框(键盘收了面板没弹出来)"。 */
internal fun MainActivity.isAttachPanelHalfOpen(): Boolean {
    val d = attachPanel ?: return false
    if (d.visibility != View.VISIBLE) return false
    if (d.height <= dp(1)) return false
    // 弹出动画进行中: 属正常弹出, 不是残留
    if (panelHAnim?.isRunning == true) return false
    // 已展开到位(高度=展开位): 正常展开态, 不是残留
    if (d.height >= panelTargetH - dp(1)) return false
    return true
}

/** 面板收起中途被键盘收回打断时的兜底: 直接收完面板(供 MainActivity insets 回调调用) */
internal fun MainActivity.finishAttachPanelDrop() {
    android.util.Log.i("NyralIme", "finishDrop pend=$panelDropPending")
    panelDropPending = false
    panelPopPending = false
    panelHAnim?.cancel(); panelHAnim = null
    attachPanel?.let { dd ->
        dd.animate().cancel()
        dd.translationY = 0f  // 复位键盘补偿
        setPanelHeight(0)
        dd.visibility = View.GONE
    }
}

/** 键盘收回动画开始时同步启动面板弹出(onPrepare 调用): 时长=键盘动画, 进度同步 ->
 * 键盘收到一半面板弹出一半, 中间交接; 面板比键盘高/键盘高度自定义均按各自面板进度同步, 不按像素对齐 */
internal fun MainActivity.startPanelPopSync(durationMs: Long) {
    android.util.Log.i("NyralIme", "popSync dur=$durationMs pend=$panelPopPending")
    if (!panelPopPending) return
    panelPopPending = false
    startPanelPopAnim(durationMs)
}

private fun MainActivity.buildUploadGrid(): LinearLayout {
    val grid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    // 上传格: 外层占满格子且透明, 内层圆角卡片 wrap_content 包住图标+文字并居中
    fun item(iconRes: Int, label: String, onClick: () -> Unit): LinearLayout {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(Ui.INPUT_BG)
                cornerRadius = dp(14).toFloat()
            }
            setOnClickListener { onClick() }
        }
        val iv = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(Ui.TEXT)
        }
        val tv = TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Ui.SUB)
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        card.addView(iv, LinearLayout.LayoutParams(dp(26), dp(26)))
        card.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        holder.addView(card)
        return holder
    }
    val cells = listOf(
        Triple(R.drawable.ic_upload_camera, "拍照", { launchCamera(); hideAttachPanel() }),
        Triple(R.drawable.ic_upload_image, "相册", { pickMedia(); hideAttachPanel() }),
        Triple(R.drawable.ic_upload_location, "位置", { Toast.makeText(this, "原型占位: 位置待接入", Toast.LENGTH_SHORT).show() }),
        Triple(R.drawable.ic_upload_file, "文件", { pickFile(); hideAttachPanel() })
    )
    cells.chunked(2).forEach { row ->
        val rowLay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.forEach { (icon, label, onClick) ->
            // fix(09-22): holder 高度改 WRAP_CONTENT(内容高), 行高同步 WRAP_CONTENT:
            // 面板高度动画(键盘弹出收起/展开)期间不再被 LinearLayout 权重压扁卡片
            rowLay.addView(item(icon, label, onClick), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
        grid.addView(rowLay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    return grid
}

/** 构建附件面板并返回, 由 MainActivity 挂到 dockContent 底部(占位式, 高度动画 0↔targetH 顶起输入框) */
internal fun MainActivity.buildAttachPanel(): LinearLayout {
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(10))
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(
                dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(),
                0f, 0f, 0f, 0f
            )
            setColor(Ui.SURFACE)
        }
    }
    attachPanel = panel
    panelFixedH = dp(200)  // 固定结构高参考(四宫格两行内容高, 内容区不足时内部滚动)

    // 单页: 上传四宫格
    // fix(09-22): 包 ScrollView(isFillViewport=false): 面板高度不足(键盘弹出收起动画/30% 兜底档)时
    // 内容内部滚动保持原尺寸, 不被 LinearLayout 按比例压缩变形; fillViewport=true 会让内容在视口内
    // 动态重排(按钮贴底)造成"内容跟随键盘上移下拉", 故禁用
    val contentUpload = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = false
        addView(buildUploadGrid(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    panel.addView(contentUpload, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    panel.visibility = View.GONE
    panel.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)  // 初始高度 0; 挂载时 MainActivity 用 LinearLayout.LayoutParams(MATCH_PARENT,0) 覆盖
    return panel
}
