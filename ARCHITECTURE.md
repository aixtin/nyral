# ARCHITECTURE.md — Nyral 架构说明

> 记录 Nyral 当前架构、大文件治理进度与遗留耦合，供接手者快速定位。

## 1. 顶层结构

- **入口**：`MainActivity`（Android Activity，当前 ~4470 行，最大的上帝文件）
- **Web 容器**：`BrowserPage` 承载引擎、JS 桥、工具栏、气泡 UI（拆刀后 445 行）
- **域模块**：`app/src/main/java/all/`，按功能域拆出的同包文件（扩展函数 + 放宽 internal）
- **工具底座**：`ToolExecutor` / `LocalEngine`（~1309 行）/ `UiKit`（~1071 行）/ `DebugServer`（~1073 行）/ `AiBubbleHolder`（~1748 行）/ `MainUi`（~1158 行）等
- **数据/密钥**：`ApiConfig.kt` 等，权限 600，不外发

## 2. BrowserPage 模块（7 域文件 + 主类）

BrowserPage 经 8 刀从 1796 行拆至 146 行，主类保留"壳 + 生命周期 + 状态持有"，功能外移到 `all/`：

| 文件 | 职责 | 规模 |
|---|---|---|
| `BrowserPage.kt`（主类） | 状态字段、生命周期、init 粘合调用 | 146 行 |
| `BrowserPageInitWeb.kt` | init 域：WebView 配置 + Client + JS 桥注册 + 滚动高亮跟随（initWebViews） | 81 行 |
| `BrowserPageRootUi.kt` | init 域：root 圆角容器 + takeover + 汉堡面板（initRootUi） | 70 行 |
| `BrowserPageScan.kt` | 扫描注入域：injectScanner（元素收集 JS） | 114 行 |
| `BrowserPageJsBridge.kt` | JS 桥独立类：onElements/onActionResult/onPageText + 本地页跳转（持 page 引用） | 83 行 |
| `BrowserPageEngines.kt` | 引擎域（LocalEngine 交互、load 流程、loadEngines/refreshDrawerUrl） | ~396 行 |
| `BrowserPageHamburger.kt` | 汉堡菜单域（菜单弹层、选项动作） | ~247 行 |
| `BrowserPageSync.kt` | 同步域（书签/历史同步链路、open/tryAutoSaveCookie） | - |
| `BrowserPageEngineMeta.kt` | 引擎辅助元信息（65 行 / 3 函数） | 65 行 |
| `BrowserPageThinkUi.kt` | 气泡 UI 域（124 行 / 4 函数） | 124 行 |
| `BrowserPageHighlightNav.kt` | 高亮导航域（74 行 / 3 函数） | 74 行 |

拆分约定见 `AGENTS.md` §3：同包 `internal fun BrowserPage.xxx()` 扩展函数，宿主 private 成员放宽 internal，备份放工程根 `backups_split_bp*`。

## 3. 核心数据流

### 3.1 JS 桥 → 元素 → 高亮链路

```
网页 JS 注入
   │ (JSBridge 调用)
   ▼
JS 桥对象（BrowserPage 内注入，injectScanner 区域）
   │ onElements
   ▼
elements 集合（域状态，类内字段）
   │ 扫描结果分发
   ▼
BrowserPageHighlightNav（高亮导航域）
   │
   ▼
UI 高亮 / 导航动作
```

### 3.2 页面加载与同步

```
BrowserPageEngines.load(url)
   │
   ▼
WebView load + loadDoneLatch（类内 latch，未提取）
   │ 页面就绪信号
   ▼
BrowserPageSync（同步域）
   ▼
书签 / 历史同步
```

## 4. 大文件治理进度

| 文件 | 当前规模 | 状态 |
|---|---|---|
| MainActivity.kt | ~4470 行 | 待治理（下一目标） |
| AiBubbleHolder.kt | ~1748 行 | 待治理 |
| LocalEngine.kt | ~1309 行 | 待治理 |
| DebugServer.kt | ~1073 行 | 待治理 |
| UiKit.kt | ~1071 行 | 待治理 |
| MainUi.kt | ~1158 行 | 待治理 |
| BrowserPage.kt | 146 行（原 1796） | 已拆 8 刀，深水区清零 |

## 5. 遗留耦合与待办

### 5.1 BrowserPage 剩余深水区（拆分时机 Agent 自定）

1. **init 块（约 168-285 行）**：WebViewClient / ChromeClient 配置 + JS 桥注册，改动面大
2. **injectScanner + JS 桥对象（约 374-555 行）**：Scanner 注入与桥对象本体，强依赖 WebView 上下文

拆这两块需先梳理 JS 桥与 elements/高亮的完整依赖面，再按 AGENTS.md §3 套路走；属高改动风险区，建议单刀只拆一块、四关验证。

### 5.2 跨文件耦合

- **BrowserPage ↔ MainActivity 强耦合**：两端互调大量方法/字段，后续需抽象接口或事件总线解耦
- **桥对象 ↔ 域状态**：JS 桥回调直接操作类内 elements / 高亮状态，拆分时需保留接收者语义

## 6. 运维速查

- 装机链路：有线 `<DEVICE_SERIAL>`；无线 188 局域网 `~/adb-hotspot.sh`；WireGuard `<WG_PHONE_IP>:5555`
- 备份目录：工程根 `backups_split_*`（勿移入 `app/src/main/java`）
- 四关验证：compileDebugKotlin → testDebugUnitTest → assembleDebug → 真机 install
