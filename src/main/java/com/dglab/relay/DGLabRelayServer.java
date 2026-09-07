package com.dglab.relay;

import com.dglab.relay.protocol.MessageRouter;
import com.dglab.relay.server.RelayWebSocketServer;
import com.dglab.relay.server.HttpStatusServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.*;

/**
 * DG-LAB (郊狼) WebSocket 中转服务主程序
 * <p>
 * 作用：在任意 WebSocket 控制端（外部脚本、游戏插件、浏览器面板等）
 *      和手机 DG-LAB APP 之间做消息桥接
 * <p>
 * 兼容协议：dglab-websocket-server v2 标准
 * <p>
 * 启动方式：
 * <pre>
 * java -jar DGLab-Relay-Server.jar                         默认端口 8080
 * java -jar DGLab-Relay-Server.jar 9000                    指定端口 9000
 * java -jar DGLab-Relay-Server.jar --public-url ws://x.x.x.x:8080   手动指定公网地址
 * java -jar DGLab-Relay-Server.jar --no-http               不启动HTTP状态页
 * </pre>
 */
public class DGLabRelayServer {

    public static final String VERSION = "1.2.0";
    public static final String LOG_PREFIX = "[郊狼中转服务] ";

    /** 公网探测 API（依次尝试，成功即返回），超时 3 秒 */
    private static final String[] PUBLIC_IP_APIS = {
            "https://api.ipify.org",           // IPv4 首选
            "https://ipv4.icanhazip.com",      // 备选1
            "http://ifconfig.me/ip",           // 备选2 (HTTP不加密 更快)
            "https://ip.3322.net"              // 备选3 (国内友好)
    };

    public static void main(String[] args) {
        // ============ 解析启动参数 ============
        int port = 8080;
        boolean enableHttp = true;
        String manualPublicUrl = null;   // 用户通过 --public-url 手动指定的完整公网地址
        boolean skipPublicIpDetect = false;
        boolean autoOpenBrowser = true;  // 默认启动后自动打开浏览器访问HTTP状态页

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) continue;
            if (a.equalsIgnoreCase("-p") || a.equalsIgnoreCase("--port")) {
                if (i + 1 < args.length) {
                    try { port = Integer.parseInt(args[i + 1]); i++; }
                    catch (NumberFormatException ignored) {}
                }
            } else if (a.equalsIgnoreCase("--public-url") || a.equalsIgnoreCase("-pu")) {
                if (i + 1 < args.length) { manualPublicUrl = args[i + 1]; i++; }
            } else if (a.equalsIgnoreCase("--no-public-ip") || a.equalsIgnoreCase("-np")) {
                skipPublicIpDetect = true;
            } else if (a.equalsIgnoreCase("--no-open") || a.equalsIgnoreCase("-no")) {
                autoOpenBrowser = false;
            } else if (a.matches("\\d+")) {
                try { port = Integer.parseInt(a); }
                catch (NumberFormatException ignored) {}
            } else if (a.equalsIgnoreCase("--no-http") || a.equalsIgnoreCase("-nh")) {
                enableHttp = false;
                autoOpenBrowser = false;
            } else if (a.equalsIgnoreCase("-h") || a.equalsIgnoreCase("--help")) {
                printHelp();
                return;
            }
        }

        // 端口范围检查
        if (port <= 0 || port > 65535) {
            System.err.println(LOG_PREFIX + "错误: 端口号必须在 1~65535 之间，输入: " + port);
            System.exit(1);
        }

        System.out.println();
        System.out.println("==========================================================");
        System.out.println("     DG-LAB 郊狼 WebSocket 中转服务 v" + VERSION);
        System.out.println("     兼容协议: dglab-websocket-server");
        System.out.println("==========================================================");
        System.out.println();

        // ============ 1. 列出本机所有局域网 IP ============
        List<String> lanIps = getLANIpAddresses();
        System.out.println(LOG_PREFIX + "【局域网】本机可用 IP 地址 (同一WiFi下手机连接):");
        if (lanIps.isEmpty()) {
            System.out.println("    ⚠  未检测到有效网卡，请手动查询 IP");
        } else {
            for (String ip : lanIps) {
                System.out.println("    ✔  ws://" + ip + ":" + port);
            }
        }
        System.out.println("    本机回环(MC同机用): ws://127.0.0.1:" + port);
        System.out.println();

        // ============ 2. 获取公网 URL ============
        String publicWsUrl = null;      // 完整的公网 ws://... 地址，用于二维码
        String publicDetectInfo = null; // 展示用日志文字

        if (manualPublicUrl != null && !manualPublicUrl.isEmpty()) {
            // 用户手动指定，直接使用 (支持直接写完整 ws:// 或 只写 host:port)
            publicWsUrl = normalizeWsUrl(manualPublicUrl, port);
            publicDetectInfo = "用户手动指定";
        } else if (!skipPublicIpDetect) {
            // 自动探测公网IP
            System.out.print(LOG_PREFIX + "【公网】正在探测公网 IP (最多 3 秒)... ");
            String pubIp = detectPublicIp(3, TimeUnit.SECONDS);
            if (pubIp != null && !pubIp.isEmpty()) {
                publicWsUrl = "ws://" + pubIp + ":" + port;
                publicDetectInfo = "自动探测 (注意: 需要已做端口映射/内网穿透)";
                System.out.println("成功: " + pubIp);
            } else {
                System.out.println("失败/超时 (可加 --no-public-ip 跳过，或 --public-url 手动指定)");
            }
        } else {
            System.out.println(LOG_PREFIX + "【公网】已通过 --no-public-ip 跳过探测");
        }

        if (publicWsUrl != null) {
            System.out.println(LOG_PREFIX + "【公网】WebSocket 连接地址:");
            System.out.println("    🌐 " + publicWsUrl);
            System.out.println("    ℹ️  来源: " + publicDetectInfo);
            System.out.println("    💡 若使用 frp/ngrok/cloudflared 等穿透，请用 --public-url 手动指定真实公网地址");
        } else {
            System.out.println(LOG_PREFIX + "【公网】未获取到公网地址，仅局域网可用");
            System.out.println("    💡 可手动指定:  java -jar DGLab-Relay-Server.jar --public-url ws://你的公网域名:端口");
        }
        System.out.println();

        // ============ 启动 WebSocket 服务 ============
        MessageRouter router = new MessageRouter();
        RelayWebSocketServer wsServer = new RelayWebSocketServer(port, router);
        try {
            wsServer.start();
            System.out.println(LOG_PREFIX + "✅ WebSocket 服务已启动，监听端口: " + port);
        } catch (Exception e) {
            System.err.println(LOG_PREFIX + "❌ WebSocket 服务启动失败: " + e.getMessage());
            System.err.println(LOG_PREFIX + "   请检查端口 " + port + " 是否被占用，或换个端口重试: java -jar DGLab-Relay-Server.jar 9001");
            System.exit(2);
        }

        // ============ 启动 HTTP 状态页 (可选) ============
        HttpStatusServer httpServer = null;
        int httpPort = port + 1;
        String statusUrlForBrowser = null;
        if (enableHttp) {
            try {
                httpServer = new HttpStatusServer(httpPort, port, router, lanIps, publicWsUrl);
                httpServer.start();
                statusUrlForBrowser = "http://127.0.0.1:" + httpPort;
                System.out.println(LOG_PREFIX + "✅ HTTP 状态页已启动:");
                System.out.println("    本机访问: " + statusUrlForBrowser);
                if (!lanIps.isEmpty()) {
                    System.out.println("    局域网:  http://" + lanIps.get(0) + ":" + httpPort);
                }
                if (publicWsUrl != null) {
                    String httpPub = httpFromWs(publicWsUrl, httpPort);
                    if (httpPub != null) {
                        System.out.println("    公网访问(需穿透HTTP): " + httpPub);
                    }
                }
                // === 自动打开默认浏览器 ===
                if (autoOpenBrowser) {
                    final String url = statusUrlForBrowser;
                    new Thread(() -> {
                        try { Thread.sleep(600); openInBrowser(url); }
                        catch (Exception ignored) {}
                    }, "auto-open-browser").start();
                }
            } catch (Exception e) {
                System.err.println(LOG_PREFIX + "⚠  HTTP 状态页启动失败: " + e.getMessage() + " (不影响WebSocket中转功能)");
            }
        }

        System.out.println();
        System.out.println(LOG_PREFIX + "———————————————————————————————————————————");
        System.out.println(LOG_PREFIX + "📱 【手机APP无法连接？请按此步骤排错】");
        System.out.println(LOG_PREFIX + "   ① 确认手机和电脑同连一个WiFi（非访客隔离WiFi）");
        System.out.println(LOG_PREFIX + "   ② 手机浏览器先访问: " + (lanIps.isEmpty() ? "http://<电脑IP>:"+httpPort : "http://"+lanIps.get(0)+":"+httpPort));
        System.out.println(LOG_PREFIX + "      → 打不开 = 网络/防火墙问题");
        System.out.println(LOG_PREFIX + "      → 能打开 → 从页面上依次尝试【4种格式二维码】");
        System.out.println(LOG_PREFIX + "   ③ 用DG-LAB APP: 远程控制 → 扫描二维码 / 手动输入地址");
        System.out.println(LOG_PREFIX + "   ④ 若提示\"请重新扫描二维码\": 格式不兼容");
        System.out.println(LOG_PREFIX + "      → HTTP状态页下拉切换 QR格式 (A/B/C/D 逐个试)");
        System.out.println(LOG_PREFIX + "———————————————————————————————————————————");
        System.out.println();
        System.out.println(LOG_PREFIX + "等待客户端连接中... (Ctrl+C 退出)");
        System.out.println();

        // ============ 注册 JVM 关闭钩子 ============
        final HttpStatusServer finalHttpServer = httpServer;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println();
            System.out.println(LOG_PREFIX + "正在关闭服务...");
            try { wsServer.stop(2000); } catch (Exception ignored) {}
            if (finalHttpServer != null) {
                try { finalHttpServer.stop(); } catch (Exception ignored) {}
            }
            System.out.println(LOG_PREFIX + "已退出。");
        }));
    }

    // ===================== 公网/辅助工具 =====================

    /**
     * 通过外部 HTTP API 获取公网 IPv4 地址，带超时控制
     */
    public static String detectPublicIp(long timeout, TimeUnit unit) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = exec.submit(() -> {
                for (String apiUrl : PUBLIC_IP_APIS) {
                    try {
                        URL u = new URL(apiUrl);
                        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(2000);
                        conn.setReadTimeout(2000);
                        conn.setRequestProperty("User-Agent", "DGLab-Relay/" + VERSION);
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                            String line;
                            while ((line = br.readLine()) != null) sb.append(line.trim());
                        }
                        String ip = sb.toString().trim();
                        // 验证格式是 IPv4
                        if (ip.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) return ip;
                    } catch (Exception ignored) {
                        // 换下一个 API
                    }
                }
                return null;
            });
            return future.get(timeout, unit);
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            return null;
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * 获取本机所有非回环的 IPv4 局域网地址
     */
    public static List<String> getLANIpAddresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) continue;
                String displayName = ni.getDisplayName().toLowerCase();
                if (displayName.contains("virtual") || displayName.contains("vmware")
                        || displayName.contains("hyper-v") || displayName.contains("docker")
                        || displayName.contains("bluetooth") || displayName.contains("loopback")) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    String s = addr.getHostAddress();
                    if (s == null || !s.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) continue;
                    if (s.startsWith("127.") || s.startsWith("169.254.")) continue;
                    result.add(s);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    /**
     * 把用户传入的 --public-url 规范化成完整的 ws:// 地址
     * 支持多种输入：
     *   直接 ws://x.com:1234   → 原样返回
     *   直接 wss://x.com       → 原样返回
     *   只有 1.2.3.4           → ws://1.2.3.4:port
     *   只有 x.com:9000        → ws://x.com:9000
     */
    private static String normalizeWsUrl(String input, int defaultPort) {
        if (input == null) return null;
        input = input.trim();
        if (input.startsWith("ws://") || input.startsWith("wss://")) return input;
        // http 开头：尝试把 scheme 换成 ws
        if (input.startsWith("http://")) return "ws://" + input.substring(7);
        if (input.startsWith("https://")) return "wss://" + input.substring(8);
        // 裸地址: 是否带端口
        if (input.contains(":")) return "ws://" + input;
        return "ws://" + input + ":" + defaultPort;
    }

    /**
     * 根据 ws://host:port 拼出公网访问 HTTP 状态页地址 (仅显示用)
     * 例如 ws://1.2.3.4:8080 + httpPort 8081 → http://1.2.3.4:8081
     */
    private static String httpFromWs(String wsUrl, int httpPort) {
        try {
            // 去掉 ws:// 或 wss://，取 // 后面的部分直到下一个 / 或结尾
            String rest;
            boolean secure = false;
            if (wsUrl.startsWith("wss://")) { rest = wsUrl.substring(6); secure = true; }
            else if (wsUrl.startsWith("ws://")) rest = wsUrl.substring(5);
            else return null;
            String hostPart = rest.split("/")[0];   // host 或 host:port
            String host = hostPart.contains(":") ? hostPart.split(":")[0] : hostPart;
            return (secure ? "https" : "http") + "://" + host + ":" + httpPort;
        } catch (Exception e) { return null; }
    }

    private static void printHelp() {
        System.out.println();
        System.out.println("DG-LAB 郊狼 WebSocket 中转服务 v" + VERSION);
        System.out.println();
        System.out.println("用法: java -jar DGLab-Relay-Server.jar [选项]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  <端口数字>                    指定WebSocket端口 (默认 8080)");
        System.out.println("  -p, --port <端口>             指定WebSocket端口");
        System.out.println("  --public-url, -pu <地址>      指定公网WebSocket完整地址 (frp/ngrok穿透时必用)");
        System.out.println("                                 支持: ws://域名:端口 / 域名:端口 / 公网IP");
        System.out.println("  --no-public-ip, -np           跳过公网IP自动探测 (加快启动)");
        System.out.println("  --no-open, -no                启动后不自动打开浏览器状态页");
        System.out.println("  --no-http, -nh                不启动HTTP状态页 (隐含--no-open)");
        System.out.println("  -h, --help                    查看本帮助");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  局域网(推荐):  java -jar DGLab-Relay-Server.jar --no-public-ip");
        System.out.println("  FRP穿透:      java -jar DGLab-Relay-Server.jar --public-url wss://game.abc.com:8843");
        System.out.println("  服务器模式:    java -jar DGLab-Relay-Server.jar 8080 --no-open");
        System.out.println();
    }

    // ======================== 辅助：打开默认浏览器 ========================
    public static void openInBrowser(String url) {
        boolean ok = false;
        // 1) 优先 Java AWT Desktop (跨平台)
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop d = java.awt.Desktop.getDesktop();
                if (d.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    d.browse(new java.net.URI(url));
                    ok = true;
                }
            }
        } catch (Exception ignored) {}

        if (ok) return;
        // 2) 回退：按操作系统调用命令
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else {
                // Linux: 依次尝试 xdg-open / gnome-open / kde-open
                pb = new ProcessBuilder("sh", "-c",
                        "xdg-open " + url + " 2>/dev/null || gnome-open " + url + " 2>/dev/null || kde-open " + url);
            }
            pb.inheritIO().start();
        } catch (Exception e) {
            // 无法自动打开 -> 忽略，让用户手动复制URL
        }
    }
}
