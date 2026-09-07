package com.dglab.relay.server;

import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.InvalidHandshakeException;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.HandshakeBuilder;
import org.java_websocket.handshake.ServerHandshakeBuilder;

/**
 * OkHttp兼容Draft (修复握手响应头)
 * <p>
 * 问题根因: DG-LAB 4 APP使用OkHttp发起WebSocket连接时, 请求会同时带两个Connection头:
 *   Connection: Upgrade
 *   Connection: close
 * Java-WebSocket将多个同名字段合并为 "Upgrade; close" (分号拼接),
 * 而Draft_6455.postProcessHandshakeResponseAsServer 会把请求的Connection值原样回写进101响应:
 *   Connection: Upgrade; close
 * OkHttp按RFC规范用逗号解析Connection头的token列表, "Upgrade; close"整体算一个token,
 * 匹配不上"upgrade", 判定握手无效 → 静默终止连接(phenotype: code=1006, 无关闭帧, APP反复重连)。
 * <p>
 * 修复: postProcess之后强制覆盖为单值 "Connection: Upgrade", 与bun官方v4-server行为一致。
 */
public class OkHttpSafeDraft extends Draft_6455 {

    @Override
    public HandshakeBuilder postProcessHandshakeResponseAsServer(ClientHandshake request, ServerHandshakeBuilder builder) throws InvalidHandshakeException {
        HandshakeBuilder b = super.postProcessHandshakeResponseAsServer(request, builder);
        // 强制输出干净的单值Connection头 (覆盖库回写的 "Upgrade; close")
        b.put("Connection", "Upgrade");
        return b;
    }

    @Override
    public Draft_6455 copyInstance() {
        // 库在握手时对所有draft调用copyInstance()创建副本, 而Draft_6455默认返回普通实例,
        // 会导致本类的postProcess修复丢失。必须返回本类副本。
        return new OkHttpSafeDraft();
    }
}