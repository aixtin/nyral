package io.github.aixtin.nyral

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * 消息行模型: id 为 DiffUtil item 稳定锚点(对齐 assistant 反编译结论)。
 * 历史行 id=DB 全局 seq(sessionBaseSeq+i); 运行期新行用递减临时 id; 阶段2再拆分 content 分片。
 */
internal sealed class ChatRow(val id: Long) {
    // data class: areContentsTheSame 才能真正比较内容, 避免全量重建时所有行被误判 changed 触发整表重绘闪跳
    data class User(val rowId: Long, val content: String) : ChatRow(rowId)
    data class Ai(val rowId: Long, val content: String, val rendered: String = "") : ChatRow(rowId)
    data class AiRich(val rowId: Long, val thinking: String, val content: String, val tools: String, val timeline: String, val rendered: String = "") : ChatRow(rowId)
    data class Sys(val rowId: Long, val text: String) : ChatRow(rowId)
    /** 新会话开场介绍卡片行 */
    data class Welcome(val rowId: Long) : ChatRow(rowId)
    /** 阶段4 时间标签行: 首条消息或与上条消息间隔>=30分钟时插入(数据层保留 ts, 纯展示层分组) */
    data class TimeTag(val rowId: Long, val text: String) : ChatRow(rowId)
    /** 流式占位行: 持有 AiBubbleHolder, 气泡盒挂在 item 容器上; 内容变化由 holder 内部驱动, 不走 DiffUtil 重绘 */
    class Streaming(val rowId: Long, val holder: AiBubbleHolder) : ChatRow(rowId) {
        var bubbleBox: View? = null
    }
    /** 危险工具安全确认行(2026-10-04): 待确认申请的气泡行; 决策后留态(已允许/已拒绝/超时拒绝) */
    data class SecurityConfirm(
        val rowId: Long,
        val requestId: String,
        val tool: String,
        val arg: String,
        val display: String?,
        val status: String,   // PENDING / ALLOWED / REJECTED / TIMEOUT
        val risk: String = "HIGH"
    ) : ChatRow(rowId)
}

/**
 * 消息列表适配器: ListAdapter + DiffUtil 增量。
 * areItemsTheSame 只比 id, areContentsTheSame 整体 equals(Streaming 恒 true, 避免流式内容触发重绘)。
 */
internal class ChatAdapter(
    private val rows: MutableList<ChatRow>,
    private val buildRow: (ChatRow) -> View,
    private val poolType: (ChatRow) -> Int = { PT_NONE },
    private val bindView: (View, ChatRow) -> Unit = { _, _ -> }
) : ListAdapter<ChatRow, ChatAdapter.VH>(DIFF) {

    class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root)

    var recyclerView: RecyclerView? = null

    /**
     * 形态 View 池（2026-09-28 滑动丝滑优化）：
     * 此前 onBindViewHolder 每次 removeAllViews + buildRow 重建整棵 View 树，
     * ViewHolder 复用被完全绕过——滚出滚回的行每帧都在 new TextView/GradientDrawable/
     * LayoutParams + measure/layout，是 120Hz 下"不够丝滑"的最大成本。
     * 现在按形态池化：同形态行复用同一 View 树，bind 只原地更新内容（setText 等）。
     * 注意：缓存的 View 必须 parent==null 才可复用（removeAllViews 已 detach）；
     * 若仍挂在旧 holder（异常时序）则走新建并覆盖池，保证不崩不串。
     */
    private val viewPool = HashMap<Int, View>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false   // 行内气泡阴影不被裁剪
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = getItem(position)
        holder.root.removeAllViews()
        if (row is ChatRow.Streaming) {
            row.bubbleBox?.let { box ->
                (box.parent as? ViewGroup)?.removeView(box)
                holder.root.addView(box)
            }
            return
        }
        val pt = poolType(row)
        if (pt >= 0) {
            val cached = viewPool[pt]
            if (cached != null && cached.parent == null) {
                holder.root.addView(cached)
                bindView(cached, row)
                return
            }
        }
        val nv = buildRow(row)
        holder.root.addView(nv)
        if (pt >= 0) viewPool[pt] = nv
    }

    /** 清理形态池（全量重建/会话切换时避免旧 View 树滞留） */
    fun clearPool() {
        viewPool.clear()
    }

    /** 追加单条(用户/系统/流式行): 同步维护外部列表 + submitList 增量 diff; onCommitted 在 diff 提交后回调 */
    fun add(row: ChatRow, onCommitted: (() -> Unit)? = null) {
        rows.add(row)
        submitList(rows.toList()) { onCommitted?.invoke() }
    }

    /** 头部插入(窗口化提示行等); onCommitted 在 diff 提交后回调 */
    fun insert(index: Int, row: ChatRow, onCommitted: (() -> Unit)? = null) {
        rows.add(index.coerceIn(0, rows.size), row)
        submitList(rows.toList()) { onCommitted?.invoke() }
    }

    /** 全量替换(会话打开/头像刷新/窗口外回退共用); onCommitted 在 diff 提交后回调 */
    fun submit(list: List<ChatRow>, onCommitted: (() -> Unit)? = null) {
        rows.clear()
        rows.addAll(list)
        submitList(rows.toList()) { onCommitted?.invoke() }
    }

    /** 移除单条(流式行收尾等): 同步维护外部列表 + submitList 增量 diff */
    fun remove(row: ChatRow) {
        val i = rows.indexOf(row)
        if (i < 0) return
        rows.removeAt(i)
        submitList(rows.toList())
    }

    /** 流式行气泡盒挂到指定 position 的 item 容器(submit 异步 diff 完成后布局就绪再挂) */
    fun attachStreaming(position: Int) {
        recyclerView?.post {
            val vh = recyclerView?.findViewHolderForAdapterPosition(position) as? VH ?: return@post
            val row = getItem(position) as? ChatRow.Streaming ?: return@post
            val box = row.bubbleBox ?: return@post
            vh.root.removeAllViews()
            (box.parent as? ViewGroup)?.removeView(box)
            vh.root.addView(box)
        }
    }

    /** 定位命中行短暂高亮(搜索跳转) */
    fun highlightRow(row: ChatRow?) {
        if (row == null) return
        val pos = rows.indexOf(row)
        if (pos < 0) return
        recyclerView?.post {
            val holder = recyclerView?.findViewHolderForAdapterPosition(pos) ?: return@post
            val root = holder.itemView
            val orig = root.alpha
            root.animate().alpha(0.25f).setDuration(180).withEndAction {
                root.animate().alpha(orig).setDuration(500).start()
            }.start()
        }
    }

    companion object {
        const val PT_NONE = -1
        const val PT_USER_TEXT = 0
        const val PT_AI_TEXT = 1
        const val PT_SYS = 2
        const val PT_TAG = 3
        const val PT_WELCOME = 4
        const val PT_AI_RICH = 5

        private val DIFF = object : DiffUtil.ItemCallback<ChatRow>() {
            override fun areItemsTheSame(a: ChatRow, b: ChatRow) = a.id == b.id
            override fun areContentsTheSame(a: ChatRow, b: ChatRow): Boolean {
                // 流式行内容由 holder 内部 view 驱动, 不参与内容比较
                if (a is ChatRow.Streaming || b is ChatRow.Streaming) return true
                return a == b
            }
        }
    }
}
