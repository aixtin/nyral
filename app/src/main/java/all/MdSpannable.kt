package io.github.aixtin.nyral

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import io.noties.markwon.ext.tables.TableTheme
import org.json.JSONArray

/**
 * MdSpans -> SpannableStringBuilder (D路线第4步).
 * TextView 系渲染载体, 与 Compose AnnotatedString 等价的区间样式映射。
 * 流式(2026-09-22): 文本区间样式映射; 方案B(2026-10-01)表格改走块化 MdBlocksView, 不再构造表格 span
 * 标题按 level 分级倍率对齐 markwon headingTextSizeMultipliers, 代码块整块圆角背景。
 */
object MdSpannable {
    const val LINK_COLOR = 0xFF1565C0.toInt()     // 链接蓝
    const val HEADING_COLOR = 0xFF0D47A1.toInt()  // 标题深蓝
    const val CODE_BG = 0x1437474F                // 代码块底色
    const val INLINE_CODE_BG = 0x2237474F         // 行内代码底色

    /** markwon headingTextSizeMultipliers(1.5,1.35,1.2,1.1,1.05,1.0) 对齐 */
    private val HEADING_SCALE = floatArrayOf(1.5f, 1.35f, 1.2f, 1.1f, 1.05f, 1.0f)

    /** 由 MainActivity markwon 构建后注入, 供表格行 span 复用 markwon 同款主题 */
    @Volatile var tableTheme: TableTheme? = null
    @Volatile var density: Float = 1f
    /** 流式表格可用宽度(px): AiBubbleHolder 渲染前注入 TextView 可用宽, 供表格行首测宽度稳定 */
    @Volatile var tableMaxWidth: Int = 0
    /** 代码块/行内代码底色: MainActivity 注入 Ui.INPUT_BG 与 markwon theme 对齐, 消除收尾突变 */
    @Volatile var codeBlockBg: Int = CODE_BG
    @Volatile var inlineCodeBg: Int = INLINE_CODE_BG

    fun toSpannable(md: MdSpans): CharSequence {
        if (md.displayText.isEmpty()) return md.displayText
        val sb = SpannableStringBuilder(md.displayText)
        for (sp in md.spans) {
            val s = sp.start
            val e = sp.end
            if (s < 0 || e > sb.length || s >= e) continue
            when (sp.type) {
                MdSpanType.BOLD ->
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                MdSpanType.ITALIC ->
                    sb.setSpan(StyleSpan(Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                MdSpanType.STRIKETHROUGH ->
                    sb.setSpan(StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                MdSpanType.INLINE_CODE -> {
                    sb.setSpan(TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(inlineCodeBg), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                MdSpanType.CODE_BLOCK -> {
                    sb.setSpan(TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RoundedBlockBgSpan(codeBlockBg, density), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                MdSpanType.LINK -> {
                    sp.extra?.let { sb.setSpan(URLSpan(it), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    sb.setSpan(ForegroundColorSpan(LINK_COLOR), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                MdSpanType.HEADING -> {
                    val level = sp.extra?.toIntOrNull() ?: 1
                    val scale = HEADING_SCALE.getOrElse(level - 1) { 1.0f }
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(scale), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(HEADING_COLOR), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                MdSpanType.QUOTE -> {
                    // 引用块: 左侧竖线+缩进(系统 QuoteSpan 竖线在流式 TextView 不绘制, 自绘 QuoteBarSpan)
                    val qColor = 0xFF0D47A1.toInt()
                    sb.setSpan(QuoteBarSpan(qColor, (3f * density).toInt(), (12f * density).toInt()), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                MdSpanType.TABLE_ROW -> { /* 方案B(2026-10-01): 表格已块化, 行 span 残留直接跳过(文本流内不再构造表格) */ }
                MdSpanType.TABLE_BLOCK -> { /* 方案B(2026-10-01): 表格块区间由 MdToBlocks 切出, 文本流内不再设 TableSpan */ }
                MdSpanType.HR -> {
                    // 分割线: 零宽占位由 HrSpan 替换为横线(占满可用宽度, 与表格同机制)
                    sb.setSpan(HrSpan(density), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return sb
    }

}
