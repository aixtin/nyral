package io.github.aixtin.nyral

import android.graphics.Color
import io.github.aixtin.nyral.R

/**
 * 主题系统（Theme API）
 * ============================================================
 * 设计目标（预接口，未来不被锁死）：
 *  - 现在：三套主题共享同一套 View 结构，切换只换"值集"（颜色/圆角/间距/字号/阴影）。
 *  - 未来：某主题要"大体改造"（换布局结构、组件形态）时，在该主题实现里覆盖
 *          风格钩子（bubbleCorner/cardCorner/...）或直接改其渲染逻辑即可，
 *          只影响该主题自身，不触碰其他主题与主框架。
 *
 * 主题 ID：
 *  - 0 DefaultTheme：现有视觉（零变化，作为默认）
 *  - 1 FlatTheme：扁平化（参考 Pocket PAI：纯白 + 深灰黑 + 橙点缀）
 *  - 2 KawaiiTheme：日系卡哇伊（粉系 + 大圆角）
 * ============================================================
 */
interface AppTheme {
    val id: Int
    val nameRes: Int

    // ========== 核心色板（语义色） ==========
    val bg: Int            // 页面背景
    val surface: Int       // 卡片/标题栏/弹窗底
    val primary: Int       // 主色（按钮/链接/强调）
    val primaryLight: Int  // 主色浅底
    val text: Int          // 正文
    val sub: Int           // 次要文字
    val divider: Int       // 分隔线
    val inputBg: Int       // 输入框底
    val stroke: Int        // 卡片/面板描边（浅色主题用浅灰，暗色用深灰）
    val danger: Int        // 危险/错误
    val dangerLight: Int   // 危险浅底
    val accent: Int        // 点缀色（默认同 primary）

    // ========== 聊天气泡 ==========
    val bubbleAi: Int      // AI 气泡底
    val bubbleAiText: Int  // AI 气泡文字
    val bubbleUser: Int    // 用户气泡底
    val bubbleUserText: Int
    val thinkBg: Int       // 思考块底
    val thinkText: Int     // 思考块文字

    // ========== 数值档位 ==========
    val radius: RadiusTokens
    val spacing: SpacingTokens
    val typography: TypeTokens
    val shadow: ShadowTokens

    // ========== 风格钩子（预留扩展点，第一版全部默认） ==========
    fun bubbleCorner(density: Float): Float = 12 * density
    fun cardCorner(density: Float): Float = 16 * density
    fun btnCorner(density: Float): Float = 14 * density
    fun inputCorner(density: Float): Float = 12 * density
}

/** 圆角档位（dp） */
data class RadiusTokens(
    val card: Int,      // 列表卡片
    val input: Int,     // 输入框
    val btn: Int,       // 主按钮
    val bubble: Int,    // 聊天气泡
    val badge: Int,     // 图标角标
    val dialog: Int     // 弹窗
)

/** 间距档位（dp） */
data class SpacingTokens(
    val page: Int,      // 页面左右留白
    val card: Int,      // 卡片内边距
    val item: Int,      // 列表项间距
    val group: Int      // 分组间距
)

/** 字号档位（sp） */
data class TypeTokens(
    val title: Float,
    val body: Float,
    val sub: Float,
    val hint: Float
)

/** 阴影档位 */
data class ShadowTokens(
    val elevation: Int, // View elevation
    val alpha: Int      // 阴影透明度 0-255
)

/** 主题基类：用构造参数收敛共同字段，各主题按需 override 风格钩子 */
open class BaseTheme(
    override val id: Int,
    override val nameRes: Int,
    override val bg: Int,
    override val surface: Int,
    override val primary: Int,
    override val primaryLight: Int,
    override val text: Int,
    override val sub: Int,
    override val divider: Int,
    override val inputBg: Int,
    override val stroke: Int,
    override val danger: Int,
    override val dangerLight: Int,
    override val accent: Int,
    override val bubbleAi: Int,
    override val bubbleAiText: Int,
    override val bubbleUser: Int,
    override val bubbleUserText: Int,
    override val thinkBg: Int,
    override val thinkText: Int,
    override val radius: RadiusTokens,
    override val spacing: SpacingTokens,
    override val typography: TypeTokens,
    override val shadow: ShadowTokens
) : AppTheme

/** 主题 0：默认（现有视觉，零变化） */
object DefaultTheme : BaseTheme(
    id = 0,
    nameRes = R.string.theme_default,
    bg = Color.parseColor("#F7F7F8"),
    surface = Color.parseColor("#FFFFFF"),
    primary = Color.parseColor("#0B93F6"),
    primaryLight = Color.parseColor("#E8F3FE"),
    text = Color.parseColor("#1A1A1A"),
    sub = Color.parseColor("#999999"),
    divider = Color.parseColor("#F0F0F2"),
    inputBg = Color.parseColor("#EFEFF1"),
    stroke = Color.parseColor("#D9D9DE"),
    danger = Color.parseColor("#E5484D"),
    dangerLight = Color.parseColor("#FFE5E5"),
    accent = Color.parseColor("#0B93F6"),
    bubbleAi = Color.parseColor("#F1F2F4"),
    bubbleAiText = Color.parseColor("#1A1A1A"),
    bubbleUser = Color.parseColor("#0B93F6"),
    bubbleUserText = Color.WHITE,
    thinkBg = Color.parseColor("#E7E8EA"),
    thinkText = Color.parseColor("#8A8A8A"),
    radius = RadiusTokens(16, 12, 14, 12, 10, 18),
    spacing = SpacingTokens(16, 16, 12, 16),
    typography = TypeTokens(17f, 16f, 12f, 11f),
    shadow = ShadowTokens(0, 0)
)

/** 主题 1：扁平化（参考 Pocket PAI 源码 token：纯白底 + 深炭灰黑主色 + 品牌蓝强调，大圆角、极淡弥散阴影、无渐变） */
object FlatTheme : BaseTheme(
    id = 1,
    nameRes = R.string.theme_flat,
    bg = Color.parseColor("#FFFFFF"),
    surface = Color.parseColor("#FFFFFF"),
    primary = Color.parseColor("#333333"),
    primaryLight = Color.parseColor("#F9FAFB"),
    text = Color.parseColor("#111111"),
    sub = Color.parseColor("#646466"),
    divider = Color.parseColor("#F0F0F0"),
    inputBg = Color.parseColor("#F9FAFB"),
    stroke = Color.parseColor("#E5E5EA"),
    danger = Color.parseColor("#FF653F"),
    dangerLight = Color.parseColor("#FFE3DE"),
    accent = Color.parseColor("#1E4DF6"),
    bubbleAi = Color.parseColor("#F2F2F2"),
    bubbleAiText = Color.parseColor("#333333"),
    bubbleUser = Color.parseColor("#333333"),
    bubbleUserText = Color.WHITE,
    thinkBg = Color.parseColor("#F9FAFB"),
    thinkText = Color.parseColor("#646466"),
    radius = RadiusTokens(16, 16, 16, 15, 8, 16),
    spacing = SpacingTokens(16, 14, 10, 14),
    typography = TypeTokens(17f, 16f, 12f, 11f),
    shadow = ShadowTokens(3, 26)
)

/** 主题 2：卡哇伊（参考「汐」Ushio 源码 token：靛蓝紫主色 + 浅灰蓝底 + 液态玻璃弥散阴影 + 圆润大圆角） */
object KawaiiTheme : BaseTheme(
    id = 2,
    nameRes = R.string.theme_kawaii,
    bg = Color.parseColor("#F8FAFC"),
    surface = Color.parseColor("#FFFFFF"),
    primary = Color.parseColor("#6366F1"),
    primaryLight = Color.parseColor("#EEF2FF"),
    text = Color.parseColor("#1E293B"),
    sub = Color.parseColor("#64748B"),
    divider = Color.parseColor("#E7EAF0"),
    inputBg = Color.parseColor("#F1F5F9"),
    stroke = Color.parseColor("#DDE3EC"),
    danger = Color.parseColor("#EF4444"),
    dangerLight = Color.parseColor("#FEE2E2"),
    accent = Color.parseColor("#22D3EE"),
    bubbleAi = Color.parseColor("#EEF2FF"),
    bubbleAiText = Color.parseColor("#1E293B"),
    bubbleUser = Color.parseColor("#6366F1"),
    bubbleUserText = Color.WHITE,
    thinkBg = Color.parseColor("#EEF2FF"),
    thinkText = Color.parseColor("#6366F1"),
    radius = RadiusTokens(16, 16, 24, 16, 999, 16),
    spacing = SpacingTokens(16, 18, 14, 18),
    typography = TypeTokens(17f, 16f, 12f, 11f),
    shadow = ShadowTokens(2, 20),
    // 卡哇伊沿用「汐」圆润大圆角，覆盖风格钩子
) {
    override fun bubbleCorner(density: Float): Float = 18 * density
    override fun cardCorner(density: Float): Float = 16 * density
    override fun btnCorner(density: Float): Float = 999 * density
}

/** 主题 3：Kiwi 暗（参考 Kiwi Browser nightmode 设计语言：蓝灰夜底 #14181C + Material 蓝强调 + 低饱和灰阶、小圆角） */
object KiwiDarkTheme : BaseTheme(
    id = 3,
    nameRes = R.string.theme_kiwi,
    bg = Color.parseColor("#14181C"),
    surface = Color.parseColor("#161E21"),
    primary = Color.parseColor("#1A73E8"),
    primaryLight = Color.parseColor("#1B2735"),
    text = Color.parseColor("#E8EAED"),
    sub = Color.parseColor("#9AA0A6"),
    divider = Color.parseColor("#262C31"),
    inputBg = Color.parseColor("#1F262C"),
    stroke = Color.parseColor("#3A3F50"),
    danger = Color.parseColor("#F28B82"),
    dangerLight = Color.parseColor("#3A2625"),
    accent = Color.parseColor("#8AB4F8"),
    bubbleAi = Color.parseColor("#1F262C"),
    bubbleAiText = Color.parseColor("#E8EAED"),
    bubbleUser = Color.parseColor("#174EA6"),
    bubbleUserText = Color.WHITE,
    thinkBg = Color.parseColor("#1B2735"),
    thinkText = Color.parseColor("#8AB4F8"),
    radius = RadiusTokens(12, 10, 12, 10, 6, 14),
    spacing = SpacingTokens(16, 14, 10, 16),
    typography = TypeTokens(17f, 16f, 12f, 11f),
    shadow = ShadowTokens(0, 0)
) {
    // Kiwi 设置项小圆角语言，覆盖风格钩子
    override fun bubbleCorner(density: Float): Float = 10 * density
    override fun cardCorner(density: Float): Float = 12 * density
    override fun btnCorner(density: Float): Float = 12 * density
    override fun inputCorner(density: Float): Float = 10 * density
}

/** 主题 4：图标（从 App 图标 ic_launcher 提取色板：米白底 #FBF7EA + 淡蓝 #9BC1DD + 浅蓝绿 #BAD9E2 + 米驼 #E2D5C7，柔和浅色系、圆润中圆角） */
object IconTheme : BaseTheme(
    id = 4,
    nameRes = R.string.theme_icon,
    bg = Color.parseColor("#FBF7EE"),
    surface = Color.parseColor("#FFFFFF"),
    primary = Color.parseColor("#7FA8CC"),
    primaryLight = Color.parseColor("#E3EEF6"),
    text = Color.parseColor("#33414E"),
    sub = Color.parseColor("#8B98A3"),
    divider = Color.parseColor("#ECE6D8"),
    inputBg = Color.parseColor("#F2EEE3"),
    stroke = Color.parseColor("#E3DED2"),
    danger = Color.parseColor("#E05B52"),
    dangerLight = Color.parseColor("#FBE4E0"),
    accent = Color.parseColor("#5FA8B8"),
    bubbleAi = Color.parseColor("#EEF4F8"),
    bubbleAiText = Color.parseColor("#33414E"),
    bubbleUser = Color.parseColor("#7FA8CC"),
    bubbleUserText = Color.WHITE,
    thinkBg = Color.parseColor("#E8F0F5"),
    thinkText = Color.parseColor("#5FA8B8"),
    radius = RadiusTokens(18, 16, 18, 16, 8, 16),
    spacing = SpacingTokens(16, 16, 12, 16),
    typography = TypeTokens(17f, 16f, 12f, 11f),
    shadow = ShadowTokens(2, 18)
) {
    // 图标圆润流线语言，覆盖风格钩子
    override fun bubbleCorner(density: Float): Float = 18 * density
    override fun cardCorner(density: Float): Float = 16 * density
    override fun btnCorner(density: Float): Float = 999 * density
    override fun inputCorner(density: Float): Float = 16 * density
}

/** 主题 5：OLED 暗（AMOLED 纯黑省电：黑底像素熄灭 + 高对比文字 + 柔和亮蓝主色） */
object OledTheme : BaseTheme(
    id = 5,
    nameRes = R.string.theme_oled,
    bg = Color.parseColor("#000000"),
    surface = Color.parseColor("#0A0A0A"),
    primary = Color.parseColor("#4DA3FF"),
    primaryLight = Color.parseColor("#122036"),
    text = Color.parseColor("#E8EAED"),
    sub = Color.parseColor("#8A9099"),
    divider = Color.parseColor("#1A1A1A"),
    inputBg = Color.parseColor("#111317"),
    stroke = Color.parseColor("#262C31"),
    danger = Color.parseColor("#F28B82"),
    dangerLight = Color.parseColor("#2A1F1F"),
    accent = Color.parseColor("#8AB4F8"),
    bubbleAi = Color.parseColor("#14171A"),
    bubbleAiText = Color.parseColor("#E8EAED"),
    bubbleUser = Color.parseColor("#0B3A75"),
    bubbleUserText = Color.WHITE,
    thinkBg = Color.parseColor("#10151C"),
    thinkText = Color.parseColor("#8AB4F8"),
    radius = RadiusTokens(12, 10, 12, 10, 6, 14),
    spacing = SpacingTokens(16, 14, 10, 16),
    typography = TypeTokens(17f, 16f, 12f, 11f),
    shadow = ShadowTokens(0, 0)
) {
    // OLED 沿用紧凑小圆角（同 Kiwi 暗）
    override fun bubbleCorner(density: Float): Float = 10 * density
    override fun cardCorner(density: Float): Float = 12 * density
    override fun btnCorner(density: Float): Float = 12 * density
    override fun inputCorner(density: Float): Float = 10 * density
}
