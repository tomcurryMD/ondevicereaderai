# Privacy Policy - OnDeviceReaderAI

**Effective Date:** May 16, 2026
**Last Updated:** May 16, 2026

## Overview

OnDeviceReaderAI is a document reader with local text-to-speech and on-device OCR features. This policy explains what data the app uses and how it is handled.

## Data Collection

OnDeviceReaderAI does not operate an account system, advertising network, analytics backend, cloud text-to-speech service, or document-processing server. We do not sell your data, and your imported books and documents are not uploaded to our servers.

- No account is required.
- No advertising SDKs are included.
- No cloud text-to-speech is used by the app.
- Books and documents are not uploaded to our servers.

The app does include platform and SDK components that may handle limited data as part of their normal operation:

- Google ML Kit on-device OCR may collect device information, app information, per-installation identifiers, performance metrics, API configuration, event types, and error codes for diagnostics and usage analytics. ML Kit states that OCR input data and OCR output data are processed on device and are not sent to Google servers by the ML Kit API.
- Instant Reader sends text selected for speech to the Android text-to-speech engine selected on your device. That engine may be provided by Google, Samsung, or another Android TTS provider and is governed by that provider's settings and privacy practices.
- Google Play Billing handles Pro purchases. The app receives purchase status needed to unlock Pro and stores that entitlement locally.

## Local Processing

The app processes selected documents on your device.

- Imported ePub, PDF, and HTML files are copied into app storage.
- Reading progress, favorites, bookmarks, highlights, and preferences are stored locally.
- On-device OCR may be used to read scanned or image-based PDF pages aloud.
- Human Reader audio is prepared and cached locally on your device.

This local data is deleted when the app is uninstalled. Android app-data backup is disabled for OnDeviceReaderAI, so imported books, prepared audio, preferences, and local Pro cache are not included in Android Auto Backup by the app.

## Network Access

The app uses internet access for optional voice-model downloads, Google Play Billing, and SDK/platform maintenance such as ML Kit diagnostics or model updates.

When you choose to download a Human Reader or optional local voice model, the app downloads open-source model files from GitHub-hosted sherpa-onnx release assets. These downloads do not include your books or reading content.

Instant Reader uses the local text-to-speech engine installed on your Android device. Depending on your device and system settings, that engine may be provided by Google, Samsung, or another Android text-to-speech provider. OnDeviceReaderAI does not send your documents to our servers.

After required voice models are downloaded, the app can read documents without an internet connection.

## File Access

The app uses Android's document picker and file-open intents so you can choose ePub, PDF, HTML, or XHTML files. The app does not scan your device storage and does not access files you did not select.

## Third-Party Components

The app uses Android, Google Play Billing, Google ML Kit, and open-source components for document parsing, rendering, text-to-speech, OCR, and local audio preparation. Open-source license notices are available at:

https://github.com/tomcurryMD/ondevicereaderai/blob/codex/part-2-human-reader/OPEN_SOURCE_NOTICES.md

## Children's Privacy

The app does not knowingly collect personal data from children. Because the app does not collect personal data from any user, no child personal data is transmitted to us.

## Changes to This Policy

We may update this Privacy Policy from time to time. Changes will be posted at the privacy policy URL and will take effect when posted.

## Contact

If you have questions about this Privacy Policy, contact:

**Email:** curry.tom.e@gmail.com
