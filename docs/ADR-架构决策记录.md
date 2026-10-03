# Nyral 架构决策记录（ADR）

> 本文件记录 Nyral 开发过程中的关键架构决策（Architecture Decision Records）：每个决策的**背景 → 方案 → 取舍 → 结论**。信息源自项目过程记录，已做脱敏处理，不含任何个人凭据、内网拓扑或私有地址。
>
> 状态约定：`已采纳` / `已废弃` / `已回退`

---

## ADR-001 引擎技术路线：借鉴开源 + 自写核心

- **状态**：已采纳
- **日期**：2026-08-24（立项）

### 背景
立项时对标了三个同类开源项目：其一功能最强但体积重，其二以速度见长，其三需 Root+LSPosed 且被判定价值低。直接套用任一项目都存在"全家桶"问题——工具被固定注入，浪费 token。

### 方案
- **借鉴**：UI/工具链/会话管理的成熟思路
- **自写核心**：工具注册表 + 路由（tool_choice）+ 上下文管理
- **核心卖点**：省 token —— 工具按需注入，不全家桶

### 结论
引擎层完全自研，对外部项目仅吸收架构思想，避免继承历史包袱与许可限制。

---

## ADR-002 推理路线：放弃本地 GGUF，统一走云端 OpenAI 兼容 API

- **状态**：已采纳（原本地推理方案已废弃）
- **日期**：2026-08-25（尝试本地）→ 2026-08-27（放弃）

### 背景
早期尝试本地 GGUF 推理，先后评估 llama.rn（RN TurboModule+JSI 绑定，脱离 RN 无法直接用）与"RN 壳 + 本地 HTTP 推理服务"方案，并拉起了 RN 工程。但依赖下载卡死（npm registry 缺包版本），链路复杂且收益不确定。

### 方案对比
| 维度 | 本地 GGUF | 云端 OpenAI 兼容 API |
|------|-----------|----------------------|
| 部署门槛 | 高（需 RN 壳 + 本地服务） | 低（填 Base URL + Key 即用） |
| 模型质量 | 受设备算力/内存限制 | 任选 DeepSeek/GLM/Qwen 等 |
| 数据隐私 | 全本地 | 依赖所选供应商 |
| 维护成本 | 高（模型、运行时、构建链） | 极低 |

### 结论
拍板不搞本地模型，推理全部走云端 API。Nyral 只有"端"没有"本地模型运行面"，产物进入工程仍保留但停更。

---

## ADR-003 工具调度协议：从自研文本协议迁移到原生 function calling

- **状态**：已采纳（LocalEngine v3.0）
- **日期**：2026-09-10

### 背景
旧版引擎依赖模型输出 `TOOL:/思考:/答案:` 前缀 + XML `<tool_call>` 兜底解析来识别工具。模型输出稍有偏差（代码块包裹 / JSON 包裹 / 多行 / 伪标签 / 等号属性式 XML）即解析失败——这是"输出一半停、幻觉工具、tool_calls 不一致"的总根因。

### 方案
- 全部工具（内置 + MCP 动态）注册为 OpenAI 兼容 `tools` JSON Schema，请求携带 `tools` 字段
- 模型按原生 `tool_calls` 原语返回结构化 `name + arguments`；SSE 解析 `delta.tool_calls` 增量累积
- 工具结果按 `role=tool + tool_call_id` 回填，走标准多轮迭代（`MAX_TOOL_CALLS=40`）
- **兼容兜底**：请求 4xx 提示 tools 不支持时，进程内降级为纯文本模式，并按旧文本协议兜底识别，旧/第三方模型不丢功能

### 取舍
采用标准协议换来解析鲁棒性，代价是把协议能力绑定到 OpenAI 兼容规范——通过降级兜底对冲该风险。

---

## ADR-004 长期记忆：端内自托管三级体系

- **状态**：已采纳
- **日期**：2026-08-25 起持续演进

### 背景
红线是数据自托管、不依赖第三方服务端。聊天记忆需要跨会话可用，且要控制 token 成本。

### 方案
- 向量：bge-small ONNX 端内语义向量（量化模型 + vocab 内置于 assets）
- 存储：SQLite
- 召回：**语义向量 + 关键词 LIKE 双路召回**，互为兜底
- 分层：短期滚动窗口 → 中期摘要（辅助模型后台压缩）→ 长期全量原文 + 语义检索
- 可靠性：pending 队列落盘 + 原子消费事务，进程被杀不丢消息、不重复归档
- 索引模型可独立配置"辅助 AI"，不占用主对话模型

### 结论
全部记忆资产存本机；语义检索能力不强时可被关键词路径兜住，防止"检索不到=上下文丢失"。

---

## ADR-005 单体治理：巨型 Activity 的渐进式拆分

- **状态**：已采纳
- **日期**：2026-08-30 ~ 2026-09-05

### 背景
MainActivity 膨胀到约 4200 行，单文件承载聊天主界面、气泡渲染、录音、附件、事件分发，可维护性差、上下文加载成本高。

### 方案（分步、每步保持可编译可装机）
1. 图标/解码层函数抽至 `UiKit.kt`（第一步减数百行）
2. `openAttachmentExternal` 等顶层函数外提
3. 气泡 span 类 + 波形状态抽至 `BubbleSpans.kt`
4. UI 构建区 21 个函数抽至新建 `MainUi.kt`（单步减 900+ 行）
5. 后续继续拆出 `AiBubbleHolder`（气泡渲染）、`AttachmentSender`（附件发送）等

### 结果量级
MainActivity 由约 4200 行降至约 2400 行，拆分期间每次变更均伴随单选/编译/真机验证，未引入回归。

### 经验
大文件拆分必须遵循"少量试点 → 每步可编译 → 真机验证 → 再下一步"节奏；全量重排风险远高于渐进式。

---

## ADR-006 会话持久化：退后台兜底落库 + 会话追踪

- **状态**：已采纳（v0.20）
- **日期**：2026-08-25

### 背景
杀后台重进后聊天消息消失——消息只存内存，仅切会话才落库，`onCreate` 也不加载最近会话。

### 方案
- `onStop` 退后台前兜底保存
- `currentSessionId` 追踪当前会话：更新走 `updateSession`，新建才是 `saveSession`
- `onCreate` 末尾恢复最近会话

### 关键点
同一对话反复切后台只更新原记录、不新建，杜绝重复会话。

---

## ADR-007 UI 渲染容器：RecyclerView 改造尝试与回退

- **状态**：已回退
- **日期**：2026-09-08 ~ 2026-09-09

### 背景
消息列表原为 `ScrollView + LinearLayout`，计划改为 `RecyclerView` 数据驱动以获得回收复用收益，分 5 个里程碑推进，并做好备份可回滚。

### 进展与回退
- 已完成：数据模型 + ChatAdapter 骨架、历史加载切换、新消息走 Adapter、流式 AiBubbleHolder 接入骨架
- 附带修复：XML 工具片段解析（兼容等号属性式 `<function=...>` / `<parameter=...>`）
- 后记：由于滚动/键盘/video 生命周期等连锁问题，整体回退到改造前基线（保留备份与补丁可倒车）

### 教训
动渲染主链路的改造需一次性评估键盘、滚动、流式、媒体生命周期的耦合度；推进中遇到连锁 bug 时，及时回退到基线比硬撑更稳妥（备份策略保住无损回滚）。

---

## ADR-008 语音输入链路：MediaRecorder → AudioRecord + PCM → WAV

- **状态**：已采纳（最终方案）
- **日期**：2026-08-29

### 背景
语音上传先后连续踩坑：m4a 伪容器被服务端拒绝（400），MediaRecorder 的 OGG 封装在目标设备不兼容（start failed）。

### 方案演进
1. m4a → 被拒，弃用
2. MediaRecorder + OGG → 启动失败，弃用
3. **AudioRecord 采 PCM，封装 WAV** → 兼容主流多模态 API，实机验证通过

### 结论
语音链路以"低层 API 采原始 PCM + 无压缩容错最高的 WAV 封装"落地，规避设备/服务端双端格式兼容问题。

---

## ADR-009 上下文治理：消息窗口化 + 注入截断双保险

- **状态**：已采纳
- **日期**：2026-09-02

### 背景
上下文采用恒定窗口，token 不随轮数爆炸，但存在三处软增长（记忆库无限涨、内存 messages 全量积累、单条消息超长注入），以及唯一真炸点——单轮消息极长顶爆上下文窗口。

### 方案（P0+P1 分治）
- **P1 messages 内存瘦身（窗口化加载）**：
  - DB 层新增 `countSessionMessages` / `loadSessionMessagesTail(tail)`，按 seq 倒序取最近 N 条升序返回
  - 保存窗口内消息不误删窗口外旧记录（`DELETE WHERE seq >= baseSeq`，插入从 baseSeq+i 起算）
  - 主界面 `MEM_WINDOW=150`：超限只载入最近 150 条，顶部提示"仅展示最近150条，更早可用搜索定位"；搜索结果按全局 seq 定位，窗口外命中临时全量加载一次
- **P0 单条超长注入截断（防顶爆）**：
  - `MAX_ATTACH_TEXT=40000`（单附件本地解析文本）/ `MAX_DOC_TOTAL=120000`（单次全部附件总量）/ `MAX_MSG_HISTORY=8000`（单条消息注入 history 上限）/ `MAX_OUTPUT_CHARS=60000`（引擎输出累积截断，`capOut` 统一收口 3 处累积点）
  - 超限一律截断并追加"…[已截断]"标注

### 结论
窗口化后旧消息不载入内存、靠中期摘要承担上下文；搜索可随时定位窗口外消息。阈值（150/8000/40000/120000/60000）为保守初值，可按真机体验调整。

---

## ADR-010 交互双模式：聊天 vs Agent

- **状态**：已采纳
- **日期**：2026-09-01

### 背景
需要两种不同形态的对话：沉浸式"聊天"（像 IM 一样并排头像、纯文本、无 Markdown 干扰）与"Agent"（完整工具调用 + 流式渲染）。此前单一渲染形态难以两头兼顾。

### 方案
- `ModeConfig.chatMode()` 读 `mode_config.xml` 的 `chat_mode` 布尔（默认开）
- **渲染分支**：聊天模式返回并排头像气泡（AI 左/用户右、圆形头像、正文纯文本、思考折叠小字"已思考XX字，点按展开"）；Agent 模式原样走原渲染链，**零改动零回归**
- **AI 禁 MD**：聊天模式下不剥 Markdown span，改由 LocalEngine system 提示词兜底禁止输出 Markdown
- **会话隔离**：onCreate 恢复 / maybeSaveCurrent / refreshSessionList 三处携带 mode，模式切换后 onResume 重载；两模式共享同一记忆库

### 结论
以"开关 + 渲染分支 + system 提示词兜底"实现双形态，Agent 模式渲染逻辑完全不动，规避回归风险。

---

## ADR-011 开发者调试通道：内置轻量 HTTP 调试服务

- **状态**：已采纳
- **日期**：2026-09-01

### 背景
此前迭代验证依赖 ADB 模拟点击/截图，链路繁琐且易因嵌套引号/heredoc 截断失败。需要一个不受 UI 状态影响、可编程驱动的验证通道。

### 方案
内置手写 `ServerSocket` HTTP 服务，替代 ADB 输入框折腾：
- `GET /v1/ping` 健康检查；`GET /v1/state` 会话/记忆/工具/token 状态；`GET /v1/logs` 拉运行日志；`POST /v1/mem/search` 记忆语义检索；`POST /v1/chat` SSE 流式对话（走完整 LocalEngine 链路）
- **安全底线**：默认关闭（设置页弹窗启用）；仅 debug 构建可启动；Token 鉴权（X-Auth-Token / query）；默认仅绑本机回环，开启"局域网访问"才对外；release 直接拒绝

### 关键踩坑
- HTTP body 必须按字节读（`Content-Length` 是字节数），按字符读导致 UTF-8 中文（3 字节/字）永远等不满，POST 卡死返回空
- SSE 事件用 `LinkedBlockingQueue`：引擎回调线程入队、服务线程写出

### 结论
调试通道落地后，"ADB 模拟点击"类验证全面切换为 `POST /v1/chat` SSE 驱动，大幅提升迭代验证效率；安全边界（默认关 + debug-only + Token）保证不进生产。

---

## ADR-012 过程可视化：透明悬浮迷你终端

- **状态**：已采纳（MVP）
- **日期**：2026-09-02

### 背景
切到其他应用时无法看到 AI 在后台干什么（此前曾提出"弹幕"需求，讨论后收敛为透明迷你终端）。需求：实时显示 AI 执行过程，不遮挡使用。

### 方案（MVP 范围）
- 半透明黑底白字、固定左上角、8 行、`FLAG_NOT_TOUCHABLE` 不挡触摸
- 数据源 = LocalEngine 自身事件流（thinking/tool/输出），不读其他应用内容
- 前台服务 + 常驻通知保活；设置页开关，未授权先跳悬浮窗权限引导
- 事件流由 MainActivity 内 LocalEngine.Callback 7 处事件统一转发 `AITerminal.push(...)`；服务未运行时环形缓冲保留最近 8 行，拉起后回放，避免开关瞬间丢事件

### 关键踩坑
`internal fun setServiceRunning()` 与 `var serviceRunning ... internal set` 的 setter 撞 JVM 签名（Platform declaration clash），删除自定义函数改用属性 internal set 解决。

### 结论
以"纯内存转发 + 前台服务悬浮窗"实现零侵入过程可视化；第一版只展示不交互，交互/布局调优按实测迭代。

---

## ADR-013 数据存储访问：Android 作用域存储适配

- **状态**：已采纳（方向B升级版）
- **日期**：2026-09-01

### 背景
App 内工作目录实际有 7 个文件（ADB 落盘可见），MediaStore 查询却只返回 1 个；连带凭据/Cookie 注入等依赖文件读取的功能失效。

### 排查与根因
- 初判"MediaStore 索引缺失"并用 MediaScanner 重扫，验证后放弃（文件实际已在索引中，`_id` 正常）
- **真实根因**：文件由非 App 方式（ADB push 等）落盘，`owner_package_name=NULL`；无存储权限时 MediaStore 只返回 owner=自身包名的文件（作用域存储过滤），因此查不到

### 方案（方向B升级版）
- `AndroidManifest.xml` 增加 `MANAGE_EXTERNAL_STORAGE`（所有文件访问）权限
- `WorkDir.kt` 配套调整，脱离 MediaStore 依赖

### 结论
Android 10+ 无存储权限时文件访问必须显式申请"所有文件访问"权限，不能假设 MediaStore 能查到非本应用创建的文件。

---

## ADR-014 消息流表格渲染：气泡内独立 TableView 块化（方案 C）

- **状态**：已采纳
- **日期**：2026-10-02（v2.1 落地）

### 背景
Markdown 表格在消息流内多方案渲染均不理想：Markwon 原生表格流式收尾才排版、无法实时；窄屏横向滚动体验差；气泡内嵌套渲染在双模式（聊天/Agent）下样式与单元格内容不稳定。反编译对比 DeepSeek、Kimi 等第三方客户端的表格渲染后，确定在气泡内做原生表格块。

### 方案演进（2026-09-29 → 10-02：A → B → C）
- **前期迭代（09-29 ~ 10-01，span 体系内演进）**：首列整格空白修复（collectCellText 递归 digText 覆盖 Code/内联节点，commit 669dea9）→ 横滑 v3 公共版（TableScrollWrap 统一包裹全部渲染点，naturalMode 自然宽测量）→ 整表统一列宽（v7 colMaxChars 预扫描全表列宽）→ 样式系列（框线改 PRIMARY、最低宽对齐正文气泡、顶边封顶、单元格内边距）→ 方案 B 边框修复（availW 扣除内边距、按 colWidths 画列竖线、行线调浅）
- **方案 A**：Markwon 原生表格 + 样式覆盖——流式期间无法实时排版，窄屏横向滚动体验差
- **方案 B**：气泡内独立 TableView 块化（初版）——块化思路成立，但行容器未处理垂直对齐、collectCellText 漏 Code/内联节点，含行内代码的整格渲染空白；列宽压缩无保底导致右半段消失待修复
- **方案 C（最终）**：MdTableView 重写，气泡内独立 TableView 块化：
  - 表格块与流式渲染管线解耦：完整块走缓存，尾部未完成块轻量 parse（衔接 D 路线 MdBlocks/MdStreamRenderer）
  - 行容器 LinearLayout 显式 `gravity=CENTER_VERTICAL`，单元格垂直居中（聊天/Agent 双模式一致）
  - `collectCellText` 覆盖 Code/内联节点，杜绝含行内代码单元格整格空白
  - 表格块 `WRAP_CONTENT` 自适应；聊天模式气泡宽度恢复对称到头像内侧、Agent 模式保持全屏
  - 方案 C 落地后清理废弃的旧表格类，阶段备份目录入 .gitignore

### 取舍
- 收益：表格流式实时渲染、单元格样式稳定、双模式一致、无 WebView 开销
- 代价：表格渲染逻辑自研维护，语法兼容范围由 commonmark 解析器兜底

### 结论
以"commonmark 解析 + 气泡内独立 TableView 块化"收敛消息流表格渲染；后续表格视觉细节（间距/对齐/线位/留白）按真机实测迭代。

---

## ADR-015 消息渲染结果落库：mdCache → rendered → 现场渲染三级读取

- **状态**：已采纳
- **日期**：2026-09-28（阶段1 落地，衔接 D 路线渲染管线）

### 背景
D 路线实时 Markdown 渲染落地后，历史消息重载/恢复需重新走 markwon 解析（CPU 开销 + 偶发并发风险），且流式期间完成的富文本渲染结果未持久化，重启即失。

### 方案（阶段1）
- **存储**：`session_msgs` 加 `rendered TEXT` + `rendered_version INTEGER DEFAULT 0`（onOpen 幂等补齐），`updateRendered(sid,seq,json,ver)` 增量写回
- **序列化**：新增 `RenderedCodec.kt`（Spanned ↔ JSON，VERSION=1），覆盖系统 span（Style/RelativeSize/ForegroundColor/BackgroundColor/Strikethrough/URL/Typeface/Bullet/LeadingMargin）+ 项目自绘 span（RoundedCodeBlock/RoundedBlockBg/RoundedTableRow/Table），只存结构化 span 不含布局态
- **读取链**：`setMarkdownCached` 三级读取——`mdCache` 命中 → `rendered` 反序列化命中 → 现场渲染；四渲染出口统一 `writeBackRendered()` 写内存 + DB
- **单段判定**：`contentEvts <= 1 && contentSegs.size <= 1` 才写回（多段 content 的 span 区间不匹配，按设计强制现场渲染，合法跳过）

### 取舍
- 收益：历史重载免 markwon 解析（真机 71 条消息 33 条落库，重开直接反序列化命中）；渲染结果与原文解耦，滚动懒写回逐步覆盖
- 代价：序列化格式自研维护，需随 span 类型演进升级 VERSION

### 结论
以"渲染结果结构化落库 + 三级读取"收敛历史恢复性能；阶段 2-5 按渲染管线演进迭代。

---

## ADR-016 MainActivity 功能域横向拆分：状态机 → 控制器 → UI 桥 → 域模块

- **状态**：已采纳
- **日期**：2026-10-03（纵向三级 + 横向四刀分两天闭环）

### 背景
ADR-005 已按"功能簇"竖向切出 MediaPreviews/AiBubbleHolder 等，但 MainActivity 仍保留 5000+ 行，会话/录音/附件三大功能域的流式字段与回调逻辑与 Activity 强耦合：一处改 UI 逻辑要动整个巨型文件，JVM 测试无法覆盖。

### 方案（2026-10-03 分步落地）
- **纵向三级**（对话流链路）：
  1. `ChatSessionState.kt`（92 行）：会话状态机，收编思考/工具/delta 等 6 个流式字段，MainActivity 首刀瘦身 344 行
  2. `ChatFlowController.kt`（195 行）：发送/流式回调控制，executor 编排 + 代际失效（切会话不串写）
  3. `StreamUiBridge.kt`（200 行）：流式行创建 + LocalEngine 回调簇桥接，ChatFlowController 再减 174 行
- **横向四刀**（功能域模块，扩展函数模式）：
  4. `MainActivitySession.kt`（173 行）：会话列表抽屉/新建/切换/保存
  5. `MainActivityInputBar.kt`（51 行）：输入栏粘合行为（onSend/输入模式/语音按钮）
  6. `MainActivityVoice.kt`（327 行）：录音域（ADR-008 产物）
  7. MainActivity 5002 → 4799 行（-203，累计瘦身约 700 行）
- 依赖处理：private 成员按需放宽 internal（8 个），顶层扩展函数内用 `val act = this` 别名访问成员

### 取舍
- 收益：MainActivity 可逐步被 JVM 测试覆盖；改动输入栏/会话不再碰核心循环；为后续"输入栏整体抽类"铺路
- 代价：扩展函数模式要求成员可见性放宽（private → internal），跨文件符号跳转略增心智负担

### 结论
以"纵向三级 + 横向域模块"把巨型 Activity 拆到可维护粒度；附件域 UI 创建块（attachPreviewWrap/attachBtn2/attachWrap）留待输入栏整体抽类。

---

## ADR-017 安全硬门禁 v2：阻塞式确认 + 票据窗口期复用

- **状态**：已采纳
- **日期**：2026-10-03（初版 dfc33ce → v2 0107f6a，已推 main）

### 背景
硬门禁初版基于 SecurityConfig 票据消费制（consumeTicket）：每次工具调用生成一次性票据，允许后即焚。指出两项体验缺陷：
1. 同参数二次调用仍弹窗——"即用即焚"导致高频重复工具（如 get_battery）弹窗疲劳
2. 弹窗时 AI 已跑完——门禁在工具执行后校验，先执行后确认，起不到拦截作用

### 方案（v2）
- `SecurityConfig.kt`：consumeTicket → validateTicket。票据不再消费即焚，改为窗口期复用：绑定（工具名+参数摘要）键，5 分钟有效期内同参数重发直接放行；超窗/换参数才重新弹确认
- `SecurityUi.kt`：requestConfirm 改阻塞式确认。CountDownLatch 挂起调用线程，UI 弹窗 await 用户点按；true/false/null 三态返回（允许/拒绝/超时），2 分钟超时兜底自动拒绝；单例防叠加，避免多工具并发弹多层窗
- `LocalEngine.kt`：工具调用前挂起在门禁上（阻塞式）；允许 → 继续执行；拒绝 → 立即停止并回"用户拒绝了工具调用，本次调用已停止"；无确认界面环境（无 UI）→ 直接放行不阻塞（兜底）

### 线程模型依据
chat() 运行在 executor 工作线程，请求处理在独立线程，UI 事件在主线程——工作线程阻塞等待主线程 UI 确认不产生死锁，这是阻塞式门禁可安全实施的前提。

### 真机三态验证（DebugServer 8765，token droid-eecd3e1858cc）
1. 阻塞确认态：含危险工具的请求挂起 + 弹窗出现；点"允许"后继续执行，审计 approvedBy=user
2. 窗口期复用态：同参数二次发送不再弹窗直接放行
3. 拒绝路径：点"拒绝"立即返回"用户拒绝了工具调用"，审计 approvedBy=rejected

### 结论
硬门禁 v2 达成"先确认后执行 + 窗口期复用"双目标；审计三态（user/rejected/超时）落库可追踪。

---

## 附：ADR 对应源码锚点（供后续补充链接指向）

| ADR | 主要载体 |
|-----|---------|
| 001/003 | `LocalEngine.kt`（工具注册 + 路由 + 原生 function calling） |
| 002 | `ApiConfig.kt` / `ModelEditActivity.kt`（自定义 Provider） |
| 004 | `MemoryDb.kt` / `MemoryKeeper.kt` / `MemoryEmbedder.kt` / `BertTokenizer.kt` |
| 005 | `MainUi.kt` / `UiKit.kt` / `BubbleSpans.kt` / `AiBubbleHolder.kt`（拆分产物） |
| 006 | `MemoryDb.kt`（会话持久化） |
| 008 | 录音模块（AudioRecord + WAV） |
| 009 | `MemoryDb.kt`（seq 窗口化）/ `MainActivity.kt`（MEM_WINDOW/截断常量）/ `LocalEngine.kt`（`MAX_OUTPUT_CHARS`/`capOut`） |
| 010 | `ModeConfig.kt` / `LocalEngine.kt`（system 禁 MD）/ 消息渲染（并排头像 + 思考折叠） |
| 011 | `DebugServer.kt`（object，ServerSocket + SSE）/ `LocalEngine.kt`（`toolList`）/ `MainActivity.kt`（SSE 转发） |
| 012 | `AITerminal.kt` + `AITerminalService.kt`（悬浮窗 + 前台服务）/ `MainActivity.kt`（7 处事件转发） |
| 013 | `AndroidManifest.xml`（`MANAGE_EXTERNAL_STORAGE`）/ `WorkDir.kt` |
| 014 | `MdTableView.kt`（气泡内独立 TableView 块化渲染）/ `MdSpans.kt` / `MdBlocks.kt` / `MdStreamRenderer.kt`（D 路线流式管线衔接） |
| 015 | `MemoryDb.kt`（rendered 列）/ `RenderedCodec.kt`（Spanned↔JSON）/ `MainActivity.kt`（三级读取 + writeBackRendered） |
| 016 | `ChatSessionState.kt` / `ChatFlowController.kt` / `StreamUiBridge.kt` / `MainActivitySession.kt` / `MainActivityInputBar.kt` / `MainActivityVoice.kt` |
| 017 | `SecurityConfig.kt` / `SecurityUi.kt` / `LocalEngine.kt`（调用前挂起门禁） |

## 附：模块实现锚点（附件解码链路）

附件「上传 → 解码 → 模型识别」链路的分工（供排查附件相关问题定位代码）：

| 附件类型 | 关键环节 | 载体 |
|---------|---------|------|
| 四类统一入口 | 附件读取 → 发送主流程 | `MainActivity.sendAttachmentFromUri` |
| 图片 | 压缩 / Base64 / 显示名 | `MainActivity.compressImage` / `queryDisplayName` |
| 视频 | 读取 / 超限转码压缩 | `MainActivity.readAll` + `VideoCompressor` |
| 音频 | 容器修正（m4a major_brand 问题） | 录音/转发模块 |
| 其他文档 | 文本提取（PDF/txt/md/docx/xlsx） | `DocTextExtractor` / `PdfTextExtractor` |
| 持久化 / 上限 | 附件存储 / 大小限制 | `AttachmentStore` / `UploadConfig` |

> 来源备注：该链路锚点源自历史排查记录，属"模块实现说明"性质，与上方 ADR（决策性）分开归类。

---
*本文档持续更新。新增/回退决策时追加 ADR-00x，并同步更新本索引。*
