package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * 聊天背景设置页: 内置浅色预设 + 自定义图片(预模糊, 三档强度)
 * 存储: SharedPreferences("chat_bg") + filesDir/chat_bg_custom.png
 */
class ChatBackgroundActivity : Activity() {

    companion object {
        const val PREFS = "chat_bg"
        const val KEY_TYPE = "bg_type"       // none / preset / custom
        const val KEY_PRESET = "bg_preset"   // 0..N
        const val KEY_BLUR = "bg_blur"       // 1-48: 1原图 48最重(旧数据曾存 0-48 / 旧三档 0/1/2)
        const val KEY_BLUR_V2 = "bg_blur_v2" // true=新格式(1-48直存), 用于区分旧数据, 防止新值被旧兼容逻辑误转
        const val CUSTOM_FILE = "chat_bg_custom.png"
        const val ORIG_FILE = "chat_bg_custom_orig.png"  // 上传原图(未模糊), 调整强度时从它重算, 避免对已模糊图叠加失效
        const val REQ_PICK = 1001

        /** 主界面读取: 返回当前背景类型, 供 MainActivity 应用 */
        fun loadType(c: Context): String =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TYPE, "none") ?: "none"
        fun loadPreset(c: Context): Int =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PRESET, 0)
    }

    // 内置浅色预设: 柔和渐变, 不干扰文字可读性
    private val presets = arrayOf(
        intArrayOf(0xFFF5F7FA.toInt(), 0xFFE4E9F2.toInt()),
        intArrayOf(0xFFFDF6EC.toInt(), 0xFFF5E6D3.toInt()),
        intArrayOf(0xFFF0F7F0.toInt(), 0xFFDCEBDC.toInt()),
        intArrayOf(0xFFF0F4FB.toInt(), 0xFFDCE6F5.toInt()),
        intArrayOf(0xFFFBF0F6.toInt(), 0xFFF0DCE8.toInt()),
        intArrayOf(0xFFF4F0FA.toInt(), 0xFFE4DCF0.toInt())
    )

    private lateinit var blurSeek: SeekBar
    private lateinit var blurValText: TextView
    private lateinit var presetCells: Array<TextView>
    private var currentType = "none"
    private var currentPreset = 0
    private var currentBlur = 16

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.statusBar(this)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        currentType = prefs.getString(KEY_TYPE, "none") ?: "none"
        currentPreset = prefs.getInt(KEY_PRESET, 0)
        currentBlur = prefs.getInt(KEY_BLUR, 16)
        if (!prefs.getBoolean(KEY_BLUR_V2, false)) {
            // 旧数据迁移到 1-48: 旧三档 0/1/2 -> 0/16/32; 0(原图) -> 1; 其余直取
            if (currentBlur <= 2) currentBlur = currentBlur * 16
            if (currentBlur < 1) currentBlur = 1
        } else {
            if (currentBlur < 1) currentBlur = 1
            if (currentBlur > 48) currentBlur = 48
        }

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.cb_title)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // 分组1: 内置预设
        content.addView(Ui.groupLabel(this, getString(R.string.cb_group_preset)))
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = Ui.rounded(Color.WHITE, 16, this@ChatBackgroundActivity)
        }
        presetCells = Array(6) { TextView(this) }
        for (row in 0 until 2) {
            val rowLay = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (row > 0) topMargin = dp(10)
                }
            }
            for (col in 0 until 3) {
                val idx = row * 3 + col
                presetCells[idx] = presetCell(idx)
                rowLay.addView(presetCells[idx], LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                    if (col > 0) marginStart = dp(10)
                })
            }
            grid.addView(rowLay)
        }
        content.addView(grid)

        // 分组2: 自定义图片
        content.addView(Ui.groupLabel(this, getString(R.string.cb_group_custom)))
        val cardImg = Ui.card(this)
        cardImg.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }
                startActivityForResult(intent, REQ_PICK)
            }
            Ui.press(this)
            addView(Ui.iconBadge(this@ChatBackgroundActivity, getString(R.string.cba_img_badge), 4))
            addView(LinearLayout(this@ChatBackgroundActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@ChatBackgroundActivity, getString(R.string.persona_pick_album)))
                addView(Ui.itemSub(this@ChatBackgroundActivity, getString(R.string.cb_sub_auto_blur)))
            })
            addView(Ui.arrow(this@ChatBackgroundActivity))
        })
        content.addView(cardImg)

        // 分组3: 模糊强度
        content.addView(Ui.groupLabel(this, getString(R.string.cb_group_blur)))
        val cardBlur = Ui.card(this)
        cardBlur.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            addView(LinearLayout(this@ChatBackgroundActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Ui.itemTitle(this@ChatBackgroundActivity, getString(R.string.cb_blur_title)))
                blurValText = TextView(this@ChatBackgroundActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(8)
                    }
                    gravity = Gravity.END
                    textSize = 14f
                    setTextColor(0xFF3478F6.toInt())
                }
                addView(blurValText)
            })
            blurSeek = SeekBar(this@ChatBackgroundActivity).apply {
                max = 48
                progress = currentBlur
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2)
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        refreshBlurText(progress)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        currentBlur = (seekBar?.progress ?: 1).coerceIn(1, 48)
                        if (currentType == "custom") {
                            // 关键修复: 从保留的"未模糊原图"重算模糊, 而非对已模糊的 CUSTOM_FILE 再叠加(叠加会导致调低强度无效果)
                            val origF = File(filesDir, ORIG_FILE)
                            if (origF.exists()) {
                                val orig = decodeSampled(origF.absolutePath, 720)
                                if (orig != null) {
                                    val blured = stackBlur(orig, currentBlur)
                                    saveCustom(blured)   // 默认写 CUSTOM_FILE
                                }
                            } else {
                                // 兼容旧数据(未保存原图): 退化为对现有图重糊一次
                                val f = File(filesDir, CUSTOM_FILE)
                                if (f.exists()) {
                                    val src = decodeSampled(f.absolutePath, 720)
                                    if (src != null) {
                                        val blured = stackBlur(src, currentBlur)
                                        saveCustom(blured)
                                    }
                                }
                            }
                        }
                        saveState()
                        Toast.makeText(this@ChatBackgroundActivity,
                            if (currentBlur <= 1) getString(R.string.cb_blur_original) else getString(R.string.cb_blur_level, currentBlur),
                            Toast.LENGTH_SHORT).show()
                    }
                })
            }
            addView(blurSeek)
            addView(Ui.itemSub(this@ChatBackgroundActivity, getString(R.string.cb_blur_hint)))
        })
        content.addView(cardBlur)

        // 分组4: 恢复默认
        content.addView(Ui.groupLabel(this, getString(R.string.cb_group_reset)))
        val cardReset = Ui.card(this)
        cardReset.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            setOnClickListener {
                currentType = "none"
                saveState()
                File(filesDir, CUSTOM_FILE).delete()
                File(filesDir, ORIG_FILE).delete()
                Toast.makeText(this@ChatBackgroundActivity, getString(R.string.cb_toast_restored), Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            Ui.press(this)
            addView(Ui.iconBadge(this@ChatBackgroundActivity, getString(R.string.cba_def_badge), 5))
            addView(LinearLayout(this@ChatBackgroundActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@ChatBackgroundActivity, getString(R.string.cb_reset_title)))
                addView(Ui.itemSub(this@ChatBackgroundActivity, getString(R.string.cb_reset_sub)))
            })
            addView(Ui.arrow(this@ChatBackgroundActivity))
        })
        content.addView(cardReset)

        root.addView(ScrollView(this).apply {
            addView(content)
            isFillViewport = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        refreshAll()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data?.data != null) {
            applyCustomImage(data.data!!)
        }
    }

    private fun presetCell(idx: Int): TextView {
        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 9f
            setTextColor(Color.WHITE)
            isClickable = true
            setOnClickListener {
                currentType = "preset"
                currentPreset = idx
                saveState()
                refreshAll()
                Toast.makeText(this@ChatBackgroundActivity, getString(R.string.cb_toast_preset), Toast.LENGTH_SHORT).show()
            }
            Ui.press(this)
        }
        val gd = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(presets[idx][0], presets[idx][1]))
        gd.cornerRadius = dp(10).toFloat()
        tv.background = gd
        return tv
    }

    private fun refreshBlurText(p: Int) {
        if (!::blurValText.isInitialized) return
        blurValText.text = if (p <= 1) getString(R.string.cb_original) else p.toString()
    }

    /** 强度 1-48: 1=原图(不模糊), 48=最重; 滑块值即真实半径 */

    private fun refreshAll() {
        refreshBlurText(currentBlur)
        for (i in presetCells.indices) {
            val checked = currentType == "preset" && currentPreset == i
            presetCells[i].text = if (checked) "●" else ""
            // 选中项加深边框提示
            val gd = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(presets[i][0], presets[i][1]))
            gd.cornerRadius = dp(10).toFloat()
            gd.setStroke(if (checked) dp(3) else 0, 0xFF3478F6.toInt())
            presetCells[i].background = gd
        }
    }

    private fun applyCustomImage(uri: Uri) {
        try {
            val src = decodeSampledUri(uri, 720)
            if (src == null) { Toast.makeText(this, getString(R.string.cb_err_read), Toast.LENGTH_SHORT).show(); return }
            // 保存未模糊原图, 供后续滑块重算模糊使用(修复: 返回主页后再次调整无效)
            saveCustom(src.copy(Bitmap.Config.ARGB_8888, false), ORIG_FILE)
            val blured = stackBlur(src, currentBlur)
            saveCustom(blured)
            currentType = "custom"
            saveState()
            refreshAll()
            Toast.makeText(this, getString(R.string.cb_toast_custom), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.cb_err_fail, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCustom(bmp: Bitmap, fileName: String = CUSTOM_FILE) {
        val f = File(filesDir, fileName)
        FileOutputStream(f).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        bmp.recycle()
    }

    private fun saveState() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TYPE, currentType)
            .putInt(KEY_PRESET, currentPreset)
            .putInt(KEY_BLUR, currentBlur)
            .putBoolean(KEY_BLUR_V2, true)   // 标记新格式(1-48), 下次进入不再走旧迁移
            .apply()
    }

    // ---------- 图片解码 + StackBlur ----------

    private fun decodeSampledUri(uri: Uri, target: Int): Bitmap? =
        BitmapLoader.decodeSampledUri(this@ChatBackgroundActivity, uri, target)

    private fun decodeSampled(path: String, target: Int): Bitmap? =
        BitmapLoader.decodeSampledFile(java.io.File(path), target)

    /** 预模糊: 缩到中尺寸 -> 3次滑动窗口 BoxBlur(近似高斯, 平滑磨砂) -> 放大回原尺寸
     *  不依赖已废弃 RenderScript, 兼容所有版本 */
    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (radius <= 1) return src.copy(Bitmap.Config.ARGB_8888, false)
        // 1) 缩小到中尺寸(约360宽), 减少计算量且让模糊更平滑
        val scale = 360f / Math.max(w, h)
        val sw = Math.max(1, (w * scale).toInt())
        val sh = Math.max(1, (h * scale).toInt())
        var cur = Bitmap.createScaledBitmap(src, sw, sh, true)
        // 2) BoxBlur 3 次 (半径按中尺寸折算)
        val r = Math.max(2, radius / 3)
        for (i in 0 until 3) cur = boxBlurPass(cur, r)
        // 3) 放大回原尺寸
        val out = Bitmap.createScaledBitmap(cur, w, h, true)
        if (cur !== src && cur !== out) cur.recycle()
        return out
    }

    /** 一次 BoxBlur(水平+垂直滑动窗口, O(n)) */
    private fun boxBlurPass(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val div = radius * 2 + 1
        val out = IntArray(w * h)

        // 水平
        for (y in 0 until h) {
            val off = y * w
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (dx in -radius..radius) {
                val p = pix[off + dx.coerceIn(0, w - 1)]
                aSum += (p ushr 24) and 0xFF
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (x in 0 until w) {
                out[off + x] = ((aSum / div) shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val pOut = pix[off + (x - radius).coerceIn(0, w - 1)]
                val pIn = pix[off + (x + radius + 1).coerceIn(0, w - 1)]
                aSum += ((pIn ushr 24) and 0xFF) - ((pOut ushr 24) and 0xFF)
                rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                bSum += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
        // 垂直
        for (x in 0 until w) {
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (dy in -radius..radius) {
                val p = out[dy.coerceIn(0, h - 1) * w + x]
                aSum += (p ushr 24) and 0xFF
                rSum += (p ushr 16) and 0xFF
                gSum += (p ushr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (y in 0 until h) {
                pix[y * w + x] = ((aSum / div) shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val pOut = out[(y - radius).coerceIn(0, h - 1) * w + x]
                val pIn = out[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                aSum += ((pIn ushr 24) and 0xFF) - ((pOut ushr 24) and 0xFF)
                rSum += ((pIn ushr 16) and 0xFF) - ((pOut ushr 16) and 0xFF)
                gSum += ((pIn ushr 8) and 0xFF) - ((pOut ushr 8) and 0xFF)
                bSum += (pIn and 0xFF) - (pOut and 0xFF)
            }
        }
        return Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
