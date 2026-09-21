# -*- coding: utf-8 -*-
"""给 MainActivity.kt 打表情抽屉补丁: 开关改抽屉 / 聚焦收抽屉 / 桥函数 / 挂载抽屉"""
import sys

path = "MainActivity.kt"
s = open(path, encoding="utf-8").read()

def rep(old, new, expect=1):
    global s
    n = s.count(old)
    if n != expect:
        print(f"FAIL anchor count={n} expect={expect}: {old[:80]!r}")
        sys.exit(1)
    s = s.replace(old, new)

# 1. attachBtn / attachBtn2 点击从附件弹窗改为抽屉开关
rep("setOnClickListener { showAttachSheet() }",
    "setOnClickListener { toggleEmojiDrawer() }", 2)

# 2. 输入框聚焦时收起抽屉(键盘弹出露出键盘, 与图层方案一致)
rep("imeOptions = EditorInfo.IME_ACTION_SEND",
    "imeOptions = EditorInfo.IME_ACTION_SEND\n            setOnFocusChangeListener { _, hasFocus -> if (hasFocus) hideEmojiDrawer() }")

# 3. browserBar 显隐桥函数(供 EmojiDrawer extension 调用)
rep("internal fun doSend(attachments: List<LocalEngine.Attachment>) {",
    "internal fun setBrowserBarVisible(v: Boolean) {\n        browserBar.visibility = if (v) View.VISIBLE else View.GONE\n    }\n\n    internal fun doSend(attachments: List<LocalEngine.Attachment>) {")

# 4. main.addView 之后挂载抽屉到 root(Gravity.BOTTOM, bottom 对齐输入框顶)
rep("""        main.addView(bodyWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))""",
    """        main.addView(bodyWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // 表情抽屉: 悬浮图层挂 root, bottom 对齐输入框顶(键盘弹出时被键盘盖住)
        val emojiDrawerRoot = buildEmojiDrawer()
        root.addView(emojiDrawerRoot, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dp(58)
        })
        inputBar.post {
            val lp = emojiDrawerRoot.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = inputBar.height + dp(6)
            emojiDrawerRoot.layoutParams = lp
        }""")

open(path, "w", encoding="utf-8").write(s)
print("PATCH OK")
