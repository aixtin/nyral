# 真机流式 / 工具链回归（ui-regression）

经 DebugServer 注入消息 + mock provider 按剧本回放 SSE，对 Nyral 的流式解析、工具调用、
断流与降级行为做端到端断言。纯本地链路，不依赖真实模型服务。

## 前置条件

- 真机开启 USB 调试，`adb devices` 可见（建议 `adb -s <serial> wait-for-device`）
- 已安装 **debug 包**，且设置里开启 DebugServer（监听 8765）
- App 当前 provider 指向 `http://127.0.0.1:18080`（脚本用 `adb reverse` 把该端口打回本机 mock）
- 本机具备 `adb` / `python3` / `curl`

## 运行

```bash
export NYRAL_DEVICE=<adb 序列号>      # 必填
# export NYRAL_TOKEN=<token>          # 可选, 默认自动从设备 shared_prefs 读取
./run_all.sh                          # 默认序列 S5 S1 S3 S2 S6 S7 S4
./run_all.sh S7                       # 只跑单个场景
```

产物全部落在 `results-<时间戳>/`：`cs_S*.jsonl`（chatstate 采样）、`sse_S*.log`（SSE 原文）、
`meta_S*.txt`、`mock_timeline.jsonl`（mock 侧时间线）、`report.txt`（断言明细）。
每个场景末行输出 `VERDICT S<N> PASS|FAIL (n pass / m fail)`，任一 FAIL 时脚本退出码非 0。

## 场景与断言

| 场景 | 剧本 | 关键断言 |
|------|------|----------|
| S1 | 长参数分片下发（12000+ 字节），逐片 flush | `tool_pending` ≥3 次；节流成立（间隔 ≥700ms 或增量 ≥512B）；tool→tool_result→done 闭环 |
| S2 | 流中途 `/v1/cancel` | `cancelled=true`、收尾文案含"停止/中断"、`ai_busy=false`；**截断参数不得被执行**（无 tool_result） |
| S3 | 流中途静默 3s | 静默后仍完成工具调用与收尾；mock 时间线含 `stall_begin` |
| S4 | provider 返回 400（tools not supported） | 错误文案进 `/v1/chatstate` 观测口（`last_error`）、透传原始报文、`error_at_ms` 有值、`ai_busy=false` |
| S5 | 正常文本流 | 气泡文本完整（含 `S5MARK`）、无异常 `finish_note` |
| S6 | calc 超长表达式（6000 项加法） | 命中"表达式过长"上限提示、拒绝作为工具结果回填、进程存活 |
| S7 | 纯断流（无 cancel，参数在半截处中断） | **无 tool / tool_result**（半截参数被丢弃）、`ai_busy=false` 不卡死 |

每个场景额外做一条通用断言：本场景不新增 `files/crash` 文件。

## 顺序约束（必须遵守）

- **S4 必须放最后**：它会让 provider 进入"不支持 tools"降级状态，污染后续场景的
  `tool` 计数（曾导致 S6 首轮误判为 FAIL）。
- S2 / S7 依赖前一个场景干净收尾，切换时脚本会先等 `ai_busy=false`（最长 15s）。

## 敏感信息处理

本目录所有文件不含设备序列号、DebugServer token、内网地址或机器路径：

- 设备序列号走 `NYRAL_DEVICE`
- token 走 `NYRAL_TOKEN`，或由脚本从设备 `shared_prefs` 读取
- 端口、包名、输出目录全部有默认值且可覆盖

请勿把 `results-*`、`requests.jsonl`、`mock_timeline.jsonl` 提交进仓库（`.gitignore` 已忽略）。
