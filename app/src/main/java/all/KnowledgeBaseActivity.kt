package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.PopupWindow
import android.widget.TextView

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

    /** 书列表页的跨书搜索关键词(空=普通浏览) */
    private var query = ""
    private var searchEt: EditText? = null

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

        // 书列表页常驻搜索: 知识沉淀多了以后, 三级翻目录找不动, 直接跨书搜标题/正文/关键词
        if (mode == "book") {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(2))
            }
            val et = Ui.input(this, getString(R.string.kb_search_hint)).apply {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val clear = TextView(this).apply {
                text = "×"
                textSize = 20f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(2), dp(4))
                visibility = View.GONE
                setOnClickListener {
                    et.setText("")
                    hideKeyboard()
                }
                Ui.press(this)
            }
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                    refresh()
                }
            })
            et.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard()
                    true
                } else false
            }
            bar.addView(et)
            bar.addView(clear)
            root.addView(bar)
            searchEt = et
        }

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val kbScroll = ScrollView(this).apply { isFillViewport = true }
        kbScroll.addView(container, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(kbScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // 旋转/重建后恢复搜索词(此时 container 已就绪, 监听器触发刷新是安全的)
        val kept = savedInstanceState?.getString("kb_query").orEmpty()
        if (kept.isNotEmpty()) searchEt?.setText(kept)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (query.isNotEmpty()) outState.putString("kb_query", query)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (!this::container.isInitialized) return
        container.removeAllViews()
        when (mode) {
            "chapter" -> renderChapters()
            "entry" -> renderEntries()
            else -> renderBooks()
        }
    }

    // ===================== 书列表 =====================

    private fun renderBooks() {
        if (query.isNotEmpty()) {
            renderSearch()
            return
        }
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
                sub = getString(R.string.kb_book_sub, chapters, entries, KnowledgeUi.rel(this, b.updatedTs)),
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

    /** 搜索结果: 跨书跨章, 直接点进条目; 二级信息用「《书》· 章」交代来源 */
    private fun renderSearch() {
        val hits = db.searchEntries(query)
        if (hits.isEmpty()) {
            container.addView(emptyHint(getString(R.string.kb_search_empty)))
            return
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.kb_search_count, hits.size)
            textSize = 12f
            setTextColor(Ui.SUB)
            setPadding(dp(4), 0, 0, dp(8))
        })
        val card = Ui.card(this)
        hits.forEachIndexed { i, hit ->
            card.addView(rowCard(
                icon = hit.entry.title.ifEmpty { "条" }.first().toString(),
                title = hit.entry.title,
                sub = "《${hit.bookTitle}》· ${hit.chapterTitle}",
                meta = getString(R.string.kb_entry_meta_short, hit.entry.version, KnowledgeUi.rel(this, hit.entry.updatedTs)),
                onClick = {
                    startActivity(Intent(this, KnowledgeEntryActivity::class.java)
                        .putExtra("entryId", hit.entry.id))
                }
            ))
            if (i < hits.size - 1) card.addView(Ui.divider(this))
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
            val kw = keywordsText(c.keywords)
            card.addView(rowCard(
                icon = c.title.ifEmpty { "章" }.first().toString(),
                title = c.title,
                sub = if (kw.isEmpty()) getString(R.string.kb_n_entries, entries)
                else getString(R.string.kb_chapter_sub, entries, kw),
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
                sub = summaryOf(e.content),
                meta = getString(R.string.kb_entry_meta_short, e.version, KnowledgeUi.rel(this, e.updatedTs)),
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

    private fun rowCard(
        icon: String,
        title: String,
        sub: String,
        meta: String? = null,
        onClick: () -> Unit
    ): LinearLayout =
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
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(this@KnowledgeBaseActivity).apply {
                    text = sub
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(3), 0, 0)
                    maxLines = if (meta == null) 2 else 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                if (meta != null) {
                    addView(TextView(this@KnowledgeBaseActivity).apply {
                        text = meta
                        textSize = 11f
                        setTextColor(Ui.SUB)
                        setPadding(0, dp(3), 0, 0)
                        maxLines = 1
                    })
                }
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

    /** 关键词存的是逗号串, 展示时统一成「A · B」; 空串不留下孤零零的分隔符 */
    private fun keywordsText(raw: String): String =
        raw.split(',', '，', ';', '；', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" · ")

    private fun summaryOf(md: String): String {
        val first = md.lineSequence().firstOrNull { it.trim().isNotEmpty() } ?: return ""
        val cleaned = first.trim()
            .replace(Regex("""[#>*`\-\[\]()|]"""), " ")
            .replace(Regex("\\s+"), " ")
        return cleaned.trim().take(60)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val token = (searchEt ?: window.decorView).windowToken
        imm.hideSoftInputFromWindow(token, 0)
        searchEt?.clearFocus()
    }

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
        // 与主页 token 面板/长期记忆页同款修复：显式在按钮正下方 6dp 展开，
        // 不再因按钮上方空间不足被系统翻转而盖住触发按钮本身
        popup!!.showAsDropDown(btn, 0, dp(6))
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
