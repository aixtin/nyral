<div align="center">

[English](README.md) | [中文](README.zh-CN.md)

</div>

---

# Nyral

> **完结（2026-10-16）**：项目于 10 月 16 日完结，此后的改动均为“完结后的即兴”——没有路线图，也没有发布计划。54 天的独立开发历程与总结见 [END.md](./END.md)。

> 隐私优先 · 模型按需选 · 开源 · 免 Root · 无账号

Nyral 是一款运行在 Android 上的开源个人智能体（Agent）：把大模型对话、本地长期记忆、SSH 远程操作、联网工具和语音/附件多模态收进一个 App。数据默认留在设备本地——无账号、无行为追踪、无三方分析/广告 SDK；模型与工具完全由你掌控，自带 API Key 或自建端点。

## 核心特性

**对话与模型**
- 多供应商按需切换：MiMo 全模态 / DeepSeek / GLM / 任意 OpenAI 兼容端点，支持自定义模型与按模型勾选能力
- 流式输出：思考段打字机 + 正文流式 Markdown 渲染（Markwon），思考/工具调用过程可折叠展开
- 消息流 Markdown 表格渲染（方案 C）：气泡内独立 TableView 块化渲染，流式期间实时排版、单元格垂直居中，聊天/Agent 双模式一致
- 思考强度分档：按供应商能力展示 自动/开关/低中高，不支持的档位不出现
- 语音输入：长按说话、上滑取消、60 秒上限自动发送；语音气泡微信式交互（AudioRecord 采 PCM 封 WAV，兼容主流多模态 API）

**附件多模态（v19+）**
- 图片：本地压缩（最长边 2048）后走 image_url
- 视频：超 37MB 本地转码压缩再发，气泡内嵌首帧缩略图
- 文档（PDF/txt/md/docx/xlsx…）：本地解析提取文本直接注入上下文，不烧视觉 token；扫描件 PDF 自动渲染成页图走视觉通道
- 一次最多 6 个附件，App 内全屏预览（图片缩放/视频播放/PDF 分页/文本复制）

**长期记忆（端侧）**
- bge-small ONNX 端内语义向量 + 关键词 LIKE 双路召回，SQLite 存储
- 滚动式记忆：短期滚动窗口 + 单槽滚动摘要（`INSERT OR REPLACE id=1` 覆盖式，辅助模型压缩）+ 长期全量原文 + 语义检索
- 检索触发决策由辅助模型联网逐轮判断（每轮阻塞等待；失败时静默跳过检索）
- 可配置独立"辅助 AI"做记忆索引，不占用主对话模型
- pending 队列落盘 + 原子消费事务：进程被杀不丢消息、不重复归档

**工具调用（40 轮上限，21 个内置工具挂在 8 个公共底座上）**

<details>
<summary><b>工具清单</b>（点击展开 · 21 个内置工具）</summary>

- 网络底座：web_search（四引擎轮换：搜狗移动端 → 必应 RSS → 必应网页 → 百度，国内直连免 key）/ web_fetch / web_download / site_auth（按域名 Cookie 自动注入）
- SSH 底座：ssh_run / file（远端文件读写/上传/下载），支持跳板机（ProxyJump over JSch）、ED25519（BouncyCastle）、双认证
- 执行底座：js_run（沙箱执行 JS，ClassShutter 限制类访问）/ sh_run（本机 Shell，危险命令拦截 + root 提权）
- 设备底座：app（第三方 App 无障碍控制）
- 文件底座：workdir（本地工作目录 Download/Nyral_work：list/read/write/grep/head/stats，AI 拉文件到本地改再传回，绕开 SSH 命令行嵌套转义）
- 附件底座：attach_read（附件读取）/ video_frame（视频抽帧）/ file_export（文件导出）
- 记忆底座：memory_search（语义检索本地记忆）/ get_time / calc（Rhino 解释模式）
- 安全底座：security_set（危险操作确认 / root 补权 / SSH 信任开关）
- 浏览器（自研 Agent 浏览器雏形）：整屏 WebView 接管，AI 步骤播报 + 页面高亮圈 + 验证码一键交还用户
- 元工具：ask_user（交互澄清）/ tool_detail（工具说明）
- 定时岗位：timer_task_create（创建定时任务：分析 goal 后列出危险工具授权清单并逐项写明用途，task_create 壳一次打包批准，任务级能力预授权随任务销毁回收）

</details>


**安全**
- 危险操作二次确认：先确认后执行，同参数 5 分钟内免重复确认，拒绝立即停止并回执，超时自动拒绝；操作审计（允许/拒绝/超时）落库可追踪
- SSRF 拦截（web_fetch/web_download 重定向目标校验）+ 日志脱敏（API 非 2xx 响应体、SSH 私钥内容不落明文日志）
- 沙箱执行（js_run ClassShutter）+ 危险命令拦截（sh_run）+ SSH 主机密钥校验（未知主机拒绝连接）

**界面**
- 多会话管理（置顶/删除/搜索定位）、会话全文搜索（正文+思考内容，相关性打分）
- Token 用量统计（主 AI / 辅助 AI 双通道，累计+当日+会话维度）
- 运行日志（主日志 + 记忆归档日志）、前台服务保活通知
- 聊天背景（预设渐变/自定义图片模糊）、全局动画体系（气泡入场/弹窗果冻展开/按压缩放）

## 系统要求

- Android 10 及以上（minSdk 29）
- arm64-v8a
- 至少一个 OpenAI 兼容 API 端点（自建或云厂商均可）

## 构建

```bash
git clone https://github.com/aixtin/nyral.git
# 仓库根目录即工程目录，无需再 cd
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17、Gradle 8.13、Android SDK 36。仓库已含 `settings.gradle.kts` 的阿里云镜像配置（国内构建快），海外网络可自行删除对应 `maven(...)` 行。

> 构建建议：仓库已内置 gradlew（锁定 Gradle 8.13），直接 `./gradlew assembleDebug`；本机已装 Gradle 8.13 也可用 `gradle assembleDebug`。
> 正式签名包用 `gradle assembleRelease`（自建 keystore 经 `keystore.properties` 读取；keystore 与 properties 已入 .gitignore，需自行准备密钥）。

## 快速开始

1. 安装 APK，进入「设置 → 模型配置」填入任意 OpenAI 兼容端点的 Base URL + API Key
2. （可选）「SSH 配置」添加远程主机：密码或私钥（ED25519 需 BC 支持，已内置），可配跳板机
3. 直接开始对话；AI 会按需调用工具，工具调用过程在气泡内折叠展示
4. 长期记忆自动归档，无需手动操作；说"记住 xxx"会立即归档

## 目录结构

<details>
<summary><b>展开查看完整目录结构</b></summary>

```
android-agent-app/
├── app/src/main/java/io/github/aixtin/nyral/   # 主包：入口 + 会话/渲染核心
│   ├── MainActivity.kt          # 聊天主界面（约 4470 行，功能域拆分后的主体）
│   ├── ChatSessionState.kt      # 会话状态机（流式字段收编）
│   ├── ChatFlowController.kt    # 发送/流式回调控制
│   ├── StreamUiBridge.kt        # 流式行创建 + LocalEngine 回调桥
│   ├── MdTableView.kt / MdBlocksView.kt / MdBlocksRender.kt  # 消息流表格渲染（方案 C）
│   └── RoundedTablePlugin.java / RoundedTableRowSpan.java / RoundedCodeBlockSpan.java  # Markwon 表格插件
└── app/src/main/java/io/github/aixtin/nyral/all/   # 功能模块（98 个 Kotlin 文件）
    ├── LocalEngine.kt           # 引擎：工具注册表 + 分发 + 对话循环（约 1650 行）
    ├── MainActivityVoice.kt / MainActivitySession.kt / MainActivityInputBar.kt  # MainActivity 功能域拆分
    ├── SecurityConfig.kt / SecurityUi.kt   # 安全确认（危险操作二次确认）
    ├── MainUi.kt / UiKit.kt / BubbleSpans.kt / Typewriter.kt  # UI 构建与动效
    ├── ApiConfig.kt / MemoryApiConfig.kt   # 供应商与模型能力表
    ├── MemoryDb.kt / MemoryKeeper.kt / MemoryEmbedder.kt / BertTokenizer.kt / MemoryTools.kt  # 记忆体系
    ├── SshTools.kt / SshConfigStore.kt / FileTools.kt / WorkDir.kt / WorkTools.kt  # SSH/SFTP/工作目录
    ├── WebTools.kt / ScriptEngine.kt / UiControlService.kt  # 联网 / 沙箱执行 / 设备控制
    ├── BrowserPage.kt           # 自研 Agent 浏览器（整屏 WebView + AI 接管/高亮/接管验证码）
    ├── AttachmentStore.kt / DocTextExtractor.kt / PdfTextExtractor.kt / VideoCompressor.kt  # 附件链路
    └── SettingsActivity.kt / ModelEditActivity.kt / McpConfigActivity.kt / ...  # 配置页
└── app/src/main/assets/mem_model/   # bge-small 量化 ONNX 模型 + vocab
```

</details>

## 数据与隐私

- 对话、记忆、Token 统计、SSH 配置（加密存储）默认全部仅存本机；无账号体系
- 语义向量化（bge-small ONNX）完全端内执行；模型推理在远端：主对话模型与辅助记忆判断/压缩模型均通过 OpenAI 兼容端点联网调用（不支持端侧模型推理）
- 无行为追踪、无三方分析/广告 SDK；唯一的对外诊断是**匿名崩溃上报**（仅设备型号、系统版本、崩溃堆栈、最近 30 条运行日志——绝不含聊天内容与个人身份信息），默认开启，可在「外观设置 → 快捷开关」一键关闭
- 启动时另一个外部请求是 GitHub Release 更新检查（404 可忽略）
- **注意**：对话消息与附件内容会发送到你配置的模型端点（OpenAI 兼容）；如需完全私密请自建端点
- 权限清单与安全设计（DebugServer 防护 / 危险操作门禁 / 数据边界）见 [SECURITY.md](SECURITY.md)

## 路线图

<details>
<summary><b>路线图</b>：<span style="color:#1a7f37"><b>10/11 已完成</b></span>（点击展开）</summary>

- [x] 多模型能力表与自定义模型
- [x] 本地记忆（bge 语义检索 + 关键词召回 + 滚动式记忆）
- [x] SSH 远程文件系统 + 跳板机 + SFTP 上传下载
- [x] 联网工具（四引擎轮换搜索/抓取/下载/site_auth Cookie 注入）
- [x] 附件多模态：图片/视频/音频/PDF/Office（v19）
- [x] 语音输入闭环（录音/发送/语音气泡，v20）
- [x] 会话搜索 + Token 统计 + 前台服务保活
- [x] 工作目录批量工具（grep/head/stats，40 轮工具上限）
- [x] 浏览器模块（自研 Agent 浏览器：整屏接管 + AI 高亮 + 验证码交还）
- [x] GitHub Release 发布（v2.0 起正式签名包，应用内更新弹窗生效）
- [ ] 对话上下文滚动/摘要策略进一步优化

</details>


## 版本记录
- **【最新】v2.8.13（2026-10-26，versionCode 96）**：全 UI 主题化收口 + 稳定性修复——新增自绘主题开关 SwitchView（胶囊轨道随主题主色过渡 + 白色滑块动画，彻底摆脱 MIUI/HyperOS 对原生 Switch 轨道色的运行时覆盖，5 处开关全部替换）；Markdown 标题/链接/引用竖线/列表圆点颜色随主题注入，修复深色主题下深蓝标题与黑色圆点不可读；权限/定时任务/模型配置/审计等页面状态色统一收敛至语义色（Ui.OK/Ui.DANGER），消除十余处硬编码绿红。前两轮已含：TimerTask/Audit 等页面整页主题化、8 处系统弹窗统一替换为自绘圆角弹窗、主页抽屉/语音条/悬浮球硬编码色修复。另含 v2.8.9 起稳定性与附件链路修复：视频抽帧从 ImageReader 改为 SurfaceTexture + PixelCopy，根治部分 MTK 机型 JNI 层 SIGABRT 崩溃（UNEXPLAINED_EXIT）；识图模型图片内联直发、音频模型音频内联发送（400 自动降级）、超预算 Office 文档 attach_read 直接分块返回文本、表情动图不再被误当视频直发。

- **v2.8.7（2026-10-26，versionCode 70）**：Agent 模式气泡渲染修复——用户视频/图片气泡会随机在左或右侧越界、那一侧圆角被裁成直角：根因是 v2.8.0 把 Agent 模式用户气泡最大宽误设为全屏宽，忽略了聊天列表左右各 12dp 的 padding；两条渲染路径溢出方向相反（内嵌播放器用显式全屏宽帧并右对齐，左缘被顶出屏幕；缩略图 ImageSpan 宽于 TextView 实际可测宽，右缘被裁）。现用户气泡与媒体 span 统一按「屏宽 − 24dp」收敛。另修复思考/工具胶囊被压成半个图标（胶囊行 MATCH_PARENT→WRAP_CONTENT，状态壳与历史恢复摘要补 42dp 最小宽度）；流式淡出动画被取消（滚动滑出屏幕 detach）时改为原位上屏成品，不再出现气泡永久空白；补齐 98 个缺失的英文资源，英文界面不再静默回退中文。

- **v2.8.6（2026-10-24，versionCode 69）**：更新弹窗体验优化——新增「此版本不再提醒」按钮：点击后记录该版本，后续自动检查不再弹窗（手动检查改为提示引导），新版本发布后仍正常提醒。另修复 ToolExecutor 中 M4A brand 比较残留的原始 NUL 字节（文件不再被误判为二进制）。

- **v2.8.5（2026-10-24，versionCode 68）**：修复 ApiConfig 引发的 ANR——providers() 改为 @Volatile 内存缓存，消除 onResume 期间主线程重复执行 AndroidKeyStore AES/GCM 同步解密造成的长时间阻塞（单次解密此前最长可卡死主线程约 60 秒）；save/add/delete 均即时刷新缓存，写入改动仍立即生效。

<details>
<summary>历史版本（v2.8.4 及更早，共 27 条）</summary>

- **v2.8.4（2026-10-24，versionCode 67）**：崩溃定位精度提升——应用内近期日志时间戳精确到毫秒（HH:mm:ss.SSS，兼容读取旧格式行）；ANR 监控加强存活探测：判定卡死后先投递探测心跳并短等 600ms，区分「仍卡住（抓到实时阻塞堆栈）」与「已恢复（补报）」两种现场，崩溃文件新增 captured_while_blocked 字段标记现场质量。
- **v2.8.3（2026-10-21，versionCode 66）**：崩溃上报开关与管线加固——新增端侧总开关（外观设置 → 快捷开关 → 崩溃上报，默认开启）：关闭后完全不上传不导出，崩溃日志仅留在应用私有目录；上报管线加固：上传鉴权 token 改为构建时注入（源码不含明文）、新增安装实例 ID 与服务端配额、401/429 视为永久失败停止重试避免本地堆积；崩溃日志上传前逐行脱敏（邮箱 / SSH user@host / URL query 参数值 / 绝对路径 → [redacted]）；CI 从 GitHub secret 注入 crashToken、新增夜间构建、纯文档提交跳过 CI；lint/detekt 基线冻结；隐私文档统一口径（唯一对外诊断 = 匿名崩溃上报，默认开启可关闭）；声明不支持端侧推理（离线路线废弃）。
- **v2.8.2（2026-10-20，versionCode 65）**：补丁版——欢迎卡片危险门禁按钮修复：未选中的“自动”档由 PRIMARY 35% 半透明底改为 Ui.PRIMARY_LIGHT 不透明实底（与“严格”一致），不再在深色聊天背景上显黑。
- **v2.8.1（2026-10-20，versionCode 64）**：补丁版——记忆库健壮性修复：MemoryDb 新增 v12 迁移 + onOpen 幂等补列（src_session_id/src_msg_no），根治老库打开长期记忆页必崩；全 App 主题从系统 Material.Light 迁移到 Theme.Nyral；返回按钮由“‹”字符统一换成 Lucide 箭头图标；全局弹窗入场动画统一为 fadeScaleShow（淡入 + 0.92→1.0 微缩放，替代 jelly 回弹 / 水墨晕开）；新增页面切换动画；长期记忆页弹窗不再被系统翻转盖住触发按钮。
- **v2.8.0（2026-10-20，versionCode 63）**：新增知识库自动沉淀（默认关闭，设置可开启）——每次与 AI 讨论后按章节异步沉淀到本地知识书，含书库/条目/回收站/审计页面；video_frame 未传 timeMs 时默认抽 0/30%/60%/95% 四帧，单帧 timeMs 超范围改报错并回传真实视频时长（不再伪造 @14s/@20s 标签），抽帧结果统一追加“(视频共Xs)”；LocalEngine 视觉块新增规则，多帧采样强制逐帧描述，杜绝“视频只按首帧概括”；空参熔断（workdir 空 action / sh_run 空 script 前置报错，不进确认流程）；思考面板宽度与聊天解耦（toggleStatusDrop / wMaxLimit / chatMaxW，双模式右缘修复）；新增 ANR Watchdog 崩溃收集；文档占位页落地“模型幻觉·勿过度依赖 AI·信用户自己”警示；工具描述强化 file_export 必用于交付；配套 UiKit / MainActivity 重构。
- **v2.7.0（2026-10-16，versionCode 48）**：项目完结收尾版——发布完结篇 END.md 并在 README 顶部声明完结；Trae 修复（SecurityUi 确认桥泄漏 / mdExecutor 线程池泄漏 / MCP 客户端并发安全）；新增 write_clipboard 剪贴板工具与 tool_detail 工具索引；基于最新 main 重新构建正式产物 APK。
- **v2.6.0（2026-10-15，versionCode 47）**：定时岗位 MVP 落地——AI 可创建定时任务（timer_task_create）：分析 goal 后列出危险工具授权清单（每项附用途），弹 task_create 壳由用户打包批准，静默场景强制拒绝创建；任务级能力预授权（任务静默执行时白名单危险工具放行），授权生命周期随任务销毁回收；定时岗位请求注入 X-Nyral-Task-ID 请求头（远端可识别任务来源）；任务执行完成新增系统通知（notifyTaskDone）；修复单次闹钟无续期/重排残留旧闹钟/授权对话框列表不渲染。安全审计页（AuditActivity）标题不再被系统状态栏遮挡（Android 15+ edge-to-edge 下消费窗口 inset）。
- **v2.5.3（2026-10-10，versionCode 46）**：聊天背景新增顶栏/输入框栏透明设置——支持不透明 / 普通透明 / 静态毛玻璃三档（消息区暂不支持）；毛玻璃以自定义图片背景为底做静态模糊；顶栏/输入框四角统一全圆角、分隔线两端内缩 16dp 避开圆弧；表情抽屉图标改用 Ui.TEXT 适配主题。
- **v2.5.2（2026-10-08，versionCode 45）**：手势交互重构——三面板手势规则统一（左抽屉：控件展开 + 开态右→左关闭；浏览器：双击右缘开/关；右汉堡面板：控件展开 + 左→右关闭）；修复左抽屉跟手滞后——DrawerDragController 在拦截判定通过瞬间即按当前 dx 定位面板/遮罩（Android 手势拦截会吞掉触发拦截的 MOVE 事件，此前面板要滑到 dx=-200 才跟手）；BrowserSlideController 重构（-96 行）。
- **v2.5.1（2026-10-07，versionCode 44）**：修复黑屏下浏览器 scan 恒 0 挂死——waitStable 入口由 requestAnimationFrame 双连启动改为 setTimeout（后台 WebView 的 rAF 不触发，扫描永不回填）；DebugServer 补挂 /v1/browser/text 端点（fetchTextSync 早已实现但暲注册路由，此前返回 404）；新增 WAKE_LOCK 权限支撑黑屏保活。
- **v2.5.0（2026-10-07，versionCode 43）**：新增新手引导向导（模式亮点、模型提供商选择、API Key 配置流程）；修复引导配置模型不持久化；新增引导相关英文翻译。
- **v2.4.0（2026-10-06，versionCode 42）**：minSdk 升至 29（Android 10 及以上）；浏览器自动化闭环修复——交互元素扫描阈值放宽至 16px 且无文字图标/下拉菜单兜底（窄屏桌面式后台）、contenteditable 富文本输入改 execCommand insertText（TipTap/ProseMirror 等编辑器）、视口外元素 scrollIntoView/程序化点击兜底；修复 token 遮罩残留吞全屏触摸死锁。
- **v2.3.5（2026-10-04，versionCode 41）**：修复第三轮安全复评剩余条目——R3-2 通知确认 PendingIntent 改用全局自增 requestCode（杜绝并发 hashCode 碰撞串台，旧通知不再批准新操作）；R3-3 SSH 主机信任确认气泡直接展示待确认主机密钥指纹供核对后批准；R3-4 危险命令规范化增加去除短横线（nc -e / nc -l -e 等反向 Shell 构造面命中黑名单）；R3-6 DebugServer /v1/browser/eval 任意 JS 求值同挂三档门禁；新增 R3-4/R3-6 防回归单测。
- **v2.3.4（2026-10-04，versionCode 40）**：修复门禁自动档复合危险动作漏确认——browser:click/type/upload、app:click/text/tap/launch、file:write/upload、workdir:write 等统一按 name:action 复合键判定（needsConfirm/riskOf/票据三处同步同 key），自动档恢复弹确认；新增门禁防回归单测（高危名单全条目风险评级断言 + 裸名保持低危语义）。
- **v2.3.3（2026-10-04，versionCode 39）**：安全中心三档门禁（严格/自动/放行）UI 落地，AI 不再自切档位；欢迎卡片内嵌门禁快捷切档；新增审计页（AuditActivity）；放行二次确认弹窗主题化，与设置页视觉一致；修复安全中心取消二次确认时的重入弹窗。
- **v2.3.2（2026-10-03，versionCode 38）**：安全审查修复版：高危操作（命令执行、脚本执行、动态工具调用）增加二次确认保护；DebugServer 局域网模式与非本机连接打醒目告警；危险命令黑名单补强（eval/$(/xargs/busybox/base64/printf/进程替换等）；移除 SSH 私钥前缀日志；release 开启 R8 混淆+资源收缩，新增 proguard-rules.pro；依赖升级（jsch 0.2.26 / bcprov 1.82 / commons-compress 1.28.0 / xz 1.10 / onnxruntime 1.26.0 / media3 1.5.1）。
- **v2.3.1（2026-10-03，versionCode 37）**：安全加固版：SSRF 防护重写（host 字面量正则改为 InetAddress 解析后逐项校验，覆盖 DNS 重绑定、IPv6、IP 编码、0.0.0.0 变体四类绕过，HTTP 连接锁定已校验 IP 并保留原 Host 头）；日志脱敏补齐（SSE 流与 SSH 命令日志仅输出长度）；file/read 路径穿越修复（canonicalPath 白名单校验）；CI 签名 job 仅 push 触发；trustHost 增加连接名校验；危险操作确认名单补 file:upload / browser:save_cookies；workdir 白名单尾斜杠误拒修复。
- **v2.3（2026-10-03，versionCode 36）**：修复 SSH 主机密钥空指针 NPE；SSRF 风险文案修正；M1/M2/M4/M5/M6 五项安全加固；CI 新增 build-release 签名 job（Secrets 管理签名）。
- **v2.2（2026-10-02，versionCode 35）**：6 项安全审计修复（JS 桥全局注入收紧至本地资产页面、Cookie 与内容访问收紧、API Key 改 AndroidKeyStore AES/GCM 加密并兼容旧明文迁移、site_auth.json 加密存储+旧数据迁移、WebTools fetch/重定向复用 SSRF 黑名单检查、DebugServer 移除 query token 仅 header 鉴权）；CI 升级（push/PR 自动 assembleDebug 编译门、debug APK 产物上传、Gradle 依赖缓存）。
- **v2.1.1（2026-10-02，versionCode 34）**：表格单元格垂直居中修复（MdTableView 行容器垂直居中，聊天/Agent 双模式一致）；慢放迁移调试弹窗；浏览器面板按钮；聊天模式气泡宽度恢复对称到头像内侧。
- **v2.1（2026-10-02，versionCode 33）**：消息流 Markdown 表格渲染方案 C 落地（MdTableView 重写：气泡内独立 TableView 块化、表格块 WRAP_CONTENT 自适应）；修复含行内代码单元格整格空白；表格右半段消失修复（列宽压缩保底 + 表格内部横滑）；文字竖排根因修复（列宽按文字需求压缩，放不下走内部横滑绝不竖排）+ 连续表格间距修复；三项 UI 修复（AI 正文全屏气泡、AI 侧长按复制恢复、浏览器双击开/关）；清理方案 C 废弃的旧表格类。
- **v2.0（2026-09-29 维护，versionCode 32）**：targetSdk/compileSdk 升至 36（AGP 8.13.0 + Gradle 8.13，为 Android 16 Live Updates API 接入准备）；D 路线实时 Markdown 渲染（MdSpans/MdBlocks/MdStreamRenderer）+ 引用块竖线/分割线渲染（QuoteBarSpan/HrSpan）+ rendered 落库基建（mdCache→rendered→现场渲染三级读取，ADR-015）；状态行覆盖式下拉面板（窗帘式挂 chatArea，ScrollView + 动画代际 token 防连点竞态）；思考气泡空气泡根治（协议前缀三处兜底）+ 时间线圆点对齐/图标化；首启闪退修复（markwon 并发锁）+ 消息列表滑动丝滑优化（RecyclerView 形态池化 + AiRich 容器池化 + flush 分批 + 漂移补偿 + watcher 去重）；表格横滑公共版（TableScrollWrap）+ 整表统一列宽（colMaxChars）；行动轨道原型 + 慢放体系；工具自热度排序（ToolHotStore）+ root 自动补齐自身权限（versionCode 31）；开源前安全加固与清理。
- **v2.0（2026-09-13）**：正式签名发布（自建 keystore 经 keystore.properties 读取，release 挂 signingConfig，密钥已异地备份）；版本号动态化 + 启动自动检查更新修复；settings.gradle 镜像注释补全（versionCode 30）
- **v1.3（2026-09-11）**：MainActivity 系列拆分（MediaPreviews/AiBubbleHolder/AttachmentSender/TerminalGate 等）+ 协程统一铺开 + UI 文案外置 i18n + 代码体检优化（Bitmap 采样解码、明文流量白名单）+ 首启授权/引导页 + 开发者文档与 ADR（versionCode 23）
- **v1.1.1（2026-09-04，versionCode 21）**：视频气泡取帧失败到顶由永久放弃改为 20s 冷却后自动重试自愈（下次渲染/滚动/回前台即恢复），修复视频气泡退化回文件卡片问题。
- **v1.1（2026-09-04）**：修复 SSE 流式连接/流句柄泄漏（统一移入 finally 释放，取消/异常不泄漏）；makeCopyable 非空断言防御加固；versionCode 19→20 / versionName 1.1；SSE 消息与 streamOnce 全文日志由 Log.i 降为 Log.v 防刷屏。
- **v1.0**：基线特性版本（versionCode 19）
</details>


## 开源许可

本项目采用 [MIT License](LICENSE)。

### 第三方依赖许可证

本项目运行时依赖以下第三方组件，均在各自许可证条款下使用（许可证全文见各组件官方仓库）：

| 依赖 | 版本 | 许可证 |
|---|---|---|
| kotlin-stdlib | 1.9.22 | Apache-2.0 |
| kotlinx-coroutines-android | 1.8.1 | Apache-2.0 |
| androidx.media3（exoplayer/ui 等） | 1.5.1 | Apache-2.0 |
| androidx.*（core/recyclerview 等） | - | Apache-2.0 |
| org.apache.commons:commons-compress | 1.28.0 | Apache-2.0 |
| io.noties.markwon（core/ext-strikethrough/ext-tables） | 4.6.2 | Apache-2.0 |
| com.atlassian.commonmark（commonmark/ext-gfm-tables/ext-gfm-strikethrough） | 0.13.0 | BSD-2-Clause |
| com.microsoft.onnxruntime:onnxruntime-android | 1.26.0 | MIT |
| org.slf4j:slf4j-api | 1.7.36 | MIT |
| com.android.tools（构建期，不打包进 APK） | - | Apache-2.0 |
| org.bouncycastle:bcprov-jdk18on | 1.82 | Bouncy Castle Licence |
| com.github.mwiede:jsch（含 jzlib/jbcrypt） | 0.2.26 | BSD-3-Clause / BSD / ISC |
| org.tukaani:xz | 1.10 | Public Domain |
| org.mozilla:rhino | 1.7.15 | MPL-2.0 |
| com.github.junrar:junrar | 7.6.0 | UnRAR freeware license |
| junit / org.json / hamcrest-core（测试期，不打包） | 4.13.2 / 20231013 / 1.3 | EPL-2.0 / JSON License / BSD-3-Clause |

MPL-2.0（rhino）与 UnRAR license（junrar）的授权声明随依赖 jar 内嵌保留；junrar 按 UnRAR 授权仅用于解压，不用于构建 RAR 兼容压缩器。

