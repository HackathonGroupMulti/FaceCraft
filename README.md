# FaceCraft

An Android app for AI-powered 3D face morphing, built for the **Nexa × Qualcomm On-Device AI Hackathon**.

Describe facial modifications in natural language — "make the eyes bigger", "sharpen the jawline" — and watch them applied in real time on an interactive 3D face rendered with Three.js. All inference runs **completely on-device** using Vision Language Models with support for both Qualcomm NPU acceleration and CPU-only mode for universal device compatibility.

## Features

- **Natural language face modification** — Describe changes in plain English and watch them applied instantly
- **Cumulative morphing** — Each prompt builds on previous modifications (e.g., "make eyes bigger" → "make nose smaller" preserves eye changes)
- **Real-time 3D visualization** — Interactive WebGL rendering with Three.js and smooth morph animations
- **Dual model support:**
  - **NPU Mode** — OmniNeural-4B (~4.5GB) with Qualcomm Hexagon NPU acceleration
  - **CPU Mode** — SmolVLM-256M (~550MB) for universal device compatibility (Samsung, Pixel, etc.)
- **34 morph parameters** — Fine control across 8 face regions (eyes, nose, jaw, cheeks, mouth, forehead, face shape)
- **Region-focused morphing** — Target specific face areas for precise modifications
- **Multiple face templates** — Pre-loaded Ally and Lisa models with ARKit blend shape support
- **Robust JSON parsing** — Multi-strategy extraction handles VLM output variations reliably
- **Comprehensive debug logging** — Track model type, generation stats, and JSON outputs

## Device Compatibility

| Mode | Model | Size | Devices | Speed |
|------|-------|------|---------|-------|
| **CPU Safe Mode** (Default) | SmolVLM-256M | ~550MB | All Android devices | ~3-8s |
| **NPU Mode** | OmniNeural-4B | ~4.5GB | Qualcomm Snapdragon 8 Gen 2+ | ~2-5s |

**CPU Safe Mode** is enabled by default, making the app work on any Android device including Samsung Galaxy S24 Ultra, Google Pixel, and other non-Qualcomm devices.

## Tech Stack

- **Kotlin** + **Jetpack Compose** — Modern Android UI
- **NexaSDK** — On-device VLM inference engine
- **OmniNeural-4B-mobile** — NPU-accelerated VLM for Qualcomm devices
- **SmolVLM-256M-Instruct** — Lightweight CPU/GPU VLM for universal compatibility
- **Three.js** (r128) — WebGL 3D rendering via Android WebView
- **Qualcomm Hexagon NPU** — Hardware-accelerated inference via FastRPC (when available)
- **Kotlinx Serialization** + **Kotlinx Coroutines** — JSON handling and async operations

## How It Works

### Cumulative Face Morphing
FaceCraft implements **stateful, cumulative modifications**:
1. **First prompt:** "make the eyes bigger" → Eyes morph to 0.6
2. **Second prompt:** "make the nose smaller" → Eyes stay at 0.6, nose morphs to 0.3
3. **Third prompt:** "fuller lips" → Eyes 0.6, nose 0.3, lips 0.7

Each modification builds on the previous state, allowing you to iteratively sculpt the face without resetting.

### Robust VLM Output Handling
The app uses a **multi-layered parsing strategy** to handle small model inconsistencies:
- **Strategy 1:** Standard JSON parsing with boundary detection
- **Strategy 2:** Extract first complete JSON object from mixed text/JSON output
- **Strategy 3:** Regex-based manual extraction for malformed JSON
- **Strategy 4:** Natural language pattern matching (e.g., "set eyeBlink_L to 0.6")

This ensures reliable operation even when VLMs output explanatory text alongside JSON.

### Enhanced Prompt Engineering
The system uses aggressive formatting instructions to guide the VLM:
- Simplified current state presentation (shows only first 5 active morphs)
- Explicit JSON-only output requirements
- Concrete formatting examples in prompts
- Regional focus constraints for targeted modifications

## Architecture

```
User prompt → FaceMorphService → NexaService (VLM on NPU or CPU)
                                        ↓
                              JSON morph parameters
                                        ↓
                MorphParameterParser (multi-strategy validation)
                                        ↓
                   WebViewBridge → Three.js face_viewer.html
                                        ↓
                 3D face rendered with smooth morph animation
```

## Quick Start

### Option 1: CPU Safe Mode (Recommended for most devices)

1. Open the app — CPU toggle is **ON** by default (green)
2. Tap **"DL CPU (~550MB)"** to download SmolVLM
3. Tap **"BOOT CPU"** to initialize
4. Start morphing with natural language prompts!

### Option 2: NPU Mode (Qualcomm devices only)

1. Toggle the **CPU switch OFF** (turns purple)
2. Tap **"DL NPU (~4.5GB)"** to download OmniNeural
3. Tap **"BOOT NPU"** to initialize with NPU acceleration
4. Start morphing!

## Usage Example

```
1. Select region: "Eyes"
   Prompt: "make them bigger and more expressive"
   → Eyes widen with increased emphasis

2. Select region: "Nose"
   Prompt: "make it slightly smaller"
   → Nose reduces while eyes stay widened

3. Select region: "Mouth & Lips"
   Prompt: "fuller lips, subtle smile"
   → Lips plump up, slight smile added
   → Previous eye and nose changes preserved

4. Select region: "All Features"
   Prompt: "slightly more angular jaw"
   → Jaw definition increases
   → All previous modifications remain intact

5. Click "RESET" to return to default face state
```

## Building & Running

### Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 11+**
- **Android SDK** with API level 27+ (Android 8.1 Oreo minimum)
- **Physical Android device** (emulators don't support the VLM inference)

### Build Steps

```bash
# 1. Clone the repository
git clone https://github.com/HackathonGroupMulti/FaceCraft.git
cd FaceCraft

# 2. Open in Android Studio
# File → Open → Select the FaceCraft folder

# 3. Sync Gradle
# Android Studio will auto-prompt, or click "Sync Now" in the toolbar

# 4. Connect your Android device via USB
# Enable USB debugging in Developer Options

# 5. Build and run
# Click the green "Run" button or press Shift+F10
# Select your connected device
```

### First Launch

1. **CPU Safe Mode is ON by default** (green toggle) — works on all devices
2. Tap **"DL CPU (~550MB)"** to download the SmolVLM model
3. Wait for download to complete
4. Tap **"BOOT CPU"** to initialize the model
5. Select a face template (Ally or Lisa)
6. Type a prompt like "make the eyes bigger" and tap **APPLY**

### For Qualcomm Devices (Optional NPU Acceleration)

1. Toggle the **CPU switch OFF** (turns purple)
2. Tap **"DL NPU (~4.5GB)"** to download OmniNeural
3. Tap **"BOOT NPU"** to initialize with Hexagon NPU acceleration

## Why NexaSDK?

**NexaSDK** is the core inference engine that makes FaceCraft possible. Here's why we chose it and how it's integrated:

### Why NexaSDK

1. **On-Device Privacy** — All AI inference runs locally on the phone. No data leaves the device, no cloud API calls, no internet required after model download.

2. **Qualcomm NPU Acceleration** — NexaSDK provides direct access to Qualcomm's Hexagon NPU via FastRPC, enabling 2-5x faster inference compared to CPU-only execution on supported devices.

3. **Multiple Model Support** — Supports both large NPU-optimized models (OmniNeural-4B) and lightweight GGUF models (SmolVLM-256M), giving us flexibility for different device capabilities.

4. **Streaming Generation** — The SDK provides token-by-token streaming, allowing real-time feedback during generation.

### Where NexaSDK is Used

NexaSDK is integrated in **[NexaService.kt](app/src/main/java/com/facemorphai/service/NexaService.kt)** — a singleton wrapper that manages all VLM operations:

```kotlin
// SDK Initialization (line ~70)
NexaSDK.initialize(licenseToken, callback)

// Model Loading - NPU Mode (line ~268)
VlmWrapper.builder()
    .vlmCreateInput(VlmCreateInput(
        model_name = "omni-neural",
        model_path = manifestPath,
        config = ModelConfig(npu_lib_folder_path = ..., npu_model_folder_path = ...),
        plugin_id = "npu"  // Uses Hexagon NPU
    ))
    .build()

// Model Loading - CPU Mode (line ~136)
VlmWrapper.builder()
    .vlmCreateInput(VlmCreateInput(
        model_name = "SmolVLM-256M",
        model_path = ggufPath,
        config = ModelConfig(nGpuLayers = 0, nThreads = 4),
        plugin_id = "cpu_gpu"  // CPU/GPU only, no NPU
    ))
    .build()

// Text Generation with Streaming (line ~377)
wrapper.generateStreamFlow(prompt, GenerateConfig(maxTokens = 512))
    .collect { token -> /* process each token */ }
```

### NexaSDK Flow in FaceCraft

```
User types "make eyes bigger"
           ↓
FaceMorphService builds optimized prompt with blendshape keys
           ↓
NexaService.generateStream() → NexaSDK VlmWrapper
           ↓
VLM runs on NPU (or CPU) and streams JSON tokens
           ↓
{"eyeWideLeft": 0.6, "eyeWideRight": 0.6}
           ↓
MorphParameterParser validates and extracts values
           ↓
WebViewBridge sends to Three.js → Face morphs in real-time
```

## Technical Details

### Key Components

| Component | Description |
|-----------|-------------|
| [FaceMorphService.kt](app/src/main/java/com/facemorphai/service/FaceMorphService.kt) | Core business logic for VLM prompt construction and state management |
| [NexaService.kt](app/src/main/java/com/facemorphai/service/NexaService.kt) | Singleton wrapper for NexaSDK, handles NPU/CPU/GGUF model loading |
| [ModelDownloader.kt](app/src/main/java/com/facemorphai/service/ModelDownloader.kt) | Downloads both NPU (OmniNeural) and CPU (SmolVLM) models |
| [MorphParameterParser.kt](app/src/main/java/com/facemorphai/parser/MorphParameterParser.kt) | Multi-strategy JSON parser with fallback extraction |
| [VlmLogManager.kt](app/src/main/java/com/facemorphai/logging/VlmLogManager.kt) | Debug logging with model type, JSON output, and generation stats |
| [WebViewBridge.kt](app/src/main/java/com/facemorphai/bridge/WebViewBridge.kt) | Android ↔ JavaScript IPC for 3D viewer communication |
| [face_viewer.html](app/src/main/assets/face_viewer.html) | Three.js WebGL application with FBXLoader and morph target animation |

### Debug Logging

The app includes comprehensive VLM debug logging accessible via the **LOG** button:

```
═══════════════════════════════════════
📋 Request #1 (Attempt 1)
🕐 Time: 14:32:15.123
⏱️ Duration: 1234ms
🤖 Model: SmolVLM-256M (CPU_ONLY)
───────────────────────────────────────
📤 PROMPT (156 chars):
Output ONLY JSON. No markdown, no text.
Keys: browInnerUp, browOuterUpLeft...
───────────────────────────────────────
🔢 Stream tokens received: 12
📥 VLM RAW OUTPUT (45 chars):
"{"browInnerUp":0.6,"browOuterUpLeft":0.4}"
───────────────────────────────────────
✅ PARSE SUCCESS: 2 parameters
═══════════════════════════════════════
```

### Robustness Features

1. **Dynamic Blendshape Discovery** — Automatically detects available morph targets from loaded FBX models
2. **Retry Logic** — 2-attempt generation with automatic fallback strategies
3. **Graceful Degradation** — Falls back to CPU if Hexagon NPU is unavailable
4. **State Merging** — Intelligent parameter merging preserves non-zero values across requests
5. **Model Type Tracking** — Logs distinguish between CPU_ONLY and VLM_NPU modes

### Performance

| Metric | NPU Mode | CPU Mode |
|--------|----------|----------|
| Model Size | ~4.5 GB | ~550 MB |
| Inference Speed | ~2-5 seconds | ~3-8 seconds |
| Context Window | 2048 tokens | 1024 tokens |
| Max Output Tokens | 256 | 512 |

## 3D Model Credits

This project uses 3D models licensed under [Creative Commons Attribution 4.0](http://creativecommons.org/licenses/by/4.0/).

- **"Ally Pretty Beautiful Face Head Model With Hair"** by skullvez
  https://skfb.ly/oC7Jx
  Licensed under [CC-BY-4.0](http://creativecommons.org/licenses/by/4.0/)

- **"Lisa - Woman Head with BlendShapes"** by skullvez
  https://skfb.ly/o69ns
  Licensed under [CC-BY-4.0](http://creativecommons.org/licenses/by/4.0/)

## License

Built for the Nexa × Qualcomm On-Device AI Hackathon.
