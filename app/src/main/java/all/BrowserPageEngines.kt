package io.github.aixtin.nyral

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 搜索引擎: name 显示名, search 搜索模板(含 {q}), home 首页, key 预设标识(空=自定义) */
internal data class Engine(val name: String, val search: String, val home: String, val key: String = "")


internal fun BrowserPage.showEngineTab() {
    engineTitle.visibility = View.VISIBLE
    engineScroll.visibility = View.VISIBLE
    dataScroll.visibility = View.GONE
    thinkScrollBox.visibility = View.GONE
    tabSpacer.visibility = View.GONE
    addTab.visibility = View.VISIBLE
    tabEngine.setTextColor(blue); tabEngine.background = tabOutline(true)
    tabData.setTextColor(gray); tabData.background = tabOutline(false)
    thinkTab.setTextColor(gray); thinkTab.background = tabOutline(false)
}
internal fun BrowserPage.showDataTab() {
    engineTitle.visibility = View.GONE
    engineScroll.visibility = View.GONE
    dataScroll.visibility = View.VISIBLE
    thinkScrollBox.visibility = View.GONE
    tabSpacer.visibility = View.GONE
    addTab.visibility = View.GONE
    tabEngine.setTextColor(gray); tabEngine.background = tabOutline(false)
    tabData.setTextColor(blue); tabData.background = tabOutline(true)
    thinkTab.setTextColor(gray); thinkTab.background = tabOutline(false)
    refreshDataView()
}


/** 引擎管理列表: ★=当前默认, 点击设为默认; 每行可改/删 */
internal fun BrowserPage.refreshEngineList() {
    if (!engineListInitialized) return
    engineList.removeAllViews()

    engineList.addView(TextView(act).apply {
        text = act.getString(R.string.br_add_engine_btn)
        textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(act.dp(10), act.dp(13), act.dp(10), act.dp(13))
        background = GradientDrawable().apply { setColor(blue); cornerRadius = act.dp(14).toFloat() }
        background = GradientDrawable().apply { setColor(blue); cornerRadius = act.dp(10).toFloat() }
        setOnClickListener { showEditEngineDialog(-1) }
        Ui.press(this)
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        leftMargin = act.dp(12); rightMargin = act.dp(12); bottomMargin = act.dp(12)
    })

    for ((i, e) in engines.withIndex()) {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(14), act.dp(12), act.dp(6), act.dp(12))
            background = GradientDrawable().apply { setColor(Ui.INPUT_BG); cornerRadius = act.dp(12).toFloat() }
        }
        row.addView(TextView(act).apply {
            text = (if (i == engineIdx) "★ " else "  ") + engineLabel(e)
            textSize = 14f
            setTextColor(if (i == engineIdx) blue else Ui.TEXT)
            setTypeface(typeface, if (i == engineIdx) Typeface.BOLD else Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (i != engineIdx) {
                    engineIdx = i; saveEngines(); refreshEngineLabel(); refreshEngineList()
                    paintStatus(act.getString(R.string.br_default_engine, e.name))
                }
            }
        })
        row.addView(TextView(act).apply {
            text = act.getString(R.string.br_edit); textSize = 12f; setTextColor(blue)
            setPadding(act.dp(10), act.dp(5), act.dp(10), act.dp(5))
            background = GradientDrawable().apply { setColor(blue); alpha = 26; cornerRadius = act.dp(9).toFloat() }
            setOnClickListener { showEditEngineDialog(i) }
            Ui.press(this)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = act.dp(14) })
        row.addView(TextView(act).apply {
            text = act.getString(R.string.br_delete); textSize = 12f; setTextColor(Ui.DANGER)
            setPadding(act.dp(10), act.dp(5), act.dp(10), act.dp(5))
            background = GradientDrawable().apply { setColor(Ui.DANGER); alpha = 22; cornerRadius = act.dp(9).toFloat() }
            setOnClickListener {
                if (engines.size <= 1) { Toast.makeText(act, act.getString(R.string.br_keep_one), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                engines.removeAt(i)
                if (engineIdx >= engines.size) engineIdx = engines.size - 1
                saveEngines(); refreshEngineLabel(); refreshEngineList()
            }
            Ui.press(this)
        })
        engineList.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = act.dp(12); rightMargin = act.dp(12)
        })
        engineList.addView(View(act).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, act.dp(8))
        })
    }    }

/** 添加(idx=-1)/编辑(idx>=0) 引擎: 名称 + 搜索模板(必须含 {q}) + 首页(可空) */
internal fun BrowserPage.showEditEngineDialog(idx: Int) {
    val isEdit = idx in engines.indices
    val src = if (isEdit) engines[idx] else null
    Dialog(act).apply {
        setTitle(act.getString(if (isEdit) R.string.br_edit_engine else R.string.br_add_engine))
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(18), act.dp(6), act.dp(18), act.dp(12))
        }
        val nameIn = EditText(act).apply { hint = act.getString(R.string.br_name_hint); textSize = 14f }
        val tmplIn = EditText(act).apply { hint = act.getString(R.string.br_tmpl_hint); textSize = 14f }
        val homeIn = EditText(act).apply { hint = act.getString(R.string.br_home_hint); textSize = 14f }
        if (src != null) { nameIn.setText(engineLabel(src)); tmplIn.setText(src.search); homeIn.setText(src.home) }
        box.addView(nameIn)
        box.addView(tmplIn)
        box.addView(homeIn)
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(act).apply {
                text = act.getString(R.string.br_cancel); textSize = 14f; setTextColor(gray); gravity = Gravity.CENTER
                setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(12))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { dismiss() }
                Ui.press(this)
            })
            addView(TextView(act).apply {
                text = act.getString(R.string.br_save); textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(12))
                background = GradientDrawable().apply { setColor(blue); cornerRadius = act.dp(12).toFloat() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    val n = nameIn.text.toString().trim()
                    var s = tmplIn.text.toString().trim()
                    val h0 = homeIn.text.toString().trim()
                    if (n.isEmpty() || s.isEmpty()) { Toast.makeText(act, act.getString(R.string.br_name_tmpl_empty), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    if (!s.contains("{q}")) { Toast.makeText(act, act.getString(R.string.br_tmpl_need_q), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s
                    val h = if (h0.isEmpty()) s.substringBefore("{q}").trimEnd('&', '?') else
                        if (h0.startsWith("http://") || h0.startsWith("https://")) h0 else "https://" + h0
                    if (isEdit) engines[idx] = Engine(n, s, h, src?.let { if (n == engineLabel(it)) it.key else "" } ?: "") else { engines.add(Engine(n, s, h, "")); engineIdx = engines.size - 1 }
                    saveEngines(); refreshEngineLabel(); refreshEngineList()
                    Toast.makeText(act, act.getString(R.string.br_saved), Toast.LENGTH_SHORT).show(); dismiss()
                }
                Ui.press(this)
            })
        }.also { box.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        setContentView(box)
        show()
    }
}

/** 登录数据面板: site_auth 登录站点逐站列出/清除 + 一键清除全部登录态与缓存 */
internal fun BrowserPage.initDataView() { refreshDataView() }
internal fun BrowserPage.refreshDataView() {
    if (!dataBoxInitialized) return
    dataBox.removeAllViews()
    dataBox.addView(TextView(act).apply {
        text = act.getString(R.string.br_data_intro)
        textSize = 12f; setTextColor(Ui.SUB)
        setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
        background = GradientDrawable().apply { setColor(Ui.INPUT_BG); cornerRadius = act.dp(10).toFloat() }
    })
    val auth = parseSiteAuth()
    if (auth == null || auth.length() == 0) {
        dataBox.addView(TextView(act).apply {
            text = act.getString(R.string.br_no_sites)
            textSize = 13f; setTextColor(gray); gravity = Gravity.CENTER
            setPadding(act.dp(10), act.dp(14), act.dp(10), act.dp(14))
        })
    } else {
        val keys = ArrayList<String>()
        val it = auth.keys()
        while (it.hasNext()) keys.add(it.next() as String)
        val metaTs = auth.optLong("__updated_at", 0L)
        if (metaTs > 0) {
            val ts = try {
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(metaTs))
            } catch (e: Exception) { metaTs.toString() }
            dataBox.addView(TextView(act).apply {
                text = act.getString(R.string.br_recent_saved, ts)
                textSize = 11f; setTextColor(gray)
                setPadding(act.dp(10), act.dp(2), act.dp(10), act.dp(2))
            })
        }
        for (site in keys.sorted()) {
            if (site.startsWith("__")) continue
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dp(6), act.dp(5), act.dp(6), act.dp(5))
            }
            row.addView(TextView(act).apply {
                text = site
                textSize = 13f; setTextColor(Ui.TEXT)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(act).apply {
                text = maskCookie(auth.optString(site))
                textSize = 11f; setTextColor(gray)
                setPadding(act.dp(4), act.dp(2), act.dp(4), act.dp(2))
            })
            row.addView(TextView(act).apply {
                text = act.getString(R.string.br_clear); textSize = 12f; setTextColor(Ui.DANGER)
                setPadding(act.dp(8), act.dp(2), act.dp(4), act.dp(2))
                setOnClickListener {
                    val r = WebTools.siteAuth(act, "{\"action\":\"del\",\"site\":\"$site\"}")
                    if (r.contains("已删除")) {
                        expiresDomainCookie(site)
                        Toast.makeText(act, act.getString(R.string.br_site_cleared, site), Toast.LENGTH_SHORT).show()
                        refreshDataView()
                    } else Toast.makeText(act, r, Toast.LENGTH_SHORT).show()
                }
                Ui.press(this)
            })
            dataBox.addView(row)
            dataBox.addView(View(act).apply {
                background = GradientDrawable().apply { setColor(Ui.DIVIDER); setSize(1, 1) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }
    }
    dataBox.addView(TextView(act).apply {
        text = act.getString(R.string.br_clear_all)
        textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
        background = GradientDrawable().apply { setColor(Ui.DANGER); cornerRadius = act.dp(12).toFloat() }
        setOnClickListener {
            runCatching { WorkDir.write(act, "site_auth.json", "{}".toByteArray(Charsets.UTF_8)) }
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            web.clearCache(true)
            Toast.makeText(act, act.getString(R.string.br_cleared_all), Toast.LENGTH_SHORT).show()
            refreshDataView()
        }
        Ui.press(this)
    })
}

/** 读取 site_auth.json 全部站点登录态; 缺失/损坏返回 null */
internal fun BrowserPage.parseSiteAuth(): org.json.JSONObject? {
    val bytes = WorkDir.read(act, "site_auth.json") ?: return null
    return runCatching { org.json.JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
}

internal fun BrowserPage.maskCookie(c: String): String {
    val t = c.trim()
    return if (t.length <= 12) "***" else t.take(8) + "…" + t.takeLast(6)
}

/** 把指定站点在 WebView 里的 Cookie 逐条置过期(等效清除该站 Cookie) */
internal fun BrowserPage.expiresDomainCookie(site: String) {
    val cm = android.webkit.CookieManager.getInstance()
    for (scheme in listOf("https://$site", "http://$site")) {
        val ck = runCatching { cm.getCookie(scheme) }.getOrNull() ?: continue
        if (ck.isBlank()) continue
        for (pair in ck.split(";")) {
            val k = pair.trim().substringBefore("=").trim()
            if (k.isNotBlank()) cm.setCookie(scheme, "$k=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/", null)
        }
    }
}

/** 复制当前访问地址到剪贴板 */
internal fun BrowserPage.copyCurrentUrl() {
    val u = urlView.text.toString().trim()
    if (u.isEmpty() || u == "—") { Toast.makeText(act, act.getString(R.string.br_nothing_copy), Toast.LENGTH_SHORT).show(); return }
    val cm = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("browser_url", u))
    Toast.makeText(act, act.getString(R.string.br_copied, u), Toast.LENGTH_SHORT).show()
}

/** 底部状态条实时回显当前访问站点 */
internal fun BrowserPage.refreshDrawerUrl(url: String?) {
    val s = url ?: return
    val host = runCatching { java.net.URI(s).host }.getOrNull()
    act.runOnUiThread {
        if (drawerStatusInitialized)
            drawerStatus.text = if (host != null) act.getString(R.string.br_visiting, host) else s
        if (urlViewInitialized) urlView.text = s
    }
}

internal fun BrowserPage.refreshEngineLabel() {
    val e = engines.getOrNull(engineIdx)?.let { engineLabel(it) } ?: "──"
    if (drawerEngineTagInitialized) drawerEngineTag.text = "$e ▾"
}

/** 切到另一引擎, 复用当前关键词(空则取搜索框文本, 仍空则开其首页) */
internal fun BrowserPage.gotoEngine(i: Int) {
    if (i !in engines.indices) return
    val kw = lastKeyword
    val e = engines[i]
    val url = if (kw.isEmpty()) e.home else e.search.replace("{q}", java.net.URLEncoder.encode(kw, "UTF-8"))
    web.loadUrl(url)
    engineIdx = i
    if (kw.isNotEmpty()) lastKeyword = kw
    saveEngines(); refreshEngineLabel()
    paintStatus(act.getString(R.string.br_search_fmt, e.name, kw.ifEmpty { act.getString(R.string.br_home) }))
}

/** 本地数据管理入口: v1 说明展示 + 一键清除 WebView 登录会话 */
internal fun BrowserPage.showDataDialog() {
    val e = engines.getOrNull(engineIdx)
    val body = StringBuilder()
    body.append(act.getString(R.string.br_dialog_body1))
    body.append(act.getString(R.string.br_dialog_body2))
    body.append(act.getString(R.string.br_dialog_body3, e?.name ?: act.getString(R.string.br_none)))
    body.append(act.getString(R.string.br_dialog_body4))
    Dialog(act).apply {
        setTitle(act.getString(R.string.br_local_data))
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dp(18), act.dp(10), act.dp(18), act.dp(14))
        }
        box.addView(TextView(act).apply {
            text = body.toString()
            textSize = 13f
            setTextColor(Ui.TEXT)
            background = GradientDrawable().apply {
                setColor(Ui.INPUT_BG)
                cornerRadius = act.dp(10).toFloat()
            }
            setPadding(act.dp(12), act.dp(12), act.dp(12), act.dp(12))
        })
        box.addView(TextView(act).apply {
            text = act.getString(R.string.br_clear_all_sessions)
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
            background = GradientDrawable().apply {
                setColor(Ui.DANGER)
                cornerRadius = act.dp(12).toFloat()
            }
            setOnClickListener {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                web.clearCache(true)
                Toast.makeText(act, act.getString(R.string.br_cleared_all), Toast.LENGTH_SHORT).show()
                dismiss()
            }
            Ui.press(this)
        })
        setContentView(box)
        show()
    }
}

/* ===================== 引擎配置持久化(SharedPreferences) ===================== */

/** 内置预设引擎: 百度/必应/谷歌/搜狗/神马/知乎 */
internal fun BrowserPage.presetEngines(): String =
    """[
      {"n":"百度","k":"baidu","s":"https://www.baidu.com/s?wd={q}","h":"https://www.baidu.com"},
      {"n":"必应","k":"bing","s":"https://www.bing.com/search?q={q}","h":"https://www.bing.com"},
      {"n":"谷歌","k":"google","s":"https://www.google.com/search?q={q}","h":"https://www.google.com"},
      {"n":"搜狗","k":"sogou","s":"https://www.sogou.com/web?query={q}","h":"https://www.sogou.com"},
      {"n":"神马","k":"sm","s":"https://m.sm.cn/s?q={q}","h":"https://m.sm.cn"},
      {"n":"知乎","k":"zhihu","s":"https://www.zhihu.com/search?type=content&q={q}","h":"https://www.zhihu.com"}]"""

internal fun BrowserPage.loadEngines() {
    val sp = act.getSharedPreferences(ENGINE_PREFS, Context.MODE_PRIVATE)
    var saved = sp.getString("engines", null)
    if (saved.isNullOrBlank()) saved = presetEngines()
    try {
        engines.clear()
        val arr = org.json.JSONArray(saved)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val n = o.getString("n")
            val k = o.optString("k", "")
            engines.add(Engine(engineLabelOf(n, k), o.getString("s"), o.optString("h", o.getString("s")), if (k.isNotEmpty()) k else engineKeyOf(n)))
        }
    } catch (e: Exception) {
        engines.clear()
        engines.add(Engine(act.getString(R.string.br_engine_baidu), "https://www.baidu.com/s?wd={q}", "https://www.baidu.com", "baidu"))
    }
    engineIdx = sp.getInt("idx", 0).coerceIn(0, (engines.size - 1).coerceAtLeast(0))
    refreshEngineLabel()
    refreshEngineList()
}

internal fun BrowserPage.saveEngines() {
    val arr = org.json.JSONArray()
    for (e in engines) arr.put(org.json.JSONObject().put("n", e.name).put("k", e.key).put("s", e.search).put("h", e.home))
    act.getSharedPreferences(ENGINE_PREFS, Context.MODE_PRIVATE).edit()
        .putString("engines", arr.toString()).putInt("idx", engineIdx).apply()
}
