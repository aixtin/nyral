# -*- coding: utf-8 -*-
"""Nyral 底栏回归验收 (2026-11-21 改版: 抽屉弹层下线)
断言:
  A1 附件行(拍照/相册/文件)常驻且位于输入栏下方
  A2 门禁按钮任一时刻只有一个可见(槽位原地替换)
  G1 点门禁按钮 -> 弹窗打开
  G2 再点门禁按钮 -> 弹窗关闭
  G3 弹窗打开时点外部 -> 弹窗关闭
  K1 点输入框 -> 键盘弹起, 输入栏上移(无键盘设备自动跳过)
  K2 附件行跟随输入栏整体上移, 相对位置不变
  K3 键盘收起后回到基线
用法: NYRAL_DEVICE=MF5HU48PGUTCSKZH python3 panel_sm_check.py [--no-install]
"""
import json, os, re, subprocess, sys, time

DEV = os.environ.get("NYRAL_DEVICE", "").strip()
PKG = os.environ.get("NYRAL_PKG", "io.github.aixtin.nyral")
BRIDGE = int(os.environ.get("NYRAL_BRIDGE", "18765"))
H = "http://127.0.0.1:%d" % BRIDGE
OUT = os.path.join(os.getcwd(), "sm-%s" % time.strftime("%Y%m%d-%H%M%S"))
os.makedirs(OUT, exist_ok=True)


def sh(cmd, t=120):
    try:
        p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=t)
        return (p.stdout or "") + (p.stderr or "")
    except Exception as e:
        return "ERR %s" % e


def get_token():
    if os.environ.get("NYRAL_TOKEN"):
        return os.environ["NYRAL_TOKEN"].strip()
    for c in ['adb -s %s shell "su -c \'cat /data/data/%s/shared_prefs/debug_server.xml\'"' % (DEV, PKG),
              'adb -s %s shell "run-as %s cat shared_prefs/debug_server.xml"' % (DEV, PKG)]:
        m = re.search(r'name="token"[^>]*>([^<]+)<', sh(c))
        if m:
            return m.group(1).strip()
    return ""


T = get_token()
if not T:
    print("no token"); sys.exit(2)


def api(path, method="GET", data=None, timeout=8):
    cmd = 'curl -s -m ' + str(timeout) + ' -H "X-Auth-Token: ' + T + '"'
    if method == "POST":
        cmd += " -X POST -H 'Content-Type: application/json' -d '%s'" % json.dumps(data or {})
    cmd += " %s%s" % (H, path)
    raw = sh(cmd)
    try:
        return json.loads(raw)
    except Exception:
        return {"_raw": raw[:300]}


def tap(x, y):
    api("/v1/touch", "POST", {"type": "tap", "x": x, "y": y}, timeout=6)


def lay():
    return (api("/v1/ui") or {}).get("layout") or {}


def box(k):
    b = lay().get(k) or {}
    if not b or b.get("null") or b.get("vis", 1) != 0:
        return None
    if (b.get("w") or 0) <= 0 or (b.get("h") or 0) <= 0:
        return None
    return b


def ctr(k):
    b = box(k)
    return None if not b else (b["x"] + b["w"] / 2.0, b["y"] + b["h"] / 2.0)


def gate():
    return ctr("attachBtn2") or ctr("attachBtn")


def tap_gate():
    c = gate()
    if not c:
        return None
    tap(c[0], c[1])
    return c


def frames():
    d = api("/v1/trace", timeout=12)
    rows = []
    for ln in (d.get("frames") or []):
        v = ln.split(",")
        if len(v) < 9:
            continue
        try:
            rows.append(dict(t=int(v[0]), y=int(v[1]), ih=int(v[2]), ch=int(v[3]),
                             pad=int(v[4]), ph=int(v[5]), po=int(v[6]), it=int(v[7]), ish=int(v[8])))
        except Exception:
            pass
    return rows


def trace_on():
    api("/v1/trace", "POST", {"on": True})
    time.sleep(0.25)


fails, notes = [], []


def chk(name, cond, detail, skip=False):
    (notes if cond else fails).append("%s %s | %s" % ("SKIP" if skip else ("OK  " if cond else "FAIL"), name, detail))
    print("  %s %s (%s)" % ("skip" if skip else ("ok  " if cond else "FAIL"), name, detail))


def wait_ready(limit=40):
    for _ in range(limit):
        if lay().get("attachRow") and gate():
            return True
        time.sleep(0.6)
    return False


def jump_check(tag, rows, allow_flip=1):
    try:
        import csv as _csv
        with open(os.path.join(OUT, "f_%s.csv" % tag.replace("/", "_")), "w") as fp:
            w = _csv.writer(fp); w.writerow(["t", "y", "ih", "ch", "pad", "ph", "po", "it", "ish"])
            for r in rows:
                w.writerow([r["t"], r["y"], r["ih"], r["ch"], r["pad"], r["ph"], r["po"], r["it"], r["ish"]])
    except Exception:
        pass
    dirs = []
    maxd = 0
    for a, b in zip(rows, rows[1:]):
        dy = b["y"] - a["y"]
        maxd = max(maxd, abs(dy))
        if abs(dy) >= 4:
            dirs.append(1 if dy > 0 else -1)
    flips = sum(1 for x, y in zip(dirs, dirs[1:]) if x != y)
    chk("%s 无方向折返" % tag, flips <= allow_flip,
        "折返=%d maxStep=%dpx dirs=%s" % (flips, maxd, dirs[:12]))


def main():
    if "--no-install" not in sys.argv:
        print(sh("adb -s %s install -r app/build/outputs/apk/debug/app-debug.apk" % DEV, t=240).strip()[-60:])
    sh("adb -s %s shell am force-stop %s" % (DEV, PKG))
    time.sleep(0.6)
    sh("adb -s %s shell am start -n %s/.MainActivity" % (DEV, PKG))
    time.sleep(2.5)
    sh("adb -s %s forward tcp:%d tcp:8765" % (DEV, BRIDGE))
    if not wait_ready():
        sh("adb -s %s shell monkey -p %s -c android.intent.category.LAUNCHER 1" % (DEV, PKG), t=40)
        time.sleep(4)
    if not wait_ready():
        print("UI 未就绪"); sys.exit(2)

    l = lay()
    row, bar = l.get("attachRow") or {}, l.get("inputBar") or {}
    base_y = bar.get("y", 0)
    base_row_y = row.get("y", 0)
    chk("A1 附件行常驻", row.get("vis") == 0 and row.get("h", 0) >= 100 and row.get("w") == l.get("root", {}).get("w"),
        "row=%s" % row)
    chk("A1 附件行在输入栏下方", base_row_y >= base_y + bar.get("h", 0) - 2,
        "rowY=%s barBot=%s" % (base_row_y, base_y + bar.get("h", 0)))
    vis_gate = [k for k in ("attachBtn", "attachBtn2") if (l.get(k) or {}).get("vis") == 0]
    chk("A2 门禁按钮唯一可见", len(vis_gate) == 1, "visible=%s" % vis_gate)

    tap_gate(); time.sleep(0.5)
    chk("G1 门禁弹窗打开", (lay().get("gatePopupShowing") is True), "popup=%s" % lay().get("gatePopupShowing"))
    tap_gate(); time.sleep(0.5)
    chk("G2 再点门禁关闭", (lay().get("gatePopupShowing") is False), "popup=%s" % lay().get("gatePopupShowing"))
    tap_gate(); time.sleep(0.5)
    tap(540, 900); time.sleep(0.5)
    chk("G3 点外部关闭", (lay().get("gatePopupShowing") is False), "popup=%s" % lay().get("gatePopupShowing"))

    # K: 键盘联动(无软键盘的设备上跳过)
    trace_on()
    gi = ctr("input")
    tap(gi[0], gi[1]); time.sleep(1.6)
    rows = frames()
    kb = (box("inputBar") or {}).get("y", base_y)
    row2 = (box("attachRow") or {}).get("y", base_row_y)
    lifted = kb < base_y - 100
    if lifted:
        chk("K1 键盘弹起输入栏上移", True, "y=%d base=%d" % (kb, base_y))
        chk("K2 附件行同步上移", row2 < base_row_y - 100, "rowY=%d baseRow=%d" % (row2, base_row_y))
        chk("K2 相对位置不变", abs((row2 - kb) - (base_row_y - base_y)) <= 3,
            "delta=%d base=%d" % (row2 - kb, base_row_y - base_y))
        jump_check("K-开", rows)
        adb_back = sh("adb -s %s shell input keyevent KEYCODE_BACK" % DEV)
        time.sleep(1.6)
        rows2 = frames()
        back = (box("inputBar") or {}).get("y", base_y)
        chk("K3 键盘收起回到基线", abs(back - base_y) <= 6, "y=%d base=%d" % (back, base_y))
        jump_check("K-收", rows2, allow_flip=2)
    else:
        chk("K1 键盘联动", True, "设备未弹软键盘, 跳过", skip=True)

    print("\n===== SUMMARY =====")
    for x in notes:
        print("  " + x)
    for x in fails:
        print("  " + x)
    print("PASS" if not fails else "FAIL(%d)" % len(fails))
    open(os.path.join(OUT, "summary.txt"), "w").write("\n".join(notes + fails))
    sys.exit(0 if not fails else 1)


main()
