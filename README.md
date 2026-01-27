# FaceCraft

An Android app for AI-powered 3D face morphing, built for the **Nexa × Qualcomm On-Device AI Hackathon**.

Describe facial modifications in natural language — "make the eyes bigger", "sharpen the jawline" — and watch them applied in real time on an interactive 3D face rendered with Three.js. All inference runs on-device using a Vision Language Model accelerated by the Qualcomm Hexagon NPU.

## Features

- Natural language face modification prompts processed by an on-device VLM
- Real-time 3D face visualization powered by Three.js and WebGL
- On-device inference using NexaSDK with OmniNeural-4B-mobile on Qualcomm Hexagon NPU
- 34 morph parameters across 8 face regions (eyes, nose, jaw, cheeks, mouth, forehead, face shape)
- Multiple face templates (Ally, Lisa) with ARKit blend shape support
- Smooth morph animations with interactive OrbitControls for 3D navigation
- CPU fallback when Hexagon NPU is unavailable

## Tech Stack

- **Kotlin** + **Jetpack Compose** — Android UI
- **NexaSDK** with **OmniNeural-4B-mobile** — on-device VLM inference
- **Three.js** (r128) — WebGL 3D rendering via Android WebView
- **Qualcomm Hexagon NPU** — hardware-accelerated inference via FastRPC
- **Kotlinx Serialization** + **Kotlinx Coroutines** — JSON handling and async operations

## Architecture

```
User prompt → FaceMorphService → NexaService (VLM on Hexagon NPU)
                                        ↓
                              JSON morph parameters
                                        ↓
                        MorphParameterParser (validation)
                                        ↓
                   WebViewBridge → Three.js face_viewer.html
                                        ↓
                         3D face rendered with morph applied
```

## Building

1. Open the project in Android Studio
2. Sync Gradle (requires JDK 11+)
3. Build and run on a Qualcomm-powered Android device (minSdk 27)
4. The app will download the OmniNeural-4B-mobile model (~4.76 GB) on first launch

## 3D Model Credits

This project uses 3D models licensed under [Creative Commons Attribution 4.0](http://creativecommons.org/licenses/by/4.0/).

- **"Ally Pretty Beautiful Face Head Model With Hair"** by skullvez
  https://skfb.ly/oC7Jx
  Licensed under [CC-BY-4.0](http://creativecommons.org/licenses/by/4.0/)

- **"Lisa - Woman Head with BlendShapes"** by skullvez
  https://skfb.ly/o69ns
  Licensed under [CC-BY-4.0](http://creativecommons.org/licenses/by/4.0/)