#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nyral 布局回归断言（/v1/ui layout 段）

存在的意义：把"输入栏被压扁 / 最后一个气泡被输入框永久遮住 / 抽屉关不严半开残留 /
发送与停止按钮同显 / dock 被键盘顶出屏幕"这类**只有肉眼才能发现、且修好又坏**的 UI 问题，
变成机器可判的 PASS/FAIL。断言只锁"必然成立的结构不变量"，不锁像素级外观，避免脆弱。

用法:
  export NYRAL_DEVICE=<adb 序列号>
  ./assert_layout.py              # 首次运行建立基线
  ./assert_layout.py --update     # 人工确认当前布局正确后，重写基线作为防回归锚点
  ./assert_layout.py --dump       # 只打印快照，不断言

退出码: 0 全通过 / 1 有断言失败 / 2 通道不可用
"""
import json, os, re, subprocess, sys, time

PKG = "io.github.aixtin.nyral"
BRIDGE = 18765
BASE = os.path.dirname(os.path.abspath(__file__))
_DEV = re.sub(r"\W", "", os.environ.get("NYRAL_DEVICE", "default"))
BASELINE = os.path.join(BASE, "layout_baseline_%s.json" % _DEV)  # 按设备分档: 布局基准机与测试机分辨率不同, 不可混用
TOL = 3              # 尺寸/坐标容差(px)
VISIBLE, INVISIBLE, GONE = 0, 4, 8

FAIL, OK = [], []


def ok(t):
    OK.append(t); print("  PASS " + t)


def bad(t):
    FAIL.append(t); print("  FAIL " + t)


def sh(cmd):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return (p.stdout or "") + (p.stderr or "")


def get_token(dev):
    if os.environ.get("NYRAL_TOKEN"):
        return os.environ["NYRAL_TOKEN"].strip()
    for c in ['adb -s %s shell "run-as %s cat /data/data/%s/shared_prefs/debug_server.xml"' % (dev, PKG, PKG),
              'adb -s %s shell "cat /data/data/%s/shared_prefs/debug_server.xml"' % (dev, PKG)]:
        out = sh(c)
        m = re.search(r'name="token"[^>]*>([^<]+)<', out)
        if m:
            return m.group(1).strip()
    return ""


def fetch(dev, token):
    host = "http://127.0.0.1:%d" % BRIDGE
    for _ in range(15):
        out = sh('curl -s -m 3 -H "X-Auth-Token: %s" %s/v1/ui' % (token, host))
        if out.strip().startswith("{"):
            return json.loads(out)
        time.sleep(1)
    return None


def num(d, *ks):
    cur = d
    for k in ks:
        if not isinstance(cur, dict) or k not in cur:
            return None
        cur = cur[k]
    return cur


def check_invariants(lay):
    root = lay.get("root") or {}
    H = num(root, "h")
    if not H or H <= 0:
        bad("root 高度无效: %s" % H)
        return
    ok("root 尺寸 %sx%s" % (num(root, "w"), H))

    minh = lay.get("minInputBarH") or 0

    # A1 输入栏不被压扁
    ih = num(lay, "inputBar", "h")
    if ih is None or not minh:
        bad("inputBar 高度或 minInputBarH 缺失")
    elif ih < minh * 0.95:
        bad("输入栏被压扁: h=%d < 下限 %.0f" % (ih, minh * 0.95))
    else:
        ok("输入栏高度 %d >= 下限 %.0f" % (ih, minh))

    # A2 消息列表有实际高度
    ch = num(lay, "chatRec", "h")
    if not ch or ch <= 0:
        bad("消息列表高度无效: %s" % ch)
    else:
        ok("消息列表高度 %d" % ch)

    # A3 dock 高度与列表底部避让空间匹配(核心不变量: 少则最后一个气泡被遮, 多则大块空白)
    pdb, dh = num(lay, "chatRec", "padB"), num(lay, "dockContent", "h")
    if pdb is None or dh is None:
        bad("chatRec.padB / dockContent.h 缺失")
    elif pdb < dh - 2:
        bad("底部避让不足: padB=%d < dock 高 %d(最后一个气泡会被遮住)" % (pdb, dh))
    elif pdb > dh + 3 * max(minh, 1):
        bad("底部避让异常过大: padB=%d vs dock 高 %d" % (pdb, dh))
    else:
        ok("底部避让 %d 与 dock 高 %d 匹配(余量 %d)" % (pdb, dh, pdb - dh))

    # A4 dock 不得被顶出屏幕底部
    dy, dhh = num(lay, "dockContent", "y"), num(lay, "dockContent", "h")
    if None in (dy, dhh):
        bad("dock 坐标缺失")
    elif dy + dhh > H + TOL:
        bad("dock 被顶出屏幕底部 %dpx" % (dy + dhh - H))
    else:
        ok("dock 底边 %d 在屏幕内(%d)" % (dy + dhh, H))

    # A5 发送/停止不得同显
    sv, tv = num(lay, "sendBtn", "vis"), num(lay, "stopBtn", "vis")
    if sv == VISIBLE and tv == VISIBLE:
        bad("发送与停止按钮同时可见")
    else:
        ok("发送/停止互斥(send=%s stop=%s)" % (sv, tv))

    # A6 抽屉只能"全关(平移到左外)"或"全开(x=0)", 不允许半开残留
    dx, dw = num(lay, "drawerPanel", "x"), num(lay, "drawerPanel", "w")
    if None in (dx, dw) or not dw:
        bad("抽屉坐标缺失")
    elif abs(dx) > TOL and abs(dx + dw) > TOL:
        bad("抽屉半开残留: x=%d (w=%d, 应为 0 或 %d)" % (dx, dw, -dw))
    else:
        ok("抽屉位置合法(x=%d w=%d)" % (dx, dw))

    # A7 附件预览可见性与附件数一致
    av, ac = num(lay, "attachPreviewWrap", "vis"), lay.get("attachCount")
    if ac is None:
        bad("attachCount 缺失")
    elif ac > 0 and av != VISIBLE:
        bad("有 %s 个附件但预览条不可见(vis=%s)" % (ac, av))
    elif ac == 0 and av == VISIBLE:
        bad("无附件但预览条可见")
    else:
        ok("附件预览一致(count=%s vis=%s)" % (ac, av))

    # A8 关键控件尺寸合法
    weird = [k for k in ("root", "chatRec", "dockContent", "inputBar")
             if (num(lay, k, "w") or 0) < 0 or (num(lay, k, "h") or 0) < 0]
    if weird:
        bad("控件尺寸为负: %s" % ",".join(weird))
    else:
        ok("控件尺寸均合法")


KEYS = ["root", "chatArea", "chatRec", "dockContent", "inputBar", "input", "sendBtn",
        "stopBtn", "micBtn", "attachBtn", "attachBtn2", "drawerPanel", "speakBar", "memDot"]
FIELDS = ["vis", "w", "h", "y", "padB"]


def compare_baseline(lay):
    if not os.path.exists(BASELINE):
        dump = {k: {f: num(lay, k, f) for f in FIELDS} for k in KEYS}
        json.dump(dump, open(BASELINE, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print("  NOTE 基线已建立(下次运行起参与比对): %s" % BASELINE)
        return
    base = json.load(open(BASELINE, encoding="utf-8"))
    diffs = []
    for k in KEYS:
        for f in FIELDS:
            b, n = base.get(k, {}).get(f), num(lay, k, f)
            if b is None or n is None:
                continue
            if abs(n - b) > (0 if f == "vis" else TOL):
                diffs.append("%s.%s %s->%s" % (k, f, b, n))
    if diffs:
        bad("布局偏离基线: " + "; ".join(diffs[:8]) + ("..." if len(diffs) > 8 else ""))
    else:
        ok("关键控件与基线一致(容差%dpx)" % TOL)


def main():
    dev = os.environ.get("NYRAL_DEVICE", "").strip()
    if not dev:
        print("请先 export NYRAL_DEVICE=<adb 序列号>"); return 2
    if dev not in sh("adb devices"):
        print("设备不在线: %s" % dev); return 2
    sh("adb -s %s forward tcp:%d tcp:8765" % (dev, BRIDGE))
    ui = fetch(dev, get_token(dev))
    if ui is None:
        print("  通道不可用: /v1/ui 无响应(检查 DebugServer 开关、token)")
        return 2

    lay = ui.get("layout")
    if "--dump" in sys.argv:
        print(json.dumps(lay, ensure_ascii=False, indent=1)); return 0

    if not isinstance(lay, dict) or "error" in lay:
        bad("layout 段不可用: %s" % (lay if lay is not None else "缺失(旧包?)"))
    else:
        ok("layout 段可读")
        if "--update" in sys.argv and os.path.exists(BASELINE):
            os.remove(BASELINE)
        check_invariants(lay)
        compare_baseline(lay)

    print("\nVERDICT %s (%d pass / %d fail)" % ("PASS" if not FAIL else "FAIL", len(OK), len(FAIL)))
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
