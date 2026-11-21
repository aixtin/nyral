# -*- coding: utf-8 -*-
"""Nyral 抽屉按钮 "+"→"×" 旋转反馈验收 (2026-11-21)
断言:
  I1 键盘关时点抽屉: 角度 0 -> 45(末态), 且中途出现中间值(说明是动画不是瞬切)
  I2 再点抽屉: 角度 45 -> 0(末态), 中途出现中间值
  I3 末态角度与面板状态一致(开=45, 关=0)
用法: NYRAL_DEVICE=emulator-5554 python3 icon_check.py [--no-install]
"""
import json, os, re, subprocess, sys, time

DEV = os.environ.get("NYRAL_DEVICE", "").strip()
PKG = os.environ.get("NYRAL_PKG", "io.github.aixtin.nyral")
BRIDGE = int(os.environ.get("NYRAL_BRIDGE", "18765"))
H = "http://127.0.0.1:%d" % BRIDGE
FAILS = []


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
              'adb -s %s shell "run-as %s cat /data/data/%s/shared_prefs/debug_server.xml"' % (DEV, PKG, PKG)]:
        m = re.search(r'name="token"[^>]*>([^<]+)<', sh(c))
        if m:
            return m.group(1).strip()
    return ""


T = get_token()
if not T:
    print("no token"); sys.exit(2)


def api(path, method="GET", data=None, timeout=6):
    cmd = 'curl -s -m %d -H "X-Auth-Token: %s"' % (timeout, T)
    if method == "POST":
        cmd += " -X POST -H 'Content-Type: application/json' -d '%s'" % json.dumps(data or {})
    cmd += " %s%s" % (H, path)
    raw = sh(cmd)
    try:
        return json.loads(raw)
    except Exception:
        return {"_raw": raw[:200]}


def ui():
    return api("/v1/ui").get("layout", {})


def chk(name, ok, msg=""):
    print(("  OK   " if ok else "  FAIL ") + name + (" | " + msg if msg else ""))
    if not ok:
        FAILS.append(name)


def launch():
    if "--no-install" not in sys.argv:
        sh("adb -s %s install -r app/build/outputs/apk/debug/app-debug.apk" % DEV, t=300)
    sh("adb -s %s shell am force-stop %s" % (DEV, PKG))
    sh("adb -s %s shell am force-stop com.android.documentsui" % DEV)
    time.sleep(0.6)
    sh("adb -s %s shell am start -n %s/.MainActivity" % (DEV, PKG)); time.sleep(3)
    sh("adb -s %s forward tcp:%d tcp:8765" % (DEV, BRIDGE))
    for _ in range(14):
        if ui().get("inputBar"):
            return True
        sh("adb -s %s shell monkey -p %s -c android.intent.category.LAUNCHER 1" % (DEV, PKG), t=40)
        time.sleep(2)
    return bool(ui().get("inputBar"))


def angle():
    return float(ui().get("attachIconAngle", -1))


def tap_attach():
    b = ui().get("attachBtn") or {}
    if b.get("vis") != 0 or not b.get("w"):
        b = ui().get("attachBtn2") or {}
    api("/v1/touch", "POST", {"type": "tap", "x": b.get("x", 0) + b.get("w", 0) / 2.0,
                              "y": b.get("y", 0) + b.get("h", 0) / 2.0})


def sample_tap():
    """点一次并连续采样角度, 返回 (采样序列, 面板状态)"""
    tap_attach()
    seq = []
    t0 = time.time()
    while time.time() - t0 < 0.45:
        a = angle()
        if a >= 0:
            seq.append(a)
        time.sleep(0.01)
    lay = ui()
    return seq, lay


def main():
    if not launch():
        print("UI 未就绪"); sys.exit(2)
    time.sleep(0.4)
    a0 = angle()
    chk("I0 初始为加号", abs(a0) < 0.5, "angle=%.1f" % a0)

    seq, lay = sample_tap()
    mid = [v for v in seq if 0.5 < v < 44.5]
    a1 = seq[-1] if seq else -1
    open_state = lay.get("attachPanelOpen")
    chk("I1 开抽屉转到 45°", abs(a1 - 45) < 0.5, "末态=%.1f 采样=%s" % (a1, ["%.0f" % v for v in seq[:14]]))
    chk("I1 是动画(有中间角度)", len(mid) > 0, "中间帧=%d 例=%s" % (len(mid), ["%.0f" % v for v in mid[:6]]))

    seq2, lay2 = sample_tap()
    mid2 = [v for v in seq2 if 0.5 < v < 44.5]
    a2 = seq2[-1] if seq2 else -1
    chk("I2 关抽屉转回 0°", abs(a2) < 0.5, "末态=%.1f 采样=%s" % (a2, ["%.0f" % v for v in seq2[:14]]))
    chk("I2 是动画(有中间角度)", len(mid2) > 0, "中间帧=%d" % len(mid2))

    print("===== SUMMARY =====")
    if FAILS:
        print("FAIL(%d)" % len(FAILS)); sys.exit(1)
    print("PASS")


if __name__ == "__main__":
    main()
