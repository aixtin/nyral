<div align="center">

[English](README.md) | [中文](README.zh-CN.md)

</div>

---

# Nyral

> Self-hosted memory · Bring your own model · Open source · No root

Nyral is an open-source AI agent assistant for Android: LLM chat, long-term local memory, SSH remote operations, web tools, and voice/attachment multimodality in a single app. Data is self-hosted; no root required; no dependency on any third-party server.

## Key Features

**Chat & Models**
- Multi-provider on-demand switching: MiMo full-modal / DeepSeek / GLM / any OpenAI-compatible endpoint; custom models with per-model capability toggles
- Streaming output: typewriter-style thinking segments + streaming Markdown body rendering (Markwon); thinking and tool-call sections collapsible
- In-message Markdown table rendering (Scheme C): independent block-level TableView inside bubbles, live layout during streaming, vertically centered cells, consistent across chat and agent modes
- Thinking intensity tiers: auto / on-off / low-mid-high shown per provider capability; unsupported tiers hidden
- Voice input: press-and-hold to speak, swipe-up to cancel, 60-second cap with auto-send; WeChat-style voice bubbles (PCM via AudioRecord, WAV-wrapped, compatible with mainstream multimodal APIs)

**Attachments & Multimodality (v19+)**
- Images: compressed locally (max 2048px long edge) then sent via `image_url`
- Videos: transcoded locally when >37MB, first-frame thumbnail embedded in bubble
- Documents (PDF/txt/md/docx/xlsx...): parsed locally, text injected straight into context without burning vision tokens; scanned PDFs auto-rendered to page images for the vision channel
- Up to 6 attachments per message; in-app fullscreen preview (image zoom / video play / PDF paging / text copy)

**Long-term Memory (self-hosted)**
- bge-small ONNX on-device semantic vectors + keyword LIKE dual-path retrieval, SQLite storage
- Three-tier layering: short-term rolling window → mid-term summaries (background compression via auxiliary model) → long-term full text + semantic search
- Optional separate "auxiliary AI" for memory indexing without consuming the main chat model
- Pending queue persisted + atomic consumption transactions: no lost or duplicated messages after process kill

**Tool Calling (40-round cap, 20 built-in tools across 8 shared bases)**
- Network: `web_search` (four-engine rotation: Sogou mobile → Bing RSS → Bing web → Baidu; direct access in mainland China, no key) / `web_fetch` / `web_download` / `site_auth` (per-domain cookie auto-injection)
- SSH: `ssh_run` / `file` (remote read/write/upload/download), proxy jumps (ProxyJump over JSch), ED25519 (BouncyCastle), dual auth
- Execution: `js_run` (sandboxed JS with ClassShutter class restrictions) / `sh_run` (local shell with dangerous-command interception + root elevation)
- Device: `app` (accessibility-driven control of third-party apps)
- Files: `workdir` (local workdir `Download/Nyral_work`: list/read/write/grep/head/stats; the AI pulls files locally, edits, and pushes them back — avoids SSH quoting hell)
- Attachments: `attach_read` / `video_frame` / `file_export`
- Memory: `memory_search` (semantic local search) / `get_time` / `calc` (Rhino interpreter)
- Security: `security_set` (dangerous-op confirmation / root permission grant / SSH trust switch)
- Browser (in-house agent-browser prototype): full-screen WebView takeover, AI step narration + highlight rings + one-tap CAPTCHA handoff to the user
- Meta tools: `ask_user` (interactive clarification) / `tool_detail` (tool documentation)

**Security**
- Secondary confirmation for dangerous operations: confirm-then-execute; same params exempted for 5 minutes; rejection stops immediately with a receipt; timeout auto-rejects; full audit trail (allow/deny/timeout) persisted
- SSRF protection (redirect-target validation for web_fetch/web_download) + log redaction (non-2xx API response bodies and SSH private-key contents never logged in plaintext)
- Sandboxed execution (js_run ClassShutter) + dangerous-command interception (sh_run) + SSH host-key verification (unknown hosts rejected)

**UI**
- Multi-session management (pin/delete/search), full-text session search (body + thinking, relevance-ranked)
- Token usage stats (main AI / auxiliary AI dual channels; cumulative / daily / per-session)
- Runtime logs (main + memory archive), foreground-service keep-alive notification
- Chat backgrounds (preset gradients / custom image blur), global animation system (bubble entrance / popup jelly expansion / press-scale)

## Requirements

- Android 7.0+ (minSdk 24); full features require Android 10+ (workdir uses MediaStore)
- arm64-v8a
- At least one OpenAI-compatible API endpoint (self-hosted or cloud)

## Build

```bash
git clone https://github.com/aixtin/nyral.git
cd nyral/android-agent-app
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17, Gradle 8.13, Android SDK 36. The repo ships `gradlew` (pinned to Gradle 8.13); a system Gradle 8.13 works too. `settings.gradle.kts` includes Aliyun mirror config for faster builds in mainland China — remove the `maven(...)` lines if you are elsewhere.

> Release-signed builds: `./gradlew assembleRelease` (self-managed keystore read from `keystore.properties`; both keystore and properties are gitignored — prepare your own keys).

## Quick Start

1. Install the APK, open Settings → Model Config, and enter the Base URL + API Key of any OpenAI-compatible endpoint
2. (Optional) Add remote hosts in SSH Config: password or private key (ED25519 supported via the built-in BC), proxy jumps supported
3. Start chatting; the AI calls tools on demand, with tool-call progress shown collapsed inside bubbles
4. Long-term memory archives automatically; saying "remember xxx" archives immediately

## Directory Layout

```
android-agent-app/
├── app/src/main/java/io/github/aixtin/nyral/   # Main package: entry + session/rendering core
│   ├── MainActivity.kt          # Chat main UI (~4470 lines, post domain-split)
│   ├── ChatSessionState.kt      # Session state machine (streaming field consolidation)
│   ├── ChatFlowController.kt    # Send / streaming callback control
│   ├── StreamUiBridge.kt        # Streaming row creation + LocalEngine callback bridge
│   ├── MdTableView.kt / MdBlocksView.kt / MdBlocksRender.kt  # In-message table rendering (Scheme C)
│   └── RoundedTablePlugin.java / RoundedTableRowSpan.java / RoundedCodeBlockSpan.java  # Markwon table plugins
└── app/src/main/java/io/github/aixtin/nyral/all/   # Feature modules (98 Kotlin files)
    ├── LocalEngine.kt           # Engine: tool registry + dispatch + dialog loop (~1650 lines)
    ├── MainActivityVoice.kt / MainActivitySession.kt / MainActivityInputBar.kt  # MainActivity domain splits
    ├── SecurityConfig.kt / SecurityUi.kt   # Security confirmations (dangerous-op second confirmation)
    ├── MainUi.kt / UiKit.kt / BubbleSpans.kt / Typewriter.kt  # UI building & animations
    ├── ApiConfig.kt / MemoryApiConfig.kt   # Provider & model capability tables
    ├── MemoryDb.kt / MemoryKeeper.kt / MemoryEmbedder.kt / BertTokenizer.kt / MemoryTools.kt  # Memory stack
    ├── SshTools.kt / SshConfigStore.kt / FileTools.kt / WorkDir.kt / WorkTools.kt  # SSH/SFTP/workdir
    ├── WebTools.kt / ScriptEngine.kt / UiControlService.kt  # Web / sandboxed execution / device control
    ├── BrowserPage.kt           # In-house agent browser (full-screen WebView + AI takeover/highlight/CAPTCHA handoff)
    ├── AttachmentStore.kt / DocTextExtractor.kt / PdfTextExtractor.kt / VideoCompressor.kt  # Attachment pipeline
    └── SettingsActivity.kt / ModelEditActivity.kt / McpConfigActivity.kt / ...  # Settings pages
└── app/src/main/assets/mem_model/   # bge-small quantized ONNX model + vocab
```

## Data & Privacy

- Conversations, memory, token stats, and SSH config (encrypted storage) stay on-device
- No telemetry; the only startup external request is the GitHub Release update check (404 ignorable)
- **Note**: chat messages and attachment contents are sent to the model endpoint you configure (OpenAI-compatible); self-host an endpoint for full privacy
- Permission list and security design (DebugServer protections / dangerous-op gates / data boundaries): see [SECURITY.md](SECURITY.md)

## Roadmap

- [x] Multi-model capability table & custom models
- [x] Local memory (bge semantic retrieval + keyword recall + three-tier layering)
- [x] SSH remote filesystem + proxy jumps + SFTP upload/download
- [x] Web tools (four-engine search rotation / fetch / download / site_auth cookie injection)
- [x] Attachment multimodality: images/videos/audio/PDF/Office (v19)
- [x] Voice input loop (record / send / voice bubble, v20)
- [x] Session search + token stats + foreground-service keep-alive
- [x] Workdir batch tools (grep/head/stats, 40-round tool cap)
- [x] Browser module (in-house agent browser: full-screen takeover + AI highlight + CAPTCHA handoff)
- [x] GitHub Release publishing (signed builds since v2.0, in-app update prompt live)
- [ ] Further optimization of conversation context tiering

## Changelog

- **v2.3.5 (2026-10-04, versionCode 41)**: Third security review round fixes — R3-2 notification confirmation PendingIntent switched to globally-incrementing requestCode (eliminates concurrent hashCode collisions; stale notifications no longer approve new ops); R3-3 SSH host-trust confirmation bubble now shows the pending host-key fingerprint for review before approval; R3-4 dangerous-command normalization strips hyphens (reverse-shell constructions like `nc -e` / `nc -l -e` hit the blocklist); R3-6 DebugServer `/v1/browser/eval` arbitrary JS evaluation gated with the same three-tier confirmation; regression tests added for R3-4/R3-6.
- **v2.3.4 (2026-10-04, versionCode 40)**: Fixed auto-tier missing confirmations for compound dangerous actions — browser:click/type/upload, app:click/text/tap/launch, file:write/upload, workdir:write unified under `name:action` composite keys (needsConfirm/riskOf/ticket synchronized); auto tier confirms again; gate regression tests added (risk-rating assertions for the full high-risk list).
- **v2.3.3 (2026-10-04, versionCode 39)**: Three-tier security gate (strict/auto/permissive) UI shipped; the AI no longer switches tiers by itself; welcome card embeds quick gate switching; audit page (AuditActivity) added; permissive-mode second-confirmation dialog themed to match settings.
- **v2.3.2 (2026-10-03, versionCode 38)**: Security review fix release — high-risk ops (command execution, script execution, dynamic tool calls) now require second confirmation; DebugServer LAN mode and non-local connections log prominent warnings; dangerous-command blocklist hardened (eval/$(/xargs/busybox/base64/printf/process substitution etc.); SSH private-key prefix logs removed; R8 obfuscation + resource shrinking enabled for release with new proguard-rules.pro; dependency upgrades (jsch 0.2.26 / bcprov 1.82 / commons-compress 1.28.0 / xz 1.10 / onnxruntime 1.26.0 / media3 1.5.1).
- **v2.3.1 (2026-10-03, versionCode 37)**: Security hardening — SSRF rewrite (host literal regex replaced by InetAddress resolution with itemized validation, covering DNS rebinding, IPv6, IP encoding, 0.0.0.0 variants; HTTP connections pin the validated IP while preserving the original Host header); log redaction completed (SSE stream and SSH command logs print lengths only); file/read path-traversal fix (canonicalPath whitelist validation); CI signing job now triggers on push only; trustHost adds connection-name validation; dangerous-op list extended with file:upload / browser:save_cookies; workdir whitelist trailing-slash false-rejection fixed.
- **v2.3 (2026-10-03, versionCode 36)**: SSH host-key null-pointer NPE fix; SSRF risk wording fix; M1/M2/M4/M5/M6 security hardening; CI adds build-release signing job (Secrets-managed signing).
- **v2.2 (2026-10-02, versionCode 35)**: 6 security-audit fixes (JS bridge global injection narrowed to local asset pages; cookie & content access tightened; API Keys moved to AndroidKeyStore AES/GCM with legacy-plaintext migration; site_auth.json encrypted with migration; WebTools fetch/redirect reuse SSRF blocklist checks; DebugServer query-token removed, header-only auth); CI upgrade (push/PR auto assembleDebug compile gate, debug APK artifact upload, Gradle dependency cache).
- **v2.1.1 (2026-10-02, versionCode 34)**: Table cell vertical centering fix (MdTableView row container; consistent across chat/agent modes); slow-motion replay debugging popup; browser panel button; chat-mode bubble width restored symmetric to avatar inner edge.
- **v2.1 (2026-10-02, versionCode 33)**: In-message Markdown table rendering Scheme C shipped (MdTableView rewrite: independent block-level TableView in bubbles, WRAP_CONTENT adaptive); inline-code cell blank fix; right-half table vanishing fix (column-width floor + inner horizontal scroll); vertical-text root-cause fix (columns compress by text demand, inner scroll instead of vertical text) + consecutive-table spacing fix; three UI fixes (fullscreen AI body bubble, long-press copy on AI side restored, browser double-tap open/close); legacy Scheme-C table classes removed.
- **v2.0 (2026-09-29 maintenance, versionCode 32)**: targetSdk/compileSdk to 36 (AGP 8.13.0 + Gradle 8.13, prep for Android 16 Live Updates API); Route D live Markdown rendering (MdSpans/MdBlocks/MdStreamRenderer) + quote-bar vertical rule / HR rendering (QuoteBarSpan/HrSpan) + rendered persistence plumbing (mdCache→rendered→on-the-fly three-tier read, ADR-015); status-line overlay dropdown panel (curtain-style attached to chatArea, ScrollView + animation-generation token to fix double-tap races); thinking-bubble air-gap root fix (three protocol-prefix fallbacks) + timeline dot alignment/iconization; first-launch crash fix (markwon concurrency lock) + message-list scroll polish (RecyclerView shape pooling + AiRich container pooling + flush batching + drift compensation + watcher dedup); shared table horizontal scroll (TableScrollWrap) + unified column width (colMaxChars); action-track prototype + slow-motion system; tool self-heat sorting (ToolHotStore) + root auto-grant of own permissions (versionCode 31); pre-open-source security hardening & cleanup.
- **v2.0 (2026-09-13)**: First signed release (self-managed keystore via keystore.properties, signingConfig on release, keys backed up offsite); dynamic versioning + startup update-check fix; settings.gradle mirror comments completed (versionCode 30).
- **v1.3 (2026-09-11)**: MainActivity series split (MediaPreviews/AiBubbleHolder/AttachmentSender/TerminalGate etc.) + coroutines rollout + UI strings externalized to i18n + code-health pass (Bitmap sampling decode, cleartext-traffic whitelist) + first-launch permission/onboarding page + developer docs & ADRs (versionCode 23).
- **v1.1 (2026-09-04)**: SSE streaming connection/stream-handle leak fix (unified finally release; no leaks on cancel/exception); makeCopyable non-null assertion hardening; versionCode 19→20 / versionName 1.1; SSE messages and streamOnce full logs downgraded Log.i → Log.v (anti-spam).
- **v1.0**: Baseline feature release (versionCode 19).
- **v1.1.1 (2026-09-04, versionCode 21)**: Video bubble frame-extraction failure changed from permanent abandonment to 20s cooldown auto-retry (recovers on next render/scroll/foreground); fixes video bubble degrading to file card.

## Privacy

Nyral is designed to keep your data local-first. Conversations, memories, attachments and logs are stored on your device. Text embedding runs fully on-device.

For model responses, the App sends your message content to the model service provider configured in Settings (DeepSeek by default; Zhipu GLM, Xiaomi MiMo and custom endpoints are supported). The App does not operate its own cloud service and does not include analytics or advertising SDKs.

See [PRIVACY.md](PRIVACY.md) for details.


## License

MIT License, see [LICENSE](LICENSE).

### Third-party dependency licenses

Runtime dependencies and their licenses (full license texts live in each component's official repo):

| Dependency | Version | License |
|---|---|---|
| kotlin-stdlib | 1.9.22 | Apache-2.0 |
| kotlinx-coroutines-android | 1.8.1 | Apache-2.0 |
| androidx.media3 (exoplayer/ui etc.) | 1.5.1 | Apache-2.0 |
| androidx.* (core/recyclerview etc.) | - | Apache-2.0 |
| org.apache.commons:commons-compress | 1.28.0 | Apache-2.0 |
| io.noties.markwon (core/ext-strikethrough/ext-tables) | 4.6.2 | Apache-2.0 |
| com.atlassian.commonmark (commonmark/ext-gfm-tables/ext-gfm-strikethrough) | 0.13.0 | BSD-2-Clause |
| com.microsoft.onnxruntime:onnxruntime-android | 1.26.0 | MIT |
| org.slf4j:slf4j-api | 1.7.36 | MIT |
| com.android.tools (build-time, not packaged) | - | Apache-2.0 |
| org.bouncycastle:bcprov-jdk18on | 1.82 | Bouncy Castle Licence |
| com.github.mwiede:jsch (incl. jzlib/jbcrypt) | 0.2.26 | BSD-3-Clause / BSD / ISC |
| org.tukaani:xz | 1.10 | Public Domain |
| org.mozilla:rhino | 1.7.15 | MPL-2.0 |
| com.github.junrar:junrar | 7.6.0 | UnRAR freeware license |
| junit / org.json / hamcrest-core (test-only, not packaged) | 4.13.2 / 20231013 / 1.3 | EPL-2.0 / JSON License / BSD-3-Clause |

MPL-2.0 (rhino) and UnRAR license (junrar) notices are preserved inside the shipped jars; junrar is used for extraction only, never to build RAR-compatible compressors.
