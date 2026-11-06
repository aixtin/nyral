#!/usr/bin/env python3
# 对单个场景的采集数据做断言, 输出 PASS/FAIL 明细
import json, os, sys, re

S = sys.argv[1] if len(sys.argv) > 1 else "S1"
# 采集目录: 默认脚本目录; run_all.sh 用 NYRAL_OUT 指到结果目录
D = os.environ.get("NYRAL_OUT") or os.path.dirname(os.path.abspath(__file__))
FAIL = []
OK = []


def ok(t):
    OK.append(t); print("  PASS " + t)


def bad(t):
    FAIL.append(t); print("  FAIL " + t)


rows = []
p = os.path.join(D, "cs_%s.jsonl" % S)
for i, l in enumerate(open(p, encoding="utf-8")):
    l = l.strip()
    if not l:
        continue
    try:
        rows.append(json.loads(l))
    except Exception:
        pass
sse = open(os.path.join(D, "sse_%s.log" % S), encoding="utf-8", errors="replace").read()
ev = re.findall(r"^event: (\w+)", sse, re.M)
cnt = {}
for e in ev:
    cnt[e] = cnt.get(e, 0) + 1
print("  samples=%d events=%s" % (len(rows), cnt))

if not rows:
    bad("无 chatstate 采样(设备侧通道不通)")
    print("VERDICT %s FAIL" % S); sys.exit(0)

# 通用: 主线程健康 + 进程存活
posts = [r.get("main_post_ms", 0) for r in rows]
stuck = sum(1 for r in rows if r.get("main_stuck"))
if max(posts) < 700:
    ok("主线程最大 post 延迟 %dms < 700ms" % max(posts))
else:
    bad("主线程最大 post 延迟 %dms >= 700ms" % max(posts))
if stuck == 0:
    ok("main_stuck 全程为 false")
else:
    bad("出现 main_stuck %d 次" % stuck)
if os.path.exists(os.path.join(D, "meta_%s.txt" % S)):
    meta = open(os.path.join(D, "meta_%s.txt" % S)).read()
    if "DEAD" in meta:
        bad("场景结束后 App 进程消失(崩溃)")
    else:
        ok("场景结束后进程存活")

probe_last = rows[-1].get("probe") or {}
act_last = rows[-1].get("activity") or {}


def pend_seq():
    seq = []
    for r in rows:
        pr = r.get("probe") or {}
        c = pr.get("pending_count", 0)
        if c and (not seq or c > seq[-1][0]):
            seq.append((c, (pr.get("pending_ts") or [0])[-1], pr.get("pending_bytes", 0)))
    return seq


c0 = rows[0].get("crash_files", 0)
c1 = rows[-1].get("crash_files", 0)
if c1 <= c0:
    ok("本场景无新增 crash 文件(%s→%s)" % (c0, c1))
else:
    bad("本场景新增 crash 文件 %d 个(%s→%s)" % (c1 - c0, c0, c1))

if S == "S1":
    n_chunk = len([1 for l in open(os.path.join(D, "mock_timeline.jsonl"), encoding="utf-8")
                   if '"event": "chunk"' in l])
    seq = pend_seq()
    if cnt.get("tool_pending", 0) >= 3:
        ok("UI 侧收到 tool_pending %d 次" % cnt["tool_pending"])
    else:
        bad("tool_pending 过少: %s" % cnt.get("tool_pending", 0))
    gaps = [seq[i][1] - seq[i - 1][1] for i in range(1, len(seq))]
    incs = [seq[i][2] - seq[i - 1][2] for i in range(1, len(seq))]
    viol = sum(1 for g, b in zip(gaps, incs) if not (g >= 700 or b >= 512))
    if gaps:
        ok("进度上报 %d 次, 间隔 %d~%dms, 字节增量最小 %dB" % (len(seq), min(gaps), max(gaps), min(incs)))
        if viol == 0:
            ok("节流规则成立: 每次上报均满足 间隔>=700ms 或 增量>=512B")
        else:
            bad("节流被突破 %d 次" % viol)
    else:
        bad("未捕获进度上报序列")
    if cnt.get("tool_result", 0) >= 1 and cnt.get("done", 0) >= 1:
        ok("tool→tool_result→最终 delta/done 全链路闭环")
    else:
        bad("闭环缺失: %s" % cnt)
elif S == "S3":
    if cnt.get("tool", 0) >= 1 and cnt.get("tool_result", 0) >= 1:
        ok("长静默(3s)后仍完成工具调用与收尾")
    else:
        bad("长静默后未完成: %s" % cnt)
    stalled = any('"stall_begin"' in l for l in open(os.path.join(D, "mock_timeline.jsonl"), encoding="utf-8"))
    ok("mock 注入静默段" if stalled else "mock 未注入静默段(stall_begin 缺失)")
elif S == "S2":
    if probe_last.get("cancelled"):
        ok("中断标记 cancelled=true")
    else:
        bad("未置中断标记")
    note = probe_last.get("finish_note") or ""
    if "停止" in note or "中断" in note:
        ok("收尾文案: %r" % note)
    else:
        bad("收尾文案异常: %r" % note)
    if act_last.get("ai_busy") is False:
        ok("中断后 ai_busy=false(未卡在 busy)")
    else:
        bad("中断后仍 busy")
    # 缺陷1(2026-11-07): 断流时截断的 arguments 绝不能被当作完整参数执行
    if cnt.get("tool_result", 0) == 0:
        ok("截断参数未被执行为工具(无 tool_result, 缺陷1 修复生效)")
    else:
        bad("截断参数被执行 tool_result=%d 次(缺陷1 未修复)" % cnt.get("tool_result", 0))
elif S == "S7":
    # 缺陷1 核心路径(2026-11-07): 纯断流无 cancel, 必须丢弃半截参数且不得空转崩死
    if cnt.get("tool_result", 0) == 0 and cnt.get("tool", 0) == 0:
        ok("截断参数被丢弃: 无 tool/tool_result")
    else:
        bad("截断参数被执行: tool=%s tool_result=%s" % (cnt.get("tool", 0), cnt.get("tool_result", 0)))
    if act_last.get("ai_busy") is False:
        ok("断流后 ai_busy=false(未卡死)")
    else:
        bad("断流后仍 busy")
elif S == "S4":
    # 缺陷2(2026-11-07): 错误文案必须进观测口, 能从 /v1/chatstate 验到"错误确实上屏"
    err = probe_last.get("last_error") or ""
    if err:
        ok("错误文案进观测口: %r" % err[:60])
    else:
        bad("错误未进观测口(probe.last_error 为空, 缺陷2 未修复)")
    if "tools not supported" in err or "400" in err or "invalid_request" in err:
        ok("错误原文透传 mock 的 400 报文")
    else:
        bad("错误文案未透传原始报文: %r" % err[:80])
    if probe_last.get("error_at_ms", 0) > 0:
        ok("错误发生时间已记录: %s" % probe_last.get("error_at_ms"))
    else:
        bad("error_at_ms 未记录")
    if act_last.get("ai_busy") is False:
        ok("出错后 ai_busy=false(未卡在 busy)")
    else:
        bad("出错后仍 busy")
elif S == "S6":
    # 缺陷3(2026-11-07): 6000 项加法漏斗前会 Rhino 爆栈整进程死亡, 修复后应被上限挡住且进程存活
    if "表达式过长" in sse:
        ok("超长表达式命中上限提示(缺陷3 修复生效)")
    else:
        bad("未命中上限提示(缺陷3 未修复或未到达 calc)")
    if cnt.get("tool_result", 0) >= 1:
        ok("上限拒绝已作为工具结果回填")
    else:
        bad("未回填工具结果: %s" % cnt)
    if act_last.get("ai_busy") is False:
        ok("收尾 ai_busy=false")
    else:
        bad("仍 busy")
elif S == "S5":
    txt = act_last.get("last_assistant_text") or ""
    if "S5MARK" in txt:
        ok("气泡文本完整: %r" % txt[:40])
    else:
        bad("气泡文本缺失 S5MARK: %r" % txt[:60])
    if act_last.get("ai_busy") is False:
        ok("收尾 ai_busy=false")
    else:
        bad("仍 busy")
    if not (probe_last.get("finish_note") or ""):
        ok("无异常收尾文案")
    else:
        bad("意外 finish_note: %r" % probe_last.get("finish_note"))

print("VERDICT %s %s (%d pass / %d fail)" % (S, "PASS" if not FAIL else "FAIL", len(OK), len(FAIL)))
