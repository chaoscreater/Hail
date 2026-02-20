@echo off
:: Auto-generates local.properties with the correct Android SDK path for this machine.

set SDK_PATH=%LOCALAPPDATA%\Android\Sdk

if exist "%SDK_PATH%\platform-tools\adb.exe" (
    :: Convert backslashes to escaped backslashes for Java properties format
    set SDK_ESCAPED=%SDK_PATH:\=\\%
    echo sdk.dir=%SDK_ESCAPED%> local.properties
    echo [OK] local.properties created: sdk.dir=%SDK_PATH%
) else (
    echo [ERROR] Android SDK not found at: %SDK_PATH%
    echo.
    echo Please install Android Studio from https://developer.android.com/studio
    echo Or set the path manually in local.properties:
    echo   sdk.dir=C\:\\path\\to\\your\\Android\\Sdk
)
pause
