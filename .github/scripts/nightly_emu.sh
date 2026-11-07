#!/usr/bin/env bash
# Nightly 模拟器冒烟 + 性能采样（由 nightly.yml 调用；多行逻辑必须落脚本文件，
# 因为 android-emulator-runner 的 script 会按行拆成独立 sh -c 执行）
#
# 定位说明：GitHub Hosted Runner 不提供嵌套虚拟化，模拟器以 -accel off 全软件模拟运行，
# system_server ANR 与 WebView chromium renderer 崩溃是常态，依赖页面渲染的
# 交互级断言（range T1-T6）在该环境下不可归因。因此本脚本的门禁只有一条可靠信号：
# App 自身进程是否 FATAL/崩溃。渲染级回归由真机 scripts/ui-regression/run_all.sh 承担。
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
# 就绪探测必须走真实 HTTP: 软模拟冷启动 DebugServer init 可达 60s+,
# 纯 TCP connect 只能验证 adb forward 宿主侧端口(假阳性), 需等 /v1/state 200
echo "== 3.5 等待 DebugServer 真实就绪 (HTTP 200, 最长 300s) =="
TOKEN="droid-nightly-$GITHUB_RUN_ID"
READY=0
for i in $(seq 1 300); do
  python3 -c 'import sys,urllib.request as u; u.urlopen(u.Request("http://127.0.0.1:8765/v1/state", headers={"X-Auth-Token": sys.argv[1]}), timeout=3)' "$TOKEN" 2>/dev/null && { READY=1; echo "DebugServer 就绪 (${i}s)"; break; }
  sleep 1
done
if [ "$READY" != "1" ]; then echo "::error::DebugServer 300s 内未就绪"; adb logcat -d -s Nyral | tail -30; exit 1; fi

mkdir -p build
echo "== 4. 冒烟: App 进程崩溃检查 (软模拟下唯一可归因的门禁) =="
# 启动后留观察窗口, 覆盖首屏渲染与 DebugServer 初始化
sleep 20
adb logcat -d -v time > build/range_logcat.txt 2>&1 || true
if grep -qE "FATAL EXCEPTION|Process: $PKG|ANR in $PKG" build/range_logcat.txt; then
  echo "::error::App 进程出现 FATAL/ANR"
  grep -nE -A15 "FATAL EXCEPTION|ANR in $PKG" build/range_logcat.txt | head -60
  exit 1
fi
echo "冒烟通过: 无 App 进程崩溃/ANR"

echo "== 5. 靶场回归 (默认跳过; 模拟器仅在 NYRAL_RANGE_MODE=observe 时试跑, 不入 CI 门禁) =="
RANGE_MODE="${NYRAL_RANGE_MODE:-skip}"
if [ "$RANGE_MODE" = "skip" ]; then
  echo "已跳过: 渲染级回归由真机 scripts/ui-regression/run_all.sh 承担"
else
  python3 app/src/test/python/range_regression.py --emulator || {
    tail -200 build/range_logcat.txt
    echo "::warning::range 观测失败(模拟器软渲染环境性), 不阻断夜间构建"
  }
fi

echo "== 6. 性能采样 冷启动/内存/DebugServer延迟 (观测项, 失败不阻断) =="
if python3 app/src/test/python/nightly_perf.py --emulator --json build/nightly-perf.json; then
  cat build/nightly-perf.json
  echo '```json' >> "$GITHUB_STEP_SUMMARY"
  cat build/nightly-perf.json >> "$GITHUB_STEP_SUMMARY"
  echo '```' >> "$GITHUB_STEP_SUMMARY"
else
  echo "::warning::性能采样失败(软模拟下冷启动采样常不可得), 不阻断"
fi
