# -*- coding: utf-8 -*-
import io, sys

def apply_patch(path, patches):
    with io.open(path, 'r', encoding='utf-8') as f:
        s = f.read()
    for i, (old, new) in enumerate(patches):
        cnt = s.count(old)
        if cnt != 1:
            print(f"[FAIL] {path} patch#{i}: count={cnt}")
            sys.exit(1)
        s = s.replace(old, new)
        print(f"[OK] {path} patch#{i}")
    with io.open(path, 'w', encoding='utf-8') as f:
        f.write(s)

# ---------- EmojiDrawer.kt ----------
emoji_patches = [
# P1: 变量区
("""/** 表情抽屉顶起高度(px), 0=收起; MainActivity insets 回调据此把 bodyWrap 压缩到抽屉顶(同键盘效果) */
internal var emojiLiftH = 0
/** 抽屉切键盘时输入框下落过渡: 与抽屉收回动画(180ms+AccelerateDecelerate)同步的进度源(哑动画, 只读进度不落布局) */
internal var emojiDropAnimator: android.animation.ValueAnimator? = null""",
"""/** 表情抽屉顶起高度(px), 0=收起; MainActivity insets 回调据此把 bodyWrap 压缩到抽屉顶(同键盘效果) */
internal var emojiLiftH = 0
/** 键盘最后弹起高度(px): 自定义高度键盘时抽屉展开占位跟随(抽屉高=max(固有高, 键盘高)) */
internal var lastKnownImeH = 0
/** 抽屉收起联动动画: 同时驱动抽屉下滑 translationY 与占位 emojiLiftH 递减(输入栏跟随抽屉可视高一体下移, 微信式) */
internal var emojiLiftDropAnimator: android.animation.ValueAnimator? = null"""),

# P2: showEmojiDrawer 立即顶起
("""        d.post {
            if (!emojiOpen) return@post  // 展开后立即收起(如快速连点): 不再顶起
            // 顶起式: 抽屉贴底, 占高=抽屉高+导航栏高, 触发 bodyWrap 压缩(输入框上移到抽屉上方, 同键盘)
            val sbH = if (android.os.Build.VERSION.SDK_INT >= 30)
                window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0
            else 0
            emojiLiftH = d.height.coerceAtLeast(dp(100)) + sbH
            animateEmojiLift(true)
        }""",
"""        // 顶起式(微信式一体): 立即设占位(不等滑入动画结束), 输入栏与抽屉同步被顶起;
        // 占位高=max(抽屉固有高, 键盘最后弹起高)+导航栏: 自定义高度键盘时抽屉跟随键盘变高, 切回键盘时 imeH 重升到同高贴合
        val sbH = if (android.os.Build.VERSION.SDK_INT >= 30)
            window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0
        else 0
        emojiLiftH = kotlin.math.max(d.height.coerceAtLeast(dp(100)), lastKnownImeH) + sbH
        animateEmojiLift(true)"""),

# P3: hideEmojiDrawer 抽屉收起动画 -> 联动动画
("""    emojiDrawer?.let { d ->
        d.animate().cancel()
        d.animate().translationY(d.height.toFloat()).setDuration(180).withEndAction {
            d.visibility = View.GONE
        }.start()
    }""",
"""    emojiDrawer?.let { d ->
        d.animate().cancel()
        if (willShowIme) {
            // 微信式联动: 抽屉下滑与占位联动(可视高按抽屉固有可视高递减), 输入栏跟随抽屉可视高一体下移;
            // 降到键盘高后由 onProgress max(imeH, emojiLiftH) 接管 -> "降到键盘高度停住等待键盘出来贴合"(无需插值补偿);
            // 键盘高>抽屉固有高时占位只减到(键盘高-抽屉可视高), 输入栏恒定在键盘顶不来回跳(V形)
            val H0 = emojiLiftH
            val sbHv = if (android.os.Build.VERSION.SDK_INT >= 30)
                window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.bottom ?: 0
            else 0
            val drop = d.height + sbHv  // 抽屉固有可视高(占位递减量)
            emojiLiftDropAnimator?.cancel()
            emojiLiftDropAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedFraction
                    d.translationY = (d.height * f).toFloat()
                    emojiLiftH = (H0 - drop * f).coerceAtLeast(0)
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        d.visibility = View.GONE
                    }
                })
                start()
            }
        } else {
            d.animate().translationY(d.height.toFloat()).setDuration(180).withEndAction {
                d.visibility = View.GONE
            }.start()
        }
    }"""),

# P4: 删除哑动画
("""    // 交互闭环(同微信): 点 + 收抽屉时输入框仍聚焦 -> 恢复弹键盘
    if (willShowIme) {
        // 输入框下落与抽屉收回(180ms)同节奏: 启动哑动画作进度源(同插值器同时长), onProgress 读 animatedFraction 插值,
        // 首帧从 0 开始不跳变(勿用时间戳: onProgress 首帧晚到会瞬间跳到高进度导致掉得更快;
        // 勿用键盘 interpolatedFraction: Decelerate 前期快, 掉得比抽屉快)
        emojiDropAnimator?.cancel()
        emojiDropAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
        // 抑制窗: 弹键盘请求发出后 500ms 内, dispatchTouchEvent 的"点空白收键盘"分支不得反杀""",
"""    // 交互闭环(同微信): 点 + 收抽屉时输入框仍聚焦 -> 恢复弹键盘
    if (willShowIme) {
        // 输入框下落已由联动动画(emojiLiftDropAnimator)驱动占位递减, 键盘 insets onProgress 用 max(imeH, emojiLiftH) 接管
        // 抑制窗: 弹键盘请求发出后 500ms 内, dispatchTouchEvent 的"点空白收键盘"分支不得反杀"""),
]

# ---------- MainActivity.kt ----------
main_patches = [
# P5: 记录键盘弹起高度
("""                val imeH = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                lastImeH = imeH""",
"""                val imeH = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                lastImeH = imeH
                // 记录键盘弹起高度(自定义高度键盘): 抽屉展开占位跟随
                if (imeH > dp(80)) lastKnownImeH = imeH"""),

# P6: insets 键盘已收兜底
("""                    if (!emojiOpen && emojiLiftH > 0) {
                        emojiLiftH = 0
                        emojiDropAnimator?.cancel(); emojiDropAnimator = null
                    }""",
"""                    if (!emojiOpen && emojiLiftH > 0) {
                        emojiLiftH = 0
                        emojiLiftDropAnimator?.cancel(); emojiLiftDropAnimator = null
                        emojiDrawer?.let { dd ->
                            dd.animate().cancel()
                            dd.translationY = dd.height.toFloat()
                            dd.visibility = View.GONE
                        }
                    }"""),

# P7: onProgress 插值 -> max 共管
("""                        // 键盘收回且抽屉已关: 过渡占位不再需要, 立即清除, 防输入框悬在抽屉顶/中间(点空白收键盘后输入框不回去)
                        if (imeH < lastProgressImeH && !emojiOpen && emojiLiftH > 0) {
                            emojiLiftH = 0
                            emojiDropAnimator?.cancel()
                            emojiDropAnimator = null
                        }
                        val bottomOccupy = if (emojiLiftH > 0 && !emojiOpen) {
                            // 输入框下落跟抽屉收回动画(180ms+AccelerateDecelerate)同节奏: 读哑动画进度插值, 首帧从 0 不跳变
                            val f = emojiDropAnimator?.takeIf { it.isRunning }?.animatedFraction
                                ?: try { imeAnim.interpolatedFraction } catch (e: Exception) { 1f }
                            kotlin.math.max(imeH, (emojiLiftH * (1f - f) + imeH * f).toInt())
                        } else {
                            kotlin.math.max(imeH, emojiLiftH)
                        }""",
"""                        // 键盘收回且抽屉已关: 过渡占位不再需要, 立即清除并收完抽屉, 防输入框悬在抽屉顶/中间(点空白收键盘后输入框不回去)
                        if (imeH < lastProgressImeH && !emojiOpen && emojiLiftH > 0) {
                            emojiLiftH = 0
                            emojiLiftDropAnimator?.cancel(); emojiLiftDropAnimator = null
                            emojiDrawer?.let { dd ->
                                dd.animate().cancel()
                                dd.translationY = dd.height.toFloat()
                                dd.visibility = View.GONE
                            }
                        }
                        // 微信式一体共管: 键盘升起 imeH 递增, 切键盘时 emojiLiftH 由联动动画实时递减,
                        // max 自然实现"抽屉降到键盘高后停住等键盘出来贴合"的一体感(无需插值补偿)
                        val bottomOccupy = kotlin.math.max(imeH, emojiLiftH)"""),

# P8: onEnd 变量改名
("""                        if ((curImeH <= dp(80) || !input.isFocused) && !emojiOpen) {
                            if (emojiLiftH > 0) emojiLiftH = 0
                            emojiDropAnimator?.cancel(); emojiDropAnimator = null""",
"""                        if ((curImeH <= dp(80) || !input.isFocused) && !emojiOpen) {
                            if (emojiLiftH > 0) emojiLiftH = 0
                            emojiLiftDropAnimator?.cancel(); emojiLiftDropAnimator = null"""),
]

apply_patch("EmojiDrawer.kt", emoji_patches)
apply_patch("MainActivity.kt", main_patches)
print("ALL DONE")
