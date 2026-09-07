# DG-LAB WebSocket 中转服务（DGLab-Relay-Server）

> 轻量、零依赖的开源 WebSocket 中转服务，用于在 **DG-LAB 硬件控制端**（手机 App、浏览器面板、任意控制软件）与 **第三方应用/服务器** 之间桥接消息。
>
> 纯 Java 实现，单 jar 可运行，内置 HTTP 状态页 + 二维码面板。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-17%2B-orange.svg)](#运行要求)
[![Protocol](https://img.shields.io/badge/protocol-V2%2FV3%2FV4-green.svg)](#协议兼容)

## ✨ 特性

- **三种协议同时兼容**：自定义 JSON 协议（推荐）、官方 V3（旧版 DG-LAB APP）、官方 V4（DG-LAB 4 新版 APP），一台服务通吃所有客户端
- **控制端 / APP 端分离**：任意客户端连上发 `strength` 消息即可成为控制端，APP 端 `bind` 注册 clientId，支持广播与定向（`targetId`）两种下发模式
- **HTTP 状态页**：自动启动 Web 面板，显示实时连接数、事件日志、局域网/公网双二维码（支持 4 种格式，逐一尝试）
- **HTTP API**：除 WebSocket 外，还能用 HTTP GET/POST 直接触发 fire/hold/stop，方便 Node.js / Python / shell 等快速接入
- **自动公网探测**：启动时自动查询本机公网 IP，配 `--public-url` 支持 frp/ngrok/cloudflared 穿透
- **持续输出会话**：V4 APP 支持"应用强度 + 持续开火"——设定强度等级后后台自动续推波形帧，真正做到"持续刺激"而非定时脉冲
- **零配置启动**：`java -jar` 即可，端口默认 8080，HTTP 状态页默认 8081
- **波形模式库**：内置 hit / breath / heart / wave / steady / flicker 六种波形，可通过参数或 HTTP 选用

## 📦 快速开始

### 运行要求

- JDK 17 或更高（推荐 21 LTS）
- 任意操作系统（Windows / macOS / Linux），支持 IPv4 网络

### 下载 / 构建

```bash
# 从源码构建（推荐，无需预编译 jar）
git clone https://github.com/<your-user>/DGLab-Relay-Server.git
cd DGLab-Relay-Server
mvn clean package -DskipTests
# 产物: target/DGLab-Relay-Server.jar
```

或直接在 **Releases** 下载预编译 jar。

### 启动

```bash
# 最简（默认 8080 端口、启动 HTTP 状态页、自动探测公网 IP、自动打开浏览器）
java -jar DGLab-Relay-Server.jar

# 指定端口 + 跳过公网探测（局域网场景推荐，启动更快）
java -jar DGLab-Relay-Server.jar 9000 --no-public-ip

# 公网穿透场景（frp/ngrok/cloudflared）
java -jar DGLab-Relay-Server.jar --public-url wss://game.example.com:8843

# 服务器/容器后台运行
java -jar DGLab-Relay-Server.jar 8080 --no-open --no-http
```

启动后控制台会打印：
- 局域网 WebSocket 地址（`ws://192.168.x.x:8080`）
- 本机回环地址（`ws://127.0.0.1:8080`，同机部署用这个）
- 公网地址（若探测/指定成功）
- HTTP 状态页地址（`http://127.0.0.1:8081`）

### 手机 APP 连接

1. 打开 DG-LAB APP（V3 或 V4 版本均可）
2. 进入"远程控制"
3. 打开浏览器访问 HTTP 状态页（同一 WiFi 下用局域网地址）
4. 页面底部下拉切换 **QR 格式 A / B / C / D**，用 APP 扫码
5. 某个格式能扫上就成功——不同 APP 版本适配不同格式，这是官方协议差异导致的

---

## 🔌 协议兼容

本服务同时支持 **三种协议**，互不冲突：

| 协议 | APP 版本 | 连接方式 | 说明 |
|------|---------|---------|------|
| **自定义 JSON**（推荐） | 所有 | `ws://host:8080` | 任意客户端可用，消息格式简单、易扩展 |
| **官方 V3**（桥接） | DG-LAB APP 旧版 | `ws://host:8080/<UUID>` | 路径段携带 tid（36 位 UUID） |
| **官方 V4**（桥接） | DG-LAB 4 新版 | `ws://host:8080?tid=<8hex>` | 查询参数携带 tid（8 位十六进制） |

> HTTP 状态页会按这三种协议自动生成二维码，用户扫码即接入。

---

## 🧭 自定义 JSON 协议（第三方控制端必看）

连接本服务后发送 JSON 消息。所有消息均为 UTF-8 文本帧。

### 1. APP 端注册

```json
{"msgType":"bind", "clientId":"my-app-01", "device":"DG-LAB 4 Pro"}
```

服务端自动注册 clientId，回 ACK：

```json
{"msgType":"bind", "clientId":"my-app-01", "code":200, "message":"ok"}
```

> **可选**：不发 bind 也能连上，服务端会在 5 秒后自动分配 `APP-AUTO-<IP后两段>-<随机>` 形式的 clientId（兜底策略）。

### 2. 控制端触发开火

```json
{
  "msgType": "strength",
  "targetId": "my-app-01",
  "data": {
    "type": "fire",
    "chA": 15,
    "chB": 15,
    "time": 1000
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `msgType` | string | ✅ | 固定 `"strength"` |
| `targetId` | string | ❌ | 定向发送给某个 APP；**不填则广播给所有 APP**（最常用） |
| `data.type` | string | ✅ | `"fire"`（单次开火）、`"hold"`（持续保持强度）、`"stop"`（立即归零） |
| `data.chA` | number | ✅ | A 通道强度，范围 0–200 |
| `data.chB` | number | ✅ | B 通道强度，范围 0–200 |
| `data.time` | number | ❌ | 单次开火时长（毫秒），`fire` 时建议传 500–30000 |

服务端回 ACK：

```json
{"msgType":"strength-ack", "delivered":1, "totalApps":3}
```

- `delivered` = 本次成功送达的 APP 数量（自定义协议）
- `totalApps` = 当前在线 APP 总数（含官方 V3/V4 桥接）

### 3. 心跳

客户端可定期发心跳保持连接：

```json
{"msgType":"heartbeat"}
```

服务端会回：

```json
{"msgType":"heartbeat", "timestamp": 1735000000000}
```

### 4. 查询客户端列表

```json
{"msgType":"getClientList"}
```

服务端回：

```json
{"msgType":"getClientList", "clientIds": ["app-01", "app-02"], "count": 2}
```

### 5. APP 主动断开

```json
{"msgType":"break"}
```

---

## 🌐 HTTP API（可选，无 WebSocket 客户端也能用）

HTTP 状态页默认在 `wsPort + 1` 端口启动（WS 8080 → HTTP 8081）。所有 HTTP API 返回 JSON。

### `GET /api/status` — 实时状态

```json
{
  "apps": 2,
  "controllers": 1,
  "apps-v4": 1,
  "apps-official": 0,
  "fireCount": 42,
  "logs": ["[12:00:01] ⚡ 指令下发: type=fire chA=15 chB=15 time=1000ms  → 送达2台APP", "..."]
}
```

### `GET /api/fire?chA=15&chB=15&time=1000&pattern=hit` — 触发开火

| 参数 | 默认 | 说明 |
|------|------|------|
| `chA` / `chB` | 15 | 通道强度 0–200 |
| `time` | 1000 | 单次开火时长（毫秒） |
| `targetId` | 空（广播） | 指定 clientId 定向触发 |
| `pattern` | `hit` | 波形模式：hit / breath / heart / wave / steady / flicker |

**curl 示例：**

```bash
# 广播
curl "http://127.0.0.1:8081/api/fire?chA=15&chB=15&time=1000"

# 指定 clientId + 波形
curl "http://127.0.0.1:8081/api/fire?chA=20&chB=10&time=2000&targetId=my-app-01&pattern=heart"
```

### `GET /api/set?chA=15&chB=15&pattern=steady` — 应用强度（网页面板"✅应用强度"按钮对应的后端）

- 对 V4 APP：写入预设强度（仅"应用"不触发刺激）；若正在持续开火则实时改幅度
- 对 V3 / 自定义 APP：下发 `hold` 指令（A/B 通道设为指定强度并保持）

### `GET /api/stop` — 停止所有输出

清除所有波形、通道强度归零。

---

## 💻 第三方接入示例

### Node.js（ws 库）

```javascript
const WebSocket = require('ws');
const ws = new WebSocket('ws://127.0.0.1:8080');

ws.on('open', () => {
  // 单次开火
  ws.send(JSON.stringify({
    msgType: 'strength',
    data: { type: 'fire', chA: 15, chB: 15, time: 1000 }
  }));
});
```

### Python（websockets）

```python
import asyncio, json, websockets

async def fire():
    async with websockets.connect('ws://127.0.0.1:8080') as ws:
        await ws.send(json.dumps({
            "msgType": "strength",
            "data": {"type": "fire", "chA": 15, "chB": 15, "time": 1000}
        }))
        print(await ws.recv())

asyncio.run(fire())
```

### Shell（curl）

```bash
# 最简单：HTTP API 触发开火，不需要 WebSocket 客户端
curl "http://127.0.0.1:8081/api/fire?chA=15&chB=15&time=1000"
```

### Java（WebSocket 客户端）

```java
WebSocketClient client = new WebSocketClient(new URI("ws://127.0.0.1:8080")) {
    @Override public void onOpen(ServerHandshake sh) {
        JSONObject data = new JSONObject();
        data.put("type", "fire"); data.put("chA", 15); data.put("chB", 15); data.put("time", 1000);
        JSONObject msg = new JSONObject();
        msg.put("msgType", "strength"); msg.put("data", data);
        send(msg.toJSONString());
    }
    @Override public void onMessage(String s) { System.out.println(s); }
    @Override public void onClose(int i, String s, boolean b) {}
    @Override public void onError(Exception e) { e.printStackTrace(); }
};
client.connect();
```

---

## 🧰 启动参数

| 参数 | 简写 | 默认 | 说明 |
|------|------|------|------|
| `<port>` | — | `8080` | 直接写数字，指定 WebSocket 端口 |
| `--port` | `-p` | `8080` | 同 `<port>`，显式写法 |
| `--public-url` | `-pu` | 空 | **公网部署必填**，完整 ws:// 地址（`ws://domain.com:8080` / `wss://domain.com:8843` 均可） |
| `--no-public-ip` | `-np` | false | 跳过公网 IP 自动探测（局域网/内网场景推荐，启动更快） |
| `--no-open` | `-no` | false | 启动后不自动打开浏览器访问状态页 |
| `--no-http` | `-nh` | false | 不启动 HTTP 状态页（隐含 `--no-open`） |
| `--help` | `-h` | — | 打印帮助 |

> HTTP 状态页端口 = WebSocket 端口 + 1，固定规则，无单独参数可改。

### 端口 / 穿透典型组合

```bash
# 纯内网/局域网（推荐开发调试）
java -jar DGLab-Relay-Server.jar 9000 --no-public-ip

# FRP 穿透：FRP 把 8080→服务器 8080，公网域名是 game.abc.com
java -jar DGLab-Relay-Server.jar --public-url ws://game.abc.com:8080 --no-public-ip

# ngrok 穿透（ngrok http 8080 后复制生成的 https 地址）
java -jar DGLab-Relay-Server.jar --public-url ws://xxxx.ngrok.io --no-public-ip

# Cloudflare Tunnel（cloudflared tunnel --url http://localhost:8080）
java -jar DGLab-Relay-Server.jar --public-url wss://xxxx.cfargotunnel.com --no-public-ip
```

---

## 🏗️ 构建说明

- **JDK 17+**（推荐 21）
- **Maven 3.9+**

```bash
mvn clean package -DskipTests
# 产物: target/DGLab-Relay-Server.jar（含依赖，约 200KB）
```

依赖仅两个：
- `org.java-websocket:Java-WebSocket`（WebSocket 服务端/客户端）
- `com.googlecode.json-simple:json-simple`（JSON 解析）

---

## 📂 项目结构

```
src/main/java/com/dglab/relay/
├── DGLabRelayServer.java           # 主程序入口（端口解析、公网探测、启动顺序）
├── protocol/
│   └── MessageRouter.java           # 核心路由（连接管理、消息分发、波形库、V3/V4桥接）
└── server/
    ├── RelayWebSocketServer.java    # WebSocket 服务端（握手日志、自动绑定、官方协议 URL 解析）
    ├── HttpStatusServer.java        # HTTP 状态页 + HTTP API（status / fire / set / stop）
    └── OkHttpSafeDraft.java         # OkHttp 握手兼容修复（Connection: close 导致 APP 判握手失败）

src/main/resources/
├── qrcode.min.js                    # 状态页二维码生成库（CDN 可替换）
└── simplelogger.properties          # Java-WebSocket 日志配置
```

---

## ❓ 常见问题

**Q：手机扫码提示"请重新扫描二维码"？**
A：HTTP 状态页底部有 4 种二维码格式（A/B/C/D），逐个切换尝试——不同 APP 版本适配不同格式。根因是 V3 和 V4 的二维码 tid 格式不同（UUID vs 8 位 hex）。

**Q：手机连不上（WebSocket 连接超时）？**
A：
1. 手机和电脑在同一 WiFi，**不能是隔离访客网络**
2. 先在手机浏览器打开 HTTP 状态页（`http://电脑IP:8081`）——打不开就是网络/防火墙问题
3. Windows 防火墙可能拦截了 Java 入站流量；Linux 上 `ufw allow 8080/tcp`
4. 公网穿透场景必须配 `--public-url ws://你的公网域名:端口`，APP 扫码拿到的才是正确的公网地址

**Q：官方 DG-LAB 4 APP 连上但没刺激？**
A：V4 协议要求**持续推波形帧**（后台每秒 10 帧），单独 SetIntensity 只设强度等级不会自动产生刺激。本服务在 `fire` 指令中会自动做这件事；若用 HTTP 控制面板，点 ✅ 后还需要点"开火（持续）"才能持续输出。

**Q：同一台服务同时连 V3、V4、自定义三种协议的 APP 可以吗？**
A：可以，路由各自维护、互不干扰。一次 `fire` 指令会同步下发到三种协议下所有在线 APP。

**Q：怎么在外部程序里控制？**
A：最简单的是 HTTP API——`curl http://127.0.0.1:8081/api/fire?...` 就能触发，任何语言都能接。需要实时状态监听再用 WebSocket。

---

## 📄 许可证

MIT License，可自由使用、修改、分发。

## 🙏 致谢

- [dglab-websocket-server](https://github.com/DrGrandet/dglab-websocket-server) — 官方 V3 协议参考实现
- [dglab-kit](https://github.com/DrGrandet/dglab-kit) — 官方波形与 V4 协议规范
- [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) — WebSocket 库
