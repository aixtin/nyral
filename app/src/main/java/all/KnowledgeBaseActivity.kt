package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 知识库浏览页(阶段3, 2026-10-19):
 * 书列表 → 章节目录 → 条目列表 三级跳转, 单一 Activity 按 mode 复用。
 * 书列表页右上角 ⋯: 回收区 / 变更审计 入口。
 */
class KnowledgeBaseActivity : Activity() {

    private lateinit var db: KnowledgeBaseDb
    private lateinit var container: LinearLayout
    private var moreBtn: TextView? = null
    private var popup: PopupWindow? = null

    private var mode = "book"
    private var bookId = 0L
    private var chapterId = 0L

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = KnowledgeBaseDb(this)
        mode = intent.getStringExtra("mode") ?: "book"
        bookId = intent.getLongExtra("bookId", 0L)
        chapterId = intent.getLongExtra("chapterId", 0L)
        Ui.statusBar(this)

        val title = when (mode) {
            "chapter" -> db.getBook(bookId)?.title ?: getString(R.string.kb_chapters)
            "entry" -> db.getChapter(chapterId)?.title ?: getString(R.string.kb_entries)
            else -> getString(R.string.kb_title)
        }
        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, title, right = if (mode == "book") ({ bar ->
            moreBtn = TextView(this@KnowledgeBaseActivity).apply {
                text = "⋯"
                textSize = 26f
                setTextColor(Ui.TEXT)
                setPadding(dp(12), dp(0), dp(6), dp(0))
                setOnClickListener { toggleMorePopup() }
                Ui.press(this)
            }
            bar.addView(moreBtn)
        }) else null))

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
        when (mode) {
            "chapter" -> renderChapters()
            "entry" -> renderEntries()
            else -> renderBooks()
        }
    }

    // ===================== 书列表 =====================

    private fun renderBooks() {
        val books = db.listBooks()
        if (books.isEmpty()) {
            container.addView(emptyHint(getString(R.string.kb_empty_books)))
            return
        }
        val card = Ui.card(this)
        books.forEachIndexed { i, b ->
            val chapters = db.chapterCount(b.id)
            val entries = db.entryCountOfBook(b.id)
            card.addView(rowCard(
                icon = b.title.ifEmpty { "书" }.first().toString(),
                title = b.title,
                sub = getString(R.string.kb_book_sub, chapters, entries, fmt(b.updatedTs)),
                onClick = {
                    startActivity(Intent(this, KnowledgeBaseActivity::class.java)
                        .putExtra("mode", "chapter")
                        .putExtra("bookId", b.id))
                }
            ))
            if (i < books.size - 1) card.addView(Ui.divider(this))
        }
        container.addView(card)
    }

    // ===================== 章列表 =====================

    private fun renderChapters() {
        val chapters = db.listChapters(bookId)
        if (chapters.isEmpty()) {
            container.addView(emptyHint(getString(R.string.kb_empty_chapters)))
            return
        }
        val card = Ui.card(this)
        chapters.forEachIndexed { i, c ->
            val entries = db.entryCount(c.id)
            card.addView(rowCard(
                icon = c.title.ifEmpty { "章" }.first().toString(),
                title = c.title,
                sub = getString(R.string.kb_chapter_sub, entries, c.keywords),
                onClick = {
                    startActivity(Intent(this, KnowledgeBaseActivity::class.java)
                        .putExtra("mode", "entry")
                        .putExtra("bookId", bookId)
                        .putExtra("chapterId", c.id))
                }
            ))
            if (i < chapters.size - 1) card.addView(Ui.divider(this))
        }
        container.addView(card)
    }

    // ===================== 条目列表 =====================

    private fun renderEntries() {
        val entries = db.listEntries(chapterId)
        if (entries.isEmpty()) {
            container.addView(emptyHint(getString(R.string.kb_empty_entries)))
            return
        }
        val card = Ui.card(this)
        entries.forEachIndexed { i, e ->
            card.addView(rowCard(
                icon = e.title.ifEmpty { "条" }.first().toString(),
                title = e.title,
                sub = getString(R.string.kb_entry_sub, e.version, summaryOf(e.content), fmt(e.updatedTs)),
                onClick = {
                    startActivity(Intent(this, KnowledgeEntryActivity::class.java)
                        .putExtra("entryId", e.id))
                }
            ))
            if (i < entries.size - 1) card.addView(Ui.divider(this))
        }
        container.addView(card)
    }

    // ===================== 通用行 =====================

    private fun rowCard(icon: String, title: String, sub: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
            Ui.press(this)
            addView(Ui.iconBadge(this@KnowledgeBaseActivity, icon, seed = title.hashCode()))
            addView(LinearLayout(this@KnowledgeBaseActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(12), 0, dp(8), 0)
                addView(TextView(this@KnowledgeBaseActivity).apply {
                    text = title
                    textSize = 15f
                    setTextColor(Ui.TEXT)
                    maxLines = 1
                })
                addView(TextView(this@KnowledgeBaseActivity).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                })
            })
            addView(TextView(this@KnowledgeBaseActivity).apply {
                text = "›"
                textSize = 22f
                setTextColor(Ui.SUB)
            })
        }

    private fun emptyHint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Ui.SUB)
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(40), dp(24), dp(40))
    }

    private fun summaryOf(md: String): String {
        val first = md.lineSequence().firstOrNull { it.trim().isNotEmpty() } ?: return ""
        val t = first.trim()
        val cleaned = t.replace(Regex("""[#>*`\-\[\]()|]"""), " ")
        return cleaned.trim().take(40)
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

    // ===================== 右上角 ⋯ 弹窗 =====================

    private fun toggleMorePopup() {
        if (popup?.isShowing == true) {
            dismissMorePopup()
            return
        }
        val btn = moreBtn ?: return
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = Ui.rounded(Ui.SURFACE, 14, this@KnowledgeBaseActivity)
            elevation = dp(10).toFloat()
        }
        fun item(label: String, onClick: () -> Unit) {
            col.addView(TextView(this@KnowledgeBaseActivity).apply {
                text = label
                textSize = 14f
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setTextColor(Ui.TEXT)
                setOnClickListener {
                    dismissMorePopup()
                    onClick()
                }
            })
        }
        item(getString(R.string.kb_trash)) {
            startActivity(Intent(this, KnowledgeTrashActivity::class.java))
        }
        item(getString(R.string.kb_audit)) {
            startActivity(Intent(this, KnowledgeAuditActivity::class.java))
        }

        col.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val popH = col.measuredHeight
        popup = PopupWindow(col, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            elevation = dp(10).toFloat()
            isTouchable = true
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable())
            setTouchInterceptor { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) {
                    val p = IntArray(2)
                    btn.getLocationOnScreen(p)
                    val inBtn = e.rawX >= p[0] && e.rawX <= p[0] + btn.width &&
                            e.rawY >= p[1] && e.rawY <= p[1] + btn.height
                    if (inBtn) {
                        dismissMorePopup()
                        return@setTouchInterceptor true
                    }
                    dismissMorePopup()
                }
                false
            }
        }
        popup!!.showAsDropDown(btn, 0, -(popH + btn.height + dp(6)))
        col.scaleX = 1f
        col.scaleY = 1f
        col.alpha = 1f
    }

    private fun dismissMorePopup() {
        val p = popup ?: return
        popup = null
        p.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissMorePopup()
    }
}
