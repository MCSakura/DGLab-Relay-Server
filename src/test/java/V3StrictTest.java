import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;

/**
 * 模拟真实 DG-Lab APP 的官方 v3-server 行为:
 *  连接 ws://host:port/<tid>
 *  1. 等服务端帧1 {type:bind, message:targetId}  (分配appId)
 *  2. 等帧2 {type:bind, message:200} (配对确认, 官方 onOpen 直接连发, 不需要APP回bind)
 * 若 200 先于帧1到达 → FAIL (发送顺序异常)
 */
public class V3StrictTest {
    static String tid;
    static volatile boolean gotF1 = false;
    static volatile boolean got200 = false;
    static volatile boolean got200BeforeF1 = false;
    static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        tid = args[0];
        String url = "ws://127.0.0.1:8096/" + tid;
        WebSocketClient c = new WebSocketClient(new URI(url)) {
            @Override public void onOpen(ServerHandshake h) { log("connected"); }
            @Override public void onMessage(String m) {
                log("recv: " + m);
                if (m.contains("\"type\":\"bind\"") && m.contains("\"message\":\"targetId\"")) {
                    gotF1 = true;
                } else if (m.contains("\"type\":\"bind\"") && m.contains("\"message\":\"200\"")) {
                    if (!gotF1) { got200BeforeF1 = true; log("!! 200 arrived BEFORE frame1"); }
                    got200 = true;
                    new Thread(() -> { try { Thread.sleep(600); } catch (Exception ignored) {} close(1000, "done"); }).start();
                } else if (m.contains("\"type\":\"error\"")) {
                    log("PAIRING_REJECTED");
                    close(1000, "rejected");
                }
            }
            @Override public void onClose(int code, String reason, boolean remote) {
                log("closed code=" + code + " reason=" + reason);
                synchronized (lock) { lock.notify(); }
            }
            @Override public void onError(Exception e) { log("error: " + e); }
        };
        c.connectBlocking();
        synchronized (lock) { lock.wait(12000); }
        if (got200 && !got200BeforeF1) { System.out.println("TEST_RESULT=PASS"); System.exit(0); }
        System.out.println("TEST_RESULT=FAIL f1=" + gotF1 + " 200=" + got200 + " early200=" + got200BeforeF1);
        System.exit(1);
    }

    static void log(String s) { System.out.println("[APP] " + s); }
}
