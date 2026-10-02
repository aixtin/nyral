# Nyral 待办

> 由 Marvis 记录，2026-08-24 添加

## 待办
- [x] 文件/图片/视频发送功能（MiMo 多模态）：选附件 → 本地压缩 → 复制 cacheDir → Base64 发 MiMo → AI 生成描述占位进历史/记忆
      - 处理路径分类定案(2026-08-28)：文本/PDF 本地处理不走模型；图片/视频/音频 必须走模型（多模态）
      - PDF 本地解析提取文本(接解析库)：发 PDF 不用喂视觉模型，省 token
- [x] LICENSE（MIT）与 README 已补齐（2026-08-31）
- [x] GitHub Release 发布（v1.3/v2.0 已发布，附正式签名 APK，更新弹窗已生效；2026-09-13）
- [ ] UI 优化（聊天界面、SSH 配置界面视觉与交互）
- [x] 整理上下文：对话上下文分级管理（短期滚动 / 中期摘要 / 长期按需检索），解决 token 无限累积（ADR-009 已落地）
- [ ] 补充基础单元测试（零测试是最大隐患，修 A 坏 B 风险高；覆盖工具链/记忆/TokenStore 等核心逻辑，2026-08-31 列入）
- [ ] MainActivity 再拆分（已从 4241 行拆到 2552 行，仍偏大；进一步抽 UI/逻辑到独立文件，2026-08-31 列入）
- [x] P0 消息列表 RecyclerView 改造（长会话卡顿根因：ScrollView 气泡渲染不回收，消息多时全量 Measure/布局；2026-09-27 以形态池化 + AiRich 容器池化 + flush 分批 + 漂移补偿落地，随 v2.0 发布）
- [x] DebugServer chat 并发竞态修复（2026-09-13 压测发现并修复：chatLock CAS 互斥，多客户端同时 /v1/chat 不再串话，并发压测验证通过）
- [x] 压测遗留：DebugServer 吞吐上限（2026-09-13 压测结论：50 并发约 278 RPS 封顶；已通过 keep-alive 复用连接优化）
- [x] browser_scan 异步回填竞态修复（2026-09-13 压测发现并修复：scan 端点改同步等待 JS 回填（scanSync 复用 CountDownLatch），同页连续 20 次 scan 元素数稳定 24 无 0 回退）
- [x] browser open 页面就绪等待（2026-09-13 压测发现并修复：open 增加 waitLoaded+loadDoneLatch 等待 onPageFinished，连续 open 各页元素数正常无串页）
- [x] UpdateChecker NPE 修复（2026-09-13，commit af7135a：更新检查空指针防护）
- [x] 发视频 OOM 修复（2026-09-13：MediaFileUtils.readAll 改 8KB 分块流式 + MAX_VIDEO_BYTES 大小上限 + VideoCompressor 转码降内存）
- [ ] 真机验证待确认（2026-09-13 记录）：workdir_grep/head/stats 真机行为、site_auth Cookie 回灌、中文搜索弹窗、ssh_upload/download、开源许可证扫描
- 2026-09-14: 工具自热度排序落地（用户拍板先做自热度/手动以后再说；新增 ToolHotStore 本地热度统计+衰减、buildToolsArray/hotToolIndex 按热度排序、冷门工具描述压缩不真删；versionCode 31 装机真机验证通过）
- 2026-09-14: root 自动补齐自身权限（RootCheck.grantSelf：pm grant 运行时权限 + appops set 特殊权限；探测到 root 授权后首次进入权限页自动补齐通知/麦克风/悬浮窗/所有文件访问/安装未知应用，尽力而为失败保持手动入口；双页真机验证 6 项全绿）
## 已完成里程碑
- 2026-10-02: v2.1.1 发布（versionCode 34，GitHub Release v2.1.1 附正式签名 APK）：表格单元格垂直居中修复（聊天/Agent 双模式一致）+ 慢放迁移调试弹窗 + 浏览器面板按钮 + 聊天模式气泡宽度恢复对称
- 2026-09-25: 状态行覆盖式下拉面板（窗帘式挂 chatArea + ScrollView + 动画代际 token 防连点竞态）+ 思考气泡空气泡根治（协议前缀三处兜底）+ 时间线竖线圆点对齐/💭🔧 图标替换 + 思考计数竖排修复
- 2026-09-27: 首启闪退修复（markwon 并发 CME 加锁串行化）+ 上滑跳闪治理（watcher 去重 + 注册时机修正）+ 停止按钮 Lucide 化 + 全局死代码清理
- 2026-09-28: rendered 落库基建阶段1（MemoryDb rendered 列 + RenderedCodec Spanned↔JSON + mdCache→rendered→现场渲染三级读取 + 全链路 writeback，ADR-015）
- 2026-09-29: 引用块竖线/分割线渲染（QuoteBarSpan/HrSpan，commit 4b175bd 阶段6）+ 表格首列整格空白修复（collectCellText 递归，669dea9）+ 表格横滑 v3 公共版（TableScrollWrap）+ 整表统一列宽（colMaxChars v7）
- 2026-09-30~10-01: 表格样式系列（框线 PRIMARY/最低宽对齐/顶边封顶/单元格内边距）+ 表格方案 B 边框修复（availW/列竖线/行线调浅）+ DebugServer 8765 直发验证链路（WireGuard 直连手机）
- 2026-10-02: v2.1 表格渲染方案 C 落地（versionCode 33）：MdTableView 重写为气泡内独立 TableView 块化、WRAP_CONTENT 自适应、修复含行内代码单元格整格空白、清理旧表格类（ADR-014）
- 2026-09-29: 开源收口（chore 安全加固与清理 9150c73）+ targetSdk/compileSdk 升 36（AGP 8.13.0 + Gradle 8.13，为 Android 16 Live Updates 接入准备，versionCode 32）
- 2026-09-27/28: D 路线实时 Markdown 渲染落地（MdSpans/MdBlocks/MdStreamRenderer 第1-5步）+ 消息列表滑动丝滑优化（RecyclerView 形态池化/AiRich 容器池化/flush 分批/漂移补偿，池化回归修复多轮）+ 行动轨道原型 + 慢放体系 + 键盘/表情/输入框三层联动修复
- 2026-09-13: 三处并发/时序 bug 修复并真机验证闭环（DebugServer chat 互斥、browser_scan 同步回填、browser open 就绪等待）+ DebugServer keep-alive 吞吐优化 + UpdateChecker NPE 修复（af7135a）+ 发视频 OOM 修复（流式读取+大小上限+VideoCompressor）
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
