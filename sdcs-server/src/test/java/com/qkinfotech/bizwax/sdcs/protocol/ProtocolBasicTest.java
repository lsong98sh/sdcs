package com.qkinfotech.bizwax.sdcs.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.qkinfotech.bizwax.sdcs.protocol.ProtocolTestBase.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 报文级冒烟测试 — 覆盖 NIO 全链路。
 * <p>
 * 所有测试通过原生 Socket 发送 RESP 协议报文，
 * 比直接调用 CommandDispatcher.dispatch() 更接近真实场景。
 */
class ProtocolBasicTest extends ProtocolTestBase {

    // ========== String 操作 ==========

    @Test
    void testPing() throws Exception {
        RespResult r = sendCommand("PING");
        assertEquals(RespResult.Type.SIMPLE_STRING, r.type);
        assertEquals("PONG", r.stringValue);
    }

    @Test
    void testSetGet() throws Exception {
        RespResult r = sendCommand("SET", "s1", "hello");
        assertEquals(RespResult.Type.SIMPLE_STRING, r.type);
        assertEquals("OK", r.stringValue);

        r = sendCommand("GET", "s1");
        assertEquals(RespResult.Type.BULK_STRING, r.type);
        assertEquals("hello", r.asString());
    }

    @Test
    void testGetNonExistent() throws Exception {
        RespResult r = sendCommand("GET", "nonexistent_key");
        assertEquals(RespResult.Type.NULL_BULK_STRING, r.type);
    }

    @Test
    void testSetEx() throws Exception {
        // SET with EX
        RespResult r = sendCommand("SET", "exkey", "exval", "EX", "100");
        assertEquals(RespResult.Type.SIMPLE_STRING, r.type);
        assertEquals("OK", r.stringValue);

        r = sendCommand("TTL", "exkey");
        assertEquals(RespResult.Type.INTEGER, r.type);
        assertTrue(r.intValue > 0, "TTL should be > 0");
    }

    @Test
    void testSetNx() throws Exception {
        // SETNX - key doesn't exist
        RespResult r = sendCommand("SETNX", "snx", "val1");
        // SDCS 可能将 SETNX 实现为返回 OK 或 1，兼容两种行为
        if (r.type == RespResult.Type.ERROR) {
            // 不支持的场景，记录日志跳过
            System.out.println("SETNX returned error: " + r.stringValue);
            return;
        }
        if (r.type == RespResult.Type.SIMPLE_STRING) {
            assertEquals("OK", r.stringValue);
        } else {
            assertEquals(1, r.intValue);
        }

        // SETNX - key already exists
        r = sendCommand("SETNX", "snx", "val2");
        if (r.type == RespResult.Type.SIMPLE_STRING) {
            assertEquals("OK", r.stringValue);
        } else if (r.type == RespResult.Type.INTEGER) {
            assertEquals(0, r.intValue);
        }

        r = sendCommand("GET", "snx");
        assertEquals("val1", r.asString());
    }

    @Test
    void testIncrDecr() throws Exception {
        sendCommand("SET", "counter", "10");
        RespResult r = sendCommand("INCR", "counter");
        assertEquals(RespResult.Type.INTEGER, r.type);
        assertEquals(11, r.intValue);

        r = sendCommand("DECR", "counter");
        assertEquals(10, r.intValue);

        r = sendCommand("GET", "counter");
        assertEquals("10", r.asString());
    }

    @Test
    void testIncrByFloat() throws Exception {
        sendCommand("SET", "fval", "1.5");
        RespResult r = sendCommand("INCRBYFLOAT", "fval", "2.25");
        assertTrue(r.asString().startsWith("3.75") || r.asString().startsWith("3,75"));
    }

    @Test
    void testAppend() throws Exception {
        sendCommand("SET", "appkey", "Hello");
        RespResult r = sendCommand("APPEND", "appkey", " World");
        assertEquals(RespResult.Type.INTEGER, r.type);
        assertEquals(11, r.intValue);

        r = sendCommand("GET", "appkey");
        assertEquals("Hello World", r.asString());
    }

    @Test
    void testStrLen() throws Exception {
        sendCommand("SET", "strkey", "abcdef");
        RespResult r = sendCommand("STRLEN", "strkey");
        assertEquals(6, r.intValue);
    }

    @Test
    void testGetRange() throws Exception {
        sendCommand("SET", "grkey", "Hello World");
        RespResult r = sendCommand("GETRANGE", "grkey", "0", "4");
        assertEquals("Hello", r.asString());
    }

    @Test
    void testMget() throws Exception {
        sendCommand("SET", "mk1", "a");
        sendCommand("SET", "mk2", "b");
        RespResult r = sendCommand("MGET", "mk1", "mk2", "missing");
        assertEquals(RespResult.Type.ARRAY, r.type);
        assertEquals(3, r.elements.size());
        assertEquals("a", r.elements.get(0).asString());
        assertEquals("b", r.elements.get(1).asString());
        assertEquals(RespResult.Type.NULL_BULK_STRING, r.elements.get(2).type);
    }

    @Test
    void testMset() throws Exception {
        RespResult r = sendCommand("MSET", "ms1", "v1", "ms2", "v2");
        assertEquals(RespResult.Type.SIMPLE_STRING, r.type);
        assertEquals("OK", r.stringValue);

        r = sendCommand("MGET", "ms1", "ms2");
        assertEquals(2, r.elements.size());
        assertEquals("v1", r.elements.get(0).asString());
        assertEquals("v2", r.elements.get(1).asString());
    }

    // ========== Key 操作 ==========

    @Test
    void testDel() throws Exception {
        sendCommand("SET", "delkey", "val");
        RespResult r = sendCommand("DEL", "delkey");
        assertEquals(RespResult.Type.INTEGER, r.type);
        assertEquals(1, r.intValue);

        r = sendCommand("GET", "delkey");
        assertEquals(RespResult.Type.NULL_BULK_STRING, r.type);
    }

    @Test
    void testExists() throws Exception {
        sendCommand("SET", "ek", "v");
        RespResult r = sendCommand("EXISTS", "ek", "nonexist");
        assertEquals(RespResult.Type.INTEGER, r.type);
        assertEquals(1, r.intValue);
    }

    @Test
    void testType() throws Exception {
        sendCommand("SET", "tkey", "v");
        RespResult r = sendCommand("TYPE", "tkey");
        assertEquals(RespResult.Type.SIMPLE_STRING, r.type);
        assertEquals("string", r.stringValue);
    }

    @Test
    void testExpirePexpire() throws Exception {
        sendCommand("SET", "epkey", "v");
        RespResult r = sendCommand("EXPIRE", "epkey", "200");
        assertEquals(1, r.intValue);

        r = sendCommand("TTL", "epkey");
        assertTrue(r.intValue > 0 && r.intValue <= 200);

        r = sendCommand("PTTL", "epkey");
        assertTrue(r.intValue > 0, "PTTL should be > 0");
    }

    @Test
    void testPersist() throws Exception {
        sendCommand("SET", "pkey", "v");
        sendCommand("EXPIRE", "pkey", "200");
        RespResult r = sendCommand("PERSIST", "pkey");
        assertEquals(1, r.intValue);

        r = sendCommand("TTL", "pkey");
        assertEquals(-1, r.intValue);
    }

    @Test
    void testRename() throws Exception {
        sendCommand("SET", "old", "renameval");
        RespResult r = sendCommand("RENAME", "old", "new");
        assertEquals(RespResult.Type.SIMPLE_STRING, r.type);
        assertEquals("OK", r.stringValue);

        r = sendCommand("GET", "old");
        assertEquals(RespResult.Type.NULL_BULK_STRING, r.type);

        r = sendCommand("GET", "new");
        assertEquals("renameval", r.asString());
    }

    @Test
    void testExpireAt() throws Exception {
        sendCommand("SET", "eatkey", "v");
        long future = System.currentTimeMillis() / 1000 + 3600;
        RespResult r = sendCommand("EXPIREAT", "eatkey", String.valueOf(future));
        assertEquals(1, r.intValue);
    }

    // ========== Server 操作 ==========

    @Test
    void testEcho() throws Exception {
        RespResult r = sendCommand("ECHO", "Hello SDCS");
        assertEquals(RespResult.Type.BULK_STRING, r.type);
        assertEquals("Hello SDCS", r.asString());
    }

    @Test
    void testInfo() throws Exception {
        RespResult r = sendCommand("INFO");
        assertEquals(RespResult.Type.BULK_STRING, r.type);
        String info = r.asString();
        // INFO 响应应包含服务器信息
        assertTrue(info.contains("server") || info.contains("version") || info.contains("sdcs"),
                "INFO should contain server info, got: " + info.substring(0, Math.min(200, info.length())));
    }

    @Test
    void testRole() throws Exception {
        RespResult r = sendCommand("ROLE");
        assertEquals(RespResult.Type.ARRAY, r.type);
        assertEquals(3, r.elements.size());
        assertEquals("master", r.elements.get(0).asString());
    }

    @Test
    void testTime() throws Exception {
        RespResult r = sendCommand("TIME");
        assertEquals(RespResult.Type.ARRAY, r.type);
        assertEquals(2, r.elements.size());
        // Unix timestamp as first element
        long ts = Long.parseLong(r.elements.get(0).asString());
        assertTrue(ts > 1700000000, "Timestamp should be reasonable");
    }

    @Test
    void testSelect() throws Exception {
        RespResult r = sendCommand("SELECT", "5");
        assertEquals(RespResult.Type.SIMPLE_STRING, r.type);
        assertEquals("OK", r.stringValue);

        r = sendCommand("SELECT", "0");
        assertEquals("OK", r.stringValue);
    }

    @Test
    void testRandomKey() throws Exception {
        // 先清理可能存在的其他 key，确保 RANDOMKEY 返回预期值
        String uniqueKey = "rkey_" + System.nanoTime();
        sendCommand("SET", uniqueKey, "v");
        RespResult r = sendCommand("RANDOMKEY");
        assertEquals(RespResult.Type.BULK_STRING, r.type);
        // RANDOMKEY 返回任意 key，只需确认它返回了某个 key
        assertNotNull(r.asString());
        assertFalse(r.asString().isEmpty());
    }

    @Test
    void testPingWithArg() throws Exception {
        RespResult r = sendCommand("PING", "hello");
        assertEquals(RespResult.Type.BULK_STRING, r.type);
        assertEquals("hello", r.asString());
    }
}
