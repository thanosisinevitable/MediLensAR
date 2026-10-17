@echo off
setlocal
cd /d "%~dp0"
set "MEDILENS_JAVA=java"
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "MEDILENS_JAVA=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe"
if defined JAVA_HOME set "MEDILENS_JAVA=%JAVA_HOME%\bin\java.exe"
"%MEDILENS_JAVA%" tools\Bootstrap.java
if errorlevel 1 exit /b 1
"%MEDILENS_JAVA%" -classpath gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain %*
exit /b %errorlevel%
