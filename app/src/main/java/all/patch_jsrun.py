#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Nyral 阶段1 js_run 补丁: LocalEngine.kt 四处 + DebugServer.kt 两处
import io, os, sys, time, shutil

BASE = "/home/ymz/droid-agent/android-agent-app/app/src/main/java/all"
TS = time.strftime("%Y%m%d_%H%M%S")
BAK = "/home/ymz/droid-agent/android-agent-app/backups"

def read(p):
    with io.open(p, "r", encoding="utf-8") as f:
        return f.read()

def write(p, s):
    with io.open(p, "w", encoding="utf-8") as f:
        f.write(s)

def patch(path, old, new, tag):
    p = os.path.join(BASE, path)
    s = read(p)
    cnt = s.count(old)
    if cnt != 1:
        print("!! [%s] anchor count=%d (expected 1), SKIP %s" % (tag, cnt, path))
        return False
    write(p, s.replace(old, new))
    print("OK [%s] %s" % (tag, path))
    return True

def main():
    # 备份
    for f in ("LocalEngine.kt", "DebugServer.kt"):
        src = os.path.join(BASE, f)
        dst = os.path.join(BAK, "%s.bak_jsrun_%s" % (f, TS))
        shutil.copy2(src, dst)
        print("BACKUP %s -> %s" % (src, dst))

    # ---- LocalEngine.kt ----
    LE = "LocalEngine.kt"
    ok = True
    # 1) toolRegistry
    ok &= patch(LE,
r'''        ToolSpec("app_installed", "列出已安装的第三方应用(包名+应用名), 供 app_launch 定位包名")
    )''',
r'''        ToolSpec("app_installed", "列出已安装的第三方应用(包名+应用名), 供 app_launch 定位包名"),
        ToolSpec("js_run", "应用内就地执行 JS 脚本(纯计算/逻辑/数据操作, 无文件/网络权限, 断网可用不依赖服务器)", "JSON: {\"code\":\"要执行的JS脚本\",\"timeoutMs\":8000}")
    )''', "registry")
    # 2) toolIndex
    ok &= patch(LE,
'''        "app_installed" to "列出已安装的第三方应用(查包名)"
    )''',
'''        "app_installed" to "列出已安装的第三方应用(查包名)",
        "js_run" to "应用内就地执行 JS 脚本(纯计算/逻辑/数据操作, 断网可用)"
    )''', "toolIndex")
    # 3) builtinSchema
    ok &= patch(LE,
'''            "app_installed" -> obj()

            "web_search" -> obj(listOf("q"),''',
'''            "app_installed" -> obj()
            "js_run" -> obj(listOf("code"),
                "code" to str("要执行的 JS 脚本(应用内就地, 纯计算/逻辑/数据操作)"),
                "timeoutMs" to int("超时毫秒, 默认 8000, 防死循环", 8000))

            "web_search" -> obj(listOf("q"),''', "schema")
    # 4) executeTool
    ok &= patch(LE,
'''            "app_installed" -> UiControlService.installed(context)
            else -> {''',
'''            "app_installed" -> UiControlService.installed(context)
            "js_run" -> run {
                val jo = try { JSONObject(argRaw.trim()) } catch (e: Exception) { null }
                val code = jo?.optString("code", "").orEmpty()
                val timeout = jo?.optLong("timeoutMs", 8000L) ?: 8000L
                if (code.isBlank()) "请指定 code(要执行的 JS 脚本)" else ScriptEngine.runJs(code, timeout)
            }
            else -> {''', "execute")

    # ---- DebugServer.kt ----
    DS = "DebugServer.kt"
    # 5) route
    ok &= patch(DS,
'''                    method == "POST" && path == "/v1/app/installed" -> appInstalled(out)
                    else -> {''',
'''                    method == "POST" && path == "/v1/app/installed" -> appInstalled(out)
                    method == "POST" && path == "/v1/js/run" -> jsRun(out, body)
                    else -> {''', "route")
    # 6) handler
    ok &= patch(DS,
'''    private fun appInstalled(out: OutputStream) {
        val act = main ?: run { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        writeJson(out, 200, JSONObject().put("ok", true).put("result", UiControlService.installed(act)))
    }''',
'''    private fun appInstalled(out: OutputStream) {
        val act = main ?: run { writeJson(out, 503, JSONObject().put("error", "MainActivity not alive")); return }
        writeJson(out, 200, JSONObject().put("ok", true).put("result", UiControlService.installed(act)))
    }

    private fun jsRun(out: OutputStream, body: String) {
        val o = try { JSONObject(body) } catch (e: Exception) { null }
        val code = o?.optString("code", "").orEmpty()
        val timeout = o?.optLong("timeoutMs", 8000L) ?: 8000L
        if (code.isBlank()) { writeJson(out, 400, JSONObject().put("error", "code required")); return }
        writeJson(out, 200, JSONObject().put("ok", true).put("result", ScriptEngine.runJs(code, timeout)))
    }''', "handler")

    print("ALL_DONE ok=%s" % ok)
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
