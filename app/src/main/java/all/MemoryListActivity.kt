package io.github.aixtin.droidagent

import io.github.aixtin.droidagent.R

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
// 保留 import（主页/其他逻辑可能复用），本页弹窗已无果冻动画
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.Dialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


/**
 * 长期记忆页 — 与主页统一视觉（灰底 + 白色圆角卡片）
 * 展示 MemoryDb 中存储的长期记忆（按时间倒序）
 * 点击条目：自绘圆角弹窗查看完整内容，支持编辑 / 删除
 * 右上角"⋯"：仿主页下拉弹窗（多选 / 删除所有）
 */
class MemoryListActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var db: MemoryDb
    private lateinit var container: LinearLayout
    private lateinit var moreBtn: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var delSelBtn: TextView
    private var popup: PopupWindow? = null
    private var popupClosing = false

    /** 多选删除模式 */
    private var multiMode = false
    private val selectedIds = mutableSetOf<Long>()

    private val embedder: MemoryEmbedder by lazy { MemoryEmbedder(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = MemoryDb(this)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)

        // ---- 自绘标题栏：左返回 + 标题 + 右上角 ⋯ ----
        root.addView(Ui.titleBar(this, getString(R.string.settings_memory), right = { bar ->
            moreBtn = TextView(this@MemoryListActivity).apply {
                text = "⋯"
                textSize = 26f
                setTextColor(Ui.TEXT)
                setPadding(dp(12), dp(0), dp(6), dp(0))
                setOnClickListener { toggleMorePopup() }
                Ui.press(this)
            }
            bar.addView(moreBtn)
        }))

        // ---- 内容区 ----
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(container)

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 底部操作条（多选模式显示）----
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        delSelBtn = Ui.dangerBtn(this, getString(R.string.memory_del_selected, 0)) { confirmDeleteSelected() }
        bottomBar.addView(delSelBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            height = dp(44)
        })
        root.addView(bottomBar)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ===================== 右上角 ⋯ 下拉弹窗（仿主页 PopupWindow 样式） =====================

    private fun toggleMorePopup() {
        if (popup?.isShowing == true) {
            dismissMorePopup()
            return
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = Ui.rounded(Color.WHITE, 14, this@MemoryListActivity)
            elevation = dp(10).toFloat()
        }
        fun item(label: String, onClick: () -> Unit) {
            col.addView(TextView(this@MemoryListActivity).apply {
                text = label
                textSize = 14f
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setTextColor(0xFF333333.toInt())
                setOnClickListener {
                    dismissMorePopup()
                    onClick()
                }
            })
        }
        item(getString(R.string.memory_multi)) { enterMultiMode() }
        item(getString(R.string.memory_delete_all)) { confirmDeleteAll() }

        col.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val popH = col.measuredHeight
        popup = PopupWindow(col, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            elevation = dp(10).toFloat()
            isTouchable = true
            isOutsideTouchable = true
            setBackgroundDrawable(GradientDrawable())
            setTouchInterceptor { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) {
                    val p = IntArray(2)
                    moreBtn.getLocationOnScreen(p)
                    val inBtn = e.rawX >= p[0] && e.rawX <= p[0] + moreBtn.width &&
                            e.rawY >= p[1] && e.rawY <= p[1] + moreBtn.height
                    if (inBtn) {
                        dismissMorePopup()
                        return@setTouchInterceptor true
                    }
                    dismissMorePopup()
                }
                false
            }
        }
        popup!!.showAsDropDown(moreBtn, 0, -(popH + moreBtn.height + dp(6)))
        // 无动画即开即显（非主页弹窗，不播果冻动画）
        col.scaleX = 1f
        col.scaleY = 1f
        col.alpha = 1f
    }

    private fun dismissMorePopup() {
        val p = popup ?: return
        if (popupClosing) return
        popupClosing = true
        val v = p.contentView
        v.animate().cancel()
        // 非主页弹窗：无收起动画，直接关闭
        p.dismiss()
        popup = null
        popupClosing = false
    }

    // ===================== 多选模式 =====================

    private fun enterMultiMode() {
        multiMode = true
        selectedIds.clear()
        moreBtn.textSize = 17f
        moreBtn.typeface = android.graphics.Typeface.DEFAULT_BOLD
        moreBtn.setPadding(dp(12), dp(0), dp(6), dp(0))
        moreBtn.text = getString(R.string.dialog_cancel)
        moreBtn.setOnClickListener { exitMultiMode() }
        bottomBar.visibility = View.VISIBLE
        updateDelSelBtn()
        refresh()
    }

    private fun exitMultiMode() {
        multiMode = false
        selectedIds.clear()
        moreBtn.textSize = 26f
        moreBtn.typeface = android.graphics.Typeface.DEFAULT
        moreBtn.setPadding(dp(12), dp(0), dp(6), dp(0))
        moreBtn.text = "⋯"
        moreBtn.setOnClickListener { toggleMorePopup() }
        bottomBar.visibility = View.GONE
        refresh()
    }

    private fun updateDelSelBtn() {
        delSelBtn.text = getString(R.string.memory_del_selected, selectedIds.size)
    }

    private fun confirmDeleteSelected() {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, getString(R.string.memory_toast_select_first), Toast.LENGTH_SHORT).show()
            return
        }
        val (dlg, box) = Ui.dialog(this, getString(R.string.memory_dialog_del_selected))
        box.addView(Ui.dialogText(this, getString(R.string.memory_confirm_del_selected, selectedIds.size)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.dialogCancelBtn(this, getString(R.string.dialog_cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(Ui.dangerBtn(this, getString(R.string.memory_btn_delete), {
            db.deleteMany(selectedIds.toList())
            dlg.dismiss()
            Toast.makeText(this, getString(R.string.memory_toast_deleted_selected, selectedIds.size), Toast.LENGTH_SHORT).show()
            exitMultiMode()
        }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        box.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        dlg.show()
    }

    // ===================== 删除所有 =====================

    private fun confirmDeleteAll() {
        val count = db.count()
        if (count == 0) {
            Toast.makeText(this, getString(R.string.memory_none), Toast.LENGTH_SHORT).show()
            return
        }
        val (dlg, box) = Ui.dialog(this, getString(R.string.memory_delete_all))
        box.addView(Ui.dialogText(this, getString(R.string.memory_confirm_delete_all, count)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.dialogCancelBtn(this, getString(R.string.dialog_cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(Ui.dangerBtn(this, getString(R.string.memory_btn_delete), {
            db.clearAll()
            dlg.dismiss()
            Toast.makeText(this, getString(R.string.memory_toast_deleted_all), Toast.LENGTH_SHORT).show()
            if (multiMode) exitMultiMode() else refresh()
        }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        box.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        dlg.show()
    }

    // ===================== 列表渲染 =====================

    private fun refresh() {
        container.removeAllViews()
        val mems = db.all()
        if (mems.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.memory_none)
                textSize = 14f
                setTextColor(0xFF999999.toInt())
                setPadding(0, dp(40), 0, dp(40))
                gravity = Gravity.CENTER
            })
            return
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.memory_count, mems.size)
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        // 卡片分组：最多 5 条一组
        val card = Ui.card(this)
        mems.forEachIndexed { i, mem ->
            card.addView(memItem(mem))
            if (i < mems.size - 1) card.addView(Ui.divider(this))
        }
        container.addView(card)
    }

    private fun memItem(mem: MemoryDb.Mem): LinearLayout {
        // 多选模式：左侧勾选框；点击切换选中；选中行高亮浅蓝底
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
        }
        Ui.press(row)
        var checkView: TextView? = null
        fun applySelected() {
            val sel = mem.id in selectedIds
            row.background = if (sel) Ui.rounded(Ui.PRIMARY_LIGHT, 10, this@MemoryListActivity)
                            else Ui.rounded(Color.WHITE, 10, this@MemoryListActivity)
            checkView?.let {
                it.text = if (sel) "✓" else ""
                it.background = Ui.rounded(if (sel) Ui.PRIMARY else Ui.INPUT_BG, 6, this@MemoryListActivity)
            }
        }
        fun toggleSelect() {
            if (!selectedIds.add(mem.id)) selectedIds.remove(mem.id)
            applySelected()
            updateDelSelBtn()
        }
        row.setOnClickListener {
            if (multiMode) toggleSelect() else showDetail(mem)
        }
        // 勾选框（仅多选模式显示）
        if (multiMode) {
            checkView = Ui.check(this@MemoryListActivity, mem.id in selectedIds).apply {
                setOnClickListener { toggleSelect() }
            }
            row.addView(checkView!!, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(10)
            })
        }
        // 右侧信息区
        row.addView(LinearLayout(this@MemoryListActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            // 上边标题: 会话名称（旧数据无会话名则显示"未命名会话"）
            addView(TextView(this@MemoryListActivity).apply {
                text = mem.sessionTitle?.takeIf { it.isNotBlank() } ?: getString(R.string.memory_unnamed_session)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFF0B93F6.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            // 下边内容: 少量摘要
            addView(TextView(this@MemoryListActivity).apply {
                text = mem.content
                textSize = 14f
                setTextColor(0xFF333333.toInt())
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(this@MemoryListActivity).apply {
                text = formatTs(mem.ts) + "    "
                textSize = 12f
                setTextColor(0xFF999999.toInt())
                setPadding(0, dp(6), 0, 0)
            })
        })
        applySelected()
        return row
    }

    // ---- 详情弹窗（自绘卡片风格）：全文 + 编辑 / 删除 ----

    private fun showDetail(mem: MemoryDb.Mem) {
        // 三明治结构：标题固定、内容滚动、按钮固定底部，长内容与键盘都不影响按钮
        val (dlg, content, bottom) = Ui.dialogFixed(this, getString(R.string.memory_detail_title), 0.65)

        content.addView(TextView(this@MemoryListActivity).apply {
            text = mem.sessionTitle?.takeIf { it.isNotBlank() } ?: getString(R.string.memory_unnamed_session)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF0B93F6.toInt())
            setPadding(0, dp(12), 0, dp(2))
        })

        content.addView(TextView(this@MemoryListActivity).apply {
            text = mem.content
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, dp(10))
        })

        content.addView(TextView(this).apply {
            text = getString(R.string.memory_created, formatTs(mem.ts))
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, dp(12))
        })

        // 按钮行：编辑 | 删除 | 取消（固定底部）
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        row.addView(Ui.lightBtn(this, getString(R.string.memory_edit), {
            dlg.dismiss()
            showEdit(mem)
        }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
            height = dp(42)
        })
        row.addView(Ui.dangerBtn(this, getString(R.string.memory_btn_delete), {
            dlg.dismiss()
            confirmDelete(mem)
        }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
            height = dp(42)
        })
        bottom.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        bottom.addView(Ui.dialogCancelBtn(this, getString(R.string.settings_btn_close)) { dlg.dismiss() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        dlg.show()
    }

    // ---- 编辑弹窗 ----

    private fun showEdit(mem: MemoryDb.Mem) {
        // 三明治结构：标题/保存按钮固定，输入区滚动；键盘弹出只压缩中间内容区
        val (dlg, content, bottom) = Ui.dialogFixed(this, getString(R.string.memory_edit_title), 0.65)
        content.addView(Ui.fieldLabel(this, getString(R.string.memory_field_content)))
        val input = Ui.input(this, getString(R.string.memory_hint_input))
        input.setText(mem.content)
        input.setTextColor(0xFF1A1A1A.toInt())
        content.addView(input)
        content.addView(Ui.hint(this, getString(R.string.memory_hint_revector)))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.dialogCancelBtn(this, getString(R.string.dialog_cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(Ui.primaryBtn(this, getString(R.string.settings_btn_save), {
            val text = input.text.toString().trim()
            if (text.isEmpty() || text == mem.content) {
                dlg.dismiss()
                return@primaryBtn
            }
            dlg.dismiss()
            saveEdit(mem.id, text)
        }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        bottom.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        dlg.show()
    }

    private fun saveEdit(id: Long, newContent: String) {
        val pending = Dialog(this)
        pending.setCancelable(false)
        pending.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        pending.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val info = TextView(this).apply {
            text = getString(R.string.memory_computing)
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            background = Ui.rounded(Color.WHITE, 14, this@MemoryListActivity)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        pending.setContentView(info)
        pending.show()

        Thread {
            try {
                val vec = embedder.embed(newContent)
                db.update(id, newContent, vec)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                uiScope.launch {
                    pending.dismiss()
                    refresh()
                }
            }
        }.start()
    }

    // ---- 删除确认 ----

    private fun confirmDelete(mem: MemoryDb.Mem) {
        val (dlg, box) = Ui.dialog(this, getString(R.string.memory_delete_title))
        box.addView(Ui.dialogText(this, getString(R.string.memory_confirm_delete_one)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.dialogCancelBtn(this, getString(R.string.dialog_cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(Ui.dangerBtn(this, getString(R.string.memory_btn_delete), {
            db.delete(mem.id)
            dlg.dismiss()
            refresh()
        }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        box.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
        dlg.show()
    }

    private fun formatTs(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

}
