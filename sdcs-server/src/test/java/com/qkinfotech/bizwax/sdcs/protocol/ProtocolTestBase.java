package com.qkinfotech.bizwax.sdcs.protocol;

import com.qkinfotech.bizwax.sdcs.SDCSServer;
import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 报文测试基类。
 * <p>
 * 启动嵌入式 NIOServer，通过原生 Socket 发送 RESP 协议报文，
 * 覆盖 NIO 全链路：Acceptor → IOEventLoop.handleRead() → WorkProcessor → CommandDispatcher → IOEventLoop.drainWriteQueue()
 * <p>
 * 子类只需继承并调用 sendXxx() 方法即可。
 *
 * @see <a href="https://redis.io/docs/reference/protocol-spec/">Redis RESP Protocol</a>
 */
public abstract class ProtocolTestBase {

    protected static final AtomicInteger PORT_ALLOC = new AtomicInteger(17200);
    protected static final int TIMEOUT_MS = 10000;

    protected static SDCSServer server;
    protected static int serverPort;
    private static String persistenceMode;

    /**
     * 子类可重写此方法指定持久化模式，如 "--persistence", "none"
     */
    protected static String[] getServerArgs() {
        return new String[]{"--persistence", "none", "--no-metrics"};
    }

    @BeforeAll
    static void globalSetUp() throws Exception {
        // 获取端口并确保可用（重试最多 5 次）
        serverPort = PORT_ALLOC.getAndIncrement();
        waitForPortRelease(serverPort, 5);

        SDCSConfig config = new SDCSConfig();
        config.setPort(serverPort);
        config.setPersistenceType(SDCSConfig.PersistenceType.NONE);
        config.setMetricsEnabled(false);
        config.setDataDir("target/test-data/protocol-" + serverPort);

        server = new SDCSServer(config);
        server.start();
        Thread.sleep(300);
    }

    @AfterAll
    static void globalTearDown() {
        if (server != null) {
            server.stop();
            // 给操作系统时间释放端口（TIME_WAIT）
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 等待端口可用（前一个 server.stop() 后 TIME_WAIT 未过期时重试）。
     */
    private static void waitForPortRelease(int port, int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                return; // 端口可用
            } catch (IOException e) {
                if (i == maxRetries - 1) {
                    throw new RuntimeException("Port " + port + " not available after " + maxRetries + " retries", e);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
    }

    // ========== 报文发送 / 响应解析 ==========

    /**
     * 发送 RESP 命令，返回解析后的响应字符串。
     * 自动重试最多 3 次以处理 Windows TIME_WAIT 端口短暂耗尽。
     */
    protected static RespResult sendCommand(String cmd, String... args) throws Exception {
        IOException lastEx = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return doSendCommand(cmd, args);
            } catch (IOException e) {
                lastEx = e;
                if (e.getMessage() != null && e.getMessage().contains("Address already in use")) {
                    Thread.sleep(200 * (attempt + 1));
                    continue;
                }
                throw e;
            }
        }
        throw lastEx;
    }

    private static RespResult doSendCommand(String cmd, String... args) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", serverPort)) {
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 编码 RESP 请求
            byte[] req = encodeCommand(cmd, args);
            out.write(req);
            out.flush();

            // 解析 RESP 响应
            return parseResponse(in);
        }
    }

    /**
     * 发送原始 RESP 字节。用于测试协议边界场景。
     * 自动重试最多 3 次以处理 Windows TIME_WAIT 端口短暂耗尽。
     */
    protected static RespResult sendRawResp(byte[] rawRequest) throws Exception {
        IOException lastEx = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return doSendRawResp(rawRequest);
            } catch (IOException e) {
                lastEx = e;
                if (e.getMessage() != null && e.getMessage().contains("Address already in use")) {
                    Thread.sleep(200 * (attempt + 1));
                    continue;
                }
                throw e;
            }
        }
        throw lastEx;
    }

    private static RespResult doSendRawResp(byte[] rawRequest) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", serverPort)) {
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(rawRequest);
            out.flush();

            return parseResponse(in);
        }
    }

    // ========== RESP 编码 ==========

    protected static byte[] encodeCommand(String cmd, String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length + 1).append("\r\n");
        appendBulkString(sb, cmd);
        for (String arg : args) {
            appendBulkString(sb, arg);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    protected static byte[] encodeRawCommand(String cmd, byte[]... rawArgs) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(512);
        writeAscii(bos, "*" + (rawArgs.length + 1) + "\r\n");
        writeAscii(bos, "$" + cmd.length() + "\r\n");
        writeAscii(bos, cmd + "\r\n");
        for (byte[] arg : rawArgs) {
            writeAscii(bos, "$" + arg.length + "\r\n");
            try {
                bos.write(arg);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            writeAscii(bos, "\r\n");
        }
        return bos.toByteArray();
    }

    private static void appendBulkString(StringBuilder sb, String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        sb.append('$').append(data.length).append("\r\n");
        sb.append(s).append("\r\n");
    }

    private static void writeAscii(ByteArrayOutputStream bos, String s) {
        try {
            bos.write(s.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ========== RESP 解析 ==========

    /**
     * 从输入流解析一个 RESP 响应。
     */
    protected static RespResult parseResponse(InputStream in) throws Exception {
        int firstByte = in.read();
        if (firstByte == -1) {
            return RespResult.error("EOF");
        }
        return switch ((char) firstByte) {
            case '+' -> RespResult.simpleString(readLine(in));
            case '-' -> RespResult.error(readLine(in));
            case ':' -> RespResult.integer(Long.parseLong(readLine(in)));
            case '$' -> {
                int len = Integer.parseInt(readLine(in));
                if (len == -1) {
                    yield RespResult.nullBulkString();
                }
                byte[] data = readExact(in, len);
                // 跳过末尾 \r\n
                int cr = in.read();
                int lf = in.read();
                yield RespResult.bulkString(data);
            }
            case '*' -> {
                int count = Integer.parseInt(readLine(in));
                if (count == -1) {
                    yield RespResult.nullArray();
                }
                List<RespResult> elements = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    elements.add(parseResponse(in));
                }
                yield RespResult.array(elements);
            }
            default -> RespResult.error("Unknown type: " + (char) firstByte);
        };
    }

    protected static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') {
                    break;
                }
                bos.write(b);
                if (next != -1) bos.write(next);
            } else {
                bos.write(b);
            }
        }
        return bos.toString(StandardCharsets.UTF_8);
    }

    protected static byte[] readExact(InputStream in, int len) throws Exception {
        byte[] buf = new byte[len];
        int offset = 0;
        while (offset < len) {
            int n = in.read(buf, offset, len - offset);
            if (n == -1) throw new EOFException("Expected " + len + " bytes, got " + offset);
            offset += n;
        }
        return buf;
    }

    // ========== RESP 结果模型 ==========

    public static class RespResult {
        public enum Type { SIMPLE_STRING, ERROR, INTEGER, BULK_STRING, NULL_BULK_STRING, ARRAY, NULL_ARRAY }

        public final Type type;
        public final String stringValue;
        public final long intValue;
        public final byte[] data;
        public final List<RespResult> elements;

        private RespResult(Type type, String stringValue, long intValue, byte[] data, List<RespResult> elements) {
            this.type = type;
            this.stringValue = stringValue;
            this.intValue = intValue;
            this.data = data;
            this.elements = elements;
        }

        static RespResult simpleString(String s) { return new RespResult(Type.SIMPLE_STRING, s, 0, null, null); }
        static RespResult error(String s) { return new RespResult(Type.ERROR, s, 0, null, null); }
        static RespResult integer(long v) { return new RespResult(Type.INTEGER, null, v, null, null); }
        static RespResult bulkString(byte[] d) { return new RespResult(Type.BULK_STRING, null, 0, d, null); }
        static RespResult nullBulkString() { return new RespResult(Type.NULL_BULK_STRING, null, 0, null, null); }
        static RespResult nullArray() { return new RespResult(Type.NULL_ARRAY, null, 0, null, null); }
        static RespResult array(List<RespResult> list) { return new RespResult(Type.ARRAY, null, 0, null, list); }

        public String asString() {
            return switch (type) {
                case SIMPLE_STRING -> stringValue;
                case BULK_STRING -> new String(data, StandardCharsets.UTF_8);
                case INTEGER -> String.valueOf(intValue);
                default -> throw new RuntimeException("Cannot convert " + type + " to string");
            };
        }

        @Override
        public String toString() {
            return switch (type) {
                case SIMPLE_STRING -> "+" + stringValue;
                case ERROR -> "-" + stringValue;
                case INTEGER -> ":" + intValue;
                case BULK_STRING -> "$" + data.length + " " + asString();
                case NULL_BULK_STRING -> "$-1";
                case ARRAY -> "*" + (elements != null ? elements.size() : -1) + " " + elements;
                case NULL_ARRAY -> "*-1";
            };
        }
    }
}
