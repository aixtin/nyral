package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 知识条目详情页(阶段3, 2026-10-19):
 * MD 正文渲染 + 编辑/删除门禁(弹窗确认) + 变更历史。
 * 删除走软删进回收区; 回收区打开时(deleted)提供恢复。
 */
class KnowledgeEntryActivity : Activity() {

    private lateinit var db: KnowledgeBaseDb
    private var entryId = 0L
    private var bodyContainer: LinearLayout? = null

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = KnowledgeBaseDb(this)
        entryId = intent.getLongExtra("entryId", 0L)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.kb_entry_detail), right = ({ bar ->
            bar.addView(TextView(this@KnowledgeEntryActivity).apply {
                text = getString(R.string.kb_history)
                textSize = 14f
                setTextColor(Ui.PRIMARY)
                setPadding(dp(10), dp(0), dp(2), dp(0))
                setOnClickListener { showHistory() }
                Ui.press(this)
            })
        })))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(20))
        }
        bodyContainer = content
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        render(content)
    }

    private fun render(container: LinearLayout) {
        container.removeAllViews()
        val e = db.getEntry(entryId)
        if (e == null) {
            Toast.makeText(this, getString(R.string.kb_gone), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        container.addView(TextView(this).apply {
            text = e.title
            textSize = 19f
            setTextColor(Ui.TEXT)
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.kb_entry_meta,
                e.version,
                if (e.sourceSessionId > 0) getString(R.string.kb_source, e.sourceSessionId) else "",
                fmt(e.updatedTs)) + if (e.deleted) " · " + getString(R.string.kb_deleted_tag) else ""
            textSize = 12f
            setTextColor(Ui.SUB)
            setPadding(0, dp(6), 0, dp(4))
        })
        container.addView(Ui.divider(this).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4)
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
        })

        val body = TextView(this).apply {
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            includeFontPadding = false
            setTextColor(Ui.TEXT)
        }
        renderMd(body, e.content)
        container.addView(body)

        if (!e.deleted) {
            container.addView(Ui.divider(this).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
            })
            val ops = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            ops.addView(Ui.primaryBtn(this, getString(R.string.kb_edit)) { showEdit(e) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) })
            ops.addView(Ui.dangerBtn(this, getString(R.string.kb_delete)) { confirmDelete(e) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
            container.addView(ops)
        } else {
            container.addView(Ui.divider(this).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
            })
            container.addView(Ui.primaryBtn(this, getString(R.string.kb_restore)) { restore(e) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        }
    }

    // ===================== MD 渲染(复用自研链) =====================

    private fun renderMd(tv: TextView, md: String) {
        if (md.isBlank()) {
            tv.text = ""
            return
        }
        MdSpannable.density = resources.displayMetrics.density
        MdSpannable.kbImageLoader = { path ->
            runCatching {
                val f = File(filesDir, path)
                if (!f.exists() || !f.isFile) null
                else BitmapFactory.decodeFile(f.absolutePath)
            }.getOrNull()
        }
        tv.post {
            val w = tv.width - dp(2)  // 预留余量
            if (w > 0) {
                MdSpannable.tableMaxWidth = w
                MdSpannable.kbImageMaxWidth = w
            }
        }
        try {
            tv.text = MdSpannable.toSpannable(MdToSpans.parse(md))
        } catch (e: Exception) {
            tv.text = md
        }
    }

    // ===================== 编辑(门禁: 弹窗确认后落库) =====================

    private fun showEdit(e: KnowledgeBaseDb.Entry) {
        val (dlg, box) = Ui.dialog(this, getString(R.string.kb_edit), animate = true)
        val titleEt = EditText(this).apply {
            setText(e.title)
            textSize = 15f
            setTextColor(Ui.TEXT)
            hint = getString(R.string.kb_title_hint)
            setHintTextColor(Ui.SUB)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = Ui.roundedBorder(Ui.STROKE, 10, 1, this@KnowledgeEntryActivity)
        }
        box.addView(titleEt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val contentEt = EditText(this).apply {
            setText(e.content)
            textSize = 14f
            setTextColor(Ui.TEXT)
            hint = getString(R.string.kb_content_hint)
            setHintTextColor(Ui.SUB)
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = Ui.roundedBorder(Ui.STROKE, 10, 1, this@KnowledgeEntryActivity)
            minLines = 6
        }
        box.addView(contentEt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(10) })
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        btnRow.addView(Ui.dialogCancelBtn(this, getString(R.string.kb_cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) })
        btnRow.addView(Ui.primaryBtn(this, getString(R.string.kb_save)) {
            val t = titleEt.text.toString().trim()
            val c = contentEt.text.toString()
            if (t.isEmpty()) {
                Toast.makeText(this, getString(R.string.kb_title_empty), Toast.LENGTH_SHORT).show()
                return@primaryBtn
            }
            db.updateEntry(entryId, t, c)
            dlg.dismiss()
            Toast.makeText(this, getString(R.string.kb_saved), Toast.LENGTH_SHORT).show()
            bodyContainer?.let { render(it) }
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) })
        box.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        dlg.show()
    }

    // ===================== 删除/恢复(门禁) =====================

    private fun confirmDelete(e: KnowledgeBaseDb.Entry) {
        val (dlg, box) = Ui.dialog(this, getString(R.string.kb_delete), animate = true)
        box.addView(Ui.dialogText(this, getString(R.string.kb_confirm_delete)))
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        btnRow.addView(Ui.dialogCancelBtn(this, getString(R.string.kb_cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) })
        btnRow.addView(Ui.dangerBtn(this, getString(R.string.kb_delete)) {
            db.softDeleteEntry(entryId)
            dlg.dismiss()
            Toast.makeText(this, getString(R.string.kb_deleted), Toast.LENGTH_SHORT).show()
            finish()
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) })
        box.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        dlg.show()
    }

    private fun restore(e: KnowledgeBaseDb.Entry) {
        db.restoreEntry(entryId)
        Toast.makeText(this, getString(R.string.kb_restored), Toast.LENGTH_SHORT).show()
        finish()
    }

    // ===================== 变更历史 =====================

    private fun showHistory() {
        val audits = db.audits("entry", entryId, 200)
        val (dlg, box) = Ui.dialog(this, getString(R.string.kb_history), maxHeightRatio = 0.6, animate = true)
        if (audits.isEmpty()) {
            box.addView(Ui.dialogText(this, getString(R.string.kb_audit_empty)))
        } else {
            audits.forEach { a ->
                box.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(8), 0, dp(2))
                    addView(TextView(this@KnowledgeEntryActivity).apply {
                        text = fmt(a.ts) + "  ·  " + a.action
                        textSize = 12f
                        setTextColor(Ui.SUB)
                    })
                    addView(TextView(this@KnowledgeEntryActivity).apply {
                        text = a.summary
                        textSize = 14f
                        setTextColor(Ui.TEXT)
                        setPadding(0, dp(2), 0, 0)
                    })
                })
                box.addView(Ui.divider(this))
            }
        }
        box.addView(Ui.primaryBtn(this, getString(R.string.kb_done)) { dlg.dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(12) })
        dlg.show()
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}
