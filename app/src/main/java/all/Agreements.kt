package io.github.aixtin.droidagent

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 免责声明 / 隐私政策 全文常量 + 通用阅读弹窗。
 * 与仓库根目录 DISCLAIMER.md / PRIVACY.md 同口径。
 * 引导页用带"滑到底才可同意"的强制弹窗；设置-关于页用只读展示弹窗。
 */
object Agreements {

    /** 免责声明（与仓库 DISCLAIMER.md 同口径的 App 内简版） */
    val DISCLAIMER_TEXT = """本应用为个人开发者提供的安卓智能体工具，仅用于学习、研究、技术交流，不构成任何正式产品承诺。

一、无担保声明
本应用按"现状"提供，作者不作任何明示或默示担保，包括但不限于适销性、特定用途适用性、不侵犯第三方权利、运行稳定、数据完整无损。因使用本应用产生的任何直接或间接损失，作者不承担责任。

二、数据与 API 披露（重要）
1. 本应用不集成任何本地模型，所有对话均通过你自行配置的第三方 AI 服务商 API（如 OpenAI 兼容接口）实现。
2. 你在对话中提交的内容、附带的上下文（如记忆检索结果、工具调用参数、文件内容片段）将通过网络请求发送至你所配置的模型服务商服务器处理，数据并不只停留在本地设备。
3. 上述数据的处理、存储与合规遵循该服务商自身的服务条款与隐私政策，与作者无关；请在配置 API 前阅读并确认服务商相关协议。
4. 涉及文件内容、SSH 远程信息、个人隐私、账号凭据等敏感数据，请在发送前充分知悉其必然经过你指定的模型通道，谨慎选择模型与配置。

三、远程与系统操作风险
本应用可能包含 SSH 远程连接、文件读写、系统调试（root/adb）等能力，此类操作可能对设备、远程服务器或其上数据造成不可预期的后果（包括但不限于数据丢失、服务中断、设备异常）。所有远程与系统级操作后果由操作者自行承担，请自行评估风险并做好备份。

四、责任边界
作者不对第三方服务商的可用性、安全性、合规性作任何担保，不对网络、模型输出内容、第三方服务故障引发的损失负责；用户违反法律法规、服务商条款或他人权利导致的后果，由用户自行承担。

五、变更
作者保留随时修改本声明的权利，修改后自更新之日起适用；继续使用本应用即视为接受最新版本。"""

    /** 隐私政策（与仓库 PRIVACY.md 同口径的 App 内简版） */
    val PRIVACY_TEXT = """本应用遵循"最小收集"原则：应用本身不收集、不存储、不分析、不共享任何与你个人身份直接相关的数据，你的使用记录默认仅保存在你自己设备上。

一、数据收集
1. 作者不收集任何个人信息，不采集设备标识、IMEI、位置、通讯录、电话、短信等。
2. 不接入任何统计、广告、埋点 SDK。
3. 应用内登录态、配置、记忆等内容均由你自行管理与操作。

二、数据去向（重要）
1. 你配置并启用的模型 API：对话内容、附带上下文（含记忆检索结果、工具调用参数、文件内容片段）会经网络发送至你所配置的第三方模型服务商处理，以该服务商自身的隐私政策为准。
2. 除你主动配置的上述通道外，本应用不向任何第三方传输你的数据。

三、本地存储内容
以下内容仅保存在设备本地，用于保证连续对话与基本功能：
1. 聊天记录、记忆库内容。
2. 模型、SSH 等连接配置（含你自行填写的地址、令牌、密钥等）。
3. 应用运行日志。
请妥善保管含敏感信息的配置数据；卸载应用或清除应用数据即可删除上述本地内容。

四、权限使用说明
1. 网络权限：用于访问你配置的模型 API、SSH 等连接目标。
2. 存储权限：用于读写你允许访问的文件。
3. 系统调试相关：仅在启用远程调试、root 能力时按你的操作生效。
权限均在功能需要时请求，你可随时在系统设置中关闭。

五、未成年人
本应用不面向未成年人提供服务；若未成年人在监护人指导下使用，请监护人知悉上述 API 数据通道内容。

六、政策变更与联系
我们可能更新本政策，更新后自公布之日起生效，重大变更将以显著方式提示。如有疑问可提交至仓库 Issue。"""

    /**
     * 只读协议展示弹窗（设置-关于页入口使用）：不强制滑动，底部"知道了"关闭。
     */
    fun showReadonly(activity: Activity, title: String, body: String) {
        val dlg = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dlg.setCancelable(true)
        dlg.setCanceledOnTouchOutside(false)
        val d = activity.resources.displayMetrics.density
        fun dpf(v: Int) = (v * d).toInt()
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(0xFFFFFFFF.toInt(), 16, activity)
        }
        panel.addView(TextView(activity).apply {
            text = title
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF1A1A1A.toInt())
            gravity = Gravity.CENTER
            setPadding(dpf(20), dpf(20), dpf(20), dpf(12))
        })
        val sv = ScrollView(activity).apply { isFillViewport = true }
        sv.addView(TextView(activity).apply {
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setLineSpacing(dpf(3).toFloat(), 1f)
            text = body
            setPadding(dpf(20), dpf(2), dpf(20), dpf(14))
        })
        panel.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val okBtn = TextView(activity).apply {
            text = "我知道了"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = Ui.rounded(0xFF0B93F6.toInt(), 18, activity)
            setPadding(dpf(6), dpf(13), dpf(6), dpf(13))
            setOnClickListener { dlg.dismiss() }
        }
        val bottomRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpf(16), dpf(6), dpf(16), dpf(18))
        }
        bottomRow.addView(okBtn, LinearLayout.LayoutParams(dpf(156), ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(bottomRow)
        dlg.setContentView(panel)
        dlg.window?.let { w ->
            val lp = w.attributes
            lp.width = (activity.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.height = (activity.resources.displayMetrics.heightPixels * 0.8).toInt()
            lp.gravity = Gravity.CENTER
            w.attributes = lp
            w.setBackgroundDrawable(ColorDrawable())
        }
        dlg.show()
    }
}
