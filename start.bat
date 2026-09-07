@echo off
REM =====================================================================
REM  DG-LAB WebSocket Relay Service Startup  v1.2.4  [100% PURE ASCII]
REM  ---------------------------------------------------------------
REM  ZERO non-ASCII bytes anywhere in this BAT file (all bytes < 0x80).
REM  All Chinese UI messages live in a SEPARATE UTF-8 text file:
REM       messages_zh.txt   (placed in the same folder as this BAT)
REM  and are printed via a companion PowerShell script:
REM       print_section.ps1  (same folder)
REM  This combination eliminates the famous CMD "GBK poison-quote
REM  multi-line parse bug" completely: since there are NO bytes
REM  >= 0x80 inside this .bat file, any codepage / byte-split /
REM  quote-state cross-line issue is mathematically impossible.
REM
REM  Additional robustness rules applied in v1.2.4:
REM    * NO multi-line `if (...) (...)` bracket-blocks anywhere.
REM      All conditionals use single-line `if COND goto LABEL` style.
REM    * NO complex `for /F ... ('command with ^| ^>^&1 ...')`
REM      pipelines - runtime values are captured via TEMP files and
REM      simple `for /F` over static file tokens.
REM    * NO `for %%A in ("path with runtime non-ASCII")` expansions
REM      used for file-size; a tiny PowerShell helper prints size.
REM =====================================================================

REM Switch console to UTF-8 so PowerShell Write-Host Chinese renders
REM with correct glyphs on the standard CMD console window.
chcp 65001 >nul 2>nul

REM -----------------------------------------------------------------
REM  Anti-flash self-wrapper: when this BAT is double-clicked we
REM  spawn a new CMD instance with /K flag, which guarantees the
REM  window stays open after ANY termination (syntax error / exit).
REM  Also force UTF-8 (65001) in the new CMD before script run.
REM -----------------------------------------------------------------
if "%1"=="/__WRAPPED" goto WRAPPED_START
REM Explicit "" as window-title arg so START never mis-parses the subsequent
REM command string as the title.  Use %ComSpec% (guaranteed to exist) instead
REM of a hard-coded "cmd.exe" so PATH-less environments and x86/x64 redirects
REM do not surprise us.  %~f0 is expanded ONCE by the top-level interpreter
REM (before we pass it into the child ComSpec) so the child sees a literal,
REM fully-qualified BAT path - no Chinese-byte runtime re-injection.
start "" "%ComSpec%" /k chcp 65001^>nul ^& call "%~f0" /__WRAPPED
exit /b 0

:WRAPPED_START
REM Safety: switch to UTF-8 again on real-entry
chcp 65001 >nul 2>nul
title DG-LAB WebSocket Relay Service  v1.2.4  (Chinese UI)
cd /d "%~dp0"
setlocal

REM ---------------------------------------------------------------
REM  IMPORTANT: JUMP OVER ALL SUBROUTINE DEFINITIONS TO THE MAIN
REM  FLOW.  Without this `goto MAIN_BEGIN` the interpreter would
REM  fall straight through the helper-label bodies below, which
REM  are ONLY meant to be reached via `call :LABEL`.
REM ---------------------------------------------------------------
goto MAIN_BEGIN

REM -----------------------------------------------------------------
REM  Helper :PRINT_SECTION  -  render a block of Chinese UI text
REM  Arg1 = section name (matches ## HEADER in messages_zh.txt)
REM  100% goto-style conditionals.  No multi-line bracket blocks.
REM -----------------------------------------------------------------
:PRINT_SECTION
set "_PS1=%~dp0print_section.ps1"
set "_MSG=%~dp0messages_zh.txt"
set "_SEC=%~1"
if exist "%_MSG%" goto PRINT_MSG_OK
echo(
echo [FATAL] Missing %_MSG% - place messages_zh.txt next to this BAT.
echo(
goto :eof
:PRINT_MSG_OK
if exist "%_PS1%" goto PRINT_PS1_OK
echo(
echo [FATAL] Missing %_PS1% - place print_section.ps1 next to this BAT.
echo(
goto :eof
:PRINT_PS1_OK
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%_PS1%" "%_MSG%" "%_SEC%"
goto :eof

REM -----------------------------------------------------------------
REM  Helper :JAVA_VERSION  -  write the installed Java version
REM  string into env var JVER (empty if detection failed).
REM  Avoids the classic CMD pitfall of parsing for /F over a live
REM  piped command whose characters (or whose output redirection
REM  tokens like 2>&1) can collide with runtime-expanded paths.
REM  Strategy: dump java -version stderr to a temp file, then parse
REM  the temp file with safe static tokens.
REM -----------------------------------------------------------------
:JAVA_VERSION
set "JVER="
set "_JV_TMP=%TEMP%\__dglab_java_ver_%RANDOM%.txt"
del "%_JV_TMP%" >nul 2>nul
java -version 2> "%_JV_TMP%" 1>nul 2>nul
if exist "%_JV_TMP%" goto JV_TMP_READY
goto JV_CLEANUP
:JV_TMP_READY
for /f "usebackq tokens=3" %%v in ("%_JV_TMP%") do set "JVER=%%v" & goto JV_GOT_ONE
:JV_GOT_ONE
:JV_CLEANUP
del "%_JV_TMP%" >nul 2>nul
set "_JV_TMP="
goto :eof

REM -----------------------------------------------------------------
REM  Helper :JAR_SIZE  -  write size of %JAR% file (bytes) into
REM  env var JAR_SIZE.  Uses a tiny one-shot PowerShell command
REM  rather than `for %%A in ("%JAR%") do set size=%%~zA` so the
REM  runtime-expanded quoted filepath (which may contain non-ASCII
REM  characters when the user placed this package in a folder with
REM  a Chinese name) is NEVER passed back into the CMD for-loop
REM  IN() clause parser, the #1 source of "syntax of command is
REM  incorrect" / "was unexpected at this time" ghosts.
REM -----------------------------------------------------------------
:JAR_SIZE
set "JAR_SIZE="
set "_JS_TMP=%TEMP%\__dglab_jar_sz_%RANDOM%.txt"
set  "_JS_JAR_PATH=%JAR%"
set  "_JS_OUT_PATH=%_JS_TMP%"
del "%_JS_TMP%" >nul 2>nul
REM NOTE: all dynamic values (jar path / tmp output path) are passed VIA
REM       ENVIRONMENT VARIABLES ($env:_JS_*).  Nothing is string-
REM       interpolated from CMD into the PowerShell command line, so
REM       there is ZERO risk of quote / dollar / backtick injection.
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command ^
  "$p=$env:_JS_JAR_PATH; $o=$env:_JS_OUT_PATH; if($p -and (Test-Path -LiteralPath $p)){ [IO.File]::WriteAllText($o, ([IO.FileInfo]$p).Length.ToString(), [Text.Encoding]::ASCII) }"
if not exist "%_JS_TMP%" goto JS_CLEANUP
set /p JAR_SIZE=<"%_JS_TMP%"
:JS_CLEANUP
del "%_JS_TMP%" >nul 2>nul
set "_JS_TMP="
set "_JS_JAR_PATH="
set "_JS_OUT_PATH="
goto :eof

REM =====================================================================
REM  =====  M A I N   F L O W  (entry point after subroutines)  =======
REM =====================================================================
:MAIN_BEGIN

REM =====================================================================
REM  BANNER  +  Step 1: Check Java
REM =====================================================================
call :PRINT_SECTION BANNER

call :PRINT_SECTION STEP1_HEADER
where java.exe >nul 2>nul
if not errorlevel 1 goto JAVA_FOUND

REM ===== Java NOT found =====
call :PRINT_SECTION STEP1_ERROR_NOJAVA
echo(
goto :ERROR

REM ===== Java FOUND =====
:JAVA_FOUND
call :JAVA_VERSION
call :PRINT_SECTION STEP1_OK
echo       %JVER%
echo(

REM =====================================================================
REM  Step 2: Check JAR file
REM =====================================================================
REM 查找优先级: 同目录 > target/DGLab-Relay-Server.jar (mvn 构建产物)
set "JAR=%~dp0DGLab-Relay-Server.jar"
if not exist "%JAR%" set "JAR=%~dp0target\DGLab-Relay-Server.jar"
call :PRINT_SECTION STEP2_HEADER
if exist "%JAR%" goto JAR_FOUND

REM ===== JAR NOT found =====
call :PRINT_SECTION STEP2_ERROR_NOJAR
echo(
goto :ERROR

REM ===== JAR FOUND =====
:JAR_FOUND
call :JAR_SIZE
call :PRINT_SECTION STEP2_OK
echo       %JAR_SIZE%
echo(

REM =====================================================================
REM  Step 3: Mode Menu loop
REM =====================================================================
:MENU
call :PRINT_SECTION MENU
set "choice="
set /p choice="> "
if "%choice%"=="" set choice=1

if "%choice%"=="1" goto MODE_LAN
if "%choice%"=="2" goto MODE_PUBLIC
if "%choice%"=="3" goto MODE_AUTO
if "%choice%"=="4" goto MODE_NOHTTP
if "%choice%"=="5" goto MODE_LAN_NOWIN
if "%choice%"=="6" goto CLEAN_EXIT

echo(
call :PRINT_SECTION INVALID_CHOICE
goto MENU

REM =====================================================================
REM  Step 4: Build common JVM arguments
REM  Console is chcp 65001 (UTF-8), so JAVA stdout MUST be UTF-8 too.
REM  (Earlier versions used GBK java output => UTF-8 window decoded GBK
REM   bytes as UTF-8 => all Chinese log lines after the menu were garbled.)
REM =====================================================================
:BUILD_JVM
set "JVM_OPTS=-Xmx256m -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8"
call :PRINT_SECTION JVM_ARGS
echo       %JVM_OPTS%
goto :eof

REM =====================================================================
REM  Mode 1 : LAN mode, auto-open browser QR page (RECOMMENDED)
REM =====================================================================
:MODE_LAN
call :PRINT_SECTION MODE1_LAN
call :BUILD_JVM
echo(
java %JVM_OPTS% -jar "%JAR%" 8080 --no-public-ip
set LAST_ERROR=%ERRORLEVEL%
goto AFTER_JAVA

REM =====================================================================
REM  Mode 5 : LAN + NO auto-open browser (servers / VMs / RDP)
REM =====================================================================
:MODE_LAN_NOWIN
call :PRINT_SECTION MODE5_LAN_NOWIN
echo(
set "port="
set /p port="> "
if not "%port%"=="" goto PORT5_SET
set port=8080
:PORT5_SET
echo --- Starting Mode 5 (LAN, no auto-browser). Port = %port%
echo --- After start, open http://127.0.0.1:%port%+1 manually for QR page.
call :BUILD_JVM
echo(
java %JVM_OPTS% -jar "%JAR%" %port% --no-public-ip --no-open
set LAST_ERROR=%ERRORLEVEL%
goto AFTER_JAVA

REM =====================================================================
REM  Mode 3 : Custom port + auto-detect public IP
REM =====================================================================
:MODE_AUTO
call :PRINT_SECTION MODE3_AUTO
echo(
set "port="
set /p port="> "
if not "%port%"=="" goto PORT3_SET
set port=8080
:PORT3_SET
echo --- Starting Mode 3. Port = %port%, will probe 4 public-IP APIs.
call :BUILD_JVM
echo(
java %JVM_OPTS% -jar "%JAR%" %port%
set LAST_ERROR=%ERRORLEVEL%
goto AFTER_JAVA

REM =====================================================================
REM  Mode 2 : Explicit public URL  (frp / ngrok / cloudflared tunnel)
REM =====================================================================
:MODE_PUBLIC
call :PRINT_SECTION MODE2_PUBLIC
echo(
set "pub="
set /p pub="[public-url]> "
if not "%pub%"=="" goto PUB_OK
call :PRINT_SECTION INVALID_CHOICE
pause >nul
goto MENU
:PUB_OK
echo --- Enter local WebSocket port (blank = 8080):
set "port="
set /p port="> "
if not "%port%"=="" goto PORT2_SET
set port=8080
:PORT2_SET
echo --- Starting Mode 2. Local port = %port%   Public URL = %pub%
call :BUILD_JVM
echo(
java %JVM_OPTS% -jar "%JAR%" -p %port% --public-url %pub%
set LAST_ERROR=%ERRORLEVEL%
goto AFTER_JAVA

REM =====================================================================
REM  Mode 4 : WebSocket only, no HTTP status/QR page (lightest)
REM =====================================================================
:MODE_NOHTTP
call :PRINT_SECTION MODE4_NOHTTP
echo(
set "port="
set /p port="> "
if not "%port%"=="" goto PORT4_SET
set port=8080
:PORT4_SET
echo --- Starting Mode 4. Port = %port%   (HTTP page disabled)
call :BUILD_JVM
echo(
java %JVM_OPTS% -jar "%JAR%" %port% --no-http
set LAST_ERROR=%ERRORLEVEL%
goto AFTER_JAVA

REM =====================================================================
REM  Post-Java-exit panel   (goto-style, NO bracket blocks)
REM =====================================================================
:AFTER_JAVA
echo(
call :PRINT_SECTION AFTER_JAVA_HEADER
echo       %LAST_ERROR%
echo(
if "%LAST_ERROR%"=="0" goto EXIT_CODE_OK
call :PRINT_SECTION AFTER_JAVA_ABNORMAL
goto EXIT_PANEL_END
:EXIT_CODE_OK
call :PRINT_SECTION AFTER_JAVA_NORMAL
:EXIT_PANEL_END
pause
goto MENU

REM =====================================================================
REM  Mode 6 : Clean exit chosen by user
REM =====================================================================
:CLEAN_EXIT
call :PRINT_SECTION CLEAN_EXIT
timeout /t 2 >nul
endlocal
exit /b 0

REM =====================================================================
REM  Global fatal-error catch-all panel  (goto-style everywhere)
REM =====================================================================
:ERROR
echo(
call :PRINT_SECTION ERROR_PANEL
REM Pass debug-path values THROUGH ENVIRONMENT VARIABLES into PowerShell so
REM that characters in %CD% / %~f0 / %JAR% never flow back through the CMD
REM parser / echo tokenizer (Chinese / quote / ampersand safe).
set  "_EP_WD=%CD%"
set  "_EP_SCR=%~f0"
set  "_EP_JAR=%JAR%"
set  "_EP_EL=%ERRORLEVEL%"
powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command ^
  "Write-Host ('   WORK_DIR  : {0}' -f $env:_EP_WD);" ^
  "Write-Host ('   SCRIPT    : {0}' -f $env:_EP_SCR);" ^
  "Write-Host ('   JAR_FILE  : {0}' -f $env:_EP_JAR);" ^
  "Write-Host ('   ERRORLEVEL: {0}' -f $env:_EP_EL)"
set "_EP_WD="
set "_EP_SCR="
set "_EP_JAR="
set "_EP_EL="
echo ===============================================================
echo(
pause
goto MENU
