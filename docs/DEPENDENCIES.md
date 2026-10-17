# Dependencies and source references

| Dependency | Pin | Reason / consideration |
|---|---|---|
| Android Gradle Plugin | 8.9.2 | SDK 35, Gradle 8.11.1, JDK 17-compatible toolchain |
| Gradle | 8.11.1 | Official wrapper and distribution SHA-256 checks |
| ARCore Android SDK | 1.48.0 | Android motion tracking, camera frames, depth hit tests; supported phone and AR Services required |
| ML Kit text recognition | 16.0.1 | Bundled Latin-script OCR; model available with APK, unlike downloadable Play Services variant |
| SQLite / Android Keystore | Android platform | Avoid extra storage/crypto dependency and bridge |
| Ultralytics/TensorFlow | Optional tooling pins in requirements file | Training/export only; review licenses and install separately |

Authoritative references: [ARCore setup](https://developers.google.com/ar/develop/java/enable-arcore), [ARCore Frame API](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame), [SDK releases](https://github.com/google-ar/arcore-android-sdk/releases), [bundled ML Kit OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [AGP 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes), [Gradle checksums](https://gradle.org/release-checksums/).

ARCore/ML Kit are governed by their provider terms and contain redistributed components; the source ZIP does not relicense them. The app includes no model weights downloaded from arbitrary repositories. Transitive dependency/security review and full Android lint require successful dependency resolution and remain release gates. No claim of a completed security audit is made.
