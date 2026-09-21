# -*- coding: utf-8 -*-
import io, sys

def patch(path, edits):
    with io.open(path, 'r', encoding='utf-8') as f:
        src = f.read()
    for old, new, tag in edits:
        cnt = src.count(old)
        if cnt != 1:
            print("[WARN] %s: anchor '%s' count=%d (expect 1)" % (tag, old[:50].replace('\n','\\n'), cnt))
            continue
        src = src.replace(old, new)
        print("[OK] %s" % tag)
    with io.open(path, 'w', encoding='utf-8') as f:
        f.write(src)

BASE = "/home/ymz/Nyral/android-agent-app/app/src/main/java/all/"

# ============ MainActivity.kt ============
main_edits = []

# 1. onCreate 注册 AvatarConfig
main_edits.append((
"""        ModeConfig.init(this)
        LogStore.init(this)""",
"""        ModeConfig.init(this)
        AvatarConfig.init(this)
        LogStore.init(this)""",
"MainActivity onCreate init AvatarConfig"))

# 2. aiAvatar 支持自定义头像
main_edits.append((
"""    private fun aiAvatar(): TextView = TextView(this@MainActivity).apply {
        val s = dp(30)
        val label = ApiConfig.providerLabel(ApiConfig.providerId())
        text = (label.take(1).ifBlank { "A" }).uppercase()
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#5A6478"))
        }
        layoutParams = LinearLayout.LayoutParams(s, s).apply {
            bottomMargin = dp(4)
        }
    }""",
"""    private fun aiAvatar(): TextView = TextView(this@MainActivity).apply {
        val s = dp(30)
        val custom = AvatarConfig.avatarDrawable(AvatarConfig.aiAvatarFile(), s)
        if (custom != null) {
            background = custom
        } else {
            val label = ApiConfig.providerLabel(ApiConfig.providerId())
            text = (label.take(1).ifBlank { "A" }).uppercase()
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#5A6478"))
            }
        }
        layoutParams = LinearLayout.LayoutParams(s, s).apply {
            bottomMargin = dp(4)
        }
    }""",
"MainActivity aiAvatar 支持自定义头像"))

# 3. userAvatar 支持自定义头像
main_edits.append((
"""    private fun userAvatar(): TextView = TextView(this@MainActivity).apply {
        val s = dp(30)
        text = "我"
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#4A90D9"))
        }
        layoutParams = LinearLayout.LayoutParams(s, s)
    }""",
"""    private fun userAvatar(): TextView = TextView(this@MainActivity).apply {
        val s = dp(30)
        val custom = AvatarConfig.avatarDrawable(AvatarConfig.userAvatarFile(), s)
        if (custom != null) {
            background = custom
        } else {
            text = "我"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#4A90D9"))
            }
        }
        layoutParams = LinearLayout.LayoutParams(s, s)
    }""",
"MainActivity userAvatar 支持自定义头像"))

patch(BASE + "MainActivity.kt", main_edits)

# ============ SettingsActivity.kt ============
set_edits = []

# 1. import Uri
set_edits.append((
"""import android.graphics.Color
import android.os.Bundle""",
"""import android.graphics.Color
import android.net.Uri
import android.os.Bundle""",
"SettingsActivity import Uri"))

# 2. 字段
set_edits.append((
"""    private lateinit var aiPersonaSub: TextView
    private lateinit var uploadSizeSub: TextView""",
"""    private lateinit var aiPersonaSub: TextView
    private lateinit var uploadSizeSub: TextView
    private lateinit var avatarUserSub: TextView
    private lateinit var avatarAiSub: TextView""",
"SettingsActivity 字段 avatarUserSub/avatarAiSub"))

# 3. onCreate init AvatarConfig
set_edits.append((
"""        PersonaConfig.init(this)
        Ui.statusBar(this)""",
"""        PersonaConfig.init(this)
        AvatarConfig.init(this)
        Ui.statusBar(this)""",
"SettingsActivity onCreate init AvatarConfig"))

# 4. 外观分组插入用户头像 / AI 头像
set_edits.append((
"""        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsSwitch("聊天模式", "并排头像 · 纯文本正文", "💬", 15, ModeConfig.chatMode()) { on ->""",
"""        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsItem("用户头像", "默认文字头像（我）", "👤", 16, {
            showAvatarDialog(false)
        }) { avatarUserSub = it })
        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsItem("AI 头像", "默认文字头像（AI）", "🤖", 17, {
            showAvatarDialog(true)
        }) { avatarAiSub = it })
        cardLook.addView(Ui.divider(this))
        cardLook.addView(settingsSwitch("聊天模式", "并排头像 · 纯文本正文", "💬", 15, ModeConfig.chatMode()) { on ->""",
"SettingsActivity 外观分组插入头像入口"))

# 5. 新增函数
set_edits.append((
"""    private fun settingsItem(title: String, subtitle: String, icon: String, seed: Int, onClick: () -> Unit, subRef: ((TextView) -> Unit)? = null): LinearLayout {""",
"""    private var pendingAvatarIsAi = false
    private val REQ_AVATAR_USER = 3001
    private val REQ_AVATAR_AI = 3002

    private fun refreshAvatarSubs() {
        if (::avatarUserSub.isInitialized)
            avatarUserSub.text = if (AvatarConfig.hasUserAvatar()) "已设置自定义头像" else "默认文字头像（我）"
        if (::avatarAiSub.isInitialized)
            avatarAiSub.text = if (AvatarConfig.hasAiAvatar()) "已设置自定义头像" else "默认文字头像（AI）"
    }

    private fun showAvatarDialog(isAi: Boolean) {
        val has = if (isAi) AvatarConfig.hasAiAvatar() else AvatarConfig.hasUserAvatar()
        val (dlg, box) = Ui.dialog(this, if (isAi) "更换 AI 头像" else "更换用户头像", jellyOvershoot = 1.4f, animate = false)
        box.addView(Ui.hint(this, if (has) "当前为自定义头像" else "当前为默认文字头像"))
        val vstack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        vstack.addView(Ui.dialogCancelBtn(this@SettingsActivity, "从相册选择图片", {
            dlg.dismiss()
            pendingAvatarIsAi = isAi
            val i = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            try {
                startActivityForResult(i, if (isAi) REQ_AVATAR_AI else REQ_AVATAR_USER)
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "无法打开相册: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }))
        if (has) {
            val restoreBtn = Ui.dangerBtn(this@SettingsActivity, "恢复默认", {
                if (isAi) AvatarConfig.clearAiAvatar() else AvatarConfig.clearUserAvatar()
                dlg.dismiss()
                refreshAvatarSubs()
                Toast.makeText(this@SettingsActivity, "已恢复默认头像", Toast.LENGTH_SHORT).show()
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
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val ok = if (pendingAvatarIsAi) AvatarConfig.setAiAvatar(data.data!!) else AvatarConfig.setUserAvatar(data.data!!)
        Toast.makeText(this, if (ok) "头像已更新" else "头像设置失败", Toast.LENGTH_SHORT).show()
        refreshAvatarSubs()
    }

    private fun settingsItem(title: String, subtitle: String, icon: String, seed: Int, onClick: () -> Unit, subRef: ((TextView) -> Unit)? = null): LinearLayout {""",
"SettingsActivity 新增 showAvatarDialog/onActivityResult"))

patch(BASE + "SettingsActivity.kt", set_edits)
print("DONE")
