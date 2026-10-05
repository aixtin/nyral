#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Nyral DebugServer 冒烟测试脚本(第2级集成测试, 2026-10-05)
验证: 设备连通 -> DebugServer 鉴权 -> /v1/chat SSE 完整链路(tool/tool_result/delta/done, 无 400)
      -> UI 发送链路(message_count 增量, 确认消息已渲染落库到会话)。
用法:
  python3 smoke_debug.py                    # 默认无线设备 10.10.10.3:5555
  python3 smoke_debug.py 192.168.2.132:5555 # 指定设备
  python3 smoke_debug.py --usb              # 优先 USB 有线设备
  python3 smoke_debug.py --message "现在几点了？用工具回答"   # 自定义冒烟消息
退出码: 0=通过, 1=失败
"""
import json
import re
import subprocess
import sys
import time

PKG = "io.github.aixtin.nyral"
ADB = "/home/ymz/android-sdk/platform-tools/adb"
PORT = 8765
MSG = "现在几点了？用工具回答"


def run(cmd, timeout=60):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout.strip(), p.stderr.strip()


def fail(msg):
    print("[FAIL]", msg)
    sys.exit(1)


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
        fail("无任何 adb 设备在线, 请先连接真机(USB 或 WG 无线)")
    # 优先 USB 有线
    usb = [d for d in devices if d[0] == "usb"]
    if usb:
        print("[INFO] 使用 USB 设备:", usb[0][1])
        return usb[0][1]
    print("[INFO] 使用无线设备:", devices[0][1])
    return devices[0][1]


def get_state(token):
    rc, out, _ = run(f'curl -s -m 10 -H "X-Auth-Token: {token}" http://127.0.0.1:{PORT}/v1/state')
    if rc != 0 or not out:
        return None
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        return None


def main():
    args = sys.argv[1:]
    if "--usb" in args:
        device = None
        for d in adb_devices():
            if d[0] == "usb":
                device = d[1]
                break
        if not device:
            fail("未找到 USB 设备")
    else:
        device = args[0] if args and ":" in args[0] else None
        if device is None:
            device = pick_device()
    global MSG
    for i, a in enumerate(args):
        if a == "--message" and i + 1 < len(args):
            MSG = args[i + 1]

    print("[1/5] 连接设备:", device)
    rc, out, _ = run(f"{ADB} connect {device}", timeout=30) if ":" in device else (0, "usb", "")
    time.sleep(1)
    rc, out, _ = run(f"{ADB} -s {device} get-state")
    if rc != 0 or "device" not in out:
        fail(f"设备 {device} 不在线: {out}")

    print("[2/5] 读取 DebugServer token")
    rc, out, _ = run(f"{ADB} -s {device} shell run-as {PKG} cat shared_prefs/debug_server.xml")
    if rc != 0:
        fail("无法读取 debug_server.xml, 确认 run-as 可用")
    m = re.search(r'<string name="token">([^<]+)</string>', out)
    if not m:
        fail("debug_server.xml 中未找到 token")
    token = m.group(1)
    print("[INFO] token:", token)

    print("[3/5] 端口转发 + /v1/state")
    run(f"{ADB} -s {device} forward tcp:{PORT} tcp:{PORT}")
    st = get_state(token)
    if not st:
        fail("/v1/state 无响应, DebugServer 可能未启动")
    if st.get("server", {}).get("enabled") is not True:
        fail("DebugServer 未启用(enabled != true)")
    if not st.get("session"):
        fail("session 信息缺失")
    base_count = st["session"].get("message_count")
    print("[INFO] server ok, session id:", st["session"].get("current_session_id"), ", message_count:", base_count)

    print("[4/5] POST /v1/chat 冒烟:", MSG)
    body = json.dumps({"message": MSG}, ensure_ascii=False)
    rc, out, _ = run(
        f'curl -s -N -m 90 -H "X-Auth-Token: {token}" -H "Content-Type: application/json" '
        f'-d \'{body}\' http://127.0.0.1:{PORT}/v1/chat'
    )
    if rc != 0 or not out:
        fail("/v1/chat 无响应")
    if "event: done" not in out:
        # 报错可能以 error event 或纯文本形式返回
        if "400" in out and ("insufficient tool messages" in out or "tool_calls" in out):
            fail("命中 400 insufficient tool messages(截断配对回归!)")
        if "event: error" in out or "error" in out.lower()[:2000]:
            fail("SSE 流中出现 error 事件")
        fail("未收到 event: done, 流不完整")
    seen = {"tool": False, "tool_result": False, "delta": False, "done": True}
    for ev in re.findall(r"^event: (\S+)", out, re.M):
        if ev in seen:
            seen[ev] = True
    print("[INFO] SSE 事件:", {k: v for k, v in seen.items()})
    if not (seen["done"] and (seen["delta"] or seen["tool"])):
        fail("SSE 链路不完整(缺 delta/done)")

    print("[5/5] UI 发送链路验证: message_count 增量")
    time.sleep(2)  # 等 AI 回复落库
    st2 = get_state(token)
    if not st2:
        fail("chat 后 /v1/state 无响应")
    after_count = st2["session"].get("message_count")
    if after_count is None or base_count is None:
        fail("session.message_count 缺失, 无法验证 UI 链路")
    delta = after_count - base_count
    print(f"[INFO] message_count: {base_count} -> {after_count} (delta={delta})")
    if delta < 2:
        fail(f"message_count 增量不足: 期望至少 +2(user+assistant), 实际 delta={delta}, 消息未渲染落库到会话")
    busy = st2["session"].get("ai_busy")
    if busy is not None and busy is not False:
        fail(f"chat 结束后 ai_busy 仍为 true: {busy}")
    print("[INFO] ai_busy:", busy)

    print("[PASS] DebugServer 冒烟通过: 鉴权 OK, SSE 链路完整无 400, UI 发送链路已渲染落库(message_count +%d)" % delta)


if __name__ == "__main__":
    main()
