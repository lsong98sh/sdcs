package com.qkinfotech.bizwax.sdcs.protocol;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.qkinfotech.bizwax.sdcs.protocol.ProtocolTestBase.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 高并发报文测试 — 多线程多 Socket 同时发送 RESP 命令。
 * <p>
 * 每个线程复用同一个 Socket（而非每次命令新建连接），
 * 避免耗尽 Windows 临时端口（TIME_WAIT）。
 * <p>
 * 覆盖 NIOServer 在高并发下的表现：
 * - IO 线程多路复用
 * - Worker 线程串行化处理
 * - 写缓冲区管理
 */
class ConcurrencyTest extends ProtocolTestBase {

    private static final int THREADS = 20;
    private static final int OPS_PER_THREAD = 500;

    /**
     * 在已有 Socket 上发送 RESP 命令并解析响应（复用连接）。
     */
    private static RespResult sendCommandOnSocket(Socket socket, String cmd, String... args) throws Exception {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        byte[] req = encodeCommand(cmd, args);
        out.write(req);
        out.flush();
        return parseResponse(in);
    }

    @Test
    void testConcurrentSetGet() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREADS);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicLong totalLatency = new AtomicLong();

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try (Socket socket = new Socket("127.0.0.1", serverPort)) {
                    socket.setSoTimeout(TIMEOUT_MS);
                    startLatch.await();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        String key = "conkey_" + threadId + "_" + i;
                        String val = "val_" + i;

                        long start = System.nanoTime();
                        RespResult r = sendCommandOnSocket(socket, "SET", key, val);
                        long lat = System.nanoTime() - start;
                        totalLatency.addAndGet(lat);

                        if (r.type == RespResult.Type.SIMPLE_STRING && "OK".equals(r.stringValue)) {
                            success.incrementAndGet();
                        } else {
                            errors.incrementAndGet();
                        }

                        // 穿插读取
                        r = sendCommandOnSocket(socket, "GET", key);
                        if (r.type == RespResult.Type.BULK_STRING && val.equals(r.asString())) {
                            success.incrementAndGet();
                        } else {
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

        long testStart = System.nanoTime();
        startLatch.countDown();
        boolean finished = doneLatch.await(60, TimeUnit.SECONDS);
        long elapsedNanos = System.nanoTime() - testStart;

        assertTrue(finished, "Concurrent test should complete within 60s");
        assertEquals(0, errors.get(), "No protocol errors should occur");
        assertEquals(THREADS * OPS_PER_THREAD * 2, success.get(),
                "All SET+GET should succeed");

        double elapsedSec = elapsedNanos / 1_000_000_000.0;
        long totalOps = success.get();
        double qps = totalOps / elapsedSec;
        long avgLatUs = totalOps > 0 ? (totalLatency.get() / totalOps) / 1000 : 0;

        System.out.println("=== Concurrency Test ===");
        System.out.println("Threads: " + THREADS + ", Ops/thread: " + OPS_PER_THREAD);
        System.out.println("Total ops: " + totalOps + " in " + String.format("%.2f", elapsedSec) + "s");
        System.out.println("QPS: " + String.format("%.0f", qps));
        System.out.println("Avg latency: " + avgLatUs + "us");

        assertTrue(qps > 100, "QPS should be > 100 for concurrent operations");
        executor.shutdown();
    }

    @Test
    void testConcurrentDifferentDataTypes() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(10);
        AtomicInteger errors = new AtomicInteger();

        Runnable listOps = () -> {
            try (Socket socket = new Socket("127.0.0.1", serverPort)) {
                socket.setSoTimeout(TIMEOUT_MS);
                startLatch.await();
                for (int i = 0; i < 200; i++) {
                    sendCommandOnSocket(socket, "LPUSH", "con_list", "item_" + i);
                }
                RespResult r = sendCommandOnSocket(socket, "LLEN", "con_list");
                if (r.type != RespResult.Type.INTEGER || r.intValue != 200) errors.incrementAndGet();
            } catch (Exception ignored) { errors.incrementAndGet(); }
            finally { doneLatch.countDown(); }
        };

        Runnable hashOps = () -> {
            try (Socket socket = new Socket("127.0.0.1", serverPort)) {
                socket.setSoTimeout(TIMEOUT_MS);
                startLatch.await();
                for (int i = 0; i < 200; i++) {
                    sendCommandOnSocket(socket, "HSET", "con_hash", "field_" + i, "val_" + i);
                }
                RespResult r = sendCommandOnSocket(socket, "HLEN", "con_hash");
                if (r.type != RespResult.Type.INTEGER || r.intValue != 200) errors.incrementAndGet();
            } catch (Exception ignored) { errors.incrementAndGet(); }
            finally { doneLatch.countDown(); }
        };

        Runnable setOps = () -> {
            try (Socket socket = new Socket("127.0.0.1", serverPort)) {
                socket.setSoTimeout(TIMEOUT_MS);
                startLatch.await();
                for (int i = 0; i < 200; i++) {
                    sendCommandOnSocket(socket, "SADD", "con_set", "member_" + i);
                }
                RespResult r = sendCommandOnSocket(socket, "SCARD", "con_set");
                if (r.type != RespResult.Type.INTEGER || r.intValue != 200) errors.incrementAndGet();
            } catch (Exception ignored) { errors.incrementAndGet(); }
            finally { doneLatch.countDown(); }
        };

        Runnable zsetOps = () -> {
            try (Socket socket = new Socket("127.0.0.1", serverPort)) {
                socket.setSoTimeout(TIMEOUT_MS);
                startLatch.await();
                for (int i = 0; i < 200; i++) {
                    sendCommandOnSocket(socket, "ZADD", "con_zset", String.valueOf(i), "mem_" + i);
                }
                RespResult r = sendCommandOnSocket(socket, "ZCARD", "con_zset");
                if (r.type != RespResult.Type.INTEGER || r.intValue != 200) errors.incrementAndGet();
            } catch (Exception ignored) { errors.incrementAndGet(); }
            finally { doneLatch.countDown(); }
        };

        Runnable stringOps = () -> {
            try (Socket socket = new Socket("127.0.0.1", serverPort)) {
                socket.setSoTimeout(TIMEOUT_MS);
                startLatch.await();
                for (int i = 0; i < 200; i++) {
                    sendCommandOnSocket(socket, "INCR", "con_incr");
                }
                RespResult r = sendCommandOnSocket(socket, "GET", "con_incr");
                if (r.type != RespResult.Type.BULK_STRING || !"200".equals(r.asString())) errors.incrementAndGet();
            } catch (Exception ignored) { errors.incrementAndGet(); }
            finally { doneLatch.countDown(); }
        };

        // 每种数据类型一个线程，10个线程
        executor.submit(listOps);
        executor.submit(listOps);
        executor.submit(hashOps);
        executor.submit(hashOps);
        executor.submit(setOps);
        executor.submit(setOps);
        executor.submit(zsetOps);
        executor.submit(zsetOps);
        executor.submit(stringOps);
        executor.submit(stringOps);

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        assertTrue(finished, "Concurrent data type test should complete");
        assertTrue(errors.get() < 5, "Most operations should succeed, errors: " + errors.get());

        executor.shutdown();
    }

    @Test
    void testConcurrentConnections() throws Exception {
        // 连接风暴测试：50 线程各建 20 个短连接 = 1000 连接，在临时端口范围内
        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch doneLatch = new CountDownLatch(50);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < 50; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 20; j++) {
                        try (Socket s = new Socket("127.0.0.1", serverPort)) {
                            s.setSoTimeout(5000);
                            s.getOutputStream().write(
                                    ("*3\r\n$3\r\nSET\r\n$10\r\ncc_" + idx + "_" + j + "\r\n$5\r\nvalue\r\n").getBytes());
                            s.getOutputStream().flush();
                            // 读取响应但不解析
                            s.getInputStream().read(new byte[1024]);
                            success.incrementAndGet();
                        } catch (Exception e) {
                            // 单个连接失败不中断整体
                        }
                    }
                } catch (Exception e) {
                    // 连接失败
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        assertTrue(finished, "Connection storm should complete");
        assertTrue(success.get() > 500, "Most connections should succeed: " + success.get());

        System.out.println("Connection storm: " + success.get() + "/1000 successful operations");
        executor.shutdown();
    }
}
