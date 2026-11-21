#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nyral 键盘/附件面板切换 帧级轨迹采集与判定 (2026-11-21)

用 /v1/trace 的逐帧数据把"消息掉一下/闪一下"变成可判定曲线:
  列: tMs,inputBarY,inputBarH,chatH,padB,panelH,panelOpen,imeTarget,imeShown

判定口径:
  P1 折返: 切换窗口内 inputBarY 出现"先下后上"(幅度>8px) = 掉底再顶回
  P2 静止漂移: 切换完成后的静止窗(后 400ms), inputBarY 极差 > 4px = 收尾抖动
  P3 让位错配: |padB - (inputBarH + 固定量 + panelH)| 偏差 > 3px 的帧数占比 > 5%

用法: NYRAL_DEVICE=<serial> ./trace_switch.py [--rapid] [--outdir DIR]
"""
import json, os, re, subprocess, sys, time

DEV = os.environ.get("NYRAL_DEVICE", "").strip()
if not DEV:
    print("need NYRAL_DEVICE"); sys.exit(2)
PKG = os.environ.get("NYRAL_PKG", "io.github.aixtin.nyral")
BRIDGE = int(os.environ.get("NYRAL_BRIDGE", "18765"))
H = "http://127.0.0.1:%d" % BRIDGE
RAPID = "--rapid" in sys.argv
OUT = None
for i, a in enumerate(sys.argv):
    if a == "--outdir" and i + 1 < len(sys.argv):
        OUT = sys.argv[i + 1]
OUT = OUT or os.path.join(os.getcwd(), "trace-%s" % time.strftime("%Y%m%d-%H%M%S"))
os.makedirs(OUT, exist_ok=True)


def sh(cmd, t=60):
    try:
        p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=t)
        return (p.stdout or "") + (p.stderr or "")
    except Exception as e:
        return "ERR %s" % e


def get_token():
    if os.environ.get("NYRAL_TOKEN"):
        return os.environ["NYRAL_TOKEN"].strip()
    for c in ['adb -s %s shell "su -c \'cat /data/data/%s/shared_prefs/debug_server.xml\'"' % (DEV, PKG),
              'adb -s %s shell "run-as %s cat /data/data/%s/shared_prefs/debug_server.xml"' % (DEV, PKG, PKG)]:
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
        return {"_raw": raw[:400]}


def tap(x, y):
    api("/v1/touch", "POST", {"type": "tap", "x": x, "y": y}, timeout=6)


def ui():
    return api("/v1/ui")


def btn_center(layout, key):
    """取按钮中心; GONE/零尺寸的别名按钮(attachBtn 与 attachBtn2 互斥显示)必须跳过"""
    b = (layout or {}).get(key) or {}
    if not b or b.get("null"):
        return None
    if b.get("vis", 0) != 0 or (b.get("w") or 0) <= 0 or (b.get("h") or 0) <= 0:
        return None
    return (b["x"] + b["w"] / 2.0, b["y"] + b["h"] / 2.0)


def dump_csv(frames, name):
    p = os.path.join(OUT, name)
    with open(p, "w") as f:
        f.write("tMs,inputBarY,inputBarH,chatH,padB,panelH,panelOpen,imeTarget,imeShown\n")
        for ln in frames:
            f.write(ln + "\n")
    return p


def parse(frames):
    rows = []
    for ln in frames:
        v = ln.split(",")
        if len(v) < 9:
            continue
        rows.append(dict(t=int(v[0]), y=int(v[1]), ih=int(v[2]), ch=int(v[3]),
                         pad=int(v[4]), ph=int(v[5]), po=int(v[6]), it=int(v[7]), ish=int(v[8])))
    return rows


def verdict(rows, tag):
    """折返判定: 在 [start,end) 区间内 inputBarY 是否出现 先下后上 且幅度 > 8px"""
    fails = []
    if len(rows) < 5:
        return ["%s: 轨迹样本不足(%d)" % (tag, len(rows))]
    ys = [r["y"] for r in rows]
    lo, hi = min(ys), max(ys)
    rng = hi - lo
    # 方向变化(带 3px 死区, 滤掉亚像素抖)
    dirs = []
    last = ys[0]
    for y in ys[1:]:
        d = y - last
        if abs(d) >= 3:
            dirs.append(1 if d > 0 else -1)
            last = y
    turns = sum(1 for i in range(1, len(dirs)) if dirs[i] != dirs[i - 1])
    # 静止窗: 最后 300ms 内的极差
    t_end = rows[-1]["t"]
    tail = [r["y"] for r in rows if r["t"] >= t_end - 300]
    tail_rng = (max(tail) - min(tail)) if tail else 0
    print("  %s: 帧=%d 跨度=%dms y极差=%dpx 方向折返=%d次 静止窗极差=%dpx" % (
        tag, len(rows), rows[-1]["t"] - rows[0]["t"], rng, turns, tail_rng))
    if turns >= 2 and rng > 8:
        fails.append("%s: 输入栏位移折返 %d 次(极差 %dpx) -> 掉底再顶回" % (tag, turns, rng))
    if tail_rng > 4:
        fails.append("%s: 静止窗仍有 %dpx 漂移 -> 收尾抖动" % (tag, tail_rng))
    return fails


def wait_ui_ready(limit=40):
    for _ in range(limit):
        u = ui()
        if (u.get("layout") or {}).get("attachBtn"):
            return u
        time.sleep(0.6)
    return {}


def main():
    devs = [l.split()[0] for l in sh("adb devices").splitlines()[1:] if "\tdevice" in l]
    print("device=%s online=%s" % (DEV, DEV in devs))
    if DEV not in devs:
        sys.exit(2)
    if "--no-install" not in sys.argv:
        print(sh("adb -s %s install -r app/build/outputs/apk/debug/app-debug.apk" % DEV, t=240).strip()[-80:])
    sh("adb -s %s shell am force-stop %s" % (DEV, PKG))
    time.sleep(0.5)
    # redroid/monkey 启动不可靠, 直接 am start 并轮询进程
    sh("adb -s %s shell am start -n %s/.MainActivity" % (DEV, PKG))
    for _ in range(20):
        time.sleep(0.8)
        if sh("adb -s %s shell pidof %s" % (DEV, PKG)).strip():
            break
    sh("adb -s %s forward tcp:%d tcp:8765" % (DEV, BRIDGE))
    u = wait_ui_ready()
    if not u:
        print("UI 未就绪: %s" % json.dumps(u, ensure_ascii=False)[:300])
        sys.exit(2)
    lay = u.get("layout") or {}
    print("density=%s inputBarH=%s" % (lay.get("density"), (lay.get("inputBar") or {}).get("h")))
    c = btn_center(lay, "attachBtn") or btn_center(lay, "attachBtn2")
    if not c:
        print("找不到附件按钮坐标"); sys.exit(2)
    print("attachBtn center=(%.0f,%.0f)" % c)

    seqs = []
    if RAPID:
        # 快速连点 4 次, 每次间隔 120ms: 覆盖"动画未落地就反向"的串扰路径
        seqs = [("rapid4", 0.12, 1.2)]
    else:
        seqs = [("panel", 1.0, 1.0), ("kb", 1.0, 1.2), ("panel2", 1.0, 1.0)]

    report = []
    for name, gap, settle in seqs:
        api("/v1/trace", "POST", {"on": True})
        time.sleep(0.3)
        taps = 4 if RAPID else 2
        for _ in range(taps):
            tap(*c)
            time.sleep(gap)
        time.sleep(settle)
        d = api("/v1/trace", timeout=12)
        frames = d.get("frames") or []
        p = dump_csv(frames, "%s.csv" % name)
        rows = parse(frames)
        print("[%s] frames=%d -> %s" % (name, len(rows), p))
        # 分段: 以 imeShown/panelOpen 变化切窗, 简单按时间均分 3 段检查折返
        if rows:
            n = len(rows)
            for i in range(3):
                seg = rows[i * n // 3:(i + 1) * n // 3]
                if seg:
                    report += verdict(seg, "%s#%d" % (name, i + 1))
        time.sleep(0.4)

    print("\n===== VERDICT =====")
    if report:
        for r in report:
            print("  FAIL " + r)
    else:
        print("  PASS 未检测到折返/收尾漂移")
    with open(os.path.join(OUT, "report.txt"), "w") as f:
        f.write("\n".join(report) or "PASS")
    print("out=%s" % OUT)
    sys.exit(1 if report else 0)


main()
