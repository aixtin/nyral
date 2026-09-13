#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# 补打 LocalEngine.kt registry 一处(锚点带 "无参数")
import io, os, sys

P = "/home/ymz/droid-agent/android-agent-app/app/src/main/java/all/LocalEngine.kt"
s = io.open(P, "r", encoding="utf-8").read()

old = r'''        ToolSpec("app_installed", "列出已安装的第三方应用(包名+应用名), 供 app_launch 定位包名", "无参数")
    )'''
new = r'''        ToolSpec("app_installed", "列出已安装的第三方应用(包名+应用名), 供 app_launch 定位包名", "无参数"),
        ToolSpec("js_run", "应用内就地执行 JS 脚本(纯计算/逻辑/数据操作, 无文件/网络权限, 断网可用不依赖服务器)", "JSON: {\"code\":\"要执行的JS脚本\",\"timeoutMs\":8000}")
    )'''

cnt = s.count(old)
print("anchor count =", cnt)
if cnt != 1:
    sys.exit(1)
io.open(P, "w", encoding="utf-8").write(s.replace(old, new))
print("OK registry patched")
