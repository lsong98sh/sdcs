package com.qkinfotech.bizwax.sdcs.proxy.backend;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.protocol.RespDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages persistent TCP connections to backend SDCS server nodes.
 * Each backend connection has a dedicated virtual thread that processes
 * queued requests sequentially (Redis protocol requires sequential
 * request/response ordering on a single connection).
 * <p>
 * Multiple connections per backend address are maintained for concurrency.
 */
public class BackendConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(BackendConnectionPool.class);

    private final int minIdle;
    private final int maxTotal;
    private final int connectTimeoutMs = 3000;

    // addr → list of connections
    private final Map<String, CopyOnWriteArrayList<BackendConnection>> pool = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BackendConnectionPool(int minIdle, int maxTotal) {
        this.minIdle = minIdle;
        this.maxTotal = maxTotal;
    }

    public void start() {
        running.set(true);
        log.info("Backend connection pool initialized (minIdle={}, maxTotal={})", minIdle, maxTotal);
    }

    /**
     * Get a connection to the given backend address, round-robin among available connections.
     * Returns null if no connection can be established.
     */
    public synchronized BackendConnection getConnection(String addr) {
        CopyOnWriteArrayList<BackendConnection> connections = pool.get(addr);

        // Try existing connections first
        if (connections != null && !connections.isEmpty()) {
            for (BackendConnection conn : connections) {
                if (conn.isActive()) {
                    return conn;
                }
            }
            // All stale - remove them
            pool.remove(addr);
        }

        // Check capacity
        int currentCount = connections != null ? connections.size() : 0;
        if (currentCount >= maxTotal) {
            log.warn("Max connections ({}) reached for {}", maxTotal, addr);
            return null;
        }

        // Create new connection
        String[] parts = addr.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 6379;

        try {
            BackendConnection conn = new BackendConnection(host, port, connectTimeoutMs);
            pool.computeIfAbsent(addr, k -> new CopyOnWriteArrayList<>()).add(conn);
            log.debug("Created new connection to backend {} (pool size: {})", addr, pool.get(addr).size());
            return conn;
        } catch (IOException e) {
            log.error("Failed to connect to backend {}: {}", addr, e.getMessage());
            return null;
        }
    }

    /**
     * Remove all connections for a given backend address.
     */
    public void removeBackend(String addr) {
        List<BackendConnection> connections = pool.remove(addr);
        if (connections != null) {
            for (BackendConnection conn : connections) {
                conn.close();
            }
            log.info("Removed backend {} from connection pool", addr);
        }
    }

    public void stop() {
        log.info("Shutting down backend connection pool...");
        running.set(false);
        for (List<BackendConnection> connections : pool.values()) {
            for (BackendConnection conn : connections) {
                conn.close();
            }
        }
        pool.clear();
        log.info("Backend connection pool stopped.");
    }

    public record PoolStat(String addr, int connections, int activeConnections) {}

    /**
     * Returns snapshot stats of all backend connections.
     */
    public List<PoolStat> getPoolStats() {
        List<PoolStat> stats = new ArrayList<>();
        for (Map.Entry<String, CopyOnWriteArrayList<BackendConnection>> entry : pool.entrySet()) {
            String addr = entry.getKey();
            List<BackendConnection> conns = entry.getValue();
            int total = conns.size();
            int active = 0;
            for (BackendConnection c : conns) {
                if (c.isActive()) active++;
            }
            stats.add(new PoolStat(addr, total, active));
        }
        return stats;
    }

    /**
     * A persistent TCP connection to a single backend SDCS server node.
     * Processes queued requests sequentially on a dedicated virtual thread.
     */
    static class BackendConnection {
        private final SocketChannel channel;
        private final BlockingQueue<BackendRequest> requestQueue = new LinkedBlockingQueue<>();
        private final RespDecoder decoder = new RespDecoder();
        private final ByteBuffer readBuf = ByteBuffer.allocate(8192);
        private final AtomicBoolean active = new AtomicBoolean(true);

        BackendConnection(String host, int port, int timeoutMs) throws IOException {
            channel = SocketChannel.open();
            channel.socket().connect(new InetSocketAddress(host, port), timeoutMs);
            channel.configureBlocking(true);
            // Start processing thread
            Thread.ofVirtual()
                    .name("backend-io-" + host + ":" + port)
                    .start(this::processLoop);
        }

        /**
         * Send a request to the backend and return a CompletableFuture for the response.
         */
        CompletableFuture<RedisMessage> send(ByteBuffer requestBytes) {
            CompletableFuture<RedisMessage> future = new CompletableFuture<>();
            if (!active.get()) {
                future.completeExceptionally(new IOException("Backend connection is closed"));
                return future;
            }
            requestQueue.add(new BackendRequest(requestBytes, future));
            return future;
        }

        boolean isActive() {
            return active.get() && channel.isConnected() && !channel.socket().isClosed();
        }

        void close() {
            active.set(false);
            try {
                channel.close();
            } catch (IOException ignored) {}
            // Fail all pending requests
            BackendRequest req;
            while ((req = requestQueue.poll()) != null) {
                req.future.completeExceptionally(new IOException("Backend connection closed"));
            }
        }

        private void processLoop() {
            while (active.get()) {
                try {
                    BackendRequest req = requestQueue.poll(1, TimeUnit.SECONDS);
                    if (req == null) continue;
                    if (!active.get()) break;

                    // Write request bytes
                    req.requestBytes.flip();
                    while (req.requestBytes.hasRemaining()) {
                        channel.write(req.requestBytes);
                    }

                    // Read response (loop until complete message)
                    RedisMessage response = null;
                    while (response == null && active.get()) {
                        readBuf.clear();
                        int n = channel.read(readBuf);
                        if (n == -1) {
                            active.set(false);
                            req.future.completeExceptionally(
                                    new IOException("Backend connection closed by peer"));
                            return;
                        }
                        readBuf.flip();
                        response = decoder.decode(readBuf);
                        if (response == null) {
                            // Partial read - compact and continue
                            readBuf.compact();
                        }
                    }

                    if (response != null) {
                        req.future.complete(response);
                    }
                } catch (Exception e) {
                    log.error("Backend I/O error", e);
                    active.set(false);
                    // Fail remaining requests
                    BackendRequest r;
                    while ((r = requestQueue.poll()) != null) {
                        r.future.completeExceptionally(e);
                    }
                    break;
                }
            }
        }

        private record BackendRequest(ByteBuffer requestBytes, CompletableFuture<RedisMessage> future) {}
    }
}
