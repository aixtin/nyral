package io.github.aixtin.nyral

import android.app.AlertDialog
import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.VideoView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.launch

/**
 * 表情抽屉(原型): 主顶栏[上传/emoji/表情库] + 副栏最近常用8个(LRU) + 内容区。
 * 占位模型: 抽屉挂 bodyWrap 底部(dockContent 之后), 高度动画 0↔targetH, 展开时自动顶起输入框(微信式)
 * 锚定输入框整体(dockContent)顶部, 随键盘 insets 压缩 bodyWrap 同步上移, 与 bodyWrap 压缩彻底解耦。
 */
internal var emojiDrawer: LinearLayout? = null
internal var emojiOpen = false
/** 收抽屉弹键盘后的抑制窗: 期间点击空白不收键盘(防止长按抬手时抽屉下滑导致落点判为抽屉外, 被 dispatchTouchEvent 反杀) */
internal var suppressHideImeUntil = 0L
private var emojiTab = 1 // 0=上传 1=emoji 2=表情库; 初始默认展示 emoji 网格

/** 抽屉切键盘联动挂起标记: 抽屉先不动, 等键盘 insets 动画 onPrepare 再同步启动高度动画(防抽屉先收输入框先掉再被顶回) */
internal var emojiDropPending = false
/** 键盘->抽屉中间交接挂起标记: 键盘开着时抽屉弹出挂起, 等键盘收回动画 onPrepare 同步启动(时长=键盘动画) */
internal var emojiPopPending = false
/** 抽屉高度动画器: 高度 0↔emojiDrawerTargetH, 展开时顶起输入框(占位式) */
internal var emojiDrawerHAnim: android.animation.ValueAnimator? = null
/** 小抽屉当前展开目标高(px): 档位A=30%屏高-dockContent高, 档位B=键盘高(imeHeightField)-dockContent高 */
internal var emojiDrawerTargetH = 0
/** 一体式互斥基准高(px): 键盘高(showEmojiDrawer 时快照); 互斥公式 edd = 基准 - imeH, 动画全程输入框零位移 */
internal var emojiDrawerKbH = 0
/** 抽屉固定结构高参考(px): topBar(≈38)+recent(38)+content+padding≈310dp; 内容区不足时内部滚动, 不再作为展开目标 */
internal var emojiDrawerFixedH = 0
private var recentBar: LinearLayout? = null
private var recentScroll: HorizontalScrollView? = null
private var recentDivider: View? = null
private var contentWrap: LinearLayout? = null
private var tabUpload: TextView? = null
private var tabEmoji: TextView? = null
private var tabLib: TextView? = null
private var contentEmoji: View? = null
private var contentUpload: View? = null
private var contentLib: View? = null
private var contentLibGrid: LinearLayout? = null

private const val RECENT_MAX = 6
private val EMOJIS = listOf(
    "😀", "😁", "😂", "🤣", "😊", "😍", "🤔", "😎",
    "🥳", "😭", "😡", "👍", "👎", "👌", "🙏", "💪",
    "🔥", "❤️", "💯", "🎉", "✨", "⭐", "🌟", "🎂",
    "🎁", "🏆", "🚀", "⚡", "☕", "🎵"
)

/** 表情库网格中正在循环播放的动图 VideoView 集合(刷新/删除时统一释放, 防 MediaPlayer 泄漏) */
private val sLibVideoViews = mutableListOf<VideoView>()

/** 表情库编辑模式: 多选删除; libSelected 存相对路径 emoji_lib/xxx */
private var libEditMode = false
private val libSelected = mutableSetOf<String>()
private var libEditBtn: TextView? = null
private var libDelBtn: TextView? = null

private fun MainActivity.emojiPrefs() = getSharedPreferences("emoji_drawer", Context.MODE_PRIVATE)

private fun MainActivity.recentEmojis(): List<String> =
    emojiPrefs().getString("recent", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

/** 点击 emoji 插入输入框光标处(无光标时追加末尾, 不直接发送); 更新 LRU 置顶 */
internal fun MainActivity.sendEmoji(e: String) {
    val sel = input.selectionStart.coerceAtLeast(0)
    input.text.insert(sel, e)
    input.setSelection(sel + e.length)
    // LRU 置顶
    val list = recentEmojis().toMutableList().apply { remove(e); add(0, e) }.take(RECENT_MAX)
    emojiPrefs().edit().putString("recent", list.joinToString(",")).apply()
    refreshRecentBar()
}

internal fun MainActivity.toggleEmojiDrawer() {
    if (emojiOpen) {
        // 切回键盘: 按钮点击可能已让 EditText 失焦(Button 抢焦点), 先确保聚焦再收抽屉,
        // 否则 hideEmojiDrawer 按 input.isFocused 判断 willShowIme=false -> 键盘不弹、输入框缩回
        if (!input.isFocused) input.requestFocus()
        hideEmojiDrawer()
    } else {
        showEmojiDrawer()
    }
}

/** 判断触摸点是否落在表情抽屉可视区域内(用于点击抽屉外空白收抽屉); 抽屉未展开/不可见时返回 false */
internal fun MainActivity.isTouchInsideEmojiDrawer(rawX: Float, rawY: Float): Boolean {
    val d = emojiDrawer ?: return false
    if (d.visibility != View.VISIBLE) return false
    val loc = IntArray(2)
    d.getLocationOnScreen(loc)
    return rawX >= loc[0] && rawX <= loc[0] + d.width &&
            rawY >= loc[1] && rawY <= loc[1] + d.height
}

/** 判断触摸点是否落在附件预览条可视区域内(点击预览条/×按钮只删附件, 不应收键盘或收抽屉) */
internal fun MainActivity.isTouchInsideAttachPreview(rawX: Float, rawY: Float): Boolean {
    val v = attachPreviewWrap
    if (v.visibility != View.VISIBLE) return false
    val loc = IntArray(2)
    v.getLocationOnScreen(loc)
    return rawX >= loc[0] && rawX <= loc[0] + v.width &&
            rawY >= loc[1] && rawY <= loc[1] + v.height
}

internal fun MainActivity.showEmojiDrawer() {
    if (emojiOpen) return
    emojiOpen = true
    emojiDrawerHAnim?.cancel(); emojiDrawerHAnim = null
    refreshEmojiLib()
    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
    // 键盘是否真的在显示: 实时读 insets ime 高(>80dp 视为弹出)。imm.isActive 仅表示 IME 会话活跃,
    // 键盘收起后仍为 true, 误判会导致走"收起键盘+350ms 兜底"分支 -> 直开表情停顿(用户 bug 现场)
    val kbOpen = try {
        (window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0) > dp(80)
    } catch (e: Exception) { false }
    android.util.Log.i("NyralIme", "showDrawer kbOpen=$kbOpen isActive=${imm?.isActive} imeShown=$imeShown imeHeightField=$imeHeightField")
    emojiDrawer?.let { d ->
        d.visibility = View.VISIBLE
        d.animate().cancel()
        // 一体式: 表情区目标高 = 真实键盘高减导航栏(键盘态 bodyWrap 底=root-imeH, 表情态底=root-sb,
        // 减 sb 后输入框零位移) 或 固定兜底高(首次无键盘记录);
        // 键盘收回后由表情区顶替原键盘空间, 输入框(大抽屉顶)零位移
        emojiDrawerKbH = imeHeightField
        val sbNav = try {
            window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0
        } catch (e: Exception) { 0 }
        emojiDrawerTargetH = if (imeHeightField > dp(80)) (imeHeightField - sbNav).coerceAtLeast(dp(160))
                             else emojiDrawerFixedH.coerceAtLeast(dp(160))
        if (kbOpen) {
            // 键盘开着: 收起键盘, 表情区撑高由 insets 动画逐帧驱动(互斥公式 h = target - imeH)
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
            // 兜底: 键盘瞬时收回无 insets 动画(onApply 不触发)时, 超时直接撑高
            // 兜底: 键盘瞬时收回无 insets 动画(onApply 不触发)时, 超时直接撑高。
            // 不依赖 emojiOpen: showEmojiDrawer 置 true 后, hideSoftInput 触发的键盘动画
            // onPrepare 可能把 emojiOpen 误清, 导致兜底永不执行 -> 表情区高度 0、聊天不顶起。
            // 改判"抽屉可见且尚未撑起"。
            window.decorView.postDelayed({
                val dd = emojiDrawer ?: return@postDelayed
                if (dd.visibility == View.VISIBLE && dd.height <= dp(1)) startEmojiPopAnim(220)
            }, 350)
        } else {
            // 无键盘: 直接撑到目标高
            startEmojiPopAnim(220)
        }
    }
    // 悬浮浏览器态展开时隐藏 browserBar
    setBrowserBarVisible(false)
}

/** 抽屉统一设高: 只设置抽屉高度; 一体式下抽屉在 dockContent 内, 高度变化直接改变大抽屉总高(输入框+表情区) */
internal fun MainActivity.setEmojiDrawerHeight(h: Int) {
    val d = emojiDrawer ?: return
    val lp = d.layoutParams
    if (lp.height != h) { lp.height = h; d.layoutParams = lp }
}

/** 抽屉高度展开动画(一体式): 0→emojiDrawerTargetH; 反悔打断可 cancel 后反向 */
internal fun MainActivity.startEmojiPopAnim(durationMs: Long) {
    val d = emojiDrawer ?: return
    d.translationY = 0f
    emojiDrawerHAnim?.cancel()
    val startH = d.height
    val targetH = emojiDrawerTargetH
    android.util.Log.i("NyralIme", "popAnim startH=$startH target=$targetH open=$emojiOpen vis=${d.visibility}")
    if (startH >= targetH) return
    emojiDrawerHAnim = android.animation.ValueAnimator.ofInt(startH, targetH).apply {
        duration = durationMs
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { a ->
            val v = a.animatedValue as Int
            setEmojiDrawerHeight(v)
            // fix(09-22): 展开动画期间聊天区视口随抽屉撑高逐帧变小, 消息必须同帧跟随贴底,
            // 否则输入框+表情先就位、消息动画结束后才滚上来(10x 慢放下"后跟上"断层明显)。
            // 与键盘 insets onProgress 逐帧驱动同语义: setEmojiDrawerHeight 触发布局后,
            // post 到下一帧按真实视口算 gap 锚定末条, 全程无跳变
            alignChatToViewport()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // fix(09-22): 表情区展开压缩聊天区视口后, 聊天区停留在旧滚动位置,
                // 最新消息被表情面板遮住(截图显示旧消息"需要点正经的, 我可以:")
                // 展开到位后滚到最新一条(同键盘 insets 路径的贴底语义)
                scrollToBottom()
            }
        })
        start()
    }
}

/** 聊天区视口尺寸变化(表情区高度动画/键盘恢复)后末条贴底:
 * 末条可见按真实像素 gap 拉底(长文/变高消息也精确); 末条不可见(用户正翻历史)保持阅读位置不动 */
internal fun MainActivity.alignChatToViewport() {
    chatRec.post {
        if (chatRec.scrollState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) return@post
        val lm = chatRec.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return@post
        val lastPos = (chatRec.adapter?.itemCount ?: 0) - 1
        val lv = lm.findViewByPosition(lastPos) ?: return@post
        val gap = lv.bottom - (chatRec.height - chatRec.paddingBottom)
        if (gap != 0) chatRec.scrollBy(0, gap)
    }
}

internal fun MainActivity.hideEmojiDrawer() {
    if (!emojiOpen) return
    emojiOpen = false
    emojiDrawerHAnim?.cancel(); emojiDrawerHAnim = null  // 反悔打断: 取消正在弹出的抽屉动画, 停中间值供反向继续
    // 一体式: 点+收抽屉时输入框仍聚焦 -> 弹键盘, 表情区高度由 insets 动画逐帧驱动收起
    // (互斥公式 h = target - imeH, 键盘弹出表情区同步收起, 输入框零位移), 不跑独立收起动画防抖动。
    val willShowIme = input.isFocused
    android.util.Log.i("NyralIme", "hideDrawer willShow=$willShowIme open=$emojiOpen")
    emojiDrawer?.let { d ->
        d.animate().cancel()
        if (willShowIme) {
            // 抑制窗: 弹键盘请求发出后 500ms 内, dispatchTouchEvent 的"点空白收键盘"分支不得反杀
            // (长按进附件: 抽屉下滑中抬手, 落点可能已判为抽屉外, 643 分支 post 的 hideSoftInput+clearFocus 会把刚弹的键盘收掉 = 概率性不弹根因)
            suppressHideImeUntil = android.os.SystemClock.uptimeMillis() + 500
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            val ok = imm?.showSoftInput(input, 0) == true
            android.util.Log.i("NyralIme", "showIme ok=$ok open=$emojiOpen")
            if (!ok) {
                startEmojiDropAnim(180)  // 键盘未弹起兜底: 直接收起表情区恢复 chatArea 撑满, 防止输入框悬空
            }
        } else {
            // 无键盘收起(点空白/再点+): 直接收起表情区
            startEmojiDropAnim(180)
        }
    }
    // 浏览器控制条恢复显隐以浏览器是否打开为准(勿无条件显示, 否则普通聊天收抽屉会冒出"欢迎回来"条)
    setBrowserBarVisible(browserOpen())
}

/** 抽屉高度收起动画: targetH→0, 抽屉收回让位(大抽屉缩回输入框+表情区, 输入框随 chatArea 回落) */
internal fun MainActivity.startEmojiDropAnim(durationMs: Long) {
    val d = emojiDrawer ?: return
    emojiDrawerHAnim?.cancel()
    val startH = d.height
    if (startH <= 0) return
    emojiDrawerHAnim = android.animation.ValueAnimator.ofInt(startH, 0).apply {
        duration = durationMs
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { a ->
            val v = a.animatedValue as Int
            setEmojiDrawerHeight(v)
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                val dd = emojiDrawer ?: return
                dd.translationY = 0f  // 收完复位补偿, 防残留偏移影响下次展开
                dd.visibility = View.GONE
                emojiDrawerHAnim = null
                // fix(09-22): 表情区收起后聊天区视口恢复变高, 若展开期间滚过底,
                // 恢复后视口底部会多出空白, 末条贴底对齐(用户翻历史时末条不可见则不动)
                alignChatToViewport()
            }
        })
        start()
    }
}

/** 键盘 insets 动画开始时同步启动抽屉收起联动(onPrepare 调用): 与键盘动画同时长同时启动,
 * 抽屉高度递减与键盘升起同帧, 降到 0 即收完(键盘接管 bodyWrap 压缩, 输入框不掉底) */
internal fun MainActivity.startEmojiDropSync(durationMs: Long) {
    android.util.Log.i("NyralIme", "dropSync dur=$durationMs pend=$emojiDropPending open=$emojiOpen")
    // 键盘弹出必须无条件收抽屉(含直接点输入框弹键盘场景):
    // 抽屉保持展开会在 bodyWrap 压缩后占位, 把输入框顶到键盘上方/输入框掉底
    if (!emojiDropPending && !emojiOpen) return
    emojiDropPending = false
    emojiOpen = false
    startEmojiDropAnim(durationMs)
}

/** 抽屉是否半开残留(可见且高度未到收起位): 反悔打断后键盘未重弹时, MainActivity onEnd 据此收尾。
 * fix(09-21): 判据收紧——弹出动画运行中或已展开到位均属正常弹出, 不算残留;
 * 否则键盘收回 onEnd 会把"键盘→抽屉切换"中正在弹出的抽屉误判为半开残留并 finishEmojiDrawerDrop 掐掉,
 * 表现为"点表情按钮概率性直接收回输入框(键盘收了抽屉没弹出来)"。 */
internal fun MainActivity.isEmojiDrawerHalfOpen(): Boolean {
    val d = emojiDrawer ?: return false
    if (d.visibility != View.VISIBLE) return false
    if (d.height <= dp(1)) return false
    // 弹出动画进行中: 属正常弹出, 不是残留
    if (emojiDrawerHAnim?.isRunning == true) return false
    // 已展开到位(高度=展开位): 正常展开态, 不是残留
    if (d.height >= emojiDrawerTargetH - dp(1)) return false
    return true
}

/** 抽屉收起中途被键盘收回打断时的兜底: 直接收完抽屉(供 MainActivity insets 回调调用) */
internal fun MainActivity.finishEmojiDrawerDrop() {
    android.util.Log.i("NyralIme", "finishDrop pend=$emojiDropPending")
    emojiDropPending = false
    emojiPopPending = false
    emojiDrawerHAnim?.cancel(); emojiDrawerHAnim = null
    emojiDrawer?.let { dd ->
        dd.animate().cancel()
        dd.translationY = 0f  // 复位键盘补偿
        setEmojiDrawerHeight(0)
        dd.visibility = View.GONE
    }
}

/** 键盘收回动画开始时同步启动抽屉弹出(onPrepare 调用): 时长=键盘动画, 进度同步 ->
 * 键盘收到一半抽屉弹出一半, 中间交接; 抽屉比键盘高/键盘高度自定义均按各自面板进度同步, 不按像素对齐 */
internal fun MainActivity.startEmojiPopSync(durationMs: Long) {
    android.util.Log.i("NyralIme", "popSync dur=$durationMs pend=$emojiPopPending")
    if (!emojiPopPending) return
    emojiPopPending = false
    startEmojiPopAnim(durationMs)
}

/** 按当前 emojiTab 统一恢复内容可见性与 tab 高亮: switchEmojiTab 与抽屉重建状态恢复共用 */
private fun MainActivity.applyEmojiTabState() {
    contentEmoji?.visibility = if (emojiTab == 1) View.VISIBLE else View.GONE
    contentUpload?.visibility = if (emojiTab == 0) View.VISIBLE else View.GONE
    contentLib?.visibility = if (emojiTab == 2) View.VISIBLE else View.GONE
    // 副栏(最近常用)只在 emoji tab 显示; 上传/表情库 tab 隐藏
    recentScroll?.visibility = if (emojiTab == 1) View.VISIBLE else View.GONE
    recentDivider?.visibility = if (emojiTab == 1) View.VISIBLE else View.GONE
    refreshTabColor()
}

private fun MainActivity.switchEmojiTab(idx: Int) {
    if (emojiTab == idx) return
    emojiTab = idx
    applyEmojiTabState()
}

private fun MainActivity.refreshTabColor() {
    tabUpload?.setTextColor(if (emojiTab == 0) Ui.PRIMARY else Ui.SUB)
    tabEmoji?.setTextColor(if (emojiTab == 1) Ui.PRIMARY else Ui.SUB)
    tabLib?.setTextColor(if (emojiTab == 2) Ui.PRIMARY else Ui.SUB)
}

private fun MainActivity.refreshRecentBar() {
    val bar = recentBar ?: return
    bar.removeAllViews()
    val recents = recentEmojis().take(RECENT_MAX)
    if (recents.isEmpty()) {
        val tip = TextView(this).apply {
            text = "最近常用"
            textSize = 12f
            setTextColor(Ui.SUB)
            gravity = Gravity.CENTER
        }
        bar.addView(tip, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.MATCH_PARENT))
    }
    for (e in recents) {
        val tv = TextView(this).apply {
            text = e
            textSize = 22f
            gravity = Gravity.CENTER
            setOnClickListener { sendEmoji(e) }
        }
        bar.addView(tv, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

/** emoji 网格: 行高内容自适应(被 ScrollView 包裹, 小抽屉高度不足时整体内部滚动, 不撑爆容器) */
private fun MainActivity.buildEmojiGrid(): LinearLayout {
    val grid = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    EMOJIS.chunked(6).forEach { row ->
        val rowLay = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.forEach { e ->
            val tv = TextView(this).apply {
                text = e
                textSize = 22f
                gravity = Gravity.CENTER
                setOnClickListener { sendEmoji(e) }
            }
            rowLay.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        grid.addView(rowLay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    return grid
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
            setColorFilter(Color.WHITE)
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
        Triple(R.drawable.ic_upload_camera, "拍照", { Toast.makeText(this, "原型占位: 拍照待接入", Toast.LENGTH_SHORT).show() }),
        Triple(R.drawable.ic_upload_image, "图片", { pickImage(); hideEmojiDrawer() }),
        Triple(R.drawable.ic_upload_video, "视频", { pickVideo(); hideEmojiDrawer() }),
        Triple(R.drawable.ic_upload_file, "文件", { pickFile(); hideEmojiDrawer() })
    )
    cells.chunked(2).forEachIndexed { rowIdx, row ->
        val rowLay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.forEach { (icon, label, onClick) ->
            // fix(09-22): holder 高度改 WRAP_CONTENT(内容高), 行高同步 WRAP_CONTENT:
            // 抽屉高度动画(键盘弹出收起/展开)期间不再被 LinearLayout 权重压扁卡片
            rowLay.addView(item(icon, label, onClick), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
        // fix(09-22): 原 Space 权重吸收剩余空间会造成按钮随视口高度动态重排
        // ("视频/文件"贴视口底跟随键盘上移下拉), 外层 ScrollView 已改 isFillViewport=false,
        // grid 高度即内容高(两行紧凑贴顶), 视口高度变化只裁剪不重排, 故移除 Space
        grid.addView(rowLay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    return grid
}

/** 构建抽屉并返回, 由 MainActivity 挂到 bodyWrap 底部(占位式, 高度动画 0↔targetH 顶起输入框) */
internal fun MainActivity.buildEmojiDrawer(): LinearLayout {
    val drawer = LinearLayout(this).apply {
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
    emojiDrawer = drawer
    emojiDrawerFixedH = dp(310)  // 固定结构高参考(内容区不足时内部滚动, 不再作为展开目标)

    // 主顶栏
    val topBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(2), dp(6), dp(2))
    }
    fun tab(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setTextColor(Ui.SUB)
        isAllCaps = false
        Ui.press(this)
    }
    tabUpload = tab("上传").also { tv ->
        tv.setOnClickListener { switchEmojiTab(0) }
        topBar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    tabEmoji = tab("emoji").also { tv ->
        tv.setOnClickListener { switchEmojiTab(1) }
        topBar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    tabLib = tab("表情库").also { tv ->
        tv.setOnClickListener { switchEmojiTab(2) }
        topBar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    drawer.addView(topBar)
    refreshTabColor()

    // 副栏: 最近常用 8 个(LRU)
    recentBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(2), dp(4), dp(2))
    }
    recentScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(recentBar)
    }
    drawer.addView(recentScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
    // 副栏(最近常用)与内容区分隔线: 跟随副栏在非 emoji tab 隐藏
    recentDivider = View(this).apply {
        setBackgroundColor(Ui.DIVIDER)
    }
    drawer.addView(recentDivider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
    refreshRecentBar()

    // 内容区: 三页(全部撑满内容区, 高度不足时 emoji 页内部滚动)
    contentWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    // emoji 网格包 ScrollView: 小抽屉高度不足(30%兜底档/键盘档)时网格超出部分内部滚动, 不撑爆容器
    contentEmoji = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = false
        addView(buildEmojiGrid(), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    // fix(09-22): 上传/表情库页同样包 ScrollView(isFillViewport=false):
    // 抽屉高度不足(键盘弹出收起动画/30% 兜底档)时内容内部滚动保持原尺寸,
    // 不被 LinearLayout 按比例压缩变形(原裸 LinearLayout 权重网格会被"折叠挤压",
    // 与 emoji 页 ScrollView 行为一致); fillViewport=true 会让内容在视口内动态重排
    // (空态文字居中/按钮贴底)造成"内容跟随键盘上移下拉", 故禁用
    contentUpload = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = false
        addView(buildUploadGrid())
    }
    // fix(09-22): contentLib 整体改为 ScrollView(isFillViewport=false), libTop 与网格都放
    // ScrollView 内容区(UNSPECIFIED 测量, 只压视口不压内容): 抽屉高度动画压缩 contentLib 时,
    // libTop 标题行/网格行保持原尺寸原位, 不再被 LinearLayout AT_MOST 压扁
    // ("编辑/添加"按钮跟随键盘跳动问题根因); 原结构 libTop 是 contentLib 直接子项,
    // 非 weight 子项在父高不足时被压缩, ScrollView 内容不受影响
    val libTop = LinearLayout(this@buildEmojiDrawer).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(2), dp(6), dp(2))
    }
    val libHint = TextView(this@buildEmojiDrawer).apply {
        text = "表情库"
        textSize = 13f
        setTextColor(Ui.SUB)
    }
    val libAdd = TextView(this@buildEmojiDrawer).apply {
        text = "＋ 添加"
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Ui.PRIMARY)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        Ui.press(this)
        setOnClickListener { pickEmojiLibImage() }
    }
    // 编辑模式入口: 进入后多选删除; 再点变"完成"退出
    val libEdit = TextView(this@buildEmojiDrawer).apply {
        text = "编辑"
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Ui.PRIMARY)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        Ui.press(this)
        setOnClickListener { toggleLibEdit() }
    }
    // 编辑模式删除按钮(默认隐藏): 文案随勾选数变化
    val libDel = TextView(this@buildEmojiDrawer).apply {
        text = "删除(0)"
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Ui.DANGER)
            cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(10), dp(4), dp(10), dp(4))
        visibility = View.GONE
        Ui.press(this)
        setOnClickListener { deleteLibSelected() }
    }
    libEditBtn = libEdit
    libDelBtn = libDel
    libTop.addView(libHint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    libTop.addView(libDel)
    libTop.addView(libEdit)
    libTop.addView(libAdd)
    contentLibGrid = LinearLayout(this@buildEmojiDrawer).apply { orientation = LinearLayout.VERTICAL }
    contentLib = ScrollView(this@buildEmojiDrawer).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = false
        addView(LinearLayout(this@buildEmojiDrawer).apply {
            orientation = LinearLayout.VERTICAL
            addView(libTop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(contentLibGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }
    refreshEmojiLib()
    contentWrap!!.addView(contentEmoji!!, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    contentWrap!!.addView(contentUpload!!, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    contentWrap!!.addView(contentLib!!, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    drawer.addView(contentWrap!!, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))  // 权重撑满: 内容区随小抽屉高度变化拉伸(不足时 emoji 页内部滚动)
    // Activity 重建后按残留 emojiTab 统一恢复内容可见性与高亮: 防"界面 emoji 高亮上传"错位
    // (内容构建默认 emoji 可见, 若仅按 emojiTab 刷高亮, 上次停在上传/表情库时重建即错位)
    applyEmojiTabState()

    drawer.visibility = View.GONE
    drawer.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)  // 初始高度 0; 挂载时 MainActivity 用 LinearLayout.LayoutParams(MATCH_PARENT,0) 覆盖
    return drawer
}

// ==================== 表情库(原型) ====================

/** 表情库索引: emoji_drawer SharedPreferences 的 "lib_items" = JSONArray[{name, file}] */
private fun MainActivity.emojiLibItems(): MutableList<JSONObject> {
    val raw = emojiPrefs().getString("lib_items", "[]") ?: "[]"
    return try {
        val a = JSONArray(raw)
        (0 until a.length()).mapTo(mutableListOf()) { a.getJSONObject(it) }
    } catch (e: Exception) { mutableListOf() }
}

private fun MainActivity.saveEmojiLibItems(items: List<JSONObject>) {
    emojiPrefs().edit().putString("lib_items", JSONArray(items).toString()).apply()
}

/** 渲染表情库网格(3列): 缩略图+名字, 点击=发送, 长按=删除 */
/** 表情库动图宽高比: 优先首帧缩略图缓存(保留原始比例, 零解码开销), 兜底媒体元数据; 取不到按 1:1 */
private fun libVideoAspect(f: File): Float {
    peekVideoThumb(f)?.let { return if (it.height > 0) it.width.toFloat() / it.height.toFloat() else 1f }
    return try {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(f.absolutePath)
            val w = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
            val h = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
            if (w > 0f && h > 0f) w / h else 1f
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    } catch (e: Exception) { 1f }
}

private fun MainActivity.refreshEmojiLib() {
    val grid = contentLibGrid ?: return
    // 重建前统一释放动图播放器(removeAllViews 不自动 stopPlayback, 直接丢会泄漏 MediaPlayer)
    sLibVideoViews.forEach { runCatching { it.stopPlayback() } }
    sLibVideoViews.clear()
    grid.removeAllViews()
    val items = emojiLibItems()
    if (items.isEmpty()) {
        grid.addView(TextView(this).apply {
            text = "表情库为空\n点右上角 ＋ 添加图片"
            textSize = 13f
            setTextColor(Ui.SUB)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(24))
            // fix(09-22): 空态文字改 WRAP_CONTENT 贴 grid 顶部(原 MATCH_PARENT 会被外层
            // ScrollView 拉满视口并随视口高度居中重排, 造成"提示跟随键盘上移下拉")
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return
    }
    items.chunked(3).forEach { rowItems ->
        val rowLay = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowItems.forEach { obj ->
            val name = obj.optString("name")
            val file = File(filesDir, obj.optString("file"))
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            // 动图(已转无声循环MP4落库): 网格内 VideoView 静音循环播放无控件(对齐微信大动图); 静态图维持缩略图
            val rel = obj.optString("file")
            val isMp4 = file.name.lowercase().endsWith(".mp4") && file.exists()
            // 编辑模式选中态: 主色描边高亮
            val libBg = GradientDrawable().apply {
                setColor(Ui.INPUT_BG)
                cornerRadius = dp(10).toFloat()
                if (libEditMode && libSelected.contains(rel)) setStroke(dp(2), Ui.PRIMARY)
            }
            val iv: View = if (isMp4) {
                // 动图单元格: 双层 FrameLayout —— 底层 VideoView 循环播放 + 上层封面(首帧缩略图, 未命中后台取帧自动刷新);
                // SurfaceView 会遮住同层下级 View, 故封面必须叠在上层; 就绪后揭掉封面露出视频, 失败/取帧未中就保留封面, 不再出现无画面的"黑块"
                FrameLayout(this).apply {
                    background = libBg
                    val cover = ImageView(this@refreshEmojiLib).apply {
                        // 与揭盖后的 VideoView 一致: 完整比例居中(方形框内竖屏/横屏等比显示, 不再居中裁剪)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        val bmp = peekVideoThumb(file)
                        if (bmp != null) {
                            setImageBitmap(bmp)
                        } else {
                            setBackgroundColor(0xFF101318.toInt())
                            val fname = file.name
                            registerThumbRefresh(fname) {
                                val nb = peekVideoThumb(file)
                                if (nb != null && isAttachedToWindow) {
                                    setImageBitmap(nb)
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                }
                            }
                            decodeVideoThumbnailBg(file, resources.displayMetrics.density, this@refreshEmojiLib)
                        }
                    }
                    val vv = VideoView(context).apply {
                        setVideoURI(Uri.fromFile(file))
                        setOnPreparedListener { mp ->
                            try { mp.isLooping = true; mp.setVolume(0f, 0f) } catch (_: Exception) {}
                            mp.start()
                            // 已开始渲染: 揭掉封面露出循环视频(兜底延迟, 防解码慢时提前露出黑块)
                            cover.postDelayed({ if (cover.isAttachedToWindow) cover.visibility = View.GONE }, 800)
                        }
                        setOnErrorListener { _, _, _ -> true }
                        sLibVideoViews.add(this)
                    }
                    // 显式按宽高比定 VideoView 尺寸并 gravity=CENTER: VideoView 虽在 EXACTLY 下会按比例
                    // 收缩自身(onMeasure), 但 prepared 前是满格、且不同 ROM 表现不一, 显式尺寸+居中双保险,
                    // 根治竖屏动图靠左/横屏动图靠上(09-19 用户反馈"表情库里还是在左边")
                    val cellSide = dp(80)
                    val libAspect = libVideoAspect(file)
                    val vw = if (libAspect >= 1f) cellSide else (cellSide * libAspect).toInt().coerceAtLeast(1)
                    val vh = if (libAspect >= 1f) (cellSide / libAspect).toInt().coerceAtLeast(1) else cellSide
                    addView(vv, FrameLayout.LayoutParams(vw, vh, Gravity.CENTER))
                    addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            } else {
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = libBg
                    try {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) {
                            val s = minOf(bmp.width, bmp.height)
                            val crop = Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s)
                            val thumb = Bitmap.createScaledBitmap(crop, dp(72), dp(72), true)
                            if (thumb != crop) crop.recycle()
                            bmp.recycle()
                            setImageBitmap(thumb)
                        }
                    } catch (e: Exception) { Log.e("Nyral", "表情缩略图失败", e) }
                }
            }
            iv.setOnClickListener {
                if (!file.exists()) return@setOnClickListener
                if (libEditMode) {
                    // 编辑模式: 点按切换勾选, 不发送
                    if (!libSelected.add(rel)) libSelected.remove(rel)
                    updateLibEditBar()
                    refreshEmojiLib()
                    return@setOnClickListener
                }
                // 单击直发: 仅输入框/预览条为空时(避免误清正在输入的文字或已挂预览的附件); 否则保护性转进附件
                if (input.text.isEmpty() && pendingAttachments.isEmpty()) {
                    sendEmojiLibItemNowFile(file, name)   // 直发: 抽屉不收起, 可连发
                } else {
                    sendEmojiLibItemFile(file, name)      // 配文场景: 进附件预览条
                    hideEmojiDrawer()
                }
            }
            iv.setOnLongClickListener {
                // 编辑模式禁长按
                if (libEditMode) return@setOnLongClickListener true
                // 长按进附件: 配文发送; 收抽屉后应弹键盘(同点+收抽屉交互闭环)
                if (file.exists()) {
                    sendEmojiLibItemFile(file, name)
                    input.requestFocus()   // 保证 willShowIme 分支: 收抽屉后弹键盘配文
                    hideEmojiDrawer()
                }
                true
            }
            val tv = TextView(this).apply {
                text = name
                textSize = 11f
                setTextColor(Ui.SUB)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
            cell.addView(iv, LinearLayout.LayoutParams(dp(80), dp(80)))
            cell.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            rowLay.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        while (rowLay.childCount < 3) {
            rowLay.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        grid.addView(rowLay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

/** 选择表情库图片(多选), 选完走 handleEmojiLibPick 存库而非聊天发送 */
internal fun MainActivity.pickEmojiLibImage() {
    startPick(Intent.ACTION_GET_CONTENT, "image/*", REQ_EMOJI_PICK, "选择表情图片")
}

/** 系统选择器回调分流: 图片压缩落库到 filesDir/emoji_lib/, 随后逐个命名 */
internal fun MainActivity.handleEmojiLibPick(uris: List<Uri>) {
    executor.execute {
        val dir = File(filesDir, "emoji_lib").apply { mkdirs() }
        val saved = mutableListOf<File>()
        for ((i, uri) in uris.withIndex()) {
            try {
                var f: File? = null
                // 动图(GIF 动画)转无声循环 MP4 落库: 复用普通发送链路 GifToMp4(零依赖硬编), 对齐微信大动图策略;
                // 转换失败/静态图回退 compressImage 存 JPEG, 绝不阻断入库
                val raw = MediaFileUtils.readAll(contentResolver, uri)
                if (GifToMp4.isAnimated(raw)) {
                    val tmp = File(cacheDir, "emoji_anim_${System.currentTimeMillis()}_$i.mp4")
                    try {
                        val frames = GifToMp4.convert(raw, tmp, GifToMp4.MAX_DURATION_MS)
                        if (frames > 0 && tmp.length() in 1..(37 * 1024 * 1024).toLong()) {
                            f = File(dir, "lib_${System.currentTimeMillis()}_$i.mp4")
                            tmp.renameTo(f)
                        }
                    } catch (e2: Exception) { Log.e("Nyral", "动图转MP4失败", e2) }
                    try { if (tmp.exists()) tmp.delete() } catch (_: Exception) {}
                }
                if (f == null) {
                    val bytes = MediaFileUtils.compressImage(contentResolver, uri, 512)
                    if (bytes.isNotEmpty()) {
                        f = File(dir, "lib_${System.currentTimeMillis()}_$i.jpg")
                        f.writeBytes(bytes)
                    }
                }
                if (f != null) saved.add(f)
            } catch (e: Exception) { Log.e("Nyral", "表情落库失败", e) }
        }
        uiScope.launch {
            if (saved.isEmpty()) {
                Toast.makeText(this@handleEmojiLibPick, "没有可保存的图片", Toast.LENGTH_SHORT).show()
                return@launch
            }
            showEmojiNameDialog(saved, emojiLibItems(), 0)
        }
    }
}

/** 逐个强制命名: 名字非空才保存; 跳过=放弃该项并删文件 */
private fun MainActivity.showEmojiNameDialog(files: List<File>, items: MutableList<JSONObject>, idx: Int) {
    if (idx >= files.size) {
        saveEmojiLibItems(items)
        refreshEmojiLib()
        Toast.makeText(this, "已存入表情库", Toast.LENGTH_SHORT).show()
        return
    }
    val f = files[idx]
    val input = EditText(this).apply {
        hint = "输入名字(必填, AI 按此检索)"
        textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT
    }
    val dlg = AlertDialog.Builder(this)
        .setTitle("命名表情 (${idx + 1}/${files.size})")
        .setView(input)
        .setPositiveButton("保存", null)
        .setNegativeButton("跳过", null)
        .create()
    dlg.setOnShowListener {
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "名字不能为空", Toast.LENGTH_SHORT).show()
            } else {
                items.add(JSONObject().put("name", name).put("file", "emoji_lib/" + f.name))
                dlg.dismiss()
                showEmojiNameDialog(files, items, idx + 1)
            }
        }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            f.delete()
            dlg.dismiss()
            showEmojiNameDialog(files, items, idx + 1)
        }
    }
    dlg.show()
}

/** 长按删除表情库项 */
private fun MainActivity.confirmDeleteEmojiLibItem(obj: JSONObject) {
    AlertDialog.Builder(this)
        .setTitle("删除表情")
        .setMessage("确定删除「${obj.optString("name")}」?")
        .setPositiveButton("删除") { _, _ ->
            try { File(filesDir, obj.optString("file")).delete() } catch (e: Exception) {}
            val items = emojiLibItems().filterNot { it.optString("file") == obj.optString("file") }
            saveEmojiLibItems(items)
            refreshEmojiLib()
        }
        .setNegativeButton("取消", null)
        .show()
}

/** 切换表情库编辑模式: 进入=多选删除, 退出=清空勾选恢复发送 */
private fun MainActivity.toggleLibEdit() {
    libEditMode = !libEditMode
    if (!libEditMode) libSelected.clear()
    updateLibEditBar()
    refreshEmojiLib()
}

/** 编辑模式顶部按钮态: 编辑<->完成, 删除按钮显隐与勾选数 */
private fun MainActivity.updateLibEditBar() {
    libEditBtn?.text = if (libEditMode) "完成" else "编辑"
    libDelBtn?.visibility = if (libEditMode) View.VISIBLE else View.GONE
    libDelBtn?.text = "删除(${libSelected.size})"
}

/** 多选删除: 删文件 + 从 lib_items 移除, 退出编辑模式并刷新 */
private fun MainActivity.deleteLibSelected() {
    if (libSelected.isEmpty()) {
        Toast.makeText(this, "先勾选要删除的表情", Toast.LENGTH_SHORT).show()
        return
    }
    var deleted = 0
    for (rel in libSelected) {
        try { if (File(filesDir, rel).delete()) deleted++ } catch (e: Exception) {}
    }
    val items = emojiLibItems().filterNot { libSelected.contains(it.optString("file")) }
    saveEmojiLibItems(items)
    libSelected.clear()
    libEditMode = false
    updateLibEditBar()
    refreshEmojiLib()
    Toast.makeText(this, "已删除 $deleted 个表情", Toast.LENGTH_SHORT).show()
}
