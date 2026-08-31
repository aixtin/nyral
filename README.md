# agent

> 记忆自托管 · 模型按需选 · 开源 · 免 Root

agent 是一款运行在 Android 上的智能体（Agent）助手：把大模型对话、本地记忆、SSH 远程文件操作与工具调用收进一个 App，数据自托管，无需 Root。

## 核心特性

- **多模型按需切换**：内置模型能力表，支持云端文本/多模态模型（MiMo 全模态、DeepSeek、GLM）与本地引擎（llama-server 等），工具调用能力按模型能力自动匹配
- **本地记忆自托管**：bge 语义向量 + 关键词双路召回（SQLite），记住语义即时归档；可经 SSH 将本地记忆同步到 VPS 记忆库，实现云端持久化、换机不丢
- **远程文件系统工具**：基于 SSH（双认证 + ED25519）提供 list/read/write/info 等远程文件操作
- **工具调用循环**：web_search / web_fetch / get_time / calc / memory_search / ssh / file 等工具，支持多工具调用循环
- **本地解析省 Token**：文本/PDF 本地解析提取，不喂视觉模型
- **免 Root、纯 Kotlin**：所有能力在 App 沙箱内自包含实现

## 技术栈

| 模块 | 选型 |
| --- | --- |
| 语言 | Kotlin |
| 模型运行时 | ONNX Runtime Android（bge 嵌入） |
| SSH | JSch + BouncyCastle |
| 脚本执行 | Rhino（JavaScript） |
| Markdown 渲染 | Markwon |

## 构建与安装



构建产物：，直接安装到 Android 设备（无需 Root）。

## 快速开始

1. 安装 APK 后打开，在设置中配置模型（云端 API Key 或本地引擎地址）
2. 可选：配置 SSH 连接（密码或密钥，支持 ED25519），开启远程文件工具
3. 可选：配置 VPS 记忆库地址，启用记忆云端同步

## 目录结构



## 数据与隐私

- 对话与记忆默认仅存本地（SQLite），云端同步仅在您配置 VPS 记忆库后开启
- 不采集任何遥测数据

## 开源许可

本项目采用 [MIT License](LICENSE)。

## 路线图

- [x] 多模型能力表与自定义模型
- [x] 本地记忆（bge 语义检索 + 关键词召回）
- [x] SSH 远程文件系统
- [ ] 文件/图片/视频发送（多模态输入）
- [ ] GitHub Release 发布（附 APK）
- [ ] 对话上下文分级管理
