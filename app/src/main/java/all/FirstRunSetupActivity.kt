package io.github.aixtin.droidagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.aixtin.droidagent.R

/**
 * 首启授权页（替代原模式选择弹窗，独立 Activity 全屏承载，根治 Translucent Dialog 的状态栏变黑）。
 * 内容：需要授权项清单（图标+名称+状态[未授权/授权]+去授权/已完成按钮）
 *       + Agent/聊天模式二选一 + 底部主按钮"进入 APP"（含"暂不授权，跳过"）。
 * 授权状态在 onResume（从系统授权页返回）与授权回调后自动刷新。
 */
class FirstRunSetupActivity : Activity() {

    private var listBox: LinearLayout? = null
    private var modeBox: LinearLayout? = null

    private class PermItem(
        val icon: String,
        val name: String,
        val desc: String,
        val required: () -> Boolean,
        val granted: () -> Boolean,
        val request: (FirstRunSetupActivity) -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)
        ModeConfig.init(this)
        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.guide_perm_title)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(16))
        }

        // 引导语
        content.addView(TextView(this).apply {
            text = getString(R.string.guide_perm_intro)
            textSize = 12.5f
            setTextColor(Ui.SUB)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(4), dp(4), dp(4), dp(12))
        })

        // 权限列表卡
        val permCard = Ui.card(this)
        permCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }.also { box -> listBox = box })
        content.addView(permCard)

        // 模式选择卡
        content.addView(TextView(this).apply {
            text = getString(R.string.guide_mode_card_title)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(dp(4), dp(18), dp(4), dp(8))
        })
        val modeCard = Ui.card(this)
        modeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        modeCard.addView(modeBox)
        content.addView(modeCard)
        content.addView(TextView(this).apply {
            text = getString(R.string.guide_mode_tip)
            textSize = 11f
            setTextColor(Ui.SUB)
            setPadding(dp(4), dp(8), dp(4), dp(4))
        })

        // 底部主按钮：进入 APP
        content.addView(Ui.primaryBtn(this, getString(R.string.guide_perm_enter)) {
            goMain(skip = false)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                setMargins(0, dp(14), 0, 0)
            }
        })

        // 跳过：暂不授权，之后在 设置→权限管理 补开
        content.addView(TextView(this).apply {
            text = getString(R.string.guide_perm_skip)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Ui.SUB)
            setPadding(0, dp(14), 0, dp(8))
            isClickable = true
            Ui.press(this)
            setOnClickListener { goMain(skip = true) }
        })

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        refreshPerms()
        renderMode()
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPerms()
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, getString(R.string.perm_04), Toast.LENGTH_LONG).show()
        }
    }

    private fun goMain(skip: Boolean) {
        if (skip) {
            // 标记已处理授权引导，避免主界面再次弹出；可在 设置→权限管理 补开
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit().putBoolean("first_run_perms_done", true).apply()
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun items(): List<PermItem> = listOf(
        PermItem("🔔", getString(R.string.perm_05), getString(R.string.perm_06), {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        }, {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }, { a ->
            a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_RUNTIME)
        }),
        PermItem("🎙", getString(R.string.perm_07), getString(R.string.perm_08), {
            true
        }, {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }, { a ->
            a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RUNTIME)
        }),
        PermItem("🪟", getString(R.string.perm_09), getString(R.string.perm_10), {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        }, {
            Settings.canDrawOverlays(this)
        }, { a -> a.openOverlay() }),
        PermItem("📁", getString(R.string.perm_11), getString(R.string.perm_12), {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        }, {
            Environment.isExternalStorageManager()
        }, { a -> a.openAllFiles() }),
        PermItem("📦", getString(R.string.perm_13), getString(R.string.perm_14), {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        }, {
            packageManager.canRequestPackageInstalls()
        }, { a -> a.openUnknownSources() })
    )

    private fun refreshPerms() {
        val box = listBox ?: return
        box.removeAllViews()
        var first = true
        items().forEach { item ->
            if (!first) box.addView(Ui.divider(this))
            first = false
            box.addView(buildPermRow(item))
        }
    }

    private fun buildPermRow(item: PermItem): LinearLayout {
        val required = item.required()
        val granted = item.granted()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            addView(Ui.iconBadge(this@FirstRunSetupActivity, item.icon.take(1), item.name.hashCode(), 40))
            addView(LinearLayout(this@FirstRunSetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@FirstRunSetupActivity, item.name))
                addView(TextView(this@FirstRunSetupActivity).apply {
                    text = item.desc
                    textSize = 12f
                    setTextColor(0xFF999999.toInt())
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            // 右侧状态/按钮：不需要=灰字提示；已授权=绿色"已完成"；未授权=蓝色"去授权"按钮
            addView(TextView(this@FirstRunSetupActivity).apply {
                textSize = 13f
                when {
                    !required -> {
                        text = getString(R.string.perm_17)
                        setTextColor(0xFFCCCCCC.toInt())
                    }
                    granted -> {
                        text = getString(R.string.guide_perm_done)
                        setTextColor(0xFF2E7D32.toInt())
                    }
                    else -> {
                        text = getString(R.string.guide_perm_go)
                        setTextColor(0xFFFFFFFF.toInt())
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        typeface = Typeface.DEFAULT_BOLD
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(14).toFloat()
                            setColor(0xFF0B93F6.toInt())
                        }
                        isClickable = true
                        setOnClickListener { item.request(this@FirstRunSetupActivity) }
                    }
                }
            })
        }
    }

    private fun renderMode() {
        val box = modeBox ?: return
        box.removeAllViews()
        box.addView(modeRow(
            title = getString(R.string.guide_mode_agent_title),
            desc = getString(R.string.guide_mode_agent_desc),
            checked = !ModeConfig.chatMode(),
            onPick = { ModeConfig.setChatMode(false); renderMode() }
        ))
        box.addView(Ui.divider(this))
        box.addView(modeRow(
            title = getString(R.string.guide_mode_chat_title),
            desc = getString(R.string.guide_mode_chat_desc),
            checked = ModeConfig.chatMode(),
            onPick = { ModeConfig.setChatMode(true); renderMode() }
        ))
    }

    private fun modeRow(title: String, desc: String, checked: Boolean, onPick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            background = Ui.rounded(if (checked) 0xFFE8F4FF.toInt() else 0x00000000.toInt(), 0, this@FirstRunSetupActivity)
            setOnClickListener { onPick() }
            addView(LinearLayout(this@FirstRunSetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@FirstRunSetupActivity).apply {
                    text = title
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Ui.TEXT)
                })
                addView(TextView(this@FirstRunSetupActivity).apply {
                    text = desc
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(3), 0, 0)
                })
            })
            addView(TextView(this@FirstRunSetupActivity).apply {
                text = if (checked) "✓" else ""
                textSize = 18f
                setTextColor(0xFF2E7D32.toInt())
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), 0, 0, 0)
            })
        }

    private fun openOverlay() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            catch (e2: Exception) { Toast.makeText(this, getString(R.string.perm_21), Toast.LENGTH_LONG).show() }
        }
    }

    private fun openAllFiles() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            catch (e2: Exception) { Toast.makeText(this, getString(R.string.perm_22), Toast.LENGTH_LONG).show() }
        }
    }

    private fun openUnknownSources() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
            catch (e2: Exception) { Toast.makeText(this, getString(R.string.perm_23), Toast.LENGTH_LONG).show() }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_RUNTIME = 2001
    }
}
