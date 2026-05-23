# Open Source Notices - OnDeviceReaderAI

OnDeviceReaderAI uses Android, Google, and open-source components for document parsing, reading, on-device OCR, local text-to-speech, and Google Play Billing.

## Core Android and Google Components

- Android platform and AndroidX Jetpack libraries, including Compose, Activity, Lifecycle, Navigation, Room, DataStore, WebKit, Media, and SplashScreen: Apache License 2.0 unless otherwise stated by the Android Open Source Project or AndroidX artifact metadata.
- Kotlin standard library and Kotlin coroutines: Apache License 2.0.
- Google Play Billing Library: Google Play Billing and Android SDK terms.
- Google ML Kit Text Recognition: Google APIs Terms of Service and ML Kit Terms.

## Open-Source Libraries

- Coil: Apache License 2.0.
- Accompanist: Apache License 2.0.
- Apache PDFBox for Android / FontBox resources: Apache License 2.0.
- Apache Commons Compress: Apache License 2.0.
- jspecify: Apache License 2.0.
- epublib-core: LGPL 2.1 or later.
- jsoup: MIT License.
- SLF4J Android: MIT License, copyright (c) 2004-2022 QOS.ch Sarl.
- sherpa-onnx: Apache License 2.0.

## LGPL Notice

epublib-core is licensed under the GNU Lesser General Public License (LGPL). OnDeviceReaderAI uses the unmodified Maven artifact `nl.siegmann.epublib:epublib-core:3.1`. Users may replace or relink the LGPL component as permitted by the LGPL. Corresponding source is available from the upstream epublib project and Maven Central artifact metadata.

## Apache NOTICE Texts

Apache Commons Compress:

> Apache Commons Compress
> Copyright 2002-2024 The Apache Software Foundation
>
> This product includes software developed at
> The Apache Software Foundation (https://www.apache.org/).

Apache PDFBox / FontBox:

> Apache PDFBox and FontBox resources are developed by The Apache Software Foundation and distributed under the Apache License 2.0. PDFBox for Android is an Android port of Apache PDFBox.

## Local Voice Models and Speech Assets

- Piper / rhasspy Piper: MIT License.
- rhasspy/piper-voices voice packs used by the app, including Lessac, Ryan, Amy, LibriTTS, and Alba model packs: upstream repository license is MIT; individual voice/model cards may include additional dataset notes.
- Kokoro English v0.19 model pack used for Human Reader: Apache License 2.0.
- eSpeak NG data used by Piper/sherpa-onnx voice packs is included through upstream sherpa-onnx model assets and remains subject to upstream notices.

## References

- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
- MIT License: https://opensource.org/license/mit
- Google ML Kit Terms: https://developers.google.com/ml-kit/terms
- Google Play Billing: https://developer.android.com/google/play/billing
- Piper voices: https://huggingface.co/rhasspy/piper-voices
- Kokoro v0.19 model card: https://huggingface.co/hexgrad/kLegacy/blob/main/v0.19/README.md

This notice is provided for launch transparency and should be updated whenever dependencies or downloadable model packs change.
