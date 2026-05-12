# Claude Code Handoff — OnDeviceReaderAI

## What This Is

OnDeviceReaderAI is an Android app (Kotlin, Jetpack Compose, Material 3) that reads ePub, PDF, and HTML documents aloud using local AI text-to-speech (Piper TTS via sherpa-onnx). All TTS runs on-device with no cloud dependency.

## Repo

```
https://github.com/tomcurryMD/ondevicereaderai
```

Local path:
```
C:\Users\curry\OneDrive\Desktop\readertomeai
```

## Current Status

The app has been through two full code reviews by Codex and a separate architecture review. All P0 and P1 issues have been fixed. The release AAB builds successfully at ~65 MB. No known compile errors or release blockers remain.

**What works (verified in code, needs device testing):**
- ePub/PDF/HTML import and rendering via WebView
- Piper TTS voice model download from sherpa-onnx GitHub releases
- TTS playback with sentence highlighting and auto-scroll
- Pause/resume/stop with proper AudioTrack lifecycle
- Audio focus handling (pauses for calls, resumes after)
- MediaSession for Bluetooth/headset controls
- Notification with Pause/Resume/Stop that rebuilds on state change
- Bookmarks with scroll position restore
- Light/Dark/Sepia themes, font size, line spacing, font family
- Table of Contents navigation
- Reading progress saved and restored
- ACTION_VIEW intent handling (open files from other apps)
- Privacy: all remote resources stripped from book content + WebView blocks remote loads
- Room database with foreign keys and proper migration
- ABI splits for smaller per-device APKs
- Privacy policy hosted at https://tomcurrymd.github.io/ondevicereaderai/privacy-policy.html

## Build Commands

```powershell
cd C:\Users\curry\OneDrive\Desktop\readertomeai
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME=(Join-Path (Get-Location) '.gradle')

# Debug APK (with ABI splits)
.\gradlew.bat :app:assembleDebug

# Release AAB (for Play Store upload)
.\gradlew.bat :app:bundleRelease
```

**Important:** `app/libs/sherpa-onnx-1.13.0.aar` (54 MB) is gitignored. Download from https://github.com/k2-fsa/sherpa-onnx/releases and place in `app/libs/` before building.

## What Needs To Happen Next

### 1. Device Testing (CRITICAL — do first)
The TTS pipeline has never been tested on a real device. This is the single biggest risk. Test on an arm64 Android phone or emulator:

- Install the debug APK: `adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- Open the app, import an ePub file
- Go to Settings → Voice Models → Download "Amy Low (US)" (~64 MB voice + ~7 MB shared espeak data on first voice download)
- Select the voice, go back to the reader
- Press Play — verify audio plays, sentence highlights, auto-scrolls
- Press Pause — verify audio pauses, notification shows "Resume"
- Press Resume — verify audio resumes from where it stopped
- Press Stop — verify highlights clear, notification dismissed
- Test notification controls (Pause/Stop from notification shade)
- Test with a PDF and an HTML file too
- Check Logcat for any crashes or sherpa-onnx errors

If TTS fails on device, the most likely causes are:
- sherpa-onnx native library not loading (check `System.loadLibrary` in logcat)
- Voice model archive not extracting correctly (check `tts_models/` directory structure)
- espeak-ng-data not found (check the `dataDir` path passed to OfflineTtsVitsModelConfig)

### 2. App Signing
The release AAB at `app/build/outputs/bundle/release/app-release.aab` is currently unsigned (uses debug signing). For Play Store:
- Create a keystore: `keytool -genkey -v -keystore ondevicereaderai.keystore -keyalg RSA -keysize 2048 -validity 10000 -alias ondevicereaderai`
- Add signing config to `app/build.gradle.kts` release buildType
- Rebuild with `.\gradlew.bat :app:bundleRelease`
- Or use Play App Signing (recommended) — upload the debug-signed AAB and let Google manage the key

### 3. Play Store Submission
- Create Google Play Console account ($25 one-time) at https://play.google.com/console
- Create app listing using content from `PLAY_STORE_LISTING.md`
- Privacy policy URL: `https://tomcurrymd.github.io/ondevicereaderai/privacy-policy.html`
- Take screenshots on device (or emulator) for the listing — need at least 2, recommend 6-8
- Complete content rating questionnaire
- Upload the signed release AAB
- Submit for review

### 4. Nice-to-Have Before Launch
These are not blockers but would improve first impressions:
- Add a simple onboarding flow (first launch → "Import a book" → "Download a voice" → "Press play")
- Add a sleep timer for TTS playback
- Add "read from here" — tap a paragraph to start TTS from that position
- Better app icon (current one is a vector placeholder)
- Add a proper pause/play icon drawable instead of reusing ic_headphones for both

### 5. Post-Launch (v1.1)
- More voice models (other languages, high-quality English)
- Pronunciation dictionary for proper nouns
- Calibre/OPDS library import
- Search inside book
- Notes export
- Volume key page turning

## Architecture Overview

```
app/src/main/java/com/readertomeai/
├── MainActivity.kt              — Entry point, intent handling, notification permission
├── ReaderToMeApp.kt             — Application singleton, DB/repo/TTS initialization
├── data/
│   ├── database/                — Room DB (AppDatabase, BookDao) with FK migration
│   ├── model/                   — Book, Bookmark, Highlight entities; VoiceModel catalog
│   └── repository/              — BookRepository (import/CRUD), SettingsRepository (DataStore)
├── epub/
│   ├── EpubParser.kt            — ePub parsing, image inlining, content sanitization
│   └── DocumentParser.kt        — Unified ePub/PDF/HTML parser with shared TTS JS
├── tts/
│   ├── TtsEngine.kt             — sherpa-onnx Piper TTS: download, init, play, pause, stop
│   └── TtsPlaybackService.kt    — Foreground service, MediaSession, notification
└── ui/
    ├── theme/Theme.kt           — Material 3 dark/light + reading color sets
    ├── navigation/NavGraph.kt   — Compose navigation
    ├── library/                 — Library grid/list with search, sort, import
    ├── reader/                  — WebView reader, TTS controls, bookmarks, settings sheet
    └── settings/                — Settings, voice model download manager
```

## Key Design Decisions
- **sherpa-onnx AAR is bundled locally** (`app/libs/`) not from Maven, because the Maven artifact was unreliable
- **WebView renders all content** — ePub chapters are converted to styled HTML; PDF pages are text-extracted and wrapped in HTML; imported HTML is sanitized. This keeps one rendering path.
- **TTS generates audio per-sentence** — splits text at sentence boundaries, generates each with sherpa-onnx, plays via AudioTrack with position polling for pause/resume
- **All remote resources blocked at two levels** — HTML sanitization strips URLs before rendering, WebView intercepts and blocks remaining requests
- **Package name is `com.readertomeai`** — the display name is "OnDeviceReaderAI" but internal package wasn't renamed to avoid breaking every file

## Don't
- Don't switch from Piper to Kokoro or any other TTS engine
- Don't rename the internal package — it touches every file and the database
- Don't add cloud/API dependencies — the offline-first privacy story is the product differentiator
- Don't add ads or tracking — the monetization plan is freemium features, not ads
