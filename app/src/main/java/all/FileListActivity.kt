package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 文件管理页(阶段D) — 只管理存量附件, 不做导入。
 * 附件入库唯一通道 = 聊天界面附件上传(保证 meta 由上传管道统一生成)。
 * 功能: 附件列表(按类型分组, 显示大小/时长) / 查看(复用 LocalFileProvider) / 导出到工作目录 / 删除。
 * 与主页统一视觉(灰底 + 白色圆角卡片)。
 */
class FileListActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var emptyView: LinearLayout

    /** 当前选中的类型 Tab, null = 全部 */
    private var selectedType: String? = null

    /** 总占用行 / 批量删除入口 */
    private lateinit var totalBar: TextView
    private lateinit var batchBtn: TextView
    private lateinit var batchBar: LinearLayout
    private lateinit var batchActionBtn: TextView
    private val selected = LinkedHashSet<String>()
    private var batchMode = false

    /** AI 产物专属配色: 紫色系, 区别于类型角标的主题蓝 */
    companion object {
        private val AI_COLOR = Color.parseColor("#8B5CF6")
        private val AI_COLOR_LIGHT = Color.parseColor("#F3EEFF")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)
        val root = Ui.pageRoot(this)

        val logBtn = TextView(this).apply {
            text = "日志"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Ui.SUB)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.INPUT_BG)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
            Ui.press(this)
            setOnClickListener {
                startActivity(Intent(this@FileListActivity, FileAuditActivity::class.java))
            }
        }
        root.addView(Ui.titleBar(this, getString(R.string.settings_files), right = { bar ->
            bar.addView(logBtn)
        }))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        // 总占用行 + 批量删除入口
        val summaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        totalBar = TextView(this).apply {
            textSize = 12f
            setTextColor(Ui.SUB)
        }
        summaryRow.addView(totalBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        batchBtn = TextView(this).apply {
            text = "批量删除"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Ui.DANGER)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Ui.DANGER)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(12), dp(5), dp(12), dp(5))
            isClickable = true
            Ui.press(this)
            setOnClickListener { toggleBatch() }
        }
        summaryRow.addView(batchBtn)
        content.addView(summaryRow)

        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(8))
        }
        // Tab 行套横向滚动: 类型再多也不拥挤, 可左右滑动查看
        content.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        })
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(container)

        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(56), 0, dp(56))
            visibility = View.GONE
            addView(ImageView(this@FileListActivity).apply {
                setImageResource(R.drawable.ic_upload_file)
                setColorFilter(Ui.DIVIDER)
            })
            addView(TextView(this@FileListActivity).apply {
                text = getString(R.string.files_empty)
                textSize = 14f
                setTextColor(Ui.SUB)
                setPadding(0, dp(10), 0, 0)
            })
        }
        content.addView(emptyView)

        // 批量删除操作条(多选模式下显示)
        batchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(10), 0, dp(4))
        }
        batchActionBtn = TextView(this).apply {
            text = "删除选中(0)"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.DANGER)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(0, dp(10), 0, dp(10))
            Ui.press(this)
            setOnClickListener { confirmBatchDelete() }
        }
        batchBar.addView(batchActionBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        batchBar.addView(TextView(this).apply {
            text = "取消"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Ui.SUB)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Ui.DIVIDER)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(0, dp(10), 0, dp(10))
            Ui.press(this)
            setOnClickListener { toggleBatch() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })
        content.addView(batchBar)

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        container.removeAllViews()
        val files = AttachmentStore.list(this)
        totalBar.text = "共 ${files.size} 个附件 · 占用 ${humanSize(files.sumOf { it.length() })}"
        batchBtn.text = if (batchMode) "取消" else "批量删除"
        batchBtn.setTextColor(if (batchMode) Ui.TEXT else Ui.DANGER)
        batchBtn.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), if (batchMode) Ui.TEXT else Ui.DANGER)
            cornerRadius = dp(14).toFloat()
        }
        emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        // 按类型分组: AI 产物单独成组(不再混入普通类型组), 其余按 mime 分
        val aiList = files.filter { isArtifact(it) }
        val nonAI = files.filter { !isArtifact(it) }
        val groups = listOf(
            "AI 产物" to aiList,
            "视频" to nonAI.filter { AttachmentStore.mimeOf(it.name).startsWith("video/") },
            "图片" to nonAI.filter { AttachmentStore.mimeOf(it.name).startsWith("image/") },
            "音频" to nonAI.filter { AttachmentStore.mimeOf(it.name).startsWith("audio/") },
            "其他" to nonAI.filter {
                val m = AttachmentStore.mimeOf(it.name)
                !m.startsWith("video/") && !m.startsWith("image/") && !m.startsWith("audio/")
            }
        ).filter { it.second.isNotEmpty() }

        // 顶部类型 Tab: 描边胶囊按钮, 选中主题蓝高亮, 计数挪到 Tab 上
        tabRow.removeAllViews()
        tabRow.addView(typeTab("全部", files.size, selectedType == null) {
            selectedType = null
            refresh()
        })
        for ((label, list) in groups) {
            tabRow.addView(typeTab(label, list.size, selectedType == label) {
                selectedType = label
                refresh()
            })
        }

        val shown = if (selectedType == null) groups else groups.filter { it.first == selectedType }
        for ((label, list) in shown) {
            for (f in list) {
                container.addView(fileCard(f), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(8)
                })
            }
        }
        batchBar.visibility = if (batchMode) View.VISIBLE else View.GONE
        batchActionBtn.text = "删除选中(${selected.size})"
    }

    /** 是否为 AI 产物: 复用 ArtifactStore 判定(meta 带 artifact_name 标记才是产物) */
    private fun isArtifact(f: File): Boolean = ArtifactStore.metaOf(this, f.name) != null

    /** 进入/退出批量删除模式 */
    private fun toggleBatch() {
        batchMode = !batchMode
        selected.clear()
        refresh()
    }

    private fun confirmBatchDelete() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先勾选要删除的附件", Toast.LENGTH_SHORT).show()
            return
        }
        val (dlg, box) = Ui.dialog(this, "批量删除")
        box.addView(Ui.dialogText(this, "确认删除选中的 ${selected.size} 个附件？删除后不可恢复"))
        box.addView(Ui.dangerBtn(this, "确认删除") {
            dlg.dismiss()
            var ok = 0
            for (n in selected.toList()) if (AttachmentStore.delete(this, n, "user_batch")) ok++
            Toast.makeText(this, if (ok > 0) "已删除 $ok 个附件" else "删除失败", Toast.LENGTH_SHORT).show()
            selected.clear()
            batchMode = false
            refresh()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dialogCancelBtn(this, getString(R.string.dialog_cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        dlg.show()
    }

    /** 类型 Tab: 描边胶囊按钮, 未选中灰描边灰字, 选中主题蓝描边 + 浅蓝底; AI 产物 Tab 用紫色系 */
    private fun typeTab(label: String, count: Int, selected: Boolean, onClick: () -> Unit): TextView {
        val isAI = label == "AI 产物"
        val c = if (isAI) AI_COLOR else Ui.PRIMARY
        val cl = if (isAI) AI_COLOR_LIGHT else Ui.PRIMARY_LIGHT
        return TextView(this).apply {
            text = "$label $count"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (selected) c else Ui.SUB)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (selected) cl else Color.TRANSPARENT)
                setStroke(dp(1), if (selected) c else Ui.DIVIDER)
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(14), dp(7), dp(14), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
            isClickable = true
            Ui.press(this)
            setOnClickListener { onClick() }
        }
    }

    /** 附件卡片: 类型角标(+AI角标) + 显示名 + 大小/时长 + 操作行(查看/导出/删除) */
    private fun fileCard(f: File): LinearLayout {
        val mime = AttachmentStore.mimeOf(f.name)
        val meta = try { AttachmentStore.metaOf(this, f.name)?.let { JSONObject(it) } } catch (e: Exception) { null }
        val durSec = meta?.optLong("durationSec", 0L) ?: 0L
        val disp = displayName(f.name)
        val isAI = isArtifact(f)
        val checked = selected.contains(f.name)
        // 白色圆角卡片 + 浅灰描边（批量选中时描边主题蓝）
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Ui.SURFACE)
                setStroke(dp(1), if (checked) Ui.PRIMARY else Ui.STROKE)
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
            Ui.press(this)
            setOnClickListener {
                if (batchMode) {
                    if (selected.contains(f.name)) selected.remove(f.name) else selected.add(f.name)
                    batchActionBtn.text = "删除选中(${selected.size})"
                    refresh()
                } else {
                    openAttachment(f.name)
                }
            }
        // 卡片头: [勾选点] + 类型图标徽章 + 标题列 + 右侧角标, 正规横排行, 去掉 FrameLayout 悬浮 hack
        addView(LinearLayout(this@FileListActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
            if (batchMode) {
                addView(TextView(this@FileListActivity).apply {
                    text = if (checked) "✓" else ""
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (checked) Ui.PRIMARY else Ui.DIVIDER)
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                    }
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
            }
            addView(ImageView(this@FileListActivity).apply {
                setImageResource(iconResOf(mime))
                setColorFilter(if (isAI) AI_COLOR else Ui.PRIMARY)
            }, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(LinearLayout(this@FileListActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@FileListActivity).apply {
                    text = disp
                    textSize = 15f
                    setTextColor(Ui.TEXT)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(this@FileListActivity).apply {
                    text = buildString {
                        append(humanSize(f.length()))
                        if (durSec > 0) append("  ·  ${fmtDuration(durSec)}")
                        if (meta?.optInt("width", 0)?.takeIf { it > 0 } != null) {
                            append("  ·  ${meta.optInt("width")}x${meta.optInt("height")}")
                        }
                    }
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(2), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
                marginEnd = dp(8)
            })
            addView(LinearLayout(this@FileListActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@FileListActivity).apply {
                    text = badgeOf(mime)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(Ui.PRIMARY)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Ui.PRIMARY_LIGHT)
                        setStroke(dp(1), Ui.PRIMARY)
                        cornerRadius = dp(10).toFloat()
                    }
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                })
                if (isAI) {
                    addView(TextView(this@FileListActivity).apply {
                        text = "AI"
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setTextColor(AI_COLOR)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(AI_COLOR_LIGHT)
                            setStroke(dp(1), AI_COLOR)
                            cornerRadius = dp(10).toFloat()
                        }
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(4)
                    })
                }
            })
        })
        addView(Ui.divider(this@FileListActivity, 12).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(dp(12), 0, 0, 0)
            }
        })
            addView(LinearLayout(this@FileListActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(iconActionBtn(Ui.lucideEye(this@FileListActivity, Ui.PRIMARY, 16), getString(R.string.files_view), Ui.PRIMARY) {
                    openAttachment(f.name)
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) })
                addView(iconActionBtn(Ui.lucideDownload(this@FileListActivity, Ui.PRIMARY, 16), getString(R.string.files_export), Ui.PRIMARY) {
                    exportAttachment(f.name)
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) })
                addView(iconActionBtn(Ui.lucideTrash(this@FileListActivity, Ui.DANGER, 16), getString(R.string.files_delete), Ui.DANGER) {
                    confirmDelete(f.name)
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
            })
        }
    }

    /** 操作按钮: Lucide 图标 + 文字, 无边框幽灵按钮 */
    private fun iconActionBtn(icon: android.graphics.drawable.BitmapDrawable, label: String, color: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            Ui.press(this)
            setOnClickListener { onClick() }
            addView(ImageView(this@FileListActivity).apply {
                setImageDrawable(icon)
            })
            addView(TextView(this@FileListActivity).apply {
                text = label
                textSize = 12f
                setTextColor(color)
                setPadding(dp(4), 0, 0, 0)
            })
        }
    }

    /** 查看: 通过 LocalFileProvider(content://<pkg>.files/<name>) 交给系统查看器 */
    private fun openAttachment(fileName: String) {
        try {
            val uri = Uri.parse("content://${packageName}.files/${Uri.encode(fileName)}")
            val it = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, AttachmentStore.mimeOf(fileName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(it)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.files_open_fail, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    /** 导出到工作目录(显式授权暴露到公共目录的审计动作) */
    private fun exportAttachment(fileName: String) {
        val f = AttachmentStore.fileOf(this, fileName)
        if (f == null) {
            Toast.makeText(this, getString(R.string.files_not_found), Toast.LENGTH_SHORT).show()
            return
        }
        val outName = displayName(fileName)
        val bytes = try { f.readBytes() } catch (e: Exception) { null }
        if (bytes == null) {
            Toast.makeText(this, getString(R.string.files_export_fail, ""), Toast.LENGTH_SHORT).show()
            return
        }
        val ok = WorkDir.write(this, outName, bytes, WorkDir.SUB_DIR_FILES)
        Toast.makeText(this, if (ok) getString(R.string.files_exported, "${WorkDir.displaySubPath(WorkDir.SUB_DIR_FILES)}$outName")
            else getString(R.string.files_export_fail, ""), Toast.LENGTH_SHORT).show()
    }

    /** 删除确认(附件不可恢复) */
    private fun confirmDelete(fileName: String) {
        val (dlg, box) = Ui.dialog(this, getString(R.string.files_delete_title))
        box.addView(Ui.dialogText(this, getString(R.string.files_delete_msg, displayName(fileName))))
        box.addView(Ui.dangerBtn(this, getString(R.string.files_delete_confirm)) {
            dlg.dismiss()
            val ok = AttachmentStore.delete(this, fileName)
            Toast.makeText(this, if (ok) getString(R.string.files_deleted)
                else getString(R.string.files_delete_fail), Toast.LENGTH_SHORT).show()
            refresh()
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        box.addView(Ui.dialogCancelBtn(this, getString(R.string.dialog_cancel)) { dlg.dismiss() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        dlg.show()
    }

    /** 落库 fileName = <时间戳>_<uuid8>_<原名>, 展示时去前缀还原原始名 */
    private fun displayName(fileName: String): String {
        val n = fileName.substringAfter('_').substringAfter('_')
        return if (n.isNotBlank()) n else fileName
    }

    private fun humanSize(size: Long): String = when {
        size >= 1024 * 1024 -> String.format(Locale.US, "%.1fMB", size / 1024.0 / 1024.0)
        size >= 1024 -> String.format(Locale.US, "%.1fKB", size / 1024.0)
        else -> "${size}B"
    }

    private fun fmtDuration(sec: Long): String {
        val s = sec % 60
        val m = sec / 60
        return if (m > 0) "${m}分${s}秒" else "${s}秒"
    }

    private fun badgeOf(mime: String): String = when {
        mime.startsWith("video/") -> "视频"
        mime.startsWith("image/") -> "图片"
        mime.startsWith("audio/") -> "音频"
        else -> "文件"
    }

    /** 文件类型对应图标资源（AI 产物由调用方换色） */
    private fun iconResOf(mime: String): Int = when {
        mime.startsWith("video/") -> R.drawable.ic_upload_video
        mime.startsWith("image/") -> R.drawable.ic_upload_image
        mime.startsWith("audio/") -> R.drawable.ic_perm_mic
        else -> R.drawable.ic_upload_file
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
