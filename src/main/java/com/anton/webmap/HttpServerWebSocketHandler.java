package com.anton.webmap;

import com.google.gson.Gson;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fake handler
 */
public class HttpServerWebSocketHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static List<Channel> channels = new CopyOnWriteArrayList<>();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory("/websocket", null, false);
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(msg);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            handshaker.handshake(ctx.channel(), msg);
        }
        channels.add(ctx.channel());
        ModWebMap.INSTANCE.sendStartMessageTo(ctx);
    }

    public static void sendTo(Channel channel, HashMap<String, Object> object) {
        channel.writeAndFlush(new TextWebSocketFrame(new Gson().toJson(object)));
    }

    public static void sendToAll(HashMap<String, Object> message) {
        HttpServerWebSocketHandler.channels.stream()
                .filter(Channel::isWritable)
                .forEach(channel -> sendTo(channel, message));
    }
}
