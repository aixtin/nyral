# -*- coding: utf-8 -*-
import io

def patch(path, pairs):
    s = io.open(path, encoding="utf-8").read()
    for old, new in pairs:
        c = s.count(old)
        assert c == 1, "%s: count=%d for: %r" % (path, c, old[:60])
        s = s.replace(old, new)
    io.open(path, "w", encoding="utf-8").write(s)
    print("patched", path)

# 1) Ui.kt: GREEN 常量 + check 圆形化 + applyCheck helper
patch("app/src/main/java/all/Ui.kt", [
    ("""    @Volatile var SURFACE: Int = DefaultTheme.surface
""",
     """    @Volatile var SURFACE: Int = DefaultTheme.surface
    /** 选中圆点绿（勾选标记统一用绿色点） */
    val GREEN = 0xFF2E7D32.toInt()
"""),
    ("""/** 自绘勾选框：选中=主色底白✓，未选中=浅灰底 */
    fun check(a: Activity, checked: Boolean): TextView = TextView(a).apply {
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(Color.WHITE)
        text = if (checked) "✓" else ""
        background = rounded(if (checked) PRIMARY else INPUT_BG, 6, a)
        layoutParams = LinearLayout.LayoutParams(dp(a, 22), dp(a, 22))
    }""",
     """/** 自绘勾选圆点：选中=绿底白●，未选中=浅灰圆底 */
    fun check(a: Activity, checked: Boolean): TextView = TextView(a).apply {
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
    }"""),
])

# 2) MainUi.kt: 供应商行 / 子模型行 文字后绿点
patch("app/src/main/java/all/MainUi.kt", [
    ("""text = p.label + if (isCur) "  ✓" else """,
     """text = run {
                        val sb = android.text.SpannableStringBuilder(p.label)
                        if (isCur) {
                            sb.append("  ")
                            val s = sb.length
                            sb.append("●")
                            sb.setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), s, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        sb
                    }"""),
    ("""text = "· " + m + if (isCurModel) "  ✓" else """,
     """text = run {
                            val sb = android.text.SpannableStringBuilder("· " + m)
                            if (isCurModel) {
                                sb.append("  ")
                                val s = sb.length
                                sb.append("●")
                                sb.setSpan(android.text.style.ForegroundColorSpan(Ui.GREEN), s, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            sb
                        }"""),
    ("""// 仅当前生效供应商且为该供应商当前模型时高亮勾选（避免未选供应商的模型也显示✓）""",
     """// 仅当前生效供应商且为该供应商当前模型时高亮勾选（避免未选供应商的模型也显示●）"""),
])

# 3) FirstRunSetupActivity.kt
patch("app/src/main/java/all/FirstRunSetupActivity.kt", [
    ('text = if (checked) "✓" else ""', 'text = if (checked) "●" else ""'),
])

# 4) ChatBackgroundActivity.kt（渐变底色卡，白点）
patch("app/src/main/java/all/ChatBackgroundActivity.kt", [
    ('presetCells[i].text = if (checked) "✓" else ""', 'presetCells[i].text = if (checked) "●" else ""'),
])

# 5) MemModelConfigActivity.kt
patch("app/src/main/java/all/MemModelConfigActivity.kt", [
    ("""        enableBox.text = if (enabled) "✓" else ""
        enableBox.background = Ui.rounded(if (enabled) Ui.PRIMARY else Ui.INPUT_BG, 6, this)""",
     """        Ui.applyCheck(enableBox, enabled, this)"""),
    ("""                    cb.text = if (checked[i]) "✓" else ""
                    cb.background = Ui.rounded(if (checked[i]) Ui.PRIMARY else Ui.INPUT_BG, 6, this@MemModelConfigActivity)""",
     """                    Ui.applyCheck(cb, checked[i], this@MemModelConfigActivity)"""),
])

# 6) ModelEditActivity.kt
patch("app/src/main/java/all/ModelEditActivity.kt", [
    ("""                cb.text = if (checked[i]) "✓" else ""
                cb.background = Ui.rounded(if (checked[i]) Ui.PRIMARY else Ui.INPUT_BG, 6, this@ModelEditActivity)""",
     """                Ui.applyCheck(cb, checked[i], this@ModelEditActivity)"""),
])

# 7) MemoryListActivity.kt
patch("app/src/main/java/all/MemoryListActivity.kt", [
    ("""                it.text = if (sel) "✓" else ""
                it.background = Ui.rounded(if (sel) Ui.PRIMARY else Ui.INPUT_BG, 6, this@MemoryListActivity)""",
     """                Ui.applyCheck(it, sel, this@MemoryListActivity)"""),
])
print("ALL DONE")
