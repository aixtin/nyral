package io.github.aixtin.nyral

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具自热度统计(2026-09-14 新增) — 纯本地 SharedPreferences, 不上云。
 * 按 executeTool 命中频率 + 最近使用时间对工具排序, 实现"常用工具排前、冷门压缩描述"的上下文瘦身;
 * 冷启动无数据时保持注册默认顺序。
 * 防抖动: count 按时间衰减(每经过 decayDays 天乘以 decayFactor), 避免历史累计淹没最近使用。
 */
object ToolHotStore {

    data class HotStat(val count: Int, val lastTs: Long)

    private const val PREFS = "tool_hot"
    private const val KEY = "stats_json"
    /** 衰减窗口: 每经过 decayDays 天, count 乘以 decayFactor */
    private const val decayDays = 7.0
    private const val decayFactor = 0.6
    private val decayWindowMs: Long = (decayDays * 86400000L).toLong()

    /** 读取全量热度统计(含衰减计算后的 count) */
    fun load(context: Context): Map<String, HotStat> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            val now = System.currentTimeMillis()
            val out = LinkedHashMap<String, HotStat>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("n", "")
                if (name.isEmpty()) continue
                val lastTs = o.optLong("t", 0L)
                var count = o.optInt("c", 0)
                if (lastTs > 0) {
                    // 按整衰减窗口衰减(每 decayDays 天乘以 decayFactor), 避免微小时间差被 toInt 截断误伤
                    val windows = ((now - lastTs).coerceAtLeast(0L) / decayWindowMs).toInt()
                    var w = windows
                    while (w-- > 0) count = (count * decayFactor).toInt()
                }
                if (count > 0 || lastTs > 0) out[name] = HotStat(count, lastTs)
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 记录一次工具命中(调用次数 +1, 刷新最近使用时间) */
    fun recordHit(context: Context, name: String) {
        if (name.isBlank()) return
        val stats = load(context).toMutableMap()
        val cur = stats[name]
        val now = System.currentTimeMillis()
        stats[name] = HotStat((cur?.count ?: 0) + 1, now)
        save(context, stats)
    }

    private fun save(context: Context, stats: Map<String, HotStat>) {
        val arr = JSONArray()
        stats.entries.sortedByDescending { it.value.count }.forEach { (name, s) ->
            arr.put(JSONObject().apply {
                put("n", name)
                put("c", s.count)
                put("t", s.lastTs)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
