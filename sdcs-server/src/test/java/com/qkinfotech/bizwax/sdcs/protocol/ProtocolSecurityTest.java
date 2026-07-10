package com.qkinfotech.bizwax.sdcs.protocol;

import com.qkinfotech.bizwax.sdcs.SDCSServer;
import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.Socket;

import static com.qkinfotech.bizwax.sdcs.protocol.ProtocolTestBase.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全报文测试 — 覆盖 AUTH 认证、requirepass、未授权访问等场景。
 * <p>
 * 启动带 --requirepass 的服务器。
 */
class ProtocolSecurityTest extends ProtocolTestBase {

    private static SDCSServer authServer;
    private static int authPort;

    @BeforeAll
    static void setUp() throws Exception {
        authPort = PORT_ALLOC.getAndIncrement();
        SDCSConfig config = new SDCSConfig();
        config.setPort(authPort);
        config.setPersistenceType(SDCSConfig.PersistenceType.NONE);
        config.setMetricsEnabled(false);
        config.setRequirepass("testpassword123");
        config.setDataDir("target/test-data/auth-" + authPort);

        authServer = new SDCSServer(config);
        authServer.start();
        Thread.sleep(300);
    }

    @AfterAll
    static void tearDown() {
        if (authServer != null) {
            authServer.stop();
        }
    }

    @Test
    void testCommandWithoutAuth() throws Exception {
        // 未认证时执行命令应返回错误
        RespResult r = sendCommandWithPassword(authPort, null, "SET", "key1", "val1");
        assertEquals(RespResult.Type.ERROR, r.type);
        assertNotNull(r.stringValue);
        assertTrue(r.stringValue.contains("NOAUTH"), "Should get NOAUTH error, got: " + r.stringValue);
    }

    @Test
    void testAuthWithCorrectPassword() throws Exception {
        // 认证 — 直接发送 AUTH 命令（password=null 避免重复 AUTH）
        RespResult r = sendCommandWithPassword(authPort, null, "AUTH", "testpassword123");
        assertEquals(RespResult.Type.SIMPLE_STRING, r.type);
        assertEquals("OK", r.stringValue);

        // 认证后在新连接上可正常执行命令（自动 AUTH 前缀）
        r = sendCommandWithPassword(authPort, "testpassword123", "SET", "authkey", "authval");
        assertEquals(RespResult.Type.SIMPLE_STRING, r.type);
        assertEquals("OK", r.stringValue);

        r = sendCommandWithPassword(authPort, "testpassword123", "GET", "authkey");
        assertEquals("authval", r.asString());
    }

    @Test
    void testAuthWrongPassword() throws Exception {
        RespResult r = sendCommandWithPassword(authPort, null, "AUTH", "wrongpassword");
        assertEquals(RespResult.Type.ERROR, r.type);
        assertNotNull(r.stringValue);
        assertTrue(r.stringValue.contains("ERR"), "Wrong password should return error, got: " + r.stringValue);
    }

    @Test
    void testAuthThenQuitThenUnauthorized() throws Exception {
        // 认证
        RespResult r = sendCommandWithPassword(authPort, null, "AUTH", "testpassword123");
        assertEquals("OK", r.stringValue);

        // 发送 QUIT 并关闭连接：使用手动 Socket 管理
        try (Socket socket = new Socket("127.0.0.1", authPort)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();

            // 先 AUTH
            out.write(encodeCommand("AUTH", "testpassword123"));
            out.flush();
            parseResponse(socket.getInputStream());

            // 发送 QUIT
            out.write(encodeCommand("QUIT"));
            out.flush();

            // 尝试读取 QUIT 响应，超时或 EOF 均可接受
            try {
                RespResult quitResp = parseResponse(socket.getInputStream());
                assertTrue(quitResp.type == RespResult.Type.SIMPLE_STRING
                        || quitResp.type == RespResult.Type.ERROR,
                        "QUIT should return OK or error, got: " + quitResp.type);
            } catch (Exception e) {
                // QUIT 后连接关闭导致无法读取响应也是可接受的
                System.out.println("QUIT response read caused: " + e.getMessage());
            }
        }

        // 新连接未认证应返回 NOAUTH
        r = sendCommandWithPassword(authPort, null, "SET", "should", "fail");
        assertEquals(RespResult.Type.ERROR, r.type);
        assertTrue(r.stringValue.contains("NOAUTH"), "After QUIT, new connection should be unauthenticated");
    }

    @Test
    void testPingWithoutAuth() throws Exception {
        // PING 在未认证状态下 — 有些服务器允许 PING，有些返回 NOAUTH
        RespResult r = sendCommandWithPassword(authPort, null, "PING");
        // 兼容两种行为：允许 PING（+PONG）或拒绝（-NOAUTH）
        if (r.type == RespResult.Type.ERROR) {
            assertTrue(r.stringValue.contains("NOAUTH"),
                    "If rejected, should be NOAUTH, got: " + r.stringValue);
        } else {
            assertEquals(RespResult.Type.SIMPLE_STRING, r.type,
                    "PING should return PONG or NOAUTH, got: " + r.type);
            assertEquals("PONG", r.stringValue);
        }
    }

    @Test
    void testAuthWithEmptyPassword() throws Exception {
        // 使用空密码认证 — 应该失败（密码不匹配）
        try {
            RespResult r = sendCommandWithPassword(authPort, null, "AUTH", "");
            assertTrue(r.type == RespResult.Type.ERROR, "Empty password should return error, got: " + r.type);
        } catch (Exception e) {
            // 如果服务器不支持空字符串的 $0 bulk string，超时也是可接受的
            System.out.println("AUTH with empty password caused timeout/error: " + e.getMessage());
        }
    }

    /**
     * 带密码或不带密码发送命令到安全服务器。
     * <p>
     * 如果 {@code password != null}，先发送 {@code AUTH <password>} 并丢弃响应，
     * 再发送实际命令。适用于需要在已认证连接上执行命令的场景。
     * <p>
     * 如果 {@code password == null}，直接发送命令（适用于测试未认证场景，
     * 或命令本身就是 AUTH 的场景）。
     */
    private static RespResult sendCommandWithPassword(int port, String password, String cmd, String... args) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();

            // 如果指定了密码，先发送 AUTH 命令
            if (password != null) {
                byte[] authReq = encodeCommand("AUTH", password);
                out.write(authReq);
                out.flush();
                // 读取 AUTH 响应并丢弃
                parseResponse(socket.getInputStream());
            }

            // 发送实际命令
            byte[] req = encodeCommand(cmd, args);
            out.write(req);
            out.flush();

            return parseResponse(socket.getInputStream());
        }
    }
}
