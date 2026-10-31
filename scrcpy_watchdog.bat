@echo off
setlocal enabledelayedexpansion
title Scrcpy Zero-Touch Rescue Watchdog

echo ================================================================
echo          SCRCPY RESCUE WATCHDOG FOR BROKEN DISPLAYS
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
echo [INFO] Scrcpy Rescue app will automatically enable ADB and auto-allow authorization.
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
    echo         Scrcpy Rescue Accessibility Service is auto-clicking 'Always allow' on device...
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
    echo Launching scrcpy with display rescue flags...
    echo (Flags: --stay-awake --turn-screen-off --power-off-on-close)
    echo.

    scrcpy -s !SERIAL! --stay-awake --turn-screen-off --power-off-on-close

    echo.
    echo [%TIME%] scrcpy closed. Resuming monitoring loop...
    echo ================================================================
    timeout /t 2 /nobreak >nul
    goto :WATCH_LOOP
)

echo [%TIME%] Unknown device state: !DEVICE_STATE!. Retrying in 2s...
timeout /t 2 /nobreak >nul
goto :WATCH_LOOP
