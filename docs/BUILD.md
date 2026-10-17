# Android build

Follow START_HERE.md for the complete Windows/macOS walkthrough. The root Gradle project is the folder containing `settings.gradle`, not the `app` subfolder.

Pinned build: Android Gradle Plugin 8.9.2, Gradle 8.11.1, SDK 35, JDK 17/21, min Android API 26. See [AGP compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes). The official wrapper JAR and Gradle distribution SHA-256 values come from [Gradle's checksum list](https://gradle.org/release-checksums/).

The wrapper JAR is bootstrapped from Gradle's official repository because download was unavailable in this environment. `tools/Bootstrap.java` is readable source, launched by the local JDK. It validates the downloaded JAR before the wrapper runs. The Gradle properties validate the distribution ZIP too. First build requires access to official dependency hosts. Subsequent offline builds additionally require all Gradle/Maven caches; this is distinct from offline operation of an installed app.

Commands:

```text
Windows: gradlew.bat assembleDebug lintDebug
Mac:     sh gradlew assembleDebug lintDebug
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`. A connected phone can be installed through Android Studio Run, or with `adb install -r app/build/outputs/apk/debug/app-debug.apk` once the build succeeds. Do not distribute a debug build as a medically validated application.

Release builds are unsigned unless the owner supplies their signing configuration. No credentials or signing keys are in this repository. Do not commit `local.properties`, SDK directories, Gradle caches or private keys.

This delivery environment contains Java 17 compiler modules, Python and Git. It has no Android SDK, emulator or physical phone. The observed bootstrap attempt did not retrieve the wrapper JAR, and therefore did not reach Android compilation. Java syntax parsing and domain tests cannot prove ARCore/ML Kit API compatibility, merged-manifest behavior, rendering or camera operation.
