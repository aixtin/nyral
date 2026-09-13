package io.github.aixtin.nyral

/**
 * 登录态判定纯函数(无 Android 依赖, 可 JVM 单测)。
 * 供 BrowserPage 自动落盘判定 与 web_fetch 登录态失效检测 复用。
 */
object SiteAuthDetector {

    /** 登录凭证特征 cookie 名关键词(命中即视为真登录态) */
    val LOGIN_COOKIE_KEYWORDS = listOf(
        "token", "bduss", "stoken", "bili_jct", "logged_in", "passport",
        "session", "csrf", "userinfo", "username", "usernick", "user_id", "userid",
        "wordpress_logged", "_m_h5_tk", "sso"
    )

    /** 纯匿名/统计/反爬/埋点 cookie 名(即使存在也不算登录态) */
    val ANONYMOUS_COOKIE_KEYS = listOf(
        "baiduid", "bidupsid", "h_ps_pssid", "baidu_ssp", "baidu_bfess",
        "_ga", "_gid", "_gat", "_hjid", "_hjssc", "_hjuser",
        "__ddg", "hm_lvt", "hm_lpvt", "hmaccount", "hmct",
        "muid", "srchuid", "_edge_s", "_edge_v", "_ss", "srchd", "usrloc", "bfbusr",
        "dc_session_id", "dc_sid", "dc_tos", "dc_dsid", "dc_cid", "waf_captcha",
        "c_first", "c_ref", "c_pref", "c_page_id", "c_segment", "c_dsid", "c_cs_c",
        "log_id", "creative_btn", "hide_login", "fid", "uuid_tt",
        "acw_sc__v2", "cookie_session", "cf_clearance", "__cf_bm", "1p_jar", "enid", "nip", "nid",
        "__utm", "_pk_", "_fbp", "_gcl"
    )

    /**
     * 判定 cookie 串是否含真实登录态: 排除匿名/统计 cookie 后, 存在登录凭证特征才返回 true。
     * 注意: 百度未登录也会种 PSTM/COOKIE_SESSION, 已分别通过 不在白名单/入黑名单 排除(历史踩坑回归)。
     */
    fun hasLoginCookies(ck: String): Boolean {
        val parts = ck.split(';')
            .map { it.trim().substringBefore('=').trim().lowercase() }
            .filter { it.isNotBlank() }
        val meaningful = parts.filter { p ->
            !ANONYMOUS_COOKIE_KEYS.any { p.contains(it) }
        }
        if (meaningful.isEmpty()) return false
        return meaningful.any { p ->
            LOGIN_COOKIE_KEYWORDS.any { p.contains(it) }
        }
    }
}
