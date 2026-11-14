package io.github.aixtin.nyral

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 权限管理页(2026-11-14 重排版)。
 *
 * 12 项平铺有两个问题: 看不出"哪几项决定定时任务能不能跑", 也看不出整体进度。
 * 现在按 PermCatalog 的分组展示(基础 / 定时任务保障 / 高级与扩展), 每组带小计,
 * 顶部一张"已就绪 x/y + 手动确认 n 项"的汇总卡, 外加一条进度条。
 *
 * 行状态统一为四种: ✓ 已开启(绿) / 去开启(浅底胶囊) / 去设置(厂商开关) / 无需开启(灰)。
 * 带有"手动确认"的项永远不计入分母 —— 没有任何 API 能读到它们的真实状态,
 * 把它们算进去只会让用户永远看到"未全部授权"。
 */
class PermissionsActivity : Activity() {

    private var listBox: LinearLayout? = null
    /** root 授权状态: -1=检测中/未知, 0=未授权, 1=已授权(首帧取进程缓存, onResume 后台重探) */
    private var rootGranted = when (RootCheck.cachedGranted()) {
        true -> 1
        false -> 0
        else -> -1
    }
    /** 本机是否检出 root 环境(用于横幅提示, 与"是否已授权"分开)。
     *  首帧只读进程级缓存, 后台探测完成后刷新, 避免主线程起 su 子进程。 */
    private var deviceRooted = RootCheck.cachedRooted() ?: false
    /** 本次进程内是否已用 root 自动补齐过自身权限(仅一次, 幂等) */
    @Volatile private var rootAutoGranted = false

    /** 后台实时探测 root 授权(不依赖进程级缓存, 用户去授权工具回来后能即时刷新);
     *  首次探测到已授权时用 root 静默补齐自身权限(尽力而为, 失败保持手动入口) */
    private fun probeRoot() {
        Thread {
            val rooted = RootCheck.deviceRooted()
            val g = rooted && RootCheck.isGranted()
            if (g && !rootAutoGranted) {
                rootAutoGranted = true
                RootCheck.grantSelf(this)
            }
            val act = this@PermissionsActivity
            act.runOnUiThread {
                deviceRooted = rooted
                rootGranted = if (g) 1 else 0
                refresh()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.perm_01)))

        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        root.addView(ScrollView(this).apply {
            addView(listBox)
            isFillViewport = true
        })

        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页 / 系统授权弹窗返回后刷新状态
        refresh()
        // 实时探测 root 授权(每次进入/返回都探测, 用户去授权工具回来后即时刷新)
        probeRoot()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        // 运行时权限被永久拒绝时引导去应用设置页
        if (grantResults.any { it != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, getString(R.string.perm_04), Toast.LENGTH_LONG).show()
        }
    }

    private fun items(): List<PermSpec> = PermCatalog.build(this, rootGranted, deviceRooted)

    /** 重建列表(不 recreate activity, 避免闪屏) */
    private fun refresh() {
        val box = listBox ?: return
        box.removeAllViews()

        val list = items()
        var granted = 0
        var total = 0
        list.forEach {
            // manual 项无 API 可读状态, 不能计入分母; root 检测中计入分母但不计入已授权
            if (it.required() && !it.manual) {
                total++
                if (it.granted()) granted++
            }
        }
        val manualCount = list.count { it.manual }

        // ===== 汇总卡 =====
        box.addView(Ui.card(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(this@PermissionsActivity).apply {
                text = if (total == 0) getString(R.string.perm_15)
                else if (manualCount > 0) getString(R.string.perm_56, granted, total, manualCount)
                else getString(R.string.perm_57, granted, total)
                textSize = 20f
                setTextColor(if (total > 0 && granted == total) Ui.OK else Ui.TEXT)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            addView(progressBar(granted, total), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                topMargin = dp(12)
            })
        })

        // ===== Root 横幅(仅检出 root 环境时出现) =====
        if (deviceRooted) {
            box.addView(TextView(this).apply {
                text = if (rootGranted == 1) getString(R.string.perm_55) else getString(R.string.perm_54)
                textSize = 12f
                setTextColor(if (rootGranted == 1) Ui.OK else Ui.SUB)
                setLineSpacing(dp(2).toFloat(), 1f)
                background = TimerTaskUi.capsule(this@PermissionsActivity, TimerTaskUi.tint(if (rootGranted == 1) Ui.OK else Ui.SUB), 12)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }

        // ===== 分组明细 =====
        listOf(PermSpec.GROUP_BASE, PermSpec.GROUP_TIMER, PermSpec.GROUP_ADV).forEach { g ->
            val group = list.filter { it.group == g }
            if (group.isEmpty()) return@forEach
            val gt = group.count { it.required() && !it.manual }
            val gg = group.count { it.required() && !it.manual && it.granted() }
            box.addView(Ui.groupLabel(this, buildString {
                append(PermCatalog.groupTitle(this@PermissionsActivity, g))
                if (gt > 0) append(" · $gg/$gt")
            }))
            box.addView(Ui.card(this).apply {
                group.forEachIndexed { i, item ->
                    if (i > 0) addView(Ui.divider(this@PermissionsActivity, 16))
                    addView(buildRow(item))
                }
            })
        }
    }

    /** 进度条: 已就绪绿/主色 + 未完成浅灰底 */
    private fun progressBar(granted: Int, total: Int): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(Ui.INPUT_BG)
            }
            clipToOutline = true
        }
        if (total > 0 && granted > 0) {
            bar.addView(View(this).apply {
                setBackgroundColor(if (granted >= total) Ui.OK else Ui.PRIMARY)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, granted.toFloat()))
        }
        if (total > granted) {
            bar.addView(View(this), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, (total - granted).toFloat()))
        }
        return bar
    }

    private fun buildRow(item: PermSpec): LinearLayout {
        val required = item.required()
        val granted = item.granted()
        val detecting = item.isRoot && rootGranted == -1
        // manual 项恒可点(它本身就是跳转入口); root 检测中不可点, 避免误触
        val clickable = item.manual || (required && !detecting && !granted)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            if (clickable) {
                isClickable = true
                setOnClickListener { item.request(this@PermissionsActivity) }
                Ui.press(this)
            }
            addView(Ui.iconBadgeRes(this@PermissionsActivity, item.iconRes, item.name.hashCode()))
            addView(LinearLayout(this@PermissionsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@PermissionsActivity, item.name).apply { textSize = 14f })
                addView(TextView(this@PermissionsActivity).apply {
                    text = item.desc
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            addView(statusView(item, required, granted, detecting))
        }
    }

    /** 右侧统一状态: 已开启(绿勾) / 去开启(浅底胶囊) / 去设置(厂商开关) / 无需开启(灰) */
    private fun statusView(item: PermSpec, required: Boolean, granted: Boolean, detecting: Boolean): TextView =
        TextView(this).apply {
            textSize = 13f
            when {
                item.manual -> {
                    text = getString(R.string.perm_41) + " ›"
                    setTextColor(Ui.SUB)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                !required -> {
                    text = getString(R.string.perm_53)
                    setTextColor(Ui.SUB)
                }
                detecting -> {
                    text = getString(R.string.perm_26)
                    setTextColor(Ui.SUB)
                }
                granted -> {
                    text = "✓ " + getString(R.string.perm_52)
                    setTextColor(Ui.OK)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                else -> {
                    text = getString(R.string.perm_51)
                    setTextColor(Ui.PRIMARY)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    background = TimerTaskUi.capsule(this@PermissionsActivity, Ui.PRIMARY_LIGHT, 13)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                }
            }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
