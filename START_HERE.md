# Start here — Windows or Mac

This is the **native Android project**. You do not need Unity. It runs on an Android phone; Android Studio on your Windows PC or Mac builds and installs it. An emulator can help inspect screens, but does not validate scanning real packages or the phone's AR performance.

**This archive contains source, tests and a small local knowledge database. An APK has not been built or device-tested here.** The first Android build may expose integration issues that the available pure-Java checks cannot detect.

1. Download and install [Android Studio](https://developer.android.com/studio). Choose the normal setup, including the Android SDK. Let the installation finish.
2. Extract this ZIP completely. On Windows, use a short path such as `C:\Projects\MediLensAndroid`. On Mac, use `~/Projects/MediLensAndroid`. Do not open files directly inside the ZIP.
3. Windows: double-click `SETUP-WINDOWS.bat`. Mac: open Terminal in the extracted project folder and run `sh SETUP-MAC.command`. These scripts use Android Studio's bundled Java when JAVA_HOME is not set, download the official Gradle wrapper, verify it, and download Gradle. They need an internet connection. A JDK 17 or 21 is required for the pinned build. If your JAVA_HOME points to another version, select the correct JDK first.
4. In Android Studio choose **Open** and select the **MediLensAndroid folder containing settings.gradle**. Wait for Gradle sync. If asked for a Gradle JDK, choose Android Studio's JDK 17/21, or download JDK 17 from the Gradle JDK selector. Keep the pinned Gradle/plugin versions for the first build.
5. Open **Tools → SDK Manager**. Install **Android 15 / API 35**, **Android SDK Build-Tools 35.0.0** and **Android SDK Platform-Tools**. Accept their licenses if you agree. Android Studio normally creates `local.properties` with the SDK location automatically.
6. Use an [ARCore-supported Android phone](https://developers.google.com/ar/devices) running Android 8.0 or newer. The project targets Android 15 APIs and allows Android 8+. A modern Android 12+ phone with good lighting is preferable for evaluation. Install/update **Google Play Services for AR** on the phone.
7. On the phone, enable Developer options and USB debugging. Connect by USB and approve the phone's debugging prompt. On Windows, install the manufacturer's USB driver if Android Studio does not list the device. Mac normally does not need an OEM driver.
8. Select the phone in Android Studio's device menu, select the `app` run configuration and press **Run ▶**. Allow camera permission on the phone. Complete any AR Services installation prompt and return to the app.
9. Open **Supported** before scanning. Only the seven listed formulations are currently recognized. Keep the complete brand, ingredient, strength and variant readable together; hold still for repeated observations. Tap a label or **Scan results** for details.
10. Use **My plan** to enter a date/time from your own regimen. The app does not invent a schedule, recommend a dose, or send background reminders.

To produce an installable debug APK, use Android Studio's **Build APK(s)** action, or its Terminal:

```text
Windows:  .\gradlew.bat assembleDebug lintDebug
Mac:      sh gradlew assembleDebug lintDebug
```

After a successful build, the APK is at `app/build/outputs/apk/debug/app-debug.apk`. Debug signing is automatic and for local testing only. A Play Store release requires your signing key and separate release review; no key is included.

If setup stops:

| Message/symptom | Action |
|---|---|
| Java not found | Install Android Studio; verify its bundled JDK or set JAVA_HOME to JDK 17/21. |
| Download timeout | Allow normal access to GitHub raw content, Gradle, Google Maven and Maven Central; inspect corporate proxy settings. Never disable checksum validation. |
| SDK location missing | Open the root project in Android Studio and select your SDK in SDK Manager. |
| License not accepted | Accept the required SDK license in Android Studio if you agree. |
| Phone absent | Unlock it, approve USB debugging and check the USB cable/driver. |
| AR unavailable | Check the supported-device list, camera permission and Google Play Services for AR. Use Help → Retry camera. |
| Every package unresolved | Check the small Supported catalog. The current OCR-region baseline cannot join brand/composition on separate panels. It is not a general medicine scanner yet. |

The remaining physical validation step is to run this build on an ARCore phone. The real package dataset/model described in the PDF is also needed for the intended robust multi-package detector. No additional Unity setup is needed.
