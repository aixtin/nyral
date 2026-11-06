#!/usr/bin/env bash
# Nyral 真机流式/工具链回归 runner（脱敏版，可入库）
#
# 作用: 经 DebugServer(/v1/chat) 注入消息, 由 mock provider 按剧本回放 SSE,
#       覆盖断流截断参数、中途取消、长静默、4xx 降级、正常工具调用等场景,
#       逐场景落 chatstate 采样 + SSE 原文 + 断言报告。
#
# 依赖: adb（真机已开 USB 调试, debug 包已装并开启 DebugServer）、python3、curl
#
# 用法:
#   ./run_all.sh                 # 跑默认场景序列
#   ./run_all.sh S7 S1           # 只跑指定场景
#
# 必填环境变量:
#   NYRAL_DEVICE  adb 设备序列号（adb devices 里的那一列）
# 可选环境变量:
#   NYRAL_TOKEN   DebugServer token; 不填则尝试从设备 shared_prefs 自动读取（需 root 或 debuggable 包）
#   NYRAL_PKG     App 包名, 默认 io.github.aixtin.nyral
#   NYRAL_BRIDGE  本机 DebugServer 转发端口, 默认 18765
#   NYRAL_MOCK    mock provider 端口, 默认 18080
#   NYRAL_OUT     结果目录, 默认 ./results-<时间戳>
#   NYRAL_NO_MOCK 设为 1 时不自动拉起 mock provider（复用已在跑的那个）
#
# 场景顺序约束（重要）:
#   S4（4xx 触发 tools 降级）必须放在最后, 否则降级状态会污染后续场景的 tools 计数。
#   默认序列已按此编排: S5 S1 S3 S2 S6 S7 S4
set -u

DIR="$(cd "$(dirname "$0")" && pwd)"
PKG="${NYRAL_PKG:-io.github.aixtin.nyral}"
BRIDGE="${NYRAL_BRIDGE:-18765}"
MOCK_PORT="${NYRAL_MOCK:-18080}"
DEV="${NYRAL_DEVICE:-}"
H="http://127.0.0.1:${BRIDGE}"
MOCK="http://127.0.0.1:${MOCK_PORT}"

if [ -z "$DEV" ]; then
  echo "错误: 未设置 NYRAL_DEVICE（adb devices 中的设备序列号）" >&2
  exit 2
fi
if ! adb devices | awk 'NR>1 && $2=="device"{print $1}' | grep -qx "$DEV"; then
  echo "错误: 设备 $DEV 不在线" >&2
  exit 2
fi

# token: 优先环境变量, 其次从设备 shared_prefs 读取
if [ -z "${NYRAL_TOKEN:-}" ]; then
  NYRAL_TOKEN=$(adb -s "$DEV" shell "su -c 'cat /data/data/${PKG}/shared_prefs/debug_server.xml'" 2>/dev/null \
    | tr -d '\r' | grep -o 'name="token">[^<]*' | sed 's/.*>//')
fi
if [ -z "${NYRAL_TOKEN:-}" ]; then
  NYRAL_TOKEN=$(adb -s "$DEV" shell "run-as ${PKG} cat /data/data/${PKG}/shared_prefs/debug_server.xml" 2>/dev/null \
    | tr -d '\r' | grep -o 'name="token">[^<]*' | sed 's/.*>//')
fi
if [ -z "${NYRAL_TOKEN:-}" ]; then
  echo "错误: 无法获取 DebugServer token, 请设置 NYRAL_TOKEN" >&2
  exit 2
fi
T="$NYRAL_TOKEN"

STAMP=$(date +%Y%m%d-%H%M%S)
OUT="${NYRAL_OUT:-$DIR/results-$STAMP}"
mkdir -p "$OUT"
RESULTS="$OUT"

# 1) 端口转发: 本机 -> 设备 DebugServer
adb -s "$DEV" forward "tcp:${BRIDGE}" tcp:8765 >/dev/null || exit 3
# 2) 反向转发: 设备内 127.0.0.1:mock 端口 -> 本机 mock provider
adb -s "$DEV" reverse "tcp:${MOCK_PORT}" "tcp:${MOCK_PORT}" >/dev/null || exit 3

# 3) 冷启动 App: tools 降级状态是"进程内"状态, 跨轮次不重启进程会让上一轮的 4xx 降级残留,
#    表现为此后所有场景 tools=0、tool_calls 只进 pending 不执行。基线必须干净。
if [ "${NYRAL_NO_COLDSTART:-0}" != "1" ]; then
  adb -s "$DEV" shell am force-stop "$PKG" >/dev/null 2>&1
  adb -s "$DEV" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  for _ in $(seq 1 24); do
    curl -s -m 2 -H "X-Auth-Token: $T" "$H/v1/chatstate" >/dev/null && break
    sleep 0.5
  done
fi

# 4) mock provider
MOCK_PID=""
if [ "${NYRAL_NO_MOCK:-0}" != "1" ]; then
  # 端口被占时绝不"看起来成功"地复用别人的 mock: 会导致剧本/时间线与本次结果目录错位
  if curl -s -m 2 -o /dev/null "$MOCK/"; then
    echo "错误: 端口 ${MOCK_PORT} 已被占用（复用在跑的 mock 请设 NYRAL_NO_MOCK=1）" >&2
    exit 3
  fi
  # 注意: 必须整块进 RESULTS 再后台启动, 否则 `cd X && cmd &` 的 & 只作用于 cd, echo 会落在原 cwd
  (
    cd "$RESULTS" || exit 1
    MOCK_PORT="$MOCK_PORT" MOCK_OUT_DIR="$RESULTS" \
      nohup python3 "$DIR/mock_provider.py" > mock.out 2>&1 &
    echo $! > mock.pid
  )
  MOCK_PID=$(cat "$RESULTS/mock.pid" 2>/dev/null)
  # 该机 Python 启动+bind 实测需 1~3s, 固定 sleep 1 会误判失败: 改为轮询
  for _ in $(seq 1 20); do
    curl -s -m 1 -o /dev/null "$MOCK/" && break
    sleep 0.5
  done
  if [ -z "$MOCK_PID" ] || ! kill -0 "$MOCK_PID" 2>/dev/null || ! curl -s -m 3 -o /dev/null "$MOCK/"; then
    echo "错误: mock provider 启动失败（见 $RESULTS/mock.out）" >&2
    sed -n '1,12p' "$RESULTS/mock.out" >&2 2>/dev/null
    exit 3
  fi
fi
cleanup() {
  [ -n "$MOCK_PID" ] && kill "$MOCK_PID" 2>/dev/null
  adb -s "$DEV" forward --remove "tcp:${BRIDGE}" >/dev/null 2>&1
  adb -s "$DEV" reverse --remove "tcp:${MOCK_PORT}" >/dev/null 2>&1
}
trap cleanup EXIT

SCENES="${*:-S5 S1 S3 S2 S6 S7 S4}"
cd "$RESULTS" || exit 1
: > report.txt

pid0=$(adb -s "$DEV" shell pidof "$PKG" | tr -d '\r')
echo "device pid before: ${pid0:-none}" | tee -a report.txt

busy() { curl -s -m 3 -H "X-Auth-Token: $T" "$H/v1/chatstate" | grep -o '"ai_busy":[a-z]*' | head -1; }

FAIL=0
for S in $SCENES; do
  echo "=== 场景 $S ===" | tee -a report.txt
  cs="cs_${S}.jsonl"; sse="sse_${S}.log"; meta="meta_${S}.txt"
  : > "$cs"; : > "$sse"; : > "$meta"

  # 注入前等设备 idle, 最长 15s（否则拿到的 chatstate 是上一次残留）
  for _ in $(seq 1 30); do
    [ "$(busy)" = '"ai_busy":false' ] && break
    sleep 0.5
  done
  echo "idle_state=$(busy)" >> "$meta"
  curl -s -m 3 -X POST -H "Content-Type: application/json" -d "{\"scene\":\"$S\"}" "$MOCK/__scene" >/dev/null

  # 持续采样
  ( while true; do
      curl -s -m 2 -H "X-Auth-Token: $T" "$H/v1/chatstate" >> "$cs" 2>/dev/null
      echo >> "$cs"
      sleep 0.15
    done ) & POLL=$!

  RUNAT=$(date +%s)
  # S2 需在流中途触发取消
  if [ "$S" = "S2" ]; then
    ( sleep 1.6; curl -s -m 3 -X POST -H "X-Auth-Token: $T" "$H/v1/cancel" > "cancel_${S}.json" ) &
  fi
  timeout 40 curl -sN -X POST -H "X-Auth-Token: $T" -H "Content-Type: application/json" \
    -d "{\"message\":\"[$S] 自动化回归 $STAMP\"}" "$H/v1/chat" > "$sse" 2>&1
  sleep 2   # 收口观察窗: 注入后继续采样, 否则读到的是请求前状态
  kill $POLL 2>/dev/null; wait $POLL 2>/dev/null

  echo "elapsed=$(( $(date +%s) - RUNAT ))s" >> "$meta"
  echo "pid_after=$(adb -s "$DEV" shell pidof "$PKG" | tr -d '\r')" >> "$meta"
  NYRAL_OUT="$RESULTS" python3 "$DIR/analyze.py" "$S" >> report.txt 2>&1 || FAIL=1
done

echo "--- crash 文件:" | tee -a report.txt
adb -s "$DEV" shell run-as "$PKG" ls -l files/crash 2>/dev/null | tee -a report.txt

echo "结果目录: $RESULTS"
grep -E "^VERDICT " report.txt
exit $FAIL
