package io.github.aixtin.nyral

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 检查更新页。
 *
 * 版本详情的来源由 UpdateChecker 自动测速选定（GitHub / Gitee 竞速，快的胜出），
 * 两个仓库的 Release 日志内容一致，因此页面不提供"切换仓库"，只展示当前生效的来源与实测耗时。
 * 下载通道仍由用户手动选择：官方直链 / GitHub / Gitee。
 */
class UpdateActivity : Activity() {

    private lateinit var column: LinearLayout
    private var scrollView: ScrollView? = null
    private var refreshBtn: ImageView? = null
    private var spinAnim: android.animation.ObjectAnimator? = null

    private var snapshot: UpdateChecker.Snapshot? = null
    private var cacheAt = 0L
    private var current = "0"
    private var loading = false
    private var failed = false

    /** 历史版本展开状态（tag 集合） */
    private val expanded = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = UpdateChecker.currentVersion(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.upd_title), right = { bar ->
            val btn = ImageView(this).apply {
                setImageResource(R.drawable.lucide_refresh_cw)
                setColorFilter(Ui.TEXT)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { refresh(force = true) }
            }
            refreshBtn = btn
            bar.addView(btn)
        }))

        val sv = ScrollView(this).apply { isFillViewport = true }
        scrollView = sv
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(32))
        }
        sv.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // 首屏先吃本地缓存，避免每次进来都空白等网络
        UpdateChecker.readCache(this)?.let {
            snapshot = it.first
            cacheAt = it.second
        }
        render()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        // 回前台且缓存已过期：静默刷新一次
        if (!loading && cacheAt > 0 && System.currentTimeMillis() - cacheAt > UpdateChecker.CACHE_TTL_MS) {
            refresh()
        }
    }

    /**
     * 拉取最新版本详情。
     * fix(2026-11-16): 一小时缓存此前只用于"首屏先渲染旧数据", 每次进页面仍会对两个仓库各查一次
     * (GitHub 未带 token/ETag, 60 次/小时配额下很快就会 403 静默落回 Gitee)。
     * 现在缓存新鲜期内直接重画缓存, 只有过期或用户主动点刷新(force=true)才走网络。
     */
    private fun refresh(force: Boolean = false) {
        if (loading) return
        if (!force && cacheAt > 0 && System.currentTimeMillis() - cacheAt < UpdateChecker.CACHE_TTL_MS) {
            render()
            return
        }
        loading = true
        failed = false
        spin()
        render()
        UpdateChecker.refresh(this) { snap ->
            loading = false
            if (snap != null) {
                snapshot = snap
                cacheAt = System.currentTimeMillis()
                failed = false
            } else if (snapshot == null) {
                failed = true
            }
            render()
            stopSpin()
        }
    }

    /** 刷新中：右上角刷新图标持续旋转，拉取结束停下 */
    private fun spin() {
        val btn = refreshBtn ?: return
        stopSpin()
        spinAnim = android.animation.ObjectAnimator.ofFloat(btn, View.ROTATION, 0f, 360f).apply {
            duration = 800
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
    }

    private fun stopSpin() {
        spinAnim?.cancel()
        spinAnim = null
        refreshBtn?.rotation = 0f
    }

    // ============================ 渲染 ============================

    private fun render() {
        val keepY = scrollView?.scrollY ?: 0
        column.removeAllViews()

        val snap = snapshot
        val latestTag = snap?.let { UpdateChecker.latestVersion(it) }
        val latest = snap?.releases?.firstOrNull { it.tag == latestTag }
        val hasUpdate = latestTag != null && UpdateChecker.compareVersion(latestTag, current) > 0

        buildStatus(snap, latestTag, hasUpdate)
        if (snap != null) buildChannels(snap, latestTag, hasUpdate)
        if (latest != null) buildBody(latest)
        if (snap != null && snap.releases.size > 1) buildHistory(snap, latestTag)
        buildFooter(snap)

        scrollView?.post { scrollView?.scrollTo(0, keepY) }
    }

    /** 顶部状态卡：当前版本 / 最新版本 / 生效来源与实测耗时 */
    private fun buildStatus(
        snap: UpdateChecker.Snapshot?,
        latestTag: String?,
        hasUpdate: Boolean
    ) {
        val card = cardView()

        val head = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
        }
        when {
            loading && snap == null -> {
                head.text = getString(R.string.upd_checking)
                head.setTextColor(Ui.SUB)
            }
            snap == null -> {
                head.text = getString(R.string.upd_failed)
                head.setTextColor(Ui.DANGER)
            }
            hasUpdate -> {
                head.text = getString(R.string.upd_new_version, latestTag ?: "")
                head.setTextColor(Ui.PRIMARY)
            }
            else -> {
                head.text = getString(R.string.upd_up_to_date)
                head.setTextColor(Ui.TEXT)
            }
        }
        card.addView(head)

        card.addView(TextView(this).apply {
            text = getString(R.string.upd_current_fmt, current)
            textSize = 13f
            setTextColor(Ui.SUB)
            setPadding(0, dp(6), 0, 0)
        })

        if (snap != null) {
            card.addView(TextView(this).apply {
                text = getString(R.string.upd_source_fmt, snap.source.label, snap.latencyMs)
                textSize = 12f
                setTextColor(Ui.SUB)
                setPadding(0, dp(2), 0, 0)
            })
        }

        if (loading) {
            card.addView(TextView(this).apply {
                text = getString(R.string.upd_checking)
                textSize = 12f
                setTextColor(Ui.SUB)
                setPadding(0, dp(8), 0, 0)
            })
            if (snap != null) {
                // 已有缓存时刷新不阻断阅读，仅灰字提示
                card.removeViewAt(card.childCount - 1)
            }
        }

        if (snap == null) {
            val btn = Ui.lightBtn(this, getString(R.string.upd_retry)) { refresh(force = true) }
            card.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
        }

        column.addView(card)
    }

    /** 更新内容卡：最新版 Release body（Markdown 轻量渲染） */
    private fun buildBody(r: UpdateChecker.Release) {
        column.addView(Ui.groupLabel(this, getString(R.string.upd_whats_new)))
        val card = cardView()

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = r.name.ifBlank { r.tag }
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = shortDate(r.publishedAt)
            textSize = 12f
            setTextColor(Ui.SUB)
        })
        card.addView(titleRow)

        card.addView(bodyView(cleanBody(r)))
        column.addView(card)
    }

    /** 下载渠道卡：紧挨状态卡，三渠道横排一行，共用一句说明 */
    private fun buildChannels(
        snap: UpdateChecker.Snapshot,
        latestTag: String?,
        hasUpdate: Boolean
    ) {
        val tag = latestTag ?: return
        if (!hasUpdate) return
        column.addView(Ui.groupLabel(this, getString(R.string.upd_download)))
        val card = cardView()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            channelBtn(getString(R.string.upd_channel_official), true) {
                doDownload(UpdateChecker.Channel.OFFICIAL, tag)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        listOf(
            getString(R.string.upd_channel_github) to UpdateChecker.Channel.GITHUB,
            getString(R.string.upd_channel_gitee) to UpdateChecker.Channel.GITEE
        ).forEach { (label, ch) ->
            row.addView(
                channelBtn(label, false) { doDownload(ch, tag) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                }
            )
        }
        card.addView(row)

        card.addView(TextView(this).apply {
            text = getString(R.string.upd_download_hint)
            textSize = 12f
            setTextColor(Ui.SUB)
            setPadding(0, dp(10), 0, 0)
        })

        column.addView(card)
    }

    /** 渠道按钮：等宽等高，官方主色实心、其余浅蓝 */
    private fun channelBtn(text: String, solid: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            isClickable = true
            gravity = Gravity.CENTER
            setTextColor(if (solid) Color.WHITE else Ui.PRIMARY)
            background = Ui.rounded(
                if (solid) Ui.PRIMARY else Ui.PRIMARY_LIGHT, 12, this@UpdateActivity
            )
            setPadding(dp(4), dp(11), dp(4), dp(11))
            Ui.press(this)
            setOnClickListener { onClick() }
        }

    /** 历史版本：默认全部折叠，点开看该版日志（无下载入口，避免误装旧版） */
    private fun buildHistory(snap: UpdateChecker.Snapshot, latestTag: String?) {
        val history = snap.releases.filter { it.tag != latestTag }.take(15)
        if (history.isEmpty()) return
        column.addView(Ui.groupLabel(this, getString(R.string.upd_history)))
        val card = cardView()
        history.forEachIndexed { idx, r ->
            if (idx > 0) card.addView(Ui.divider(this, 0))
            card.addView(historyRow(r))
        }
        column.addView(card)
    }

    private fun historyRow(r: UpdateChecker.Release): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = r.tag
            textSize = 15f
            setTextColor(Ui.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = shortDate(r.publishedAt)
            textSize = 12f
            setTextColor(Ui.SUB)
        })
        val arrow = TextView(this).apply {
            text = if (expanded.contains(r.tag)) "\u2304" else "\u203A"
            textSize = 16f
            setTextColor(Ui.SUB)
            setPadding(dp(10), 0, 0, 0)
        }
        head.addView(arrow)
        wrap.addView(head)

        if (expanded.contains(r.tag)) {
            wrap.addView(bodyView(cleanBody(r), 12.5f))
        }

        wrap.setOnClickListener {
            if (expanded.contains(r.tag)) expanded.remove(r.tag) else expanded.add(r.tag)
            render()
        }
        return wrap
    }

    private fun buildFooter(snap: UpdateChecker.Snapshot?) {
        val page = snap?.source?.page ?: UpdateChecker.Source.GITHUB.page
        val btn = Ui.lightBtn(this, getString(R.string.upd_all_releases)) { openUrl(page) }
        column.addView(btn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })
    }

    // ============================ 交互 ============================

    private fun doDownload(channel: UpdateChecker.Channel, tag: String) {
        val ok = UpdateChecker.download(this, channel, tag)
        Toast.makeText(
            this,
            if (ok) getString(R.string.upd_download_started) else getString(R.string.upd_download_failed),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, getString(R.string.about_17), Toast.LENGTH_SHORT).show() }
    }

    // ============================ 小组件 ============================

    private fun cardView(): LinearLayout = Ui.card(this).apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun bodyView(text: String, sizeSp: Float = 13.5f): TextView = TextView(this).apply {
        val blank = text.isBlank()
        setText(if (blank) getString(R.string.upd_empty_body) else markdown(text))
        textSize = sizeSp
        setTextColor(if (blank) Ui.SUB else Ui.TEXT)
        setLineSpacing(dp(3).toFloat(), 1f)
        movementMethod = LinkMovementMethod.getInstance()
        highlightColor = Color.TRANSPARENT
        setPadding(0, dp(8), 0, 0)
    }

    /** Release 正文常以「v2.9.31」自带头行，与卡片标题重复，渲染前剥掉 */
    private fun cleanBody(r: UpdateChecker.Release): String {
        val lines = r.body.replace("\r\n", "\n").replace("\r", "\n")
            .split("\n").toMutableList()
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        if (lines.isEmpty()) return r.body
        val head = lines.first().trim()
            .trimStart('#').trim()
            .removeSurrounding("**").trim()
            .lowercase()
            // fix(2026-11-16): "## v2.9.24（versionCode 137）" 这类带括注的头行剥不干净, 会让标题重复一遍
            .substringBefore('（').substringBefore('(').trim()
            .removePrefix("v")
        val keys = listOf(r.name, r.tag).map {
            it.trim().removeSurrounding("**").lowercase().removePrefix("v")
        }.filter { it.isNotEmpty() && !it.contains(" ") }
        if (head in keys) {
            lines.removeAt(0)
            while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        }
        return lines.joinToString("\n")
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    /** "2026-11-10T12:00:00Z" -> "2026-11-10"（按本机时区，避免东八区凌晨把发布日期显示成前一天） */
    private fun shortDate(s: String): String {
        // fix(2026-11-16): 原来直接截前 10 字符 = UTC 日期; 解析失败时仍退回截断
        return runCatching {
            // fix(2026-11-16): LocalDate.ofInstant 是 Java 9+ API, API<33 上无 desugar 会抛
            // NoSuchMethodError(runCatching 兜住后静默退化成截断字符串)。改用 Instant.atZone
            java.time.Instant.parse(s).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        }.getOrElse { if (s.length >= 10) s.substring(0, 10) else s }
    }

    // ============================ 轻量 Markdown ============================

    private val inlineRe = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")

    private fun markdown(src: String): CharSequence {
        val sb = SpannableStringBuilder()
        val lines = src.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var first = true
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                if (!first && (sb.isEmpty() || sb[sb.length - 1] != '\n')) sb.append("\n")
                continue
            }
            if (!first && (sb.isEmpty() || sb[sb.length - 1] != '\n')) sb.append("\n")

            val headingMatch = Regex("^#{1,6}\\s+").find(line)
            val bullet = line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")
            val quote = line.startsWith("> ")

            when {
                headingMatch != null -> {
                    val content = line.substring(headingMatch.range.last + 1)
                    val s = sb.length
                    appendInline(sb, content)
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.06f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                bullet -> {
                    sb.append("\u00B7 ")
                    appendInline(sb, line.substring(2).trim())
                }
                quote -> {
                    val s = sb.length
                    appendInline(sb, line.substring(2).trim())
                    sb.setSpan(ForegroundColorSpan(Ui.SUB), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> appendInline(sb, line)
            }
            first = false
        }
        return sb
    }

    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var last = 0
        for (m in inlineRe.findAll(text)) {
            if (m.range.first > last) out.append(text.substring(last, m.range.first))
            val g = m.groupValues
            if (g[1].isNotEmpty()) {
                val s = out.length
                out.append(g[1])
                out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (g[2].isNotEmpty()) {
                val s = out.length
                out.append(g[2])
                out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(0x14000000), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                val s = out.length
                out.append(g[3])
                out.setSpan(urlSpan(g[4]), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = m.range.last + 1
        }
        if (last < text.length) out.append(text.substring(last))
    }

    private fun urlSpan(url: String): ClickableSpan = object : ClickableSpan() {
        override fun onClick(widget: View) { openUrl(url) }
        override fun updateDrawState(ds: TextPaint) {
            ds.color = Ui.PRIMARY
            ds.isUnderlineText = false
        }
    }
}
