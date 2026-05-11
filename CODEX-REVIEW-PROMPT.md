# Codex Review Prompt — OnDeviceReaderAI

## Context

You are reviewing a complete Android app project called **OnDeviceReaderAI**. It's a document reader (ePub, PDF, HTML) with local AI-powered text-to-speech using Piper TTS neural voice models via sherpa-onnx. Think of it as Readera Premium but with free, natural-sounding AI voices that run entirely on-device.

The app was scaffolded in one session and needs a thorough code review, architecture audit, and product/business strategy review before going to market.

## Project Location

```
C:\Users\curry\OneDrive\Desktop\OnDeviceReaderAI\
```

## File Inventory (read all of these)

### Build & Config
- `build.gradle.kts` — root build file
- `settings.gradle.kts` — project settings & repositories
- `gradle.properties` — Gradle JVM config
- `gradle/wrapper/gradle-wrapper.properties` — Gradle 8.11.1
- `app/build.gradle.kts` — app module: dependencies, SDK config, compose setup
- `app/proguard-rules.pro` — ProGuard/R8 rules
- `.gitignore`

### Manifest & Resources
- `app/src/main/AndroidManifest.xml` — permissions, activities, services, intent filters for epub/pdf/html
- `app/src/main/res/values/strings.xml` — all user-facing strings
- `app/src/main/res/values/colors.xml` — brand palette
- `app/src/main/res/values/themes.xml` — Material theme + splash
- `app/src/main/res/xml/file_paths.xml` — FileProvider paths
- `app/src/main/res/drawable/ic_launcher_foreground.xml` — adaptive icon foreground (book + headphones)
- `app/src/main/res/drawable/ic_launcher_background.xml` — adaptive icon background
- `app/src/main/res/drawable/ic_headphones.xml` — notification icon
- `app/src/main/res/drawable/ic_stop.xml` — notification stop action icon

### Core Kotlin Source (app/src/main/java/com/OnDeviceReaderAI/)
- `MainActivity.kt` — entry point, splash screen, Compose setup
- `ReaderToMeApp.kt` — Application class, singletons for DB/repos/TTS, notification channel

### Data Layer
- `data/model/Book.kt` — Room entities: Book, Bookmark, Highlight; data classes: TocEntry, ChapterContent
- `data/model/VoiceModel.kt` — VoiceModel data class, VoiceQuality enum, AvailableVoices catalog with HuggingFace URLs
- `data/database/AppDatabase.kt` — Room database singleton
- `data/database/BookDao.kt` — all Room queries: books CRUD, bookmarks, highlights, search, sort
- `data/repository/BookRepository.kt` — import logic (epub/pdf/html), file copying, cover extraction, CRUD wrappers
- `data/repository/SettingsRepository.kt` — DataStore preferences for reading settings, TTS settings, library prefs

### Document Parsing
- `epub/EpubParser.kt` — ePub parsing via epublib + Jsoup, chapter extraction, styled HTML generation for WebView, TOC, plain text for TTS
- `epub/DocumentParser.kt` — unified parser wrapping EpubParser + PdfBox-Android + Jsoup for PDF and HTML support

### TTS Engine
- `tts/TtsEngine.kt` — sherpa-onnx/Piper TTS integration: voice download from HuggingFace, model init, sentence splitting, AudioTrack playback, pause/resume/stop, coroutine-based
- `tts/TtsPlaybackService.kt` — foreground service for background TTS playback with notification controls

### UI Layer (Jetpack Compose + Material 3)
- `ui/theme/Theme.kt` — dark/light color schemes, brand colors (Purple #6C63FF, Coral #FF6584), reading-specific color sets (light/dark/sepia)
- `ui/navigation/NavGraph.kt` — Navigation Compose: Library → Reader, Settings, VoiceManager
- `ui/library/LibraryScreen.kt` — grid/list view, search, sort dropdown, import FAB, empty state, delete dialog, loading overlay
- `ui/library/LibraryViewModel.kt` — library state management, import, search, sort, delete
- `ui/reader/ReaderScreen.kt` — WebView reader, animated top/bottom bars, TTS controls (play/pause/stop/skip), TOC dialog, bookmarks dialog, reading settings bottom sheet
- `ui/reader/ReaderViewModel.kt` — chapter loading, navigation, TTS orchestration, settings sync, progress saving
- `ui/settings/SettingsScreen.kt` — voice speed slider, auto-scroll toggle, highlight toggle, theme picker, about section
- `ui/settings/SettingsViewModel.kt` — settings state
- `ui/settings/VoiceManagerScreen.kt` — voice model cards with download/select/delete, progress bar, info card
- `ui/settings/VoiceManagerViewModel.kt` — voice download orchestration

### Documentation
- `README.md` — features, tech stack, build instructions, voice model table, project structure

---

## What I Need From You

### 1. Code Review
Read every source file listed above. Identify:
- **Bugs** — anything that will crash or misbehave at runtime
- **Compile errors** — missing imports, wrong APIs, type mismatches
- **Architecture issues** — anti-patterns, memory leaks, lifecycle problems
- **Threading issues** — coroutine scope leaks, main thread blocking, race conditions
- **Android-specific issues** — permission handling gaps, lifecycle edge cases, WebView security, service lifecycle

### 2. Feature & UX Recommendations
What changes would make this app **maximally useful** to readers? Think about:
- What do Readera, Moon+ Reader, and Kindle users love that's missing here?
- What TTS-specific UX improvements would make the reading-aloud experience best-in-class?
- Accessibility gaps
- What would make someone choose this over existing apps?

### 3. Monetization & Market Strategy
How should this app make money? Consider:
- Free vs freemium vs paid model
- What features should be free vs premium?
- Google Play Store optimization (ASO) — name, description, keywords, screenshots
- Competitive positioning against Readera Premium ($10), Speechify ($139/yr), NaturalReader, BookFusion
- Revenue model that doesn't compromise the "free AI TTS" value proposition
- Realistic TAM and user acquisition strategy

### 4. Priority Action List
Give me a **ranked list of the top 20 changes** I should make before publishing to the Play Store, ordered by impact. For each:
- What to change
- Why it matters
- Estimated effort (hours)

Be brutally honest. I want to ship something good, not something half-baked.
