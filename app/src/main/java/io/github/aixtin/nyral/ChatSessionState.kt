package io.github.aixtin.nyral

/**
 * 会话流式状态机（MainActivity 状态机改造试点第一刀）:
 * 集中"请求-流式"生命周期状态 —— 请求代际、AI 输出阶段、正文跟随模式、流式行、
 * 表情掩码缓冲、AI 忙标记。MainActivity 不再直接操作裸状态字段,
 * 一律通过本类的迁移方法推进状态, 为后续事件分发(doSend/continueSend/onDone/onError)
 * 外移至控制器铺路。
 *
 * 代际语义(阶段2 流式竞态治理): 每次发起新请求/取消当前请求(切会话)自增,
 * 流式回调进入主线程后先校验 isCurrent(epoch), 天然拦截迟到回调。
 */
internal class ChatSessionState {
    /** 请求代际: 发起新请求/取消请求自增; 迟到回调以 isCurrent(epoch) 拦截 */
    private var requestEpoch = 0L

    /** AI 输出阶段: 0=idle 1=thinking 2=tool 3=content */
    var aiStage = 0
        private set

    /** 正文跟随模式: FAB 一键到底后 true; 正文默认停滚, 右下角出现一键到底 */
    var contentFollow = false
        private set

    /** 当前流式会话的 Streaming 行(收尾时移除重建为静态 AI 行, 防僵尸行重复渲染) */
    var streamingRow: ChatRow.Streaming? = null
        private set

    /** AI 表情流式掩码缓冲: 跨 delta 分片的未闭合 [表情: 尾巴 */
    var emojiMaskTail = ""
        private set

    /** AI 引擎忙标记: doSend 提前占位防连发穿透; 请求收尾归还 */
    var aiBusy = false
        private set

    val epoch: Long get() = requestEpoch

    /** 发起新请求(continueSend): 占忙 + 推进代际, 返回本轮代际供回调校验 */
    fun beginRequest(): Long {
        aiBusy = true
        return ++requestEpoch
    }

    /** 仅占忙不改代际(doSend 附件异步路径提前置位, 防快速连发穿透) */
    fun markBusy() {
        aiBusy = true
    }

    /** 请求结束(成功/失败/取消均走) */
    fun endRequest() {
        aiBusy = false
    }

    /** 取消/切换前清理流式 UI 状态: 与引擎是否在跑无关, 始终执行 */
    fun resetStreamUi() {
        streamingRow = null
        aiStage = 0
        contentFollow = false
    }

    /** 取消进行中请求: 推进代际使全部迟到回调失效 */
    fun bumpEpoch() {
        requestEpoch++
    }

    /** 回调代际校验: 非当前代际即迟到, 丢弃 */
    fun isCurrent(epoch: Long) = epoch == requestEpoch

    /** 挂载流式行(回收传送带末位) */
    fun attachStreaming(row: ChatRow.Streaming) {
        streamingRow = row
    }

    /** 摘除流式行(返回旧行, 供移除后重建静态 AI 行) */
    fun detachStreaming(): ChatRow.Streaming? = streamingRow.also { streamingRow = null }

    /** AI 阶段推进 */
    fun setAiStage(s: Int) {
        aiStage = s
    }

    /** 正文跟随模式 */
    fun setContentFollow(f: Boolean) {
        contentFollow = f
    }

    /** 掩码尾部写入(每次掩码处理后) */
    fun setMaskTail(tail: String) {
        emojiMaskTail = tail
    }
}
