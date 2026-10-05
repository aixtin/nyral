#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nyral 第4级发版回归: 固定靶场 6 条历史问题复测脚本 v2 (2026-10-06)
靶场: https://atin.asia/nyral-range/  (?level=none|normal|heavy|empty 切档)
历史问题清单:
  1. scan 空数组    - 空页 scan 不崩溃, count=0
  2. 重复采集        - 同一页连续重扫 count 恒定(容器+叶子去重不累积)
  3. scroll 容器     - 滚动后重扫可见集合正确变化(滚动->重扫工作流不打断)
  4. 坐标越界        - 极端滚动后重扫不崩溃(越界坐标弃采, 单测已锁行为规范)
  5. sticky 吸顶     - 滚到底后 sticky/fixed 元素仍被采集(不因越界误杀)
  6. 浮层劫持        - 广告元素被采集且被浮层遮挡时拒点(防护生效)
用法:
  python3 range_regression.py                 # 自动选设备
  python3 range_regression.py 10.10.10.3:5555 # 指定无线设备
  python3 range_regression.py --usb
退出码: 0=全部通过, 1=存在失败
"""
import json
import re
import subprocess
import sys
import time

PKG = "io.github.aixtin.nyral"
ADB = "/home/ymz/android-sdk/platform-tools/adb"
PORT = 8765
RANGE = "https://atin.asia/nyral-range/"
RESULTS = []


def run(cmd, timeout=60):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout.strip(), p.stderr.strip()


def fail(msg):
    print("[FATAL]", msg)
    sys.exit(2)


def adb_devices():
    rc, out, _ = run(f"{ADB} devices -l")
    devices = []
    for line in out.splitlines()[1:]:
        if not line.strip():
            continue
        parts = line.split()
        serial = parts[0]
        if "usb:" in line:
            devices.append(("usb", serial))
        elif ":" in serial:
            devices.append(("wifi", serial))
    return devices


def pick_device():
    devices = adb_devices()
    if not devices:
        fail("无 adb 设备在线, 请先连接真机(USB 或 WG 无线)")
    usb = [d for d in devices if d[0] == "usb"]
    if usb:
        print("[INFO] 使用 USB 设备:", usb[0][1])
        return usb[0][1]
    print("[INFO] 使用无线设备:", devices[0][1])
    return devices[0][1]


def setup(device):
    if ":" in device:
        rc, out, err = run(f"{ADB} connect {device}")
        print("[INFO] adb connect:", out or err)
        time.sleep(1)
    rc, out, err = run(f"{ADB} -s {device} shell pm path {PKG}")
    if rc != 0:
        fail(f"设备上未安装 {PKG}: {err or out}")
    rc, out, err = run(f"{ADB} -s {device} shell run-as {PKG} cat shared_prefs/debug_server.xml")
    if rc != 0 or "token" not in out:
        fail(f"无法读取 debug_server.xml: {err or out}")
    m = re.search(r'<string name="token">([^<]+)</string>', out)
    if not m:
        fail("debug_server.xml 中未找到 token")
    token = m.group(1).strip()
    run(f"{ADB} -s {device} forward tcp:{PORT} tcp:{PORT}")
    print("[INFO] token:", token[:12] + "...")
    return token


def http(method, path, body=None, token=None, timeout=30):
    import urllib.request
    url = f"http://127.0.0.1:{PORT}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if token:
        req.add_header("X-Auth-Token", token)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode())
    except Exception as e:
        return 0, {"error": str(e)}


def check(name, cond, detail):
    tag = "PASS" if cond else "FAIL"
    RESULTS.append((name, tag, detail))
    print(f"[{tag}] {name}: {detail}")
    return cond


def open_url(token, url):
    return http("POST", "/v1/browser/open", {"url": url}, token, timeout=40)


def scan(token):
    return http("POST", "/v1/browser/scan", None, token, timeout=40)


def scroll(token, delta):
    return http("POST", "/v1/browser/scroll", {"delta": delta}, token, timeout=40)


def click(token, index):
    return http("POST", "/v1/browser/click", {"index": index}, token, timeout=40)


def main():
    args = sys.argv[1:]
    device = None
    for a in args:
        if a == "--usb":
            for d in adb_devices():
                if d[0] == "usb":
                    device = d[1]
                    break
            if not device:
                fail("未找到 USB 设备")
        elif not a.startswith("--") and ":" in a:
            device = a
    if device is None:
        device = pick_device()
    token = setup(device)

    print("\n=== T1 scan 空数组: empty 档空页 ===")
    st, js = open_url(token, RANGE + "?level=empty")
    ok = st == 200 and js.get("ok") is True
    check("T1 open empty 档", ok, f"st={st} {js}")
    time.sleep(1)
    st, js = scan(token)
    count = js.get("count", -1) if st == 200 else -1
    t1 = check("T1 scan 空数组返回 count=0", st == 200 and js.get("ok") is True and count == 0,
               f"count={count}")

    print("\n=== T2 重复采集: heavy 档连扫 3 次 ===")
    st, js = open_url(token, RANGE + "?level=heavy")
    c1 = js.get("count", -1) if st == 200 else -1
    time.sleep(1.5)
    st, js = scan(token)
    c2 = js.get("count", -1) if st == 200 else -1
    time.sleep(1)
    st, js = scan(token)
    c3 = js.get("count", -1) if st == 200 else -1
    t2 = check("T2 重复采集 count 恒定", st == 200 and c1 == c2 == c3 >= 0,
               f"counts={c1}/{c2}/{c3}")

    print("\n=== T3 scroll 容器: normal 档滚动重扫 ===")
    st, js = open_url(token, RANGE + "?level=normal")
    c0 = js.get("count", -1) if st == 200 else -1
    time.sleep(1.5)
    st, js = scroll(token, 600)
    s_msg = str(js.get("message", ""))
    st2, js2 = scan(token)
    c_down = js2.get("count", -1) if st2 == 200 else -1
    st3, js3 = scroll(token, -600)
    st4, js4 = scan(token)
    c_back = js4.get("count", -1) if st4 == 200 else -1
    t3 = check("T3 滚动->重扫工作流完整(滚动实际生效, 快照稳定不丢元素)", st == 200 and st2 == 200 and st3 == 200 and st4 == 200
               and "实际 600/请求 600" in s_msg and c_down == c0 and c_back == c0 and c_down > 0,
               f"c0={c0} -> down={c_down} -> back={c_back} | scroll={s_msg[:40]}")

    print("\n=== T4 坐标越界: 极端滚动不崩溃 ===")
    st, js = open_url(token, RANGE + "?level=normal")
    time.sleep(1.5)
    st1, js1 = scroll(token, 50000)
    st2, js2 = scan(token)
    st3, js3 = scroll(token, -50000)
    st4, js4 = scan(token)
    t4 = check("T4 极端滚动+重扫 4 次全 ok", st == 200 and st1 == 200 and st2 == 200 and st3 == 200 and st4 == 200
               and js2.get("ok") is True and js4.get("ok") is True,
               f"scroll=50000 ok, scan={js2.get('count')}, rollback ok, scan={js4.get('count')}")

    print("\n=== T5 sticky 吸顶: 滚到底后固定元素仍在采集 ===")
    st, js = open_url(token, RANGE + "?level=normal")
    c0 = js.get("count", -1) if st == 200 else -1
    time.sleep(1.5)
    st1, js1 = scroll(token, 50000)
    st2, js2 = scan(token)
    c_bottom = js2.get("count", -1) if st2 == 200 else -1
    t5 = check("T5 滚到底重扫: 全量采集完整不丢元素(sticky/固定元素未被越界误杀)",
               st == 200 and st1 == 200 and st2 == 200 and c_bottom == c0 and c_bottom > 0,
               f"c0={c0} -> bottom={c_bottom}")

    print("\n=== T6 浮层劫持: 广告采集 + 遮挡拒点防护 ===")
    st, js = open_url(token, RANGE + "?level=none")
    c_none = js.get("count", -1) if st == 200 else -1
    time.sleep(1.5)
    none_click_ok = True
    none_msgs = []
    for i in range(min(4, max(c_none - 1, 0))):
        stc, jsc = click(token, i)
        if stc != 200 or jsc.get("ok") is not True:
            none_click_ok = False
        none_msgs.append(str(jsc.get("message", ""))[:50])
    none_rejected = any("遮挡" in m for m in none_msgs)
    st, js = open_url(token, RANGE + "?level=heavy")
    c_heavy = js.get("count", -1) if st == 200 else -1
    time.sleep(1.5)
    ad_delta = c_heavy - c_none
    heavy_click_ok = True
    heavy_msgs = []
    for i in range(min(4, max(c_heavy - 1, 0))):
        stc, jsc = click(token, i)
        if stc != 200 or jsc.get("ok") is not True:
            heavy_click_ok = False
        heavy_msgs.append(str(jsc.get("message", ""))[:60])
    heavy_rejected = any("遮挡" in m for m in heavy_msgs)
    st, js = scan(token)
    c_after = js.get("count", -1) if st == 200 else -1
    t6 = check("T6 广告采集 + 浮层拒点防护生效",
               st == 200 and c_heavy > c_none and none_click_ok
               and heavy_click_ok and heavy_rejected and c_after > 0,
               f"none={c_none} heavy={c_heavy} delta={ad_delta} heavy拒点={heavy_rejected} after={c_after}")
    print("     none clicks:", none_msgs)
    print("     heavy clicks:", heavy_msgs)

    print("\n========== 汇总 ==========")
    passed = sum(1 for r in RESULTS if r[1] == "PASS")
    for name, tag, detail in RESULTS:
        print(f"{tag}  {name}")
    print(f"结果: {passed}/{len(RESULTS)} 通过")
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
