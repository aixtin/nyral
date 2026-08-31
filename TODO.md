# DroidAgent 待办

> 由 Marvis 记录，2026-08-24 添加

## 待办
- [x] 文件/图片/视频发送功能（MiMo 多模态）：选附件 → 本地压缩 → 复制 cacheDir → Base64 发 MiMo → AI 生成描述占位进历史/记忆
      - 处理路径分类定案(2026-08-28)：文本/PDF 本地处理不走模型；图片/视频/音频 必须走模型（多模态）
      - PDF 本地解析提取文本(接解析库)：发 PDF 不用喂视觉模型，省 token
- [x] LICENSE（MIT）与 README 已补齐（2026-08-31）
- [ ] GitHub Release 发布（附 APK，发布后更新弹窗才生效）
- [ ] UI 优化（聊天界面、SSH 配置界面视觉与交互）
- [ ] 整理上下文：对话上下文分级管理（短期滚动 / 中期摘要 / 长期按需检索），解决 token 无限累积
- [ ] 记忆写入 VPS：DroidAgent 本地记忆（SQLite）经 SSH 同步到 VPS 记忆库
      - 目标：复用 Marvis 记忆库连接卡机制（VPS /opt/marvis-memory/Marvis记忆/ + memory-api :8899）
      - 链路：手机 App → SSH（VPS 公网 22）→ VPS 本地 127.0.0.1:8899 /save
      - 效果：记忆云端持久化，换机/重装不丢；DroidAgent 与 Marvis 共享记忆资产

## 已完成里程碑
- 2026-08-28: 模型能力表（ApiConfig.modelCapabilities）：预设=内置表(MiMo全模态/DeepSeek文本+工具/GLM文本+工具)+名称兜底；自定义模型编辑页新增能力勾选(图片/视频/音频/工具调用，默认文本+工具)
- 2026-08-28: 新增 web_search 工具（Bing cn.bing.com 桌面UA 解析 b_algo，无 key），清单首位 web_search→web_fetch→get_time→calc→memory_search→ssh/file
- 2026-08-28: 工具清单调整（web_fetch 前置、冷门 ssh/file 后置）+ 删除 memory_save（MemoryKeeper 全量自动归档取代）+ "记住"语义即时归档（MemoryKeeper.push force）
- 2026-08-27/28: 关于页改造（品牌区/博客链接/数据项文案）+ UpdateChecker 改跳 GitHub Releases + 仓库描述统一"记忆自托管·模型按需选·开源·免Root"
- v0.3: SSH 双认证 + ED25519 修复
- v0.4: 本地记忆（bge 语义检索 + 关键词双路召回）
- v0.5: 远程文件系统工具（list/read/write/info）
- v0.5.1: 工具调用格式容错 + ~ 展开 + 连接名模糊匹配
- v0.6: 多工具调用循环（上限 5 轮）
