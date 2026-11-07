package io.github.aixtin.nyral

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.net.Uri
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class Element(val x: Int, val y: Int, val w: Int, val h: Int, val label: String)

/**
 * 同步扫描页面元素: 注入 JS 扫描器并阻塞等待 onElements 回填完成(超时兜底)。
 * 由非 UI 线程调用(DebugServer 工作线程 / LocalEngine 工具线程), 回填在 UI 线程 onElements 完成。
 * 返回识别到的元素数; 页面无地址立即返回当前数。
 */
internal fun BrowserPage.scanSync(timeoutMs: Long): Int {
    val latch = CountDownLatch(1)
    act.runOnUiThread {
        scanLatch = latch
        val u = runCatching { web.url }.getOrNull()
        if (u.isNullOrBlank()) { latch.countDown(); return@runOnUiThread }
        injectScanner()
    }
    try { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
    return elements.size
}

/** 提取整页可见文本(截断 3000 字), 供 browser_text 工具: 元素扫不到时让 AI 至少能读到页面内容 */
internal fun BrowserPage.fetchTextSync(timeoutMs: Long): String {
    val latch = CountDownLatch(1)
    act.runOnUiThread {
        textLatch = latch
        val u = runCatching { web.url }.getOrNull()
        if (u.isNullOrBlank()) { latch.countDown(); return@runOnUiThread }
        web.evaluateJavascript("(function(){var t=document.body?document.body.innerText:'';daBridge.onPageText((t||'').slice(0,3000));})();", null)
    }
    try { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
    return textResult.ifBlank { "页面暂无可见文本" }
}

/** 滚动浏览器页(正数向下, 负数向上), 供 browser_scroll 工具
 *  滚动目标自动判定: 窗口与嵌套滚动容器中取"视口可见占位面积"最大者(正在看的内容区优先),
 *  fixed/sticky(吸顶吸底栏/悬浮件)一律排除, 防滚动被装饰容器或屏幕外长列表劫持;
 *  回报正则允许负值, 修复负方向滚动只回"已发出"不回实际滚动量的静默问题;
 *  页面无可滚动区域(整页已在视口内/弹窗锁滚/仍在加载)与已到边界分开提示 */
internal fun BrowserPage.scrollBySync(delta: Int): String {
    val latch = CountDownLatch(1)
    actionResult = ""
    actionLatch = latch
    act.runOnUiThread {
        web.evaluateJavascript(
            "(function(){" +
            "function range(n){try{return Math.max(0,n.scrollHeight-n.clientHeight);}catch(e){return 0;}}" +
            "var vw=window.innerWidth||document.documentElement.clientWidth||0;" +
            "var vh=window.innerHeight||document.documentElement.clientHeight||0;" +
            "var se=document.scrollingElement||document.documentElement;" +
            "var best=null,bestR=range(se),bestA=bestR>0?vw*vh:0;" +
            "try{" +
            " var els=document.querySelectorAll('*');" +
            " for(var i=0;i<els.length;i++){" +
            "  var el=els[i];" +
            "  if(el.scrollHeight-el.clientHeight<24)continue;" +
            "  var oy=getComputedStyle(el).overflowY;" +
            "  if(oy!=='scroll'&&oy!=='auto'&&oy!=='overlay')continue;" +
            "  var ps=getComputedStyle(el).position;" +
            "  if(ps==='fixed'||ps==='sticky')continue;" +
            "  var rg=range(el);" +
            "  if(rg<=0)continue;" +
            "  var r=el.getBoundingClientRect();" +
            "  var x1=Math.max(r.left,0),y1=Math.max(r.top,0);" +
            "  var x2=Math.min(r.right,vw),y2=Math.min(r.bottom,vh);" +
            "  if(x2<=x1||y2<=y1)continue;" +
            "  var a=(x2-x1)*(y2-y1);" +
            "  if(a>bestA||(a===bestA&&rg>bestR)){best=el;bestA=a;bestR=rg;}" +
            " }" +
            "}catch(e){}" +
            "var node=best||se;" +
            "var before=node.scrollTop;" +
            "try{node.scrollBy(0," + delta + ");}catch(e){try{node.scrollTop=before+" + delta + ";}catch(e2){}}" +
            "var after=node.scrollTop;" +
            "daBridge.onActionResult('sc:'+Math.round(after-before)+':'+Math.round(after)+':'+Math.round(bestR)+':'+(best?'inner':'window'));" +
            "})();", null)
    }
    try { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
    actionLatch = null
    val m = Regex("^sc:(-?[0-9]+):(-?[0-9]+):(-?[0-9]+):(inner|window)$").find(actionResult.trim())
    if (m == null) return "滚动指令已发出($delta), 页面未回报实际滚动量, 可重扫确认位置"
    val actual = m.groupValues[1].toInt()
    val pos = m.groupValues[2].toInt()
    val max = m.groupValues[3].toInt()
    val who = if (m.groupValues[4] == "inner") "嵌套滚动容器" else "页面主文档"
    return if (actual != 0)
        "已滚动: 实际 $actual/请求 $delta(目标=$who, 当前进度 $pos/$max)"
    else if (max == 0)
        "未滚动: 页面当前无可滚动区域(可能整页已在视口内/弹窗锁定/仍在加载, 进度 0/0), 可稍后重试或重扫确认"
    else
        "未滚动: 已到滚动边界(目标=$who, 当前进度 $pos/$max); 若页面主体未动, 可先 scan 后换方向或调整步长"
}

/** 当前已识别元素快照(视口坐标), 供调试接口返回 JSON */
internal fun BrowserPage.elementsSnapshot(): List<Map<String, Any>> =
    elements.mapIndexed { i, e -> mapOf("index" to i, "x" to e.x, "y" to e.y, "w" to e.w, "h" to e.h, "label" to e.label) }

/** AI 可读元素清单: "共N个: [i]「label」(x,y wxh)", 供 browser_scan 工具返回 */
internal fun BrowserPage.snapshotText(): String {
    if (elements.isEmpty()) return "页面暂无识别到可操作元素(可能动态加载中)。建议: 1)稍等1秒再调 browser_scan 重扫; 2)用 browser_text 读取整页文字; 3)用 browser_scroll 滚动页面后再 browser_scan; 仍无则页面可能纯展示, 可考虑 web_search 补充"
    val sb = StringBuilder("页面共识别 ${elements.size} 个可操作元素(视口坐标, 滚动页面后坐标会变, 应重新 scan):\n")
    for ((i, e) in elements.withIndex()) {
        sb.append("[$i]「${e.label}」 坐标(${e.x},${e.y}) 尺寸${e.w}x${e.h}\n")
    }
    return sb.toString().trimEnd()
}

/** 点击浏览器页第 N 个已识别元素: 物理触摸注入, 绕开站点 isTrusted 反自动化拦截; 元素坐标为视口坐标
 *  防错位+生效确认闭环: 先 data-scan 实时重定位+身份校验+覆盖层检测(elementFromPoint 命中点须属于目标, 被遮挡直接拒点);
 *  点击后以页面指纹(URL/标题/正文长度)轮询确认状态真实变化, 无变化如实回报"可能未生效", 杜绝"报成功≠生效";
 *  目标在视口外/标记丢失则走事件驱动(滚动进入视口→MutationObserver+rAF 确认稳定→实时重定位→elementFromPoint+身份校验→再 fire) */
internal fun BrowserPage.clickIndex(i: Int): String {
    val e = elements.getOrNull(i) ?: return "索引越界(共 ${elements.size} 个)"
    // 快速路径: data-scan 实时重定位 + 身份校验 + 覆盖层检测
    val t = locateByScan(i, e)
    if (t != null) {
        if (t.occ.isNotBlank()) return "拒点: 元素[$i]「${e.label}」中心被页面覆盖层遮挡(遮挡者: ${t.occ}), 未点击; 建议先关闭覆盖层或重新 scan"
        val (vw, vh) = viewSize()
        if (vw > 0 && vh > 0 && t.x in 0 until vw && t.y in 0 until vh) {
            physicalTapViewport(t.x, t.y)
            val eff = waitClickEffect(t.fp)
            return "已点击元素[$i]「${e.label}」(data-scan 实时定位 @${t.x},${t.y} 视口坐标)$eff"
        }
        // 目标在视口外: 先尝试 scrollIntoView 滚入后物理点; 窄屏 overflow 裁剪、滚不进来的(如文章行的溢出菜单⋯)
        // 用程序化 el.click() 展开(弹出的菜单在屏内, 重新 scan 即可选)
        when (revealOffscreen(i)) {
            "prog" -> return "元素[$i]「${e.label}」在可视区外(窄屏布局溢出且无法滚入), 已程序化触发点击; 若弹出菜单, 请重新 scan 后选择其中项目"
            "inview", "scrolled" -> {
                val (vw2, vh2) = viewSize()
                val t2 = locateByScan(i, e)
                if (t2 != null && t2.occ.isBlank() && vw2 > 0 && vh2 > 0 && t2.x in 0 until vw2 && t2.y in 0 until vh2) {
                    physicalTapViewport(t2.x, t2.y)
                    val eff2 = waitClickEffect(t2.fp)
                    return "已点击元素[$i]「${e.label}」(滚入视口后定位 @${t2.x},${t2.y})$eff2"
                }
                return clickWithEventDriven(i, e)
            }
            else -> return clickWithEventDriven(i, e)
        }
    }
    // data-scan 标记已丢失(节点被移除/重扫重建): 事件驱动内部会按快照 label 重扫匹配
    return clickWithEventDriven(i, e)
}

/** WebView 当前可视尺寸(CSS px) */
private fun BrowserPage.viewSize(): Pair<Int, Int> {
    val gate = CountDownLatch(1)
    var vw = 0; var vh = 0; var sc = 1f
    act.runOnUiThread {
        try { vw = web.width; vh = web.height; sc = web.scale.toFloat() } finally { gate.countDown() }
    }
    try { gate.await(1, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) {}
    return Pair(if (sc > 0f) (vw / sc).roundToInt() else vw, if (sc > 0f) (vh / sc).roundToInt() else vh)
}

/** 视口外元素处置: 已在视口返回 inview; 否则先 scrollIntoView, 300ms 后进入视口=scrolled(调用方重新物理定位点击);
 *  仍在视口外(窄屏 overflow:hidden 裁掉、滚不进来的溢出菜单按钮)=程序化 el.click() 展开后回 prog; 元素丢失=no-element */
private fun BrowserPage.revealOffscreen(i: Int): String {
    val latch = CountDownLatch(1)
    actionResult = ""
    actionLatch = latch
    act.runOnUiThread {
        web.evaluateJavascript(
            "(function(){var i=$i;" +
            "function findScan(i,doc){" +
            "  var el=doc.querySelector('[data-scan=\"'+i+'\"]');" +
            "  if(el) return el;" +
            "  var fs=doc.querySelectorAll('iframe');" +
            "  for(var j=0;j<fs.length;j++){try{var inner=findScan(i,fs[j].contentDocument);if(inner)return inner;}catch(e){}" +
            "  return null;" +
            "}" +
            "var el=findScan(i,document);" +
            "if(!el){daBridge.onActionResult('no-element');return;}" +
            "function inView(){var r=el.getBoundingClientRect();return r.width>0&&r.height>0&&r.top>=0&&r.left>=0&&r.bottom<=window.innerHeight&&r.right<=window.innerWidth;}" +
            "if(inView()){daBridge.onActionResult('inview');return;}" +
            "try{el.scrollIntoView({block:'center',inline:'center',behavior:'instant'});}catch(e){try{el.scrollIntoView();}catch(e2){}}" +
            "setTimeout(function(){" +
            "  if(inView()){daBridge.onActionResult('scrolled');}" +
            "  else{try{el.click();daBridge.onActionResult('prog');}catch(err){daBridge.onActionResult('no-element');}}" +
            "},300);" +
            "})();", null)
    }
    try { latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
    actionLatch = null
    val r = actionResult.trim()
    return if (r in setOf("inview", "scrolled", "prog", "no-element")) r else "no-element"
}

/** 快路径定位结果: 中心视口坐标 + 覆盖层描述 + 点击前页面指纹(URL/标题/正文长度) */
private data class ClickTarget(val x: Int, val y: Int, val occ: String, val fp: Array<String>)

/** data-scan 实时重定位 + 身份校验 + 覆盖层检测 + 页面指纹采集; 返回点击中心视口坐标; 定位失败/身份不符返回 null */
private fun BrowserPage.locateByScan(i: Int, e: Element): ClickTarget? {
    val latch = CountDownLatch(1)
    actionResult = ""
    actionLatch = latch
    act.runOnUiThread {
        web.evaluateJavascript(
            "(function(){var i=" + i + ";" +
            "function findScan(i,doc){" +
            "  var el=doc.querySelector('[data-scan=\"'+i+'\"]');" +
            "  if(el) return el;" +
            "  var fs=doc.querySelectorAll('iframe');" +
            "  for(var j=0;j<fs.length;j++){" +
            "    try{var inner=findScan(i,fs[j].contentDocument); if(inner) return inner;}catch(e){}" +
            "  }" +
            "  return null;" +
            "}" +
            "var el=findScan(i,document);" +
            "if(!el){daBridge.onActionResult('NO_ELEMENT');return;}" +
            "var r=el.getBoundingClientRect();" +
            "var vx=Math.round(r.left+r.width/2), vy=Math.round(r.top+r.height/2);" +
            "var nowTxt=((el.innerText||el.getAttribute('aria-label')||'').trim()).replace(/\\s+/g,' ').replace(/\\|/g,'/');" +
            // 覆盖层检测: 命中点元素须属于目标(自身/子孙/祖先), 否则目标被遮挡, 拒点并上报遮挡者
            "var occ='';" +
            "try{var hit=document.elementFromPoint(vx,vy);" +
            "if(hit&&hit!==el&&!el.contains(hit)&&!hit.contains(el)){" +
            "occ=(hit.tagName||'')+' '+(hit.innerText||hit.getAttribute('aria-label')||'').trim().slice(0,20).replace(/\\|/g,'/');}}catch(e){}" +
            "var h='',t='';var l=0;" +
            "try{h=location.href.slice(0,120).replace(/\\|/g,'%7C');}catch(e){}" +
            "try{t=(document.title||'').slice(0,40).replace(/\\|/g,'%7C');}catch(e){}" +
            "try{l=document.body?document.body.innerText.length:0;}catch(e){}" +
            "daBridge.onActionResult(vx+','+vy+'|'+nowTxt.slice(0,120)+'|'+occ+'|'+h+'|'+t+'|'+l);})();", null)
    }
    try { latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
    val raw = actionResult
    if (raw == "NO_ELEMENT" || raw.isBlank()) return null
    val parts = raw.split("|")
    if (parts.size < 6) return null
    val snap = e.label.replace(Regex("\\s+"), " ").trim()
    val now = parts[1].replace(Regex("\\s+"), " ").trim()
    if (snap.length >= 4 && !now.contains(snap.take(12))) return null
    val c = parts[0].split(",")
    if (c.size != 2) return null
    val x = c[0].toIntOrNull() ?: return null
    val y = c[1].toIntOrNull() ?: return null
    return ClickTarget(x, y, parts[2].trim(), arrayOf(parts[3], parts[4], parts[5]))
}

/** 采集当前页面指纹(URL/标题/正文长度), 供点击生效确认前后对比; 失败返回空占位 */
private fun BrowserPage.captureFingerprint(): Array<String> {
    val latch = CountDownLatch(1)
    actionResult = ""
    actionLatch = latch
    act.runOnUiThread {
        web.evaluateJavascript(
            "(function(){var h='',t='';var l=0;" +
            "try{h=location.href.slice(0,120).replace(/\\|/g,'%7C');}catch(e){}" +
            "try{t=(document.title||'').slice(0,40).replace(/\\|/g,'%7C');}catch(e){}" +
            "try{l=document.body?document.body.innerText.length:0;}catch(e){}" +
            "daBridge.onActionResult('FP:'+h+'|'+t+'|'+l);})();", null)
    }
    try { latch.await(600, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
    val raw = actionResult
    if (!raw.startsWith("FP:")) return arrayOf("", "", "0")
    val p = raw.substring(3).split("|")
    return if (p.size == 3) arrayOf(p[0], p[1], p[2]) else arrayOf("", "", "0")
}

/** 点击后生效确认: 以页面指纹轮询 1.5 秒, 状态变化/无变化/超时均如实回报, 供调用方决定是否重扫 */
private fun BrowserPage.waitClickEffect(fp: Array<String>): String {
    val latch = CountDownLatch(1)
    actionResult = ""
    actionLatch = latch
    val hu = fp[0].replace("\\", "\\\\").replace("\"", "\\\"")
    val ti = fp[1].replace("\\", "\\\\").replace("\"", "\\\"")
    act.runOnUiThread {
        web.evaluateJavascript(
            "(function(){var hu=\"$hu\",ti=\"$ti\",L=" + fp[2] + ",t0=Date.now();" +
            "function fp(){try{return location.href.slice(0,120).replace(/\\|/g,'%7C')+'|'+(document.title||'').slice(0,40).replace(/\\|/g,'%7C')+'|'+(document.body?document.body.innerText.length:0);}catch(e){return 'E';}}" +
            "var f0=hu+'|'+ti+'|'+L;" +
            "(function ck(){var c=fp();" +
            "if(c!=='E'&&c!==f0){daBridge.onActionResult('EFF:CHANGED');return;}" +
            "if(Date.now()-t0>=1500){daBridge.onActionResult('EFF:NO');return;}" +
            "setTimeout(ck,150);})();})();", null)
    }
    try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) {}
    actionLatch = null
    val raw = actionResult.trim()
    if (raw == "EFF:CHANGED") return "; 生效确认: 页面状态已变化"
    if (raw == "EFF:NO") return "; 生效确认: 1.5秒内未检测到页面变化(URL/标题/正文均未变), 点击可能未生效或被悬浮层截获, 建议重扫核实"
    // 回调丢失(多为页面跳转杀掉待执行的 JS): 以当前 URL 对比兜底
    val nowUrl = getUrlSync()
    return if (nowUrl.isNotBlank() && fp[0].isNotBlank() && nowUrl.take(120) != fp[0]) "; 生效确认: 页面已跳转"
    else "; 生效确认: 检测超时未能确认, 建议重扫核实"
}

/** 当前页面 URL(UI 线程读取, 短超时) */
private fun BrowserPage.getUrlSync(): String {
    val gate = CountDownLatch(1)
    var u = ""
    act.runOnUiThread { try { u = web.url ?: "" } finally { gate.countDown() } }
    try { gate.await(600, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
    return u
}

/** 按快照 label 前12字在重扫结果中匹配索引; 未匹配返回 -1 */
private fun BrowserPage.matchLabel(label: String): Int {
    val key = label.replace(Regex("\\s+"), " ").trim().take(12)
    if (key.length < 2) return -1
    for ((i, e) in elements.withIndex()) {
        val lbl = e.label.replace(Regex("\\s+"), " ").trim()
        if (lbl.contains(key)) return i
    }
    return -1
}

/** JS 滚动-稳定-校验结果 */
private data class JsCheckResult(val status: String, val vx: Int, val vy: Int, val detail: String)

/** 事件驱动点击闭环: 滚动进入视口→MutationObserver+rAF 稳定→实时重定位→elementFromPoint+身份校验→fire; 重试上限3次; 失败显式拒绝 */
private fun BrowserPage.clickWithEventDriven(i: Int, e: Element): String {
    var attempt = 0
    while (attempt < 3) {
        attempt++
        val (status, vx, vy, detail) = runScrollCheckJs(i, e)
        when {
            status == "OK" -> {
                val f = captureFingerprint()
                physicalTapViewport(vx, vy)
                val eff = waitClickEffect(f)
                return "已点击元素[$i]「${e.label}」(事件驱动闭环 @${vx},${vy} 视口坐标)$eff"
            }
            status == "LOST" -> {
                // 标记丢失: 重扫并按快照 label 匹配新索引后再走闭环
                val cnt = scanSync(2500)
                val j = matchLabel(e.label)
                if (j < 0) return "元素[$i]「${e.label}」滚动后标记丢失, 重扫($cnt)未匹配到同款, 未点击, 请重新 scan"
                val e2 = elements.getOrNull(j) ?: return "元素[$i]「${e.label}」重扫后索引[$j]越界, 未点击"
                val (s2, x2, y2, d2) = runScrollCheckJs(j, e2)
                if (s2 == "OK") {
                    val f = captureFingerprint()
                    physicalTapViewport(x2, y2)
                    val eff = waitClickEffect(f)
                    return "已点击元素[$i]→重扫后[$j]「${e2.label}」(事件驱动闭环 @${x2},${y2})$eff"
                }
                return "元素[$i]「${e.label}」重扫后[$j]「${e2.label}」定位仍失败($s2${if (d2.isNotBlank()) ":$d2" else ""}), 未点击, 请重新 scan"
            }
            status == "MISMATCH" || status == "IDMISMATCH" || status == "NOT_IN_VIEW" || status == "TIMEOUT" -> {
                if (attempt >= 3) {
                    return "元素[$i]「${e.label}」点击前校验未通过($status@$vx,$vy${if (detail.isNotBlank()) ":$detail" else ""}), 已重试${attempt}次, 未点击, 请重新 scan"
                }
                // 渲染中途抖动: 不等待的连续重试会打在同一帧, 小睡一拍让页面先稳定再校验
                Thread.sleep(400)
            }
            else -> return "元素[$i]「${e.label}」异常状态($status:$detail), 未点击"
        }
    }
    return "元素[$i]「${e.label}」点击失败, 未点击"
}

/** 注入滚动-稳定-校验 JS 并同步等待结果; 返回 (状态, 视口x, 视口y, 细节) */
private fun BrowserPage.runScrollCheckJs(idx: Int, e: Element): JsCheckResult {
    val latch = CountDownLatch(1)
    actionResult = ""
    actionLatch = latch
    val snapKey = e.label.replace(Regex("\\s+"), " ").trim().take(12)
    val snapJson = "\"" + snapKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val js = buildScrollCheckJs(idx, snapJson)
    act.runOnUiThread { web.evaluateJavascript(js, null) }
    try { latch.await(6, java.util.concurrent.TimeUnit.SECONDS) } catch (e2: InterruptedException) {}
    val raw = actionResult
    if (raw.isBlank()) return JsCheckResult("TIMEOUT", 0, 0, "no-result")
    val m = Regex("^(OK|LOST|NOT_IN_VIEW|TIMEOUT|MISMATCH|IDMISMATCH)(?::([0-9]+),([0-9]+)(?::(.*))?)?$").find(raw.trim())
    if (m == null) return JsCheckResult("UNKNOWN", 0, 0, raw.take(60))
    return when (m.groupValues[1]) {
        "OK" -> JsCheckResult("OK", m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].toIntOrNull() ?: 0, "")
        else -> JsCheckResult(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].toIntOrNull() ?: 0, m.groupValues[4].take(60))
    }
}

/** 构造事件驱动滚动-校验 JS: scrollIntoView→MutationObserver+rAF 稳定→elementFromPoint+身份校验→返回状态 */
private fun buildScrollCheckJs(idx: Int, snapJson: String): String = """
(function(){
var i=$idx, snap=$snapJson;
function findScan(i,doc){
  var el=doc.querySelector('[data-scan="'+i+'"]');
  if(el) return el;
  var fs=doc.querySelectorAll('iframe');
  for(var j=0;j<fs.length;j++){
    try{var inner=findScan(i,fs[j].contentDocument); if(inner) return inner;}catch(e){}
  }
  return null;
}
function findScrollableAncestor(el){
  var cur=el.parentElement;
  while(cur&&cur!==document.documentElement){
    var st=getComputedStyle(cur);
    var oy=st.overflowY;
    if(oy==='scroll'||oy==='auto'||oy==='overlay'){return cur;}
    cur=cur.parentElement;
  }
  return null;
}
function docTxt(el){return ((el.innerText||el.getAttribute('aria-label')||'').replace(/\[object[^\]]*\]/g,'').trim()).replace(/\s+/g,' ');}
var el=findScan(i,document);
if(!el){daBridge.onActionResult('LOST');return;}
var scroller=findScrollableAncestor(el);
try{el.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});}catch(e){try{el.scrollIntoView(true);}catch(e2){}}
var dirty=false;
var observer=null;
try{
  observer=new MutationObserver(function(muts){
    for(var k=0;k<muts.length;k++){
      var m=muts[k];
      if(m.type==='childList'){
        for(var j=0;j<m.removedNodes.length;j++){
          var n=m.removedNodes[j];
          if(n===el||(n.contains&&n.contains(el))){dirty=true;}
        }
      }
      if(m.type==='attributes'&&m.target===el&&m.attributeName==='data-scan'){dirty=true;}
    }
  });
  observer.observe(el,{childList:true,subtree:true,attributes:true,attributeFilter:['data-scan']});
  if(scroller&&scroller!==document.documentElement){
    observer.observe(scroller,{childList:true,subtree:true});
  }
}catch(e){}
var stable=0,last=null,frames=330,ended=false,hitFail=0;
function finish(s){if(ended)return;ended=true;try{if(observer)observer.disconnect();}catch(e){}daBridge.onActionResult(s);}
function check(){
  if(ended)return;
  var cur=findScan(i,document);
  if(!cur){finish('LOST');return;}
  if(dirty){dirty=false;stable=0;}
  var r=cur.getBoundingClientRect();
  if(r.width<1&&r.height<1){stable=0;}
  else if(last&&Math.abs(r.top-last.top)<1&&Math.abs(r.left-last.left)<1){
    stable++;
    if(stable>=2){
      var vx=Math.round(r.left+r.width/2);
      var vy=Math.round(r.top+r.height/2);
      var vw=window.innerWidth||document.documentElement.clientWidth||360;
      var vh=window.innerHeight||document.documentElement.clientHeight||603;
      if(!(vx>=0&&vx<vw&&vy>=0&&vy<vh)){finish('NOT_IN_VIEW');return;}
      var hit=document.elementFromPoint(vx,vy);
      var hs=null;
      if(hit){var h2=hit;while(h2&&h2!==document.documentElement){if(h2.hasAttribute&&h2.hasAttribute('data-scan')){hs=h2.getAttribute('data-scan');break;}h2=h2.parentElement;}}
      if(hs!==null&&hs===String(i)){
        if(snap){
          var now=docTxt(cur).slice(0,120);
          if(now&&now.indexOf(snap)<0){finish('IDMISMATCH:'+vx+','+vy+':'+now.slice(0,30));return;}
        }
        finish('OK:'+vx+','+vy); return;
      }
      // 命中点暂未落到目标(懒加载占位/覆盖层): 等待其消失, 连续失败超阈值才判 MISMATCH
      hitFail++;
      if(hitFail>=100){finish('MISMATCH:'+vx+','+vy+':'+(hs||'none'));return;}
      last=null; stable=0;
      if(--frames<=0){finish('TIMEOUT');return;}
      requestAnimationFrame(check); return;
    }
  }else{stable=0;}
  last={top:r.top,left:r.left};
  if(--frames<=0){finish('TIMEOUT');return;}
  requestAnimationFrame(check);
}
requestAnimationFrame(check);
setTimeout(function(){finish('TIMEOUT');},6000);
})();
""".trimIndent()

/** 直接按视口坐标物理注入一次触摸(DOWN+UP), 不做滚动; 供事件驱动路径在落点校验通过后调用 */
private fun BrowserPage.physicalTapViewport(vx: Int, vy: Int): Boolean {
    val gate = CountDownLatch(1)
    act.runOnUiThread {
        try {
            val vw = web.width; val vh = web.height
            if (vw <= 0 || vh <= 0) { paintStatus(act.getString(R.string.br_click_view_not_ready)); return@runOnUiThread }
            val s = web.scale.toFloat()
            val sx = (vx * s).roundToInt().coerceIn(0, vw - 1)
            val sy = (vy * s).roundToInt().coerceIn(0, vh - 1)
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, sx.toFloat(), sy.toFloat(), 0)
            val up = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, sx.toFloat(), sy.toFloat(), 0)
            web.onTouchEvent(down)
            web.onTouchEvent(up)
            down.recycle(); up.recycle()
            paintStatus(act.getString(R.string.br_tapped, sx, sy))
        } finally { gate.countDown() }
    }
    try { gate.await(1, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) {}
    return true
}

/** 向浏览器页第 N 个已识别元素(输入框)输入文本; data-scan 标记优先定位, 兜底按扫描时视口坐标 elementFromPoint(仅接受输入类元素)。
 *  普通 input/textarea 走原生 value setter+input/change(受控组件兼容); contenteditable 富文本(TipTap/ProseMirror 等)
 *  走选区全选+execCommand insertText(经编辑器自身事务, value setter 对非输入元素抛 Illegal invocation)。同步返回真实回执。 */
internal fun BrowserPage.typeIndex(i: Int, text: String): String {
    val e = elements.getOrNull(i) ?: return "索引越界(共 ${elements.size} 个)"
    val x = e.x; val y = e.y
    val safe = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    val latch = CountDownLatch(1)
    actionResult = ""
    actionLatch = latch
    act.runOnUiThread { web.evaluateJavascript(
        "(function(){var i=$i,x=$x,y=$y,s=\"$safe\";" +
        "function findScan(i,doc){" +
        "  var el=doc.querySelector('[data-scan=\"'+i+'\"]');" +
        "  if(el) return el;" +
        "  var fs=doc.querySelectorAll('iframe');" +
        "  for(var j=0;j<fs.length;j++){" +
        "    try{var inner=findScan(i,fs[j].contentDocument); if(inner) return inner;}catch(e){}" +
        "  }" +
        "  return null;" +
        "}" +
        "var el=findScan(i,document);" +
        "if(!el){var p=document.elementFromPoint(x,y);if(p&&(p.tagName==='INPUT'||p.tagName==='TEXTAREA'||p.isContentEditable)){el=p;}}" +
        "if(!el){daBridge.onActionResult('no-element');return;}" +
        "try{" +
        "  el.focus();" +
        "  if(el.isContentEditable||el.hasAttribute('contenteditable')){" +
        "    var rg=document.createRange();rg.selectNodeContents(el);" +
        "    var sv=window.getSelection();sv.removeAllRanges();sv.addRange(rg);" +
        "    var okCE=document.execCommand('insertText',false,s);" +
        "    daBridge.onActionResult('ce:'+(okCE?'ok':'unsupported')+':'+(el.innerText||'').length);" +
        "  }else{" +
        "    var isArea=el.tagName==='TEXTAREA';" +
        "    var setter=(isArea?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype);" +
        "    var d=Object.getOwnPropertyDescriptor(setter,'value');" +
        "    if(d&&d.set){d.set.call(el,s);}else{el.value=s;}" +
        "    el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));" +
        "    daBridge.onActionResult('in:'+el.tagName);" +
        "  }" +
        "}catch(err){daBridge.onActionResult('type-error:'+(err&&err.message?err.message:String(err)));}" +
        "})();", null) }
    try { latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (ex: InterruptedException) {}
    actionLatch = null
    val r = actionResult.trim()
    return when {
        r == "no-element" -> "未找到元素[$i]「${e.label}」(页面可能已重排, 请重新 scan 后再输入)"
        r.startsWith("type-error") -> "输入失败:${r.removePrefix("type-error:")}（元素[$i]「${e.label}」）"
        r.startsWith("ce:ok:") -> "已向正文富文本框[$i]「${e.label}」输入 ${text.length} 字（框内现有 ${r.removePrefix("ce:ok:")} 字）"
        r.startsWith("ce:unsupported") -> "正文框不支持 execCommand 输入（元素[$i]「${e.label}」）, 请换用接管模式手动输入"
        r.startsWith("in:") -> "已向元素[$i]「${e.label}」输入 ${text.length} 字"
        else -> "输入结果未确认(元素[$i]「${e.label}」, 回执=$r)"
    }
}

/** 供调试/兜底: 执行任意 JS 表达式并把结果经 onActionResult 回传(EVAL: 前缀), 调用线程非主线程可同步等结果 */
internal fun BrowserPage.evalScript(script: String, done: CountDownLatch) {
    actionResult = ""
    actionLatch = done
    val js = "try{(function(){var __r=(function(){return (" + script + ");})();" +
             "daBridge.onActionResult('EVAL:'+String(JSON.stringify(__r)));})();}" +
             "catch(e){daBridge.onActionResult('EVAL:ERR:'+e.message);}"
    act.runOnUiThread { web.evaluateJavascript(js, null) }
    Thread { try { Thread.sleep(1500); done.countDown() } catch (e: Exception) {} }.start()
}

/** 取最近一次 JS 执行结果(去掉 EVAL: 前缀) */
internal fun BrowserPage.lastActionResult(): String {
    val r = actionResult
    return if (r.startsWith("EVAL:")) r.substring(5) else r
}

/** scan 同步等待: DebugServer / AI 工具触发重扫时阻塞等 onElements 回填, 防止异步竞态读到旧/空元素 */
/** open 页面就绪等待: 记录最近一次 open 触发的加载, onPageFinished 时置完成 */

/** 供 MainActivity/未来 AI 引擎调用的公开能力; url 为关键词时自动转百度搜索 */
internal fun BrowserPage.open(url: String? = null) {
    if (url != null) {
        lastUrl = url; loaded = true; paintStatus(act.getString(R.string.br_opening, url))
        loadDoneLatch = CountDownLatch(1)
        act.runOnUiThread { web.loadUrl(toLoadableUrl(url)) }
    } else ensureLoad()
    slideIn()
}

/**
 * 等待最近一次 open 的页面加载完成(onPageFinished), 由非 UI 线程调用。
 * 无进行中加载立即返回 true; 超时返回 false。
 */
internal fun BrowserPage.waitLoaded(timeoutMs: Long): Boolean {
    val l = loadDoneLatch ?: return true
    return try { l.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { false }
}

/** AI 清除浏览器缓存: full=true 连登录 Cookie 一起清(会退出所有站点登录); 清完强制刷新当前页(主线程执行) */
internal fun BrowserPage.clearCacheForAi(full: Boolean): String {
    val latch = CountDownLatch(1)
    val ret = arrayOfNulls<String>(1)
    act.runOnUiThread {
        try {
            val cm = android.webkit.CookieManager.getInstance()
            if (full) cm.removeAllCookies(null)
            web.clearCache(true)
            if (full) cm.flush()
            web.reload()
            ret[0] = if (full) "已清除全部缓存与登录Cookie, 当前页已刷新" else "已清除页面缓存, 当前页已刷新"
        } catch (e: Exception) {
            ret[0] = "清缓存失败: ${e.message}"
        }
        latch.countDown()
    }
    try { latch.await(4, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) { }
    return ret[0] ?: "清缓存操作超时"
}

/** 登录态判定已抽至 SiteAuthDetector(纯函数可单测), 见 all/SiteAuthDetector.kt */
/** 检测当前域 Cookie: 非空、含真实登录态且相对上次有变化则回调 autoSaveCookie */
internal fun BrowserPage.tryAutoSaveCookie(url: String?) {
    val host = runCatching { java.net.URI(url ?: "").host }.getOrNull() ?: return
    if (host.isBlank()) return
    val cm = CookieManager.getInstance()
    val ck = listOf("https://$host", "http://$host")
        .mapNotNull { u -> runCatching { cm.getCookie(u) }.getOrNull()?.takeIf { it.isNotBlank() } }
        .firstOrNull().orEmpty()
    if (ck.isNotBlank() && SiteAuthDetector.hasLoginCookies(ck) && ck != hostCookieCache[host]) {
        hostCookieCache[host] = ck
        autoSaveCookie?.invoke(host, ck)
    }
}
/** AI 提取登录态 Cookie 存 site_auth: domain 非空按该域取(返回 cookie 原文), 为空取浏览器当前页域名(返回 "域名\tcookie") */
internal fun BrowserPage.cookieStringFor(domain: String?): String {
    val latch = java.util.concurrent.CountDownLatch(1)
    val out = arrayOfNulls<String>(1)
    act.runOnUiThread {
        try {
            val curUrl = runCatching { web.url }.getOrNull() ?: lastUrl
            val host = domain?.trim()?.removePrefix("http://")?.removePrefix("https://")?.substringBefore('/')
                ?: runCatching { java.net.URI(curUrl).host }.getOrNull()
            if (host.isNullOrBlank()) { out[0] = ""; return@runOnUiThread }
            val cm = android.webkit.CookieManager.getInstance()
            val ck = listOf("https://$host", "http://$host")
                .mapNotNull { u -> runCatching { cm.getCookie(u) }.getOrNull()?.takeIf { it.isNotBlank() } }
                .firstOrNull().orEmpty()
            out[0] = if (domain.isNullOrBlank()) "$host\t$ck" else ck
        } catch (e: Exception) { out[0] = "" }
        latch.countDown()
    }
    try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) { }
    return out[0] ?: ""
}

/** 非 http(s) 开头的文本视为搜索词, 拼百度搜索; 已是网址原样用 */
internal fun BrowserPage.toLoadableUrl(u: String): String {
    val t = u.trim()
    if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") || t.startsWith("file:")) return t
    if (t.isEmpty()) return engines.getOrNull(engineIdx)?.home ?: "https://www.baidu.com"
    lastKeyword = t
    val e = engines.getOrNull(engineIdx) ?: engines.firstOrNull()
        ?: return "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode(t, "UTF-8")
    return e.search.replace("{q}", java.net.URLEncoder.encode(t, "UTF-8"))
}
internal fun BrowserPage.close() { slideOut() }
/** 网页后退一步; 无历史可退时返回 false(由调用方决定是否收起浏览器页) */
/** browser_upload 工具: 把工作目录(Download/Nyral_work)文件注入页面第 N 个 file input;
 *  优先用 browser_scan 的元素索引定位(若该元素是 file input), 否则按页面第 N 个 input[type=file] 定位(默认0) */
internal fun BrowserPage.uploadIndex(i: Int, localName: String): String {
    val ctx: Context = act
    if (!WorkDir.exists(ctx, localName)) return "工作目录不存在该文件: $localName (可先用 workdir_list 查看)"
    val uri = Uri.parse("content://${ctx.packageName}.files/work/${Uri.encode(localName)}")
    pendingUpload = arrayOf(uri)
    val js = "(function(){var idx=$i;" +
        "var target=null;" +
        "var marked=document.querySelector('[data-scan=\"'+idx+'\"]');" +
        "if(marked&&marked.tagName==='INPUT'&&marked.getAttribute('type')==='file'){target=marked;}" +
        "if(!target){var all=document.querySelectorAll('input[type=file]');" +
        "if(all.length===0){daBridge.onActionResult('no-file-input');return;}" +
        "target=all[(idx>=0&&idx<all.length)?idx:0];}" +
        "target.click();" +
        "daBridge.onActionResult('file-input-clicked:'+(target.name||'?'));})();"
    act.runOnUiThread { web.evaluateJavascript(js, null) }
    return "已发起上传 $localName 到文件选择框[$i](观察页面是否出现文件)"
}

internal fun BrowserPage.goBack(): Boolean {
    if (web.canGoBack()) { web.goBack(); return true }
    return false
}
internal fun BrowserPage.setStatus(s: String) { paintStatus(s) }
internal fun BrowserPage.setThink(s: String) {
    act.runOnUiThread {
        thinkBody.text = s
        thinkLog = if (thinkLog.isEmpty()) s else thinkLog + "\n" + s
        thinkAll.text = thinkLog
        thinkScrollBox.visibility = View.VISIBLE
    }
}
/** 高亮某元素(视口坐标), 高亮圈随页面滚动平移跟随 */
internal fun BrowserPage.highlightElement(x: Int, y: Int, w: Int, h: Int, label: String) {
    highlight.setTarget(RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()))
    highlight.updateScroll(web.scrollX, web.scrollY, web.contentHeight, web.height, web.scale.toFloat())
    paintStatus(act.getString(R.string.br_highlight_fmt, label.ifEmpty { act.getString(R.string.br_element) }))
}
