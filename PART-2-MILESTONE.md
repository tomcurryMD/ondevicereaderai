# Part 2 Milestone

Date: 2026-05-13

This checkpoint marks the end of Part 2: Human Reader is working on the connected Android device.

## Confirmed

- Instant Reader playback is working smoothly enough for live reading.
- Human Reader now produces audible local playback instead of silent highlighted scrolling.
- The Human Reader model was switched to Kokoro English (`kokoro-en-v0_19`) with silent-output guards.
- Human Reader preparation can cache generated chapter audio for later playback.
- The reader UI supports Instant Reader vs Human Reader selection.
- The library now supports starred favorites and a finished-books section.
- Release APK builds, signs, installs, and launches on the connected phone.

## Current User-Verified Milestone

The user confirmed: dictation and the Human Reader voice are working.

## Part 3 Starts From Here

Part 3 should focus on polish, stability, and store-readiness:

- Stress-test Human Reader preparation across whole books and partial caches.
- Confirm background preparation does not stall or get killed unexpectedly.
- Validate pause/resume, chapter transitions, and cache playback after app restart.
- Continue refining library and reader UX from real phone testing.
- Prepare Play Store packaging, signing, pricing, and listing materials.

## Guardrails

- Keep the app local-first.
- Do not switch away from Piper for Instant Reader without explicit approval.
- Do not add cloud TTS, ads, or tracking.
- Do not rename the package unless explicitly required for release.
