package com.dglab.relay.server;

import com.dglab.relay.DGLabRelayServer;
import com.dglab.relay.protocol.MessageRouter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.simple.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 内嵌 HTTP 状态页服务器
 * 功能：
 *  1. 访问 / 显示状态页（APP数、控制端数、最近日志、局域网/公网双二维码）
 *  2. 访问 /api/status 返回 JSON 状态
 *  3. 访问 /api/fire?chA=15&chB=15&time=1000 HTTP触发测试开火
 * <p>
 * 二维码使用 Google Chart API 在线生成，无额外依赖
 */
@SuppressWarnings("unchecked")
public class HttpStatusServer {

    private final int httpPort;
    private final int wsPort;
    private final MessageRouter router;
    private final List<String> lanIps;
    private final String publicWsUrl;     // 完整 ws:// 公网地址，可能为null
    private HttpServer server;

    /** 新构造函数：包含公网URL */
    public HttpStatusServer(int httpPort, int wsPort, MessageRouter router, List<String> lanIps, String publicWsUrl) {
        this.httpPort = httpPort;
        this.wsPort = wsPort;
        this.router = router;
        this.lanIps = lanIps;
        this.publicWsUrl = publicWsUrl;
    }

    /** 兼容旧调用（4参数） */
    public HttpStatusServer(int httpPort, int wsPort, MessageRouter router, List<String> lanIps) {
        this(httpPort, wsPort, router, lanIps, null);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(httpPort), 0);
        server.createContext("/", new RootHandler());
        server.createContext("/api/status", new ApiStatusHandler());
        server.createContext("/api/fire", new ApiFireHandler());
        server.createContext("/api/set", new ApiSetHandler());
        server.createContext("/api/stop", new ApiStopHandler());
        server.createContext("/static/qrcode.min.js", new StaticQrJsHandler());
        server.setExecutor(null); // default
        server.start();
    }

    private static final byte[] QRCODE_JS_BYTES;
    static {
        // Read once at class-init.  ClassLoader#getResourceAsStream reads from the
        // fat-JAR classpath root (where Maven copies src/main/resources -> /).
        try (InputStream in = HttpStatusServer.class.getClassLoader()
                .getResourceAsStream("qrcode.min.js")) {
            if (in == null) {
                throw new RuntimeException("[FATAL] Missing classpath resource: qrcode.min.js\n" +
                        "   Please put qrcode.min.js into src/main/resources and rebuild the JAR.");
            }
            ByteArrayOutputStream ba = new ByteArrayOutputStream(20000);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) ba.write(buf, 0, n);
            QRCODE_JS_BYTES = ba.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load embedded qrcode.min.js", e);
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    /** 为一个 ws 基础地址生成 4 种 DG-LAB 兼容 QR 格式内容
     *  v4Tid: V4协议控制端ID(8位hex)   v3Tid: V3协议控制端ID(UUID)
     *  注意两者必须不同: V3老协议APP会校验 targetId 的UUID格式, 不能用V4的8hex */
    private static QrFormats makeFormats(String baseWsUrl, String v4Tid, String v3Tid) {
        QrFormats f = new QrFormats();
        // ===== A(默认⭐): DG-LAB 4 APP 官方V4 Socket 二维码 =====
        // 格式来源: dglab-websocket-server README "V4 二维码":
        //   wsUrl = ws://host:port?tid=<控制方clientId>   (V4协议: tid 作为查询参数)
        //   QR    = https://dungeon-lab.cn/s/?v=1&action=socket&url=<urlencode(wsUrl)>
        String v4WsUrl = baseWsUrl + "?tid=" + v4Tid;
        f.a = "https://dungeon-lab.cn/s/?v=1&action=socket&url=" + urlEncodeUtf8(v4WsUrl);
        // ===== B: V4 纯 ws URL (部分APP版本可直接识别裸地址) =====
        f.b = v4WsUrl;
        // ===== C: V3 官方格式 (老版APP) =====
        //   wsUrl = ws://host:port/<控制端clientId>   (V3协议: targetId 作为URL路径段, 官方用UUID)
        //   QR    = https://www.dungeon-lab.com/app-download.php#DGLAB-SOCKET#<原文wsUrl>
        // 重要: V3的 #DGLAB-SOCKET# 片段 APP 直接按原文解析, 【不做URL解码】!
        //       实测 encodeURIComponent 编码版(%3A%2F%2F) 会导致APP无法识别(提示重扫),
        //       明文 ws://... 才能正常连接。
        String v3WsUrl = baseWsUrl + "/" + v3Tid;
        f.c = "https://www.dungeon-lab.com/app-download.php#DGLAB-SOCKET#" + v3WsUrl;
        // ===== D: dgLabRemote 自定义Scheme (携带官方V4二维码内容, 浏览器点击唤起APP) =====
        f.d = "dgLabRemote://?url=" + urlEncodeUtf8(f.a);
        return f;
    }

    private static String urlEncodeUtf8(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return s == null ? "" : s; }
    }
    private static final class QrFormats {
        String a, b, c, d; // 4 种格式的【二维码内容字符串】
    }

    /** 构造一个全空格式的 QrFormats（用于"自定义地址"卡：初始无内容，等用户在网页里生成） */
    private static QrFormats makeEmptyFormats() {
        QrFormats f = new QrFormats();
        f.a = f.b = f.c = f.d = "";
        return f;
    }

    /** 构造【带4种格式切换器】的单个QR卡片HTML
     *  NOTE: QR images are NOT fetched from Google Charts API any more
     *        (it was shut down globally in 2019, and blocked inside CN anyway).
     *        Instead we render the QR-code OFFLINE inside the browser using the
     *        embedded qrcode.min.js served from /static/qrcode.min.js via a
     *        <canvas> element inside .qr-img-wrap. */
    private String buildQrCard(String cardClass, String title, String subtitle,
                                QrFormats fmts, int qrSize, boolean isPublic) {
        return buildQrCard(cardClass, title, subtitle, fmts, qrSize, isPublic, "", "");
    }

    /** 扩展版：extraAttrs=附加到卡片div的data属性，extraHeaderHtml=标题下方追加HTML(自定义输入区) */
    private String buildQrCard(String cardClass, String title, String subtitle,
                                QrFormats fmts, int qrSize, boolean isPublic,
                                String extraAttrs, String extraHeaderHtml) {
        // 将 4 个格式内容存入 data 数组属性，前端JS直接用
        String jsonArr = toJsArray(fmts.a, fmts.b, fmts.c, fmts.d);
        String labelA = "⭐ V4官方";
        String labelB = "V4纯URL";
        String labelC = "V3官方";
        String labelD = "Scheme唤起";
        String descA = "DG-LAB 4 APP 官方V4格式: dungeon-lab.cn/s/?v=1&action=socket&url=... (默认, 首选)";
        String descB = "V4官方裸地址: ws://host:port?tid=控制方ID";
        String descC = "V3官方格式兜底: app-download.php#DGLAB-SOCKET#... 老版APP用";
        String descD = "自定义Scheme：浏览器点击唤起APP";
        String tipIfFail = isPublic
                ? "<div class='fmt-tip'>💡 默认就是 <b>⭐V4官方</b>(DG-LAB 4 APP官方推荐协议)；连不上再依次试 <b>V4纯URL→V3官方→Scheme</b></div>"
                : "<div class='fmt-tip'>💡 默认就是 <b>⭐V4官方</b> 格式 (DG-LAB 4 APP 官方推荐协议)；连不上再依次试 <b>V4纯URL→V3官方→Scheme</b></div>";

        return
            "<div class='qr-card " + cardClass + "' data-qr-formats='" + jsonArr + "' data-default='0'" +
            (extraAttrs.isEmpty() ? "" : " " + extraAttrs) + ">" +
            "  <div class='qr-title'>" + title + "</div>" +
            "  <div class='qr-sub'>" + subtitle + "</div>" +
            (extraHeaderHtml.isEmpty() ? "" : extraHeaderHtml) +
            // qrcodejs@1.0 REQUIRES a plain EMPTY DIV (container) as its mount target:
            //   <div id="qrcode"></div>   -->   new QRCode(div, {text,width,height,...})
            // The library itself appends a <canvas> (or <img>/<table> on legacy browsers)
            // inside the mount div.  Passing a pre-built <canvas> here causes the lib to
            // nest a canvas inside a canvas -> invalid HTML silently ignored by the DOM,
            // which is why our v1.2.1 showed nothing on the user's screen.
            "  <div class='qr-img-wrap'><div class='qr-target' style='width:" + qrSize + "px;height:" + qrSize + "px;max-width:100%;'></div></div>" +
            "  <div class='fmt-btns'>" +
            "    <button type='button' class='fmt-btn fmt-btn-active' data-idx='0' title='" + escapeHtml(descA) + "'>" + labelA + "</button>" +
            "    <button type='button' class='fmt-btn' data-idx='1' title='" + escapeHtml(descB) + "'>" + labelB + "</button>" +
            "    <button type='button' class='fmt-btn' data-idx='2' title='" + escapeHtml(descC) + "'>" + labelC + "</button>" +
            "    <button type='button' class='fmt-btn' data-idx='3' title='" + escapeHtml(descD) + "'>" + labelD + "</button>" +
            "  </div>" +
                        "  <div class='qr-desc' title='点击复制到剪贴板' onclick='var t=this.querySelector(&quot;.qr-desc-text&quot;);dglabCopy(t.innerText,t);'>" +
            "    <span style='font-size:10px;color:#888'>格式内容 (点击复制)：</span>" +
            "    <div class='qr-desc-text' style='font-family:Consolas,monospace;font-size:11px;word-break:break-all;line-height:1.3;margin-top:2px;max-height:50px;overflow:auto;padding:4px;background:#fff;border-radius:4px;border:1px solid #e5e7eb'>" + escapeHtml(fmts.a) + "</div>" +
            "  </div>" +
            tipIfFail +
            "</div>";
    }
    /** 4个字符串转成JS数组字面量 (转义引号和反斜杠) */
    private static String toJsArray(String a, String b, String c, String d) {
        StringBuilder sb = new StringBuilder("[");
        for (String s : new String[]{a, b, c, d}) {
            sb.append('"');
            sb.append(s.replace("\\", "\\\\").replace("\"", "\\\""));
            sb.append("\",");
        }
        sb.deleteCharAt(sb.length() - 1);
        sb.append("]");
        return sb.toString();
    }

    // =========================== HTTP 处理器 ===========================

    private class RootHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String wsUrlLocal = "ws://127.0.0.1:" + wsPort;
            String wsUrlLan = lanIps.isEmpty() ? wsUrlLocal : "ws://" + lanIps.get(0) + ":" + wsPort;

            // ========== 生成局域网 + 公网 (各4种格式, tid=桥接控制端ID) ==========
            String bridgeTid = router.getBridgeControllerId();
            String v3BridgeTid = router.getV3BridgeControllerId();
            QrFormats lanFmts = makeFormats(wsUrlLan, bridgeTid, v3BridgeTid);
            QrFormats pubFmts = publicWsUrl != null && !publicWsUrl.isEmpty() ? makeFormats(publicWsUrl, bridgeTid, v3BridgeTid) : null;

            // ========== 列表 ==========
            StringBuilder logHtml = new StringBuilder();
            List<String> logs = router.getEventLog();
            for (int i = logs.size() - 1; i >= 0; i--) {
                logHtml.append("<div class='log'>").append(escapeHtml(logs.get(i))).append("</div>\n");
            }
            if (logs.isEmpty()) logHtml.append("<div class='log' style='color:#888'>（暂无事件，等待客户端连接...）</div>");

            StringBuilder ipListHtml = new StringBuilder();
            for (String ip : lanIps) {
                ipListHtml.append("<li title='点击复制' onclick='dglabCopy(\"ws://").append(ip).append(":").append(wsPort).append("\",this)'>ws://").append(ip).append(":").append(wsPort).append("</li>\n");
            }
            if (lanIps.isEmpty()) {
                ipListHtml.append("<li style='color:#d66'>⚠ 未检测到局域网IP</li>");
            }

            StringBuilder appListHtml = new StringBuilder();
            List<String> apps = router.getAppClientIds();
            List<String> officialApps = router.getOfficialAppIds();
            List<String> v4Apps = router.getV4AppIds();
            if (apps.isEmpty() && officialApps.isEmpty() && v4Apps.isEmpty()) {
                appListHtml.append("<span style='color:#888'>暂无APP连接 → 请扫上方 ⭐V4官方 二维码 ✨</span>");
            } else {
                for (String id : v4Apps) {
                    appListHtml.append("<span class='chip' style='background:#eef2ff;color:#4338ca'>📱 ")
                            .append(escapeHtml(id)).append(" · V4官方已配对</span>\n");
                }
                for (String id : officialApps) {
                    appListHtml.append("<span class='chip' style='background:#ecfdf5;color:#047857'>📱 ")
                            .append(escapeHtml(id)).append(" · V3官方已配对</span>\n");
                }
                for (String id : apps) {
                    appListHtml.append("<span class='chip'>📱 ").append(escapeHtml(id)).append("</span>\n");
                }
            }

            // ========== 二维码区域：局域网卡片 + 公网卡片/指南 + 自定义地址卡 ==========
            String qrAreaHtml;
            String lanCard = buildQrCard("lan-card",
                    "<span class='emoji'>📶</span> 局域网连接 (同一WiFi)",
                    "手机连接相同WiFi，DG-LAB APP → 远程控制 → 扫这里",
                    lanFmts, 280, false);

            String rightCard;
            if (pubFmts == null) {
                // 无公网 → 右侧显示穿透指南（指向下方自定义地址卡）
                String pubEmpty =
                    "<div class='qr-card pub-card pub-empty'>" +
                    "  <div class='qr-title'><span class='emoji'>🌐</span> 公网连接</div>" +
                    "  <div class='qr-sub'>未配置公网地址 → 用下方 🧭 自定义地址 卡即可</div>" +
                    "  <div class='pub-empty-box'>" +
                    "    <div class='pub-empty-icon'>🔌</div>" +
                    "    <p><b>异地 / 4G / 5G 远程：</b></p>" +
                    "    <ol>" +
                    "      <li><b>路由器端口映射</b>：把本机 WS(" + wsPort + ") 和本页(" + httpPort + ")映射到公网后，在下方「🧭 自定义地址」填入 公网IP:端口 并生成二维码</li>" +
                    "      <li><b>内网穿透 frp / ngrok / cloudflared</b>：拿到穿透后的 域名:远程端口，在下方「🧭 自定义地址」填入并生成即可</li>" +
                    "      <li><b>仅家中使用</b>：忽略这里，用左侧局域网 ✅</li>" +
                    "    </ol>" +
                    "    <p style='margin-top:8px;font-size:11px;color:#9d174d'>✅ 全程网页操作，无需 --public-url、无需重启服务</p>" +
                    "  </div>" +
                    "</div>";
                rightCard = pubEmpty;
            } else {
                rightCard = buildQrCard("pub-card",
                        "<span class='emoji'>🌐</span> 公网连接 (异地/4G/5G)",
                        "frp/ngrok/端口映射后使用，随时随地连接",
                        pubFmts, 280, true);
            }

            // 自定义地址卡：公网/内网穿透的域名、IP、端口与本地不一致时，手动填写并生成
            String customExtraHeader =
                "<div class='custom-addr-form'>" +
                "  <div class='custom-addr-fields'>" +
                "    <select id='custScheme' title='协议'>" +
                "      <option value='ws' selected>ws://</option>" +
                "      <option value='wss'>wss://</option>" +
                "    </select>" +
                "    <input id='custHost' type='text' placeholder='穿透后的域名或IP，如 frp.abc.com / 1.2.3.4' spellcheck='false' autocomplete='off'/>" +
                "    <span style='color:#059669;font-weight:700'>:</span>" +
                "    <input id='custPort' type='text' inputmode='numeric' placeholder='远程端口，如 8843' spellcheck='false' style='width:130px'/>" +
                "    <button type='button' class='cust-gen-btn' onclick='genCustomQr()'>⚡ 生成二维码</button>" +
                "  </div>" +
                "  <div class='custom-addr-hint'>💡 穿透/映射后，手机实际连的是 <b>域名或IP:远程端口</b>（常与本地 " + wsPort + " 不同）。填好点「⚡ 生成二维码」，内容自动保存，页面每10秒刷新不丢失。</div>" +
                "</div>";
            String customCard = buildQrCard("custom-card",
                    "<span class='emoji'>🧭</span> 自定义地址（内网穿透 / 公网域名IP）",
                    "穿透后的域名、IP 或端口与本地不一致时，在此填写并生成二维码",
                    makeEmptyFormats(), 280, true,
                    "data-v4-tid='" + bridgeTid + "' data-v3-tid='" + v3BridgeTid + "'",
                    customExtraHeader);

            qrAreaHtml = "<div class='qr-grid'>" + lanCard + rightCard + customCard + "</div>";

            String html = "<!DOCTYPE html>\n" +
"<html lang='zh-CN'>\n<head>\n<meta charset='UTF-8'>\n" +
"<meta name='viewport' content='width=device-width,initial-scale=1'>\n" +
"<title>DG-LAB 郊狼 WebSocket 中转服务 状态页</title>\n" +
"<style>\n" +
"*{box-sizing:border-box;margin:0;padding:0}\n" +
"body{font-family:-apple-system,'Microsoft YaHei',sans-serif;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);min-height:100vh;padding:16px;color:#333}\n" +
".container{max-width:1100px;margin:0 auto;background:#fff;border-radius:16px;box-shadow:0 10px 40px rgba(0,0,0,.2);overflow:hidden}\n" +
".header{background:linear-gradient(90deg,#f093fb 0%,#f5576c 100%);color:#fff;padding:24px 32px}\n" +
".header h1{font-size:22px;margin-bottom:6px}.header p{opacity:.9;font-size:13px}\n" +
".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:14px;padding:20px 32px}\n" +
".card{background:#f8f9ff;border-radius:12px;padding:16px;border:1px solid #e6e8ff}\n" +
".card h3{color:#555;font-size:12px;margin-bottom:6px;text-transform:uppercase;letter-spacing:.5px}\n" +
".card .num{font-size:32px;font-weight:700;color:#4a5bcf}\n" +
".card .sub{font-size:11px;color:#888;margin-top:3px}\n" +
/* 二维码双列布局 */
".qr-area{padding:0 32px 20px}\n" +
".qr-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}\n" +
".qr-grid-single .qr-card.pub-empty{display:flex;align-items:stretch}\n" +
".qr-card{border-radius:14px;padding:18px;border:2px solid #eee;display:flex;flex-direction:column}\n" +
".qr-card .qr-title{font-size:17px;font-weight:700;margin-bottom:4px}\n" +
".qr-card .qr-title .emoji{margin-right:6px}\n" +
".qr-card .qr-sub{font-size:12px;color:#666;margin-bottom:12px}\n" +
".qr-card.lan-card{background:linear-gradient(135deg,#e0f2fe,#dbeafe);border-color:#93c5fd}\n" +
".qr-card.lan-card .qr-title{color:#1e40af}\n" +
".qr-card.pub-card{background:linear-gradient(135deg,#ffe4e6,#fce7f3);border-color:#f9a8d4}\n" +
".qr-card.pub-card .qr-title{color:#9d174d}\n" +
".qr-card.custom-card{grid-column:1/-1;background:linear-gradient(135deg,#ecfdf5,#d1fae5);border-color:#6ee7b7}\n" +
".qr-card.custom-card .qr-title{color:#065f46}\n" +
".custom-addr-form{display:flex;flex-direction:column;gap:6px;margin:2px 0 10px}\n" +
".custom-addr-fields{display:flex;gap:6px;flex-wrap:wrap;align-items:center}\n" +
".custom-addr-fields select,.custom-addr-fields input{padding:7px 10px;border:1px solid #a7f3d0;border-radius:8px;font-size:13px;outline:none;color:#333}\n" +
".custom-addr-fields select:focus,.custom-addr-fields input:focus{border-color:#10b981;box-shadow:0 0 0 2px rgba(16,185,129,.15)}\n" +
".custom-addr-fields input#custHost{flex:1;min-width:220px;font-family:Consolas,monospace}\n" +
".custom-addr-fields .cust-gen-btn{border:0;padding:8px 16px;background:linear-gradient(90deg,#059669,#10b981);color:#fff;border-radius:8px;font-size:13px;cursor:pointer;font-weight:600;white-space:nowrap}\n" +
".custom-addr-fields .cust-gen-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(16,185,129,.4)}\n" +
".custom-addr-hint{font-size:11px;color:#065f46;background:rgba(16,185,129,.08);border:1px dashed #6ee7b7;border-radius:6px;padding:5px 8px;line-height:1.5}\n" +
".qr-empty-hint{width:100%;min-height:220px;display:flex;align-items:center;justify-content:center;color:#9ca3af;font-size:13px;text-align:center;line-height:1.7;background:#f9fafb;border-radius:8px}\n" +
".qr-img-wrap{background:#fff;border-radius:10px;padding:10px;margin:0 auto;text-align:center;box-shadow:0 2px 8px rgba(0,0,0,.06);flex:1;display:flex;align-items:center;justify-content:center;margin-bottom:10px;width:100%;max-width:300px}\n" +
".qr-img-wrap img{max-width:100%;height:auto;display:block}\n" +
".qr-url{font-family:Consolas,monospace;font-size:12px;background:#fff;padding:7px 10px;border-radius:6px;border:1px solid #ddd;text-align:center;word-break:break-all;color:#444;cursor:pointer;transition:.15s}\n" +
".qr-url:hover{background:#fffbe6;border-color:#facc15}\n" +
".qr-url.pub{background:#fff0f3;border-color:#fda4af}\n" +
".qr-tip{margin-top:8px;font-size:11px;color:#be185d;background:rgba(236,72,153,.1);padding:5px 8px;border-radius:5px;text-align:center}\n" +
".pub-empty-box{background:#fff;border-radius:10px;padding:18px 18px 18px 32px;flex:1;font-size:13px;color:#333;line-height:1.8;border:1px dashed #f9a8d4}\n" +
".pub-empty-icon{font-size:48px;text-align:center;margin:4px 0 10px}\n" +
".pub-empty-box ol{margin-top:6px;padding-left:20px}\n" +
".pub-empty-box li{margin-bottom:6px}\n" +
".pub-empty-box code{background:#fff1f2;color:#be123c;padding:1px 5px;border-radius:3px;font-size:12px}\n" +
/* 下方其他面板 */
".panel{padding:0 32px 24px;display:grid;grid-template-columns:1fr 1fr;gap:20px}\n" +
".panel > div{background:#fafbff;border-radius:12px;padding:18px;border:1px solid #eee}\n" +
".panel h2{font-size:15px;margin-bottom:12px;color:#333;display:flex;align-items:center;gap:8px}\n" +
".panel h2::before{content:'';width:4px;height:14px;background:#5a6bde;border-radius:2px}\n" +
".ws-addrs{list-style:none;margin-top:4px}\n" +
".ws-addrs li{background:#fff;border:1px solid #e0e2ff;border-radius:6px;padding:7px 10px;margin-bottom:5px;font-family:Consolas,monospace;font-size:12px;color:#4a5bcf;cursor:pointer;transition:.2s}\n" +
".ws-addrs li:hover{background:#eef0ff;transform:translateX(2px)}\n" +
".chip{display:inline-block;background:#fff0f6;color:#d946ef;padding:3px 9px;border-radius:999px;font-size:11px;margin:2px}\n" +
".test-btn{display:inline-block;padding:8px 16px;background:linear-gradient(90deg,#f5576c,#f093fb);color:#fff;border:0;border-radius:8px;font-size:13px;cursor:pointer;margin-top:8px;text-decoration:none}\n" +
".test-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(245,87,108,.4)}\n" +
".log-box{max-height:280px;overflow-y:auto;background:#1e1e2e;color:#d4d4d8;border-radius:8px;padding:10px;font-family:Consolas,monospace;font-size:11px;line-height:1.65}\n" +
".log-box .log{padding:1px 0;border-bottom:1px dashed rgba(255,255,255,.05)}\n" +
"/* ======= 网页控制面板(等同DG-LAB APP遥控) ======= */\n" +
".ctrl-area{padding:0 32px 20px}\n" +
".ctrl-card{background:linear-gradient(135deg,#fef3c7,#fde68a);border:2px solid #fcd34d;border-radius:14px;padding:18px}\n" +
".ctrl-card h2{color:#92400e;font-size:16px}\n" +
".dev-status{background:#fff;border:1px solid #fcd34d;border-radius:8px;padding:8px 12px;font-size:12px;color:#78350f;margin-bottom:12px;line-height:1.6}\n" +
".ctrl-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-bottom:12px}\n" +
".ctrl-block{background:rgba(255,255,255,.7);border-radius:10px;padding:10px 14px}\n" +
".ctrl-label{font-size:13px;font-weight:600;color:#78350f;margin-bottom:8px}\n" +
".ctrl-label b{color:#dc2626;font-size:15px;margin-left:4px}\n" +
".slider{width:100%;height:8px;-webkit-appearance:none;appearance:none;border-radius:5px;outline:none;cursor:pointer}\n" +
".slider::-webkit-slider-thumb{-webkit-appearance:none;appearance:none;width:22px;height:22px;border-radius:50%;cursor:pointer;border:3px solid #fff;box-shadow:0 2px 6px rgba(0,0,0,.3)}\n" +
".slider::-moz-range-thumb{width:20px;height:20px;border-radius:50%;cursor:pointer;border:3px solid #fff;box-shadow:0 2px 6px rgba(0,0,0,.3)}\n" +
".slider-a{background:linear-gradient(90deg,#fee2e2,#ef4444)}\n" +
".slider-a::-webkit-slider-thumb{background:#dc2626}\n" +
".slider-a::-moz-range-thumb{background:#dc2626}\n" +
".slider-b{background:linear-gradient(90deg,#dbeafe,#3b82f6)}\n" +
".slider-b::-webkit-slider-thumb{background:#2563eb}\n" +
".slider-b::-moz-range-thumb{background:#2563eb}\n" +
".slider-t{background:linear-gradient(90deg,#fef9c3,#ca8a04)}\n" +
".slider-t::-webkit-slider-thumb{background:#ca8a04}\n" +
".slider-t::-moz-range-thumb{background:#ca8a04}\n" +
".ctrl-select{width:100%;padding:8px 10px;border:1px solid #fbbf24;border-radius:8px;font-size:13px;background:#fff;color:#78350f;outline:none;cursor:pointer}\n" +
".ctrl-btns{display:flex;gap:10px;flex-wrap:wrap}\n" +
".ctrl-btn{border:0;padding:11px 22px;border-radius:10px;font-size:14px;font-weight:700;cursor:pointer;color:#fff;transition:.15s}\n" +
".ctrl-btn:hover{transform:translateY(-2px);box-shadow:0 6px 16px rgba(0,0,0,.2)}\n" +
".ctrl-fire{background:linear-gradient(90deg,#f5576c,#f093fb);flex:1;min-width:120px}\n" +
".ctrl-apply{background:linear-gradient(90deg,#059669,#10b981)}\n" +
".ctrl-stop{background:linear-gradient(90deg,#374151,#6b7280)}\n" +
".ctrl-hint{margin-top:10px;font-size:11px;color:#92400e;background:rgba(255,255,255,.6);border:1px dashed #fcd34d;border-radius:6px;padding:6px 10px;line-height:1.6}\n" +
".cont-chk{display:flex;align-items:center;gap:6px;margin-top:10px;font-size:13px;font-weight:600;color:#92400e;cursor:pointer;user-select:none}\n" +
".cont-chk input{width:17px;height:17px;cursor:pointer;accent-color:#dc2626}\n" +
".ctrl-btn.firing{background:linear-gradient(90deg,#7f1d1d,#b91c1c);animation:pulseBtn 1s ease-in-out infinite;pointer-events:none}\n" +
"@keyframes pulseBtn{0%,100%{box-shadow:0 0 0 0 rgba(220,38,38,.6)}50%{box-shadow:0 0 0 10px rgba(220,38,38,0)}}\n" +
".footer{padding:10px 32px 18px;font-size:12px;color:#888;text-align:center}\n" +
/* ======= 新增：4种QR格式切换器 ======= */
".fmt-btns{display:flex;gap:5px;margin:4px 0 10px;flex-wrap:wrap;justify-content:center}\n" +
".fmt-btn{border:1px solid #d1d5db;background:#fff;color:#4b5563;font-size:11px;padding:5px 8px;border-radius:6px;cursor:pointer;white-space:nowrap;transition:.15s;font-weight:600}\n" +
".fmt-btn:hover{background:#f3f4f6;border-color:#9ca3af;color:#111827}\n" +
".fmt-btn-active{background:linear-gradient(90deg,#4f46e5,#7c3aed);color:#fff;border-color:transparent;box-shadow:0 2px 6px rgba(79,70,229,.35)}\n" +
".qr-card.pub-card .fmt-btn-active{background:linear-gradient(90deg,#db2777,#f43f5e);box-shadow:0 2px 6px rgba(219,39,119,.35)}\n" +
".qr-desc{margin-top:4px;cursor:pointer;user-select:none}\n" +
".fmt-tip{margin-top:10px;font-size:11px;color:#475569;background:#f1f5f9;padding:6px 8px;border-radius:6px;text-align:center;line-height:1.5;border:1px solid #e2e8f0}\n" +
".qr-card.pub-card .fmt-tip{background:#fff1f2;color:#be123c;border-color:#fecdd3}\n" +
"@media(max-width:780px){.qr-grid{grid-template-columns:1fr}.panel{grid-template-columns:1fr;padding:0 16px 20px}.grid{padding:14px}.header{padding:16px 20px}.qr-area{padding:0 16px 16px}.ctrl-area{padding:0 16px 16px}.ctrl-grid{grid-template-columns:1fr;gap:10px}.fmt-btn{font-size:10px;padding:4px 6px}}\n" +
"</style>\n</head>\n<body>\n" +
"<div class='container'>\n" +
"  <div class='header'>\n" +
"    <h1>🎮 DG-LAB 郊狼 WebSocket 中转服务 <span style='font-size:12px;opacity:.85;padding:2px 8px;background:rgba(255,255,255,.2);border-radius:999px'>v" + DGLabRelayServer.VERSION + "</span></h1>\n" +
"    <p>外部控制端（脚本/插件/面板） ←WebSocket→ 本服务 ←WebSocket/扫码→ 手机 DG-LAB APP</p>\n" +
"  </div>\n" +
"  <div class='grid'>\n" +
"    <div class='card'><h3>📱 APP在线</h3><div class='num' id='num-app'>" + router.getTotalAppCount() + "</div><div class='sub'>DG-LAB APP已连接数(实时)</div></div>\n" +
"    <div class='card'><h3>🎛 控制端在线</h3><div class='num' id='num-ctrl'>" + router.getControllerCount() + "</div><div class='sub'>MC插件 / API调用端(实时)</div></div>\n" +
"    <div class='card'><h3>⚡ 开火指令累计</h3><div class='num' id='num-fire'>" + router.getFireCount() + "</div><div class='sub'>启动后触发总次数</div></div>\n" +
"    <div class='card'><h3>🔌 端口 / 状态</h3><div class='num' style='font-size:22px;padding-top:5px'>WS " + wsPort + "<br>HTTP " + httpPort + "</div><div class='sub'>WS=APP和插件连，HTTP=本页面</div></div>\n" +
"  </div>\n" +
"\n" +
"  <div class='qr-area'>\n" + qrAreaHtml + "\n  </div>\n" +
"\n" +
"  <div class='ctrl-area'>\n" +
"    <div class='ctrl-card'>\n" +
"      <h2 style='margin-bottom:10px'>🎛 网页控制面板（等同 DG-LAB 4 APP 遥控）</h2>\n" +
"      <div id='dev-status' class='dev-status'>设备状态加载中...</div>\n" +
"      <div class='ctrl-grid'>\n" +
"        <div class='ctrl-block'>\n" +
"          <div class='ctrl-label'>🔴 通道A强度 <b id='valA'>15</b></div>\n" +
"          <input type='range' id='sldA' min='0' max='200' value='15' class='slider slider-a'>\n" +
"        </div>\n" +
"        <div class='ctrl-block'>\n" +
"          <div class='ctrl-label'>🔵 通道B强度 <b id='valB'>15</b></div>\n" +
"          <input type='range' id='sldB' min='0' max='200' value='15' class='slider slider-b'>\n" +
"        </div>\n" +
"        <div class='ctrl-block'>\n" +
"          <div class='ctrl-label'>🌊 波形模式</div>\n" +
"          <select id='pat' class='ctrl-select'>\n" +
"            <option value='hit'>💥 挤压(命中节奏)</option>\n" +
"            <option value='breath'>🫁 呼吸(渐强渐弱)</option>\n" +
"            <option value='heart'>💓 心跳(双跳)</option>\n" +
"            <option value='wave'>🌊 水波(频率起伏)</option>\n" +
"            <option value='steady'>➖ 持续(满幅不断)</option>\n" +
"            <option value='flicker'>⚡ 闪烁(快节奏)</option>\n" +
"          </select>\n" +
"        </div>\n" +
"        <div class='ctrl-block'>\n" +
"          <div class='ctrl-label'>⏱ 开火时长 <b id='valT'>1000</b> ms</div>\n" +
"          <input type='range' id='sldT' min='200' max='10000' step='100' value='1000' class='slider slider-t'>\n" +
"          <label class='cont-chk'><input type='checkbox' id='chkCont'> 🔁 持续开火（一直输出，直到点「停止开火」）</label>\n" +
"        </div>\n" +
"      </div>\n" +
"      <div class='ctrl-btns'>\n" +
"        <button type='button' class='ctrl-btn ctrl-fire' id='btnFire' onclick='ctrlFire()'>⚡ 开火</button>\n" +
"        <button type='button' class='ctrl-btn ctrl-apply' onclick='ctrlApply()'>✅ 应用强度</button>\n" +
"        <button type='button' class='ctrl-btn ctrl-stop' id='btnStop' onclick='ctrlStop()'>⏹ 停止开火</button>\n" +
"      </div>\n" +
"      <div class='ctrl-hint'>💡 拖<b>滑块只改数值不发送</b>；点 <b>✅应用强度</b> 才把强度/波形下发到手机（开火中则实时调整）；点 <b>⚡开火</b> 才开始输出；勾选 <b>🔁持续开火</b> 则一直输出，直到点 <b>⏹停止开火</b>。需先用 ⭐V4官方 二维码配对APP并连接郊狼硬件。</div>\n" +
"    </div>\n" +
"  </div>\n" +
"\n" +
"  <div class='panel'>\n" +
"    <div>\n" +
"      <h2>💻 所有本机连接地址</h2>\n" +
"      <ul class='ws-addrs'>" +
"         <li title='本机回环'>ws://127.0.0.1:" + wsPort + " <span style='color:#999;font-size:10px'>(MC同机)</span></li>\n" +
          ipListHtml.toString() +
          (publicWsUrl != null ? "<li style='color:#be185d;border-color:#fecdd3;background:#fff1f2' title='公网'>🌐 " + escapeHtml(publicWsUrl) + " <span style='color:#999;font-size:10px'>(公网)</span></li>\n" : "") +
"      </ul>\n" +
"      <h2 style='margin-top:18px'>📱 已连接APP设备列表</h2>\n" +
"      <div id='app-list'>" + appListHtml + "</div>\n" +
"      <h2 style='margin-top:18px'>🧪 硬件测试（直接触发）</h2>\n" +
"      <a class='test-btn' href='javascript:void(0)' onclick='dglabFire(15,15,1000)'>⚡ A=B=15 输出1秒</a>\n" +
"      <a class='test-btn' style='margin-left:4px;background:linear-gradient(90deg,#4facfe,#00f2fe)' href='javascript:void(0)' onclick='dglabFire(5,5,500)'>🪶 轻柔 A=B=5 (0.5秒)</a>\n" +
"      <a class='test-btn' style='margin-left:4px;background:linear-gradient(90deg,#fa709a,#fee140);color:#444' href='javascript:void(0)' onclick='dglabFire(1,1,200)'>👀 微测试 1级 0.2秒</a>\n" +
"    </div>\n" +
"    <div>\n" +
"      <h2>📜 实时事件日志 <span style='font-size:11px;color:#10b981;font-weight:400'>● 实时更新(无需刷新)</span></h2>\n" +
"      <div class='log-box' id='log-box'>" + logHtml + "</div>\n" +
"      <p style='margin-top:6px;font-size:11px;color:#888'>数字/设备/日志每 2 秒 AJAX 局部刷新，不重载页面 &nbsp;|&nbsp; <a href='/api/status' target='_blank' style='color:#5a6bde'>JSON API</a></p>\n" +
"      <script src='/static/qrcode.min.js'></script>\n" +
"      <script>\n" +
"      // ===== Global copy helper with HTTP fallback =====\n" +
"      // navigator.clipboard only exists in secure contexts (HTTPS/localhost).\n" +
"      // This page is served over plain http://LAN-IP from phones, so we MUST\n" +
"      // fall back to execCommand('copy') + hidden textarea.\n" +
"      function dglabCopy(text, el){\n" +
"        function ok(){ if(!el)return; el.style.color='#047857'; el.title='\\u2713 \\u5df2\\u590d\\u5236'; setTimeout(function(){el.style.color='';el.title='\\u70b9\\u51fb\\u590d\\u5236';},1200); }\n" +
"        function fail(){ if(!el)return; el.style.color='#b91c1c'; el.title='\\u590d\\u5236\\u5931\\u8d25\\uff1a\\u8bf7\\u957f\\u6309\\u6587\\u672c\\u624b\\u52a8\\u590d\\u5236'; setTimeout(function(){el.style.color='';el.title='\\u70b9\\u51fb\\u590d\\u5236';},2500); }\n" +
"        try {\n" +
"          if(navigator.clipboard && navigator.clipboard.writeText){\n" +
"            navigator.clipboard.writeText(text).then(ok, function(){ if(legacyCopy(text))ok(); else fail(); });\n" +
"            return;\n" +
"          }\n" +
"        }catch(e){}\n" +
"        if(legacyCopy(text)) ok(); else fail();\n" +
"      }\n" +
"      function legacyCopy(text){\n" +
"        try {\n" +
"          var ta=document.createElement('textarea');\n" +
"          ta.value=text;\n" +
"          ta.setAttribute('readonly','');\n" +
"          ta.style.cssText='position:fixed;left:-9999px;top:0;opacity:0';\n" +
"          document.body.appendChild(ta);\n" +
"          // iOS Safari needs contentEditable + selection range to copy reliably\n" +
"          if(navigator.userAgent.match(/ipad|iphone/i)){\n" +
"            ta.contentEditable=true; ta.readOnly=true;\n" +
"            var range=document.createRange(); range.selectNodeContents(ta);\n" +
"            var sel=window.getSelection(); sel.removeAllRanges(); sel.addRange(range);\n" +
"            ta.setSelectionRange(0, text.length);\n" +
"          } else {\n" +
"            ta.focus(); ta.select();\n" +
"          }\n" +
"          var ok=document.execCommand('copy');\n" +
"          document.body.removeChild(ta);\n" +
"          return ok;\n" +
"        }catch(e){ return false; }\n" +
"      }\n" +
"      // ===== 硬件测试: AJAX触发 + 小窗提示 (不再打开新标签页) =====\n" +
"      function dglabFire(chA, chB, time){\n" +
"        try {\n" +
"          fetch('/api/fire?chA='+chA+'&chB='+chB+'&time='+time)\n" +
"            .then(function(r){ return r.json(); })\n" +
"            .then(function(d){\n" +
"              if(d && d.ok) dglabToast('⚡ 已下发: A='+d.chA+' B='+d.chB+' '+d.time+'ms · 送达 '+d.delivered+' 台 / APP '+d.totalApps+' 台', true);\n" +
"              else dglabToast('❌ 下发失败'+(d&&d.error?': '+d.error:''), false);\n" +
"            })\n" +
"            .catch(function(e){ dglabToast('❌ 请求失败: '+e.message, false); });\n" +
"        }catch(e){ dglabToast('❌ 触发异常: '+e.message, false); }\n" +
"      }\n" +
"      function dglabToast(text, ok){\n" +
"        var t = document.getElementById('dglabToast');\n" +
"        if(!t){\n" +
"          t = document.createElement('div');\n" +
"          t.id = 'dglabToast';\n" +
"          t.style.cssText = 'position:fixed;top:20px;left:50%;transform:translateX(-50%);z-index:9999;padding:12px 22px;border-radius:10px;color:#fff;font-size:14px;font-weight:600;box-shadow:0 6px 20px rgba(0,0,0,.35);transition:opacity .35s;pointer-events:none;max-width:92vw;word-break:break-all';\n" +
"          document.body.appendChild(t);\n" +
"        }\n" +
"        t.style.background = ok ? 'linear-gradient(90deg,#059669,#10b981)' : 'linear-gradient(90deg,#dc2626,#ef4444)';\n" +
"        t.textContent = text;\n" +
"        t.style.opacity = '1';\n" +
"        clearTimeout(t._tm);\n" +
"        t._tm = setTimeout(function(){ t.style.opacity='0'; }, 3000);\n" +
"      }\n" +
"      // ===== OFFLINE QR rendering (embedded qrcodejs library by davidshimjs) =====\n" +
"      // Google Chart API was globally shut down in 2019 + blocked in CN.\n" +
"      // MOUNT POINT: each .qr-card has a <div class='qr-target'>(empty DIV container)</div>\n" +
"      // inside .qr-img-wrap.  qrcodejs v1 REQUIRES a plain DIV here - it creates\n" +
"      // its own <canvas>/<img>/<table> inside the mount div as children.\n" +
"      // Passing a pre-existing <canvas> into the ctor would nest canvas>canvas\n" +
"      // (invalid HTML5) and the DOM silently drops the child so nothing renders.\n" +
"      (function(){\n" +
"        var QR_DEFSIZE=280;\n" +
"        function drawQrOnCard(card,idx){\n" +
"          try {\n" +
"            var formats=JSON.parse(card.getAttribute('data-qr-formats'));\n" +
"            if(isNaN(idx)||idx<0)idx=0;\n" +
"            var content=formats[idx]||'';\n" +
"            var mount=card.querySelector('div.qr-target');\n" +
"            if(!mount)return;\n" +
"            // Fully destroy prior render.  qrcodejs appends nodes inside mount.\n" +
"            // Setting innerHTML='' cleans both canvas/img/table AND releases any\n" +
"            // references we held.  Safer than .clear() on stale instances.\n" +
"            mount.innerHTML='';\n" +
"            if(!content){\n" +
"              // 空内容: 自定义地址卡尚未生成 -> 显示占位提示\n" +
"              var hint=document.createElement('div');\n" +
"              hint.className='qr-empty-hint';\n" +
"              hint.innerText='⬆ 填写上方 域名/IP:端口 后点击「⚡ 生成二维码」';\n" +
"              mount.appendChild(hint);\n" +
"            } else {\n" +
"              var q=new QRCode(mount,{\n" +
"                text:content,\n" +
"                width:QR_DEFSIZE,\n" +
"                height:QR_DEFSIZE,\n" +
"                colorDark:'#000000',\n" +
"                colorLight:'#ffffff',\n" +
"                correctLevel:QRCode.CorrectLevel.M\n" +
"              });\n" +
"              mount._qrInst=q;\n" +
"            }\n" +
"            // Update text preview\n" +
"            var txt=card.querySelector('.qr-desc-text');\n" +
"            if(txt)txt.innerText=content;\n" +
"            // Update active button style\n" +
"            var btns=card.querySelectorAll('.fmt-btn');\n" +
"            btns.forEach(function(b){b.classList.remove('fmt-btn-active')});\n" +
"            var activeBtn=card.querySelector('.fmt-btn[data-idx=\"'+idx+'\"]');\n" +
"            if(activeBtn)activeBtn.classList.add('fmt-btn-active');\n" +
"          }catch(e){\n" +
"            console.error('QR render failed',e);\n" +
"            // Fallback: inject a red text block so the user can report exactly what failed instead of a white box.\n" +
"            try {\n" +
"              var bad=document.createElement('div');\n" +
"              bad.style.cssText='width:100%;height:280px;display:flex;align-items:center;justify-content:center;background:#fef2f2;color:#b91c1c;font-size:12px;line-height:1.5;padding:8px;text-align:center';\n" +
"              bad.innerText='二维码生成失败: '+String(e.message||e);\n" +
"              var mount=card.querySelector('div.qr-target');\n" +
"              if(mount){mount.innerHTML='';mount.appendChild(bad);}\n" +
"            }catch(_){}\n" +
"          }\n" +
"        }\n" +
"        // ===== 自定义地址(内网穿透/公网域名) 生成二维码 =====\n" +
"        // 手动填 域名/IP + 端口, 与 makeFormats(Java) 输出完全一致:\n" +
"        //   v4: base?tid=控制端   v3: base/控制端(UUID)\n" +
"        function genCustomQr(){\n" +
"          var card=document.querySelector('.qr-card.custom-card');\n" +
"          if(!card)return;\n" +
"          var scheme=(document.getElementById('custScheme').value||'ws').toLowerCase();\n" +
"          var host=(document.getElementById('custHost').value||'').trim();\n" +
"          var port=(document.getElementById('custPort').value||'').trim();\n" +
"          // 容错: 直接把完整 ws://host:port 粘到域名框\n" +
"          var p1=host.indexOf('://');\n" +
"          if(p1>0){ scheme=host.substring(0,p1).toLowerCase(); host=host.substring(p1+3).trim(); }\n" +
"          host=host.split('/')[0].trim();\n" +
"          // 容错: 域名框里直接带 :端口\n" +
"          var c=host.lastIndexOf(':');\n" +
"          if(c>0){ var tail=host.substring(c+1);\n" +
"            if(tail.length>0 && parseInt(tail,10)>0 && String(parseInt(tail,10))===tail){ if(!port)port=tail; host=host.substring(0,c).trim(); }\n" +
"          }\n" +
"          if(!host){ dglabToast('⚠ 请填写穿透后的域名或IP', false); return; }\n" +
"          if(host.indexOf(' ')>-1||host.indexOf('/')>-1||host.indexOf(':')>-1){ dglabToast('⚠ 域名或IP格式不正确', false); return; }\n" +
"          var pn=parseInt(port,10);\n" +
"          if(!port || String(pn)!==port || pn<1 || pn>65535){ dglabToast('⚠ 请填写有效端口(1-65535)，例如 8843', false); return; }\n" +
"          var v4Tid=card.getAttribute('data-v4-tid');\n" +
"          var v3Tid=card.getAttribute('data-v3-tid');\n" +
"          var base=scheme+'://'+host+':'+pn;\n" +
"          var v4Ws=base+'?tid='+v4Tid;\n" +
"          var fa='https://dungeon-lab.cn/s/?v=1&action=socket&url='+encodeURIComponent(v4Ws);\n" +
"          var fb=v4Ws;\n" +
"          var fc='https://www.dungeon-lab.com/app-download.php#DGLAB-SOCKET#'+base+'/'+v3Tid;\n" +
"          var fd='dgLabRemote://?url='+encodeURIComponent(fa);\n" +
"          card.setAttribute('data-qr-formats', JSON.stringify([fa,fb,fc,fd]));\n" +
"          drawQrOnCard(card,0);\n" +
"          try{ localStorage.setItem('dglabCustomAddr', JSON.stringify({scheme:scheme,host:host,port:port})); }catch(e){}\n" +
"          dglabToast('✅ 已生成: '+base+'  手机扫上方二维码', true);\n" +
"        }\n" +
"        // 页面每10秒自动刷新, 从localStorage恢复上次填的自定义地址\n" +
"        function restoreCustomCard(){\n" +
"          var card=document.querySelector('.qr-card.custom-card');\n" +
"          if(!card)return;\n" +
"          var saved=null;\n" +
"          try{ saved=JSON.parse(localStorage.getItem('dglabCustomAddr')||'null'); }catch(e){ saved=null; }\n" +
"          if(!saved||!saved.host)return;\n" +
"          var se=document.getElementById('custScheme'); if(se)se.value=saved.scheme||'ws';\n" +
"          var he=document.getElementById('custHost'); if(he)he.value=saved.host;\n" +
"          var pe=document.getElementById('custPort'); if(pe)pe.value=saved.port||'';\n" +
"          genCustomQr();\n" +
"        }\n" +
"        function bootAllCards(){\n" +
"          document.querySelectorAll('.qr-card[data-qr-formats]').forEach(function(card){\n" +
"            var def=parseInt(card.getAttribute('data-default'),10);\n" +
"            if(isNaN(def))def=1;\n" +
"            drawQrOnCard(card,def);\n" +
"            card.querySelectorAll('.fmt-btn').forEach(function(btn){\n" +
"              btn.addEventListener('click',function(){\n" +
"                var idx=parseInt(this.getAttribute('data-idx'),10);\n" +
"                drawQrOnCard(card,idx);\n" +
"              });\n" +
"            });\n" +
"          });\n" +
"        }\n" +
"        // Defer boot until DOM+subresources (especially /static/qrcode.min.js) are parsed.\n" +
"        // DOMContentLoaded fires earlier but window.onload guarantees <script src=...>\n" +
"        // above has been fully evaluated -> QRCode global defined.\n" +
"        function bootPage(){\n" +
"          bootAllCards();\n" +
"          restoreCustomCard();\n" +
"        }\n" +
"        // onclick='genCustomQr()' 运行于全局作用域, 这里把 IIFE 内的函数导出到 window\n" +
"        window.genCustomQr = genCustomQr;\n" +
"        if(document.readyState==='complete') setTimeout(bootPage,0);\n" +
"        else window.addEventListener('load', bootPage);\n" +
"        // 实时状态改为 AJAX 局部刷新, 不再整页 reload (二维码/滑块状态不会被重置)\n" +
"      })();\n" +
"\n" +
"      // ================= 网页控制面板 (等同 DG-LAB 4 APP 遥控) + 实时状态轮询 =================\n" +
"      function dglabEsc(s){return String(s==null?'':s).replace(/[&<>\\\"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','\\\"':'&quot;'}[c];});}\n" +
"      function ctrlVals(){return {a:parseInt(document.getElementById('sldA').value,10)||0,b:parseInt(document.getElementById('sldB').value,10)||0,t:parseInt(document.getElementById('sldT').value,10)||1000,p:document.getElementById('pat').value,cont:document.getElementById('chkCont').checked};}\n" +
"      function setFiringUI(on){var bf=document.getElementById('btnFire');if(!bf)return;\n" +
"        if(on){bf.classList.add('firing');bf.textContent='🔴 持续开火中… 点右侧停止';}\n" +
"        else{bf.classList.remove('firing');bf.textContent='⚡ 开火';}}\n" +
"      function ctrlFire(){var v=ctrlVals();\n" +
"        var url='/api/fire?chA='+v.a+'&chB='+v.b+'&pattern='+encodeURIComponent(v.p);\n" +
"        if(v.cont){url+='&continuous=1';} else {url+='&time='+v.t;}\n" +
"        fetch(url).then(function(r){return r.json();}).then(function(d){\n" +
"          if(d&&d.ok){\n" +
"            if(d.delivered>0){\n" +
"              if(d.continuous){setFiringUI(true);dglabToast('🔴 持续开火开始: A='+d.chA+' B='+d.chB+' 波形='+d.pattern+'，点「⏹停止开火」结束', true);}\n" +
"              else dglabToast('⚡ 开火: A='+d.chA+' B='+d.chB+' '+d.time+'ms 波形='+d.pattern+' · 送达'+d.delivered+'台', true);\n" +
"            } else dglabToast('⚠ 指令已发但无APP在线接收(请先扫码配对并连硬件)', false);\n" +
"          }\n" +
"          else dglabToast('❌ 开火失败'+(d&&d.error?': '+d.error:' (无APP在线?)'), false);\n" +
"        }).catch(function(e){dglabToast('❌ 请求失败: '+e.message,false);});\n" +
"      }\n" +
"      function ctrlApply(){var v=ctrlVals();\n" +
"        fetch('/api/set?chA='+v.a+'&chB='+v.b+'&pattern='+encodeURIComponent(v.p))\n" +
"        .then(function(r){return r.json();}).then(function(d){\n" +
"          if(d&&d.ok){ if(d.delivered>0) dglabToast('✅ 强度已下发: A='+d.chA+' B='+d.chB+' 波形='+d.pattern+' · 送达'+d.delivered+'台/共'+d.totalApps+'台', true); else dglabToast('⚠ 指令已发但无APP在线接收(请先扫码配对)', false); }\n" +
"          else dglabToast('❌ 设置失败 (无APP在线?)', false);\n" +
"        }).catch(function(e){dglabToast('❌ 请求失败: '+e.message,false);});\n" +
"      }\n" +
"      function ctrlStop(){setFiringUI(false);\n" +
"        fetch('/api/stop').then(function(r){return r.json();}).then(function(d){\n" +
"          if(d&&d.ok) dglabToast('⏹ 已停止开火并强度归零 · 送达'+d.delivered+'台/共'+d.totalApps+'台', true);\n" +
"          else dglabToast('❌ 停止失败', false);\n" +
"        }).catch(function(e){dglabToast('❌ 请求失败: '+e.message,false);});\n" +
"      }\n" +
"      // 滑块: 拖动只更新本地数值显示, 不发送; 点「应用强度」才下发。持续开火勾选时禁用时长滑块。\n" +
"      (function(){\n" +
"        var sldA=document.getElementById('sldA'),sldB=document.getElementById('sldB'),sldT=document.getElementById('sldT'),chk=document.getElementById('chkCont');\n" +
"        if(!sldA)return;\n" +
"        sldA.oninput=function(){document.getElementById('valA').textContent=sldA.value;};\n" +
"        sldB.oninput=function(){document.getElementById('valB').textContent=sldB.value;};\n" +
"        sldT.oninput=function(){document.getElementById('valT').textContent=sldT.value;};\n" +
"        function syncCont(){var on=chk.checked; sldT.disabled=on; sldT.style.opacity=on?0.4:1;}\n" +
"        if(chk) chk.onchange=syncCont;\n" +
"        syncCont();\n" +
"      })();\n" +
"      // ---- 实时状态轮询 (每2秒, 只更新数字/设备/日志 DOM, 不动二维码) ----\n" +
"      function refreshStatus(){\n" +
"        fetch('/api/status').then(function(r){return r.json();}).then(function(d){\n" +
"          var el;\n" +
"          // 同步持续开火按钮状态 (服务端会话可能由其他页面/重启导致状态变化)\n" +
"          if(typeof setFiringUI==='function'){var bf=document.getElementById('btnFire');if(bf){var onNow=bf.classList.contains('firing');if(!!d.continuousActive!==onNow)setFiringUI(!!d.continuousActive);}}\n" +
"          if(el=document.getElementById('num-app'))el.textContent=d.appCount;\n" +
"          if(el=document.getElementById('num-ctrl'))el.textContent=d.controllerCount;\n" +
"          if(el=document.getElementById('num-fire'))el.textContent=d.fireCount;\n" +
"          var list=document.getElementById('app-list');\n" +
"          if(list){var chips='';\n" +
"            (d.v4AppIds||[]).forEach(function(id){chips+=\"<span class='chip' style='background:#eef2ff;color:#4338ca'>📱 \"+dglabEsc(id)+\" · V4官方已配对</span>\";});\n" +
"            (d.officialAppIds||[]).forEach(function(id){chips+=\"<span class='chip' style='background:#ecfdf5;color:#047857'>📱 \"+dglabEsc(id)+\" · V3官方已配对</span>\";});\n" +
"            (d.appClientIds||[]).forEach(function(id){chips+=\"<span class='chip'>📱 \"+dglabEsc(id)+'</span>';});\n" +
"            list.innerHTML=chips||\"<span style='color:#888'>暂无APP连接 → 请扫上方 ⭐V4官方 二维码 ✨</span>\";\n" +
"          }\n" +
"          var ds=document.getElementById('dev-status');\n" +
"          if(ds){\n" +
"            var devs=d.v4Devices||{},keys=Object.keys(devs),html='';\n" +
"            if(keys.length){html=keys.map(function(id){var slots=devs[id]||[];return '📱 V4设备 <b>'+dglabEsc(id)+'</b> · 硬件槽 '+slots.length+' 个 '+(slots.length?'✅ 已连硬件':'⚠️ APP未连硬件');}).join('<br>');}\n" +
"            else if(d.appCount>0){html='✅ 已有 '+d.appCount+' 个APP连接，但V4硬件槽未同步。请用 <b>⭐V4官方</b> 二维码配对，并在APP中连接郊狼硬件。';}\n" +
"            else {html='<span style=\"color:#b45309\">暂无APP连接 → 请扫描 ⭐V4官方 二维码配对后即可在此遥控</span>';}\n" +
"            ds.innerHTML=html;\n" +
"          }\n" +
"          var lb=document.getElementById('log-box');\n" +
"          if(lb){var logs=d.logs||[],h='';for(var i=logs.length-1;i>=0;i--){h+=\"<div class='log'>\"+dglabEsc(logs[i])+'</div>';}lb.innerHTML=h||\"<div class='log' style='color:#888'>（暂无事件，等待客户端连接...）</div>\";}\n" +
"        }).catch(function(){});\n" +
"      }\n" +
"      setInterval(refreshStatus, 2000);\n" +
"      if(document.readyState!=='loading') refreshStatus(); else document.addEventListener('DOMContentLoaded',refreshStatus);\n" +
"      window.ctrlFire=ctrlFire; window.ctrlApply=ctrlApply; window.ctrlStop=ctrlStop;\n" +
"      </script>\n" +
"    </div>\n" +
"  </div>\n" +
"  <div class='footer'>DGLab Relay Server v" + DGLabRelayServer.VERSION + " · 兼容 dglab-websocket-server 协议</div>\n" +
"</div>\n</body></html>";

            byte[] out = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        }
    }

    private class ApiStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            JSONObject resp = new JSONObject();
            resp.put("version", DGLabRelayServer.VERSION);
            resp.put("wsPort", wsPort);
            resp.put("httpPort", httpPort);
            // APP总数(自定义+V3+V4), 网页数字卡片用这个
            resp.put("appCount", router.getTotalAppCount());
            resp.put("customAppCount", router.getAppCount());
            resp.put("controllerCount", router.getControllerCount());
            resp.put("fireCount", router.getFireCount());
            resp.put("appClientIds", router.getAppClientIds());
            // 官方V3桥接
            resp.put("officialAppCount", router.getOfficialAppCount());
            resp.put("officialAppIds", router.getOfficialAppIds());
            // 官方V4桥接
            resp.put("v4AppCount", router.getV4AppCount());
            resp.put("v4AppIds", router.getV4AppIds());
            resp.put("v4Devices", new JSONObject(router.getV4Devices()));
            resp.put("continuousActive", router.isContinuousActive());
            // 波形模式 + 实时事件日志 (网页局部刷新用, 不再整页reload)
            resp.put("waveforms", router.getWaveformNames());
            resp.put("logs", router.getEventLog());
            resp.put("bridgeControllerId", router.getBridgeControllerId());
            resp.put("lanIps", lanIps);
            // 公网相关
            resp.put("publicWsUrl", publicWsUrl == null ? "" : publicWsUrl);
            resp.put("hasPublic", publicWsUrl != null && !publicWsUrl.isEmpty());

            String wsUrlLan = lanIps.isEmpty() ? "ws://127.0.0.1:" + wsPort : "ws://" + lanIps.get(0) + ":" + wsPort;
            resp.put("wsUrlLan", wsUrlLan);
            byte[] out = resp.toJSONString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        }
    }

    private class ApiFireHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String query = ex.getRequestURI().getQuery();
            int chA = 15, chB = 15, time = 1000;
            String targetId = null, pattern = "hit";
            boolean continuous = false;
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length < 2) continue;
                    String k = kv[0], v = kv[1];
                    try {
                        if (k.equalsIgnoreCase("chA")) chA = clampInt(Integer.parseInt(v), 0, 200);
                        else if (k.equalsIgnoreCase("chB")) chB = clampInt(Integer.parseInt(v), 0, 200);
                        else if (k.equalsIgnoreCase("time")) time = clampInt(Integer.parseInt(v), 100, 30000);
                        else if (k.equalsIgnoreCase("targetId")) targetId = v;
                        else if (k.equalsIgnoreCase("pattern")) pattern = v;
                        else if (k.equalsIgnoreCase("continuous"))
                            continuous = v.equals("1") || v.equalsIgnoreCase("true");
                    } catch (NumberFormatException ignored) {}
                }
            }
            int delivered;
            if (continuous) {
                // 持续开火: 一直输出直到 /api/stop
                delivered = router.broadcastFireContinuous(chA, chB, pattern);
            } else {
                delivered = router.broadcastFire(chA, chB, time, targetId, pattern);
            }

            JSONObject resp = new JSONObject();
            resp.put("ok", true);
            // 顶层直接放 chA/chB/time/pattern, 前端 toast 直接读 (修复 a=undefined 问题)
            resp.put("chA", chA);
            resp.put("chB", chB);
            resp.put("time", time);
            resp.put("pattern", pattern);
            resp.put("continuous", continuous);
            resp.put("delivered", delivered);
            resp.put("totalApps", router.getTotalAppCount());
            byte[] out = resp.toJSONString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        }
    }

    /** /api/set?chA=15&chB=15  设定双通道强度(持续保持, 不归零) */
    private class ApiSetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String query = ex.getRequestURI().getQuery();
            int chA = 0, chB = 0;
            String pattern = null;
            if (query != null) {
                for (String pair : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv.length < 2) continue;
                    try {
                        if (kv[0].equalsIgnoreCase("chA")) chA = clampInt(Integer.parseInt(kv[1]), 0, 200);
                        else if (kv[0].equalsIgnoreCase("chB")) chB = clampInt(Integer.parseInt(kv[1]), 0, 200);
                        else if (kv[0].equalsIgnoreCase("pattern")) pattern = kv[1];
                    } catch (NumberFormatException ignored) {}
                }
            }
            int delivered = router.broadcastSetIntensity(chA, chB, pattern);
            JSONObject resp = new JSONObject();
            resp.put("ok", true);
            resp.put("chA", chA);
            resp.put("chB", chB);
            resp.put("pattern", pattern == null ? "steady" : pattern);
            resp.put("delivered", delivered);
            resp.put("totalApps", router.getTotalAppCount());
            byte[] out = resp.toJSONString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        }
    }

    /** /api/stop  停止所有输出(清波形 + 强度归零) */
    private class ApiStopHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            int delivered = router.broadcastStopAll();
            JSONObject resp = new JSONObject();
            resp.put("ok", true);
            resp.put("delivered", delivered);
            resp.put("totalApps", router.getTotalAppCount());
            byte[] out = resp.toJSONString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        }
    }

    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /**
     * Serves the embedded offline QR-code renderer (qrcode.min.js by davidshimjs, MIT).
     * This is a STATIC asset - once loaded at class-init time we blast it out with
     * far-future cache headers so the browser never re-requests it.  This route
     * replaces the dead Google Charts API dependency.
     */
    private static final class StaticQrJsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String method = ex.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = QRCODE_JS_BYTES;
            ex.getResponseHeaders().set("Content-Type", "application/javascript; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            ex.getResponseHeaders().set("Vary", "Accept-Encoding");
            // ETag based on size (fast and deterministic enough for a static asset)
            ex.getResponseHeaders().set("ETag", "\"" + Integer.toHexString(body.length) + "\"");
            long len = "HEAD".equalsIgnoreCase(method) ? 0 : body.length;
            ex.sendResponseHeaders(200, len);
            if (len > 0) {
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            }
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
