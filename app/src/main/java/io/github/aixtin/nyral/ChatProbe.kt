package io.github.aixtin.nyral

import org.json.JSONArray
import org.json.JSONObject

/**
 * 真机回归观测探针(2026-11-07)。
 * 只记录"用户眼睛能看到但代码拿不到"的 UI 侧状态(自绘气泡文本 uiautomator 抓不到),
 * 供 DebugServer /v1/chatstate 只读导出; 不参与任何渲染与业务判定, 无副作用。
 */
object ChatProbe {

    private const val MAX_EVENTS = 200

    private val lock = Any()
    private var roundStartMs = 0L
    private var roundDoneMs = 0L
    private var finishNote = ""
    private var cancelled = false
    // 缺陷2(2026-11-07): 错误文案此前只进临时错误卡、不入 messages, 回归无法断言"错误是否上屏", 这里单独留一份
    private var lastError = ""
    private var errorAtMs = 0L
    private var replyLen = 0
    private var pendingName = ""
    private var pendingBytes = 0
    private val pendingTs = ArrayList<Long>()
    private val pendingBytesSeq = ArrayList<Int>()

    /** 新一轮开始(仅调试注入路径显式调用, 手动发送不影响既有值) */
    fun onRoundStart() = synchronized(lock) {
        roundStartMs = System.currentTimeMillis()
        roundDoneMs = 0L
        finishNote = ""
        cancelled = false
        lastError = ""
        errorAtMs = 0L
        replyLen = 0
        pendingName = ""
        pendingBytes = 0
        pendingTs.clear()
        pendingBytesSeq.clear()
    }

    /** P2-1: 工具参数生成进度事件 */
    fun onPending(name: String, bytes: Int) = synchronized(lock) {
        pendingName = name
        pendingBytes = bytes
        pendingTs.add(System.currentTimeMillis())
        pendingBytesSeq.add(bytes)
        if (pendingTs.size > MAX_EVENTS) {
            pendingTs.removeAt(0)
            pendingBytesSeq.removeAt(0)
        }
    }

    /** 错误事件: 记录本轮最终展示给用户的错误文案(与气泡内容同源) */
    fun onError(msg: String) = synchronized(lock) {
        lastError = msg
        errorAtMs = System.currentTimeMillis()
    }

    /** 收尾事件: note 为追加到气泡的结束语(空串表示正常完成) */
    fun onFinish(note: String, wasCancelled: Boolean, reply: String) = synchronized(lock) {
        roundDoneMs = System.currentTimeMillis()
        finishNote = note
        cancelled = wasCancelled
        replyLen = reply.length
        pendingName = ""
        pendingBytes = 0
    }

    /** 收尾阶段补写 note(中断分支的文案在落库后才确定) */
    fun noteFinish(note: String) = synchronized(lock) { finishNote = note }

    fun snapshot(): JSONObject = synchronized(lock) {
        val tsArr = JSONArray()
        pendingTs.forEach { tsArr.put(it) }
        val bytesArr = JSONArray()
        pendingBytesSeq.forEach { bytesArr.put(it) }
        JSONObject()
            .put("round_start_ms", roundStartMs)
            .put("round_done_ms", roundDoneMs)
            .put("pending_name", pendingName)
            .put("pending_bytes", pendingBytes)
            .put("pending_count", pendingTs.size)
            .put("pending_ts", tsArr)
            .put("pending_bytes_seq", bytesArr)
            .put("finish_note", finishNote)
            .put("cancelled", cancelled)
            .put("reply_len", replyLen)
            .put("last_error", lastError)
            .put("error_at_ms", errorAtMs)
    }
}
