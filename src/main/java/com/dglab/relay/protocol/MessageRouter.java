package com.dglab.relay.protocol;

import com.dglab.relay.DGLabRelayServer;
import org.java_websocket.WebSocket;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MessageRouter - 连接管理器 + 消息路由
 * <p>
 * 负责：
 *  1. 维护所有 WebSocket 连接 (APP客户端 + 插件控制端)
 *  2. 根据 clientId / targetId 进行消息转发
 *  3. 兼容 dglab-websocket-server 的标准消息格式
 * <p>
 * 连接类型识别策略：
 *  - APP端：连接后发送 {"msgType":"bind","clientId":"xxx"} 或发送 targetId 字段的消息
 *  - 插件端：发送 {"msgType":"strength", ...} 的控制消息
 *  未注册 clientId 的连接一律视为 APP端，服务器分配随机 clientId
 */
public class MessageRouter {

    // ============ 连接注册表 ============
    // clientId -> WebSocket 连接 (APP端)
    private final Map<String, WebSocket> appClients = new ConcurrentHashMap<>();
    // 反向：WebSocket -> clientId
    private final Map<WebSocket, String> connToClientId = new ConcurrentHashMap<>();

    // 插件/控制端连接 (不区分clientId，任何端都可发控制命令，所以只需计数)
    private final Set<WebSocket> controllerConns = ConcurrentHashMap.newKeySet();

    // ============ 官方 V3 协议桥接层 (dglab-websocket-server 兼容) ============
    // 通过官方二维码接入的APP连接 (只收官方帧，不收自定义JSON广播)
    private final Set<WebSocket> officialAppConns = ConcurrentHashMap.newKeySet();
    // 官方APP: 连接 -> 分配的appId
    private final Map<WebSocket, String> officialIdByConn = new ConcurrentHashMap<>();
    // 官方V3配对中的连接 (已发分配ID帧, 等APP回bind): conn -> appId
    private final Map<WebSocket, String> officialPendingConns = new ConcurrentHashMap<>();
    // 官方APP进行中的波形定时任务: appId -> 任务列表
    private final Map<String, List<java.util.concurrent.ScheduledFuture<?>>> officialPulseTasks = new ConcurrentHashMap<>();
    // 桥接控制端ID(V4官方): 官方v4-server 用4字节随机hex (8位十六进制, 如 3d65ca6a)
    // 注意: 不能用UUID! DG-LAB 4 APP会校验controller_attached中clientId的格式,
    //       非8位hex会导致APP判定协议错误直接终止连接 (表现为code=1006且无上行消息)
    private final String bridgeControllerId = randomHexId();
    // 桥接控制端ID(V3官方): 官方v3-server 用 crypto.randomUUID() 生成 (36位UUID带横线)。
    // V3 APP 会校验二维码 targetId 的 UUID 格式 —— 必须与V4(8hex)分开使用!
    // 若V3也共用8hex短ID, APP 扫码后本地校验失败直接拒绝, 表现为"提示重新扫描"且中转无任何日志。
    private final String v3ControllerId = UUID.randomUUID().toString();

    // ============ 官方 V4 协议桥接层 (dglab-websocket-server v4-server.ts 兼容) ============
    // V4已接入APP: 连接 -> clientId (8位hex, 官方v4-server用4字节随机hex)
    private final Map<WebSocket, String> v4AppConns = new ConcurrentHashMap<>();
    // V4 APP: clientId -> 连接
    private final Map<String, WebSocket> v4ConnByAppId = new ConcurrentHashMap<>();
    // V4 APP暴露的设备slotId列表: clientId -> slotIds (来自devices.get响应/devices.snapshot事件)
    private final Map<String, List<String>> v4SlotsByAppId = new ConcurrentHashMap<>();
    // V4 持续输出会话: clientId -> 会话 (网页"应用强度"实时控制: 后台持续推脉冲, 滑块实时改强度)
    // DG-LAB 设备必须持续收到 AppendPulseData(t:0) 波形帧才会有刺激; SetIntensity(t:7) 只实时改变幅度。
    private final Map<String, ContSession> v4Continuous = new ConcurrentHashMap<>();
    private static final long CONT_TICK_MS = 1000L; // 持续推送周期
    private static final int  CONT_FRAMES  = 10;    // 每周期帧数 = 1.0s (每帧100ms), 与周期相等避免队列堆积
    private static final long CONT_STRENGTH_REFRESH_MS = CONT_TICK_MS; // t:4临时强度刷新周期; t:4时长取其3倍防止到期失效
    /** 持续输出会话状态 */
    private static final class ContSession {
        volatile int a;                 // 通道A当前强度(0-200)
        volatile int b;                 // 通道B当前强度
        volatile String pattern;        // 当前波形模式名
        volatile java.util.concurrent.ScheduledFuture<?> task;
    }
    // V4 RPC自增请求ID (官方dglab-kit用自增数)
    private final java.util.concurrent.atomic.AtomicLong v4ReqCounter = new java.util.concurrent.atomic.AtomicLong(0);
    // 波形/清理调度器 (daemon线程, 不阻塞JVM退出)
    private final java.util.concurrent.ScheduledExecutorService bridgeScheduler =
            java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "dglab-bridge");
                t.setDaemon(true);
                return t;
            });
    /**
     * 官方波形帧: 挤压节奏 (每帧100ms: 静默100ms + 满幅100ms)
     * 来源: dglab-kit src/waveform/coyote.ts COYOTE_WAVEFORMS.EXTRUSTION
     * 帧格式: 前8位hex=频率, 后8位hex=幅度(0x64=100%, 0x00=0%)
     */
    private static final String[] WAVE_HIT_FRAMES = {"0A0A0A0A00000000", "0A0A0A0A64646464"};

    // ============ 波形模式库 (网页控制面板, 等同 DG-LAB APP 内波形选择) ============
    // 每帧 = 100ms, 8字节hex: 前4字节=频率Hz(同字节重复4次), 后4字节=幅度0~100(同字节重复4次)
    private static final java.util.LinkedHashMap<String, String[]> WAVEFORMS = new java.util.LinkedHashMap<>();
    static {
        WAVEFORMS.put("hit",     new String[]{wf(10, 0), wf(10, 100)});
        WAVEFORMS.put("breath",  buildWave(new int[]{0, 10, 25, 45, 65, 80, 92, 100, 100, 92, 80, 65, 45, 25, 10, 0, 0, 0, 0, 0}, 10));
        WAVEFORMS.put("heart",   buildWave(new int[]{100, 100, 30, 0, 100, 70, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 10));
        WAVEFORMS.put("wave",    buildWaveWave());
        WAVEFORMS.put("steady",  new String[]{wf(10, 100)});
        WAVEFORMS.put("flicker", buildWave(new int[]{100, 0, 100, 100, 0, 0, 100, 0, 100, 0, 0, 100, 100, 0, 100, 0, 0, 0, 100, 0}, 16));
    }
    /** 构造单帧: freqHz频率 + ampPct幅度(0-100) */
    private static String wf(int freqHz, int ampPct) {
        String fb = String.format("%02x", freqHz & 0xFF);
        String ab = String.format("%02x", Math.max(0, Math.min(100, ampPct)));
        return fb + fb + fb + fb + ab + ab + ab + ab;
    }
    /** 按幅度序列(固定频率)生成波形 */
    private static String[] buildWave(int[] amps, int freqHz) {
        String[] out = new String[amps.length];
        for (int i = 0; i < amps.length; i++) out[i] = wf(freqHz, amps[i]);
        return out;
    }
    /** 水波: 频率在8~16Hz间起伏, 幅度中等 */
    private static String[] buildWaveWave() {
        int[] freqs = {8, 9, 10, 12, 14, 16, 14, 12, 10, 9, 8, 9, 10, 12, 14, 12};
        String[] out = new String[freqs.length];
        for (int i = 0; i < freqs.length; i++) out[i] = wf(freqs[i], 55 + (i % 2 == 0 ? 12 : 0));
        return out;
    }
    private static String[] wavePattern(String name) {
        String[] p = WAVEFORMS.get(name == null ? "hit" : name);
        return p != null ? p : WAVEFORMS.get("hit");
    }

    public MessageRouter() {
        // 官方V3心跳: 每30秒向已配对APP下发, 保持长连接 (官方默认60s, 取更短更稳)
        bridgeScheduler.scheduleAtFixedRate(this::sendOfficialHeartbeats, 30, 30,
                java.util.concurrent.TimeUnit.SECONDS);
    }

    // 消息统计 (最近100条日志)
    private final Deque<String> eventLog = new ArrayDeque<>(100);

    // 统计
    private long fireCount;  // 触发开火指令次数

    // ============ 客户端注册 ============

    /**
     * 新连接建立
     */
    public void onConnect(WebSocket conn) {
        String remote = getRemote(conn);
        addLog("➕ 新客户端连接: " + remote);
    }

    /**
     * 连接断开
     */
    public void onDisconnect(WebSocket conn, int code, String reason) {
        String remote = getRemote(conn);
        officialDisconnect(conn);   // 官方V3桥接APP清理
        v4Disconnect(conn);         // 官方V4桥接APP清理
        String cid = connToClientId.remove(conn);
        if (cid != null) {
            appClients.remove(cid);
            addLog("➖ APP客户端离线: " + cid + " (" + remote + "), 原因: code=" + code);
        } else if (controllerConns.remove(conn)) {
            addLog("➖ 控制端(插件)离线: " + remote + ", 原因: code=" + code);
        } else {
            addLog("➖ 未注册客户端断开: " + remote + ", code=" + code);
        }
    }

    /**
     * 收到任意客户端的消息
     */
    public void onMessage(WebSocket conn, String message) {
        // 官方V4桥接APP的消息 (帧格式: type/clientId/data, ?tid=接入)
        if (v4AppConns.containsKey(conn)) {
            v4Message(conn, message);
            return;
        }
        // 官方V3桥接APP的消息 (帧格式: type/clientId/targetId/message, 无msgType字段)
        // 注意: 配对中的连接(已收第1步分配帧)也必须走官方处理, 否则APP回的bind会掉进自定义协议解析
        if (officialAppConns.contains(conn) || officialPendingConns.containsKey(conn)) {
            officialMessage(conn, message);
            return;
        }
        try {
            JSONParser parser = new JSONParser();
            Object obj = parser.parse(message);
            if (!(obj instanceof JSONObject)) {
                sendError(conn, "invalid_json", "消息必须是JSON对象");
                return;
            }
            JSONObject json = (JSONObject) obj;
            String msgType = (String) json.get("msgType");
            if (msgType == null) {
                sendError(conn, "missing_msgType", "缺少 msgType 字段");
                return;
            }

            switch (msgType) {
                case "bind":
                    handleBind(conn, json);
                    break;
                case "heartbeat":
                    handleHeartbeat(conn, json);
                    break;
                case "strength":
                    handleStrength(conn, json);
                    break;
                case "getClientList":
                    handleGetClientList(conn);
                    break;
                case "break":
                case "disconnect":
                    // APP主动断开
                    conn.close();
                    break;
                default:
                    // 未知消息类型：直接广播给所有APP（用于APP端其他透传协议）
                    broadcastToApps(json.toJSONString());
                    break;
            }

        } catch (Exception e) {
            addLog("⚠ 解析消息失败: " + e.getMessage() + " 原始: " + truncate(message, 100));
            sendError(conn, "parse_error", "JSON解析失败: " + e.getMessage());
        }
    }

    // ============ 具体消息处理 ============

    /**
     * APP端注册：{"msgType":"bind", "clientId":"xxx", "device":"xxx"}
     * 兼容模式：如果没有 clientId，服务器自动生成一个返回
     */
    @SuppressWarnings("unchecked")
    private void handleBind(WebSocket conn, JSONObject json) {
        String clientId = (String) json.get("clientId");
        if (clientId == null || clientId.trim().isEmpty()) {
            clientId = "app-" + UUID.randomUUID().toString().substring(0, 8);
        }
        // 如果该 clientId 已被占用，先踢掉旧连接
        WebSocket old = appClients.get(clientId);
        if (old != null && old != conn && old.isOpen()) {
            old.close(4000, "clientId rebinded");
        }
        appClients.put(clientId, conn);
        connToClientId.put(conn, clientId);
        addLog("📱 APP注册成功: clientId=" + clientId + "  设备: " + json.get("device"));

        // 回 ACK 给 APP
        JSONObject resp = new JSONObject();
        resp.put("msgType", "bind");
        resp.put("clientId", clientId);
        resp.put("code", 200);
        resp.put("message", "ok");
        sendJson(conn, resp);
    }

    /**
     * 心跳：{"msgType":"heartbeat"} 回包
     */
    @SuppressWarnings("unchecked")
    private void handleHeartbeat(WebSocket conn, JSONObject json) {
        JSONObject resp = new JSONObject();
        resp.put("msgType", "heartbeat");
        resp.put("timestamp", System.currentTimeMillis());
        sendJson(conn, resp);
    }

    /**
     * 强度控制（核心！从插件到APP）
     * 插件发送:
     * {
     *   msgType:"strength",
     *   data:{type:"fire", chA:15, chB:15, time:1000},
     *   targetId:"xxx"    (可选，不填=广播所有APP)
     * }
     */
    @SuppressWarnings("unchecked")
    private void handleStrength(WebSocket conn, JSONObject json) {
        // 记录发送方为控制端（如果还没被当作APP注册的话）
        if (!connToClientId.containsKey(conn)) {
            controllerConns.add(conn);
        }

        Object dataRaw = json.get("data");
        if (!(dataRaw instanceof JSONObject)) {
            sendError(conn, "invalid_data", "strength 消息必须带 data 对象");
            return;
        }
        JSONObject data = (JSONObject) dataRaw;
        // 兼容：data 中也允许带 msgType 字段
        String type = data.get("type") == null ? null : String.valueOf(data.get("type"));

        String targetId = (String) json.get("targetId");
        String payloadJson = json.toJSONString();

        int delivered = 0;
        if (targetId != null && !targetId.isEmpty()) {
            // 定向发送
            WebSocket target = appClients.get(targetId);
            if (target != null && target.isOpen()) {
                sendString(target, payloadJson);
                delivered = 1;
            } else {
                sendError(conn, "client_not_found", "targetId=" + targetId + " 不在线");
                return;
            }
        } else {
            // 广播给所有在线 APP
            delivered = broadcastToApps(payloadJson);
        }

        if ("fire".equalsIgnoreCase(type)) {
            fireCount++;
        }

        // ===== 官方V3/V4桥接: 把插件指令翻译成官方帧发给扫码接入的DG-LAB APP =====
        if ("stop".equalsIgnoreCase(type)) {
            deliverOfficialStop();
            deliverV4Stop();
        } else if ("hold".equalsIgnoreCase(type)) {
            // hold = 全程保持输出: 只把AB通道强度设定为指定值, 不附加波形、不做自动清除
            // (插件在游戏全程周期发送, 维持强度不归零)
            int ha = intValue(data.get("chA"));
            int hb = intValue(data.get("chB"));
            deliverOfficialHold(ha, hb);
            deliverV4Hold(ha, hb);
        } else {
            deliverOfficialFire(intValue(data.get("chA")), intValue(data.get("chB")), intValue(data.get("time")));
            deliverV4Fire(intValue(data.get("chA")), intValue(data.get("chB")), intValue(data.get("time")));
        }

        Object chA = data.get("chA");
        Object chB = data.get("chB");
        Object tm = data.get("time");
        addLog("⚡ 指令下发: type=" + type + "  chA=" + chA + " chB=" + chB + " time=" + tm + "ms  → 送达" + delivered + "台APP"
                + (targetId != null ? " (target=" + targetId + ")" : ""));

        // 给控制端回 ACK
        JSONObject ack = new JSONObject();
        ack.put("msgType", "strength-ack");
        ack.put("delivered", delivered);
        ack.put("totalApps", appClients.size());
        sendJson(conn, ack);
    }

    /**
     * 查询客户端列表
     */
    @SuppressWarnings("unchecked")
    private void handleGetClientList(WebSocket conn) {
        JSONObject resp = new JSONObject();
        resp.put("msgType", "getClientList");
        List<String> ids = new ArrayList<>(appClients.keySet());
        resp.put("clientIds", ids);
        resp.put("count", ids.size());
        sendJson(conn, resp);
    }

    // ============ 广播/发送 ============

    private int broadcastToApps(String jsonStr) {
        int n = 0;
        for (Map.Entry<String, WebSocket> entry : appClients.entrySet()) {
            WebSocket ws = entry.getValue();
            if (ws != null && ws.isOpen()) {
                try { ws.send(jsonStr); n++; }
                catch (Exception ignored) {}
            }
        }
        return n;
    }

    private void sendString(WebSocket ws, String s) {
        if (ws != null && ws.isOpen()) {
            ws.send(s);
        }
    }

    @SuppressWarnings("unchecked")
    private void sendJson(WebSocket ws, JSONObject j) {
        sendString(ws, j.toJSONString());
    }

    @SuppressWarnings("unchecked")
    private void sendError(WebSocket ws, String code, String msg) {
        if (ws == null || !ws.isOpen()) return;
        JSONObject err = new JSONObject();
        err.put("msgType", "error");
        err.put("code", code);
        err.put("message", msg);
        ws.send(err.toJSONString());
    }

    // ============ 辅助 ============

    private String getRemote(WebSocket ws) {
        try {
            return ws.getRemoteSocketAddress().toString();
        } catch (Exception e) {
            return "?";
        }
    }

    private synchronized void addLog(String line) {
        String ts = String.format("%1$tH:%1$tM:%1$tS", System.currentTimeMillis());
        String msg = "[" + ts + "] " + line;
        System.out.println(DGLabRelayServer.LOG_PREFIX + msg);
        while (eventLog.size() >= 100) eventLog.pollFirst();
        eventLog.addLast(msg);
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ============ HTTP状态页/外部查询 API ============

    public int getAppCount() { return appClients.size(); }
    public int getControllerCount() { return controllerConns.size(); }
    public long getFireCount() { return fireCount; }
    public List<String> getAppClientIds() { return new ArrayList<>(appClients.keySet()); }
    public List<String> getEventLog() { return new ArrayList<>(eventLog); }

    /** APP总数 = 自定义协议 + 官方V3桥接 + 官方V4桥接 (扫码配对的APP都算) */
    public int getTotalAppCount() { return appClients.size() + officialAppConns.size() + v4AppConns.size(); }
    public int getV4AppCount() { return v4AppConns.size(); }
    /** V4已配对APP的设备槽: appId -> slotIds (网页控制面板显示设备用) */
    public java.util.Map<String, List<String>> getV4Devices() { return new java.util.HashMap<>(v4SlotsByAppId); }
    /** 网页面板可选波形模式名列表 */
    public List<String> getWaveformNames() { return new ArrayList<>(WAVEFORMS.keySet()); }

    /** 连接是否已被注册为 APP / 控制端 / 官方V3桥接APP（都没注册=纯野连接） */
    public boolean isRegistered(WebSocket conn) {
        if (conn == null) return false;
        return connToClientId.containsKey(conn) || controllerConns.contains(conn) || officialAppConns.contains(conn);
    }

    /**
     * 自动把该未注册连接绑定为 APP（作为兼容兜底）
     * 会基于 remote 生成较稳定的 clientId
     * @return 分配的 clientId，如果连接已注册返回 null
     */
    public String autoBindApp(WebSocket conn, String remote) {
        if (isRegistered(conn) || conn == null) return null;
        // 从 remote 抽取 IP 做标识 (去掉 ":port")
        String host = (remote != null && remote.contains(":"))
                ? remote.substring(0, remote.lastIndexOf(':'))
                : (remote == null ? "unknown" : remote);
        // 生成稳定、可读的 id：APP-<IP后两段>-<短随机>
        String[] parts = host.split("\\.");
        String suf = (parts.length >= 2) ? parts[parts.length - 2] + parts[parts.length - 1] : host.replace(".", "");
        String baseId = "APP-AUTO-" + suf.toUpperCase();
        String id = baseId;
        int i = 1;
        synchronized (appClients) {
            while (appClients.containsKey(id)) id = baseId + "-" + (++i);
            appClients.put(id, conn);
            connToClientId.put(conn, id);
        }
        addLog("🤝 自动绑定为APP: clientId=" + id + " remote=" + remote);
        // 回发 bindAck，让APP知道绑定成功
        try {
            JSONObject ack = new JSONObject();
            ack.put("msgType", "bindAck");
            ack.put("clientId", id);
            ack.put("status", 200);
            ack.put("message", "ok (auto)");
            conn.send(ack.toJSONString());
        } catch (Exception ignored) {}
        return id;
    }

    /**
     * 供HTTP API 直接触发 fire 指令
     * @return 成功送达的APP数量
     */
    public int broadcastFire(int chA, int chB, int timeMs, String targetId) {
        return broadcastFire(chA, chB, timeMs, targetId, "hit");
    }

    /**
     * 供HTTP API 直接触发 fire 指令
     * @param pattern 波形模式名 (hit/breath/heart/wave/steady/flicker)
     * @return 成功送达的APP数量 (自定义协议 + V3桥接 + V4桥接)
     */
    @SuppressWarnings("unchecked")
    public int broadcastFire(int chA, int chB, int timeMs, String targetId, String pattern) {
        JSONObject data = new JSONObject();
        data.put("type", "fire");
        data.put("chA", chA);
        data.put("chB", chB);
        data.put("time", timeMs);
        JSONObject msg = new JSONObject();
        msg.put("msgType", "strength");
        if (targetId != null && !targetId.isEmpty()) {
            msg.put("targetId", targetId);
        }
        msg.put("data", data);
        String json = msg.toJSONString();
        fireCount++;
        addLog("🌐 HTTP触发: type=fire chA=" + chA + " chB=" + chB + " time=" + timeMs + "ms 波形=" + pattern);
        // 官方V3/V4桥接同步下发
        deliverOfficialFire(chA, chB, timeMs, pattern);
        deliverV4Fire(chA, chB, timeMs, pattern);

        int custom;
        if (targetId != null && !targetId.isEmpty()) {
            WebSocket ws = appClients.get(targetId);
            custom = (ws != null && ws.isOpen()) ? 1 : 0;
            if (custom == 1) ws.send(json);
        } else {
            custom = broadcastToApps(json);
        }
        return custom + officialAppConns.size() + v4AppConns.size();
    }

    /** 网页面板: 设定双通道强度 (启动/更新持续输出会话, 实时生效, 不归零) */
    public int broadcastSetIntensity(int chA, int chB) {
        return broadcastSetIntensity(chA, chB, null);
    }

    /**
     * 网页面板: 应用强度 (点"✅应用强度"才调用)。
     * 只把当前A/B强度(及波形)下发到手机, 不启动脉冲输出 (刺激在点"开火"后才开始):
     *  - 若正在持续开火: 实时更新会话强度, 正在播放的脉冲幅度瞬变;
     *  - 若未开火: 仅下发 SetIntensity 预设强度(开火时按此强度输出), 不产生刺激。
     */
    @SuppressWarnings("unchecked")
    public int broadcastSetIntensity(int chA, int chB, String pattern) {
        JSONObject data = new JSONObject();
        data.put("type", "hold");
        data.put("chA", chA);
        data.put("chB", chB);
        JSONObject msg = new JSONObject();
        msg.put("msgType", "strength");
        msg.put("data", data);
        int custom = broadcastToApps(msg.toJSONString());
        deliverOfficialHold(chA, chB);
        v4ApplyStrength(chA, chB, pattern);
        addLog("🌐 HTTP应用强度: A=" + chA + " B=" + chB + (pattern != null ? " 波形=" + pattern : ""));
        return custom + officialAppConns.size() + v4AppConns.size();
    }

    /**
     * 网页面板: 持续开火 (开火时长选"持续"时调用)。
     * 启动无限脉冲流, 一直输出直到点"停止开火"(/api/stop)。
     * V4: 后台每秒续推脉冲帧; V3/自定义: 发一长段(30s)火力作为尽力兜底。
     */
    @SuppressWarnings("unchecked")
    public int broadcastFireContinuous(int chA, int chB, String pattern) {
        JSONObject data = new JSONObject();
        data.put("type", "fire");
        data.put("chA", chA);
        data.put("chB", chB);
        data.put("time", 30000);
        JSONObject msg = new JSONObject();
        msg.put("msgType", "strength");
        msg.put("data", data);
        int custom = broadcastToApps(msg.toJSONString());
        fireCount++;
        deliverOfficialFire(chA, chB, 30000, pattern); // V3 长段兜底(30s)
        v4StartContinuous(chA, chB, pattern);          // V4 真正持续到停止
        addLog("🌐 HTTP持续开火: A=" + chA + " B=" + chB + " 波形=" + pattern + " (直到停止开火)");
        return custom + officialAppConns.size() + v4AppConns.size();
    }

    /** 网页面板: 停止所有输出 (停止持续会话 + 清波形 + 强度归零) */
    @SuppressWarnings("unchecked")
    public int broadcastStopAll() {
        JSONObject data = new JSONObject();
        data.put("type", "stop");
        JSONObject msg = new JSONObject();
        msg.put("msgType", "strength");
        msg.put("data", data);
        int custom = broadcastToApps(msg.toJSONString());
        v4StopContinuous();   // 先停持续推送任务
        deliverOfficialStop();
        deliverV4Stop();
        addLog("🌐 HTTP停止: 所有通道已清零");
        return custom + officialAppConns.size() + v4AppConns.size();
    }

    // ============================================================
    // V4 持续输出会话 (网页"应用强度"实时控制的核心)
    // ============================================================

    /**
     * 启动 V4 持续开火 (点"开火-持续"时调用):
     *  立即设强度+推首批脉冲, 并起后台任务每秒续推脉冲帧 (设备需连续脉冲才有刺激),
     *  一直输出直到 v4StopContinuous (/api/stop)。若已在持续中, 仅更新参数。
     */
    public void v4StartContinuous(int chA, int chB, String pattern) {
        if (v4AppConns.isEmpty()) return;
        final int a = Math.max(0, Math.min(200, chA));
        final int b = Math.max(0, Math.min(200, chB));
        final String pat = (pattern == null || pattern.trim().isEmpty()) ? "steady" : pattern;
        for (Map.Entry<WebSocket, String> e : v4AppConns.entrySet()) {
            WebSocket conn = e.getKey();
            String clientId = e.getValue();
            if (conn == null || !conn.isOpen()) continue;
            List<String> slots = v4SlotsByAppId.get(clientId);
            if (slots == null || slots.isEmpty()) {
                sendV4Rpc(conn, "devices.get", null);
                addLog("⚠ [V4持续] 设备槽未同步, 已重新请求设备列表: " + clientId);
                continue;
            }
            ContSession sess = v4Continuous.get(clientId);
            if (sess == null) {
                sess = new ContSession();
                sess.a = a; sess.b = b; sess.pattern = pat;
                final ContSession sf = sess;
                final WebSocket fconn = conn;
                final List<String> fslots = slots;
                // 先设通道强度等级(t:4, 3s有效), 再推首批波形 → 马上有感觉
                v4SetTempStrength(fconn, fslots, sf.a, sf.b, CONT_STRENGTH_REFRESH_MS * 3);
                v4SendPulse(fconn, fslots, sf.pattern, CONT_FRAMES);
                // 后台每秒: 刷新强度等级(t:4, 读实时sf.a/sf.b → 改强度≤1s生效) + 续推波形帧
                sf.task = bridgeScheduler.scheduleAtFixedRate(() -> {
                    try {
                        v4SetTempStrength(fconn, fslots, sf.a, sf.b, CONT_STRENGTH_REFRESH_MS * 3);
                        v4SendPulse(fconn, fslots, sf.pattern, CONT_FRAMES);
                    } catch (Exception ex) { addLog("⚠ [V4持续] 推送异常: " + ex.getMessage()); }
                }, CONT_TICK_MS, CONT_TICK_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                v4Continuous.put(clientId, sf);
                addLog("🟢 [V4持续] 持续开火开始: clientId=" + clientId + " A=" + a + " B=" + b + " 波形=" + pat);
            } else {
                // 已在持续中: 更新参数并即时生效
                sess.a = a; sess.b = b; sess.pattern = pat;
                v4SetTempStrength(conn, slots, sess.a, sess.b, CONT_STRENGTH_REFRESH_MS * 3);
                v4SendPulse(conn, slots, sess.pattern, CONT_FRAMES);
                addLog("🟢 [V4持续] 持续开火更新: clientId=" + clientId + " A=" + sess.a + " B=" + sess.b + " 波形=" + sess.pattern);
            }
        }
    }

    /**
     * 应用强度 (点"✅应用强度"时调用) —— 只下发 SetIntensity, 不启动脉冲:
     *  - 正在持续开火: 更新会话强度并立即 SetIntensity, 正在播放的脉冲幅度瞬变 (实时调强度);
     *  - 未开火: 仅预设强度(开火时按此值输出), 不产生刺激。
     */
    public void v4ApplyStrength(int chA, int chB, String pattern) {
        if (v4AppConns.isEmpty()) return;
        final int a = Math.max(0, Math.min(200, chA));
        final int b = Math.max(0, Math.min(200, chB));
        final boolean hasPat = pattern != null && !pattern.trim().isEmpty();
        final String pat = hasPat ? pattern : "steady";
        for (Map.Entry<WebSocket, String> e : v4AppConns.entrySet()) {
            WebSocket conn = e.getKey();
            String clientId = e.getValue();
            if (conn == null || !conn.isOpen()) continue;
            List<String> slots = v4SlotsByAppId.get(clientId);
            if (slots == null || slots.isEmpty()) {
                sendV4Rpc(conn, "devices.get", null);
                continue;
            }
            ContSession sess = v4Continuous.get(clientId);
            if (sess != null) {
                // 持续开火中: 实时调整 —— 立即发 t:4 改通道强度等级(<1s见效), 切波形则补推波形帧
                sess.a = a; sess.b = b;
                if (hasPat) sess.pattern = pat;
                v4SetTempStrength(conn, slots, sess.a, sess.b, CONT_STRENGTH_REFRESH_MS * 3);
                if (hasPat) v4SendPulse(conn, slots, sess.pattern, CONT_FRAMES);
                addLog("🟡 [V4持续] 实时调强度: clientId=" + clientId + " A=" + sess.a + " B=" + sess.b + " 波形=" + sess.pattern);
            } else {
                // 未开火: 仅记录预设值(无脉冲即无输出), 开火时按此等级输出
                addLog("🔵 [V4] 应用强度(未开火, 已预设): clientId=" + clientId + " A=" + a + " B=" + b);
            }
        }
    }

    /** 停止所有 V4 持续输出会话 (取消后台任务; 清设备任务+归零由 deliverV4Stop 完成) */
    public void v4StopContinuous() {
        for (ContSession s : v4Continuous.values()) {
            if (s.task != null) s.task.cancel(false);
        }
        if (!v4Continuous.isEmpty()) {
            v4Continuous.clear();
            addLog("🛑 [V4持续] 已停止所有持续输出会话");
        }
    }

    /**
     * V4: 推送一批脉冲波形帧 (device.op t:0 AppendPulseData, A/B双通道, ver=3)
     * 波形帧只承载"形状"(幅度字节0-100为形状包络, 峰值满幅);
     * 实际输出强度由 SetTempIntensity(t:4) 设定的通道等级决定 (见 v4SetTempStrength)。
     */
    @SuppressWarnings("unchecked")
    private void v4SendPulse(WebSocket conn, List<String> slots, String pattern, int frameCount) {
        String[] pat = wavePattern(pattern);
        org.json.simple.JSONArray frames = new org.json.simple.JSONArray();
        for (int i = 0; i < frameCount; i++) frames.add(pat[i % pat.length]);
        long durMs = frameCount * 100L;
        for (String slot : slots) {
            JSONObject pa = new JSONObject();
            pa.put("s", slot); pa.put("c", 0); pa.put("t", 0);
            pa.put("v", frames); pa.put("d", durMs); pa.put("ver", 3); pa.put("im", true);
            sendV4Rpc(conn, "device.op", pa);
            JSONObject pb = new JSONObject();
            pb.put("s", slot); pb.put("c", 1); pb.put("t", 0);
            pb.put("v", frames); pb.put("d", durMs); pb.put("ver", 3); pb.put("im", true);
            sendV4Rpc(conn, "device.op", pb);
        }
    }

    /**
     * V4: 设定通道强度等级 (device.op t:4 SetTempIntensity) —— 官方 kit 设绝对强度的正确方式。
     * t:7 SetIntensity 远程仅 v:0(复位) 被APP采纳; 非0绝对强度须用 t:4 {v:等级, d:持续ms}。
     * A/B 通道分别设置; durationMs 内该等级有效, 持续开火时由调用方周期性刷新防止失效。
     */
    @SuppressWarnings("unchecked")
    private void v4SetTempStrength(WebSocket conn, List<String> slots, int a, int b, long durationMs) {
        for (String slot : slots) {
            JSONObject ta = new JSONObject();
            ta.put("s", slot); ta.put("c", 0); ta.put("t", 4);
            ta.put("v", Math.max(0, Math.min(200, a))); ta.put("d", durationMs); ta.put("im", true);
            sendV4Rpc(conn, "device.op", ta);
            JSONObject tb = new JSONObject();
            tb.put("s", slot); tb.put("c", 1); tb.put("t", 4);
            tb.put("v", Math.max(0, Math.min(200, b))); tb.put("d", durationMs); tb.put("im", true);
            sendV4Rpc(conn, "device.op", tb);
        }
    }

    // ============================================================
    // 官方 V3 协议桥接 (dglab-websocket-server v3-server.ts 兼容)
    // 服务自身充当官方协议的"控制端": 二维码中的 tid = bridgeControllerId
    // APP扫码 → ws://ip:port/<tid> → 服务器分配appId并配对
    // 插件fire指令 → 翻译为官方帧: strength-N+2+值 / pulse-A:[帧...] / clear-N
    // ============================================================

    /** 二维码 tid 使用的桥接控制端 clientId */
    public String getBridgeControllerId() { return bridgeControllerId; }
    /** V3 官方二维码使用的控制端 clientId (UUID, 与V4的8hex分开) */
    public String getV3BridgeControllerId() { return v3ControllerId; }
    public boolean isOfficialApp(WebSocket conn) { return conn != null && officialAppConns.contains(conn); }
    public int getOfficialAppCount() { return officialAppConns.size(); }
    public List<String> getOfficialAppIds() { return new ArrayList<>(officialIdByConn.values()); }

    /**
     * 官方V3 APP通过二维码接入 (URL路径段携带控制端clientId)
     * 严格复刻官方 v3-server.ts onOpen 行为:
     *  1. 下发 {"type":"bind","clientId":appId,"targetId":"","message":"targetId"}
     *  2. 立即配对并下发 {"type":"bind","clientId":<控制端ID>,"targetId":appId,"message":"200"}
     * 注意: 官方 v3-server 在APP带targetId连入时【直接】pair并发200, 不需要等APP回bind。
     *       若等APP回bind才发200, 真实APP会一直等待配对确认 → 超时提示"重新扫描二维码"。
     */
    public void officialConnect(WebSocket conn, String targetId) {
        String remote = getRemote(conn);
        if (targetId == null || !targetId.equals(v3ControllerId)) {
            sendOfficialFrame(conn, "", "", "error", "401");
            addLog("❌ [V3桥接] APP接入被拒: 无效targetId=" + targetId + " remote=" + remote);
            conn.close(4001, "invalid_target_id");
            return;
        }
        // 官方v3-server 为每个连接分配 crypto.randomUUID() 作为 clientId
        String appId = UUID.randomUUID().toString();
        try {
            // 直接注册为已配对APP (官方 onOpen 即完成 pair)
            officialAppConns.add(conn);
            officialIdByConn.put(conn, appId);
            // 第1帧: 告知APP分配到的连接ID
            sendOfficialFrame(conn, appId, "", "bind", "targetId");
            // 第2帧: 配对确认 200 (官方 onOpen 中 pair 成功后立即发送)
            sendOfficialFrame(conn, v3ControllerId, appId, "bind", "200");
            addLog("✅ [V3桥接] DG-LAB APP 配对成功! appId=" + appId
                    + " 二维码tid=" + targetId + " remote=" + remote);
        } catch (Exception e) {
            officialAppConns.remove(conn);
            officialIdByConn.remove(conn);
            addLog("⚠ [V3桥接] APP接入异常: " + e.getMessage());
        }
    }

    /** 官方V3 APP上行消息: 冗余bind(幂等)、feedback/strength 上报、心跳等 */
    private void officialMessage(WebSocket conn, String message) {
        try {
            Object obj = new JSONParser().parse(message);
            if (!(obj instanceof JSONObject)) return;
            JSONObject json = (JSONObject) obj;
            String type = json.get("type") == null ? "" : String.valueOf(json.get("type"));
            String msg = json.get("message") == null ? "" : String.valueOf(json.get("message"));

            // APP 主动回发的 bind: 配对已在连接时完成, 仅幂等重发200(部分APP会再发)
            if ("bind".equals(type)) {
                String appId = officialIdByConn.get(conn);
                if (appId != null) {
                    sendOfficialFrame(conn, v3ControllerId, appId, "bind", "200");
                }
                return;
            }

            if ("heartbeat".equals(type)) return; // 服务器侧已定时下发, 忽略回显
            String appId = officialIdByConn.get(conn);
            if ("msg".equals(type) && (msg.startsWith("feedback") || msg.startsWith("strength"))) {
                addLog("📥 [V3桥接] APP上报: " + msg + " (appId=" + appId + ")");
                return;
            }
            addLog("📨 [V3桥接] APP消息 type=" + type + " message=" + truncate(msg, 80));
        } catch (Exception e) {
            addLog("⚠ [V3桥接] APP消息解析失败: " + truncate(message, 80));
        }
    }

    /** 官方V3断开清理 (含配对中的连接) */
    public void officialDisconnect(WebSocket conn) {
        if (conn == null) return;
        officialPendingConns.remove(conn);
        String appId = officialIdByConn.remove(conn);
        if (appId != null) {
            officialAppConns.remove(conn);
            cancelOfficialPulse(appId);
            addLog("➖ [V3桥接] APP离线: appId=" + appId);
        }
    }

    /** 定时官方心跳: V3为bind格式帧, V4为纯{type:heartbeat} (官方v4-server broadcastHeartbeat) */
    private void sendOfficialHeartbeats() {
        for (WebSocket conn : officialAppConns) {
            if (conn == null || !conn.isOpen()) continue;
            String appId = officialIdByConn.get(conn);
            if (appId == null) continue;
            try {
                sendOfficialFrame(conn, appId, v3ControllerId, "heartbeat", "200");
            } catch (Exception ignored) {}
        }
        // V4心跳: 纯 {type:'heartbeat'} 广播
        for (WebSocket conn : v4AppConns.keySet()) {
            if (conn == null || !conn.isOpen()) continue;
            try {
                sendV4Frame(conn, kv("type", "heartbeat"));
            } catch (Exception ignored) {}
        }
    }

    /**
     * 把插件/HTTP的 fire 指令翻译成官方V3帧发给所有已配对APP:
     *  1. strength-1+2+chA / strength-2+2+chB  设定双通道强度
     *  2. pulse-A:[帧...]  每1秒1包(10帧, 每帧100ms), 持续 timeMs
     *  3. 结束后 clear-1 / clear-2 停止波形
     */
    public void deliverOfficialFire(int chA, int chB, int timeMs) {
        deliverOfficialFire(chA, chB, timeMs, "hit");
    }

    /**
     * 把插件/HTTP的 fire 指令翻译成官方V3帧发给所有已配对APP:
     *  1. strength-1+2+chA / strength-2+2+chB  设定双通道强度
     *  2. pulse-A:[帧...]  每1秒1包(10帧, 每帧100ms), 持续 timeMs (帧内容由 pattern 波形决定)
     *  3. 结束后 clear-1 / clear-2 停止波形
     */
    public void deliverOfficialFire(int chA, int chB, int timeMs, String pattern) {
        if (officialAppConns.isEmpty()) return;
        final int a = Math.max(0, Math.min(200, chA));
        final int b = Math.max(0, Math.min(200, chB));
        final long dur = Math.max(200, Math.min(30000, timeMs));
        final String[] pat = wavePattern(pattern);
        for (WebSocket conn : officialAppConns) {
            if (conn == null || !conn.isOpen()) continue;
            final String appId = officialIdByConn.get(conn);
            if (appId == null) continue;
            try {
                cancelOfficialPulse(appId); // 新波形覆盖旧波形 (官方v3行为: 先清通道)
                sendOfficialFrame(conn, v3ControllerId, appId, "msg", "clear-1");
                sendOfficialFrame(conn, v3ControllerId, appId, "msg", "clear-2");
                sendOfficialFrame(conn, v3ControllerId, appId, "msg", "strength-1+2+" + a);
                sendOfficialFrame(conn, v3ControllerId, appId, "msg", "strength-2+2+" + b);
                // 波形帧: 每100ms一帧, 按所选波形模式循环
                int totalFrames = (int) Math.max(2, dur / 100);
                List<String> frames = new ArrayList<>(totalFrames);
                for (int i = 0; i < totalFrames; i++) {
                    frames.add(pat[i % pat.length]);
                }
                // 按1秒(10帧)一包拆分, 定时发送 (官方v3: DEFAULT_PUNISHMENT_TIME=1)
                List<java.util.concurrent.ScheduledFuture<?>> tasks = new ArrayList<>();
                int packetCount = (totalFrames + 9) / 10;
                for (int p = 0; p < packetCount; p++) {
                    final int from = p * 10;
                    final int to = Math.min(from + 10, totalFrames);
                    final String pulseMsg = "pulse-A:" + framesToJson(frames.subList(from, to));
                    final boolean last = (p == packetCount - 1);
                    tasks.add(bridgeScheduler.schedule(() -> {
                        try {
                            sendOfficialFrame(conn, v3ControllerId, appId, "msg", pulseMsg);
                            if (last) {
                                sendOfficialFrame(conn, v3ControllerId, appId, "msg", "clear-1");
                                sendOfficialFrame(conn, v3ControllerId, appId, "msg", "clear-2");
                            }
                        } catch (Exception ignored) {}
                    }, p * 1000L, java.util.concurrent.TimeUnit.MILLISECONDS));
                }
                officialPulseTasks.put(appId, tasks);
                addLog("⚡ [V3桥接] 开火→APP: appId=" + appId + " A=" + a + " B=" + b + " 时长=" + dur + "ms 波形=" + pattern + " 包数=" + packetCount);
            } catch (Exception e) {
                addLog("⚠ [V3桥接] 开火转发失败: " + e.getMessage());
            }
        }
    }

    /** 停止所有官方APP输出: 清波形 + 强度归零 (对应插件 stop 指令) */
    public void deliverOfficialStop() {
        if (officialAppConns.isEmpty()) return;
        for (WebSocket conn : officialAppConns) {
            if (conn == null || !conn.isOpen()) continue;
            String appId = officialIdByConn.get(conn);
            if (appId == null) continue;
            try {
                cancelOfficialPulse(appId);
                sendOfficialFrame(conn, v3ControllerId, appId, "msg", "clear-1");
                sendOfficialFrame(conn, v3ControllerId, appId, "msg", "clear-2");
                sendOfficialFrame(conn, v3ControllerId, appId, "msg", "strength-1+2+0");
                sendOfficialFrame(conn, v3ControllerId, appId, "msg", "strength-2+2+0");
            } catch (Exception ignored) {}
        }
        addLog("🛑 [V3桥接] 停止指令已下发所有官方APP (强度归零)");
    }

    /** 全程保持输出(V3): 把AB通道强度设定为指定值, 不做清除 (对应插件 hold 指令) */
    public void deliverOfficialHold(int chA, int chB) {
        if (officialAppConns.isEmpty()) return;
        final int a = Math.max(0, Math.min(200, chA));
        final int b = Math.max(0, Math.min(200, chB));
        for (WebSocket conn : officialAppConns) {
            if (conn == null || !conn.isOpen()) continue;
            String appId = officialIdByConn.get(conn);
            if (appId == null) continue;
            try {
                sendOfficialFrame(conn, v3ControllerId, appId, "msg", "strength-1+2+" + a);
                sendOfficialFrame(conn, v3ControllerId, appId, "msg", "strength-2+2+" + b);
            } catch (Exception ignored) {}
        }
        addLog("⏱ [V3桥接] 保持强度已下发所有官方APP (A=" + a + " B=" + b + ")");
    }

    private void cancelOfficialPulse(String appId) {
        List<java.util.concurrent.ScheduledFuture<?>> old = officialPulseTasks.remove(appId);
        if (old != null) {
            for (java.util.concurrent.ScheduledFuture<?> f : old) f.cancel(false);
        }
    }

    // ============================================================
    // 官方 V4 协议桥接 (dglab-websocket-server v4-server.ts 兼容)
    // 服务自身充当官方协议的"控制端": 二维码中的 tid = bridgeControllerId
    // APP扫码 → ws://ip:port?tid=<tid> → hello + controller_attached 即配对完成
    // 插件fire指令 → 翻译为V4 RPC: {t:'req',m:'device.op',data:{s,c,t,v}}
    //   t:7=SetIntensity  t:0=AppendPulseData  device.op.clear=清理任务
    // ============================================================

    /**
     * 官方V4 APP通过二维码接入 (URL查询参数 ?tid=控制方clientId)
     * 严格复刻 v4-server.ts onOpen + attachClient 流程:
     *  1. 先发 {"type":"hello","clientId":appId} (所有连接固定第一帧)
     *  2. tid无效 → {"type":"error","code":"controller_not_found"} + close 4001
     *  3. 配对成功 → {"type":"controller_attached","clientId":控制方ID}
     *  4. 不再立即发 devices.get (官方服务器从不主动发RPC)
     *     APP收到 controller_attached 后会自动上报 devices.snapshot 事件
     */
    public void v4Connect(WebSocket conn, String targetId) {
        String remote = getRemote(conn);
        // 生成8位hex clientId (官方v4-server: 4字节随机hex)
        String clientId;
        do {
            clientId = randomHexId();
        } while (v4ConnByAppId.containsKey(clientId));
        try {
            sendV4Frame(conn, kv("type", "hello", "clientId", clientId));
            if (targetId == null || !targetId.equals(bridgeControllerId)) {
                sendV4Frame(conn, kv("type", "error", "code", "controller_not_found"));
                addLog("❌ [V4桥接] APP接入被拒: 无效tid=" + targetId + " remote=" + remote);
                conn.close(4001, "controller_not_found");
                return;
            }
            v4AppConns.put(conn, clientId);
            v4ConnByAppId.put(clientId, conn);
            sendV4Frame(conn, kv("type", "controller_attached", "clientId", bridgeControllerId));
            addLog("✅ [V4桥接] DG-LAB 4 APP 配对成功! clientId=" + clientId + " remote=" + remote
                    + " (等待APP自动上报 devices.snapshot)");
        } catch (Exception e) {
            v4AppConns.remove(conn);
            v4ConnByAppId.remove(clientId);
            addLog("⚠ [V4桥接] APP接入异常: " + e.getMessage());
        }
    }

    /** 官方V4 APP上行消息: 设备列表事件/响应、ping、心跳等 */
    private void v4Message(WebSocket conn, String message) {
        try {
            Object obj = new JSONParser().parse(message);
            if (!(obj instanceof JSONObject)) {
                addLog("📨 [V4桥接] RX非JSON: " + truncate(message, 120));
                return;
            }
            JSONObject json = (JSONObject) obj;
            String type = json.get("type") == null ? "" : String.valueOf(json.get("type"));
            // 消息级ping → 回pong (官方v4-server handlePingMessage)
            if ("ping".equals(type)) {
                sendV4Frame(conn, kv("type", "pong", "ts", System.currentTimeMillis()));
                return;
            }
            if ("pong".equals(type) || "heartbeat".equals(type)) return; // 回显忽略
            if (!"message".equals(type)) {
                // 未知帧: 完整记录用于诊断APP断开问题
                addLog("📨 [V4桥接] RX未知帧 type=" + type + ": " + truncate(message, 160));
                return;
            }
            Object dataObj = json.get("data");
            if (!(dataObj instanceof JSONObject)) {
                addLog("📨 [V4桥接] RX message无data: " + truncate(message, 160));
                return;
            }
            JSONObject data = (JSONObject) dataObj;
            String clientId = v4AppConns.get(conn);
            if (clientId == null) return;
            String dt = data.get("t") == null ? "" : String.valueOf(data.get("t"));
            if ("resp".equals(dt)) {
                handleV4Response(conn, clientId, data);
            } else if ("ev".equals(dt)) {
                handleV4Event(conn, clientId, data);
            } else if ("req".equals(dt)) {
                // APP主动发起的RPC请求: 必须回复, 否则APP侧RPC超时会判定控制方无响应
                handleV4Request(conn, clientId, data);
            } else {
                addLog("📨 [V4桥接] APP数据: " + truncate(data.toJSONString(), 120) + " (clientId=" + clientId + ")");
            }
        } catch (Exception e) {
            addLog("⚠ [V4桥接] APP消息解析失败: " + truncate(message, 120) + " err=" + e.getMessage());
        }
    }

    /** APP主动发起的RPC请求 (t:req): ping探测回时间戳, 其余回错误, 保证APP的RPC不悬空 */
    @SuppressWarnings("unchecked")
    private void handleV4Request(WebSocket conn, String clientId, JSONObject data) {
        String reqId = data.get("reqId") == null ? "" : String.valueOf(data.get("reqId"));
        String m = data.get("m") == null ? "" : String.valueOf(data.get("m"));
        addLog("📨 [V4桥接] APP发起RPC: m=" + m + " reqId=" + reqId + " (clientId=" + clientId + ")");
        JSONObject payload = new JSONObject();
        payload.put("t", "resp");
        if (!reqId.isEmpty()) payload.put("reqId", reqId);
        if ("ping".equals(m)) {
            payload.put("result", System.currentTimeMillis());
        } else {
            payload.put("error", "unimplemented");
        }
        sendV4MessageData(conn, payload);
    }

    /** V4 RPC响应: devices.get的结果含设备列表 */
    @SuppressWarnings("unchecked")
    private void handleV4Response(WebSocket conn, String clientId, JSONObject data) {
        Object resultObj = data.get("result");
        if (resultObj instanceof JSONObject) {
            Object devices = ((JSONObject) resultObj).get("devices");
            updateV4Slots(clientId, devices);
        }
    }

    /** V4事件: devices.snapshot(全量) / devices.patch(增量) / slots.patch / custom.action */
    @SuppressWarnings("unchecked")
    private void handleV4Event(WebSocket conn, String clientId, JSONObject data) {
        String ev = data.get("ev") == null ? "" : String.valueOf(data.get("ev"));
        switch (ev) {
            case "devices.snapshot":
                updateV4Slots(clientId, data.get("devices"));
                break;
            case "devices.patch": {
                List<String> slots = v4SlotsByAppId.getOrDefault(clientId, new ArrayList<>());
                Object added = data.get("added");
                if (added instanceof org.json.simple.JSONArray) {
                    for (Object d : (org.json.simple.JSONArray) added) {
                        if (d instanceof JSONObject) {
                            String slot = String.valueOf(((JSONObject) d).get("slotId"));
                            if (!slots.contains(slot)) slots.add(slot);
                        }
                    }
                }
                Object removed = data.get("removed");
                if (removed instanceof org.json.simple.JSONArray) {
                    for (Object r : (org.json.simple.JSONArray) removed) slots.remove(String.valueOf(r));
                }
                v4SlotsByAppId.put(clientId, slots);
                addLog("📋 [V4桥接] 设备列表增量: clientId=" + clientId + " 当前设备=" + slots);
                break;
            }
            case "custom.action":
                addLog("📥 [V4桥接] APP按钮反馈: action=" + data.get("action") + " (clientId=" + clientId + ")");
                break;
            case "slots.patch":
                addLog("📥 [V4桥接] 设备状态更新 (clientId=" + clientId + ")");
                break;
            default:
                addLog("📨 [V4桥接] APP事件: ev=" + ev + " (clientId=" + clientId + ")");
        }
    }

    /** 从设备数组更新slotId列表 */
    @SuppressWarnings("unchecked")
    private void updateV4Slots(String clientId, Object devices) {
        if (!(devices instanceof org.json.simple.JSONArray)) return;
        List<String> slots = new ArrayList<>();
        for (Object d : (org.json.simple.JSONArray) devices) {
            if (d instanceof JSONObject) {
                Object slot = ((JSONObject) d).get("slotId");
                if (slot != null) slots.add(String.valueOf(slot));
            }
        }
        v4SlotsByAppId.put(clientId, slots);
        addLog("📋 [V4桥接] 设备列表同步: clientId=" + clientId + " 设备=" + slots);
    }

    /**
     * 把插件/HTTP的 fire 指令翻译成V4 RPC发给所有已配对APP:
     *  1. device.op t:7 设定双通道强度 (SetIntensity)
     *  2. device.op t:0 下发波形帧 (AppendPulseData, ver=3, 与V3波形帧格式一致)
     */
    public void deliverV4Fire(int chA, int chB, int timeMs) {
        deliverV4Fire(chA, chB, timeMs, "hit");
    }

    /**
     * 把插件/HTTP的 fire 指令翻译成V4 RPC发给所有已配对APP:
     *  1. device.op t:7 设定双通道强度 (SetIntensity)
     *  2. device.op t:0 下发波形帧 (AppendPulseData, ver=3, A/B双通道, 帧内容由 pattern 决定)
     */
    @SuppressWarnings("unchecked")
    public void deliverV4Fire(int chA, int chB, int timeMs, String pattern) {
        if (v4AppConns.isEmpty()) return;
        final int a = Math.max(0, Math.min(200, chA));
        final int b = Math.max(0, Math.min(200, chB));
        final long dur = Math.max(200, Math.min(30000, timeMs));
        for (Map.Entry<WebSocket, String> e : v4AppConns.entrySet()) {
            WebSocket conn = e.getKey();
            String clientId = e.getValue();
            if (conn == null || !conn.isOpen()) continue;
            try {
                List<String> slots = v4SlotsByAppId.get(clientId);
                if (slots == null || slots.isEmpty()) {
                    // 设备列表未同步: 重新请求并提示
                    sendV4Rpc(conn, "devices.get", null);
                    addLog("⚠ [V4桥接] APP暂无已同步设备(请确认APP已连接硬件), 已重新请求设备列表: clientId=" + clientId);
                    continue;
                }
                // 1. 设通道强度等级 t:4 SetTempIntensity(整个开火时长有效); 2. 推波形帧(形状)
                int totalFrames = (int) Math.max(2, dur / 100);
                v4SetTempStrength(conn, slots, a, b, dur + 500L);
                String[] patArr = wavePattern(pattern);
                org.json.simple.JSONArray frames = new org.json.simple.JSONArray();
                for (int i = 0; i < totalFrames; i++) frames.add(patArr[i % patArr.length]);
                for (String slot : slots) {
                    JSONObject pulseA = new JSONObject();
                    pulseA.put("s", slot); pulseA.put("c", 0); pulseA.put("t", 0);
                    pulseA.put("v", frames); pulseA.put("d", dur); pulseA.put("ver", 3); pulseA.put("im", true);
                    sendV4Rpc(conn, "device.op", pulseA);
                    JSONObject pulseB = new JSONObject();
                    pulseB.put("s", slot); pulseB.put("c", 1); pulseB.put("t", 0);
                    pulseB.put("v", frames); pulseB.put("d", dur); pulseB.put("ver", 3); pulseB.put("im", true);
                    sendV4Rpc(conn, "device.op", pulseB);
                }
                addLog("⚡ [V4桥接] 开火→APP: clientId=" + clientId + " A=" + a + " B=" + b
                        + " 时长=" + dur + "ms 波形=" + pattern + " 设备=" + slots.size() + "台");
            } catch (Exception ex) {
                addLog("⚠ [V4桥接] 开火转发失败: " + ex.getMessage());
            }
        }
    }

    /**
     * 全程保持输出(V4): 只把AB通道强度设定为指定值, 不下发波形、不做自动清除。
     * 供插件游戏全程周期调用, 防止强度被清除后归零。
     */
    @SuppressWarnings("unchecked")
    public void deliverV4Hold(int chA, int chB) {
        if (v4AppConns.isEmpty()) return;
        final int a = Math.max(0, Math.min(200, chA));
        final int b = Math.max(0, Math.min(200, chB));
        for (Map.Entry<WebSocket, String> e : v4AppConns.entrySet()) {
            WebSocket conn = e.getKey();
            String clientId = e.getValue();
            if (conn == null || !conn.isOpen()) continue;
            try {
                List<String> slots = v4SlotsByAppId.get(clientId);
                if (slots == null || slots.isEmpty()) {
                    sendV4Rpc(conn, "devices.get", null);
                    continue;
                }
                // 保持强度: t:4 SetTempIntensity 3s有效 (插件每2s发一次, 不断档); 远程t:7非0不被采纳
                v4SetTempStrength(conn, slots, a, b, 3000L);
                addLog("⏱ [V4桥接] 保持强度→APP: clientId=" + clientId + " A=" + a + " B=" + b + " 设备=" + slots.size() + "台");
            } catch (Exception ex) {
                addLog("⚠ [V4桥接] 保持强度转发失败: " + ex.getMessage());
            }
        }
    }

    /** 停止所有V4 APP输出: 清全部任务 + 强度归零 (对应插件 stop 指令) */
    @SuppressWarnings("unchecked")
    public void deliverV4Stop() {
        if (v4AppConns.isEmpty()) return;
        for (Map.Entry<WebSocket, String> e : v4AppConns.entrySet()) {
            WebSocket conn = e.getKey();
            String clientId = e.getValue();
            if (conn == null || !conn.isOpen()) continue;
            try {
                sendV4Rpc(conn, "device.op.clear", new JSONObject()); // 清全部任务
                List<String> slots = v4SlotsByAppId.get(clientId);
                if (slots != null) {
                    for (String slot : slots) {
                        JSONObject zeroA = new JSONObject();
                        zeroA.put("s", slot); zeroA.put("c", 0); zeroA.put("t", 7); zeroA.put("v", 0); zeroA.put("im", true);
                        sendV4Rpc(conn, "device.op", zeroA);
                        JSONObject zeroB = new JSONObject();
                        zeroB.put("s", slot); zeroB.put("c", 1); zeroB.put("t", 7); zeroB.put("v", 0); zeroB.put("im", true);
                        sendV4Rpc(conn, "device.op", zeroB);
                    }
                }
            } catch (Exception ignored) {}
        }
        addLog("🛑 [V4桥接] 停止指令已下发所有V4 APP (任务清空+强度归零)");
    }

    /** V4断开清理 */
    public void v4Disconnect(WebSocket conn) {
        if (conn == null) return;
        String clientId = v4AppConns.remove(conn);
        if (clientId != null) {
            v4ConnByAppId.remove(clientId);
            v4SlotsByAppId.remove(clientId);
            // 停止该APP的持续输出会话
            ContSession sess = v4Continuous.remove(clientId);
            if (sess != null && sess.task != null) sess.task.cancel(false);
            addLog("➖ [V4桥接] APP离线: clientId=" + clientId);
        }
    }

    /** V4已配对APP的clientId列表 (HTTP状态页显示用) */
    public List<String> getV4AppIds() { return new ArrayList<>(v4AppConns.values()); }

    /** 是否有持续开火会话正在运行 (网页按钮状态同步用) */
    public boolean isContinuousActive() { return !v4Continuous.isEmpty(); }

    /** 发送V4 RPC请求: {type:'message', data:{t:'req', reqId, m, data}} (外层不带clientId, 与官方服务器转发格式一致) */
    @SuppressWarnings("unchecked")
    private void sendV4Rpc(WebSocket conn, String method, JSONObject data) {
        if (conn == null || !conn.isOpen()) return;
        JSONObject payload = new JSONObject();
        payload.put("t", "req");
        payload.put("reqId", String.valueOf(v4ReqCounter.incrementAndGet()));
        payload.put("m", method);
        if (data != null) payload.put("data", data);
        addLog("📤 [V4桥接] TX RPC: " + truncate(payload.toJSONString(), 160));
        sendV4MessageData(conn, payload);
    }

    /** 发送V4应用层消息帧: {type:'message', data:payload} */
    @SuppressWarnings("unchecked")
    private void sendV4MessageData(WebSocket conn, JSONObject payload) {
        if (conn == null || !conn.isOpen()) return;
        JSONObject frame = new JSONObject();
        frame.put("type", "message");
        frame.put("data", payload);
        sendString(conn, frame.toJSONString());
    }

    /** 发送V4服务端帧 (hello/controller_attached/heartbeat/pong/error) */
    @SuppressWarnings("unchecked")
    private void sendV4Frame(WebSocket conn, JSONObject frame) {
        if (conn == null || !conn.isOpen()) return;
        sendString(conn, frame.toJSONString());
    }

    /** 生成8位hex clientId (官方v4-server用4字节随机hex: CLIENT_ID_BYTES=4) */
    private static String randomHexId() {
        return String.format("%08x", (long) (Math.random() * 0xFFFFFFFFL));
    }

    /** 构建简单JSON对象: kv("k1","v1","k2","v2") */
    @SuppressWarnings("unchecked")
    private static JSONObject kv(Object... pairs) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) o.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return o;
    }

    /** 官方V3统一帧格式: {"type":T,"clientId":C,"targetId":Tg,"message":M} */
    @SuppressWarnings("unchecked")
    private void sendOfficialFrame(WebSocket conn, String clientId, String targetId, String type, String message) {
        if (conn == null || !conn.isOpen()) return;
        JSONObject f = new JSONObject();
        f.put("type", type);
        f.put("clientId", clientId == null ? "" : clientId);
        f.put("targetId", targetId == null ? "" : targetId);
        f.put("message", message == null ? "" : message);
        sendString(conn, f.toJSONString());
    }

    private static String framesToJson(List<String> frames) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(frames.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    private static int intValue(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        try { return o == null ? 0 : Integer.parseInt(String.valueOf(o).trim()); }
        catch (Exception e) { return 0; }
    }
}
