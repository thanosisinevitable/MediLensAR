@echo off
cd /d "%~dp0"
call gradlew.bat --version
if errorlevel 1 (
 echo Setup failed. Install Android Studio first and check your internet connection.
) else (
 echo Setup complete. Open this MediLensAndroid folder in Android Studio.
)
pause
