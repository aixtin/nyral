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

/**
 * MdSpans -> SpannableStringBuilder (D路线第4步).
 * TextView 系渲染载体, 与 Compose AnnotatedString 等价的区间样式映射。
 */
object MdSpannable {
    const val LINK_COLOR = 0xFF1565C0.toInt()     // 链接蓝
    const val HEADING_COLOR = 0xFF0D47A1.toInt()  // 标题深蓝
    const val CODE_BG = 0x1437474F                // 代码块底色
    const val INLINE_CODE_BG = 0x2237474F         // 行内代码底色

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
                    sb.setSpan(BackgroundColorSpan(INLINE_CODE_BG), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                MdSpanType.CODE_BLOCK -> {
                    sb.setSpan(TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(CODE_BG), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                MdSpanType.LINK -> {
                    sp.extra?.let { sb.setSpan(URLSpan(it), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    sb.setSpan(ForegroundColorSpan(LINK_COLOR), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                MdSpanType.HEADING -> {
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.15f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(HEADING_COLOR), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return sb
    }
}
