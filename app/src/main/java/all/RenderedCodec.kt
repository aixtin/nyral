package io.github.aixtin.nyral

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tables.TableTheme
import org.json.JSONArray
import org.json.JSONObject

/**
 * rendered 落库序列化器: Spanned <-> JSON 字符串 (渲染产物持久化 v1)。
 * 只存结构化 span(类型+区间+参数), 不含布局态(表格 width 等) —— 显示时按当前气泡宽度重新布局。
 *
 * 覆盖 span:
 *  标准: StyleSpan / RelativeSizeSpan / ForegroundColorSpan / BackgroundColorSpan /
 *        StrikethroughSpan / URLSpan / TypefaceSpan / BulletSpan / LeadingMarginSpan.Standard
 *  自定义: RoundedCodeBlockSpan / RoundedBlockBgSpan / RoundedTableRowSpan / TableSpan
 *  未知 span 跳过(不持久化, 重建缺失该样式, 不崩)
 */
object RenderedCodec {

    const val VERSION = 1

    private const val KEY_V = "v"
    private const val KEY_TEXT = "text"
    private const val KEY_SPANS = "spans"
    private const val KEY_T = "t"
    private const val KEY_S = "s"
    private const val KEY_E = "e"
    private const val MAX_TABLE_CELL_CHARS = 16

    /** Spanned -> JSON 字符串; 无可序列化 span 或失败返回 null */
    fun encode(spanned: Spanned): String? {
        try {
            val arr = JSONArray()
            for (sp in spanned.getSpans(0, spanned.length, Any::class.java)) {
                val s = spanned.getSpanStart(sp)
                val e = spanned.getSpanEnd(sp)
                if (s < 0 || e > spanned.length || s >= e) continue
                val o = spanToJson(sp, s, e) ?: continue
                arr.put(o)
            }
            // 纯文本(无 span)也落库: decode 侧空 spans 数组返回纯文本, 保持渲染产物完整性
            val root = JSONObject()
            root.put(KEY_V, VERSION)
            root.put(KEY_TEXT, spanned.toString())
            root.put(KEY_SPANS, arr)
            return root.toString()
        } catch (_: Throwable) {
            return null
        }
    }

    /** JSON 字符串 -> Spanned; 版本不匹配/解析失败返回 null(调用方退现场渲染)。
     *  tableMaxWidth: 表格行可用宽(px), 默认取 MdSpannable 静态现算值;
     *  必须与现场渲染同宽——decode 重建的表格行宽度固化在 span 里, 宽度不一致=重启二次渲染跳变 */
    fun decode(json: String?, markwonTheme: MarkwonTheme?, tableTheme: TableTheme?, density: Float, tableMaxWidth: Int = MdSpannable.tableMaxWidth): Spanned? {
        if (json.isNullOrBlank()) return null
        try {
            val root = JSONObject(json)
            val ver = root.optInt(KEY_V, 0)
            if (ver != VERSION) return null
            val text = root.optString(KEY_TEXT, "")
            val sb = SpannableStringBuilder(text)
            val arr = root.optJSONArray(KEY_SPANS) ?: return sb
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = o.optInt(KEY_S, -1)
                val e = o.optInt(KEY_E, -1)
                if (s < 0 || e > text.length || s >= e) continue
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = o.optInt(KEY_S, -1)
                val e = o.optInt(KEY_E, -1)
                if (s < 0 || e > sb.length || s >= e) continue
                applySpan(sb, o, s, e, markwonTheme, tableTheme, density, tableMaxWidth)
            }
            return sb
        } catch (_: Throwable) {
            return null
        }
    }

    private fun applySpan(sb: SpannableStringBuilder, o: JSONObject, s: Int, e: Int, markwonTheme: MarkwonTheme?, tableTheme: TableTheme?, density: Float, tableMaxWidth: Int) {
        when (o.optString(KEY_T)) {
            "style" -> sb.setSpan(StyleSpan(o.optInt("style", Typeface.NORMAL)), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            "relsize" -> sb.setSpan(RelativeSizeSpan(o.optDouble("prop", 1.0).toFloat()), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            "fg" -> sb.setSpan(ForegroundColorSpan(o.optInt("color", 0xFF000000.toInt())), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            "bg" -> sb.setSpan(BackgroundColorSpan(o.optInt("color", 0)), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            "strike" -> sb.setSpan(StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            "url" -> {
                val u = o.optString("url", "")
                if (u.isNotEmpty()) sb.setSpan(URLSpan(u), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            "typeface" -> {
                val f = o.optString("family", "monospace")
                if (f.isNotEmpty()) sb.setSpan(TypefaceSpan(f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            "bullet" -> sb.setSpan(BulletSpan(o.optInt("gap", 20)), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            "lmargin" -> sb.setSpan(LeadingMarginSpan.Standard(o.optInt("first", 0), o.optInt("rest", 0)), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            "codeblock" -> {
                val th = markwonTheme
                if (th != null) sb.setSpan(RoundedCodeBlockSpan(th, density), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            "blockbg" -> sb.setSpan(RoundedBlockBgSpan(o.optInt("color", 0), density), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            "hr" -> {
                // 分割线: 无 density 时按当前屏幕密度重建(现场渲染用同值, 视觉一致)
                val d = o.optDouble("density", density.toDouble()).toFloat()
                sb.setSpan(HrSpan(d), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            "quote" -> {
                // 引用竖线: 缺省参数与 MdSpannable 现场渲染保持一致(蓝竖线 3dp/12dp)
                sb.setSpan(
                    QuoteBarSpan(
                        o.optInt("color", 0xFF0D47A1.toInt()),
                        o.optInt("stripe", (3f * density).toInt()),
                        o.optInt("gap", (12f * density).toInt())
                    ),
                    s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun spanToJson(sp: Any, s: Int, e: Int): JSONObject? {
        val o = JSONObject()
        o.put(KEY_S, s)
        o.put(KEY_E, e)
        return when (sp) {
            is StyleSpan -> { o.put(KEY_T, "style"); o.put("style", sp.style); o }
            is RelativeSizeSpan -> { o.put(KEY_T, "relsize"); o.put("prop", sp.sizeChange); o }
            is ForegroundColorSpan -> { o.put(KEY_T, "fg"); o.put("color", sp.foregroundColor); o }
            is BackgroundColorSpan -> { o.put(KEY_T, "bg"); o.put("color", sp.backgroundColor); o }
            is StrikethroughSpan -> { o.put(KEY_T, "strike"); o }
            is URLSpan -> { o.put(KEY_T, "url"); o.put("url", sp.url); o }
            is TypefaceSpan -> {
                o.put(KEY_T, "typeface")
                o.put("family", sp.family ?: "monospace")
                o
            }
            is BulletSpan -> { o.put(KEY_T, "bullet"); o.put("gap", sp.gapWidth); o }
            is LeadingMarginSpan.Standard -> {
                o.put(KEY_T, "lmargin")
                o.put("first", sp.getLeadingMargin(true))
                o.put("rest", sp.getLeadingMargin(false))
                o
            }
            is RoundedCodeBlockSpan -> { o.put(KEY_T, "codeblock"); o }
            is RoundedBlockBgSpan -> { o.put(KEY_T, "blockbg"); o.put("color", sp.bgColor); o }
            is HrSpan -> { o.put(KEY_T, "hr"); o.put("density", sp.getDensity()); o }
            is QuoteBarSpan -> {
                o.put(KEY_T, "quote")
                o.put("color", sp.getColor())
                o.put("stripe", sp.getStripeWidth())
                o.put("gap", sp.getGapWidth())
                o
            }
            else -> null
        }
    }
}
