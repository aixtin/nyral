package io.github.aixtin.nyral

import android.content.Context
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 知识库展示层的时间文案: 今天/昨天/N 天前, 更早用本地化日期, 避免列表里全是精确到分的时间戳 */
object KnowledgeUi {

    fun rel(ctx: Context, ts: Long): String {
        if (ts <= 0L) return ""
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val days = daysBetween(now, then)
        return when {
            days == 0L -> ctx.getString(R.string.kb_time_today, shortTime(ctx, ts))
            days == 1L -> ctx.getString(R.string.kb_time_yesterday, shortTime(ctx, ts))
            days in 2L..6L -> ctx.getString(R.string.kb_time_days_ago, days)
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(ts))
        }
    }

    /** 审计动作的内部英文枚举 → 界面文案 */
    fun actionLabel(ctx: Context, action: String): String = when (action) {
        "create" -> ctx.getString(R.string.kb_action_create)
        "update" -> ctx.getString(R.string.kb_action_update)
        "delete" -> ctx.getString(R.string.kb_action_delete)
        "restore" -> ctx.getString(R.string.kb_action_restore)
        "purge" -> ctx.getString(R.string.kb_action_purge)
        else -> action
    }

    private fun shortTime(ctx: Context, ts: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(ts))

    /** 两个时间相差的自然日(今天=0, 昨天=1); 用零点比较, 避免 23:59 → 00:01 被算成同一天 */
    private fun daysBetween(now: Calendar, then: Calendar): Long {
        val a = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val b = (then.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return ((a.timeInMillis - b.timeInMillis).toDouble() / 86400000.0).roundToInt().toLong()
    }
}
