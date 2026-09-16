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
    class User(id: Long, val content: String) : ChatRow(id)
    class Ai(id: Long, val content: String) : ChatRow(id)
    class AiRich(id: Long, val thinking: String, val content: String, val tools: String, val timeline: String) : ChatRow(id)
    class Sys(id: Long, val text: String) : ChatRow(id)
    /** 新会话开场介绍卡片行 */
    class Welcome(id: Long) : ChatRow(id)
    /** 阶段4 时间标签行: 首条消息或与上条消息间隔>=30分钟时插入(数据层保留 ts, 纯展示层分组) */
    class TimeTag(id: Long, val text: String) : ChatRow(id)
    /** 流式占位行: 持有 AiBubbleHolder, 气泡盒挂在 item 容器上; 内容变化由 holder 内部驱动, 不走 DiffUtil 重绘 */
    class Streaming(id: Long, val holder: AiBubbleHolder) : ChatRow(id) {
        var bubbleBox: View? = null
    }
}

/**
 * 消息列表适配器: ListAdapter + DiffUtil 增量。
 * areItemsTheSame 只比 id, areContentsTheSame 整体 equals(Streaming 恒 true, 避免流式内容触发重绘)。
 */
internal class ChatAdapter(
    private val rows: MutableList<ChatRow>,
    private val buildRow: (ChatRow) -> View
) : ListAdapter<ChatRow, ChatAdapter.VH>(DIFF) {

    class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root)

    var recyclerView: RecyclerView? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
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
        } else {
            holder.root.addView(buildRow(row))
        }
    }

    /** 追加单条(用户/系统/流式行): 同步维护外部列表 + submitList 增量 diff; onCommitted 在 diff 提交后回调 */
    fun add(row: ChatRow, onCommitted: (() -> Unit)? = null) {
        rows.add(row)
        submitList(rows.toList(), onCommitted)
    }

    /** 头部插入(窗口化提示行等); onCommitted 在 diff 提交后回调 */
    fun insert(index: Int, row: ChatRow, onCommitted: (() -> Unit)? = null) {
        rows.add(index.coerceIn(0, rows.size), row)
        submitList(rows.toList(), onCommitted)
    }

    /** 全量替换(会话打开/头像刷新/窗口外回退共用); onCommitted 在 diff 提交后回调 */
    fun submit(list: List<ChatRow>, onCommitted: (() -> Unit)? = null) {
        rows.clear()
        rows.addAll(list)
        submitList(rows.toList(), onCommitted)
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
