# Device release verification

Sepand beta targets the Poco X3 GT / Android 13 and compiles against the stable Android API 36 SDK. The app manifest target SDK remains API 35.

Before treating a build as stable on the handset, test:
- Spark-X2.6 1.7B Q4 on CPU first, then benchmark Vulkan/hybrid.
- RAM, battery and temperature during a 10–15 minute conversation.
- Accessibility capture, app launch, taps, scroll, typing, exact-recipient message approval and verification.
- Notification RemoteInput auto-reply only for explicitly allowlisted apps and conversations.
- Persian/English OCR, offline speech packs and offline TTS.
- Obsidian vault permission persistence and bilingual memory retrieval.
- MCP HTTPS/loopback initialization, tool approval and errors.

Do not enable background auto-replies until the intended app and exact conversation have been allowlisted and tested.

The Vulkan shader compiler is now provisioned before llama.cpp native bootstrap in CI.
