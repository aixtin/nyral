package io.github.aixtin.nyral

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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * 我的资料 — 点 AI 个性化页顶部用户头像进入的独立用户资料页。
 * - 我的头像：居中大头像 + 名字（默认空，填了才显示）+ 提示，整块点击弹更换窗口
 *   （相册选图→裁剪→落盘，恢复默认在弹窗内，与 AI 个性化页同构）
 * - 我的名字 / 我的爱好：平面化输入框，保存按钮一并落盘
 * - 保存成功 / 头像变更后 setResult(RESULT_OK)，供 AI 个性化页刷新顶部资料卡
 */
class UserProfileActivity : Activity() {

    private lateinit var userNameInput: EditText
    private var userNameReady = false
    private lateinit var userHobbyInput: EditText
    private var userHobbyReady = false
    private var avatarCardRef: LinearLayout? = null
    private var pendingPickFile: File? = null

    private val REQ_AVATAR_USER = 3001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserConfig.init(this)
        AvatarConfig.init(this)
        Ui.statusBar(this)

        val root = Ui.pageRoot(this)
        root.addView(Ui.titleBar(this, getString(R.string.persona_group_user)))

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

        // ---- 我的头像 ----
        val cardAvatar = userAvatarCard()
        avatarCardRef = cardAvatar
        content.addView(cardAvatar)

        // ---- 我的名字 + 我的爱好 + 保存 ----
        content.addView(Ui.groupLabel(this, getString(R.string.persona_group_info)))
        val cardInfo = Ui.card(this)
        cardInfo.addView(TextView(this).apply {
            text = getString(R.string.persona_user_name)
            textSize = 15f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(12), dp(12), 0)
        })
        cardInfo.addView(userNameEdit(UserConfig.userName()))
        cardInfo.addView(userNameHint())
        cardInfo.addView(Ui.divider(this))
        cardInfo.addView(TextView(this).apply {
            text = getString(R.string.persona_user_hobby)
            textSize = 15f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(12), dp(12), 0)
        })
        cardInfo.addView(userHobbyEdit(UserConfig.userHobby(), { input ->
            userHobbyInput = input
        }))
        cardInfo.addView(userProfileHint())
        content.addView(cardInfo)

        setContentView(root)
    }

    /** 我的头像卡片：居中大头像 + 名字（默认空，填了才显示）+ 提示，整块点击弹更换窗口（与 AI 侧一致） */
    private fun userAvatarCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = Ui.rounded(Ui.SURFACE, 16, this@UserProfileActivity)
        isClickable = true
        setOnClickListener { showAvatarDialog() }
        Ui.press(this)
        addView(avatarBig(64), LinearLayout.LayoutParams(dp(64), dp(64)))
        // 名字行固定占位：固定行高，空与有字完全同高，输入名字不再撑开推移
        addView(TextView(this@UserProfileActivity).apply {
            text = UserConfig.userName()
            textSize = 16f
            setTextColor(Ui.TEXT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(26)
            ).apply { topMargin = dp(8) }
        })
        addView(TextView(this@UserProfileActivity).apply {
            text = if (AvatarConfig.hasUserAvatar()) getString(R.string.persona_hint_avatar_set) else getString(R.string.persona_hint_change)
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

    /** 大头像：有自定义头像用圆形图片，否则文字占位（同 AI 个性化页视觉） */
    private fun avatarBig(sizeDp: Int): TextView {
        val s = dp(sizeDp)
        val file = AvatarConfig.userAvatarFile()
        val custom = AvatarConfig.avatarDrawable(file, s)
        return TextView(this).apply {
            if (custom != null) {
                background = custom
            } else {
                // 默认文字头像固定为 A（对外昵称 atin 首字母），不随名字变化
                text = "A"
                textSize = 26f * sizeDp / 64f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Ui.PRIMARY)
                }
            }
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
    }

    /** 我的名字：平面化单行输入框（对称 AI 名字样式） */
    private fun userNameEdit(current: String): EditText {
        userNameReady = false
        val et = Ui.input(this, getString(R.string.persona_hint_user_name)).apply {
            setText(if (current.isEmpty()) "" else current)
            setSelection(text.length)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = Ui.rounded(Ui.INPUT_BG, 12, this@UserProfileActivity)
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
        userNameInput = et
        userNameReady = true
        return et
    }

    /** 我的名字：提示行 */
    private fun userNameHint(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), dp(14))
        addView(TextView(this@UserProfileActivity).apply {
            text = getString(R.string.persona_hint_user_name_sub)
            textSize = 12f
            setTextColor(Ui.SUB)
        })
    }

    /** 我的爱好：平面化多行输入框（对称 AI 人设样式） */
    private fun userHobbyEdit(current: String, ref: (EditText) -> Unit): EditText {
        userHobbyReady = false
        val et = Ui.input(this, getString(R.string.persona_hint_user_hobby)).apply {
            setText(if (current.isEmpty()) "" else current)
            setSelection(text.length)
            setSingleLine(false)
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            background = Ui.rounded(Ui.INPUT_BG, 12, this@UserProfileActivity)
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
                    if (s?.length ?: 0 > 100) {
                        val keep = s!!.substring(0, 100)
                        setText(keep)
                        setSelection(keep.length)
                    }
                }
            })
        }
        ref(et)
        userHobbyReady = true
        return et
    }

    /** 我的资料：保存按钮（保存我的名字 + 我的爱好） */
    private fun userProfileHint(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(14))
        addView(TextView(this@UserProfileActivity).apply {
            text = getString(R.string.persona_hint_user_hobby_sub)
            textSize = 12f
            setTextColor(Ui.SUB)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@UserProfileActivity).apply {
            text = getString(R.string.settings_btn_save)
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = Ui.rounded(Ui.PRIMARY, 18, this@UserProfileActivity)
            setPadding(dp(18), dp(7), dp(18), dp(7))
            isClickable = true
            Ui.press(this)
            setOnClickListener {
                if (userHobbyReady && userNameReady) {
                    UserConfig.setUserHobby(userHobbyInput.text.toString().trim())
                    UserConfig.setUserName(userNameInput.text.toString().trim())
                    rebuildAvatarCard()
                    setResult(RESULT_OK)
                    Toast.makeText(this@UserProfileActivity, getString(R.string.persona_toast_user_saved), Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    /** 重建头像卡片，使头像与按钮状态（恢复默认可见性）同步刷新 */
    private fun rebuildAvatarCard() {
        avatarCardRef?.let { card ->
            val container = card.parent as? ViewGroup ?: return@let
            val idx = container.indexOfChild(card)
            if (idx >= 0) {
                container.removeViewAt(idx)
                val fresh = userAvatarCard()
                avatarCardRef = fresh
                container.addView(fresh, idx)
            }
        }
    }

    /** 更换用户头像对话框：相册选图（有自定义头像时附加恢复默认） */
    private fun showAvatarDialog() {
        val has = AvatarConfig.hasUserAvatar()
        val (dlg, box) = Ui.dialog(this, getString(R.string.persona_change_user), jellyOvershoot = 1.4f, animate = false)
        box.addView(Ui.hint(this, if (has) getString(R.string.persona_current_custom) else getString(R.string.persona_current_default)))
        val vstack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        vstack.addView(Ui.dialogCancelBtn(this, getString(R.string.persona_pick_album), {
            dlg.dismiss()
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            try {
                startActivityForResult(i, REQ_AVATAR_USER)
            } catch (e: Exception) {
                Toast.makeText(this@UserProfileActivity, getString(R.string.persona_err_album, e.message), Toast.LENGTH_SHORT).show()
            }
        }))
        if (has) {
            val restoreBtn = Ui.dangerBtn(this@UserProfileActivity, getString(R.string.persona_restore_default), {
                AvatarConfig.clearUserAvatar()
                dlg.dismiss()
                rebuildAvatarCard()
                setResult(RESULT_OK)
                Toast.makeText(this@UserProfileActivity, getString(R.string.persona_toast_restored), Toast.LENGTH_SHORT).show()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return

        // 裁剪页返回：拿到 1:1 正方形临时文件并落盘
        if (requestCode == AvatarCropActivity.REQ_CROP) {
            pendingPickFile?.delete()
            pendingPickFile = null
            val path = data.getStringExtra(AvatarCropActivity.EXTRA_RESULT_PATH)
            if (path == null) return
            val file = File(path)
            val ok = AvatarConfig.setUserAvatarFromSquare(file)
            Toast.makeText(this, if (ok) getString(R.string.persona_avatar_updated) else getString(R.string.persona_avatar_failed), Toast.LENGTH_SHORT).show()
            rebuildAvatarCard()
            setResult(RESULT_OK)
            file.delete()
            return
        }

        if (data.data == null) return
        // 相册选图返回：先把图片拷贝到应用私有目录再跳裁剪页
        val uri = data.data!!
        var localFile: File? = null
        try {
            val dst = File(cacheDir, "avatar_pick_" + System.currentTimeMillis() + ".img")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out) }
            }
            if (dst.length() > 0) localFile = dst
        } catch (e: Exception) {
            localFile = null
        }
        try {
            val i = Intent(this, AvatarCropActivity::class.java).apply {
                putExtra(AvatarCropActivity.EXTRA_URI, localFile?.let { Uri.fromFile(it) } ?: uri)
                putExtra(AvatarCropActivity.EXTRA_IS_AI, false)
            }
            startActivityForResult(i, AvatarCropActivity.REQ_CROP)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.persona_err_crop, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
