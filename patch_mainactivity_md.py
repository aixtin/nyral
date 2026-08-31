# -*- coding: utf-8 -*-
import io

p = "/home/ymz/droid-agent/android-agent-app/app/src/main/java/com/example/agentapp/MainActivity.kt"
s = io.open(p, encoding="utf-8").read()

# 1) imports
old = "import kotlin.math.abs"
new = """import kotlin.math.abs
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin"""
assert s.count(old) == 1
s = s.replace(old, new)

# 2) markwon 实例字段 (lazy, 本地渲染无网络)
old = "    private val executor = Executors.newSingleThreadExecutor()"
new = """    private val executor = Executors.newSingleThreadExecutor()
    // Markdown 本地渲染 (Markwon, 开源/无网络/不接第三方服务)
    private val markwon by lazy {
        Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create())
            .build()
    }"""
assert s.count(old) == 1
s = s.replace(old, new)

# 3) 历史恢复 AI 气泡正文 -> Markdown 渲染
old = """            addView(TextView(this@MainActivity).apply {
                text = content
                textSize = 15f
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(BUBBLE_AI_TEXT)
                setPadding(0, dp(4), 0, dp(2))
            })"""
new = """            addView(TextView(this@MainActivity).apply {
                markwon.setMarkdown(this, content)
                textSize = 15f
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(BUBBLE_AI_TEXT)
                setPadding(0, dp(4), 0, dp(2))
                maxWidth = maxW
            })"""
assert s.count(old) == 1
s = s.replace(old, new)

# 4) 流式正文刷新 -> Markdown 渲染
old = "            contentView?.let { it.text = contentText.toString() }"
new = "            contentView?.let { markwon.setMarkdown(it, contentText.toString()) }"
assert s.count(old) == 1
s = s.replace(old, new)

io.open(p, "w", encoding="utf-8").write(s)
print("PATCH_OK")
