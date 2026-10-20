# Nyral 发布流程（Release Process）

> 适用仓库：aixtin/nyral　　本文档是发版的唯一操作手册。
> 原则：机器只认两个版本号来源 —— `app/build.gradle.kts` 与 GitHub tag；其余全是文档，漏改不会报错，只会慢慢烂掉。

---

## 0. 版本号规则（定死，不再讨论）

| 项 | 位置 | 规则 |
|----|------|------|
| `versionCode` | `app/build.gradle.kts` → `defaultConfig` | 整数，**严格单调递增**，发一次版加 1，永不回退、永不复用 |
| `versionName` | 同上 | `X.Y.Z` 语义化，只增不减 |
| Git tag | GitHub 仓库 | `vX.Y.Z`，**必须与 versionName 逐字符一致** |
| Release 资产名 | GitHub Release | `nyral-vX.Y.Z.apk`（正式签名包） |

命名含义约定：
- 第三位（Z，补丁）：只修 bug、改文案、调渲染，无新功能
- 第二位（Y，次版本）：新增功能或架构调整（如 minSdk 变更、表格方案换代）
- 第一位（X，大版本）：破坏性变更（数据格式不兼容、需要用户迁移）

**为什么 tag 必须规范**：`UpdateChecker` 请求 `/releases/latest` 的 `tag_name` 与本地 `versionName` 比大小来决定是否弹更新窗。tag 格式乱、非递增、或漏打，更新提示要么静默失效，要么对老版本乱提示。

**UpdateChecker 比较机制（必须锁死）**：`tag_name` 与 `versionName` 的比较必须是语义化数字比较（拆 `X.Y.Z` 逐段转 int 比较），**禁止字符串比较**——字符串序下 `v2.3.10` 会小于 `v2.3.9`，大补丁号会被判定为降级，更新提示静默失效。此断言写入单测（UpdateCheckerTest），发版流程依赖它成立。

---

## 1. 发版前（代码冻结）

- [ ] 过一遍《规范_开源平台发布检查清单》：硬敏感（凭据/密钥/公网 IP/序列号/debug token/第三方品牌词）0 命中，软敏感（内部代号/拓扑词）人工抽查通过
- [ ] 工作区干净：`git status` 无未提交改动
- [ ] `./gradlew :app:testDebugUnitTest` 本地全绿（CI 同此命令）
- [ ] `./gradlew :app:lintDebug` 无新增 error（存量 error 冻在 `app/lint-baseline.xml`，**只准减不准增**；新增问题必须修掉，不要往 baseline 里塞）
- [ ] `./gradlew :app:assembleDebug` 编译通过
- [ ] 真机冒烟：冷启动 → 发一条消息（流式 + 工具调用都跑一次）→ 设置页各入口能进 → 更新检查不误报
- [ ] 若本次动了这些区域，另做专项验证：
  - 记忆链路 → 归档一条、搜一次、杀进程后 pending 不丢
  - SSH → 连接 + 上传/下载 + 未知主机拒绝
  - 附件 → 图片/视频/PDF 各发一个
  - 无障碍 app 工具 → scan/click 一次
- [ ] 涉及发版的性能项留人工采样：冷启动时间、常驻内存、记忆召回延迟（nightly 只跑模拟器基础档，重档不覆盖）

---

## 2. 构建正式签名包

签名材料本地不入库（`keystore.properties` 与 keystore 均在 `.gitignore`）：

```bash
# 本地：仓库根目录即工程目录
./gradlew clean
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

- [ ] 产物改名为 `nyral-vX.Y.Z.apk`
- [ ] 记录 SHA256：`sha256sum nyral-vX.Y.Z.apk`（Release notes 里要写）
- [ ] 校验签名：`apksigner verify --print-certs` 输出与上次发版同一证书指纹（**换签名 = 老用户无法覆盖安装**）

CI 也会产出签名包（`.github/workflows/android.yml` 的 `build-release`，仅 push 触发、PR 不跑），签名材料从 Secrets 读：`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEYSTORE_ALIAS`。但**发版以本地构建的包为准**，CI 包仅作校验用途——避免两次构建产物不一致。

---

## 3. 必改清单（每次发版都改）

### 3.1 代码里的版本号
- [ ] `app/build.gradle.kts`：`versionCode` +1、`versionName` 改新值 —— **第一个改的地方**

### 3.2 GitHub 侧
- [ ] 提交并推送：`git add -A && git commit -m "release: vX.Y.Z" && git push`
- [ ] 打 tag 并推送：`git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z`（**tag 与 versionName 必须一致**）
- [ ] 新建 Release：选中上面那个 tag，标题 `vX.Y.Z`，正文写变更要点 + 完整 SHA256，上传 `nyral-vX.Y.Z.apk`
- [ ] 三个不许犯的错：
  1. 不许存成 **Draft** —— `/releases/latest` 不返回草稿，全体用户更新检查静默失效
  2. 不许勾 **Pre-release** —— 同上
  3. 不许传错资产名或压缩包（必须是可直接安装的 .apk）

### 3.3 双 README（两份都要，只改中文版是高频事故）
- [ ] `README.md`（英）
- [ ] `README.zh-CN.md`（中）
  - [ ] 版本记录/更新日志顶部追加一条：版本 + 日期 + versionCode + 变更要点
  - [ ] 路线图（Roadmap）里本次完成的项打勾
  - [ ] 「系统要求」段有变化就改（**本次 minSdk 24→29 必须改**：现在写的是「Android 7.0+（minSdk 24），完整功能需 Android 10+」，要改成单一说法「Android 10 及以上」，不要再留「能装但功能残缺」的中间态）
  - [ ] 构建命令段核对（已知问题：README.zh-CN 里的 `cd nyral/android-agent-app` 是错路径，仓库根目录即工程目录，顺便修掉）

### 3.4 docs/README.md（开发者文档）
- [ ] 「构建 → 前置要求」表里的「目标设备 Android 7.0+（minSdk 24）」→ 改新值
- [ ] 「版本号：……（当前 v2.1.1 / versionCode 34）」→ 改新值（**这行现在就是过期的，本次务必同步**）
- [ ] 发布流程节回顾一遍，与本文档保持一致
- [ ] 模块清单表：本次新增/改名/删除的 Kotlin 文件补进对应表格
- [ ] 文档索引里 ADR 条数（现写「15 条」）与实际一致
- [ ] 顺手修：docs/README 写「Gradle 无 wrapper，需本机系统 Gradle」，而 README 写「已内置 gradlew（锁定 8.13）」——两处矛盾，统一为「仓库已含 gradlew，也支持本机 Gradle 8.13」

### 3.5 TODO.md
- [ ] 本次完成的待办项打勾
- [ ] 「已完成里程碑」顶部追加一行：日期 + 事件 + versionCode
- [ ] 别断档——这是唯一的跨版本追溯台账

### 3.6 仓库首页元信息（顺手扫一眼）
- [ ] About 描述、Topics、Homepage 链接是否仍准确（如描述里还提不提「免 Root」、支持版本口径）

---

## 4. 条件清单（涉及什么改什么）

| 触发条件 | 要改的东西 |
|----------|-----------|
| 有架构级决策 | `docs/ADR-架构决策记录.md` 追加 ADR-0xx（背景 → 方案 → 取舍 → 结论）+ 同步 docs/README 的 ADR 索引表 |
| 安全相关变更（门禁/沙箱/密钥处理/支持版本） | `SECURITY.md` + `SECURITY.zh-CN.md`。**本次 minSdk 变更动到「支持版本」，两张都看** |
| 数据边界、权限、对外请求有变化 | `PRIVACY.md`（新增联网端点、遥测、权限都必须在此体现） |
| 新增/移除第三方依赖 | README 许可证表 + `.github/ci/license-check.py` 白名单，两者必须对得上（CI 会卡） |
| 新增文件/模块 | `ARCHITECTURE.md`、`AGENTS.md`、docs/README 模块表 |
| 文案硬编码了版本或安装提示 | `app/src/main/res/values/strings.xml`（AboutActivity 版本号是从 PackageManager 动态读的，不用管） |
| 支持版本变化影响 CI | `.github/workflows/*.yml`：nightly 的 `api-level: 34` 模拟器若要跟随新下限再调；常规 push/PR CI 的矩阵一般不动 |
| 构建工具链变化（JDK/Gradle/SDK） | 根 `build.gradle.kts`、`gradle/wrapper/gradle-wrapper.properties`、README 构建段、CI 的 `java-version` |

---

## 5. 发布后验证（15 分钟）

- [ ] 前台仓库页「Latest release」显示的就是刚发的版本，**不是 draft、不是 pre-release**
- [ ] Release 资产能下载，文件名 `nyral-vX.Y.Z.apk`，SHA256 与本地一致
- [ ] 用**上一个版本**的机器打开 App → 更新检查能弹窗 → 指向新版本
- [ ] 新版本安装后：冷启动 → 发消息 → 工具调用 → 设置页，四项正常
- [ ] 老版本覆盖安装不报「签名冲突」，应用数据保留（记忆、会话、SSH 配置还在）
- [ ] CI 三条（unit-tests / lint / build-debug）在该 commit 上全绿

---

## 6. 出问题时

- **包已上传但发现严重 bug**：不要删 Release 重新传同名 tag 覆盖安装包（用户下载的 hash 会对不上）。正确做法：保留病版本 Release，把 `latest` 指向其前一个**正常 Release**（可在 Releases 页把病版本改为 Pre-release，`/releases/latest` 就会跳过它），然后立刻发补丁版 `X.Y.Z+1`。
- **tag 打错了**：`git push origin :refs/tags/vX.Y.Z` 删远端 tag，本地 `git tag -d`，改对再推。已建 Release 的先在页面删掉。
- **versionCode 忘了加**：安装会被系统拒绝覆盖（INSTALL_FAILED_VERSION_DOWNGRADE 的反向问题），必须重新构建、重新打 tag、重新发版，不能只改文档。
- **误把签名换了**：老用户只能卸载重装（数据丢失）。这是不可逆事故，发版前一定要核对证书指纹。

---

## 7. 固化到仓库（建议）

1. 把本文档放到仓库 `docs/RELEASE_PROCESS.md`，并在 `docs/README.md` 文档索引表加一行。
2. 在根 `README.md` / `README.zh-CN.md` 的「构建」节末尾加一句：`发布流程见 docs/RELEASE_PROCESS.md`。
3. （可选，收益高）把 §3 的必改清单做成 `scripts/release.sh`：读 versionName 参数 → 校验 versionCode 递增 → 改 build.gradle → 全量 grep 文档里的旧版本号并列出未同步文件 → 提示手动确认。这样以后漏改文档会当场被脚本点名，而不是等三个月后发现 docs 还停在 v2.1.1。

---

## 附录：本次（v2.3.5 → 下一版，含 minSdk 24→29）专属清单

- [ ] `app/build.gradle.kts`：minSdk 24 → 29，versionCode +1，versionName 更新
- [ ] `README.md` + `README.zh-CN.md`：系统要求段改为「Android 10 及以上」，删掉「Android 7.0+ / 完整功能 10+」的双口径
- [ ] `docs/README.md`：前置要求表「Android 7.0+（minSdk 24）」→ Android 10+；版本号行 v2.1.1/34 → 新值；修正 `cd nyral/android-agent-app` 错路径；统一 wrapper 口径
- [ ] `AGENTS.md`：真机验证矩阵里的设备版本描述同步
- [ ] `SECURITY.md` + `SECURITY.zh-CN.md`：支持版本口径同步
- [ ] 代码里 `Build.VERSION.*` 判断**保留不动**（同一套代码继续跑，只是不再为 <29 做验证与承诺）
- [ ] `strings.xml`：若有安装页/首启提示提到版本下限则改
- [ ] 本次无架构级决策 → 不加 ADR；依赖未变 → 许可证表不动
