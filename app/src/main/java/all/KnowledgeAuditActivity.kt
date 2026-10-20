package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 知识库变更审计页(阶段3, 2026-10-19):
 * 读 kb_audit, 按时间倒序渲染变更留痕卡片(仿安全审计页)。
 */
class KnowledgeAuditActivity : Activity() {

    private lateinit var db: KnowledgeBaseDb
    private lateinit var container: LinearLayout

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = KnowledgeBaseDb(this)
        Ui.statusBar(this)
        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.kb_audit)))
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val audits = db.audits(limit = 300)
        if (audits.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.kb_audit_empty)
                textSize = 13f
                setTextColor(Ui.SUB)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(24), dp(40), dp(24), dp(40))
            })
            return
        }
        audits.forEach { a ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(4))
                addView(TextView(this@KnowledgeAuditActivity).apply {
                    text = fmt(a.ts) + "  ·  " + actionLabel(a.action)
                    textSize = 12f
                    setTextColor(Ui.SUB)
                })
                addView(TextView(this@KnowledgeAuditActivity).apply {
                    text = a.summary
                    textSize = 14f
                    setTextColor(Ui.TEXT)
                    setPadding(0, dp(3), 0, 0)
                    typeface = Typeface.DEFAULT
                })
                addView(TextView(this@KnowledgeAuditActivity).apply {
                    text = a.entityType + " #" + a.entityId
                    textSize = 11f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(2), 0, 0)
                })
            })
            container.addView(Ui.divider(this))
        }
    }

    private fun actionLabel(action: String): String = when (action) {
        "create" -> getString(R.string.kb_action_create)
        "update" -> getString(R.string.kb_action_update)
        "delete" -> getString(R.string.kb_action_delete)
        "restore" -> getString(R.string.kb_action_restore)
        "purge" -> getString(R.string.kb_action_purge)
        else -> action
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}
