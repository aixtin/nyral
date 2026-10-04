<div align="center">

[English](SECURITY.md) | [中文](SECURITY.zh-CN.md)

</div>

---

# 安全与权限说明

Nyral 将设备控制能力（SSH、Shell、WebView 操作、无障碍控制）与本地记忆放进一个 App，因此安全设计是核心部分。本文档说明实际的安全机制、权限清单与数据边界，供使用者自行评估信任成本。

## 安全设计

### 本地调试服务（DebugServer）
DebugServer 提供屏幕截图、模拟点击/输入、WebView JS 执行、Agent 链路调用等调试能力，防护如下：

- **release 构建不启动**：运行时检查 `ApplicationInfo.FLAG_DEBUGGABLE`，非 debuggable 直接返回
- **默认关闭**：需连点版本号 7 次解锁设置页入口
- **默认仅绑定 127.0.0.1**：仅本机 / adb forward 可访问；"局域网访问"需手动开启，开启时打醒目告警日志
- **鉴权**：仅接受 `X-Auth-Token` 请求头（query 参数形式已移除，防经代理/日志泄露）
- **非本机来源连接**打告警日志

### 危险操作门禁
- 默认 `auto` 档：`ssh_run` / `sh_run` / `js_run` / `browser_eval` / `web_download` / 文件写入 / 浏览器点击输入 / 第三方 App 控制列为 **HIGH**，执行前弹确认
- `security_set` 为 **CRITICAL**（配置变更类）
- 可切 `strict` 档：每次都确认、禁用 5 分钟票据复用
- root 自动提权默认关闭

### 其他
- SSH 主机密钥校验：未知主机拒绝连接
- 危险命令拦截（sh_run）+ 沙箱执行（js_run ClassShutter）

## 权限清单

### Manifest 静态声明

| 权限 | 用途 |
|---|---|
| INTERNET | 联网（模型 API / SSH / 更新检查） |
| RECORD_AUDIO | 语音输入 |
| REQUEST_INSTALL_PACKAGES | 自更新安装 APK |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC | 前台服务保活 |
| POST_NOTIFICATIONS | 通知 |
| POST_PROMOTED_NOTIFICATIONS | Android 15+ 推广类通知 |
| SYSTEM_ALERT_WINDOW | 悬浮窗 |
| MANAGE_EXTERNAL_STORAGE | 所有文件访问 |
| WRITE_EXTERNAL_STORAGE（maxSdk 28） | API<29 老设备写公共目录 |

### 运行时动态请求（5 类）
通知、麦克风、悬浮窗、所有文件访问、安装未知应用。其中后三项为特殊授权；启用 root 自动补齐时通过 `pm grant` + `appops set` 自动完成，默认关闭。

## 数据边界

- **仅存本机**：对话记录、长期记忆、Token 统计、SSH 配置（加密存储）
- **出设备数据**：对话消息、附件及解析文本会发送到**你配置的模型端点**（OpenAI 兼容）。如需完全私密，请自建端点
- **不采集遥测**；启动时的唯一外部请求是 GitHub Release 更新检查（404 可忽略）

## 使用建议

1. 优先在备用机上安装，使用 **Release 签名包**（不要使用 debug 构建，debug 构建可开启 DebugServer）
2. 门禁设为 `strict`，root 自动提权保持关闭
3. SSH 只配专用低权限账号，不指向生产机；API Key 使用带额度上限的
4. 分阶段放权：先用 web_search / 文档解析 / 本地记忆等只读能力，稳定后再考虑 sh_run 与 App 控制

## 漏洞报告

发现安全问题请通过 [GitHub Issues](https://github.com/aixtin/nyral/issues) 提交（可注明 SECURITY 前缀）；敏感细节建议先通过仓库主页联系方式私信沟通。
