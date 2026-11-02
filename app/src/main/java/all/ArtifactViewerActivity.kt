package io.github.aixtin.nyral

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import android.widget.ImageView
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 产物查看页(2026-11-02 产物通道): AI 登记的产物全屏查看。
 * - text/html 与 SVG: 沙箱 WebView 渲染(JS 开启支持交互; 无 baseURL; 拦截全部子请求断网,
 *   页面只能自娱, 不能外联/读本地文件)
 * - 其余文本类(代码/Markdown/JSON): 等宽文本查看
 * - 底部操作: 保存(图片进相册 Pictures/Nyral, 其余进 Download/Nyral) / 分享 / 复制
 */
class ArtifactViewerActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var fileName: String
    private var fileText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApiConfig.init(this)
        Ui.statusBar(this)

        val fn = intent.getStringExtra("file_name")
        if (fn.isNullOrBlank()) { finish(); return }
        fileName = fn
        val f = AttachmentStore.fileOf(this, fileName)
        if (f == null) {
            Toast.makeText(this, "产物文件不存在", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val meta = ArtifactStore.metaOf(this, fileName)
        val artName = meta?.optString("artifact_name", "")?.ifBlank { null } ?: File(fileName).name
        val version = meta?.optInt("artifact_version", 1) ?: 1
        val mime = meta?.optString("mime", "")?.ifBlank { null } ?: AttachmentStore.mimeOf(this, fileName)
        fileText = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { "" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        root.addView(Ui.titleBar(this, "产物"))

        // 标题行: 产物名 + 版本
        root.addView(TextView(this).apply {
            text = "$artName  ·  第 $version 版"
            textSize = 13f
            setTextColor(Ui.SUB)
            setPadding(dp(20), dp(10), dp(20), dp(10))
        })

        val card = FrameLayout(this).apply {
            setBackgroundColor(Ui.SURFACE)
            background = Ui.rounded(Ui.SURFACE, 16, this@ArtifactViewerActivity)
            clipToOutline = true
        }
        val contentWrap = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(dp(12), 0, dp(12), dp(8)) }

        if (mime == "text/html" || mime == "image/svg+xml") {
            val wv = WebView(this).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                // 断网沙箱: 拦截主文档以外的全部子请求(网络/相对路径均返回空), 页面无法外联
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                loadDataWithBaseURL(null, fileText, if (mime == "image/svg+xml") "image/svg+xml" else "text/html", "utf-8", null)
            }
            card.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else if (mime.startsWith("image/")) {
            // 图片产物: 直接解码位图显示, 不再显示乱码文本
            val sv = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
            val bmp = try { android.graphics.BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null }
            if (bmp == null) {
                sv.addView(TextView(this).apply {
                    text = "图片解码失败(${f.length()} 字节), 请用分享按钮调起外部应用查看"
                    textSize = 13f
                    setTextColor(Color.parseColor("#222222"))
                    setPadding(dp(14), dp(12), dp(14), dp(16))
                })
            } else {
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(8), dp(8), dp(8), dp(4)) })
                col.addView(TextView(this).apply {
                    text = "${f.length()} 字节"
                    textSize = 11f
                    setTextColor(Color.parseColor("#999999"))
                    setPadding(dp(14), dp(4), dp(14), dp(12))
                })
                sv.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            card.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
} else if (mime.contains("pdf") || fileName.endsWith(".pdf", true)) {
            // PDF: 用系统 PdfRenderer 逐页渲染成位图, 不再显示源码文本
            val sv = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#808080")) }
            val pages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            try {
                val fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(fd)
                var pi = 0
                val targetW = resources.displayMetrics.widthPixels - dp(24)
                while (pi < renderer.pageCount) {
                    val page = renderer.openPage(pi)
                    try {
                        val bw = page.width.coerceAtLeast(1)
                        val bh = page.height.coerceAtLeast(1)
                        val scale = targetW.toFloat() / bw
                        val bmp = Bitmap.createBitmap(targetW, (bh * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages.addView(ImageView(this).apply {
                            setImageBitmap(bmp)
                            adjustViewBounds = true
                        }, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, dp(6), 0, dp(6)) })
                    } finally {
                        page.close()
                    }
                    pi++
                }
                renderer.close()
            } catch (e: Exception) {
                pages.addView(TextView(this).apply {
                    text = "PDF 渲染失败: ${e.message}\n文件大小: ${f.length()} 字节"
                    textSize = 13f
                    setTextColor(Color.parseColor("#222222"))
                    setPadding(dp(14), dp(12), dp(14), dp(16))
                })
            }
            sv.addView(pages, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            val sv = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
            sv.addView(TextView(this).apply {
                text = fileText.ifBlank { "(二进制或空内容, 请用分享按钮调起外部应用查看)" }
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#222222"))
                setPadding(dp(14), dp(12), dp(14), dp(16))
                setTextIsSelectable(true)
            })
            card.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(card, contentWrap)

        // 底部操作条
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BG)
        }
        fun act(label: String, weight: Float, primary: Boolean = false, onClick: () -> Unit): Button =
            Button(this).apply {
                text = label
                textSize = 14f
                isAllCaps = false
                stateListAnimator = null
                minWidth = 0
                minimumWidth = 0
                setTextColor(if (primary) Color.WHITE else Ui.TEXT)
                background = Ui.rounded(if (primary) Ui.PRIMARY else Ui.INPUT_BG, 12, this@ArtifactViewerActivity)
                setPadding(dp(10), dp(11), dp(10), dp(11))
                setOnClickListener { onClick() }
            }.also { b ->
                bar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                    .apply { setMargins(dp(5), 0, dp(5), 0) })
            }

        act("保存", 1f, primary = true) {
            val bytes = try { AttachmentStore.fileOf(this, fileName)?.readBytes() } catch (e: Exception) { null }
            if (bytes == null) Toast.makeText(this, "读取失败", Toast.LENGTH_SHORT).show()
            else {
                // 导出名优先用产物注册名(干净无内部前缀); 无扩展名时退回存储文件名
                val meta = ArtifactStore.metaOf(this, fileName)
                val artName = meta?.optString("artifact_name", "")?.ifBlank { null }
                val disp = if (artName != null && artName.contains(".")) artName else File(fileName).name
                val path = WorkDir.export(this, disp, bytes)
                Toast.makeText(this,
                    if (path != null) "已保存到 $path$disp"
                    else "保存失败",
                    Toast.LENGTH_LONG).show()
            }
        }
        act("分享", 1f) {
            val ff = AttachmentStore.fileOf(this, fileName)
            if (ff == null) { Toast.makeText(this, "产物文件不存在", Toast.LENGTH_SHORT).show(); return@act }
            val uri = android.net.Uri.parse("content://${packageName}.files/${android.net.Uri.encode(ff.name)}")
            val share = Intent(Intent.ACTION_SEND).apply {
                type = AttachmentStore.mimeOf(this@ArtifactViewerActivity, fileName)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "分享产物"))
        }
        act("复制内容", 1f) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("nyral_artifact", fileText))
            Toast.makeText(this, "内容已复制(${fileText.length} 字符)", Toast.LENGTH_SHORT).show()
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }
}
