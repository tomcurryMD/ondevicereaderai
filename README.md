# OnDeviceReaderAI

A polished Android document reader with local AI-powered text-to-speech. Reads ePub, PDF, and HTML files with natural-sounding neural voices — completely free, no cloud required.

## Features

### Document Reader
- Open ePub, PDF, and HTML files from your device — just like Readera
- Clean, distraction-free reading experience
- Three reading themes: Light, Dark, and Sepia
- Adjustable font size, line spacing, and font family (Sans, Serif, Mono)
- Table of Contents navigation
- Bookmarks and reading progress tracking
- Remembers your position across sessions

### AI Text-to-Speech
- Natural-sounding neural voices powered by Piper TTS (via sherpa-onnx)
- 100% on-device — your books never leave your phone
- Multiple voice models to choose from (English US/UK)
- Adjustable speech speed (0.5x to 2.0x)
- Sentence-level highlighting during narration
- Auto-advances through chapters
- Background playback with notification controls

### Library Management
- Import ePubs, PDFs, and HTML files from anywhere on your device
- Cover art extraction and display
- Grid and list view modes
- Sort by title, author, recently read, or recently added
- Search across your library

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material Design 3
- **Document Parsing:** epublib + Jsoup (ePub), PdfBox-Android (PDF), Jsoup (HTML)
- **TTS Engine:** Piper TTS via sherpa-onnx (ONNX neural models)
- **Database:** Room (SQLite)
- **Preferences:** DataStore
- **Image Loading:** Coil
- **Min SDK:** Android 8.0 (API 26)

## Getting Started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 35

### Build & Run

1. Clone or copy this project folder
2. Open the project in Android Studio
3. Let Gradle sync (this will download all dependencies)
4. Connect an Android device or start an emulator
5. Click Run (or `./gradlew installDebug`)

### First Launch

1. The app opens to an empty library
2. Tap **+ Import** to pick an ePub file from your device
3. Open the book and start reading
4. Go to **Settings > Voice Models** to download a voice
5. Once downloaded, tap the Play button in the reader to hear your book read aloud

## Voice Models

Voice models are downloaded from Hugging Face (Piper voices). Available voices:

| Voice | Language | Quality | Size |
|-------|----------|---------|------|
| Amy (US) | en-US | Medium | 63 MB |
| Lessac (US) | en-US | Medium | 63 MB |
| LibriTTS (US) | en-US | Medium | 75 MB |
| Alba (UK) | en-GB | Medium | 63 MB |
| Amy Low (US) | en-US | Low | 16 MB |

Models run on CPU — no GPU needed. After downloading, voices work completely offline.

## Project Structure

```
app/src/main/java/com/OnDeviceReaderAI/
├── MainActivity.kt              # Entry point
├── ReaderToMeApp.kt             # Application class
├── data/
│   ├── database/                # Room DB + DAOs
│   ├── model/                   # Data classes
│   └── repository/              # Data access layer
├── epub/
│   ├── EpubParser.kt           # ePub file parsing (with image inlining + sanitization)
│   └── DocumentParser.kt       # Unified parser: ePub + PDF + HTML
├── tts/
│   ├── TtsEngine.kt            # Piper TTS integration
│   └── TtsPlaybackService.kt   # Background playback
└── ui/
    ├── library/                 # Library/home screen
    ├── reader/                  # Book reader + WebView
    ├── settings/                # Settings + voice manager
    ├── theme/                   # Material 3 theme
    └── navigation/              # Nav graph
```

## Known Limitations

- Some store-bought ePubs with heavy encryption may fail to parse — this is the same for Readera, Moon+ Reader, and every other third-party reader
- Password-protected PDFs need the password removed first
- Initial TTS latency on first sentence while the neural model warms up

## License

MIT — do whatever you want with it.

## Credits

- [Piper TTS](https://github.com/rhasspy/piper) — neural text-to-speech engine
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — Android ONNX runtime for Piper
- [epublib](https://github.com/psiegman/epublib) — ePub parsing
- [Jsoup](https://jsoup.org/) — HTML parsing
