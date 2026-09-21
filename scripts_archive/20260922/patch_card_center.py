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

# 1) Ui.kt: 新增 roundedBorder（无填充仅描边）+ check 圆点 includeFontPadding 居中
patch("app/src/main/java/all/Ui.kt", [
    ("""    fun rounded(color: Int, radiusDp: Int, a: Activity): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(a, radiusDp).toFloat() }""",
     """    fun rounded(color: Int, radiusDp: Int, a: Activity): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(a, radiusDp).toFloat() }

    /** 圆角描边框：无填充，仅 stroke */
    fun roundedBorder(strokeColor: Int, radiusDp: Int, strokeDp: Int, a: Activity): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(a, radiusDp).toFloat()
            setStroke(dp(a, strokeDp), strokeColor)
        }"""),
    ("""    fun check(a: Activity, checked: Boolean): TextView = TextView(a).apply {
        gravity = Gravity.CENTER
        textSize = 8f
        text = "●"
        setTextColor(if (checked) GREEN else SUB)
        layoutParams = LinearLayout.LayoutParams(dp(a, 12), dp(a, 12))
    }""",
     """    fun check(a: Activity, checked: Boolean): TextView = TextView(a).apply {
        gravity = Gravity.CENTER
        textSize = 8f
        includeFontPadding = false
        text = "●"
        setTextColor(if (checked) GREEN else SUB)
        layoutParams = LinearLayout.LayoutParams(dp(a, 12), dp(a, 12))
    }"""),
])

# 2) ModelEditActivity.kt: 模型多选行 → 圆角描边框卡片（整卡可点）
patch("app/src/main/java/all/ModelEditActivity.kt", [
    ("""            val cb = Ui.check(this, checked[i])
            fun updateCb() {
                Ui.applyCheck(cb, checked[i], this@ModelEditActivity)
            }
            // 模型名行
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(dp(2), dp(8), dp(2), dp(2))
                setOnClickListener {
                    checked[i] = !checked[i]
                    updateCb()
                }
            }
            Ui.press(row)""",
     """            val cb = Ui.check(this, checked[i])
            lateinit var row: LinearLayout
            fun styleRow() {
                row.background = Ui.roundedBorder(if (checked[i]) Ui.PRIMARY else Ui.DIVIDER, 12, 1, this@ModelEditActivity)
            }
            fun updateCb() {
                Ui.applyCheck(cb, checked[i], this@ModelEditActivity)
                styleRow()
            }
            // 模型名行（独立圆角描边框卡片，整卡可点切换选中）
            row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = dp(8)
                }
                setOnClickListener {
                    checked[i] = !checked[i]
                    updateCb()
                }
            }
            styleRow()
            Ui.press(row)"""),
])
print("ALL DONE")
