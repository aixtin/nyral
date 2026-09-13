# agent 待办

> 由 assistant 记录，2026-08-24 添加

## 待办
- [x] 文件/图片/视频发送功能（MiMo 多模态）：选附件 → 本地压缩 → 复制 cacheDir → Base64 发 MiMo → AI 生成描述占位进历史/记忆
      - 处理路径分类定案(2026-08-28)：文本/PDF 本地处理不走模型；图片/视频/音频 必须走模型（多模态）
      - PDF 本地解析提取文本(接解析库)：发 PDF 不用喂视觉模型，省 token
- [x] LICENSE（MIT）与 README 已补齐（2026-08-31）
- [x] GitHub Release 发布（v1.3/v2.0 已发布，附正式签名 APK，更新弹窗已生效；2026-09-13）
- [ ] UI 优化（聊天界面、SSH 配置界面视觉与交互）
- [x] 整理上下文：对话上下文分级管理（短期滚动 / 中期摘要 / 长期按需检索），解决 token 无限累积（ADR-009 已落地）
- [ ] 记忆写入 VPS：agent 本地记忆（SQLite）经 SSH 同步到 VPS 记忆库
      - 目标：复用 assistant 记忆库连接卡机制（VPS /opt/agent-memory/assistant记忆/ + memory-api :8899）
      - 链路：手机 App → SSH（VPS 公网 22）→ VPS 本地 127.0.0.1:8899 /save
      - 效果：记忆云端持久化，换机/重装不丢；agent 与 assistant 共享记忆资产

- [ ] 补充基础单元测试（零测试是最大隐患，修 A 坏 B 风险高；覆盖工具链/记忆/TokenStore 等核心逻辑，2026-08-31 列入）
- [ ] MainActivity 再拆分（已从 4241 行拆到 2552 行，仍偏大；进一步抽 UI/逻辑到独立文件，2026-08-31 列入）
- [ ] P0 消息列表 RecyclerView 改造（长会话卡顿根因：ScrollView 气泡渲染不回收，消息多时全量 Measure/布局；改为 RecyclerView 回收复用，2026-09-13 列入，约 2-3 天工作量）
- [x] DebugServer chat 并发竞态修复（2026-09-13 压测发现并修复：chatLock CAS 互斥，多客户端同时 /v1/chat 不再串话，并发压测验证通过）
- [x] 压测遗留：DebugServer 吞吐上限（2026-09-13 压测结论：50 并发约 278 RPS 封顶；已通过 keep-alive 复用连接优化）
- [x] browser_scan 异步回填竞态修复（2026-09-13 压测发现并修复：scan 端点改同步等待 JS 回填（scanSync 复用 CountDownLatch），同页连续 20 次 scan 元素数稳定 24 无 0 回退）
- [x] browser open 页面就绪等待（2026-09-13 压测发现并修复：open 增加 waitLoaded+loadDoneLatch 等待 onPageFinished，连续 open 各页元素数正常无串页）
- [ ] 真机验证待确认（2026-09-13 记录）：workdir_grep/head/stats 真机行为、site_auth Cookie 回灌、中文搜索弹窗、ssh_upload/download、开源许可证扫描
## 已完成里程碑
- 2026-09-13: 三处并发/时序 bug 修复并真机验证闭环（DebugServer chat 互斥、browser_scan 同步回填、browser open 就绪等待）+ DebugServer keep-alive 吞吐优化
- 2026-09-13: v2.0 正式签名发布（versionCode 30，自建 keystore 接入 release signingConfig，密钥异地备份）+ 版本号动态化修复更新检测
- 2026-09-11: v1.3（versionCode 23）+ 开源准备收口（浏览器模块与 Markdown 渲染源码入库、脱敏内网 IP、清理备份/补丁脚本）+ 开发者文档（docs/README + ADR 13 条）
- 2026-08-28: 模型能力表（ApiConfig.modelCapabilities）：预设=内置表(MiMo全模态/DeepSeek文本+工具/GLM文本+工具)+名称兜底；自定义模型编辑页新增能力勾选(图片/视频/音频/工具调用，默认文本+工具)
- 2026-08-28: 新增 web_search 工具（Bing cn.bing.com 桌面UA 解析 b_algo，无 key），清单首位 web_search→web_fetch→get_time→calc→memory_search→ssh/file
- 2026-08-28: 工具清单调整（web_fetch 前置、冷门 ssh/file 后置）+ 删除 memory_save（MemoryKeeper 全量自动归档取代）+ "记住"语义即时归档（MemoryKeeper.push force）
- 2026-08-27/28: 关于页改造（品牌区/博客链接/数据项文案）+ UpdateChecker 改跳 GitHub Releases + 仓库描述统一"记忆自托管·模型按需选·开源·免Root"
- v0.3: SSH 双认证 + ED25519 修复
- v0.4: 本地记忆（bge 语义检索 + 关键词双路召回）
- v0.5: 远程文件系统工具（list/read/write/info）
- v0.5.1: 工具调用格式容错 + ~ 展开 + 连接名模糊匹配
- v0.6: 多工具调用循环（上限 5 轮）
