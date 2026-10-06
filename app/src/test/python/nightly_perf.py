#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nyral nightly 性能采样 (2026-10-06)
覆盖决策②可自动化部分: 冷启动耗时 / 内存峰值 / DebugServer 接口延迟
SSH 握手延迟与记忆召回延迟依赖真机用户配置(VPS/记忆库), 留发版前真机手动采样, 见 README 备注。

用法:
  python3 nightly_perf.py --emulator       # CI 模拟器 (emulator-5554)
  python3 nightly_perf.py 10.10.10.3:5555  # 真机(需 DebugServer 已开启)
  python3 nightly_perf.py --emulator --json perf.json
环境变量: ADB 可覆盖 adb 路径
退出码: 0=采样完成, 2=设备/前置失败
"""
import argparse
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import time

PKG = "io.github.aixtin.nyral"
ACTIVITY = "io.github.aixtin.nyral/.MainActivity"
EMULATOR_SERIAL = "emulator-5554"
ADB = os.environ.get("ADB") or shutil.which("adb") or "/home/ymz/android-sdk/platform-tools/adb"
PORT = 8765
COLD_RUNS = 3


def run(cmd, timeout=60):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout.strip(), p.stderr.strip()


def fail(msg):
    print("[FATAL]", msg)
    sys.exit(2)


def cold_start_ms(device):
    """force-stop 后 am start -W 测冷启动 TotalTime(毫秒)"""
    run(f"{ADB} -s {device} shell am force-stop {PKG}")
    time.sleep(1.5)
    rc, out, err = run(f"{ADB} -s {device} shell am start -W -n {ACTIVITY}", timeout=90)
    if rc != 0:
        return None
    m = re.search(r"TotalTime:\s*(\d+)", out)
    return int(m.group(1)) if m else None


def memory_pss_kb(device):
    """启动后 dumpsys meminfo 取 App 进程 TOTAL PSS (KB)"""
    rc, out, err = run(f"{ADB} -s {device} shell dumpsys meminfo {PKG}", timeout=60)
    if rc != 0:
        return None
    # 取进程块内 TOTAL PSS: 匹配 "TOTAL PSS:" 行
    m = re.search(r"TOTAL PSS:\s+([\d,]+)", out)
    if not m:
        # 兼容多进程: 汇总所有进程块的 TOTAL PSS
        totals = [int(x.replace(",", "")) for x in re.findall(r"TOTAL PSS:\s+([\d,]+)", out)]
        return sum(totals) if totals else None
    return int(m.group(1).replace(",", ""))


def debug_server_state_ms(device, token):
    """DebugServer /v1/state 响应延迟(毫秒), 需已 forward tcp:8765"""
    import urllib.request
    url = f"http://127.0.0.1:{PORT}/v1/state"
    req = urllib.request.Request(url, method="GET")
    req.add_header("X-Auth-Token", token)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            resp.read()
            return (time.perf_counter() - t0) * 1000
    except Exception:
        return None


def get_token(device):
    rc, out, err = run(f"{ADB} -s {device} shell run-as {PKG} cat shared_prefs/debug_server.xml")
    if rc != 0 or "token" not in out:
        return None
    m = re.search(r'<string name="token">([^<]+)</string>', out)
    return m.group(1).strip() if m else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("device", nargs="?", default=None, help="设备 serial (如 10.10.10.3:5555)")
    ap.add_argument("--emulator", action="store_true", help="使用 CI 模拟器 emulator-5554")
    ap.add_argument("--json", default=None, help="采样结果写 JSON 文件")
    args = ap.parse_args()

    device = args.device
    if args.emulator:
        device = EMULATOR_SERIAL
    if not device:
        rc, out, err = run(f"{ADB} devices")
        lines = [l.split() for l in out.splitlines()[1:] if l.strip()]
        if not lines:
            fail("无 adb 设备")
        device = lines[0][0]
    if ":" in device:
        run(f"{ADB} connect {device}")
        time.sleep(1)
    rc, _, _ = run(f"{ADB} -s {device} shell pm path {PKG}")
    if rc != 0:
        fail(f"设备 {device} 上未安装 {PKG}")

    print(f"[INFO] device={device}")
    print(f"[INFO] 冷启动采样 {COLD_RUNS} 次 (force-stop 后 am start -W) ...")
    starts = []
    for i in range(COLD_RUNS):
        ms = cold_start_ms(device)
        print(f"  run{i + 1}: {ms} ms" if ms is not None else f"  run{i + 1}: 失败")
        if ms is not None:
            starts.append(ms)
    cold_median = statistics.median(starts) if starts else None

    print("[INFO] 内存采样 (dumpsys meminfo) ...")
    time.sleep(1)
    pss_kb = memory_pss_kb(device)

    print("[INFO] DebugServer /v1/state 延迟 ...")
    run(f"{ADB} -s {device} forward tcp:{PORT} tcp:{PORT}")
    token = get_token(device)
    state_ms = debug_server_state_ms(device, token) if token else None

    result = {
        "device": device,
        "cold_start_median_ms": cold_median,
        "cold_start_samples_ms": starts,
        "mem_pss_kb": pss_kb,
        "debug_state_ms": state_ms,
        "note": "SSH握手/记忆召回延迟需真机用户配置, 发版前手动采样",
    }
    print("\n========== 性能采样结果 ==========")
    for k, v in result.items():
        print(f"{k}: {v}")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print(f"[INFO] 已写入 {args.json}")
    sys.exit(0)


if __name__ == "__main__":
    main()
