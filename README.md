# Nyral

> 记忆自托管 · 模型按需选 · 开源 · 免 Root

Nyral 是一款运行在 Android 上的开源智能体（Agent）助手：把大模型对话、本地长期记忆、SSH 远程操作、联网工具和语音/附件多模态收进一个 App。数据自托管，无需 Root，不依赖任何第三方服务端。

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

**长期记忆（自托管）**
- bge-small ONNX 端内语义向量 + 关键词 LIKE 双路召回，SQLite 存储
- 三级分层：短期滚动窗口 → 中期摘要（辅助模型后台压缩）→ 长期全量原文 + 语义检索
- 可配置独立"辅助 AI"做记忆索引，不占用主对话模型
- pending 队列落盘 + 原子消费事务：进程被杀不丢消息、不重复归档

**工具调用（40 轮上限，20 个内置工具挂在 8 个公共底座上）**
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

- Android 7.0+（minSdk 24），完整功能需 Android 10+（工作目录走 MediaStore）
- arm64-v8a
- 至少一个 OpenAI 兼容 API 端点（自建或云厂商均可）

## 构建

```bash
git clone https://github.com/aixtin/nyral.git
cd nyral/android-agent-app
gradle assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17、Gradle 8.13、Android SDK 36。仓库已含 `settings.gradle.kts` 的阿里云镜像配置（国内构建快），海外网络可自行删除对应 `maven(...)` 行。

> 注意：本项目无 gradle wrapper（`gradlew`），请使用本机 Gradle 8.13 直接构建。
> 正式签名包用 `gradle assembleRelease`（自建 keystore 经 `keystore.properties` 读取；keystore 与 properties 已入 .gitignore，需自行准备密钥）。

## 快速开始

1. 安装 APK，进入「设置 → 模型配置」填入任意 OpenAI 兼容端点的 Base URL + API Key
2. （可选）「SSH 配置」添加远程主机：密码或私钥（ED25519 需 BC 支持，已内置），可配跳板机
3. 直接开始对话；AI 会按需调用工具，工具调用过程在气泡内折叠展示
4. 长期记忆自动归档，无需手动操作；说"记住 xxx"会立即归档

## 目录结构

```
android-agent-app/
├── app/src/main/java/io/github/aixtin/nyral/   # 主包：入口 + 会话/渲染核心
│   ├── MainActivity.kt          # 聊天主界面（约 4800 行，功能域拆分后的主体）
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

## 数据与隐私

- 对话、记忆、Token 统计、SSH 配置（加密存储）全部仅存本机
- 不采集任何遥测；唯一外部请求是启动时检查 GitHub Release 更新（可忽略 404）

## 路线图

- [x] 多模型能力表与自定义模型
- [x] 本地记忆（bge 语义检索 + 关键词召回 + 三级分层）
- [x] SSH 远程文件系统 + 跳板机 + SFTP 上传下载
- [x] 联网工具（四引擎轮换搜索/抓取/下载/site_auth Cookie 注入）
- [x] 附件多模态：图片/视频/音频/PDF/Office（v19）
- [x] 语音输入闭环（录音/发送/语音气泡，v20）
- [x] 会话搜索 + Token 统计 + 前台服务保活
- [x] 工作目录批量工具（grep/head/stats，40 轮工具上限）
- [x] 浏览器模块（自研 Agent 浏览器：整屏接管 + AI 高亮 + 验证码交还）
- [x] GitHub Release 发布（v1.3/v2.0 正式签名包，应用内更新弹窗生效）
- [ ] 对话上下文分级管理进一步优化

## 版本记录

- **v2.1.1（2026-10-02，versionCode 34）**：表格单元格垂直居中修复（MdTableView 行容器垂直居中，聊天/Agent 双模式一致）；慢放迁移调试弹窗；浏览器面板按钮；聊天模式气泡宽度恢复对称到头像内侧。
- **v2.1（2026-10-02，versionCode 33）**：消息流 Markdown 表格渲染方案 C 落地（MdTableView 重写：气泡内独立 TableView 块化、表格块 WRAP_CONTENT 自适应）；修复含行内代码单元格整格空白；表格右半段消失修复（列宽压缩保底 + 表格内部横滑）；文字竖排根因修复（列宽按文字需求压缩，放不下走内部横滑绝不竖排）+ 连续表格间距修复；三项 UI 修复（AI 正文全屏气泡、AI 侧长按复制恢复、浏览器双击开/关）；清理方案 C 废弃的旧表格类。
- **v2.0（2026-09-29 维护，versionCode 32）**：targetSdk/compileSdk 升至 36（AGP 8.13.0 + Gradle 8.13，为 Android 16 Live Updates API 接入准备）；D 路线实时 Markdown 渲染（MdSpans/MdBlocks/MdStreamRenderer）+ 引用块竖线/分割线渲染（QuoteBarSpan/HrSpan）+ rendered 落库基建（mdCache→rendered→现场渲染三级读取，ADR-015）；状态行覆盖式下拉面板（窗帘式挂 chatArea，ScrollView + 动画代际 token 防连点竞态）；思考气泡空气泡根治（协议前缀三处兜底）+ 时间线圆点对齐/图标化；首启闪退修复（markwon 并发锁）+ 消息列表滑动丝滑优化（RecyclerView 形态池化 + AiRich 容器池化 + flush 分批 + 漂移补偿 + watcher 去重）；表格横滑公共版（TableScrollWrap）+ 整表统一列宽（colMaxChars）；行动轨道原型 + 慢放体系；工具自热度排序（ToolHotStore）+ root 自动补齐自身权限（versionCode 31）；开源前安全加固与清理。
- **v2.0（2026-09-13）**：正式签名发布（自建 keystore 经 keystore.properties 读取，release 挂 signingConfig，密钥已异地备份）；版本号动态化 + 启动自动检查更新修复；settings.gradle 镜像注释补全（versionCode 30）
- **v1.3（2026-09-11）**：MainActivity 系列拆分（MediaPreviews/AiBubbleHolder/AttachmentSender/TerminalGate 等）+ 协程统一铺开 + UI 文案外置 i18n + 代码体检优化（Bitmap 采样解码、明文流量白名单）+ 首启授权/引导页 + 开发者文档与 ADR（versionCode 23）
- **v1.1（2026-09-04）**：修复 SSE 流式连接/流句柄泄漏（统一移入 finally 释放，取消/异常不泄漏）；makeCopyable 非空断言防御加固；versionCode 19→20 / versionName 1.1；SSE 消息与 streamOnce 全文日志由 Log.i 降为 Log.v 防刷屏。
- **v1.0**：基线特性版本（versionCode 19）
- **v1.1.1（2026-09-04，versionCode 21）**：视频气泡取帧失败到顶由永久放弃改为 20s 冷却后自动重试自愈（下次渲染/滚动/回前台即恢复），修复视频气泡退化回文件卡片问题。

## 开源许可

本项目采用 [MIT License](LICENSE)。

### 第三方依赖许可证

本项目运行时依赖以下第三方组件，均在各自许可证条款下使用（许可证全文见各组件官方仓库）：

| 依赖 | 版本 | 许可证 |
|---|---|---|
| kotlin-stdlib | 1.9.22 | Apache-2.0 |
| kotlinx-coroutines-android | 1.8.1 | Apache-2.0 |
| androidx.media3（exoplayer/ui/common 等） | 1.4.1 | Apache-2.0 |
| androidx.*（core/annotation/collection 等） | - | Apache-2.0 |
| org.apache.commons:commons-compress / commons-io / commons-codec / commons-lang3 | 1.27.1+ | Apache-2.0 |
| io.noties.markwon（core/ext-strikethrough/ext-tables） | 4.6.2 | Apache-2.0 |
| com.atlassian.commonmark | 0.13.0 | BSD-2-Clause |
| com.microsoft.onnxruntime:onnxruntime-android | 1.17.3 | MIT |
| org.slf4j:slf4j-api | 1.7.36 | MIT |
| com.android.tools（构建期，不打包进 APK） | - | Apache-2.0 |
| org.bouncycastle:bcprov-jdk18on | 1.78.1 | Bouncy Castle Licence |
| com.github.mwiede:jsch（含 jzlib/jbcrypt） | 0.2.17 | Revised BSD / ISC |
| org.tukaani:xz | 1.9 | Public Domain |
| org.mozilla:rhino | 1.7.14 | MPL-2.0 |
| com.github.junrar:junrar | 7.5.5 | UnRAR freeware license |

MPL-2.0（rhino）与 UnRAR license（junrar）的授权声明随依赖 jar 内嵌保留；junrar 按 UnRAR 授权仅用于解压，不用于构建 RAR 兼容压缩器。

