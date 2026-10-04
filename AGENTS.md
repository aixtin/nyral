# AGENTS.md — Nyral 工程协作说明书

> 面向 AI 助手 / 接手的开发者：在 Nyral 仓库里安全干活的第一份文档。
> 核心原则：**改源码前先备份，拆文件按套路走，四关验证必须全过。**

## 1. 工程概览

- 项目：Nyral 安卓 App（包名 `io.github.aixtin.nyral`），Kotlin 单模块，构建 gradle 8.13
- 源码：`app/src/main/java/`，业务模块文件集中在 `app/src/main/java/all/`（按功能域拆分，同包）
- 数据：`ApiConfig.kt` 等含密钥文件权限 600，严禁改动或外发
- 硬门禁：`SecurityConfig.kt` 阻塞式确认（弹窗即暂停，拒绝即停，允许才继续）；同参数别老弹窗

## 2. 构建与验证命令（四关）

在工程根 `/home/ymz/Nyral/android-agent-app/` 执行：

```bash
# 关1 编译（最快，改完先跑这个）
./gradlew :app:compileDebugKotlin --console=plain -q

# 关2 单测（当前 49/49 全绿，历史拆刀从未破坏）
./gradlew :app:testDebugUnitTest --console=plain

# 关3 APK
./gradlew :app:assembleDebug --console=plain

# 关4 真机装机（有线优先）
adb -s <DEVICE_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

链路：云端沙箱 → ssh VPS <VPS_PUBLIC_IP> → 2222 隧道 → 188（<WG_188_IP>）。188 上 adb 直连真机 `<DEVICE_SERIAL>`（有线）；无线调试走 WireGuard `<WG_PHONE_IP>:5555`。

## 3. 上帝文件拆分规范（本工程核心工作流）

目标：把几百上千行的"上帝文件"按功能域拆成 `all/` 下的小文件。模式固定为 **同包扩展函数 + 放宽 internal**，纯重排、不改变行为。

### 3.1 拆前必须备份

```bash
mkdir -p backups_split_<刀名>_$(date +%s)
cp app/src/main/java/all/Xxx.kt backups_split_<刀名>_$(date +%s)/
```

**备份目录必须放工程根**（`backups_split_*`），严禁放 `app/src/main/java` 内——否则 Kotlin 认为类重复声明，编译报 Redeclaration（反复踩过的坑）。

### 3.2 拆分套路（逐刀执行）

1. 选内聚、低耦的域：内部状态自洽、外部调用少的函数块优先
2. 新文件放 `all/XxxDomain.kt`，package 与宿主类同包
3. 函数签名改写：
   - `private fun zzz()` → `internal fun BrowserPage.zzz()`
   - `internal fun zzz()` → `internal fun BrowserPage.zzz()`（原本 internal 的类成员也**必须**补接收者前缀，漏加会 Unresolved）
4. 域引用的宿主类 private 字段 / 方法放宽为 `internal`；`private val act` 构造参数放宽 `internal val act`
5. 嵌套 `data class` 移为同包顶层 `internal`（顶层 private 等同文件私有，跨文件不可见）
6. `::lateinitVar.isInitialized` 类外访问不到 backing field：在原类加兼容 getter
   ```kotlin
   internal val xxxInitialized get() = ::xxx.isInitialized
   ```
   新文件改用该 getter
7. 域内字段声明（如 latch / 回调 / 缓存）**不提取**，留在类内放宽 internal 即可（顶层 var 会变全局变量，语义错误）
8. 提取块逐行去 4 空格缩进；Python 锚点 + 花括号配平定位域块，避免行号偏移

### 3.3 验证顺序（每刀必过四关）

编译 → 单测 → APK → 真机装机。任何一关失败：先查改动清单（备份 diff），严禁反复盲试。

### 3.4 进度参考

BrowserPage 已按此模式拆 8 刀（1796 → 146 行），产物 12 个域文件（含 init 域/扫描域/JS 桥独立类），每刀四关全绿。备份目录 `backups_split_bp2_*` ~ `backups_split_bp8_*` 保留在工程根。

## 4. 装机链路速查

| 场景 | 命令 |
|---|---|
| 188 经 VPS 回连 | `ssh -i <SSH_KEY_188> -p 2222 <USER>@127.0.0.1` |
| 有线真机 | `adb -s <DEVICE_SERIAL> install -r <apk>` |
| 无线（188 局域网） | `~/adb-hotspot.sh` 自动探测后 `adb install` |
| 无线（WireGuard） | VPS 上 `adb connect <WG_PHONE_IP>:5555` 后 install |
| 调试日志 | `adb logcat -d | tail -200`（统一走 VPS WireGuard） |

## 5. 约定红线

- 系统路径、密钥文件、`.git` 等敏感内容不碰
- 删除/覆盖前先备份；批量破坏性操作必须逐项确认
- 记忆库连接卡（记忆读写）见仓库外文档，不在此重复
