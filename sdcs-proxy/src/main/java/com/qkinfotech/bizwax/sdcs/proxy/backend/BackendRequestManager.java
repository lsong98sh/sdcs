package com.qkinfotech.bizwax.sdcs.proxy.backend;

import com.qkinfotech.bizwax.sdcs.proxy.backend.BackendConnectionPool.BackendConnection;
import com.qkinfotech.bizwax.sdcs.proxy.server.ClientWriter;
import com.qkinfotech.bizwax.sdcs.proxy.server.RespCodec;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.protocol.RespEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class BackendRequestManager {

    private static final Logger log = LoggerFactory.getLogger(BackendRequestManager.class);
    private static final long REQUEST_TIMEOUT_MS = 5000;

    /**
     * Forward a write request to multiple backends and wait for quorum.
     */
    public static void forwardWrite(ClientWriter writer, String command,
                                     List<String> args, List<String> targets, BackendConnectionPool pool) {
        int quorum = Math.max(1, (targets.size() / 2) + 1);
        List<CompletableFuture<RedisMessage>> futures = new ArrayList<>();

        // Encode the command once
        ByteBuffer requestBytes = encodeCommand(command, args);
        if (requestBytes == null) {
            writer.write(RespCodec.error("ERR internal error: failed to encode command"));
            return;
        }

        // Record the actual encoded byte count before it's modified
        int encodedSize = requestBytes.position();
        byte[] encodedData = requestBytes.array();

        for (String target : targets) {
            BackendConnection conn = pool.getConnection(target);
            if (conn == null || !conn.isActive()) {
                log.debug("Backend {} unavailable", target);
                continue;
            }
            // Each backend gets its own ByteBuffer with exactly the encoded bytes
            ByteBuffer buf = ByteBuffer.allocate(encodedSize);
            buf.put(encodedData, 0, encodedSize);
            futures.add(conn.send(buf));
        }

        if (futures.isEmpty()) {
            writer.write(RespCodec.error("ERR no backend available"));
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                List<RedisMessage> results = new ArrayList<>();
                long deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS;

                for (CompletableFuture<RedisMessage> f : futures) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    try {
                        RedisMessage result = f.get(remaining, TimeUnit.MILLISECONDS);
                        results.add(result);
                    } catch (Exception e) {
                        log.debug("Backend request timeout/failure: {}", e.getMessage());
                    }
                }

                if (results.size() >= quorum && !results.isEmpty()) {
                    writer.write(results.get(0));
                } else {
                    writer.write(RespCodec.error(
                            "ERR failed to get quorum (" + results.size() + "/" + quorum + ")"));
                }
            } catch (Exception e) {
                log.error("Error in write forwarding", e);
                writer.write(RespCodec.error("ERR internal error: " + e.getMessage()));
            }
        });
    }

    /**
     * Forward a read request to a single backend.
     */
    public static void forwardRead(ClientWriter writer, String command,
                                    List<String> args, String target, BackendConnectionPool pool) {
        BackendConnection conn = pool.getConnection(target);
        if (conn == null || !conn.isActive()) {
            writer.write(RespCodec.error("ERR cannot connect to backend " + target));
            return;
        }

        ByteBuffer requestBytes = encodeCommand(command, args);
        if (requestBytes == null) {
            writer.write(RespCodec.error("ERR internal error: failed to encode command"));
            return;
        }

        CompletableFuture<RedisMessage> future = conn.send(requestBytes);

        Thread.startVirtualThread(() -> {
            try {
                RedisMessage result = future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                writer.write(result);
            } catch (Exception e) {
                writer.write(RespCodec.error("ERR read timeout/failure: " + e.getMessage()));
            }
        });
    }

    /**
     * Encode a Redis command as RESP byte buffer for sending to backend.
     */
    private static ByteBuffer encodeCommand(String command, List<String> args) {
        RedisMessage cmdMsg = RespCodec.encodeCommand(command, args);
        RespEncoder encoder = new RespEncoder(4096);
        ByteBuffer buf = ByteBuffer.allocate(4096);
        try {
            encoder.encode(cmdMsg, buf);
            return buf;
        } catch (Exception e) {
            log.error("Failed to encode command {}: {}", command, e.getMessage());
            return null;
        }
    }
}
