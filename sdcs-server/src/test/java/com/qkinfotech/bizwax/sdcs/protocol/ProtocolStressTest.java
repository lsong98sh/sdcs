package com.qkinfotech.bizwax.sdcs.protocol;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.qkinfotech.bizwax.sdcs.protocol.ProtocolTestBase.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 报文级压力测试 — 通过原生 Socket 发送 RESP 协议报文，
 * 覆盖 NIO 全链路：Acceptor → IOEventLoop.handleRead() → WorkProcessor → CommandDispatcher。
 * <p>
 * 每个线程复用同一个 Socket，避免耗尽 Windows 临时端口。
 * 保留 {@code StressTest} 作为直接调用 dispatcher 的补充测试。
 */
@Tag("stress")
class ProtocolStressTest extends ProtocolTestBase {

    /** 在已有 Socket 上发送 RESP 命令并解析响应 */
    private static RespResult sendOnSocket(Socket socket, String cmd, String... args) throws Exception {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(encodeCommand(cmd, args));
        out.flush();
        return parseResponse(in);
    }

    /** 在已有 Socket 上发送原始 RESP 字节并解析响应 */
    private static RespResult sendOnSocketRaw(Socket socket, byte[] raw) throws Exception {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(raw);
        out.flush();
        return parseResponse(in);
    }

    @Test
    void testMixedCommandStress() throws Exception {
        /*
         * 混合命令压力测试：
         *   4 线程 × 1000 ops = 4k 操作
         *   覆盖 SET/GET/INCR/HSET/HGET/LPUSH/SADD
         *   通过 NIO 全链路，验证高吞吐下的正确性
         */
        int threads = 4;
        int opsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicLong totalLatencyNanos = new AtomicLong();

        String[] commands = {"SET", "GET", "INCR", "HSET", "HGET", "LPUSH", "SADD"};

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try (Socket socket = new Socket("127.0.0.1", serverPort)) {
                    socket.setSoTimeout(TIMEOUT_MS);
                    startLatch.await();

                    for (int i = 0; i < opsPerThread; i++) {
                        String cmd = commands[i % commands.length];
                        long start = System.nanoTime();

                        try {
                            switch (cmd) {
                                case "SET" -> {
                                    RespResult r = sendOnSocket(socket, "SET", "pskey_" + threadId + "_" + i, "val_" + i);
                                    if (r.type == RespResult.Type.SIMPLE_STRING && "OK".equals(r.stringValue)) {
                                        success.incrementAndGet();
                                    } else {
                                        errors.incrementAndGet();
                                    }
                                }
                                case "GET" -> {
                                    RespResult r = sendOnSocket(socket, "GET", "pskey_" + threadId + "_" + (i % 100));
                                    if (r.type == RespResult.Type.BULK_STRING || r.type == RespResult.Type.NULL_BULK_STRING) {
                                        success.incrementAndGet();
                                    } else {
                                        errors.incrementAndGet();
                                    }
                                }
                                case "INCR" -> {
                                    RespResult r = sendOnSocket(socket, "INCR", "psincr_" + threadId);
                                    if (r.type == RespResult.Type.INTEGER) {
                                        success.incrementAndGet();
                                    } else {
                                        errors.incrementAndGet();
                                    }
                                }
                                case "HSET" -> {
                                    RespResult r = sendOnSocket(socket, "HSET", "pshash_" + threadId, "field_" + (i % 50), "hval_" + i);
                                    if (r.type == RespResult.Type.INTEGER) {
                                        success.incrementAndGet();
                                    } else {
                                        errors.incrementAndGet();
                                    }
                                }
                                case "HGET" -> {
                                    RespResult r = sendOnSocket(socket, "HGET", "pshash_" + threadId, "field_" + (i % 50));
                                    if (r.type == RespResult.Type.BULK_STRING || r.type == RespResult.Type.NULL_BULK_STRING) {
                                        success.incrementAndGet();
                                    } else {
                                        errors.incrementAndGet();
                                    }
                                }
                                case "LPUSH" -> {
                                    RespResult r = sendOnSocket(socket, "LPUSH", "pslist_" + threadId, "item_" + i);
                                    if (r.type == RespResult.Type.INTEGER) {
                                        success.incrementAndGet();
                                    } else {
                                        errors.incrementAndGet();
                                    }
                                }
                                case "SADD" -> {
                                    RespResult r = sendOnSocket(socket, "SADD", "psset_" + threadId, "mem_" + i);
                                    if (r.type == RespResult.Type.INTEGER) {
                                        success.incrementAndGet();
                                    } else {
                                        errors.incrementAndGet();
                                    }
                                }
                            }
                            totalLatencyNanos.addAndGet(System.nanoTime() - start);
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long wallStart = System.nanoTime();
        startLatch.countDown();
        boolean finished = doneLatch.await(60, TimeUnit.SECONDS);
        long elapsedNanos = System.nanoTime() - wallStart;

        assertTrue(finished, "Stress test should complete within 60s");
        assertEquals(0, errors.get(), "Zero protocol errors expected");

        double elapsedSec = elapsedNanos / 1_000_000_000.0;
        int totalSuccess = success.get();
        double qps = totalSuccess / elapsedSec;
        long avgLatUs = totalSuccess > 0 ? (totalLatencyNanos.get() / totalSuccess) / 1000 : 0;

        System.out.println("=== Mixed Command Stress Test ===");
        System.out.println("Threads: " + threads + ", Ops/thread: " + opsPerThread);
        System.out.println("Total ops: " + totalSuccess + " in " + String.format("%.2f", elapsedSec) + "s");
        System.out.println("QPS: " + String.format("%.0f", qps));
        System.out.println("Avg latency: " + avgLatUs + " us");
        System.out.println("Errors: " + errors.get());

        assertTrue(qps > 200, "QPS should exceed 200 for mixed commands");

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    @Test
    void testLargeValueStress() throws Exception {
        /*
         * 大值压力测试：
         *   1 线程，5 个 10KB 大值 SET/GET 操作
         *   验证 NIO 分段读写的正确性
         */
        int ops = 5;
        int valueSize = 10 * 1024; // 10KB

        StringBuilder sb = new StringBuilder(valueSize);
        for (int i = 0; i < valueSize; i++) sb.append('x');
        String largeValue = sb.toString();

        try (Socket socket = new Socket("127.0.0.1", serverPort)) {
            socket.setSoTimeout(30000);
            int success = 0;
            int errors = 0;

            for (int i = 0; i < ops; i++) {
                try {
                    String key = "plarge_" + i;
                    RespResult r = sendOnSocket(socket, "SET", key, largeValue);
                    if (r.type == RespResult.Type.SIMPLE_STRING && "OK".equals(r.stringValue)) {
                        r = sendOnSocket(socket, "GET", key);
                        if (r.type == RespResult.Type.BULK_STRING && r.data != null && r.data.length == valueSize) {
                            success++;
                        } else {
                            errors++;
                        }
                    } else {
                        errors++;
                    }
                } catch (Exception e) {
                    errors++;
                }
            }

            assertEquals(0, errors, "Zero errors for large value ops");
            assertEquals(ops, success, "All large value ops should succeed");

            System.out.println("=== Large Value Stress Test ===");
            System.out.println("Ops: " + ops + ", Value size: 10KB");
            System.out.println("Success: " + success + " in single thread");
        }
    }

    @Test
    void testSustainedThroughput() throws Exception {
        /*
         * 持续吞吐量测试：
         *   2 线程混合 SET/GET，持续 5 秒
         *   验证短时间持续运行下的吞吐量
         */
        int threads = 2;
        long durationMs = 5_000;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicLong totalOps = new AtomicLong();
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<String> errorDetail = new AtomicReference<>("");

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try (Socket socket = new Socket("127.0.0.1", serverPort)) {
                    socket.setSoTimeout(TIMEOUT_MS);
                    startLatch.await();

                    long deadline = System.currentTimeMillis() + durationMs;
                    int counter = 0;

                    while (System.currentTimeMillis() < deadline) {
                        try {
                            String key = "ps_sustain_" + threadId + "_" + (counter % 200);
                            RespResult setR = sendOnSocket(socket, "SET", key, "val_" + counter);
                            if (setR.type == RespResult.Type.SIMPLE_STRING && "OK".equals(setR.stringValue)) {
                                RespResult r = sendOnSocket(socket, "GET", key);
                                if (r.type == RespResult.Type.BULK_STRING) {
                                    totalOps.addAndGet(2);
                                } else {
                                    errors.incrementAndGet();
                                }
                            } else {
                                errors.incrementAndGet();
                            }
                            counter++;
                        } catch (Exception e) {
                            errors.incrementAndGet();
                            errorDetail.set(e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    errorDetail.set(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue(finished, "Sustained throughput test should complete within 30s");

        long ops = totalOps.get();
        double qps = ops / (durationMs / 1000.0);

        System.out.println("=== Sustained Throughput Test (5s) ===");
        System.out.println("Total ops (SET+GET): " + ops);
        System.out.println("Avg QPS: " + String.format("%.0f", qps));
        System.out.println("Errors: " + errors.get());
        if (errors.get() > 0) {
            System.out.println("Last error: " + errorDetail.get());
        }

        assertTrue(ops >= 200, "Should complete >= 200 ops in 5s, got: " + ops);
        assertTrue(errors.get() < 10, "Errors should be minimal: " + errors.get());
    }

    @Test
    void testConnectionStorm() throws Exception {
        /*
         * 连接风暴测试：
         *   50 线程各建 5 个短连接 = 250 连接
         *   验证 Acceptor 在高频连接/断开场景下的稳定性
         */
        int threads = 50;
        int connPerThread = 5;

        ExecutorService executor = Executors.newFixedThreadPool(30);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        byte[] pingResp = encodeCommand("PING");

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                for (int j = 0; j < connPerThread; j++) {
                    try (Socket s = new Socket("127.0.0.1", serverPort)) {
                        s.setSoTimeout(5000);
                        RespResult r = sendOnSocketRaw(s, pingResp);
                        if (r.type == RespResult.Type.SIMPLE_STRING && "PONG".equals(r.stringValue)) {
                            success.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // 单个连接失败可接受
                    }
                }
                doneLatch.countDown();
            });
        }

        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        assertTrue(finished, "Connection storm should complete within 30s");
        assertTrue(success.get() >= threads * connPerThread * 8 / 10,
                "At least 80% connections should succeed: " + success.get()
                        + "/" + (threads * connPerThread));

        System.out.println("=== Connection Storm Test ===");
        System.out.println("Success: " + success.get() + "/" + (threads * connPerThread));

        executor.shutdown();
    }
}
