package com.qkinfotech.bizwax.sdcs.proxy.server;

import com.qkinfotech.bizwax.sdcs.proxy.backend.BackendConnectionPool;
import com.qkinfotech.bizwax.sdcs.proxy.config.ProxyConfig;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;

import java.util.ArrayList;
import java.util.List;

public class ProxyStatusHandler {

    private final ProxyConfig config;
    private final ProxyServer proxyServer;
    private final BackendConnectionPool pool;
    private final long startTime;

    public ProxyStatusHandler(ProxyConfig config, ProxyServer proxyServer, BackendConnectionPool pool) {
        this.config = config;
        this.proxyServer = proxyServer;
        this.pool = pool;
        this.startTime = System.currentTimeMillis();
    }

    public RedisMessage handle() {
        List<RedisMessage> fields = new ArrayList<>();

        fields.add(RedisMessage.bulkString("addr"));
        fields.add(RedisMessage.bulkString(config.getProxyAddr()));

        fields.add(RedisMessage.bulkString("uptime_secs"));
        fields.add(RedisMessage.bulkString(
                String.valueOf((System.currentTimeMillis() - startTime) / 1000)));

        fields.add(RedisMessage.bulkString("clients"));
        fields.add(RedisMessage.bulkString(
                String.valueOf(proxyServer.getClientCount())));

        fields.add(RedisMessage.bulkString("version"));
        fields.add(RedisMessage.bulkString("1.0.0"));

        // Backend pool stats
        List<BackendConnectionPool.PoolStat> stats = pool.getPoolStats();
        List<RedisMessage> backendEntries = new ArrayList<>();
        for (BackendConnectionPool.PoolStat stat : stats) {
            backendEntries.add(RedisMessage.array(
                    RedisMessage.bulkString(stat.addr()),
                    RedisMessage.bulkString(String.valueOf(stat.connections())),
                    RedisMessage.bulkString(String.valueOf(stat.activeConnections()))
            ));
        }
        fields.add(RedisMessage.bulkString("backends"));
        fields.add(RedisMessage.array(backendEntries));

        return RedisMessage.array(fields);
    }
}
