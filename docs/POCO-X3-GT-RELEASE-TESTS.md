# Sepand AI prerelease device verification plan

Target handset: Xiaomi Poco X3 GT (MediaTek Dimensity 1100, Mali-G77 MC9, 8 GB RAM).

## Install and permissions
- Install the debug-signed beta APK without uninstalling other apps; Sepand uses application ID com.sepand.ai and installs alongside Buddy.
- Grant Accessibility, notification listener, microphone, and selected Obsidian vault permissions only when testing those features.
- Confirm the four themes and English/Persian directionality.

## Local inference
- Import Spark-X2.6-1.7B Q4 GGUF first; do not load the 4B model until memory headroom is measured.
- Compare CPU and Vulkan on the same prompt and context length; keep Auto/CPU if Vulkan is slower or less stable.
- Monitor RAM, surface temperature, battery drain, first-token latency and generation speed. Stop if thermal or memory pressure becomes excessive.

## Phone control and messages
- Test app launch, foreground screen reading, scroll, Back, visible-label tap, typing and post-action verification.
- Test one-off message draft and send with exact recipient review and explicit approval.
- Test notification auto-reply only after enabling it and allowlisting exact package and conversation names. Verify it skips non-allowlisted senders and uses a notification RemoteInput action only.

## Vision, files and voice
- Test English and Persian OCR on screenshots and photos, image labels, PDF OCR, and DOCX/XLSX/PPTX extraction.
- Test live speech recognition only with offline language packs installed and test TTS with offline voices selected.
- Confirm unsupported audio files are not falsely described as transcribed.

## Obsidian and MCP
- Select a vault, revoke/regrant access, and test English and Persian retrieval across Markdown notes.
- Test MCP HTTPS and loopback server initialization, tool listing, approval, denial and failure reporting.

This is a test plan, not a statement that physical-device validation has already passed.

## Release build record

GitHub Actions compiles the native llama.cpp dependency before running the Android Gradle build. An APK is releasable only after both stages succeed.

Retry note: Android SDK component provisioning accepts sdkmanager license prompts without failing on the closed input pipe.
