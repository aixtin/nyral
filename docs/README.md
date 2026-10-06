# Nyral · 开发者文档

> 面向开源社区的开发者文档，与仓库根 README（面向用户）互补。本文档专注工程结构、构建发布、架构决策与模块实现说明。

## 文档索引

| 文档 | 内容 | 适用读者 |
|------|------|---------|
| [架构决策记录（ADR）](./ADR-架构决策记录.md) | 17 条关键架构决策：背景 → 方案 → 取舍 → 结论 | 想理解"为什么这么设计"的开发者 |
| 本文档 | 工程结构、构建与发布流程、模块清单 | 想动手改代码/构建/发版的开发者 |

---

## 工程结构

源码按两个包路径组织（均属包 `io.github.aixtin.nyral`）：主包 `app/src/main/java/io/github/aixtin/nyral/` 放入口与会话/渲染核心，功能模块集中在 `all/` 子目录，共 100+ 个 Kotlin/Java 源文件、约 2 万行。按职责分簇如下：

### 引擎与对话

| 载体 | 职责 |
|------|------|
| `LocalEngine.kt` | 核心引擎：工具注册表（约 1650 行，20 工具 8 底座）+ 两步式路由（tool_choice）+ 原生 function calling + SSE 流式解析 + 工具调用循环（40 轮上限）+ 上下文组装 + MCP 工具合并 |
| `MainActivity.kt` | 聊天主界面：气泡渲染、录音、附件收发、事件分发、会话管理（约 4800 行，功能域拆分后的主体） |
| `ChatSessionState.kt` | 会话状态机：收编思考/工具/delta 等 6 个流式字段 |
| `ChatFlowController.kt` | 发送/流式回调控制（executor 编排 + 代际失效） |
| `StreamUiBridge.kt` | 流式行创建 + LocalEngine 回调簇桥接（思考/工具/正文/收尾/错误全分支） |
| `MainActivityVoice.kt` / `MainActivitySession.kt` / `MainActivityInputBar.kt` | MainActivity 功能域横向拆分（录音域 / 会话域 / 附件域输入栏） |
| `MainUi.kt` / `UiKit.kt` / `BubbleSpans.kt` / `Typewriter.kt` | UI 构建与动效、气泡 span、打字机动效 |
| `ModeConfig.kt` | 聊天 / Agent 双模式开关与分派 |
| `MdTableView.kt` / `MdBlocksView.kt` / `MdBlocksRender.kt` | 消息流 Markdown 表格渲染（方案 C：气泡内独立 TableView 块化 + 单元格垂直居中 + 列宽按文字需求压缩/内部横滑） |

### 安全

| 载体 | 职责 |
|------|------|
| `SecurityConfig.kt` | 安全配置 + 危险操作确认（同参数 5 分钟免重复确认；unknown 主机拒绝 SSH） |
| `SecurityUi.kt` | 阻塞式确认 UI（CountDownLatch 挂起调用线程，2 分钟超时自动拒绝，三态：允许/拒绝/超时） |
| `ScriptEngine.kt` | 执行底座：js_run（沙箱 + ClassShutter）/ sh_run（本机 Shell + 危险命令拦截 + root 提权） |

### 模型配置

| 载体 | 职责 |
|------|------|
| `ApiConfig.kt` / `MemoryApiConfig.kt` | 供应商列表、模型能力表、自定义 Provider |
| `ModelEditActivity.kt` | 自定义模型编辑页 |

### 记忆体系（端内自托管三级）

| 载体 | 职责 |
|------|------|
| `MemoryDb.kt` | SQLite 存储：会话、消息（seq 窗口化、rendered 渲染结果落库）、记忆、Token 统计 |
| `MemoryKeeper.kt` | 记忆归档/摘要/消费事务（pending 队列 + 原子消费） |
| `MemoryEmbedder.kt` / `BertTokenizer.kt` | bge-small ONNX 端内语义向量 |
| `MemorySummaryActivity.kt` | 中期摘要 / 记忆管理入口 |

### SSH / 工作目录

| 载体 | 职责 |
|------|------|
| `SshTools.kt` | JSch SSH/SFTP：跳板机（ProxyJump）、ED25519（BouncyCastle）、双认证 |
| `SshConfigActivity.kt` / `SshConfigStore.kt` | SSH 配置管理（加密存储） |
| `FileTools.kt` | 远程文件操作 |
| `WorkDir.kt` / `WorkTools.kt` | 本地工作目录（Download/Nyral_work）：list/read/write/grep/head/stats |

### 联网工具 / 浏览器

| 载体 | 职责 |
|------|------|
| `WebTools.kt` | web_search（四引擎轮换：搜狗移动端 → 必应 RSS → 必应网页 → 百度）/ web_fetch / web_download / site_auth（Cookie 注入） |
| `BrowserPage.kt` | 自研 Agent 浏览器：整屏 WebView 接管、AI 状态条 + 高亮圈（坐标画框 + 滚动跟随）、验证码一键交还用户 |

### 附件多模态链路

| 载体 | 职责 |
|------|------|
| `AttachmentStore.kt` | 附件持久化与大小限制 |
| `DocTextExtractor.kt` / `PdfTextExtractor.kt` | PDF/txt/md/docx/xlsx 文本提取 |
| `VideoCompressor.kt` | 视频转码压缩（超限降质） |
| `MainActivity.sendAttachmentFromUri` | 附件读取 → 发送主流程入口 |

### 调试与可视化

| 载体 | 职责 |
|------|------|
| `DebugServer.kt` | 内置 HTTP 调试服务（/v1/ping、/v1/state、/v1/chat SSE、/v1/logs、/v1/mem/search），Token 鉴权，debug-only |
| `AITerminal.kt` + `AITerminalService.kt` | 透明悬浮迷你终端：前台服务 + 悬浮窗，LocalEngine 事件流实时展示 |

### 配置 / 页面

| 载体 | 职责 |
|------|------|
| `SettingsActivity.kt` | 设置主页（模型/SSH/记忆/外观/调试服务/AI 悬浮终端开关） |
| `FirstRunSetupActivity.kt` / `Agreements.kt` / `GuideActivity.kt` | 首启授权、用户协议、引导流程 |
| `AboutActivity.kt` | 关于页 |

### 资源

- `app/src/main/assets/mem_model/`：bge-small 量化 ONNX 模型 + vocab（端内语义检索）
- `app/src/main/res/`：布局/图标/动效/strings（UI 文案已外置）

---

## 构建

### 前置要求

| 项 | 要求 |
|----|------|
| JDK | 17 |
| Gradle | 8.13（仓库已含 gradlew，也支持本机 Gradle 8.13） |
| Android SDK | 36（compileSdk） |
| 目标设备 | Android 10+（minSdk 29）、arm64-v8a |

### 构建命令

```bash
git clone https://github.com/aixtin/nyral.git
# 仓库根目录即工程目录，无需再 cd
gradle assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

- 仓库已含 `settings.gradle.kts` 的阿里云镜像配置（国内网络构建更快），海外网络可删除对应 `maven(...)` 行。
- 版本号：`app/build.gradle.kts` 中 `versionCode` / `versionName`（当前 v2.4.0 / versionCode 42）。
- 正式签名：`gradle assembleRelease`，keystore 自建（RSA2048/10000 天），经 `keystore.properties` 读取（keystore 与 properties 均入 .gitignore 排密）。

### 装机

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

连接设备后安装；日常迭代也常用 DebugServer（`POST /v1/chat` SSE）驱动端到端验证，替代 ADB 模拟点击。

---

## 发布流程（维护者）

发布新版本到 GitHub Releases 的标准闭环：

1. **代码收口**：合并功能分支到 `main`，提交信息遵循 `feat:/docs:/fix:/i18n:` 前缀
2. **构建**：`gradle assembleRelease`（正式签名）产出 APK；日常调试用 `assembleDebug`
3. **推送**：`git push` main（涉及历史改写时用 `git push --force`）
4. **打 tag**：如 `git tag v2.0 && git push origin v2.0`（版本号必须递增，否则旧版 UpdateChecker 不触发更新）
5. **创建 Release**：GitHub Releases 新建，附 APK 归档（含 SHA256 校验值）
6. **同步文档**：改动涉及架构决策时，追加 `docs/ADR-架构决策记录.md` 的 ADR-00x 条目，并更新本文档索引

### 开源许可合规

- 项目主许可证：MIT（见仓库根 `LICENSE`）
- 第三方依赖许可已全量扫描并登记在根 README「第三方依赖许可证」表
- 注意：`rhino`（MPL-2.0）与 `junrar`（UnRAR freeware license）属弱左版/受限许可，授权声明随依赖 jar 内嵌保留；junrar 仅用于解压

---

## 架构决策摘要

完整的「背景 → 方案 → 取舍 → 结论」见 [架构决策记录（ADR）](./ADR-架构决策记录.md)，当前 17 条：

| # | 决策 | 状态 |
|---|------|------|
| 001 | 引擎技术路线：借鉴开源 + 自写核心 | 已采纳 |
| 002 | 推理路线：放弃本地 GGUF，统一云端 OpenAI 兼容 API | 已采纳 |
| 003 | 工具调度：自研文本协议 → 原生 function calling | 已采纳 |
| 004 | 长期记忆：端内自托管三级体系 | 已采纳 |
| 005 | 单体治理：巨型 Activity 渐进式拆分 | 已采纳 |
| 006 | 会话持久化：退后台兜底落库 | 已采纳 |
| 007 | UI 渲染容器：RecyclerView 改造尝试与回退 | 已回退 |
| 008 | 语音输入链路：AudioRecord + PCM → WAV | 已采纳 |
| 009 | 上下文治理：消息窗口化 + 注入截断双保险 | 已采纳 |
| 010 | 交互双模式：聊天 vs Agent | 已采纳 |
| 011 | 开发者调试通道：内置 HTTP 调试服务 | 已采纳 |
| 012 | 过程可视化：透明悬浮迷你终端 | 已采纳 |
| 013 | 数据存储访问：Android 作用域存储适配 | 已采纳 |
| 014 | 消息流表格渲染：气泡内独立 TableView 块化（方案 C） | 已采纳 |
| 015 | 消息渲染结果落库：mdCache → rendered → 现场渲染三级读取 | 已采纳 |
