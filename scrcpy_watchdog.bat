@echo off
setlocal enabledelayedexpansion
title Windows Android - Scrcpy Zero-Touch Rescue Watchdog

:: Target package configuration for broken display recovery
set PKG_NAME=com.aistudio.windowsandroid.uqdvkf
set LOG_EXTERNAL_PATH=/sdcard/Android/data/%PKG_NAME%/files/scrcpy_rescue.log

echo ================================================================
echo      WINDOWS ANDROID - SCRCPY RESCUE WATCHDOG FOR BROKEN SCREENS
echo ================================================================
echo.
echo Checking for ADB and scrcpy prerequisites...

where adb >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] 'adb' was not found in your PATH!
    echo Please install Android Platform Tools and add adb.exe to PATH.
    pause
    exit /b 1
)

where scrcpy >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] 'scrcpy' was not found in your PATH!
    echo Please download scrcpy from https://github.com/Genymobile/scrcpy
    pause
    exit /b 1
)

echo [OK] adb and scrcpy detected in PATH.
echo.
echo Restarting ADB Server to clear any stale USB endpoints...
adb kill-server
adb start-server
echo.
echo Waiting for device connection...
echo [INFO] Plug in your USB cable now.
echo [INFO] Windows Android will automatically enable ADB and auto-allow authorization.
echo.

:WATCH_LOOP
set DEVICE_FOUND=0
set DEVICE_STATE=none
set SERIAL=

for /f "skip=1 tokens=1,2" %%A in ('adb devices') do (
    if not "%%A"=="" (
        set SERIAL=%%A
        set DEVICE_STATE=%%B
        set DEVICE_FOUND=1
        goto :PROCESS_STATE
    )
)

:PROCESS_STATE
if "!DEVICE_FOUND!"=="0" (
    echo [%TIME%] No device detected. Connect USB cable... (Retrying in 2s)
    timeout /t 2 /nobreak >nul
    goto :WATCH_LOOP
)

if "!DEVICE_STATE!"=="unauthorized" (
    echo [%TIME%] Device !SERIAL! is UNAUTHORIZED.
    echo         Windows Android Accessibility Service is auto-clicking 'Always allow' on device...
    echo         Fix command for permissions:
    echo         adb shell pm grant %PKG_NAME% android.permission.WRITE_SECURE_SETTINGS
    timeout /t 2 /nobreak >nul
    goto :WATCH_LOOP
)

if "!DEVICE_STATE!"=="offline" (
    echo [%TIME%] Device !SERIAL! is OFFLINE. Re-plugging USB recommended...
    timeout /t 3 /nobreak >nul
    goto :WATCH_LOOP
)

if "!DEVICE_STATE!"=="device" (
    echo.
    echo ================================================================
    echo [%TIME%] SUCCESS: Device !SERIAL! is AUTHORIZED AND READY!
    echo ================================================================
    
    :: Ensure WRITE_SECURE_SETTINGS permission is granted
    echo [INFO] Ensuring WRITE_SECURE_SETTINGS permission is active...
    adb -s !SERIAL! shell pm grant %PKG_NAME% android.permission.WRITE_SECURE_SETTINGS >nul 2>nul
    
    :: Pull phone rescue log to PC for reading later
    echo [INFO] Pulling latest rescue log from device to PC...
    adb -s !SERIAL! pull %LOG_EXTERNAL_PATH% ./scrcpy_rescue.log >nul 2>nul
    if not exist scrcpy_rescue.log (
        adb -s !SERIAL! exec-out run-as %PKG_NAME% cat files/scrcpy_rescue.log > ./scrcpy_rescue.log 2>nul
    )
    if exist scrcpy_rescue.log (
        echo [OK] Phone rescue log retrieved and saved to './scrcpy_rescue.log'.
    )

    echo.
    echo Launching scrcpy with display rescue flags...
    echo (Flags: --stay-awake --turn-screen-off --power-off-on-close)
    echo.

    scrcpy -s !SERIAL! --stay-awake --turn-screen-off --power-off-on-close

    echo.
    echo [%TIME%] scrcpy closed. Updating phone rescue log...
    adb -s !SERIAL! pull %LOG_EXTERNAL_PATH% ./scrcpy_rescue.log >nul 2>nul
    echo [%TIME%] Resuming monitoring loop...
    echo ================================================================
    timeout /t 2 /nobreak >nul
    goto :WATCH_LOOP
)

echo [%TIME%] Unknown device state: !DEVICE_STATE!. Retrying in 2s...
timeout /t 2 /nobreak >nul
goto :WATCH_LOOP
