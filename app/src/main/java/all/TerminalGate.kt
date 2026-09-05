package io.github.aixtin.droidagent

import android.app.Activity
import android.app.Application

/**
 * 悬浮终端显隐门控 + 前台窗口状态(从 MainActivity 拆分):
 * - DA 在前台(任意页面前台)隐藏悬浮终端, 切到其他 APP/回桌面自动显示
 * - 记录当前前台 Activity 简单类名, 供调试服务查询
 * 均为进程级静态状态, 不依赖任何 Activity 实例, 故独立成 object。
 */
object TerminalGate {
    // 悬浮终端显隐门控: 进程级只注册一次(避免 MainActivity 重建重复注册)
    private var terminalGateRegistered = false
    // 当前 DA 已 resumed 的页面数(>0 即 DA 在前台)
    private var terminalResumedCount = 0
    // 显示防抖: 页面切换时旧页面 pause(计数--至0)与新页面 resume(计数++)存在间隙,
    // 若 pause 瞬间立即显示会闪一下; 延迟确认仍无 DA 页面在前台才真正显示
    private val terminalHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private const val TERMINAL_SHOW_DEBOUNCE_MS = 350L
    private var terminalShowRunnable: Runnable? = null
    // 当前 RESUMED Activity 简单类名(供调试服务查询前台窗口; null 表示后台/无窗口)
    @Volatile
    private var resumedActivityName: String? = null

    /** DA 是否有页面在前台 */
    @JvmStatic
    fun daInForeground(): Boolean = terminalResumedCount > 0

    /** 当前前台 Activity 简单类名(无前台则为 null) */
    @JvmStatic
    fun foregroundActivityName(): String? = resumedActivityName

    /** 服务启动/其他入口需要按当前前台状态校正悬浮窗显隐时调用 */
    @JvmStatic
    fun refreshTerminalOverlay() {
        if (terminalResumedCount > 0) {
            cancelTerminalShow()
            AITerminalService.setOverlayVisible(false)
        } else {
            scheduleTerminalShow()
        }
    }

    /**
     * 进程级注册一次前台状态监听(ActivityLifecycleCallbacks)。
     * 由 MainActivity.onCreate 调用; MainActivity 重建时因 registered 标志已置位而跳过重复注册。
     */
    @JvmStatic
    fun register(application: Application) {
        if (terminalGateRegistered) return
        terminalGateRegistered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                terminalResumedCount++
                resumedActivityName = a.javaClass.simpleName
                // 页面切换过程中(旧页 pause~新页 resume 间隙)可能已排入显示任务, 立即取消防抖并隐藏
                cancelTerminalShow()
                AITerminalService.setOverlayVisible(false)
            }
            override fun onActivityPaused(a: Activity) {
                terminalResumedCount--
                if (a.javaClass.simpleName == resumedActivityName) resumedActivityName = null
                // 不立即显示: 走防抖, 避免 DA 内页面切换时闪一下(延迟后确认无 DA 页面在前台才显示)
                if (terminalResumedCount <= 0) scheduleTerminalShow()
            }
            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
            override fun onActivityDestroyed(a: Activity) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
        })
    }

    /** 延迟显示悬浮窗(防抖): 若期间有 DA 页面 resumed 则取消 */
    private fun scheduleTerminalShow() {
        terminalShowRunnable?.let { terminalHandler.removeCallbacks(it) }
        val r = Runnable {
            terminalShowRunnable = null
            AITerminalService.setOverlayVisible(true)
        }
        terminalShowRunnable = r
        terminalHandler.postDelayed(r, TERMINAL_SHOW_DEBOUNCE_MS)
    }

    private fun cancelTerminalShow() {
        terminalShowRunnable?.let { terminalHandler.removeCallbacks(it) }
        terminalShowRunnable = null
    }
}
