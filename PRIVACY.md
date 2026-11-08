# Privacy Policy

Last updated: 2026-11-08

Nyral ("the App") is developed by aixtin. This document describes what data the App stores, what it sends over the network, and how you can control your data.

## 1. Data Stored Locally

All personal data stays on your device by default. The App uses a local database (`memory.db`, SQLite) to store:

- Your conversation history and session metadata.
- Local memory entries used to improve continuity across sessions.
- Local logs used for troubleshooting.

Other local data includes:

- Attachments and files you manage through the App (stored under app-managed storage).
- App preferences and settings (provider configuration, UI options, etc.).

The App also bundles an on-device text embedding model. Text vectorization happens entirely on your device; no text is sent to a remote service for embedding.

## 2. Data Sent Over the Network

The App does not operate its own cloud service. It only connects to the destinations described below:

### 2.1 Model Service Provider

To generate responses, the App sends your conversation content (the messages you send, plus relevant recent context) to the model service provider you choose. By default this is **DeepSeek** (`api.deepseek.com`). You can switch to other built-in providers (e.g. Zhipu GLM, Xiaomi MiMo) or configure a custom endpoint in Settings.

- What is transmitted: the text of your messages and the immediate conversation context needed to produce a reply.
- What is not transmitted: other local data (attachments, files, logs, preferences) is not uploaded to the provider.
- Please review the provider's own privacy policy for how it handles your data. DeepSeek's policy is available at <https://platform.deepseek.com/>.

### 2.2 Update Check

The App periodically checks the official GitHub repository (`github.com/aixtin/nyral`) for new releases. This request only sends basic HTTP metadata and does not include personal data.

### 2.3 Features You Explicitly Use

- **Browser component**: when you use the built-in browser, it loads the pages you open, and requests follow the same network rules as a normal browser (including cookies for the sites you visit).
- **MCP servers**: if you enable MCP servers, the App connects to the addresses you configured. No data is sent to an MCP server unless you actively use it.
- **Image generation (AI image tools)**: when you use the image generation feature, the prompt text and generation settings you provide are sent to the image provider you configured (built-in: Zhipu GLM-Image/CogView at open.bigmodel.cn, Volcano Ark Seedream at ark.cn-beijing.volces.com; or any custom OpenAI-compatible endpoint). Only the data needed to generate the image is transmitted; other local data stays on your device.
- **Remote debug (Developer mode)**: the DebugServer is disabled by default; when enabled on your local network, it exposes debugging interfaces only on your LAN.

## 3. Permissions

| Permission | Purpose |
|---|---|
| Internet | Model service requests, update checks, browser and MCP access |
| Microphone | Voice input for messages |
| Storage / Media | Managing files and attachments you choose to open or save |
| Overlay | Floating window feature (e.g. browser overlay) |
| Install packages | Installing APK files you explicitly select |
| Notifications | Showing message and download notifications |

Permissions are only used for the features described above.

## 4. Analytics, Advertising and Diagnostics

The App does **not** include third-party analytics, advertising, or behavioral-tracking SDKs, and has no account system. No usage statistics are collected. The only outbound diagnostics are the anonymous crash reporting and the Liveness Pulse described below.

### Crash Reporting (on by default, user can disable)

To help locate and fix crashes, the App writes a local log when a crash / ANR / unexplained exit occurs, and anonymously uploads it over HTTPS on the next cold start to the developer's own server (atin.asia/crash). The payload contains only device model, Android version, crash stack, and the latest 30 runtime log lines — never chat content, account info, or any personally identifiable data. This is the only data the App sends beyond what you explicitly configure (model endpoint, MCP servers, browser). It is **enabled by default and can be turned off anytime**: Appearance Settings → Quick Switches → Crash Reporting. Reports are stored on the developer's server for up to 30 days and are automatically purged afterwards; the server also enforces per-day and total storage quotas to prevent abuse. Crash data is never sold, shared, or used for profiling.

### Liveness Pulse and Suggestions (same switch)

After an app upgrade the App may show a one-time 「检测活人」 card. Tapping the confirm button sends a single anonymous pulse to the same atin.asia/crash endpoint: `type: PULSE`, app version name/code, Android version, timestamp, and the per-install random ID that is also sent in the `X-Install-Id` header. Tapping Cancel sends nothing, and the card is not shown again for that version. Confirming then opens an optional suggestion box: only the text you type is uploaded, only when you tap Submit (`type: FEEDBACK`, at most 500 characters). Fresh installs and same-version launches never show the card, and both features are disabled together with Crash Reporting.


## 5. Data Control and Deletion

- You can delete individual conversations, clear logs, or clear all local data from within the App.
- Uninstalling the App removes all local data it created.
- Data already transmitted to a model provider is governed by that provider's own policy and cannot be withdrawn by uninstalling the App.

## 6. Contact

For questions about this policy, contact: aixtin <https://atin.asia>
