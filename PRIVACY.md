# Privacy Policy

Last updated: 2026-10-05

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

## 4. Analytics and Advertising

The App does **not** include analytics, telemetry, advertising, or crash-reporting SDKs. No usage statistics are collected.

## 5. Data Control and Deletion

- You can delete individual conversations, clear logs, or clear all local data from within the App.
- Uninstalling the App removes all local data it created.
- Data already transmitted to a model provider is governed by that provider's own policy and cannot be withdrawn by uninstalling the App.

## 6. Contact

For questions about this policy, contact: aixtin <https://atin.asia>
