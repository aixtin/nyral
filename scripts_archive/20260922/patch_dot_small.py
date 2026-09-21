# -*- coding: utf-8 -*-
import io

def patch(path, pairs, all_count=None):
    s = io.open(path, encoding="utf-8").read()
    for old, new in pairs:
        c = s.count(old)
        if all_count is not None:
            assert c == all_count, "%s: count=%d want=%d for: %r" % (path, c, all_count, old[:50])
            s = s.replace(old, new)
        else:
            assert c == 1, "%s: count=%d for: %r" % (path, c, old[:50])
            s = s.replace(old, new)
    io.open(path, "w", encoding="utf-8").write(s)
    print("patched", path)

# 1) Ui.kt: 勾选圆点缩到呼吸灯尺寸（纯小圆点，无背景块）
patch("app/src/main/java/all/Ui.kt", [
    ("""    fun check(a: Activity, checked: Boolean): TextView = TextView(a).apply {
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(Color.WHITE)
        text = if (checked) "●" else ""
        background = rounded(if (checked) GREEN else INPUT_BG, 11, a)
        layoutParams = LinearLayout.LayoutParams(dp(a, 22), dp(a, 22))
    }

    /** 刷新勾选圆点样式（选中=绿底白●，未选中=浅灰圆底） */
    fun applyCheck(v: TextView, checked: Boolean, a: Activity) {
        v.text = if (checked) "●" else ""
        v.background = rounded(if (checked) GREEN else INPUT_BG, 11, a)
    }""",
     """    fun check(a: Activity, checked: Boolean): TextView = TextView(a).apply {
        gravity = Gravity.CENTER
        textSize = 8f
        text = "●"
        setTextColor(if (checked) GREEN else SUB)
        layoutParams = LinearLayout.LayoutParams(dp(a, 12), dp(a, 12))
    }

    /** 刷新勾选圆点样式（选中=绿色小实心点，未选中=浅灰小点，视觉同呼吸灯 6dp） */
    fun applyCheck(v: TextView, checked: Boolean, a: Activity) {
        v.text = "●"
        v.setTextColor(if (checked) GREEN else SUB)
    }"""),
])

# 2) MainUi.kt: 文字后绿点缩到 8sp（≈6dp，同呼吸灯）
patch("app/src/main/java/all/MainUi.kt", [
    ("""sb.setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), s, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)""",
     """sb.setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), s, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sb.setSpan(android.text.style.AbsoluteSizeSpan(8, true), s, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)"""),
], all_count=2)

# 3) FirstRunSetupActivity.kt: 引导页大点缩小
patch("app/src/main/java/all/FirstRunSetupActivity.kt", [
    ("""                text = if (checked) "●" else ""
                textSize = 18f""",
     """                text = if (checked) "●" else ""
                textSize = 8f"""),
])

# 4) ChatBackgroundActivity.kt: 预设卡大点缩小
patch("app/src/main/java/all/ChatBackgroundActivity.kt", [
    ("""            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)""",
     """            gravity = Gravity.CENTER
            textSize = 9f
            setTextColor(Color.WHITE)"""),
])
print("ALL DONE")
