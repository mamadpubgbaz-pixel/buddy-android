# Sepand AI for Android

**Version:** 0.2.0-beta1  
**Application ID:** `com.sepand.ai`  
**Target:** Xiaomi Poco X3 GT · MediaTek Dimensity 1100 · Mali-G77 MC9 · 8 GB RAM  
**Minimum Android:** 10 (API 29) · tuned initially for Android 13 / MIUI

Sepand AI is a local-first Android phone agent. The app uses Spark-X2.6 GGUF through the native `tensai-app/llama.kt` llama.cpp binding, Android Accessibility for on-screen control, optional notification inline replies, local OCR/image labels, local speech APIs, MCP Streamable HTTP, and read-only Obsidian retrieval.

## Feature set in this beta

- **Phone control:** accessibility screen text snapshots, app launching, scroll gestures, tap by visible label, text entry, Back, Home, and web search. Actions run in a bounded multi-step observe/act/verify loop (1–8 actions depending on the selected thinking budget).
- **Message sending:** one-off sends require approval of the exact draft, recipient and target app. Sepand first tries a matching notification inline-reply action; if unavailable, it can use the visible conversation UI after checking the foreground app and exact recipient label. It only clicks a clearly labelled Send/ارسال control.
- **Allowlisted auto-replies:** optional and OFF by default. After a separate global opt-in, you can specify exact Android app package names and exact conversation titles. The notification listener uses the local Spark model to draft a short reply and sends it only through that notification's inline `RemoteInput` action. It skips messages flagged as sensitive/financial, refuses to use accessibility as an auto-send fallback, checks the allowlists again before sending, and stops if the notification changed or disappeared. Apps that do not expose inline reply actions are not supported by background auto-reply.
- **Approval gates:** message sends, all typed text, MCP calls, and sensitive button clicks pause for a separate approval. Sepand does not type passwords, one-time codes, payment details, or recovery codes.
- **Visual input:** bundled ML Kit image labels (400+ categories) plus English/Persian Tesseract OCR. Video input samples up to three frames and applies the same local pipeline. This is on-device classification and text extraction, not a generative vision-language model; Spark-X2.6 itself remains text-only. OCR language packs are downloaded once on first OCR use, then processed locally.
- **Documents and files:** common text/code files, PDF (first 8 pages rendered/OCRed), DOCX/XLSX/PPTX text, and bounded text entries from ZIP/EPUB archives. Audio files remain file references; pre-recorded audio transcription and video soundtrack transcription are not implemented. Video visual analysis is limited to three sampled frames. Unknown binary files are retained as references, not decoded.
- **Voice:** microphone speech recognition through Android's on-device recognizer when installed and supported; offline TTS voices already installed on the device. Sepand will not fall back to a cloud speech recognizer. Download the desired offline speech/voice packs in Android settings first. Arbitrary audio-file transcription and continuous wake-word listening are not supported.
- **Obsidian:** the chosen vault is searched on every user turn when connected. Local Spark expands the request bilingually, a local BM25 ranker narrows the candidate set, and Spark locally reranks those candidates by meaning. This is hybrid lexical + LLM semantic retrieval, not a dense vector/embedding index. Retrieved notes are reference data, not instructions.
- **MCP:** Streamable HTTP `initialize`, `tools/list`, and `tools/call`; supports HTTPS endpoints and HTTP only for `localhost` / `127.0.0.1`. Each tool call requires approval. Stdio transport, OAuth, and per-tool credential vaults are not included.
- **Interface:** Persian/English with RTL for Persian, four glass themes (Dark Red, Dark Green, White Red, White Green), editable system prompt, six thinking presets, file picker, settings, and a user-started floating control panel.
- **Inference controls:** Auto starts on CPU, CPU-only, Vulkan/GPU offload, and experimental hybrid CPU/GPU are selectable. A backend change requires reloading the model. The app uses a 3,072-token context and big-core-aware llama.kt thread selection.

## Local model and hardware recommendations

Start with **Spark-X2.6-1.7B Q4_K_M GGUF**. The 4B model can consume a large part of the phone's 8 GB RAM and is not the default recommendation. XHToken publishes Spark-X2.6-1.7B and 4B as text models; image input is handled separately by the app's local image-label/OCR path.

- Model project and licensing: <https://github.com/XHToken/Spark-X2.6>
- Official model collection: <https://huggingface.co/collections/XHToken/spark-x26>
- Android inference runtime: <https://github.com/tensai-app/llama.kt>

The model's native thinking interface exposes three modes (none, low, high). Sepand's six presets change generation limits/action budgets and map several presets onto those model modes; they are not six native Spark reasoning modes.

**Poco X3 GT notes:** Auto deliberately starts with CPU. Vulkan support exists in the selected llama.cpp Android binding and has been validated by its maintainers on another Mali/MediaTek device, not on this exact phone. Dense model inference can be faster on CPU on Mali; full GPU offload can also consume unevictable shared RAM. Test CPU first, then GPU and hybrid while watching temperature, battery, and memory pressure. No root, overclock, or thermal-throttle bypass is used.

## Permissions and safety

- Accessibility is required for on-screen reading, scrolling, tapping, and typing. Enable it manually in Android Settings.
- Notification access is optional and needed for notification previews/inline replies. Capture previews are stored locally only when the user turns the feature on.
- Overlay permission is optional and only used after the user starts the floating controls.
- Microphone permission is requested only when the user taps Mic.
- The app does not bypass app restrictions, protected screens, encryption, Android permission boundaries, or Android's send/notification controls.
- Web search opens the user's selected browser. Search queries and any MCP-server requests leave the device according to the destination's own privacy policy.

## Build from source

Requirements:

- JDK 21
- Android SDK Platform 37
- Android Build Tools 36.0.0
- Android NDK `27.2.12479018`
- CMake 3.22.1
- Gradle 9.8.2 (or newer compatible with AGP 9.5.0)

```bash
bash scripts/bootstrap.sh
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs the native bootstrap and APK build. Each main-branch push also creates a **testing prerelease** with a debug-signed APK. This is for sideload testing, not a Play Store production-signed release. CI debug signing keys are ephemeral; future beta updates may need the previous app uninstalled before installing a new CI artifact. A persistent signing key should be configured as GitHub Actions secrets before stable update releases.

## Install and first-run setup on Poco X3 GT

1. Install the `Sepand AI` APK. It uses application ID `com.sepand.ai`, so it installs separately from Buddy; it cannot silently replace an already-installed Buddy app.
2. Download a compatible Spark-X2.6-1.7B GGUF quantization and import it in Settings. The official XHToken release is not automatically downloaded by the app.
3. Start with Auto/CPU, Thinking 3, and the default context.
4. Pick the Obsidian vault folder in Memory and grant persistent read access.
5. Enable Sepand Accessibility in Android Settings for phone interaction.
6. Optionally enable notification access for inline replies and notification previews.
7. Install offline English/Persian speech packs and offline TTS voices in Android settings if you want local voice use.
8. Try a simple, non-sensitive task first. Review every approval card, especially before sending a message.

## Repository migration

This codebase replaces the Buddy source at the root of the `mamadpubgbaz-pixel/buddy-android` repository. The previous Buddy tree is preserved on branch `backup/buddy-before-sepand-20261122`. The repository URL slug may still say `buddy-android`; the application itself is Sepand AI.
