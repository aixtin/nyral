# DroidAgent

> 记忆自托管 · 模型按需选 · 开源 · 免 Root

DroidAgent 是一款运行在 Android 上的开源智能体（Agent）助手：把大模型对话、本地长期记忆、SSH 远程操作、联网工具和语音/附件多模态收进一个 App。数据自托管，无需 Root，不依赖任何第三方服务端。

## 核心特性

**对话与模型**
- 多供应商按需切换：MiMo 全模态 / DeepSeek / GLM / 任意 OpenAI 兼容端点，支持自定义模型与按模型勾选能力
- 流式输出：思考段打字机 + 正文流式 Markdown 渲染（Markwon），思考/工具调用过程可折叠展开
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

**工具调用（40 轮上限）**
- 联网：web_search（必应 RSS）/ web_fetch / web_download / site_auth（按域名 Cookie 自动注入）
- SSH/SFTP：ssh_run / file_list / file_read / file_info / file_write / ssh_upload / ssh_download / ssh_ls，支持跳板机（ProxyJump over JSch）、ED25519（BouncyCastle）、双认证
- 本地工作目录（Download/DroidAgent_work）：workdir_list / read / write / grep（批量全文搜索）/ head（防上下文爆炸）/ stats，AI 拉文件到本地改再传回，绕开 SSH 命令行嵌套转义
- 其他：get_time / calc（Rhino 解释模式）/ memory_search

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
git clone https://github.com/aixtin/droid-agent.git
cd droid-agent/android-agent-app
gradle assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17、Gradle 8.5+、Android SDK 34。仓库已含 `settings.gradle.kts` 的阿里云镜像配置（国内构建快），海外网络可自行删除对应 `maven(...)` 行。

> 注意：本项目无 gradle wrapper（`gradlew`），请使用本机 Gradle 8.5+ 直接构建。

## 快速开始

1. 安装 APK，进入「设置 → 模型配置」填入任意 OpenAI 兼容端点的 Base URL + API Key
2. （可选）「SSH 配置」添加远程主机：密码或私钥（ED25519 需 BC 支持，已内置），可配跳板机
3. 直接开始对话；AI 会按需调用工具，工具调用过程在气泡内折叠展示
4. 长期记忆自动归档，无需手动操作；说"记住 xxx"会立即归档

## 目录结构

```
android-agent-app/
├── app/src/main/java/io/github/aixtin/droidagent 注: 源码实际位于 java/all/ (包名 io.github.aixtin.droidagent)
│   ├── MainActivity.kt        # 聊天主界面/气泡渲染/录音/附件
│   ├── MainUi.kt / Ui.kt / UiKit.kt / BubbleSpans.kt / Typewriter.kt  # UI 构建与动效
│   ├── LocalEngine.kt         # 两步式路由 + SSE 流式解析 + 工具调用循环
│   ├── ApiConfig.kt / MemoryApiConfig.kt  # 供应商与模型能力表
│   ├── MemoryDb.kt / MemoryKeeper.kt / MemoryEmbedder.kt / BertTokenizer.kt  # 记忆三级体系
│   ├── SshTools.kt / SshConfigStore.kt / FileTools.kt / WorkDir.kt / WorkTools.kt  # SSH/SFTP/工作目录
│   ├── WebTools.kt            # 搜索/抓取/下载/site_auth
│   ├── AttachmentStore.kt / DocTextExtractor.kt / PdfTextExtractor.kt / VideoCompressor.kt  # 附件链路
│   └── SettingsActivity.kt / ModelEditActivity.kt / ...  # 各配置页
└── app/src/main/assets/mem_model/   # bge-small 量化 ONNX 模型 + vocab
```

## 数据与隐私

- 对话、记忆、Token 统计、SSH 配置（加密存储）全部仅存本机
- 不采集任何遥测；唯一外部请求是启动时检查 GitHub Release 更新（可忽略 404）
- 记忆云端同步（规划中）将完全走你自己服务器的 SSH 通道

## 路线图

- [x] 多模型能力表与自定义模型
- [x] 本地记忆（bge 语义检索 + 关键词召回 + 三级分层）
- [x] SSH 远程文件系统 + 跳板机 + SFTP 上传下载
- [x] 联网工具（必应 RSS 搜索/抓取/下载/site_auth Cookie 注入）
- [x] 附件多模态：图片/视频/音频/PDF/Office（v19）
- [x] 语音输入闭环（录音/发送/语音气泡，v20）
- [x] 会话搜索 + Token 统计 + 前台服务保活
- [x] 工作目录批量工具（grep/head/stats，40 轮工具上限）
- [ ] 记忆经 SSH 同步到自托管 VPS（复用 Marvis 记忆库协议）
- [ ] 对话上下文分级管理进一步优化
- [ ] GitHub Release 发布（附 APK，发布后应用内更新弹窗生效）

## 版本记录

- **v1.1（2026-09-04）**：修复 SSE 流式连接/流句柄泄漏（统一移入 finally 释放，取消/异常不泄漏）；makeCopyable 非空断言防御加固；versionCode 19→20 / versionName 1.1；SSE 消息与 streamOnce 全文日志由 Log.i 降为 Log.v 防刷屏。
- **v1.0**：基线特性版本（versionCode 19）

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


- 2026-09-04 v1.1.1(versionCode 21): 视频气泡取帧失败到顶由永久放弃改为20s冷却后自动重试自愈(下次渲染/滚动/回前台即恢复), 修复视频气泡退化回文件卡片问题。
