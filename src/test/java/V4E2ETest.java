import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;

/**
 * 模拟真实 DG-LAB 4 APP 的 V4 协议全流程 (严格按官方协议):
 *  1. 连接 ws://host:port/?tid=<tid> → 期待 {type:hello, clientId}
 *  2. 期待 {type:controller_attached, clientId:<tid>}
 *  3. 收到 controller_attached 后立即主动上报 devices.snapshot (官方APP行为)
 *  4. 触发 HTTP /api/fire → 期待 device.op 指令 (t:7设强度 / t:0波形)
 *  5. APP主动发起RPC ping → 期待 {t:'resp', result:<ts>}
 */
public class V4E2ETest {
    static String tid;
    static volatile boolean gotHello, gotAttached, gotSetIntensity, gotPulse, gotRpcPingResp;
    static volatile String myClientId;
    static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        tid = args[0];
        String wsBase = args.length > 1 ? args[1] : "ws://127.0.0.1:8096";
        String url = wsBase + "/?tid=" + tid;
        WebSocketClient c = new WebSocketClient(new URI(url)) {
            @Override public void onOpen(ServerHandshake h) { log("connected"); }
            @Override public void onMessage(String m) {
                log("recv: " + m);
                if (m.contains("\"type\":\"hello\"")) { gotHello = true; myClientId = ext(m, "clientId"); }
                else if (m.contains("\"type\":\"controller_attached\"")) {
                    gotAttached = true;
                    // 官方APP行为: 收到controller_attached后立即上报全量设备列表
                    send("{\"type\":\"message\",\"data\":{\"t\":\"ev\",\"ev\":\"devices.snapshot\",\"devices\":[{\"slotId\":\"slot-001\",\"name\":\"Coyote 3.0\",\"type\":\"coyote\"}]}}");
                    log("sent devices.snapshot (slot-001)");
                }
                else if (m.contains("\"type\":\"message\"")) {
                    // 服务端下发的RPC请求
                    if (m.contains("\"m\":\"devices.get\"")) {
                        String reqId = ext(m, "reqId");
                        // 回复设备列表 (模拟APP已连接1台郊狼设备)
                        send("{\"type\":\"message\",\"data\":{\"t\":\"resp\",\"reqId\":\"" + reqId
                                + "\",\"result\":{\"devices\":[{\"slotId\":\"slot-001\",\"name\":\"Coyote 3.0\",\"type\":\"coyote\"}]}}}");
                        log("sent devices resp (slot-001)");
                    } else if (m.contains("\"m\":\"device.op\"")) {
                        if (m.contains("\"t\":7")) gotSetIntensity = true;
                        if (m.contains("\"t\":0") && m.contains("\"v\":[")) gotPulse = true;
                        if (m.contains("\"s\":\"slot-001\"") && !gotSetIntensity) {
                            log("!! device.op 未携带正确slotId");
                        }
                    } else if (m.contains("\"t\":\"resp\"") && m.contains("\"reqId\":\"app-ping-1\"")) {
                        gotRpcPingResp = true;
                    }
                } else if (m.contains("\"type\":\"error\"")) {
                    log("ERROR_FRAME received");
                }
            }
            @Override public void onClose(int code, String reason, boolean remote) {
                log("closed code=" + code + " reason=" + reason);
                synchronized (lock) { lock.notify(); }
            }
            @Override public void onError(Exception e) { log("error: " + e); }
        };
        c.connectBlocking();
        // 等待配对完成
        long t0 = System.currentTimeMillis();
        while (!(gotHello && gotAttached) && System.currentTimeMillis() - t0 < 8000) Thread.sleep(100);
        if (!(gotHello && gotAttached)) {
            System.out.println("TEST_RESULT=FAIL handshake hello=" + gotHello + " attached=" + gotAttached);
            System.exit(1);
        }
        // APP主动发起RPC ping (模拟APP探测控制方)
        Thread.sleep(200);
        c.send("{\"type\":\"message\",\"data\":{\"t\":\"req\",\"reqId\":\"app-ping-1\",\"m\":\"ping\"}}");
        // 触发HTTP fire
        Thread.sleep(300);
        new java.net.URL("http://127.0.0.1:8097/api/fire?chA=15&chB=15&time=1000").openStream().close();
        log("http fire triggered");
        // 等待device.op指令+RPC ping响应
        t0 = System.currentTimeMillis();
        while (!(gotSetIntensity && gotPulse && gotRpcPingResp) && System.currentTimeMillis() - t0 < 8000) Thread.sleep(100);
        Thread.sleep(300);
        c.close(1000, "done");
        boolean pass = gotSetIntensity && gotPulse && gotRpcPingResp;
        System.out.println("TEST_RESULT=" + (pass ? "PASS" : "FAIL")
                + " intensity=" + gotSetIntensity + " pulse=" + gotPulse + " rpcPingResp=" + gotRpcPingResp);
        System.exit(pass ? 0 : 1);
    }

    static String ext(String json, String key) {
        String pat = "\"" + key + "\":";
        int i = json.indexOf(pat);
        if (i < 0) return "";
        int s = i + pat.length();
        if (json.charAt(s) == '"') { int e = json.indexOf("\"", s + 1); return json.substring(s + 1, e); }
        int e = s; while (e < json.length() && ",}".indexOf(json.charAt(e)) < 0) e++;
        return json.substring(s, e);
    }

    static void log(String s) { System.out.println("[APP] " + s); }
}
