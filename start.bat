@echo off
setlocal EnableDelayedExpansion

rem ================================================================
rem DG-LAB Relay Server - Windows Launcher
rem Usage:
rem   Double-click this file -> interactive menu
rem   Or CLI: start.bat lan-fast | tunnel <ws-url> | server | build | quick
rem ================================================================

rem --- 0. cd to script dir (portable, works even when double-clicked) ---
cd /d "%~dp0"

rem --- 1. auto-locate shaded jar ---
set "JAR_FILE="
for /f "delims=" %%f in ('dir /b target\DGLabWebSocketRelay-*.jar 2^>nul ^| findstr /v shaded') do set "JAR_FILE=target\%%f"
if "%JAR_FILE%"=="" for /f "delims=" %%f in ('dir /b target\DGLab-Relay-Server.jar 2^>nul') do set "JAR_FILE=target\%%f"
if "%JAR_FILE%"=="" set "JAR_FILE=target\DGLab-Relay-Server.jar"

rem --- 2. java self-check ---
where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Please install JDK 17+ and add to PATH.
    echo         Download: https://adoptium.net/
    pause
    exit /b 1
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%~v"
echo [ENV] Java %JAVA_VER%

rem --- 3. CLI arg parsing ---
set "MODE=%~1"
if /i "%MODE%"=="lan-gui"   set "MODE=lan"
if /i "%MODE%"=="network"   set "MODE=lan"
if /i "%MODE%"=="default"   set "MODE=quick"
if /i "%MODE%"=="no-http"   set "MODE=server"

if not "%MODE%"=="" goto :RUN_MODE

rem --- 4. Main menu ---
:MENU
cls
echo +=======================================================+
echo ^|    DG-LAB Relay Server  -  Launcher                  ^|
echo +=======================================================+
echo.
echo   [1] LAN mode         - same WiFi, auto public IP detect
echo   [2] LAN fast         - skip public IP detect, faster
echo   [3] Tunnel/frp       - public deploy with tunnel
echo   [4] Server/headless  - no HTTP page, no auto-open
echo   [5] Dev/build        - mvn package then run
echo   [6] Quick            - port 8080, all defaults
echo   [7] Custom           - enter args manually
echo.
echo   [Q] Quit
echo.
set /p "CHOICE=Select mode [1-7 / Q]: "
if "%CHOICE%"=="" goto :RUN_MODE
if /i "%CHOICE%"=="1" set "MODE=lan"      & goto :RUN_MODE
if /i "%CHOICE%"=="2" set "MODE=lan-fast" & goto :RUN_MODE
if /i "%CHOICE%"=="3" set "MODE=tunnel"  & goto :RUN_MODE
if /i "%CHOICE%"=="4" set "MODE=server"  & goto :RUN_MODE
if /i "%CHOICE%"=="5" set "MODE=build"   & goto :RUN_MODE
if /i "%CHOICE%"=="6" set "MODE=quick"   & goto :RUN_MODE
if /i "%CHOICE%"=="7" set "MODE=custom"  & goto :RUN_MODE
if /i "%CHOICE%"=="Q" exit /b 0
goto :MENU

rem ================================================================
rem 5. Dispatch
rem ================================================================
:RUN_MODE

echo.
echo   - Dir:  %CD%
echo   - Jar:  %JAR_FILE%
echo.

if /i "%MODE%"=="lan"      goto :MODE_LAN
if /i "%MODE%"=="lan-fast" goto :MODE_LAN_FAST
if /i "%MODE%"=="tunnel"   goto :MODE_TUNNEL
if /i "%MODE%"=="server"   goto :MODE_SERVER
if /i "%MODE%"=="build"    goto :MODE_BUILD
if /i "%MODE%"=="quick"    goto :MODE_QUICK
if /i "%MODE%"=="custom"   goto :MODE_CUSTOM

echo [WARN] Unknown mode "%MODE%" - returning to menu.
pause
goto :MENU

rem --- 1: LAN ---
:MODE_LAN
set "PORT=8080"
set /p "PORT=  WebSocket port? [8080]: "
if "%PORT%"=="" set "PORT=8080"
set "ARGS=%PORT%"
echo.
echo [START] LAN mode - detect public IP, enable HTTP page, auto-open browser
goto :DO_START

rem --- 2: LAN fast ---
:MODE_LAN_FAST
set "PORT=8080"
set /p "PORT=  WebSocket port? [8080]: "
if "%PORT%"=="" set "PORT=8080"
set "ARGS=%PORT% --no-public-ip"
echo.
echo [START] LAN fast - skip public IP detect
goto :DO_START

rem --- 3: Tunnel ---
:MODE_TUNNEL
set "PORT=8080"
set /p "PORT=  WebSocket port? [8080]: "
if "%PORT%"=="" set "PORT=8080"
echo.
echo  Common tunnel examples:
echo    frp:         wss://game.abc.com:8843
echo    ngrok:       ws://xxxx.ngrok.io
echo    cloudflared: wss://xxxx.cfargotunnel.com
echo    public IP:   ws://YOUR_PUBLIC_IP:8080
echo.
set /p "PUB_URL=  Public ws:// full URL: "
if "%PUB_URL%"=="" (
    echo [CANCEL] No URL entered.
    pause
    goto :MENU
)
set "ARGS=%PORT% --no-public-ip --public-url %PUB_URL%"
echo.
echo [START] Tunnel mode - public URL = %PUB_URL%
goto :DO_START

rem --- 4: Server/headless ---
:MODE_SERVER
set "PORT=8080"
set /p "PORT=  WebSocket port? [8080]: "
if "%PORT%"=="" set "PORT=8080"
set "ARGS=%PORT% --no-public-ip --no-open --no-http"
echo.
echo [START] Server mode - no HTTP page, no auto-open
goto :DO_START

rem --- 5: Dev/build ---
:MODE_BUILD
where mvn >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Maven not found. Install Maven 3.9+ and add to PATH.
    pause
    exit /b 1
)
echo [BUILD] mvn clean package -DskipTests ...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo [ERROR] Build failed. Check console output.
    pause
    goto :MENU
)
echo [BUILD] Done.
set "JAR_FILE="
for /f "delims=" %%f in ('dir /b target\DGLabWebSocketRelay-*.jar 2^>nul ^| findstr /v shaded') do set "JAR_FILE=target\%%f"
if "%JAR_FILE%"=="" set "JAR_FILE=target\DGLab-Relay-Server.jar"
set "ARGS=8080 --no-public-ip"
goto :DO_START

rem --- 6: Quick ---
:MODE_QUICK
set "ARGS=8080"
goto :DO_START

rem --- 7: Custom ---
:MODE_CUSTOM
echo.
echo  Command template:
echo    java -jar "%JAR_FILE%" [your args]
echo.
echo  Available flags:
echo    -p / --port ^<num^>         WS port (default 8080)
echo    --public-url ^<ws://...^>   tunnel public URL
echo    --no-public-ip             skip public IP detection
echo    --no-open                  do not auto-open browser
echo    --no-http                  disable HTTP status page
echo.
set /p "ARGS=  Full args: "
goto :DO_START

rem ================================================================
rem 6. Launch
rem ================================================================
:DO_START
if not exist "%JAR_FILE%" (
    echo.
    echo [ERROR] Jar not found: "%JAR_FILE%"
    echo         Run Dev mode (option 5) first, or mvn package manually.
    echo.
    pause
    exit /b 1
)
echo.
echo +===============================================+
echo ^|  java -jar "%JAR_FILE%" %ARGS%
echo +===============================================+
echo.
java -jar "%JAR_FILE%" %ARGS%
echo.
echo Process exited with code %ERRORLEVEL%
pause
exit /b 0
