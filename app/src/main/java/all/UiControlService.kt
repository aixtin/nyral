package io.github.aixtin.nyral

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Environment
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍控制服务(原型): AI 控制第三方 App 的免 root 通道。
 * 依赖系统无障碍授权(系统设置->无障碍->已安装的服务->Nyral)。
 * 开启后读取前台窗口节点树, 提供:
 *   - app_scan    扫描当前屏幕可操作元素清单(索引+文本+坐标)
 *   - app_click   点击第 N 个元素
 *   - app_text    向第 N 个输入框输入文本
 *   - app_back    模拟返回键
 *   - app_home    回到桌面
 *   - app_launch  按包名启动第三方 App
 * 由 LocalEngine 工具桥接调用(companion 单例), 与 BrowserPage 桥接同一模式。
 */
class UiControlService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: UiControlService? = null

        /** 服务是否已开启(用于 UI 提示引导) */
        val isRunning: Boolean get() = instance != null

        private const val NOT_READY =
            "无障碍服务未开启: 请到 系统设置 -> 无障碍 -> 已安装的服务 里开启 Nyral"

        /** 扫描当前屏幕可操作元素清单(带 [索引] 文本/描述 坐标), 供 app_click/app_text 定位;
         *  空树时返回 EMPTY_TREE 前缀标记, 触发大脑切视觉兜底(app_screenshot+app_tap 闭环) */
        fun scan(): String = instance?.collectElements() ?: NOT_READY

        /** 坐标注入点击(免节点树, 供视觉兜底: 空树/自绘页面直接按屏幕坐标点) */
        fun tap(x: Int, y: Int): String = instance?.tapAt(x, y) ?: NOT_READY

        /** 截图当前屏幕保存到 app 私有目录(filesDir/截图), 返回文件路径; 供视觉模型识别页面元素(M4 私有化) */
        fun screenshot(): String = instance?.captureScreen() ?: NOT_READY

        /** 点击第 index 个可操作元素(索引来自 app_scan) */
        fun click(index: Int): String = instance?.clickAt(index) ?: NOT_READY

        /** 向第 index 个可输入元素输入文本 */
        fun type(index: Int, text: String): String = instance?.typeAt(index, text) ?: NOT_READY

        /** 模拟系统返回键 */
        fun back(): String {
            val s = instance ?: return NOT_READY
            return if (s.performGlobalAction(GLOBAL_ACTION_BACK)) "已执行返回键" else "返回键执行失败(可能不在前台)"
        }

        /** 回到桌面 */
        fun home(): String {
            val s = instance ?: return NOT_READY
            return if (s.performGlobalAction(GLOBAL_ACTION_HOME)) "已回到桌面" else "回到桌面失败"
        }

        /** 按包名启动第三方 App */
        fun launch(context: Context, pkg: String): String {
            val s = instance ?: return NOT_READY
            val intent = context.packageManager.getLaunchIntentForPackage(pkg.trim())
                ?: return "未找到包名 $pkg 的应用(可先调 app_installed 查看已装应用)"
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                s.startActivity(intent)
                return "已启动 $pkg"
            } catch (e: Exception) {
                return "启动 $pkg 失败: ${e.message}"
            }
        }

        /** 列出已安装的第三方应用(包名+应用名), 供 app_launch 定位包名 */
        fun installed(context: Context): String {
            val pm = context.packageManager
            val list = pm.getInstalledApplications(0)
                .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                .mapNotNull { a ->
                    val n = pm.getApplicationLabel(a).toString()
                    "$n|${a.packageName}"
                }
                .sorted()
            if (list.isEmpty()) return "未找到第三方应用"
            return list.joinToString("\n").let { if (it.length > 3000) it.take(3000) + "\n...(已截断)" else it }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 原型阶段不做事件预扫: app_scan 时现取节点树, 保证拿到的就是当前屏幕
    }

    override fun onInterrupt() {
    }

    // ================= 扫描与操作实现 =================

    /** 单条可操作元素 */
    private data class El(val label: String, val node: AccessibilityNodeInfo, val rect: Rect)

    /** 最近一次扫描结果, 供 click/type 定位(节点不跨窗口保留, 每次操作前重新扫描更稳) */
    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

    private fun collectElements(): String {
        var result = collectElementsOnce()
        // 加载瞬间可达树退化: 整屏只剩一个 WebView 节点判为页面加载中, 短暂等待后重采(最多 3 次)而非直接结案
        var tries = 0
        while (tries < 3 && isDegradedWebViewTree(result)) {
            tries++
            try { Thread.sleep(400) } catch (e: InterruptedException) { }
            result = collectElementsOnce()
        }
        if (tries > 0 && isDegradedWebViewTree(result)) result += "\n(提示: 页面可能仍在加载, 可稍后重扫)"
        return result
    }

    /** 判定采集结果是否退化: 只有一个元素且是 WebView(页面加载瞬间常见) */
    private fun isDegradedWebViewTree(result: String): Boolean {
        if (!result.startsWith("[0] ")) return false
        if (result.contains("\n[1]")) return false
        val nl = result.indexOf('\n')
        val first = if (nl < 0) result else result.substring(0, nl)
        return first.contains("WebView")
    }

    private fun collectElementsOnce(): String {
        val root = rootInActiveWindow ?: return "当前无前台窗口可读取(请确认已开启无障碍且停留在目标 App 页面)"
        val found = ArrayList<El>()
        walk(root, found, 0)
        lastNodes = found.map { it.node }
        if (found.isEmpty()) return "EMPTY_TREE 当前屏幕未识别到可操作元素(可能页面元素不支持无障碍访问, 请改用 app_screenshot 截图+视觉识别+app_tap 坐标点击闭环)"
        val sb = StringBuilder()
        found.forEachIndexed { i, e ->
            val cx = e.rect.centerX()
            val cy = e.rect.centerY()
            val label = e.label.ifBlank { "<无文本>" }
            sb.append("[${i}] $label (${e.rect.width()}x${e.rect.height()} @$cx,$cy)\n")
        }
        return sb.toString().trimEnd()
    }

    /** DFS 收集可操作元素: clickable/longClickable/checkable/输入框/按钮类, 限可见+有尺寸, 最多 30 条 */
    private fun walk(node: AccessibilityNodeInfo, out: ArrayList<El>, depth: Int) {
        if (depth > 60 || out.size >= 30) return
        if (node.isVisibleToUser && node.isEnabled) {
            val r = Rect()
            node.getBoundsInScreen(r)
            val w = r.width(); val h = r.height()
            if (w > 0 && h > 0 && isActionable(node)) {
                out.add(El(labelOf(node), node, r))
            }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            walk(c, out, depth + 1)
        }
    }

    /** 判定节点是否可操作(可点击/可勾选/输入框/按钮/链接) */
    private fun isActionable(n: AccessibilityNodeInfo): Boolean {
        if (n.isClickable || n.isLongClickable || n.isCheckable || n.isEditable) return true
        val cls = n.className?.toString()?.substringAfterLast('.') ?: ""
        return cls == "Button" || cls == "ImageButton" || cls == "CheckBox" ||
                cls == "RadioButton" || cls == "Switch" || cls == "ToggleButton" ||
                cls == "EditText"
    }

    /** "[object Object]"类字符串化伪影(站点把 JS 对象插值进 aria-label/正文, WebView 无障碍名按 aria-label 优先会原样透传) */
    private val objectArtifact = Regex("\\[object[^\\]]*\\]")

    /** 提取元素可读标签: 文本 > 内容描述 > viewId > 类名; 先剥伪影再判空, 防垃圾标签顶替真实文本 */
    private fun labelOf(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString()?.replace(objectArtifact, "")?.trim().orEmpty()
        if (t.isNotBlank()) return t
        val d = n.contentDescription?.toString()?.replace(objectArtifact, "")?.trim().orEmpty()
        if (d.isNotBlank()) return d
        val id = n.viewIdResourceName?.substringAfterLast('/') ?: ""
        if (id.isNotBlank()) return id
        return n.className?.toString()?.substringAfterLast('.') ?: "未知元素"
    }

    private fun tapAt(x: Int, y: Int): String {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = try { dispatchGesture(gesture, null, null) } catch (e: Exception) { false }
        return if (ok) "已坐标点击 ($x,$y)" else "坐标点击失败(无障碍手势不可用)"
    }

    private fun captureScreen(): String {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            return "截图需要 Android 11+ (当前 SDK ${android.os.Build.VERSION.SDK_INT})"
        }
        val latch = CountDownLatch(1)
        var shot: AccessibilityService.ScreenshotResult? = null
        var fail: String? = null
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        shot = screenshot
                        latch.countDown()
                    }
                    override fun onFailure(errorCode: Int) {
                        fail = "截图失败 errorCode=$errorCode(可能页面受限或被遮挡)"
                        latch.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            return "截图异常: ${e.message}"
        }
        try { latch.await(3, TimeUnit.SECONDS) } catch (e: Exception) {}
        val r = shot ?: return fail ?: "截图超时"
        val bmp = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
            ?: run { r.hardwareBuffer.close(); return "截图解码失败" }
        try {
            val dir = File(filesDir, WorkDir.SUB_DIR_SHOTS) // M4: 截图私有化, 存 app 私有目录
            dir.mkdirs()
            val f = File(dir, "app_screenshot_${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            return "截图已保存: ${f.absolutePath}"
        } catch (e: Exception) {
            return "截图保存失败: ${e.message}"
        } finally {
            bmp.recycle()
            r.hardwareBuffer.close()
        }
    }

    private fun clickAt(index: Int): String {
        val node = lastNodes.getOrNull(index)
            ?: return "索引越界(可用 app_scan 刷新最新清单)"
        val ok = try { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (e: Exception) { false }
        return if (ok) "已点击第 $index 个元素" else "点击失败(元素可能已失效, 可重新 app_scan)"
    }

    private fun typeAt(index: Int, text: String): String {
        val node = lastNodes.getOrNull(index)
            ?: return "索引越界(可用 app_scan 刷新最新清单)"
        if (!node.isEditable) return "第 $index 个元素不是输入框, 无法输入(请重新 app_scan 确认)"
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            return if (ok) "已输入文本到第 $index 个输入框" else "输入失败"
        } catch (e: Exception) {
            return "输入异常: ${e.message}"
        }
    }
}
