package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BoundaryTest {

    private DatabaseManager dbManager;
    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager();
        dispatcher = new CommandDispatcher(dbManager);
    }

    private static RedisMessage bulkString(String s) {
        return RedisMessage.bulkString(s.getBytes());
    }

    private static List<RedisMessage> args(String... strings) {
        return List.of(strings).stream().map(s -> RedisMessage.bulkString(s.getBytes())).toList();
    }

    @Test
    void testLargeValue() {
        String largeVal = "x".repeat(1024 * 1024);
        dispatcher.dispatch("SET", args("large", largeVal));
        RedisMessage r = dispatcher.dispatch("GET", args("large"));
        assertEquals(largeVal, r.asString());
    }

    @Test
    void testLongKey() {
        String longKey = "k".repeat(1024);
        dispatcher.dispatch("SET", args(longKey, "val"));
        RedisMessage r = dispatcher.dispatch("GET", args(longKey));
        assertEquals("val", r.asString());
    }

    @Test
    void testEmptyValue() {
        dispatcher.dispatch("SET", args("empty", ""));
        RedisMessage r = dispatcher.dispatch("GET", args("empty"));
        assertEquals("", r.asString());
    }

    @Test
    void testSpecialChars() {
        String special = "key:with/special@chars#123";
        dispatcher.dispatch("SET", args(special, "val"));
        RedisMessage r = dispatcher.dispatch("GET", args(special));
        assertEquals("val", r.asString());
    }

    @Test
    void testUnicodeValue() {
        String unicode = "中文测试汉字 \uD83D\uDE00\uD83C\uDF89";
        dispatcher.dispatch("SET", args("unicode", unicode));
        RedisMessage r = dispatcher.dispatch("GET", args("unicode"));
        assertEquals(unicode, r.asString());
    }

    @Test
    void testManyKeys() {
        for (int i = 0; i < 10000; i++) {
            dispatcher.dispatch("SET", args("bk_" + i, "v" + i));
        }
        for (int i = 0; i < 10000; i += 1000) {
            RedisMessage r = dispatcher.dispatch("GET", args("bk_" + i));
            assertEquals("v" + i, r.asString());
        }
    }

    @Test
    void testOverwriteKey() {
        dispatcher.dispatch("SET", args("ow", "old"));
        dispatcher.dispatch("SET", args("ow", "new"));
        RedisMessage r = dispatcher.dispatch("GET", args("ow"));
        assertEquals("new", r.asString());
    }

    @Test
    void testNullKeyGet() {
        RedisMessage r = dispatcher.dispatch("GET", args("nonexistent"));
        assertTrue(r.isNullBulkString());
    }

    @Test
    void testEmptyKey() {
        dispatcher.dispatch("SET", args("", "emptykey"));
        RedisMessage r = dispatcher.dispatch("GET", args(""));
        assertEquals("emptykey", r.asString());
    }

    @Test
    void testBinarySafeValue() {
        byte[] binaryData = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryData[i] = (byte) i;
        }
        dispatcher.dispatch("SET", List.of(
                bulkString("bin"),
                RedisMessage.bulkString(binaryData)
        ));
        RedisMessage r = dispatcher.dispatch("GET", args("bin"));
        assertArrayEquals(binaryData, r.getData());
    }

    @Test
    void testCaseSensitiveKey() {
        dispatcher.dispatch("SET", args("Key", "upper"));
        dispatcher.dispatch("SET", args("key", "lower"));
        RedisMessage r1 = dispatcher.dispatch("GET", args("Key"));
        assertEquals("upper", r1.asString());
        RedisMessage r2 = dispatcher.dispatch("GET", args("key"));
        assertEquals("lower", r2.asString());
    }

    @Test
    void testSetGetIntegerValue() {
        dispatcher.dispatch("SET", args("intkey", "42"));
        RedisMessage r = dispatcher.dispatch("GET", args("intkey"));
        assertEquals("42", r.asString());
    }

    @Test
    void testVeryLongValue() {
        String longVal = "a".repeat(10 * 1024 * 1024);
        dispatcher.dispatch("SET", args("vlong", longVal));
        RedisMessage r = dispatcher.dispatch("GET", args("vlong"));
        assertEquals(longVal, r.asString());
    }
}
