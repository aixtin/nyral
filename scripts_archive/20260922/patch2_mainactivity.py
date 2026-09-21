# -*- coding: utf-8 -*-
"""修正 emojiDrawer 挂载顺序: 移到 root.addView(main) 之后, 否则被聊天层盖住"""
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

# 1. 删除旧挂载块(在 main 构建内 addView 到 root, 会被 main 盖住)
old_block = """        // 表情抽屉: 悬浮图层挂 root, bottom 对齐输入框顶(键盘弹出时被键盘盖住)
        val emojiDrawerRoot = buildEmojiDrawer()
        root.addView(emojiDrawerRoot, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dp(58)
        })
        inputBar.post {
            val lp = emojiDrawerRoot.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = inputBar.height + dp(6)
            emojiDrawerRoot.layoutParams = lp
        }
"""
rep(old_block, "")

# 2. root.addView(main) 之后插入新挂载(最上层)
rep("""        root.addView(main)""",
    """        root.addView(main)
        // 表情抽屉: 悬浮图层挂 root 最上层, bottom 对齐输入框顶(键盘弹出时被键盘盖住)
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
print("PATCH2 OK")
