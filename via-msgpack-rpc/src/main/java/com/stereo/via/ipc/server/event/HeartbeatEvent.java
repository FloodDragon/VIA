package com.stereo.via.ipc.server.event;

import com.stereo.via.event.Event;
import com.stereo.via.ipc.Heartbeat;
import com.stereo.via.ipc.Packet;
import com.stereo.via.ipc.server.event.enums.HeartbeatEnum;
import com.stereo.via.ipc.util.Time;
import io.netty.channel.ChannelHandlerContext;

/**
 * Created by stereo on 16-8-25.
 */
public class HeartbeatEvent implements Event<HeartbeatEnum> {
    private long timestamp;
    private HeartbeatEnum type;
    private Packet packet;
    private ChannelHandlerContext channelHandlerContext;

    public HeartbeatEvent(HeartbeatEnum type, ChannelHandlerContext channelHandlerContext, Packet packet)
    {
        this(type,channelHandlerContext);
        this.packet = packet;
    }

    protected HeartbeatEvent(HeartbeatEnum type, ChannelHandlerContext channelHandlerContext)
    {
        this.type = type;
        this.channelHandlerContext = channelHandlerContext;
        this.timestamp = Time.now();
    }

    @Override
    public HeartbeatEnum getType() {
        return type;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }

    public Heartbeat getHeartbeat() {
        return packet.getHeartbeat();
    }

    public Packet getPacket() {
        return packet;
    }

    public ChannelHandlerContext getChannelHandlerContext() {
        return channelHandlerContext;
    }
}
