package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.provider.MediaStore
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        val dlBtn = android.widget.ImageView(this).apply {
            setImageDrawable(Ui.lucideDownload(this@LogActivity, Ui.PRIMARY, 16))
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setOnClickListener { exportLogs() }
        }
        root.addView(Ui.titleBar(this, getString(R.string.log_01), right = { bar ->
            bar.addView(dlBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        }))

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
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(6), dp(24), dp(4))
        }
        val logMaxH = (resources.displayMetrics.heightPixels * 0.8f).toInt()
        scroll = Ui.MaxHeightScrollView(this, logMaxH).apply {
            background = Ui.rounded(0xFF101418.toInt(), 12, this@LogActivity)
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
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(6), dp(16), dp(12))
        }
        // 刷新: 浅蓝气泡 + refresh-cw 图标, 紧凑宽度
        bottomBar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = Ui.rounded(Ui.PRIMARY_LIGHT, 12, this@LogActivity)
            isClickable = true
            setOnClickListener { refresh(true) }
            Ui.press(this)
            addView(android.widget.ImageView(this@LogActivity).apply {
                setImageDrawable(Ui.lucideRefresh(this@LogActivity, Ui.PRIMARY, 16))
            })
            addView(TextView(this@LogActivity).apply {
                text = getString(R.string.log_04)
                textSize = 13f
                setTextColor(Ui.PRIMARY)
                setPadding(dp(6), 0, 0, 0)
            })
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(0, 0, dp(24), 0)
        })
        // 清空: 主色气泡 + trash 图标, 紧凑宽度
        bottomBar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = Ui.rounded(Ui.PRIMARY, 12, this@LogActivity)
            isClickable = true
            setOnClickListener { confirmClear() }
            Ui.press(this)
            addView(android.widget.ImageView(this@LogActivity).apply {
                setImageDrawable(Ui.lucideTrash(this@LogActivity, Color.WHITE, 16))
            })
            addView(TextView(this@LogActivity).apply {
                text = getString(R.string.log_05)
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(6), 0, 0, 0)
            })
        }, LinearLayout.LayoutParams(0, dp(40), 1f))
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
        tv.background = Ui.rounded(if (selected) Ui.PRIMARY else Ui.SURFACE, 12, this)
        tv.setTextColor(if (selected) Color.WHITE else Ui.TEXT)
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

    private fun exportLogs() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val n1 = exportOne(LogStore.MAIN, "Nyral_主日志_" + stamp + ".txt", WorkDir.SUB_DIR_LOG_MAIN)
        val n2 = exportOne(LogStore.MEM, "Nyral_辅助AI日志_" + stamp + ".txt", WorkDir.SUB_DIR_LOG_MEM)
        val msg = if (n1 != null && n2 != null)
            getString(R.string.log_13) + WorkDir.displaySubPath(WorkDir.SUB_DIR_LOG_MAIN) + n1 + "、" + n2
                  else getString(R.string.log_14)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** 导出单个日志到工作目录子目录(日志/主日志|日志/辅日志), 返回实际文件名(可能带序号), 失败返回 null */
    private fun exportOne(tag: String, base: String, subDir: String): String? {
        val sb = StringBuilder()
        LogStore.history(tag).forEach { sb.append(it.line()).append('\n') }
        val content = sb.toString()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val name = uniqueName29(base, subDir)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, WorkDir.relPath(subDir))
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: return null
                name
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Nyral_work/" + subDir)
                if (!dir.exists()) dir.mkdirs()
                val name = uniqueNameOld(dir, base)
                File(dir, name).writeText(content, Charsets.UTF_8)
                name
            }
        } catch (e: Exception) {
            null
        }
    }

    /** API29+ MediaStore 查重名(限定子目录) */
    private fun uniqueName29(base: String, subDir: String): String {
        var name = base
        var n = 2
        while (exists29(name, subDir)) {
            val dot = base.lastIndexOf('.')
            name = if (dot > 0) base.substring(0, dot) + "_" + n + base.substring(dot) else base + "_" + n
            n++
        }
        return name
    }

    private fun exists29(name: String, subDir: String): Boolean {
        val rel = WorkDir.relPath(subDir)
        val c = contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "(" + MediaStore.MediaColumns.RELATIVE_PATH + " = ? OR " + MediaStore.MediaColumns.RELATIVE_PATH + " = ?) AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
            arrayOf(rel, "$rel/", name), null)
        val cnt = c?.count ?: 0
        c?.close()
        return cnt > 0
    }

    /** API<29 文件系统查重名 */
    private fun uniqueNameOld(dir: File, base: String): String {
        var name = base
        var n = 2
        while (File(dir, name).exists()) {
            val dot = base.lastIndexOf('.')
            name = if (dot > 0) base.substring(0, dot) + "_" + n + base.substring(dot) else base + "_" + n
            n++
        }
        return name
    }
}
