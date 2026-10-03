# Offline RP Harness

A role-play harness for small language models (1B–1.5B), built to run **fully offline** on mid-range Android phones and low-end computers.

- No accounts, no telemetry, no analytics, no monetization.
- Chats, settings, and logs stay on your device unless you choose to export them.
- Free software under GPL-3.0.

The goal is simple: anyone with just a phone or an old laptop should be able to have *something* to role-play with, privately, without paying a subscription or sending their chats to someone else's server.

> **Status: work in progress.** This repository is the recovery baseline: a working single-pass build on a Nokia X20. An earlier prototype had a multi-pass pipeline (plan → write → review) that exceeded the memory budget on 6 GB devices, so those features are being rebuilt from this baseline with memory limits designed in from the start. See the [Roadmap](#roadmap).

**Current test device:** Nokia X20 (6 GB RAM, Snapdragon 480, Android 13).

---

## Privacy

- The harness does not collect or transmit any data.
- All inference happens on-device through [llama.cpp](https://github.com/ggml-org/llama.cpp).
- Conversation history, character cards, and settings are stored locally.
- Export is manual and opt-in.

> **Note:** the Android manifest still declares the `INTERNET` permission, which is only needed for an optional developer backend (a local LM Studio / llama-server endpoint). It is being removed from release builds so Android itself enforces the offline guarantee. You can check what any build can do under *Settings → Apps → Harness → Permissions*.

---

## What it does

The harness wraps a GGUF model in a structured role-play layer:

- **Character cards:** system prompt, greeting, optional gender and pronouns.
- **State engine:** tracks location, mood, trust, action, recent events, and open threads.
- **Prompt assembly:** merges card rules, format instructions, a compact state block, and a sliding window of recent turns, all within a token budget.
- **Quality guards:** impersonation detection, lazy-reply detection, and format enforcement. Loop detection and history compaction are available as optional settings.
- **Portrait system:** mood and action slots swap character images as the scene changes.

The UI and logic run in a WebView (`harness.html`). Inference runs natively through llama.cpp over a JNI bridge.

---

## Architecture

```
┌─────────────────────────────────────┐
│ harness.html (UI + logic, JS)       │
│  ├─ StateManager / PromptAssembler  │
│  ├─ ResponseParser / quality guards │
│  └─ Portrait slots + editor         │
└──────────────┬──────────────────────┘
               │ @JavascriptInterface
┌──────────────▼──────────────────────┐
│ LlamaBridge.kt / NativeLib.kt       │
│ Kotlin ↔ C++ bridge                 │
└──────────────┬──────────────────────┘
               │ JNI
┌──────────────▼──────────────────────┐
│ native-lib.cpp                      │
│ llama.cpp (compiled for arm64)      │
└─────────────────────────────────────┘
```

The JS layer is backend-agnostic. It can talk to `mock` (for UI development), `openai` (LM Studio / llama-server, developer use only), or `native` (the on-device build). The harness logic is the same in all three.

---

## Current state

**Working**
- Model extraction from APK assets to internal storage
- llama.cpp model load with mmap
- Token streaming with `[STATE]` block suppression
- State parsing and persistence
- Portrait slot system with mood mapping
- Single-pass generation on the Nokia X20

**Being rebuilt (memory-efficient versions)**
- Multi-pass generation (planning, review, state classifier)
- Quality-check retries

**Not yet built**
- Scene pre-warm system (saved example exchanges per character)
- Device profiles (Small / Standard / Laptop)
- Model picker UI
- Named pose prefixes and continuation

---

## Memory budget

The 6 GB phone is the tight case. These are rough estimates for a 1B model at Q4_K_M and are being verified with `adb shell dumpsys meminfo`.

| Component | Estimate | Notes |
|---|---|---|
| Model weights (mmap) | ~400–900 MB | File-backed. Android can reclaim these pages under pressure. |
| Output/logits buffer (`n_batch=512`) | ~260 MB | Scales with `n_batch`. The biggest easy win. |
| KV cache (`n_ctx=1024`) | ~34 MB | Scales with context length. |
| WebView + app | ~500 MB | |

**Design rule:** a 300 MB free-memory buffer is kept untouched. Every new feature must say which line of the budget it comes out of, and must check available memory before running.

Planned fixes for multi-pass: reuse one context and clear the KV cache between passes instead of recreating it, lower `n_batch` to 128–256, run sub-passes without streaming, and reduce pass counts on constrained devices.

---

## Models

No model is included in this repository, and none is downloaded by the app yet.

The app currently expects one GGUF file in `app/src/main/assets/models/`, named by two constants in `app/src/main/java/com/roleplay/harness/ModelLoader.kt` (`ASSET_PATH` and `TARGET_NAME`). A Llama 3.2 1B–class instruct model in Q4_K_M is the current target. The prompt format is selectable (`llama3` is the default, `chatml` is also supported).

Model files are ignored by Git. A first-run model picker and download flow are on the roadmap.

**Model licenses are separate from this project's license.** Check the license on the model's page before downloading or redistributing it. For example, Llama 3.2 models are covered by Meta's community license, which includes attribution requirements and an acceptable use policy. Fine-tunes may add their own terms.

---

## Content

The project is designed to start in a safe-for-work default. Adult content support is planned as an explicit opt-in that is off on a fresh install, with the adult model offered as a separate download rather than bundled. This repository does not include any explicit models, character cards, or sample content.

---

## Building

**Requirements**
- Android Studio with the NDK and CMake 3.22.1 installed
- An arm64 device (the build targets `arm64-v8a`), Android 10 or newer (`minSdk` 29)
- Roughly 1.5 GB of free storage on the device (the model is currently stored in the APK and copied on first run)

**Steps**

```bash
git clone --recursive https://github.com/kosryvrdrgn14/offline-rp-harness.git
cd offline-rp-harness
```

1. Put a GGUF model in `app/src/main/assets/models/`, matching the filename in `ModelLoader.kt`.
2. Open the project in Android Studio and let Gradle sync.
3. Build and run on your device.

`--recursive` is required because llama.cpp is included as a Git submodule. If you already cloned without it, run `git submodule update --init`.

---

## Roadmap

- [x] Recovery baseline: working single-pass build on the X20
- [ ] **Phase 1: safety fixes** (marker sanitizer, stop sequences, message persistence)
- [ ] **Phase 2: scene system** (pre-warm exchanges, picker UI, editor)
- [ ] **Phase 3: prompt augmentations** (name prefix, continuation, merged analysis)
- [ ] **Phase 4: Android batch** (renderer crash handler, non-streaming sub-passes)
- [ ] **Phase 5: native optimizations** (KV clear instead of context recreation, `n_batch` tuning)
- [ ] **Phase 6: device profiles**

---

## Design philosophy

Most local LLM tooling optimizes for capability: benchmark scores, context length, quantization efficiency. This project optimizes for *experience*: how it feels to talk to a character for twenty turns straight. That means state tracking, format consistency, portrait feedback, and a UI that gets out of the way.

The target user doesn't know what a GGUF is. They want a character, running on their own hardware, without setup. Everything here is built around that.

---

## License

This project is licensed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE).

### Third-party software

- [llama.cpp](https://github.com/ggml-org/llama.cpp) is used under the MIT License. Copyright © the ggml authors.
- Language models are the property of their respective authors and are covered by their own licenses.
