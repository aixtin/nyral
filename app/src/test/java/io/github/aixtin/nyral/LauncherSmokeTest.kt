package io.github.aixtin.nyral

import android.os.Build
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 冷启动冒烟（2026-11-16，第二版）。
 *
 * 目的：在没有模拟器/真机的 CI 里，用 Robolectric 真跑一次
 * Application.onCreate + 桌面入口 GuideActivity.onCreate/onResume + 主界面 onCreate，
 * 把"安装后一打开就闪退"里属于代码层的部分（NPE、资源缺失、ClassCast、初始化异常）挡在构建期。
 *
 * 第二版的改动（对应 review 回执）：
 * ① U7 修完后主界面用例不再 skip —— 用 @Config(sdk = [29]) 在 minSdk 上硬断言跑完 onCreate；
 * ② 顺带把 minSdk 这层压住 —— LocalDate.ofInstant 这类"高版本才有的方法"正好在 29 上炸；
 * ③ 不再用 assumeTrue 兜 AIOOBE：API 34 上的 ArrayIndexOutOfBoundsException 来自 Robolectric
 *    自己没 shadow Environment.isExternalStorageManager()，改用 29 既避开框架缺口，
 *    又覆盖真实最低系统版本。
 *
 * 覆盖不到：native so 实际加载（只断言"缺 so 时不抛"）、厂商 ROM 行为、真实权限与 root 探测，
 * 以及 R8 之后的反射行为（另有 assembleRelease + lintVitalRelease 把关）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LauncherSmokeTest {

    @Test
    fun `Application 初始化不抛异常`() {
        val app = ApplicationProvider.getApplicationContext<NyralApp>()
        assertNotNull("Application 未创建", app)
        assertNotNull("包名为空", app.packageName)
        // 用例跑在 minSdk 上，顺带把"高版本 API 被误用"这一类压住
        assertEquals("冒烟用例应固定在 minSdk 上跑", 29, Build.VERSION.SDK_INT)
    }

    @Test
    fun `桌面入口 GuideActivity 能打开且不是白屏`() {
        val controller = Robolectric.buildActivity(GuideActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull("GuideActivity 未创建", activity)
        val decor = activity.window.decorView
        assertNotNull("decorView 为空(窗口未建立)", decor)
        // 两条合法路径：① 首启停在引导页且内容视图已挂载；② 老用户直接透传并 finish 自己
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val hasContent = content != null && content.childCount > 0
        assertTrue("引导页既没渲染内容也没透传进主界面", hasContent || activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun `MainActivity 冷启动不崩`() {
        // 第二版：硬断言，不再 assumeTrue 跳过。
        // U7 修完后 MemoryEmbedder 的 OrtEnvironment 是 by lazy + runCatching，
        // JVM 里没有 arm64 so 也只降级成"模型未就绪"，不会把 UnsatisfiedLinkError 抛到 onCreate。
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().get()
        assertNotNull("MainActivity 未创建", activity)
        assertFalse("MainActivity 启动后立刻 finish(疑似启动失败)", activity.isFinishing)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertTrue("主界面内容视图未挂载", content != null && content.childCount > 0)
        controller.pause().stop().destroy()
    }

    @Test
    fun `没有 native 库时 MemoryEmbedder 降级而不抛`() {
        val app = ApplicationProvider.getApplicationContext<NyralApp>()
        val embedder = MemoryEmbedder(app)
        // 缺 so / 缺模型文件都必须是"未就绪"，不是异常
        assertFalse("无 native 库时 reload() 应返回 false", embedder.reload())
        assertFalse("未就绪时 isReady() 应为 false", embedder.isReady())
    }
}
