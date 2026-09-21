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

emoji_patches = [
# 类型修复
("""                    emojiLiftH = (H0 - drop * f).coerceAtLeast(0)""",
"""                    emojiLiftH = (H0 - drop * f).toInt().coerceAtLeast(0)"""),
# 新增兜底函数(放在 hideEmojiDrawer 后面, 找 switchEmojiTab 前)
("""private fun MainActivity.switchEmojiTab(idx: Int) {""",
"""/** 抽屉收起中途被键盘收回打断时的兜底: 直接收完抽屉并清占位(供 MainActivity insets 回调调用) */
internal fun MainActivity.finishEmojiDrawerDrop() {
    emojiLiftH = 0
    emojiLiftDropAnimator?.cancel(); emojiLiftDropAnimator = null
    emojiDrawer?.let { dd ->
        dd.animate().cancel()
        dd.translationY = dd.height.toFloat()
        dd.visibility = View.GONE
    }
}

private fun MainActivity.switchEmojiTab(idx: Int) {"""),
]

main_patches = [
# P6 insets 兜底: 收抽屉改为调用函数
("""                    if (!emojiOpen && emojiLiftH > 0) {
                        emojiLiftH = 0
                        emojiLiftDropAnimator?.cancel(); emojiLiftDropAnimator = null
                        emojiDrawer?.let { dd ->
                            dd.animate().cancel()
                            dd.translationY = dd.height.toFloat()
                            dd.visibility = View.GONE
                        }
                    }""",
"""                    if (!emojiOpen && emojiLiftH > 0) {
                        finishEmojiDrawerDrop()
                    }"""),
# P7 onProgress: 收抽屉改为调用函数
("""                        if (imeH < lastProgressImeH && !emojiOpen && emojiLiftH > 0) {
                            emojiLiftH = 0
                            emojiLiftDropAnimator?.cancel(); emojiLiftDropAnimator = null
                            emojiDrawer?.let { dd ->
                                dd.animate().cancel()
                                dd.translationY = dd.height.toFloat()
                                dd.visibility = View.GONE
                            }
                        }""",
"""                        if (imeH < lastProgressImeH && !emojiOpen && emojiLiftH > 0) {
                            finishEmojiDrawerDrop()
                        }"""),
]

apply_patch("EmojiDrawer.kt", emoji_patches)
apply_patch("MainActivity.kt", main_patches)
print("ALL DONE")
