package io.github.aixtin.nyral

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ===== 方案 B 气泡块容器 (2026-10-01) =====
 * 气泡正文从单一 TextView 升级为块容器 LinearLayout:
 *   Bubble -> LinearLayout{ TextView(文本块), MdTableView(表格块), ... }
 *  - 文本块: 复用原 Spannable 渲染(行内样式全保留), 一个块一个 TextView;
 *  - 表格块: 独立 MdTableView(TableLayout + onDraw 边框 + 内部横滑)。
 *  - 渲染路径收敛一条: MdToBlocks.render(md) 产出有序块列表, bindBlocks 逐块挂载。
 *  - 内存泄漏防护: bindBlocks 前 removeAllViews 清空旧子块(RecyclerView 复用安全)。
 *  - 性能基准: bindBlocks 计时 + 块数量上报 Log.i(PerfB), 供性能基准测试采样。
 */
internal class MdBlocksView(context: Context) : LinearLayout(context) {

    private var textColor = 0xFFFFFFFF.toInt()
    private var borderColor = 0
    private var headerBg = 0
    private var availW = 0
    private var maxW = 0
    private var pendingBlocks: List<MdRenderBlock>? = null

    init {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
    }

    /** 注入渲染上下文: 必须在 bindBlocks 前调用(宽/色来自气泡) */
    fun setRenderContext(maxWidth: Int, availWidth: Int, textColor: Int, border: Int, headerBg: Int) {
        maxW = maxWidth
        availW = availWidth
        this.textColor = textColor
        this.borderColor = border
        this.headerBg = headerBg
    }

    /**
     * 2026-10-02 表格气泡宽度修复: 父链给 AT_MOST(全屏可用宽) 时按渲染上下文 maxW 封顶,
     * 与正文气泡 chatMaxW() 同宽(聊天模式=屏宽-120dp); 内容不足 maxW 时仍贴合内容宽。
     * (本环境 android.jar 的 View 层无 setMaxWidth, 只能 override onMeasure 限制测量 spec)
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var wSpec = widthMeasureSpec
        if (maxW > 0) {
            val mode = android.view.View.MeasureSpec.getMode(widthMeasureSpec)
            val size = android.view.View.MeasureSpec.getSize(widthMeasureSpec)
            if (mode == android.view.View.MeasureSpec.AT_MOST || mode == android.view.View.MeasureSpec.UNSPECIFIED) {
                if (size > maxW || mode == android.view.View.MeasureSpec.UNSPECIFIED) {
                    wSpec = android.view.View.MeasureSpec.makeMeasureSpec(maxW, android.view.View.MeasureSpec.AT_MOST)
                }
            }
        }
        super.onMeasure(wSpec, heightMeasureSpec)
    }

    /** 渲染块列表(幂等): 每次绑定前清空旧子块, 避免池化复用残留 */
    fun bindBlocks(blocks: List<MdRenderBlock>?) {
        removeAllViews()
        val list = blocks ?: return
        if (list.isEmpty()) return
        val t0 = System.nanoTime()
        for (b in list) {
            when (b) {
                is MdRenderBlock.TextBlock -> addView(makeTextBlock(b.spanned))
                is MdRenderBlock.TableBlock -> addView(makeTableBlock(b.table))
            }
        }
        val dtUs = (System.nanoTime() - t0) / 1000
        android.util.Log.i("PerfB", "bindBlocks blocks=${list.size} dtUs=$dtUs availW=$availW maxW=$maxW")
    }

    private fun makeTextBlock(spanned: CharSequence): TextView {
        return TextView(context).apply {
            text = spanned
            textSize = 15f
            setTextColor(textColor)
            setLineSpacing(dp(3f).toFloat(), 1f)
            includeFontPadding = false
            // 内外边距由块容器统一提供(与原气泡 TextView 一致), 文本块自身不再叠加 padding
            setPadding(0, 0, 0, 0)
            maxWidth = maxW
            // 2026-10-02: 不再启用系统文本选择(长按统一交给块容器弹原文本复制框, 与用户侧一致)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun makeTableBlock(table: MdTableData): MdTableView {
        return MdTableView(context).apply {
            bindTable(
                table,
                availW,
                resources.displayMetrics.density,
                resources.displayMetrics.scaledDensity,
                borderColor,
                headerBg,
                textColor,
                textColor   // 表头文字与正文同色(INPUT_BG 浅底不适合白字)
            )
            // 宽度改为内容贴合(WRAP_CONTENT): 窄表按内容宽渲染, 气泡内表格左右留白对称,
            // 不再被 availW 撑满导致窄表右侧大片空白; 宽表由父链 AT_MOST 约束为可用宽,
            // 超出部分仍走内部横滑(方案B保留横滑能力, 右半段不被裁)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // 表格块底部留 8dp 间距: 连续输出多个表格时, 上一表格外框底线与下一表格
                // 外框顶线不再紧贴重叠成一条, 视觉上表格间有明确间隔
                bottomMargin = dp(8f)
            }
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
