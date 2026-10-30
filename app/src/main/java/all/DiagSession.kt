package io.github.aixtin.nyral

import android.content.Context

/**
 * 手机体检会话(2026-10-30 P2)
 *
 * 设计取舍:
 *  - 连续多次体检复用同一条 kind=2 会话, 不新建——避免体检一次列表多一条, 挤掉普通对话;
 *  - 报告正文不写进会话, 会话里只留一条摘要卡; 完整明细落 diag_archive 档案, 按 archive_id 回查;
 *  - 会话消息超 40 条滚动丢弃最早(数据在档案里, 丢消息不丢报告)。
 */
object DiagSession {
    const val TITLE = "手机体检"
    const val MAX_MSGS = 40

    /** 取/建体检会话, 返回会话 id; 已存在则续用(不新建) */
    fun openOrResume(context: Context, mode: Int): Long {
        val db = MemoryDb(context)
        val exist = db.findSessionByKind(MemoryDb.KIND_DIAG, mode)
        if (exist != null && exist > 0L) return exist
        return db.saveSession(TITLE, emptyList(), mode, MemoryDb.KIND_DIAG)
    }

    /** 往体检会话追加一轮摘要卡; 失败只记日志, 不影响体检页 */
    fun appendReport(context: Context, sessionId: Long, mode: Int, card: String) {
        if (sessionId <= 0L) return
        try {
            val db = MemoryDb(context)
            val msgs = db.loadSessionMessages(sessionId).toMutableList()
            val ts = System.currentTimeMillis()
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(ts))
            msgs.add(MemoryDb.SessionMsg("user", "体检一次", ts = ts, date = date))
            msgs.add(MemoryDb.SessionMsg("assistant", card, ts = ts, date = date))
            val trimmed = if (msgs.size > MAX_MSGS) msgs.subList(msgs.size - MAX_MSGS, msgs.size).toList() else msgs
            db.updateSession(sessionId, db.sessionTitleOf(sessionId) ?: TITLE, trimmed, mode)
        } catch (e: Exception) {
            android.util.Log.w("DiagSession", "写体检会话失败: " + e.message)
        }
    }
}
