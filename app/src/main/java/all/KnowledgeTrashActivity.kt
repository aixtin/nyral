package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 知识库回收区(阶段3, 2026-10-19):
 * 软删条目列表, 支持恢复 / 彻底删除(弹窗确认, 审计留痕)。
 */
class KnowledgeTrashActivity : Activity() {

    private lateinit var db: KnowledgeBaseDb
    private lateinit var container: LinearLayout

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = KnowledgeBaseDb(this)
        Ui.statusBar(this)
        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.kb_trash)))
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        container.removeAllViews()
        val items = db.deletedEntries()
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.kb_trash_empty)
                textSize = 13f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(40), dp(24), dp(40))
            })
            return
        }
        val card = Ui.card(this)
        items.forEachIndexed { i, e ->
            card.addView(trashRow(e))
            if (i < items.size - 1) card.addView(Ui.divider(this))
        }
        container.addView(card)
    }

    private fun trashRow(e: KnowledgeBaseDb.Entry): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                startActivity(Intent(this@KnowledgeTrashActivity, KnowledgeEntryActivity::class.java)
                    .putExtra("entryId", e.id))
            }
            Ui.press(this)
            addView(LinearLayout(this@KnowledgeTrashActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@KnowledgeTrashActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(this@KnowledgeTrashActivity).apply {
                        text = e.title
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Ui.TEXT)
                        maxLines = 1
                    })
                    addView(TextView(this@KnowledgeTrashActivity).apply {
                        text = fmt(e.updatedTs)
                        textSize = 12f
                        setTextColor(Ui.SUB)
                        setPadding(0, dp(3), 0, 0)
                    })
                })
                addView(TextView(this@KnowledgeTrashActivity).apply {
                    text = getString(R.string.kb_restore)
                    textSize = 13f
                    setTextColor(Ui.PRIMARY)
                    setPadding(dp(14), dp(8), dp(6), dp(8))
                    setOnClickListener {
                        db.restoreEntry(e.id)
                        Toast.makeText(this@KnowledgeTrashActivity, getString(R.string.kb_restored), Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                    Ui.press(this)
                })
                addView(TextView(this@KnowledgeTrashActivity).apply {
                    text = getString(R.string.kb_purge)
                    textSize = 13f
                    setTextColor(Ui.DANGER)
                    setPadding(dp(6), dp(8), dp(2), dp(8))
                    setOnClickListener { confirmPurge(e) }
                    Ui.press(this)
                })
            })
        }

    private fun confirmPurge(e: KnowledgeBaseDb.Entry) {
        val (dlg, box) = Ui.dialog(this, getString(R.string.kb_purge), animate = true)
        box.addView(Ui.dialogText(this, getString(R.string.kb_confirm_purge, e.title)))
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        btnRow.addView(Ui.dialogCancelBtn(this, getString(R.string.kb_cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) })
        btnRow.addView(Ui.dangerBtn(this, getString(R.string.kb_purge)) {
            db.purgeEntry(e.id)
            dlg.dismiss()
            Toast.makeText(this, getString(R.string.kb_purged), Toast.LENGTH_SHORT).show()
            refresh()
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) })
        box.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        dlg.show()
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}
