# Status — 2026-09-21

Current milestone: native Android source preview; **P0 release incomplete**.

Implemented: native camera UI; ARCore session and camera texture; bundled OCR; same-frame rotation/coordinate mapping; bounded asynchronous requests; three-observation resolver; IoU text-region tracks; short stale expiry; depth-only anchors and screen fallback; local knowledge; duplicate/DDI/unknown reasoning; local encrypted occurrences and dose-event history; provenance sheets; permission/error handling; setup scripts; dataset tooling.

Verified locally:

- Java 17 core compilation: passed.
- Java domain specifications: 61 passed, 0 failed.
- Python SQLite, dataset and metric tests: 49 passed, 0 failed.
- Java syntax parsing: 16 files, 0 errors. Android API linking remains unverified.
- SQLite integrity/foreign keys/checksum/provenance: passed.

Build attempt: `sh gradlew assembleDebug` exited before Gradle setup completed. The official wrapper JAR was not retrieved. No Android SDK, build tools or connected phone are available in this workspace. No APK was generated. See `evaluation/android-build-attempt.txt` and `evaluation/environment.json`.

Known unfinished release requirements:

- Android dependency resolution, full compilation, lint and APK generation.
- Real phone session lifecycle, camera texture/rotation, OCR availability and overlay alignment tests.
- A trained package detector and per-package crops. Current regions are OCR blocks, not package bounding boxes.
- Actual camera/open-set evaluation and any claim of identification accuracy.
- Broader intended-region medicine coverage with qualified clinical review. Current source subset has 32 formulations (including selected Indian-market brands) and 1 interaction row; recognition coverage must not be mistaken for interaction coverage.
- Recurring regimens and background reminders. Current plan stores individual user-entered occurrences.
- Airplane-mode device test and measured phone P50/P95.

No automated tests are currently failing. No device performance result is claimed. The original Unity repository is preserved separately; this project is the new native Android path.

Next gates: build with Android SDK → fix any compiler/lint issues → phone capture/geometry tests → integrate real detector/data → validate safety and open set → expand clinical catalog → release APK.
