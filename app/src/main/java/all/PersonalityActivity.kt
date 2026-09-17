package io.github.aixtin.nyral

import io.github.aixtin.nyral.R

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * AI 个性化（设置 → 外观 → AI 个性化）— 资料卡式三级页面。
 * - 顶部资料卡：AI 头像（左）/ 用户头像（右）左右并排，点击各自头像弹更换窗口
 * - 下方分组：AI 名字（点击输入框弹窗）、AI 人设（页面内平面化输入框）
 * 头像选择/裁剪流程与逻辑在此页自持（相册选图→拷私有目录→裁剪→落盘）。
 */
class PersonalityActivity : Activity() {

    private lateinit var aiNameInput: EditText
    private var aiNameReady = false
    private lateinit var aiPersonaInput: EditText
    private var personaInputReady = false
    private var pendingPickFile: File? = null
    private var pendingAvatarIsAi = false
    private var personaCardContainer: LinearLayout? = null
    private var personaCardRef: LinearLayout? = null

    private val REQ_AVATAR_USER = 3001
    private val REQ_AVATAR_AI = 3002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PersonaConfig.init(this)
        AvatarConfig.init(this)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.persona_title)))

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 资料卡：AI(左) / 用户(右) 双头像并排 ----
        personaCardContainer = content
        personaCardRef = personaCard()
        content.addView(personaCardRef)

        // ---- 分组：身份信息 ----
        content.addView(Ui.groupLabel(this, getString(R.string.persona_group_info)))
        val cardInfo = Ui.card(this)
        // AI 名字：平面化输入框（内嵌编辑，仿 AI 人设，无需弹窗）
        cardInfo.addView(TextView(this).apply {
            text = getString(R.string.persona_ai_name)
            textSize = 15f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(12), dp(12), 0)
        })
        cardInfo.addView(aiNameEdit(PersonaConfig.aiName()))
        cardInfo.addView(aiNameHint())

        // AI 人设：平面化输入框（直接内嵌编辑，无需弹窗）
        cardInfo.addView(Ui.divider(this))
        cardInfo.addView(TextView(this).apply {
            text = getString(R.string.persona_ai_persona)
            textSize = 15f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(12), dp(12), 0)
        })
        cardInfo.addView(personaEdit(PersonaConfig.aiPersona(), { input ->
            aiPersonaInput = input
        }))
        cardInfo.addView(personaHint())
        content.addView(cardInfo)

        setContentView(root)
    }

    /** 资料卡：AI 头像（左）/ 用户头像（右）左右并排，点击各自头像弹出更换窗口 */
    private fun personaCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, 0)
        background = Ui.rounded(Ui.SURFACE, 16, this@PersonalityActivity)
        // 左：AI 头像
        addView(avatarCell(
            avatar = avatarBig(true),
            name = PersonaConfig.aiName(),
            hint = if (AvatarConfig.hasAiAvatar()) getString(R.string.persona_hint_avatar_set) else getString(R.string.persona_hint_change),
            isAi = true
        ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 中间竖分隔线
        addView(View(this@PersonalityActivity).apply {
            setBackgroundColor(Ui.INPUT_BG)
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(96))
        })
        // 右：用户头像
        addView(avatarCell(
            avatar = avatarBig(false),
            name = getString(R.string.persona_me),
            hint = if (AvatarConfig.hasUserAvatar()) getString(R.string.persona_hint_avatar_set) else getString(R.string.persona_hint_change),
            isAi = false
        ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** 单个头像单元：大头像 + 名字 + 提示，整块可点击 */
    private fun avatarCell(avatar: View, name: String, hint: String, isAi: Boolean): LinearLayout =
        LinearLayout(this@PersonalityActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(18), dp(20), dp(18))
            isClickable = true
            setOnClickListener { showAvatarDialog(isAi) }
            Ui.press(this)
            addView(avatar)
            addView(TextView(this@PersonalityActivity).apply {
                text = name
                textSize = 16f
                setTextColor(Ui.TEXT)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            })
            addView(TextView(this@PersonalityActivity).apply {
                text = hint
                textSize = 12f
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            })
        }

    /** 大头像：有自定义头像用圆形图片，否则文字占位（同聊天页视觉） */
    private fun avatarBig(isAi: Boolean): TextView {
        val s = dp(64)
        val file = if (isAi) AvatarConfig.aiAvatarFile() else AvatarConfig.userAvatarFile()
        val custom = AvatarConfig.avatarDrawable(file, s)
        return TextView(this).apply {
            if (custom != null) {
                background = custom
            } else {
                if (isAi) {
                    text = "N"
                    textSize = 26f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#5A6478"))
                    }
                } else {
                    text = getString(R.string.persona_me)
                    textSize = 26f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Ui.PRIMARY)
                    }
                }
            }
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
    }

    /** AI 名字：平面化单行输入框（浅灰圆角底，无边框，仿 AI 人设样式） */
    private fun aiNameEdit(current: String): EditText {
        aiNameReady = false
        val et = Ui.input(this, getString(R.string.persona_hint_ai_name)).apply {
            setText(if (current.isEmpty()) "" else current)
            setSelection(text.length)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = Ui.rounded(Ui.INPUT_BG, 12, this@PersonalityActivity)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), dp(8), dp(12), 0)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length ?: 0 > 20) {
                        val keep = s!!.substring(0, 20)
                        setText(keep)
                        setSelection(keep.length)
                    }
                }
            })
        }
        aiNameInput = et
        aiNameReady = true
        return et
    }

    /** AI 名字：提示行（保存按钮已合并到 AI 人设区，随人设一起保存） */
    private fun aiNameHint(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), dp(14))
        addView(TextView(this@PersonalityActivity).apply {
            text = getString(R.string.persona_hint_ai_name_sub)
            textSize = 12f
            setTextColor(Ui.SUB)
        })
    }

    /** AI 人设：平面化多行输入框（浅灰圆角底，无边框） */
    private fun personaEdit(current: String, ref: (EditText) -> Unit): EditText {
        personaInputReady = false
        val et = Ui.input(this, getString(R.string.persona_hint_ai_persona)).apply {
            setText(if (current.isEmpty()) "" else current)
            setSelection(text.length)
            setSingleLine(false)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            background = Ui.rounded(Ui.INPUT_BG, 12, this@PersonalityActivity)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), dp(8), dp(12), 0)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length ?: 0 > 200) {
                        val keep = s!!.substring(0, 200)
                        setText(keep)
                        setSelection(keep.length)
                    }
                }
            })
        }
        ref(et)
        personaInputReady = true
        return et
    }

    /** AI 人设：保存按钮 */
    private fun personaHint(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(14))
        addView(TextView(this@PersonalityActivity).apply {
            text = getString(R.string.persona_hint_ai_persona_sub)
            textSize = 12f
            setTextColor(Ui.SUB)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@PersonalityActivity).apply {
            text = getString(R.string.settings_btn_save)
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.rounded(Ui.PRIMARY, 18, this@PersonalityActivity)
            setPadding(dp(18), dp(7), dp(18), dp(7))
            isClickable = true
            Ui.press(this)
            setOnClickListener {
                if (personaInputReady && aiNameReady) {
                    PersonaConfig.setAiPersona(aiPersonaInput.text.toString().trim())
                    PersonaConfig.setAiName(aiNameInput.text.toString().trim())
                    rebuildPersonaCard()
                    Toast.makeText(this@PersonalityActivity, getString(R.string.persona_toast_saved), Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    // ==================== 头像更换（含相册→裁剪→落盘） ====================

    private fun showAvatarDialog(isAi: Boolean) {
        val has = if (isAi) AvatarConfig.hasAiAvatar() else AvatarConfig.hasUserAvatar()
        val (dlg, box) = Ui.dialog(this, if (isAi) getString(R.string.persona_change_ai) else getString(R.string.persona_change_user), jellyOvershoot = 1.4f, animate = false)
        box.addView(Ui.hint(this, if (has) getString(R.string.persona_current_custom) else getString(R.string.persona_current_default)))
        val vstack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        vstack.addView(Ui.dialogCancelBtn(this, getString(R.string.persona_pick_album), {
            dlg.dismiss()
            pendingAvatarIsAi = isAi
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            try {
                startActivityForResult(i, if (isAi) REQ_AVATAR_AI else REQ_AVATAR_USER)
            } catch (e: Exception) {
                Toast.makeText(this@PersonalityActivity, getString(R.string.persona_err_album, e.message), Toast.LENGTH_SHORT).show()
            }
        }))
        if (has) {
            val restoreBtn = Ui.dangerBtn(this, getString(R.string.persona_restore_default), {
                if (isAi) AvatarConfig.clearAiAvatar() else AvatarConfig.clearUserAvatar()
                dlg.dismiss()
                rebuildPersonaCard()
                Toast.makeText(this@PersonalityActivity, getString(R.string.persona_toast_restored), Toast.LENGTH_SHORT).show()
            })
            restoreBtn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            vstack.addView(restoreBtn)
        }
        box.addView(vstack)
        dlg.show()
    }

    /** 重建资料卡头部，使头像与名字同步刷新 */
    private fun rebuildPersonaCard() {
        personaCardContainer?.let { container ->
            val idx = container.indexOfChild(personaCardRef)
            if (idx >= 0) {
                container.removeViewAt(idx)
                container.addView(personaCard(), idx)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return

        // 裁剪页返回：拿到 1:1 正方形临时文件并落盘
        if (requestCode == AvatarCropActivity.REQ_CROP) {
            // 清理选图临时副本(无论成功失败)
            pendingPickFile?.delete()
            pendingPickFile = null
            val path = data.getStringExtra(AvatarCropActivity.EXTRA_RESULT_PATH)
            if (path == null) return
            val file = File(path)
            val ok = if (pendingAvatarIsAi) AvatarConfig.setAiAvatarFromSquare(file)
            else AvatarConfig.setUserAvatarFromSquare(file)
            Toast.makeText(this, if (ok) getString(R.string.persona_avatar_updated) else getString(R.string.persona_avatar_failed), Toast.LENGTH_SHORT).show()
            rebuildPersonaCard()
            file.delete()
            return
        }

        if (data.data == null) return
        // 相册选图返回：先把图片拷贝到应用私有目录再跳裁剪页
        // (直接传相册 Uri 时跨 Activity 的临时读取授权不稳定, 裁剪页二次解码易失败退出; 拷贝后可离线解码)
        val uri = data.data!!
        var localFile: File? = null
        try {
            val dst = File(cacheDir, "avatar_pick_${System.currentTimeMillis()}.img")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out) }
            }
            if (dst.length() > 0) localFile = dst
        } catch (e: Exception) {
            // 拷贝失败(罕见), 回退直接传 Uri, 由裁剪页兜底提示
            localFile = null
        }
        try {
            val i = Intent(this, AvatarCropActivity::class.java).apply {
                putExtra(AvatarCropActivity.EXTRA_URI, localFile?.let { Uri.fromFile(it) } ?: uri)
                putExtra(AvatarCropActivity.EXTRA_IS_AI, pendingAvatarIsAi)
            }
            startActivityForResult(i, AvatarCropActivity.REQ_CROP)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.persona_err_crop, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 列表项与编辑弹窗 ====================

    private fun settingsItem(title: String, subtitle: String, icon: String, seed: Int, onClick: () -> Unit, subRef: ((TextView) -> Unit)? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            setOnClickListener { onClick() }
            Ui.press(this)
            addView(Ui.iconBadge(this@PersonalityActivity, icon, seed))
            addView(LinearLayout(this@PersonalityActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(8), 0)
                }
                addView(Ui.itemTitle(this@PersonalityActivity, title))
                addView(TextView(this@PersonalityActivity).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(Ui.SUB)
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    subRef?.invoke(this)
                })
            })
            addView(Ui.arrow(this@PersonalityActivity))
        }
    }

    private fun showTitleEdit(title: String, current: String, onSave: (String) -> Unit, hint: String = getString(R.string.persona_hint_edit, title), multiline: Boolean = false, maxLength: Int = 0) {
        val (dlg, box) = Ui.dialog(this, getString(R.string.persona_dialog_edit, title), jellyOvershoot = 1.4f, animate = false)
        val input = Ui.input(this, hint).apply {
            setText(current)
            setSelection(text.length)
            if (multiline) {
                setSingleLine(false)
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
            }
        }
        box.addView(input)
        var counter: TextView? = null
        if (maxLength > 0) {
            counter = TextView(this).apply {
                textSize = 11f
                setTextColor(Ui.SUB)
                gravity = Gravity.END
                setPadding(0, dp(4), dp(2), 0)
            }
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val len = s?.length ?: 0
                    if (len > maxLength) {
                        val keep = s!!.substring(0, maxLength)
                        input.setText(keep)
                        input.setSelection(keep.length)
                    }
                    counter?.text = "${input.text.length}/$maxLength"
                }
            })
            counter.text = "${input.text.length}/$maxLength"
            box.addView(counter)
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            addView(Ui.dialogCancelBtn(this@PersonalityActivity, getString(R.string.settings_btn_save), {
                onSave(input.text.toString().trim())
                dlg.dismiss()
            }))
            val cancelBtn = Ui.dialogCancelBtn(this@PersonalityActivity, getString(R.string.dialog_cancel), { dlg.dismiss() })
            cancelBtn.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
            addView(cancelBtn)
        })
        dlg.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
