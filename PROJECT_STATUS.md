# Sepand AI 0.2.0-beta1 — implementation status

## Integrated in source

- Kotlin + Jetpack Compose Android app, application ID `com.sepand.ai`.
- Persian/English UI, Persian RTL, four glass themes, system-prompt editor, six reasoning/action-budget presets.
- Local Spark-X2.6 GGUF inference through `tensai-app/llama.kt`, with Auto/CPU/GPU/Vulkan/hybrid controls. Auto starts CPU; explicit GPU/hybrid modes are experimental on the Poco X3 GT.
- Accessibility service: bounded screen snapshots, app launch, scroll, visible-label click, text entry, Back, Home, and screen reading.
- Bounded autonomous action loop; it observes the current UI after actions and resumes after user-approved actions.
- Message send path: one-off sends require explicit approval, exact recipient and target-package verification; notification RemoteInput first, verified visible composer fallback second.
- Allowlisted background auto-reply: OFF by default; exact app-package + exact conversation-title allowlists; local model generates reply; only Android notification RemoteInput can send; sensitive-message keyword filter; allowlists and original notification are rechecked before sending.
- Approval requirement for manual text entry, one-off sends, MCP calls, and sensitive clicks.
- Optional notification preview capture and inline notification replies.
- Local bundled ML Kit image labeling and Tesseract OCR in English/Persian; OCR packs downloaded once to app-private storage.
- PDF OCR (up to 8 pages), text/code, DOCX/XLSX/PPTX extraction, bounded text entries from ZIP/EPUB, and up to three sampled video frames.
- Local Android on-device microphone speech recognition (when installed) and offline TTS voice selection.
- Obsidian Markdown hybrid search on every user prompt: local Spark bilingual query expansion, BM25 candidate ranking and Spark semantic reranking.
- MCP Streamable HTTP initialize, list tools and call tools, with per-call approval and HTTPS/local-loopback restriction.
- Optional floating panel for scroll/back/home.
- GitHub Actions build and beta prerelease workflows.

## Known limits (must not be described as fully solved)

1. **Model vision:** Spark-X2.6 is text-only. Sepand adds on-device image classification and OCR, not a generative visual reasoning model that understands arbitrary scenes. Complex visual questions, spatial reasoning and chart interpretation are limited.
2. **Messaging:** one-off sends require approval. Optional background auto-replies are OFF by default, need exact app/contact allowlists, and only work when an incoming notification exposes a RemoteInput action. They do not support arbitrary messaging apps, protected chats, disappearing messages, or unsupported notification formats. Keyword-based sensitive-content skipping is conservative, not infallible.
3. **Voice:** live microphone transcription depends on Android providing an installed on-device recognizer and language pack. TTS depends on installed offline voices. Arbitrary audio-file transcription and continuous wake-word listening are not included.
4. **File formats:** common office/text/PDF/image formats have parsers; ZIP/EPUB extraction is limited to safe bounded text entries; video is sampled at up to three frames. Unknown binaries and audio files remain file references. Video audio tracks and pre-recorded audio transcription are not included.
5. **Memory:** Obsidian uses BM25 candidate retrieval, local Spark bilingual query expansion and local model-based semantic reranking. It is not a dedicated vector-embedding database. Search is limited to Markdown notes within bounded scan/read limits.
6. **MCP:** only Streamable HTTP is implemented. No stdio process transport, OAuth login flow, credential vault, or automatic server installation.
7. **Automation:** the agent has a bounded step budget and relies on Android Accessibility node text. Apps that hide labels, draw custom canvases, block accessibility, or use protected UI may not be automatable. It must verify results and request approval for consequential actions.
8. **Hardware:** no privileged CPU/GPU frequency control, direct GPU utilization telemetry, or thermal-throttle override. Auto uses CPU. GPU/hybrid must be benchmarked on the user's exact device.
9. **Release signing:** GitHub Actions emits debug-signed testing APKs. Production release signing requires a persistent protected keystore in GitHub Actions secrets and physical-device test coverage.

## Required device test plan

- Fresh install and permissions on Poco X3 GT / Android 13 MIUI.
- Spark-X2.6-1.7B Q4 GGUF load on CPU; 4B load only after memory measurement.
- CPU vs Vulkan vs hybrid speed, memory, battery and thermal behavior.
- Accessibility screen capture, scroll, back, app open, input focus, and app-switch detection.
- Notification RemoteInput reply tests for Telegram/WhatsApp/standard Android messaging apps.
- Screen-composer send test with exact visible contact and Send button, ensuring rejection on package/recipient mismatch.
- OCR on Persian/English photos, screenshots and PDFs; document extraction fixtures.
- Android offline speech recognition + offline English/Persian TTS packs.
- Obsidian vault permission persistence, Persian normalization, bilingual query expansion, BM25 ranking, semantic reranking and large-vault limits.
- Auto-reply allowlist matching, approval mode vs opt-in auto mode, sensitive-message skips, RemoteInput-only send path and duplicate notification handling.
- MCP HTTPS/local loopback handshake, tools/list, approval, tool call and error paths.

A successful GitHub Actions build is necessary but not a substitute for these tests on the actual phone.
