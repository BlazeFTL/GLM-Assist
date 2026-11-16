<div align="center">

<img src="https://capsule-render.vercel.app/api?type=waving&height=180&color=0:4F46E5,100:06B6D4&text=glmAssist&fontColor=ffffff&fontSize=58&fontAlignY=38&desc=GLM%20chat%20in%20a%20tiny%20native%20wrapper&descAlignY=60&descSize=16&section=header" width="100%" alt="glmAssist"/>

**A lightweight, open-source Android app for [GLM Chat](https://chat.z.ai/).**
No bloat, no trackers — just the full Z.ai experience in ~49 KB.

[![Release](https://img.shields.io/github/v/release/BlazeFTL/Z-AI-App-GLM?style=for-the-badge&logo=github&color=4F46E5)](https://github.com/BlazeFTL/Z-AI-App-GLM/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/BlazeFTL/Z-AI-App-GLM/main.yml?style=for-the-badge&logo=githubactions&logoColor=white&label=CI)](https://github.com/BlazeFTL/Z-AI-App-GLM/actions/workflows/main.yml)
[![Downloads](https://img.shields.io/github/downloads/BlazeFTL/Z-AI-App-GLM/total?style=for-the-badge&color=06B6D4)](https://github.com/BlazeFTL/Z-AI-App-GLM/releases)
[![License](https://img.shields.io/github/license/BlazeFTL/Z-AI-App-GLM?style=for-the-badge&color=22C55E)](LICENSE)

![Android](https://img.shields.io/badge/Android-8.0%2B-34A853?logo=android&logoColor=white)
![Java](https://img.shields.io/badge/Java-100%25-ED8B00?logo=openjdk&logoColor=white)
![APK](https://img.shields.io/badge/APK-~49%20KB-blue)
![Targets](https://img.shields.io/badge/targets-Android%2015%20(SDK%2035)-informational)

</div>

---

## Overview

**GLMAsist** wraps [chat.z.ai](https://chat.z.ai/) in a plain Android `WebView` — nothing blocked, nothing injected, nothing collected. It behaves like the desktop site while adding the native pieces a browser tab can't do: file pickers, mic/camera prompts, real downloads, and image saving.

Built in pure Java with a single dependency (`androidx.webkit`), minified and resource-shrunk to a **~49 KB APK**.

## Features

- **Unrestricted WebView** — no URL blocking; all in-site navigation stays in the app, external links open in the right app
- **File uploads** — system file picker with multi-select support
- **Voice & camera** — runtime permission prompts for mic and camera, granted only when the site asks
- **Full download support** — regular, `blob:`, and `data:` URLs saved to `Downloads`, with cookies and headers preserved
- **Save images** — long press any image to download it
- **Persistent sessions** — cookies kept and flushed on pause; stay logged in
- **Desktop experience** — spoofed desktop-class user agent for the full site
- **Day/night theme** — follows the system on Android 10+
- **Native back navigation** — back button walks WebView history
- **Tiny & private** — no analytics, no ads, `allowBackup=false`, no unnecessary permissions

## Download

<div align="center">

[![Get the APK](https://img.shields.io/badge/Download-glmAssist.apk-4F46E5?style=for-the-badge&logo=android&logoColor=white)](https://github.com/BlazeFTL/Z-AI-App-GLM/releases/latest/download/glmAssist.apk)

</div>

Grab the latest build from [Releases](https://github.com/BlazeFTL/Z-AI-App-GLM/releases). Requires **Android 8.0 (API 26)** or newer. Enable installation from unknown sources when prompted.

## Build from source

```bash
git clone https://github.com/BlazeFTL/Z-AI-App-GLM.git
cd Z-AI-App-GLM
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Requires JDK 8+ and Android SDK 35. Release builds are signed, minified (`proguard-android-optimize`), and resource-shrunk; CI publishes them automatically on every push.

## Permissions

| Permission | Why | When |
|---|---|---|
| `INTERNET` | Load chat.z.ai | Always |
| `RECORD_AUDIO` | Voice input | Only when the site requests the mic |
| `CAMERA` | Camera input | Only when the site requests the camera |
| `WRITE_EXTERNAL_STORAGE` | Save downloads | Android 9 and below only |

Camera and microphone hardware are optional (`required=false`).

## How it works

- **Blob downloads** — a `WebMessageListener` bridge scoped to `https://chat.z.ai` receives blob content as base64 and writes it via `MediaStore` (API 29+) or the public Downloads directory. `URL.revokeObjectURL` is delayed 60 s so links stay fetchable.
- **HTTP downloads** — routed through `DownloadManager` with session cookies, matching user agent, and referer attached.
- **Data URLs** — decoded (base64 or percent-encoded) and saved on a background thread.
- **Filenames** — taken from `Content-Disposition`, else guessed from MIME type, with collision-safe naming (`GLM_yyyyMMdd_HHmmss` fallback).

## Project structure

```
app/src/main/
├── java/io/github/blazeftl/glmassist/
│   └── MainActivity.java      # entire app logic
├── res/                       # icons, layout, strings
└── AndroidManifest.xml
.github/workflows/main.yml     # CI build + release
```

## Credits & license

Based on [geminiAssist](https://github.com/divested-mobile/geminiassist), which builds on **gptAssist** and **GMaps WV** by [Divested Computing Group](https://divested.dev/).

Licensed under **GPL-3.0** — see [LICENSE](LICENSE).

> **Disclaimer:** glmAssist is an unofficial wrapper and is not affiliated with Z.ai or Zhipu AI. All chat content and accounts are handled by chat.z.ai under their terms.

<div align="center">

<img src="https://capsule-render.vercel.app/api?type=waving&height=100&color=0:4F46E5,100:06B6D4&section=footer" width="100%" alt=""/>

</div>
