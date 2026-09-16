package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)
        val root = Ui.pageRoot(this)

        root.addView(Ui.titleBar(this, getString(R.string.settings_files)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(container)

        emptyView = TextView(this).apply {
            text = getString(R.string.files_empty)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            setPadding(0, dp(48), 0, dp(48))
            visibility = View.GONE
        }
        content.addView(emptyView)

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
        emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        // 按类型分组: 视频 / 图片 / 音频 / 其他
        val groups = listOf(
            "视频" to files.filter { AttachmentStore.mimeOf(it.name).startsWith("video/") },
            "图片" to files.filter { AttachmentStore.mimeOf(it.name).startsWith("image/") },
            "音频" to files.filter { AttachmentStore.mimeOf(it.name).startsWith("audio/") },
            "其他" to files.filter {
                val m = AttachmentStore.mimeOf(it.name)
                !m.startsWith("video/") && !m.startsWith("image/") && !m.startsWith("audio/")
            }
        ).filter { it.second.isNotEmpty() }

        for ((label, list) in groups) {
            container.addView(Ui.groupLabel(this, "$label (${list.size})"))
            for (f in list) {
                container.addView(fileCard(f))
            }
        }
    }

    /** 附件卡片: 类型角标 + 显示名 + 大小/时长 + 操作行(查看/导出/删除) */
    private fun fileCard(f: File): LinearLayout {
        val mime = AttachmentStore.mimeOf(f.name)
        val meta = try { AttachmentStore.metaOf(this, f.name)?.let { JSONObject(it) } } catch (e: Exception) { null }
        val durSec = meta?.optLong("durationSec", 0L) ?: 0L
        val disp = displayName(f.name)
        return Ui.card(this).apply {
            addView(LinearLayout(this@FileListActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@FileListActivity).apply {
                    text = badgeOf(mime)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = Ui.rounded(Color.parseColor("#8A8F9C"), 6, this@FileListActivity)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                })
                addView(TextView(this@FileListActivity).apply {
                    text = disp
                    textSize = 15f
                    setTextColor(0xFF1F2329.toInt())
                    maxLines = 1
                    setPadding(dp(10), 0, 0, 0)
                })
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
                setTextColor(0xFF8A8F9C.toInt())
                setPadding(0, dp(4), 0, 0)
            })
            addView(Ui.divider(this@FileListActivity).apply { setPadding(0, dp(8), 0, dp(8)) })
            addView(LinearLayout(this@FileListActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(actionBtn(getString(R.string.files_view)) {
                    openAttachment(f.name)
                }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(8) })
                addView(actionBtn(getString(R.string.files_export)) {
                    exportAttachment(f.name)
                }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(8) })
                addView(actionBtn(getString(R.string.files_delete), danger = true) {
                    confirmDelete(f.name)
                }, LinearLayout.LayoutParams(0, dp(34), 1f))
            })
        }
    }

    /** 操作按钮: 浅色圆角小按钮 */
    private fun actionBtn(label: String, danger: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (danger) Ui.DANGER else Ui.PRIMARY)
            background = Ui.rounded(
                if (danger) Color.parseColor("#FDECEC") else Ui.PRIMARY_LIGHT, 8, this@FileListActivity)
            isClickable = true
            Ui.press(this)
            setOnClickListener { onClick() }
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
        val ok = WorkDir.write(this, outName, bytes)
        Toast.makeText(this, if (ok) getString(R.string.files_exported, "${WorkDir.displayPath}$outName")
            else getString(R.string.files_export_fail, ""), Toast.LENGTH_SHORT).show()
    }

    /** 删除确认(附件不可恢复) */
    private fun confirmDelete(fileName: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.files_delete_title))
            .setMessage(getString(R.string.files_delete_msg, displayName(fileName)))
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .setPositiveButton(getString(R.string.files_delete_confirm)) { _, _ ->
                val ok = AttachmentStore.delete(this, fileName)
                Toast.makeText(this, if (ok) getString(R.string.files_deleted)
                    else getString(R.string.files_delete_fail), Toast.LENGTH_SHORT).show()
                refresh()
            }
            .show()
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
