package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Timer
import java.util.TimerTask

/**
 * 运行日志页 — 双视图:
 * - 主日志: 主对话 AI 请求/响应/工具调用/错误
 * - 辅助 AI: MemoryKeeper 归档 + 主题索引生成过程
 * 自动刷新(1s), 自动滚动到底部, 支持手动刷新与清空当前视图。
 */
class LogActivity : Activity() {

    private var currentTag: String? = null   // null=全部(null 不在此页出现, 仅保留语义) / "main" / "mem"
    private lateinit var logText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var tabMain: TextView
    private lateinit var tabMem: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var timer: Timer? = null
    private var lastShownKey = ""            // 去重: 内容未变化不重绘

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogStore.init(this)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.log_01)))

        // ---- tab 行 ----
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
        tabMain = tabButton(getString(R.string.log_02), true) {
            currentTag = LogStore.MAIN
            refreshTabs()
            refresh(true)
        }
        tabMem = tabButton(getString(R.string.log_03), false) {
            currentTag = LogStore.MEM
            refreshTabs()
            refresh(true)
        }
        tabRow.addView(tabMain, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(0, 0, dp(6), 0)
        })
        tabRow.addView(tabMem, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(dp(6), 0, 0, 0)
        })
        root.addView(tabRow)
        currentTag = LogStore.MAIN
        refreshTabs()

        // ---- 日志内容区 ----
        val contentWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(4))
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF101418.toInt())
            isVerticalScrollBarEnabled = true
        }
        logText = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFD8DEE4.toInt())
            setLineSpacing(0f, 1.15f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scroll.addView(logText, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentWrap.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(contentWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 底部操作栏 ----
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(6), dp(16), dp(12))
        }
        bottomBar.addView(Ui.lightBtn(this, getString(R.string.log_04)) { refresh(true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(8), 0)
            })
        bottomBar.addView(Ui.primaryBtn(this, getString(R.string.log_05)) { confirmClear() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(8), 0, 0, 0)
            })
        root.addView(bottomBar)

        setContentView(root)

        refresh(true)
        timer = Timer("log-refresh", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    ui.post { refresh(false) }
                }
            }, 1000, 1000)
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        super.onDestroy()
    }

    private fun tabButton(text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onClick() }
            Ui.press(this)
            updateTabStyle(this, selected)
        }

    private fun updateTabStyle(tv: TextView, selected: Boolean) {
        tv.setBackgroundResource(0)
        tv.background = Ui.rounded(if (selected) 0xFF3B82F6.toInt() else Color.WHITE, 12, this)
        tv.setTextColor(if (selected) Color.WHITE else 0xFF666666.toInt())
    }

    private fun refreshTabs() {
        updateTabStyle(tabMain, currentTag == LogStore.MAIN)
        updateTabStyle(tabMem, currentTag == LogStore.MEM)
    }

    private fun refresh(force: Boolean) {
        val tag = currentTag
        val list = LogStore.history(tag)
        val sb = StringBuilder()
        list.forEach { sb.append(it.line()).append('\n') }
        if (list.isEmpty()) {
            sb.append(getString(R.string.log_06))
        }
        sb.append(getString(R.string.log_07)).append(list.size).append(getString(R.string.log_08))
        val key = sb.toString()
        if (!force && key == lastShownKey) return
        lastShownKey = key
        logText.text = key
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun confirmClear() {
        val (dlg, box) = Ui.dialog(this, getString(R.string.log_09))
        box.addView(Ui.dialogText(this, getString(R.string.log_10)))
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(Ui.dialogCancelBtn(this@LogActivity, getString(R.string.log_11)) { dlg.dismiss() })
            addView(TextView(this@LogActivity).apply {
                text = getString(R.string.log_12)
                textSize = 15f
                setTextColor(0xFFE53935.toInt())
                setPadding(dp(20), dp(10), dp(4), dp(10))
                setOnClickListener {
                    LogStore.clear(currentTag)
                    lastShownKey = ""
                    refresh(true)
                    dlg.dismiss()
                }
                Ui.press(this)
            })
        })
        dlg.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
