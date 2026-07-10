package com.qkinfotech.bizwax.sdcs.proxy;

import com.qkinfotech.bizwax.sdcs.SDCSServer;
import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxyIntegrationTest {

    private static SDCSServer sdcsServer;
    private static SDCSProxy proxy;
    private static final int SDCS_PORT = 16380;
    private static final int PROXY_PORT = 16381;
    private static final String DB_PATH = "test-proxy.db";

    @BeforeAll
    static void setUpAll() throws Exception {
        // Clean up previous test db
        Files.deleteIfExists(new File(DB_PATH).toPath());

        // Start SDCS server
        SDCSConfig sdcsConfig = new SDCSConfig();
        sdcsConfig.setPort(SDCS_PORT);
        sdcsConfig.setPersistenceType(SDCSConfig.PersistenceType.NONE);
        sdcsConfig.setMetricsEnabled(false);
        sdcsConfig.setRegistryJdbcUrl("jdbc:sqlite:" + DB_PATH);
        sdcsConfig.setRegisterHash("0-1023");
        sdcsConfig.setRegisterAddr("127.0.0.1:" + SDCS_PORT);

        sdcsServer = new SDCSServer(sdcsConfig);
        sdcsServer.start();
        Thread.sleep(500); // Wait for registration

        // Create config file for proxy
        String configContent = """
                proxy.port=%d
                proxy.bind=127.0.0.1
                proxy.max-connections=100
                registry.jdbc-url=jdbc:sqlite:%s
                registry.refresh-interval-seconds=1
                registry.heartbeat-timeout-seconds=10
                backend.min-idle=1
                backend.max-total=4
                """.formatted(PROXY_PORT, DB_PATH.replace("\\", "\\\\"));
        Files.writeString(new File("config-integration.properties").toPath(), configContent);

        proxy = new SDCSProxy("config-integration.properties");
        proxy.start();
        Thread.sleep(500); // Wait for proxy to initialize and refresh route table
    }

    @AfterAll
    static void tearDownAll() {
        if (sdcsServer != null) {
            sdcsServer.stop();
        }
    }

    @Test
    @Order(1)
    void testPing() throws Exception {
        String response = sendCommand("PING");
        assertTrue(response.startsWith("+PONG"), "Expected +PONG but got: " + response);
    }

    @Test
    @Order(2)
    void testSetGet() throws Exception {
        String setResp = sendCommand("SET", "proxy_test_key", "proxy_test_value");
        assertEquals("+OK", setResp);

        String getResp = sendCommand("GET", "proxy_test_key");
        assertTrue(getResp.startsWith("$"), "Expected bulk string response, got: " + getResp);
        assertTrue(getResp.contains("proxy_test_value"),
                "Expected response containing proxy_test_value, got: " + getResp);
    }

    @Test
    @Order(3)
    void testDel() throws Exception {
        sendCommand("SET", "proxy_del_key", "value");
        String delResp = sendCommand("DEL", "proxy_del_key");
        assertTrue(delResp.startsWith(":1"), "Expected :1 but got: " + delResp);

        String getResp = sendCommand("GET", "proxy_del_key");
        assertTrue(getResp.contains("$-1"), "Expected null bulk string, got: " + getResp);
    }

    @Test
    @Order(4)
    void testCommandViaProxy() throws Exception {
        // Multiple operations that go through proxy routing
        String setResp = sendCommand("SET", "counter", "100");
        assertEquals("+OK", setResp);

        String incrResp = sendCommand("INCR", "counter");
        assertTrue(incrResp.startsWith(":101"), "Expected :101 but got: " + incrResp);

        String getResp = sendCommand("GET", "counter");
        assertTrue(getResp.startsWith("$3"), "Expected $3 bulk string, got: " + getResp);
    }

    private String sendCommand(String cmd, String... args) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", PROXY_PORT)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Build RESP command
            StringBuilder sb = new StringBuilder();
            sb.append("*").append(1 + args.length).append("\r\n");
            sb.append("$").append(cmd.length()).append("\r\n");
            sb.append(cmd).append("\r\n");
            for (String arg : args) {
                sb.append("$").append(arg.length()).append("\r\n");
                sb.append(arg).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Read response (one line for simple responses)
            // For simple responses like +OK, +PONG, :1, $-1, just read the first line
            byte[] buf = new byte[4096];
            int totalRead = 0;
            long deadline = System.currentTimeMillis() + 5000;

            while (totalRead < buf.length && System.currentTimeMillis() < deadline) {
                int n = in.read(buf, totalRead, buf.length - totalRead);
                if (n == -1) break;
                totalRead += n;
                // Check if we have a complete response (single line ending with \r\n for simple types,
                // or more for bulk strings)
                String partial = new String(buf, 0, totalRead, StandardCharsets.UTF_8);
                if (partial.startsWith("+") || partial.startsWith("-") || partial.startsWith(":")) {
                    if (partial.contains("\r\n")) {
                        break;
                    }
                } else if (partial.startsWith("$")) {
                    // Bulk string: $<len>\r\n<data>\r\n
                    if (partial.contains("\r\n")) {
                        int idx = partial.indexOf("\r\n");
                        String lenStr = partial.substring(1, idx);
                        if (lenStr.equals("-1")) {
                            // Null bulk string, just $-1\r\n
                            if (partial.length() >= idx + 2) break;
                        } else {
                            int len = Integer.parseInt(lenStr);
                            int totalExpected = idx + 2 + len + 2; // header + data + \r\n
                            if (totalRead >= totalExpected) break;
                        }
                    }
                } else if (partial.startsWith("*")) {
                    // Array: similar to bulk string header
                    if (partial.contains("\r\n")) break;
                }
            }

            return new String(buf, 0, totalRead, StandardCharsets.UTF_8).trim();
        }
    }
}
