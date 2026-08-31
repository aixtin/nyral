package io.github.aixtin.droidagent

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Token 用量统计: 累计(全部) + 当日, 按天滚动。数据本地存储, 不落第三方 */
object TokenStore {
    private const val PREFS = "token_stats"
    private const val K_TOTAL_PROMPT = "total_prompt"
    private const val K_TOTAL_COMPLETION = "total_completion"
    private const val K_COUNT = "count"
    private const val K_DAY = "day"
    private const val K_DAY_PROMPT = "day_prompt"
    private const val K_DAY_COMPLETION = "day_completion"
    private const val K_LAST_PROMPT = "last_prompt"
    private const val K_SESSION_KEYS = "session_keys"
    private const val SESS_PREFIX = "sess_"
    private const val MAX_SESSIONS = 30

    // ============ 辅助 AI（记忆辅助模型）独立通道 ============
    private const val A_PREFIX = "aux_"
    private const val K_A_TOTAL_PROMPT = A_PREFIX + "total_prompt"
    private const val K_A_TOTAL_COMPLETION = A_PREFIX + "total_completion"
    private const val K_A_COUNT = A_PREFIX + "count"
    private const val K_A_DAY = A_PREFIX + "day"
    private const val K_A_DAY_PROMPT = A_PREFIX + "day_prompt"
    private const val K_A_DAY_COMPLETION = A_PREFIX + "day_completion"

    /** MainActivity 发送前设置, LocalEngine.record 内部读取, 用于会话维度累计 */
    @Volatile var currentSessionId: Long? = null

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(c: Context, prompt: Int, completion: Int) {
        if (prompt <= 0 && completion <= 0) return
        val p = prefs(c)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val day = p.getString(K_DAY, "")
        val dayPrompt = if (day == today) p.getLong(K_DAY_PROMPT, 0) else 0L
        val dayCompletion = if (day == today) p.getLong(K_DAY_COMPLETION, 0) else 0L
        val e = p.edit()
        e.putLong(K_TOTAL_PROMPT, p.getLong(K_TOTAL_PROMPT, 0) + prompt)
            .putLong(K_TOTAL_COMPLETION, p.getLong(K_TOTAL_COMPLETION, 0) + completion)
            .putLong(K_COUNT, p.getLong(K_COUNT, 0) + 1)
            .putString(K_DAY, today)
            .putLong(K_DAY_PROMPT, dayPrompt + prompt)
            .putLong(K_DAY_COMPLETION, dayCompletion + completion)
        // 最近一次请求的上下文 token 数(实际发送给模型的 prompt)
        if (prompt > 0) e.putLong(K_LAST_PROMPT, prompt.toLong())
        // 会话维度累计: 发送前由 MainActivity 设置 currentSessionId
        val sid = currentSessionId
        if (sid != null) {
            val pk = SESS_PREFIX + sid + "_prompt"
            val ck = SESS_PREFIX + sid + "_completion"
            e.putLong(pk, p.getLong(pk, 0) + prompt)
            e.putLong(ck, p.getLong(ck, 0) + completion)
            val keys = HashSet(p.getStringSet(K_SESSION_KEYS, emptySet()) ?: emptySet())
            keys.add(sid.toString())
            if (keys.size > MAX_SESSIONS) {
                // 修复: 原按字符串字典序淘汰("10"<"9" 先删 9), 会误删新会话保留老会话;
                // 改按数字 id 升序, 优先淘汰最老会话
                val victims = keys.sortedBy { it.toLongOrNull() ?: Long.MAX_VALUE }.take(keys.size - MAX_SESSIONS)
                for (v in victims) {
                    e.remove(SESS_PREFIX + v + "_prompt")
                    e.remove(SESS_PREFIX + v + "_completion")
                    keys.remove(v)
                }
            }
            e.putStringSet(K_SESSION_KEYS, keys)
        }
        e.apply()
    }

    /** 最近一次请求的上下文消耗(prompt tokens) */
    fun lastPrompt(c: Context): Long = prefs(c).getLong(K_LAST_PROMPT, 0)

    // ============ 辅助 AI（记忆辅助模型）统计 ============

    /** 辅助 AI 消耗记录：MemoryKeeper 生成主题索引等后台调用成功后写入 */
    fun recordAux(c: Context, prompt: Int, completion: Int) {
        if (prompt <= 0 && completion <= 0) return
        val p = prefs(c)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val day = p.getString(K_A_DAY, "")
        val dayPrompt = if (day == today) p.getLong(K_A_DAY_PROMPT, 0) else 0L
        val dayCompletion = if (day == today) p.getLong(K_A_DAY_COMPLETION, 0) else 0L
        p.edit()
            .putLong(K_A_TOTAL_PROMPT, p.getLong(K_A_TOTAL_PROMPT, 0) + prompt)
            .putLong(K_A_TOTAL_COMPLETION, p.getLong(K_A_TOTAL_COMPLETION, 0) + completion)
            .putLong(K_A_COUNT, p.getLong(K_A_COUNT, 0) + 1)
            .putString(K_A_DAY, today)
            .putLong(K_A_DAY_PROMPT, dayPrompt + prompt)
            .putLong(K_A_DAY_COMPLETION, dayCompletion + completion)
            .apply()
    }

    data class AuxStats(
        val totalPrompt: Long,
        val totalCompletion: Long,
        val count: Long,
        val dayPrompt: Long,
        val dayCompletion: Long
    ) {
        val total: Long get() = totalPrompt + totalCompletion
        val day: Long get() = dayPrompt + dayCompletion
    }

    fun auxStats(c: Context): AuxStats {
        val p = prefs(c)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val sameDay = p.getString(K_A_DAY, "") == today
        return AuxStats(
            p.getLong(K_A_TOTAL_PROMPT, 0),
            p.getLong(K_A_TOTAL_COMPLETION, 0),
            p.getLong(K_A_COUNT, 0),
            if (sameDay) p.getLong(K_A_DAY_PROMPT, 0) else 0L,
            if (sameDay) p.getLong(K_A_DAY_COMPLETION, 0) else 0L
        )
    }

    /** 会话维度累计 (prompt, completion); 无会话或未产生记录时为 0 */
    fun sessionStats(c: Context, sessionId: Long?): Pair<Long, Long> {
        if (sessionId == null) return 0L to 0L
        val p = prefs(c)
        return p.getLong(SESS_PREFIX + sessionId + "_prompt", 0) to
            p.getLong(SESS_PREFIX + sessionId + "_completion", 0)
    }

    data class Stats(
        val totalPrompt: Long,
        val totalCompletion: Long,
        val count: Long,
        val dayPrompt: Long,
        val dayCompletion: Long
    ) {
        val total: Long get() = totalPrompt + totalCompletion
        val day: Long get() = dayPrompt + dayCompletion
    }

    fun stats(c: Context): Stats {
        val p = prefs(c)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val sameDay = p.getString(K_DAY, "") == today
        return Stats(
            p.getLong(K_TOTAL_PROMPT, 0),
            p.getLong(K_TOTAL_COMPLETION, 0),
            p.getLong(K_COUNT, 0),
            if (sameDay) p.getLong(K_DAY_PROMPT, 0) else 0L,
            if (sameDay) p.getLong(K_DAY_COMPLETION, 0) else 0L
        )
    }

    fun clear(c: Context) {
        prefs(c).edit().clear().apply()
    }
}
