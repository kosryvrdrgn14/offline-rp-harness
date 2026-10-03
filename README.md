# Offline RP Harness

A role-play harness for low-parameter language models (1B–1.5B), designed to run **fully offline** on mid-range Android phones and low-end computers. No API calls, no server, no network dependency. There will also be no monetization, telemetry, no analytics, no network calls and all logs and data stay on the device unless the user decides to export and share settings or logs. 

**Current test target:** Nokia X20, 6 GB RAM, Snapdragon 480, Android 13.

> **Status: Work in progress.** The core pipeline runs on-device — model loads, tokens generate, replies stream, state updates persist. Multi-pass generation is implemented but currently exceeds the memory budget on 6 GB devices. See [Roadmap](#roadmap) for what's next.

## What it does

The harness wraps a GGUF model in a structured role-play layer:

- **Character cards** — system prompt, greeting, optional gender/pronouns
- **State engine** — tracks location, mood, trust, action, recent events, open threads
- **Prompt assembly** — merges card rules, format instructions, state block, and a sliding verbatim window
- **Quality guards** — impersonation detection, lazy-reply detection, format enforcement
- **Portrait system** — mood and action slots swap character images as the scene changes
- **Multi-pass generation** — optional planning → writing → review → state-classifier pipeline

The harness runs entirely inside a WebView, with native inference via llama.cpp over a JNI bridge.

## Architecture

┌─────────────────────────────────────┐
│ harness.html (UI + logic, JS) │
│ ├─ StateManager / PromptAssembler │
│ ├─ Quality / MultiPassEngine │
│ └─ Portrait slots + editor │
└──────────────┬──────────────────────┘
│ @JavascriptInterface
┌──────────────▼──────────────────────┐
│ LlamaBridge.kt / NativeLib.kt │
│ Kotlin ↔ C++ bridge │
└──────────────┬──────────────────────┘
│ JNI
┌──────────────▼──────────────────────┐
│ native-lib.cpp │
│ llama.cpp (compiled for arm64) │
└─────────────────────────────────────┘


The JS layer is intentionally backend-agnostic. It can talk to `mock`, `openai` (LM Studio / llama-server), or `native` — same harness logic in all three cases.

## Current state

**Working:**
- Model extraction from APK assets → internal storage
- llama.cpp model load with mmap
- Token streaming with `[STATE]` block suppression
- State parsing and persistence
- Portrait slot system with mood mapping
- Single-pass generation on the Nokia X20

**Implemented but unstable on 6 GB devices:**
- Multi-pass generation (planning, review, state classifier)
- Quality-check retries

**Not yet built:**
- Scene pre-warm system
- Device profiles (Small / Standard / Laptop)
- Model picker UI
- Named pose prefixes and continuation

## Memory constraints

The 6 GB phone is the tight case. Rough peak budget during a generate cycle:

| Component | Size |
|---|---|
| Model weights (mmap) | ~400–900 MB |
| Logits buffer (`n_batch=512`) | ~260 MB |
| KV cache (`n_ctx=1024`) | ~34 MB |
| WebView + app | ~500 MB |
| **Peak per pass** | **~1.3–1.7 GB** |

Android's low-memory killer fires below ~314 MB free. Multi-pass on a 1B model pushes peak over that threshold. The fix is a combination of native optimizations (KV-clear instead of context recreation, `n_batch=256`, non-streaming sub-passes) plus reduced pass counts on constrained devices.

## Building

Requires Android Studio with NDK and CMake installed.

```bash
git clone https://github.com/kosryvrdrgn14/offline-rp-harness.git
cd offline-rp-harness

Place a GGUF model in app/src/main/assets/models/ matching the filename in ModelLoader.kt, then build and run.

Note: model files are not tracked in Git. The expected filename is defined by two constants in app/src/main/java/com/roleplay/harness/ModelLoader.kt.

Roadmap
☑ Recovery baseline: working single-pass build on X20
□ Phase 1 — safety fixes (marker sanitizer, stop sequences, message persistence)
□ Phase 2 — scene system (pre-warm exchanges, picker UI, editor)
□ Phase 3 — prompt augmentations (name prefix, continuation, merged analysis)
□ Phase 4 — Android batch (renderer crash handler, non-streaming sub-passes)
□ Phase 5 — native optimizations (KV clear, n_batch tuning)
□ Phase 6 — device profiles
Design philosophy
Most local LLM tooling optimizes for capability — benchmark scores, context length, quantization efficiency. This project optimizes for experience: how it feels to talk to a character for twenty turns straight. That means state tracking, format consistency, portrait feedback, and a UI that gets out of the way.

The target user doesn't know what a GGUF is. They want a character, running on their own hardware, without setup. Everything here is built around that.

License
GPL-3.0
