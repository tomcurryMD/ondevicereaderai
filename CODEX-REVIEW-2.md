# Codex Review #2 — OnDeviceReaderAI (Post-Fix)

## Context

You are reviewing an Android app called **OnDeviceReaderAI** — a document reader (ePub, PDF, HTML) with local AI text-to-speech using Piper TTS via sherpa-onnx. The first Codex review found ~20 issues. All P0 and P1 issues have been fixed. The debug build now compiles and produces APKs.

This is review #2: verify the fixes landed correctly, find anything new, and confirm the app is Play Store ready.

## Repo

```
https://github.com/tomcurryMD/ondevicereaderai
```

Local path (if running locally):
```
C:\Users\curry\OneDrive\Desktop\readertomeai
```

## Build

```powershell
cd C:\Users\curry\OneDrive\Desktop\readertomeai
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME=(Join-Path (Get-Location) '.gradle')
.\gradlew.bat :app:assembleDebug
```

Note: `app/libs/sherpa-onnx-1.13.0.aar` (54 MB) is gitignored. Download from https://github.com/k2-fsa/sherpa-onnx/releases and place in `app/libs/` before building.

## What Changed Since Review #1

### P0 Fixes Applied
1. **Gradle settings** — `dependencyResolution` → `dependencyResolutionManagement`
2. **TTS voice asset pipeline** — Now downloads sherpa-onnx tar.bz2 model packs (model.onnx + tokens.txt + espeak-ng-data). Uses `walkTopDown()` for nested archive detection.
3. **sherpa-onnx constructor** — Changed to `OfflineTts(context.assets, config)` with proper `dataDir` field.
4. **Compile blocker** — Added `ExperimentalFoundationApi` import, wired `combinedClickable`.
5. **Gradle wrapper** — Added gradlew, gradlew.bat, gradle-wrapper.jar.

### P1 Fixes Applied
6. **Notification stop** — `handleStop()` now calls `ttsEngine.stop()` before stopping the service.
7. **Pause/resume** — AudioTrack playback now polls `playbackHeadPosition` instead of fixed delay. Handles pause/resume via AudioTrack state.
8. **Audio focus** — Added `AudioFocusRequest`, pauses on transient loss, stops on permanent loss, resumes on regain.
9. **MediaSession** — Added `MediaSessionCompat` with Play/Pause/Stop callbacks. Notification uses `MediaStyle` with session token for Bluetooth/headset controls.
10. **WebView reload** — Tracks `html.hashCode()` in `mutableIntStateOf`, only calls `loadDataWithBaseURL` when hash changes. No more spurious reloads.
11. **WebView security** — Strips `<script>`, `<iframe>`, `<form>` from all book content via Jsoup Safelist. JS bridge validates input ranges. Blocks all external navigation.
12. **EPUB images** — Inlined as base64 data URIs via `resolveImages()` in EpubParser.
13. **ACTION_VIEW intents** — MainActivity handles incoming file URIs, imports the document, and navigates to the reader.
14. **Threading** — All parsing moved to `Dispatchers.IO`. PDF page extraction no longer blocks main thread.
15. **Bookmark positions** — Now saves actual `currentScrollProgress` (0.0-1.0) instead of hardcoded 0.
16. **Notification permission** — Requests `POST_NOTIFICATIONS` on Android 13+.
17. **TTS highlighting** — `onSentenceStart` callback calls `highlightSentence(start, end)` via JS evaluator bridge. `stopTts()` calls `clearTtsHighlights()`.

### Engineering Fixes
18. **Room foreign keys** — Bookmarks and Highlights have `ForeignKey` with `CASCADE` delete + index on `bookId`.
19. **Room migration** — Proper `MIGRATION_1_2` that recreates tables with foreign keys instead of `fallbackToDestructiveMigration`.
20. **PDF HTML escaping** — `escapeHtml()` applied to all PDF text before injection into HTML.
21. **HTML sanitization** — Jsoup Safelist strips dangerous tags from imported HTML files.
22. **Archive path traversal protection** — `safeArchiveOutput()` validates canonical paths during tar extraction.
23. **Download validation** — Checks HTTP response code before extraction. Temp file cleanup in `finally` block.

### New Additions
24. **ABI splits** — Per-architecture APKs: arm64=41.8MB, armv7=40.4MB, universal=86.5MB (debug). Release AAB will be ~20-25MB per device.
25. **PDF/HTML TTS JS** — `DocumentParser.wrapInStyledHtml` now includes full `highlightSentence()`, `clearTtsHighlights()`, `scrollToProgress()` JavaScript (was previously missing, only ePub had it).
26. **Privacy policy** — Hosted at https://tomcurrymd.github.io/ondevicereaderai/privacy-policy.html
27. **Play Store listing** — Full description, keywords, screenshot concepts in `PLAY_STORE_LISTING.md`.
28. **Rename** — User-facing name changed from ReaderToMeAI to OnDeviceReaderAI.

## Files to Review (read all)

### Build & Config
- `build.gradle.kts` (root)
- `settings.gradle.kts`
- `app/build.gradle.kts` — ABI splits, dependencies, sherpa AAR
- `gradle.properties`
- `gradle/wrapper/gradle-wrapper.properties`
- `app/proguard-rules.pro`
- `.gitignore`
- `local.properties`

### Manifest & Resources
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/themes.xml`
- `app/src/main/res/xml/file_paths.xml`
- `app/src/main/res/drawable/` (all icons)

### Kotlin Source (app/src/main/java/com/readertomeai/)
- `MainActivity.kt`
- `ReaderToMeApp.kt`
- `data/model/Book.kt`
- `data/model/VoiceModel.kt`
- `data/database/AppDatabase.kt`
- `data/database/BookDao.kt`
- `data/repository/BookRepository.kt`
- `data/repository/SettingsRepository.kt`
- `epub/EpubParser.kt`
- `epub/DocumentParser.kt`
- `tts/TtsEngine.kt`
- `tts/TtsPlaybackService.kt`
- `ui/theme/Theme.kt`
- `ui/navigation/NavGraph.kt`
- `ui/library/LibraryScreen.kt`
- `ui/library/LibraryViewModel.kt`
- `ui/reader/ReaderScreen.kt`
- `ui/reader/ReaderViewModel.kt`
- `ui/settings/SettingsScreen.kt`
- `ui/settings/SettingsViewModel.kt`
- `ui/settings/VoiceManagerScreen.kt`
- `ui/settings/VoiceManagerViewModel.kt`

### Docs
- `README.md`
- `PRIVACY_POLICY.md`
- `PLAY_STORE_LISTING.md`
- `docs/privacy-policy.html`

---

## What I Need

### 1. Verify Previous Fixes
Go through each of the 28 fixes listed above. For each one, confirm:
- Does the fix actually work as described?
- Are there edge cases the fix misses?
- Did it introduce any new bugs?

### 2. Runtime Risk Assessment
Without running on a device, identify the top 5 things most likely to crash or misbehave at runtime, ranked by likelihood. For each:
- What will happen
- Which file/line
- How to fix it

### 3. Play Store Readiness Checklist
Check against Google Play requirements:
- Target SDK and permissions
- Privacy policy completeness
- Content rating compatibility
- App signing readiness
- Store listing completeness
- Any policy violations

### 4. Remaining Work Estimate
Give me a ranked punch list of everything that still needs to happen before publishing, with effort estimates. Separate into:
- **Must fix before publish** (would cause rejection or 1-star reviews)
- **Should fix before publish** (professional quality)
- **Nice to have post-launch** (v1.1 features)

### 5. One-Line Verdict
Is this app ready to publish to the Play Store? Yes/No and why in one sentence.
