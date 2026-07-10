package com.qkinfotech.bizwax.sdcs.protocol;

import com.qkinfotech.bizwax.sdcs.SDCSServer;
import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

import static com.qkinfotech.bizwax.sdcs.protocol.ProtocolTestBase.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * NIOServer 重启测试 — 验证 start → stop → start 流程。
 * <p>
 * 每个测试方法独立管理自身的 server 生命周期，避免互相干扰。
 */
class ProtocolRestartTest {

    private static final AtomicInteger PORT_ALLOC = new AtomicInteger(18300);

    /** 在指定端口上发送命令 */
    private static RespResult sendOnPort(int port, String cmd, String... args) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(encodeCommand(cmd, args));
            out.flush();
            return parseResponse(in);
        }
    }

    /** 创建并启动服务器 */
    private static SDCSServer createServer(int port, String dataDir) throws Exception {
        SDCSConfig config = new SDCSConfig();
        config.setPort(port);
        config.setPersistenceType(SDCSConfig.PersistenceType.NONE);
        config.setMetricsEnabled(false);
        config.setDataDir(dataDir);
        SDCSServer srv = new SDCSServer(config);
        srv.start();
        Thread.sleep(500);
        // 验证端口可用
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(3000);
            OutputStream out = s.getOutputStream();
            out.write(encodeCommand("PING"));
            out.flush();
            RespResult r = parseResponse(s.getInputStream());
            assertEquals("+PONG", r.toString(), "Server should respond to PING");
        }
        return srv;
    }

    @Test
    void testSameInstanceRestart() throws Exception {
        /*
         * 场景5：同一个实例 start() → stop() → start()
         * 验证：相同 SDCSServer/NIOServer 对象可以多次启停
         */
        int port = PORT_ALLOC.getAndIncrement();
        String dataDir = "target/test-data/rst-sameinst";

        SDCSConfig config = new SDCSConfig();
        config.setPort(port);
        config.setPersistenceType(SDCSConfig.PersistenceType.NONE);
        config.setMetricsEnabled(false);
        config.setDataDir(dataDir);

        // 同一个实例，只创建一次
        SDCSServer server = new SDCSServer(config);

        // ====== 第一轮：start → 命令 → stop ======
        System.out.println("--- Same-instance: round 1 (port " + port + ") ---");
        server.start();
        Thread.sleep(500);

        assertEquals("+PONG", sendOnPort(port, "PING").toString());
        sendOnPort(port, "SET", "rk_r1", "round1");
        assertEquals("round1", sendOnPort(port, "GET", "rk_r1").asString());

        server.stop();
        Thread.sleep(1500);

        // 停止后连接被拒
        try {
            sendOnPort(port, "PING");
            fail("Should not connect after stop (round 1)");
        } catch (Exception e) {
            // 预期
        }

        // ====== 第二轮：同一个实例再次 start → 命令 → stop ======
        System.out.println("--- Same-instance: round 2 (port " + port + ") ---");
        server.start();
        Thread.sleep(500);

        assertEquals("+PONG", sendOnPort(port, "PING").toString());
        sendOnPort(port, "SET", "rk_r2", "round2");
        assertEquals("round2", sendOnPort(port, "GET", "rk_r2").asString());

        // 第一轮的数据仍在内存中（静态 MemoryStore），不验证

        server.stop();
        Thread.sleep(500);

        try {
            sendOnPort(port, "PING");
            fail("Should not connect after stop (round 2)");
        } catch (Exception e) {
            // 预期
        }

        System.out.println("=== testSameInstanceRestart PASSED ===");
    }

    @Test
    void testStartStopStart() throws Exception {
        /*
         * 场景1：stop() → start() 一次
         * 验证：实例 A 正常 → 停止后连接被拒 → 实例 B 正常
         */
        int port = PORT_ALLOC.getAndIncrement();
        SDCSServer server = createServer(port, "target/test-data/rst1");

        // 实例 A 正常工作
        assertEquals("+PONG", sendOnPort(port, "PING").toString());
        sendOnPort(port, "SET", "rk_a", "hello");
        assertEquals("hello", sendOnPort(port, "GET", "rk_a").asString());

        // 保持旧连接
        Socket old = new Socket("127.0.0.1", port);
        old.setSoTimeout(3000);

        // 停止实例 A
        System.out.println("--- Stopping instance A (port " + port + ") ---");
        server.stop();
        Thread.sleep(1500);

        // 旧连接应断开
        try {
            OutputStream oldOut = old.getOutputStream();
            oldOut.write(encodeCommand("PING"));
            oldOut.flush();
            parseResponse(old.getInputStream());
            fail("Old connection should be closed after stop");
        } catch (Exception e) {
            // 预期
        }
        old.close();

        // 新连接应该被拒
        try {
            sendOnPort(port, "PING");
            fail("Should not connect after stop");
        } catch (Exception e) {
            // 预期
        }

        // 启动实例 B，相同端口
        System.out.println("--- Starting instance B (port " + port + ") ---");
        server = createServer(port, "target/test-data/rst1b");

        // 实例 B 正常工作
        assertEquals("+PONG", sendOnPort(port, "PING").toString());
        sendOnPort(port, "SET", "rk_b", "world");
        assertEquals("world", sendOnPort(port, "GET", "rk_b").asString());

        server.stop();
        Thread.sleep(500);
        System.out.println("=== testStartStopStart PASSED ===");
    }

    @Test
    void testMultipleRestartCycles() throws Exception {
        /*
         * 场景2：多次重启（3 次）
         * 验证：每次重启后 server 都能正常处理命令
         */
        int port = PORT_ALLOC.getAndIncrement();

        for (int i = 0; i < 3; i++) {
            System.out.println("--- Restart cycle " + (i + 1) + "/3 (port " + port + ") ---");
            SDCSServer srv = createServer(port, "target/test-data/rstc" + i + "_" + port);

            assertEquals("+PONG", sendOnPort(port, "PING").toString());
            sendOnPort(port, "SET", "rk_" + i, "val_" + i);
            assertEquals("val_" + i, sendOnPort(port, "GET", "rk_" + i).asString());

            srv.stop();
            Thread.sleep(1500);

            // 验证已停止
            try {
                sendOnPort(port, "PING");
                fail("Cycle " + i + ": server should be stopped");
            } catch (Exception e) {
                // 预期
            }
        }
        System.out.println("=== testMultipleRestartCycles PASSED ===");
    }

    @Test
    void testStopIdempotent() throws Exception {
        /*
         * 场景3：stop() 幂等性
         * 验证：连续调用两次 stop() 不应抛异常
         */
        int port = PORT_ALLOC.getAndIncrement();
        SDCSServer server = createServer(port, "target/test-data/rstidem");

        assertEquals("+PONG", sendOnPort(port, "PING").toString());

        server.stop();
        Thread.sleep(1000);

        // 第二次 stop()
        server.stop();

        try {
            sendOnPort(port, "PING");
            fail("Server should be stopped");
        } catch (Exception e) {
            // 预期
        }
        System.out.println("=== testStopIdempotent PASSED ===");
    }

    @Test
    void testRestartWithPersistence() throws Exception {
        /*
         * 场景4：持久化重启
         * 验证：AOF 模式下数据在重启后依然存在
         */
        int port = PORT_ALLOC.getAndIncrement();
        String dataDir = "target/test-data/rstpersist";

        SDCSConfig cfg1 = new SDCSConfig();
        cfg1.setPort(port);
        cfg1.setPersistenceType(SDCSConfig.PersistenceType.RDB_AOF);
        cfg1.setMetricsEnabled(false);
        cfg1.setDataDir(dataDir);

        SDCSServer server = new SDCSServer(cfg1);
        server.start();
        Thread.sleep(800);

        sendOnPort(port, "SET", "persist_k", "keepme");
        sendOnPort(port, "SADD", "persist_s", "a", "b", "c");

        server.stop();
        Thread.sleep(1500);

        // 重新启动
        SDCSConfig cfg2 = new SDCSConfig();
        cfg2.setPort(port);
        cfg2.setPersistenceType(SDCSConfig.PersistenceType.RDB_AOF);
        cfg2.setMetricsEnabled(false);
        cfg2.setDataDir(dataDir);

        server = new SDCSServer(cfg2);
        server.start();
        Thread.sleep(800);

        // 数据应恢复
        RespResult r = sendOnPort(port, "GET", "persist_k");
        assertEquals("keepme", r.asString(), "AOF data should survive restart");

        r = sendOnPort(port, "SCARD", "persist_s");
        assertEquals(3L, r.intValue, "Set should be restored from AOF");

        server.stop();
        Thread.sleep(500);
        System.out.println("=== testRestartWithPersistence PASSED ===");
    }
}
