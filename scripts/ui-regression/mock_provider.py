#!/usr/bin/env python3
# Nyral mock provider v4
# 关键修正(实测发现):
#  1) App 会把记忆检索决策器/标题生成等**内部辅助请求**也打到当前 provider,
#     这些请求 tools=[] 且 system 提示可见 → 必须回纯文本, 否则会被误判成剧本触发 tool_calls。
#  2) App 的 user content 是"当前消息+历史 transcript"混合且会被截断,
#     靠消息内标记判定场景不可靠 → 改为控制端点 POST /__scene 强制指定, 标记只做兜底。
# 场景: S1 长参数逐片 / S2 中途断流 / S3 长静默 / S4 错误码 / S5 正常
import json, os, time, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("MOCK_PORT", "18080"))
BASE = os.path.dirname(os.path.abspath(__file__))
# 采集日志目录: 默认脚本目录; run_all.sh 用 MOCK_OUT_DIR 指到结果目录, 避免把运行产物写进仓库
OUTDIR = os.environ.get("MOCK_OUT_DIR") or BASE
LOG = os.path.join(OUTDIR, "requests.jsonl")
TL = os.path.join(OUTDIR, "mock_timeline.jsonl")
STATE = {"scene": "AUTO"}
POST_TOOL_HINT = "已执行上述工具调用并返回结果"
AUX_HINTS = ("记忆检索决策器", "生成标题", "会话标题", "关键词")
lock = threading.Lock()


def rec(obj, path=LOG):
    try:
        with lock, open(path, "a", encoding="utf-8") as f:
            f.write(json.dumps(obj, ensure_ascii=False) + "\n")
            f.flush()
    except Exception:
        pass


def sse(obj):
    return ("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode()


def chunk(content=None, tool_calls=None, finish=None):
    d = {}
    if content is not None:
        d["content"] = content
    if tool_calls is not None:
        d["tool_calls"] = tool_calls
    return {"choices": [{"index": 0, "delta": d, "finish_reason": finish}]}


def text_of(m):
    c = (m or {}).get("content")
    if isinstance(c, list):
        return " ".join(str(p.get("text", "")) for p in c if isinstance(p, dict))
    return c or ""


def scene_from_messages(msgs):
    users = [m for m in msgs if (m or {}).get("role") == "user"]
    last = text_of(users[-1]) if users else ""
    tail = last[-400:]
    best, pos = None, -1
    for s in ("S1", "S2", "S3", "S4", "S5", "S6", "S7"):
        p = tail.rfind("[%s]" % s)
        if p > pos:
            pos, best = p, s
    return best


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _json(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _stream(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()

    def do_GET(self):
        self._json(200, {"ok": True, "port": PORT, "scene": STATE["scene"]})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except Exception:
            body = {}
        if self.path == "/__scene":
            STATE["scene"] = str(body.get("scene") or "AUTO").upper()
            rec({"ts": time.time(), "event": "set_scene", "scene": STATE["scene"]}, TL)
            return self._json(200, {"ok": True, "scene": STATE["scene"]})
        msgs = body.get("messages") or []
        tools = body.get("tools") or []
        sys_txt = text_of(msgs[0]) if msgs and msgs[0].get("role") == "system" else ""
        is_aux = (not tools) and any(h in sys_txt for h in AUX_HINTS)
        users = [m for m in msgs if (m or {}).get("role") == "user"]
        prev = text_of(users[-1])[-600:] if users else ""
        # 工具轮续请求的 user content 只有那句提示; 正常轮末尾带 [Sx] 标记 → 用它区分, 避免历史误判
        tail200 = prev[-200:]
        post_tool = (POST_TOOL_HINT in tail200) and ("[S" not in tail200)
        forced = STATE["scene"]
        scene = forced if forced != "AUTO" else (scene_from_messages(msgs) or "S5")
        rec({"ts": time.time(), "scene": scene, "aux": is_aux, "post_tool": post_tool,
             "n_messages": len(msgs), "n_tools": len(tools), "stream": bool(body.get("stream")),
             "task_id": self.headers.get("X-Nyral-Task-ID")})
        if not self.path.endswith("/chat/completions"):
            return self._json(404, {"error": "not found"})
        if is_aux:
            self._stream()
            txt = "NO" if "记忆检索决策器" in sys_txt else "Nyral 测试会话"
            self.wfile.write(sse(chunk(content=txt)))
            self.wfile.write(sse(chunk(finish="stop"))); self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush(); return
        if scene == "S4":
            return self._json(400, {"error": {"message": "tools not supported by this model",
                                              "type": "invalid_request_error"}})
        self._stream()
        try:
            if post_tool:
                self._text("%sMARK 参数已解析, 结果为 1。" % scene)
            elif scene == "S1":
                self._tool_calls(piece=480, gap=0.12)
            elif scene == "S2":
                self._tool_calls(piece=480, gap=0.12, cutoff=int(12013 * 0.35))
                rec({"ts": time.time(), "event": "abort_stream"}, TL)
                self.wfile.flush(); self.close_connection = True
            elif scene == "S3":
                self._tool_calls(piece=480, gap=0.12, stall_at=4800, stall_sec=3.0)
            elif scene == "S6":
                # 缺陷3(2026-11-07): 6000 项加法(12001 字符)。修复前 Rhino IRFactory 递归建树爆栈、整进程死亡;
                # 修复后应被 calc 上限拦住并返回提示文案, 进程存活
                self._tool_calls(piece=480, gap=0.05,
                                 args=json.dumps({"expr": "1+" * 6000 + "1"}, ensure_ascii=False))
            elif scene == "S7":
                # 缺陷1 核心路径: 纯断流(不发 finish_reason 也不发 [DONE]), 且不触发用户取消
                # 修复后必须走"丢弃半截参数"分支: 无 tool 执行 + 有"流未完整结束"警告日志
                self._tool_calls(piece=480, gap=0.12, cutoff=int(12013 * 0.35))
                rec({"ts": time.time(), "event": "abort_stream"}, TL)
                self.wfile.flush(); self.close_connection = True
            else:
                self._text("S5MARK 这是 mock 正常回复。")
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

    def _text(self, txt):
        step = max(1, len(txt) // 4)
        for i in range(0, len(txt), step):
            self.wfile.write(sse(chunk(content=txt[i:i + step]))); self.wfile.flush()
            time.sleep(0.05)
        self.wfile.write(sse(chunk(finish="stop"))); self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def _tool_calls(self, piece=480, gap=0.12, cutoff=None, stall_at=None, stall_sec=0.0, args=None):
        # 默认 1 + 12000 空格: 正则允许空格, Rhino 解析无递归 → 参数长但执行瞬时
        if args is None:
            args = json.dumps({"expr": "1" + " " * 12000}, ensure_ascii=False)
        total = len(args); sent = 0; first = True; idx = 0; t0 = time.time()
        while sent < total:
            if cutoff is not None and sent >= cutoff:
                break
            take = min(piece, total - sent)
            if cutoff is not None:
                take = min(take, cutoff - sent)
            frag = args[sent:sent + take]
            if first:
                tc = [{"index": 0, "id": "call_mock", "type": "function",
                       "function": {"name": "calc", "arguments": frag}}]
                first = False
            else:
                tc = [{"index": 0, "function": {"arguments": frag}}]
            self.wfile.write(sse(chunk(tool_calls=tc))); self.wfile.flush()
            sent += take; idx += 1
            rec({"ts": time.time(), "event": "chunk", "seq": idx, "bytes": sent,
                 "total": total, "pct": round(sent * 100.0 / total, 1)}, TL)
            if stall_at is not None and sent >= stall_at and stall_sec > 0:
                rec({"ts": time.time(), "event": "stall_begin", "bytes": sent}, TL)
                time.sleep(stall_sec)
                rec({"ts": time.time(), "event": "stall_end", "bytes": sent}, TL)
                stall_at = None
            time.sleep(gap)
        rec({"ts": time.time(), "event": "tool_calls_done", "bytes": sent, "total": total,
             "chunks": idx, "elapsed": round(time.time() - t0, 3)}, TL)
        if cutoff is None:
            self.wfile.write(sse(chunk(finish="tool_calls")))
            self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush()


if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
    print("mock v4 on 127.0.0.1:%d" % PORT, flush=True)
    srv.serve_forever()
