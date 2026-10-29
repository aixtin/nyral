# MCP：接入外部工具，并把手机能力暴露给桌面

Nyral 在 MCP 上是双向的：

- **客户端**：把外部 MCP 服务的工具挂进对话，模型可直接调用（设置 → MCP 服务）
- **服务端**：把手机自身暴露为一个标准 MCP Server，桌面端 Agent（Claude Desktop / Cursor / 其它支持 Streamable HTTP 的客户端）可反过来查询手机状态、检索手机本地记忆、读取当前界面截图

没有云端中转，没有账号，配置与调用全部在本机完成。

---

## 一、客户端：接入外部 MCP 服务

### 入口

设置 → MCP 服务 → 添加 MCP 服务

字段三样：名称、服务器地址、Token（可空，服务端要求鉴权时才填）。

### 协议细节

| 项 | 值 |
| --- | --- |
| 传输 | Streamable HTTP（同时兼容 SSE 响应） |
| protocolVersion | `2025-06-18` |
| 调用链路 | `initialize` → `tools/list` → `tools/call` |
| 鉴权头 | `Authorization: Bearer <token>` |
| 存储 | Token 经 AndroidKeyStore AES/GCM 加密后落盘（`mcp_configs.xml`，密钥不出 TEE/StrongBox，其他应用不可读） |

配置页提供「测试」按钮：保存前先握手 + 拉一次工具清单，成功会提示「连接成功，共 N 个工具: a, b, c」，避免填错地址后进对话才发现不可用。

编辑已有服务时，弹窗底部提供「删除此服务」，需二次确认；删除后该服务的全部工具立即从后续对话中移除。

### 预置服务（点条目自动填地址）

| 服务 | 地址 | 是否需要 Token | 适合什么 |
| --- | --- | --- | --- |
| DeepWiki | `https://mcp.deepwiki.com/mcp` | 否 | 直接问答 GitHub 仓库的代码与文档（该域名仅 IPv6 解析，IPv4-only 网络连不上） |
| Context7 | `https://mcp.context7.com/mcp` | 否 | 查第三方库/框架的最新文档与用法 |
| Microsoft Learn | `https://learn.microsoft.com/api/mcp` | 否 | 微软官方技术文档 |
| GitHub | `https://api.githubcopilot.com/mcp/` | 是（PAT） | 仓库、Issue、PR 操作 |

预置只是省掉手抄地址，不绑定、不上报，能不能连取决于你当前的网络。

### 工具是怎么进入对话的

不是把所有 MCP 工具一次性塞进提示词（那样 token 会爆）。Nyral 的策略是：

1. 常驻前 `MCP_TOP_N`（默认 4）个工具直接暴露给模型；
2. 其余工具只给索引（名称 + 一行说明），模型需要时用内置的 `tool_detail` 工具按需展开完整参数再调用。

所以接入一个工具很多的 MCP 服务，也不会一上来就被撑爆上下文。

### 示例：接一个自建的本地服务

```
名称: local-fs
地址: http://127.0.0.1:8787/mcp
Token: 留空
```

只要它说的是 MCP Streamable HTTP，Nyral 不关心你用什么语言写的（Python / Node / Go 都行）。

---

## 二、服务端：把手机变成 MCP Server

### 打开方式

设置 → 调试服务（Debug Server）→ 开启，记下页面上的 **Token**。

服务监听 `8765` 端口，MCP 端点为：

```
http://<手机在局域网内的 IP>:8765/mcp
```

同一 Wi-Fi 下的电脑即可访问。手机 IP 在系统 Wi-Fi 详情里能看到，形如 `192.168.1.20`。

### 暴露的工具（全部只读）

| 工具 | 作用 | 说明 |
| --- | --- | --- |
| `phone_status` | 设备与运行状态 | 只回模型名、记忆条数、工具数、会话数等白名单字段 |
| `phone_memory_search` | 检索手机本地长期记忆 | 语义 + 关键词双路检索，返回条目摘要 |
| `phone_screen` | 当前界面截图 | 返回 base64 图片，便于桌面端"看"手机在显示什么 |

`phone_status` 做了字段裁剪：SSH 主机、API Key、Token 一类的私有信息不会出现在返回值里。

### 桌面端配置

支持 Streamable HTTP + 自定义 Header 的客户端，直接填 URL 和鉴权头即可：

```json
{
  "mcpServers": {
    "nyral-phone": {
      "url": "http://192.168.1.20:8765/mcp",
      "headers": { "X-Auth-Token": "<调试服务页面上的 Token>" }
    }
  }
}
```

只支持 stdio 的客户端（例如较老版本的 Claude Desktop），用一个桥接进程把 HTTP 转成 stdio 即可，`mcp-remote` 这类工具就是干这个的，具体参数以其文档为准，鉴权头照上面传。

### 它有什么用

- 电脑上写代码，让桌面 Agent 顺手看一眼手机当前界面在报什么错（`phone_screen`）
- 在桌面端问"我手机里记过的那条部署命令是什么"（`phone_memory_search`）
- 让桌面 Agent 判断手机端当前模型 / 记忆规模，决定任务该在手机还是电脑上跑（`phone_status`）

### 安全边界

- 默认关闭，不开启就没有监听端口；
- 仅局域网可达，不做公网穿透，也不自带内网穿透；
- 强制 Token 鉴权，`Authorization: Bearer` 与 `X-Auth-Token` 都接受；
- 暴露的三个工具全部只读，不能通过 MCP 改动手机上的任何数据；
- Token 就是调试服务页面上的那个，关掉调试服务即整体失效。

---

## 三、排查

**`Unable to resolve host "xxx": No address associated with hostname`**
手机当前没网，或该域名只有 IPv6 记录而你所在网络没有 IPv6 出口，或运营商 DNS 对该域名返回空。先在手机浏览器打开同一地址确认可达，再回来点「测试」。

**`连接失败: 401` / `Unauthorized`**
服务端要求鉴权但 Token 没填或填错。Nyral 客户端以 `Authorization: Bearer <token>` 发送，这是 MCP 规范的通行方式；Token 本身别带多余空格。

**配好了，但对话里看不到工具**
新配置在下一次会话初始化时生效，重开一个会话即可。另外工具多时只有前 4 个常驻，其余需要模型主动通过 `tool_detail` 展开。

**想删掉某个服务**
设置 → MCP 服务 → 点该条目 → 「删除此服务」→ 确认。删除会同时移除它已加载的工具。

**手机作为服务端，电脑连不上**
确认同一 Wi-Fi、手机没开 AP 隔离（部分路由器的"访客网络"会隔离设备）、调试服务确实处于开启状态，并且电脑访问的是 `http://` 而不是 `https://`。
