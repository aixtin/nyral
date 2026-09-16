package io.github.aixtin.nyral

import io.github.aixtin.nyral.SiteAuthDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SiteAuthDetector 登录态判定单测(2026-09-13, 纯 JVM 运行)。
 * 固化: 百度未登录(仅 PSTM/COOKIE_SESSION)不误判为登录; 含 token/bduss/session/csrf 等真实登录态必判 true。
 */
class SiteAuthDetectorTest {

    @Test
    fun emptyCookie_notLogin() {
        assertFalse(SiteAuthDetector.hasLoginCookies(""))
        assertFalse(SiteAuthDetector.hasLoginCookies("   "))
    }

    @Test
    fun anonymousOnly_notLogin() {
        assertFalse(SiteAuthDetector.hasLoginCookies("BAIDUID=abc123; _ga=GA1.1.1.1"))
        assertFalse(SiteAuthDetector.hasLoginCookies("_gid=1; _gat=1; __ddgid=xx"))
        assertFalse(SiteAuthDetector.hasLoginCookies("Hm_lvt_1=1; Hm_lpvt_1=2; HMACCOUNT=xx"))
    }

    @Test
    fun baiduNotLoggedIn_onlyPstmCookieSession_notLogin() {
        assertFalse(SiteAuthDetector.hasLoginCookies("BAIDUID=xx; PSTM=1690000000; COOKIE_SESSION=123_45_6"))
    }

    @Test
    fun loginToken_present() {
        assertTrue(SiteAuthDetector.hasLoginCookies("token=abc; _ga=1"))
        assertTrue(SiteAuthDetector.hasLoginCookies("BAIDUID=xx; bduss=reallogin"))
        assertTrue(SiteAuthDetector.hasLoginCookies("session=xyz; path=/"))
        assertTrue(SiteAuthDetector.hasLoginCookies("csrf_token=xyz"))
        assertTrue(SiteAuthDetector.hasLoginCookies("username=marvis; logged_in=1"))
    }

    @Test
    fun loginMixedWithAnonymous_true() {
        assertTrue(SiteAuthDetector.hasLoginCookies("BAIDUID=xx; _ga=1; PSTM=1; bduss=secret; cookie_session=1"))
    }

    @Test
    fun caseInsensitive() {
        assertTrue(SiteAuthDetector.hasLoginCookies("Token=abc"))
        assertTrue(SiteAuthDetector.hasLoginCookies("Bduss=abc"))
    }
}
