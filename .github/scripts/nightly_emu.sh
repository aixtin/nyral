#!/usr/bin/env bash
# Nightly 模拟器回归+性能采样（由 nightly.yml 调用；多行逻辑必须落脚本文件，
# 因为 android-emulator-runner 的 script 会按行拆成独立 sh -c 执行）
set -e
echo "== 1. 安装 APK =="
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "== 2. 注入 DebugServer 开关与 token (CI 新装默认关闭) =="
printf '%s' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="enabled" value="true" /><string name="token">droid-nightly-'"$GITHUB_RUN_ID"'</string></map>' > /tmp/debug_server.xml
adb push /tmp/debug_server.xml /data/local/tmp/debug_server.xml
adb shell run-as $PKG mkdir -p shared_prefs
adb shell "run-as $PKG cp /data/local/tmp/debug_server.xml shared_prefs/debug_server.xml"
adb shell run-as $PKG cat shared_prefs/debug_server.xml

echo "== 2.5 注入 guide_done=true (引导页直达主界面触发 DebugServer init) =="
printf '%s' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="guide_done" value="true" /></map>' > /tmp/app_prefs.xml
adb push /tmp/app_prefs.xml /data/local/tmp/app_prefs.xml
adb shell "run-as $PKG cp /data/local/tmp/app_prefs.xml shared_prefs/app_prefs.xml"
adb shell run-as $PKG cat shared_prefs/app_prefs.xml

echo "== 3. 启动 App (触发 DebugServer init) =="
adb logcat -c
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.GuideActivity
adb forward tcp:8765 tcp:8765
# 就绪探测改为真实 HTTP 请求: 软模拟冷启动 DebugServer init 可达 60s+,
# 纯 TCP connect 只能验证 adb forward 宿主侧端口(假阳性), 需等 /v1/state 200
echo "== 3.5 等待 DebugServer 真实就绪 (HTTP 200, 最长 300s) =="
TOKEN="droid-nightly-$GITHUB_RUN_ID"
READY=0
for i in $(seq 1 300); do
  python3 -c 'import sys,urllib.request as u; u.urlopen(u.Request("http://127.0.0.1:8765/v1/state", headers={"X-Auth-Token": sys.argv[1]}), timeout=3)' "$TOKEN" 2>/dev/null && { READY=1; echo "DebugServer 就绪 (${i}s)"; break; }
  sleep 1
done
if [ "$READY" != "1" ]; then echo "::error::DebugServer 300s 内未就绪"; adb logcat -d -s Nyral | tail -30; exit 1; fi
echo "== 3.6 等待系统组件稳定 (软模拟易崩, 避免启动高峰撞测试) =="
sleep 10

echo "== 4. 靶场回归 T1-T6 (决策③) =="
mkdir -p build
python3 app/src/test/python/range_regression.py --emulator || {
  adb logcat -d -v time > build/range_logcat.txt 2>&1; tail -500 build/range_logcat.txt
  if [ "${NYRAL_RANGE_MODE:-strict}" = "observe" ]; then
    echo "::warning::range 回归在观测模式下失败, 不阻断夜间构建"
  else
    exit 1
  fi
}

echo "== 5. 性能采样 冷启动/内存/DebugServer延迟 (决策②) =="
mkdir -p build
python3 app/src/test/python/nightly_perf.py --emulator --json build/nightly-perf.json
cat build/nightly-perf.json
echo '```json' >> "$GITHUB_STEP_SUMMARY"
cat build/nightly-perf.json >> "$GITHUB_STEP_SUMMARY"
echo '```' >> "$GITHUB_STEP_SUMMARY"
