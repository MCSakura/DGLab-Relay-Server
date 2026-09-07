package com.dglab.relay.server;

import com.dglab.relay.DGLabRelayServer;
import com.dglab.relay.protocol.MessageRouter;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 服务端实现
 * 同时接收 APP客户端 和 MC插件控制端 的连接
 */
public class RelayWebSocketServer extends WebSocketServer {

    private final MessageRouter router;
    /** 连接建立后多少秒内还不发 bind 消息 → 自动作为APP注册 */
    private static final long AUTO_BIND_DELAY_SEC = 5L;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "dglab-autobind");
        t.setDaemon(true); return t;
    });

    public RelayWebSocketServer(int port, MessageRouter router) {
        // 使用 OkHttpSafeDraft: 修复101响应头 "Connection: Upgrade; close" 导致APP(OkHttp)判定握手失败
        super(new InetSocketAddress(port), java.util.Collections.singletonList(new OkHttpSafeDraft()));
        this.router = router;
        setConnectionLostTimeout(60);
    }

    @Override
    public void onStart() {
        // 服务启动
    }

    // ===== 关键增强：握手日志 + OkHttp兼容修复 =====
    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft, ClientHandshake request) throws InvalidDataException {
        String remote = safeRemote(conn);
        String path = request.getResourceDescriptor();  // e.g. "/dglab?param=1"
        StringBuilder sb = new StringBuilder();
        sb.append("🔐 新握手请求 from ").append(remote).append(" → path=").append(path);
        // 打印几个关键 header (Origin/Sec-WebSocket-Key/User-Agent)
        for (String key : new String[]{"Origin", "User-Agent", "Host", "Sec-WebSocket-Protocol"}) {
            String v = request.getFieldValue(key);
            if (v != null && !v.isEmpty()) sb.append("  ").append(key).append("=").append(truncate(v, 50));
        }
        System.out.println(DGLabRelayServer.LOG_PREFIX + sb);
        // Connection头修复交给 OkHttpSafeDraft (postProcessHandshakeResponseAsServer)
        // 默认实现会把请求头原样回写到101响应, 具体修复见 OkHttpSafeDraft.java
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String remote = safeRemote(conn);
        router.onConnect(conn);
        // ===== 官方V4协议: 握手URL带 ?tid=/<tid>= 查询参数 → 作为DG-LAB 4 APP接入V4桥接 =====
        String resource = handshake.getResourceDescriptor();
        String queryTid = extractQueryTid(resource);
        if (queryTid != null && !queryTid.isEmpty()) {
            System.out.println(DGLabRelayServer.LOG_PREFIX
                    + "📡 [V4桥接] 检测到官方V4协议连接 tid=" + queryTid + " from " + remote);
            router.v4Connect(conn, queryTid);
            return;
        }
        // ===== 官方V3协议: 握手URL路径段携带 tid → 作为DG-LAB APP接入V3桥接 =====
        String pathTid = extractPathTid(resource);
        if (pathTid != null && !pathTid.isEmpty()) {
            System.out.println(DGLabRelayServer.LOG_PREFIX
                    + "📡 [V3桥接] 检测到官方协议连接 tid=" + pathTid + " from " + remote);
            router.officialConnect(conn, pathTid);
            return; // 官方连接无需自动绑定计时器
        }
        // ===== 关键增强：5秒内不发bind 就自动绑定为APP =====
        final WebSocket connFinal = conn;
        scheduler.schedule(() -> {
            if (connFinal.isOpen() && !router.isRegistered(connFinal)) {
                String assignedId = router.autoBindApp(connFinal, remote);
                if (assignedId != null) {
                    System.out.println(DGLabRelayServer.LOG_PREFIX
                            + "🤝 [自动绑定] 客户端 " + remote + " 长时间未发bind，已自动作为APP注册: " + assignedId);
                }
            }
        }, AUTO_BIND_DELAY_SEC, TimeUnit.SECONDS);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        router.onDisconnect(conn, code, reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // 对前几条消息打印摘要，便于排错（截断前200字符）
        if (!router.isRegistered(conn)) {
            System.out.println(DGLabRelayServer.LOG_PREFIX
                    + "📨 首消息 " + safeRemote(conn) + " → " + truncate(message, 220));
        } else if (message != null && message.length() > 500) {
            // 超长消息提示一下
            System.out.println(DGLabRelayServer.LOG_PREFIX
                    + "📨 大消息 from " + safeRemote(conn) + " len=" + message.length());
        }
        router.onMessage(conn, message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            String remote = safeRemote(conn);
            System.err.println(DGLabRelayServer.LOG_PREFIX + "⚠ 连接 " + remote + " 错误: " + ex.getMessage());
        } else {
            System.err.println(DGLabRelayServer.LOG_PREFIX + "⚠ 服务器错误: " + ex.getMessage());
        }
    }

    // ===========================================================
    // 工具方法
    // ===========================================================

    /**
     * 从握手资源描述符解析V4查询参数tid (官方v4-server: url.searchParams targetId/tid)
     *   "/?tid=<tid>" 或 "/?targetId=<tid>"
     * @return 查询参数tid; 无则返回null
     */
    private static String extractQueryTid(String resource) {
        if (resource == null) return null;
        int q = resource.indexOf('?');
        if (q < 0) return null;
        String query = resource.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq);
            if (k.equals("tid") || k.equals("targetId")) {
                String v = pair.substring(eq + 1);
                try { return java.net.URLDecoder.decode(v, "UTF-8"); }
                catch (Exception e) { return v; }
            }
        }
        return null;
    }

    /**
     * 从握手资源描述符解析官方V3路径段targetId (控制端clientId):
     *   "/<tid>" 路径段形式
     * 排除自定义协议路径 (/dglab 等)
     */
    private static String extractPathTid(String resource) {
        if (resource == null) return null;
        String path = resource;
        int q = resource.indexOf('?');
        if (q >= 0) path = resource.substring(0, q);
        String seg = path.startsWith("/") ? path.substring(1) : path;
        if (seg.isEmpty()) return null;
        String lower = seg.toLowerCase();
        if (lower.equals("dglab") || lower.endsWith("/dglab") || lower.equals("ws")) return null;
        return seg;
    }

    private static String safeRemote(WebSocket conn) {
        try {
            InetSocketAddress addr = conn.getRemoteSocketAddress();
            if (addr == null) return "?";
            // 去掉末尾的主机名部分，只显示 IP:port
            String s = addr.toString();
            int slash = s.indexOf('/');
            return slash >= 0 ? s.substring(slash + 1) : s;
        } catch (Exception e) { return "?"; }
    }
    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…(+" + (s.length() - max) + ")";
    }
}
