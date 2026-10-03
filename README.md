# Offline RP Harness

A role-play harness for low-parameter language models (1B–1.5B), designed to run **fully offline** on mid-range Android phones and low-end computers. No API calls, no server, no network dependency.

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
