@echo off
echo Starting Drono App deployment...
echo.

rem Change to the Android app directory
cd android-app\IMadeThatBitchFamous

echo Building debug APK...
call .\gradlew assembleDebug

rem Check if build was successful
if %ERRORLEVEL% neq 0 (
    echo Build failed with exit code %ERRORLEVEL%
    exit /b %ERRORLEVEL%
)

echo Build successful!
echo.

rem Get list of connected devices
adb devices

echo.
echo Installing on target devices...

rem Install on specific devices
adb -s R38N9014KDM install -r .\app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% equ 0 (
    echo Installation successful on R38N9014KDM!
) else (
    echo Installation failed on R38N9014KDM with exit code %ERRORLEVEL%
)

adb -s R9WR310F4GJ install -r .\app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% equ 0 (
    echo Installation successful on R9WR310F4GJ!
) else (
    echo Installation failed on R9WR310F4GJ with exit code %ERRORLEVEL%
)

rem Return to root directory
cd ..\..

echo.
echo Deployment completed! 