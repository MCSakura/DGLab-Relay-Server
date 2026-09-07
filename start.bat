@echo off
setlocal EnableDelayedExpansion
rem 关键：控制台切 UTF-8，同时 JVM 三个编码参数显式切 UTF-8（Windows 默认 GBK，强制对齐才能避免中文乱码）
chcp 65001 >nul 2>&1
set "JAVA_OPTS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"

title DG-LAB 郊狼中转服务 - 启动器

rem ================================================================
rem DG-LAB (郊狼) WebSocket 中转服务 启动脚本 (Windows / 简体中文)
rem 双击本文件 → 交互式菜单选择模式 → 启动
rem 也可以命令行带参数跳过菜单：
rem   start.bat lan              局域网模式 (自动探测公网IP、启动HTTP状态页)
rem   start.bat lan-fast         局域网快速模式 (跳过公网探测)
rem   start.bat tunnel <ws-url>  穿透模式
rem   start.bat server           服务器后台模式 (不自动开浏览器)
rem   start.bat build            开发者模式 (mvn package 后再启动)
rem   start.bat quick            最简: 8080 + 默认参数
rem ================================================================

rem --- 0. 切到脚本所在目录（双击启动时也能找到 jar）---
cd /d "%~dp0"

rem --- 1. 自动定位 jar（优先 target/ 下带版本的产物，再兜底 target/DGLab-Relay-Server.jar）---
set "JAR_FILE="
for /f "delims=" %%f in ('dir /b target\DGLabWebSocketRelay-*.jar 2^>nul ^| findstr /v shaded') do set "JAR_FILE=target\%%f"
if "%JAR_FILE%"=="" for /f "delims=" %%f in ('dir /b target\DGLab-Relay-Server.jar 2^>nul') do set "JAR_FILE=target\%%f"
if "%JAR_FILE%"=="" set "JAR_FILE=target\DGLab-Relay-Server.jar"

rem --- 2. java 自检 ---
where java >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到 Java，请先安装 JDK 17+ 并配置 PATH。
    echo        下载: https://adoptium.net/
    pause
    exit /b 1
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%~v"
echo [环境] Java %JAVA_VER%

rem --- 3. 命令行参数解析 ---
set "MODE=%~1"
if /i "%MODE%"=="lan-gui"   set "MODE=lan"
if /i "%MODE%"=="network"   set "MODE=lan"
if /i "%MODE%"=="default"   set "MODE=quick"
if /i "%MODE%"=="no-http"   set "MODE=server"

if not "%MODE%"=="" goto :RUN_MODE

rem --- 4. 主菜单 ---
:MENU
cls
echo ╔══════════════════════════════════════════════╗
echo ║    DG-LAB 郊狼 WebSocket 中转服务  启动器     ║
echo ╚══════════════════════════════════════════════╝
echo.
echo   [1] 局域网模式       — 同一 WiFi 手机连电脑，自动探测公网IP
echo   [2] 局域网快速模式   — 跳过公网探测，启动更快 (推荐开发调试)
echo   [3] 穿透模式         — frp / ngrok / cloudflared 公网部署
echo   [4] 服务器后台模式   — 不自动开浏览器，不含 HTTP 状态页
echo   [5] 开发者模式       — 从源码构建后再启动 (需 mvn)
echo   [6] 最简启动         — 端口 8080，默认所有参数
echo   [7] 自定义参数       — 自己拼命令行
echo.
echo   [Q] 退出
echo.
set /p "CHOICE=请选择模式 [1-7 / Q]: "
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
rem 5. 执行对应模式
rem ================================================================
:RUN_MODE

echo.
echo  ├─ 工作目录: %CD%
echo  ├─ JAR 文件: %JAR_FILE%
echo.

if /i "%MODE%"=="lan"      goto :MODE_LAN
if /i "%MODE%"=="lan-fast" goto :MODE_LAN_FAST
if /i "%MODE%"=="tunnel"   goto :MODE_TUNNEL
if /i "%MODE%"=="server"   goto :MODE_SERVER
if /i "%MODE%"=="build"    goto :MODE_BUILD
if /i "%MODE%"=="quick"    goto :MODE_QUICK
if /i "%MODE%"=="custom"   goto :MODE_CUSTOM

rem 默认 (未识别模式 / 无参数) → 交互输入后重进菜单
echo [提示] 未识别模式 "%MODE%"，请在菜单中选择。
pause
goto :MENU

rem --- 模式1: 局域网 (自动探测公网IP) ---
:MODE_LAN
set "PORT=8080"
set /p "PORT=  WebSocket 端口? [8080]: "
if "%PORT%"=="" set "PORT=8080"
set "ARGS=%PORT%"
echo.
echo [启动] 局域网模式 — 自动探测公网 IP (3s)，启动 HTTP 状态页，自动打开浏览器
goto :DO_START

rem --- 模式2: 局域网快速 ---
:MODE_LAN_FAST
set "PORT=8080"
set /p "PORT=  WebSocket 端口? [8080]: "
if "%PORT%"=="" set "PORT=8080"
set "ARGS=%PORT% --no-public-ip"
echo.
echo [启动] 局域网快速模式 — 跳过公网探测，启动 HTTP 状态页
goto :DO_START

rem --- 模式3: 公网穿透 ---
:MODE_TUNNEL
set "PORT=8080"
set /p "PORT=  WebSocket 端口? [8080]: "
if "%PORT%"=="" set "PORT=8080"
echo.
echo  常见穿透地址示例:
echo    frp:       wss://game.abc.com:8843
echo    ngrok:     ws://xxxx.ngrok.io
echo    cloudflared:  wss://xxxx.cfargotunnel.com
echo    公网IP:    ws://你的公网IP:8080
echo.
set /p "PUB_URL=  公网 ws:// 完整地址?: "
if "%PUB_URL%"=="" (
    echo [取消] 未填公网地址，退出。
    pause
    goto :MENU
)
set "ARGS=%PORT% --no-public-ip --public-url %PUB_URL%"
echo.
echo [启动] 穿透模式 — 公网地址 = %PUB_URL%
goto :DO_START

rem --- 模式4: 服务器后台 ---
:MODE_SERVER
set "PORT=8080"
set /p "PORT=  WebSocket 端口? [8080]: "
if "%PORT%"=="" set "PORT=8080"
set "ARGS=%PORT% --no-public-ip --no-open --no-http"
echo.
echo [启动] 服务器后台模式 — 无 HTTP 状态页、不自动开浏览器
goto :DO_START

rem --- 模式5: 开发者 (mvn build) ---
:MODE_BUILD
where mvn >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到 mvn，请先安装 Maven 3.9+ 并配置 PATH。
    pause
    exit /b 1
)
echo [构建] mvn clean package -DskipTests ...
call mvn clean package -DskipTests
if errorlevel 1 (
    echo [错误] 构建失败，请检查控制台输出。
    pause
    goto :MENU
)
echo [构建] 完成。
rem 构建后重新定位 jar
set "JAR_FILE="
for /f "delims=" %%f in ('dir /b target\DGLabWebSocketRelay-*.jar 2^>nul ^| findstr /v shaded') do set "JAR_FILE=target\%%f"
if "%JAR_FILE%"=="" set "JAR_FILE=target\DGLab-Relay-Server.jar"
set "ARGS=8080 --no-public-ip"
goto :DO_START

rem --- 模式6: 最简 ---
:MODE_QUICK
set "ARGS=8080"
goto :DO_START

rem --- 模式7: 自定义命令行 ---
:MODE_CUSTOM
echo.
echo  已拼命令行:
echo    java %JAVA_OPTS% -jar "%JAR_FILE%" [你的参数]
echo.
echo  可用参数 (直接回车跳过):
echo    -p / --port ^<数字^>       指定 WS 端口 (默认 8080)
echo    --public-url ^<ws://...^>   公网穿透完整地址
echo    --no-public-ip            跳过公网探测
echo    --no-open                 不自动开浏览器
echo    --no-http                 不启动 HTTP 状态页
echo.
set /p "ARGS=  完整命令行参数: "
goto :DO_START

rem ================================================================
rem 6. 真正启动
rem ================================================================
:DO_START
if not exist "%JAR_FILE%" (
    echo.
    echo [错误] 找不到 jar: "%JAR_FILE%"
    echo        请先 mvn clean package -DskipTests，或切换到 开发者模式 自动构建。
    echo.
    pause
    exit /b 1
)
echo.
echo ════════════════════════════════════════════════
echo  java %JAVA_OPTS% -jar "%JAR_FILE%" %ARGS%
echo ════════════════════════════════════════════════
echo.
java %JAVA_OPTS% -jar "%JAR_FILE%" %ARGS%
echo.
echo 进程已结束，退出码 %ERRORLEVEL%
pause
exit /b 0
