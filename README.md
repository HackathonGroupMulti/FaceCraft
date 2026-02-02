# FaceCraft

An Android app for AI-powered 3D face morphing, built for the **Nexa × Qualcomm On-Device AI Hackathon**.

Describe facial modifications in natural language — "make the eyes bigger", "sharpen the jawline" — and watch them applied in real time on an interactive 3D face rendered with Three.js. All inference runs on-device using a Vision Language Model accelerated by the Qualcomm Hexagon NPU.

## Features

- **Natural language face modification** — Describe changes in plain English and watch them applied instantly
- **Cumulative morphing** — Each prompt builds on previous modifications (e.g., "make eyes bigger" → "make nose smaller" preserves eye changes)
- **Real-time 3D visualization** — Interactive WebGL rendering with Three.js and smooth morph animations
- **On-device AI inference** — Uses NexaSDK with OmniNeural-4B-mobile VLM, accelerated by Qualcomm Hexagon NPU
- **34 morph parameters** — Fine control across 8 face regions (eyes, nose, jaw, cheeks, mouth, forehead, face shape)
- **Region-focused morphing** — Target specific face areas for precise modifications
- **Multiple face templates** — Pre-loaded Ally and Lisa models with ARKit blend shape support
- **Robust JSON parsing** — Multi-strategy extraction handles VLM output variations reliably
- **Hardware acceleration** — Hexagon NPU support with automatic CPU fallback for compatibility

## Tech Stack

- **Kotlin** + **Jetpack Compose** — Android UI
- **NexaSDK** with **OmniNeural-4B-mobile** — on-device VLM inference
- **Three.js** (r128) — WebGL 3D rendering via Android WebView
- **Qualcomm Hexagon NPU** — hardware-accelerated inference via FastRPC
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

This ensures reliable operation even when the 4B mobile VLM outputs explanatory text alongside JSON.

### Enhanced Prompt Engineering
The system uses aggressive formatting instructions to guide the VLM:
- Simplified current state presentation (shows only first 5 active morphs)
- Explicit JSON-only output requirements
- Concrete formatting examples in prompts
- Regional focus constraints for targeted modifications

## Architecture

```
User prompt → FaceMorphService → NexaService (VLM on Hexagon NPU)
                                        ↓
                              JSON morph parameters
                                        ↓
                MorphParameterParser (multi-strategy validation)
                                        ↓
                   WebViewBridge → Three.js face_viewer.html
                                        ↓
                 3D face rendered with smooth morph animation
```

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

The app maintains state across all modifications, allowing iterative refinement of the 3D face model.

## Building

1. Open the project in Android Studio
2. Sync Gradle (requires JDK 11+)
3. Build and run on a Qualcomm-powered Android device (minSdk 27)
4. The app will download the OmniNeural-4B-mobile model (~4.76 GB) on first launch
5. Initialize the model by clicking "BOOT CORE"
6. Start morphing with natural language prompts!

## Technical Details

### Key Components

- **[FaceMorphService.kt](app/src/main/java/com/facemorphai/service/FaceMorphService.kt)** — Core business logic for VLM prompt construction and state management
- **[NexaService.kt](app/src/main/java/com/facemorphai/service/NexaService.kt)** — Singleton wrapper for NexaSDK, handles NPU/CPU inference
- **[MorphParameterParser.kt](app/src/main/java/com/facemorphai/parser/MorphParameterParser.kt)** — Multi-strategy JSON parser with fallback extraction
- **[WebViewBridge.kt](app/src/main/java/com/facemorphai/bridge/WebViewBridge.kt)** — Android ↔ JavaScript IPC for 3D viewer communication
- **[face_viewer.html](app/src/main/assets/face_viewer.html)** — Three.js WebGL application with FBXLoader and morph target animation

### Robustness Features

The app is designed to handle the unpredictability of small on-device VLMs:

1. **Dynamic Blendshape Discovery** — Automatically detects available morph targets from loaded FBX models
2. **Retry Logic** — 2-attempt generation with automatic fallback strategies
3. **Comprehensive Logging** — Detailed logs for debugging VLM output and parsing issues (filter: `FaceMorphService` or `MorphParameterParser`)
4. **Graceful Degradation** — Falls back to CPU if Hexagon NPU is unavailable
5. **State Merging** — Intelligent parameter merging preserves non-zero values across requests

### Performance

- **Model Size:** ~4.76 GB (OmniNeural-4B-mobile)
- **Inference Speed:** ~2-5 seconds per prompt (varies by device)
- **Context Window:** 2048 tokens
- **Max Output Tokens:** 256 (sufficient for JSON morph parameters)

## 3D Model Credits

This project uses 3D models licensed under [Creative Commons Attribution 4.0](http://creativecommons.org/licenses/by/4.0/).

- **"Ally Pretty Beautiful Face Head Model With Hair"** by skullvez
  https://skfb.ly/oC7Jx
  Licensed under [CC-BY-4.0](http://creativecommons.org/licenses/by/4.0/)

- **"Lisa - Woman Head with BlendShapes"** by skullvez
  https://skfb.ly/o69ns
  Licensed under [CC-BY-4.0](http://creativecommons.org/licenses/by/4.0/)