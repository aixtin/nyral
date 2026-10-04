<div align="center">

[English](SECURITY.md) | [中文](SECURITY.zh-CN.md)

</div>

---

# Security & Permissions

Nyral bundles device-control capabilities (SSH, shell, WebView automation, accessibility control) with local memory in one app, so security design is a core concern. This document describes the actual security mechanisms, the permission list, and the data boundaries, so you can assess the trust cost yourself.

## Security Design

### Local Debug Server (DebugServer)
DebugServer provides debugging capabilities: screenshots, simulated taps/input, WebView JS execution, and agent-pipeline calls. Protections:

- **Not started in release builds**: runtime check of `ApplicationInfo.FLAG_DEBUGGABLE`; returns immediately when not debuggable
- **Disabled by default**: requires tapping the version number 7 times to unlock the settings entry
- **Binds to 127.0.0.1 by default**: only the device itself / `adb forward` can reach it; "LAN access" must be enabled manually and logs a prominent warning when on
- **Authentication**: only the `X-Auth-Token` request header is accepted (the query-parameter form was removed to prevent leaks via proxies/logs)
- **Non-local connections** log warnings

### Dangerous-Operation Gate
- Default `auto` tier: `ssh_run` / `sh_run` / `js_run` / `browser_eval` / `web_download` / file writes / browser click-input / third-party app control are rated **HIGH** and require confirmation before execution
- `security_set` is **CRITICAL** (configuration-change class)
- `strict` tier available: confirm every time, disables the 5-minute ticket reuse
- Root auto-elevation is off by default

### Other
- SSH host-key verification: unknown hosts are rejected
- Dangerous-command interception (sh_run) + sandboxed execution (js_run ClassShutter)

## Permission List

### Manifest static declarations

| Permission | Purpose |
|---|---|
| INTERNET | Networking (model API / SSH / update check) |
| RECORD_AUDIO | Voice input |
| REQUEST_INSTALL_PACKAGES | Self-update APK install |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC | Foreground-service keep-alive |
| POST_NOTIFICATIONS | Notifications |
| POST_PROMOTED_NOTIFICATIONS | Android 15+ promoted notifications |
| SYSTEM_ALERT_WINDOW | Overlay window |
| MANAGE_EXTERNAL_STORAGE | All-files access |
| WRITE_EXTERNAL_STORAGE (maxSdk 28) | Public-dir writes on pre-API-29 devices |

### Runtime dynamic requests (5 kinds)
Notifications, microphone, overlay window, all-files access, install unknown apps. The last three are special authorizations; when root auto-elevation is enabled they are granted automatically via `pm grant` + `appops set` — off by default.

## Data Boundaries

- **On-device only**: conversation history, long-term memory, token stats, SSH config (encrypted storage)
- **Leaves the device**: chat messages, attachments, and parsed text are sent to **the model endpoint you configure** (OpenAI-compatible). Self-host an endpoint for full privacy
- **No telemetry**; the only startup external request is the GitHub Release update check (404 ignorable)

## Recommendations

1. Install on a spare device first, using the **Release-signed build** (avoid debug builds — debug builds can enable DebugServer)
2. Set the gate to `strict`; keep root auto-elevation off
3. SSH: use dedicated low-privilege accounts, never point at production machines; use API keys with spending caps
4. Grant capabilities gradually: start with read-only abilities (web_search / document parsing / local memory), then consider sh_run and app control once stable

## Vulnerability Reporting

Report security issues via [GitHub Issues](https://github.com/aixtin/nyral/issues) (prefix the title with `SECURITY`); for sensitive details, prefer reaching out privately through the contact info on the repository homepage.
