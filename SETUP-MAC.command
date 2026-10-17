#!/bin/sh
cd "$(dirname "$0")" || exit 1
sh gradlew --version
result=$?
if [ "$result" -eq 0 ]; then
  printf '\nSetup complete. Open this MediLensAndroid folder in Android Studio.\n'
else
  printf '\nSetup failed. Install Android Studio first and check your internet connection.\n'
fi
exit "$result"
