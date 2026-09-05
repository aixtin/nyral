package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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

/**
 * 权限管理 — 集中查看 / 补开 app 依赖的全部权限。
 * 背景: 部分手机长时间不使用某权限会自动收回, 需一处查看哪些已授权、哪些未授权并一键补开。
 * 权限项与首启引导(runFirstRunPermissionGuide)保持一致:
 *   通知 / 麦克风(运行时权限); 悬浮窗 / 所有文件访问 / 安装未知应用(特殊权限)
 */
class PermissionsActivity : Activity() {

    private var listBox: LinearLayout? = null
    private var sumText: TextView? = null

    private class PermItem(
        val icon: String,
        val name: String,
        val desc: String,
        /** 当前系统是否需要该权限(false 表示本机无需, 展示getString(R.string.perm_17)) */
        val required: () -> Boolean,
        /** 是否已授权 */
        val granted: () -> Boolean,
        /** 发起授权(运行时弹窗或跳系统设置页) */
        val request: (PermissionsActivity) -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.perm_01)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // 汇总卡: 已授权数 + 背景说明
        content.addView(Ui.card(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(this@PermissionsActivity).apply {
                text = getString(R.string.perm_02)
                textSize = 24f
                setTextColor(0xFF2E7D32.toInt())
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                sumText = this
            })
            addView(TextView(this@PermissionsActivity).apply {
                text = getString(R.string.perm_03)
                textSize = 12f
                setTextColor(0xFF999999.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
        })

        // 权限明细卡
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }.also { holder ->
            listBox = LinearLayout(this@PermissionsActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            holder.addView(Ui.card(this@PermissionsActivity).apply {
                addView(listBox)
            })
        })

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        })

        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页 / 系统授权弹窗返回后刷新状态
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
        // 运行时权限被永久拒绝时引导去应用设置页
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, getString(R.string.perm_04), Toast.LENGTH_LONG).show()
        }
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

    /** 重建列表(不 recreate activity, 避免闪屏) */
    private fun refresh() {
        val box = listBox ?: return
        box.removeAllViews()

        val list = items()
        var granted = 0
        var total = 0
        list.forEach { if (it.required()) { total++; if (it.granted()) granted++ } }
        sumText?.apply {
            text = if (total == 0) getString(R.string.perm_15) else getString(R.string.perm_16)
            setTextColor(if (granted == total) 0xFF2E7D32.toInt() else 0xFFD32F2F.toInt())
        }

        var first = true
        list.forEach { item ->
            if (!first) box.addView(Ui.divider(this))
            first = false
            box.addView(buildRow(item))
        }
    }

    private fun buildRow(item: PermItem): LinearLayout {
        val required = item.required()
        val granted = item.granted()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            if (required && !granted) {
                isClickable = true
                setOnClickListener { item.request(this@PermissionsActivity) }
                Ui.press(this)
            }
            addView(Ui.iconBadge(this@PermissionsActivity, item.icon, item.name.hashCode()))
            addView(LinearLayout(this@PermissionsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@PermissionsActivity, item.name))
                addView(TextView(this@PermissionsActivity).apply {
                    text = item.desc
                    textSize = 12f
                    setTextColor(0xFF999999.toInt())
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            addView(TextView(this@PermissionsActivity).apply {
                text = when {
                    !required -> getString(R.string.perm_17)
                    granted -> getString(R.string.perm_18)
                    else -> getString(R.string.perm_19)
                }
                textSize = 13f
                setTextColor(when {
                    !required -> 0xFFCCCCCC.toInt()
                    granted -> 0xFF2E7D32.toInt()
                    else -> 0xFFD32F2F.toInt()
                })
                typeface = if (required && !granted) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                if (required && !granted) {
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(0x1AD32F2F.toInt())
                    }
                    text = getString(R.string.perm_20)
                }
            })
        }
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
