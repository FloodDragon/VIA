package com.stereo.via.ipc.client;

import com.stereo.via.ipc.Constants;
import com.stereo.via.ipc.Heartbeat;
import com.stereo.via.ipc.Packet;
import com.stereo.via.ipc.exc.IpcRuntimeException;
import com.stereo.via.ipc.util.Daemon;
import com.stereo.via.ipc.util.Time;
import com.stereo.via.service.AbstractService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Created by stereo on 17-1-18.
 */
public class HeartbeatReport extends AbstractService implements Runnable
{
    private static Logger LOG = LoggerFactory.getLogger(HeartbeatReport.class);
    private Daemon thread;
    private ClientProxy clientProxy;
    private final int heartBeatRate;
    private volatile boolean running;
    Heartbeat heartbeat;

    public HeartbeatReport(ClientProxy proxy) {
        super("HeartbeatReport");
        clientProxy = proxy;
        heartBeatRate = clientProxy.getConfig().getHeartBeatRate();
        heartbeat = new Heartbeat(proxy.getClientId());
    }

    @Override
    public void run()
    {
        try
        {
            while (running)
            {
                heatbeat();
                Thread.sleep(heartBeatRate);
            }
        }catch (InterruptedException ex)
        {
            LOG.info(getName() + " thread interrupted");
        }
    }

    @Override
    protected void serviceInit() throws Exception
    {
        LOG.info(getName() + " no init");
    }

    @Override
    protected void serviceStart() throws Exception
    {
        register();
        running = true;
        thread = new Daemon(this);
        thread.start();
    }

    @Override
    protected void serviceStop() throws Exception
    {
        running = false;
        thread.interrupt();
        unregister();
    }

    void register()
    {
        LOG.info(getName() + " register");
        heartbeat.now();
        reportHeartBeat(Constants.TYPE_HEARTBEAT_REQUEST_REGISTER);
    }

    void unregister()
    {
        LOG.info(getName() + " unregister");
        heartbeat.now();
        reportHeartBeat(Constants.TYPE_HEARTBEAT_REQUEST_UNREGISTER);
    }

    void heatbeat()
    {
        LOG.info(getName() + " heatbeat");
        heartbeat.now();
        reportHeartBeat(Constants.TYPE_HEARTBEAT);
    }

    void reportHeartBeat(byte type)
    {
        AsyncFuture<Packet> future = clientProxy.sendPacket(Packet.packetHeartBeat(heartbeat, type));
        try {
            heartbeat = future.get(clientProxy.getConfig().getReadTimeout(), TimeUnit.MILLISECONDS).getHeartbeat();
        } catch (Exception ex) {
            LOG.error(getName() + " reportHeartBeat ", ex);
            throw new IpcRuntimeException(getName() + " reportHeartBeat fail",ex);
        }
    }
}
