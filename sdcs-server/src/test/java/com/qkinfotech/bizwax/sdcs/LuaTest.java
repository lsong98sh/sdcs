package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.scripting.LuaScriptEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LuaTest {

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
    void testEvalReturnLiteral() {
        RedisMessage r = dispatcher.dispatch("EVAL", args("return 1", "0"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertEquals("1", r.asString());
    }

    @Test
    void testEvalCallSet() {
        RedisMessage r = dispatcher.dispatch("EVAL", args("return redis.call('SET', KEYS[1], ARGV[1])", "1", "testkey", "testval"));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());

        r = dispatcher.dispatch("GET", args("testkey"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertEquals("testval", r.asString());
    }

    @Test
    void testEvalShaWithoutLoad() {
        RedisMessage r = dispatcher.dispatch("EVALSHA", args("0000000000000000000000000000000000000000", "0"));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("NOSCRIPT"));
    }

    @Test
    void testScriptLoadAndEvalSha() {
        RedisMessage r = dispatcher.dispatch("SCRIPT", List.of(bulkString("LOAD"), bulkString("return 1")));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        String sha1 = r.asString();
        assertNotNull(sha1);

        r = dispatcher.dispatch("EVALSHA", args(sha1, "0"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertEquals("1", r.asString());
    }

    @Test
    void testScriptLoadAndEvalShaWithKeys() {
        RedisMessage r = dispatcher.dispatch("SCRIPT", List.of(
                bulkString("LOAD"),
                bulkString("return redis.call('SET', KEYS[1], ARGV[1])")
        ));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        String sha1 = r.asString();
        assertNotNull(sha1);

        r = dispatcher.dispatch("EVALSHA", args(sha1, "1", "lua_sha_key", "lua_sha_val"));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());

        r = dispatcher.dispatch("GET", args("lua_sha_key"));
        assertEquals("lua_sha_val", r.asString());
    }

    @Test
    void testScriptExists() {
        dispatcher.dispatch("SCRIPT", List.of(bulkString("LOAD"), bulkString("return 1")));

        RedisMessage r = dispatcher.dispatch("SCRIPT", List.of(
                bulkString("EXISTS"),
                bulkString("0000000000000000000000000000000000000000")
        ));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertEquals(1, elements.size());
        assertEquals(0, elements.get(0).getIntegerValue());
    }

    @Test
    void testScriptFlush() {
        dispatcher.dispatch("SCRIPT", List.of(bulkString("LOAD"), bulkString("return 1")));

        RedisMessage r = dispatcher.dispatch("SCRIPT", List.of(bulkString("FLUSH")));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());

        r = dispatcher.dispatch("SCRIPT", List.of(
                bulkString("EXISTS"),
                bulkString("0000000000000000000000000000000000000000")
        ));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        assertEquals(0, r.getElements().get(0).getIntegerValue());
    }

    @Test
    void testScriptLoadThenExists() {
        RedisMessage r = dispatcher.dispatch("SCRIPT", List.of(bulkString("LOAD"), bulkString("return 42")));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        String sha1 = r.asString();
        assertNotNull(sha1);

        r = dispatcher.dispatch("SCRIPT", List.of(bulkString("EXISTS"), bulkString(sha1)));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        assertEquals(1, r.getElements().get(0).getIntegerValue());
    }

    @Test
    void testEvalEmptyScript() {
        RedisMessage r = dispatcher.dispatch("EVAL", args("", "0"));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
    }

    @Test
    void testEvalRedisPCall() {
        RedisMessage r = dispatcher.dispatch("EVAL", args("return redis.pcall('SET', KEYS[1], ARGV[1])", "1", "pcallkey", "pcallval"));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());

        r = dispatcher.dispatch("GET", args("pcallkey"));
        assertEquals("pcallval", r.asString());
    }

    @Test
    void testEvalCallWithoutReturn() {
        RedisMessage r = dispatcher.dispatch("EVAL", args("redis.call('SET', KEYS[1], ARGV[1])", "1", "nor etkey", "nor etval"));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());

        r = dispatcher.dispatch("GET", args("nor etkey"));
        assertEquals("nor etval", r.asString());
    }

    @Test
    void testEvalReturnStringLiteral() {
        RedisMessage r = dispatcher.dispatch("EVAL", args("return \"hello\"", "0"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertEquals("hello", r.asString());

        r = dispatcher.dispatch("EVAL", args("return 'world'", "0"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertEquals("world", r.asString());
    }

    @Test
    void testEvalParseArgsQuotes() {
        RedisMessage r = dispatcher.dispatch("EVAL", args(
                "return redis.call('SET', KEYS[1], ARGV[1])",
                "1", "quotekey", "hello,world"
        ));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());

        r = dispatcher.dispatch("GET", args("quotekey"));
        assertEquals("hello,world", r.asString());
    }

    @Test
    void testScriptExistsEmpty() {
        RedisMessage r = dispatcher.dispatch("SCRIPT", List.of(bulkString("EXISTS")));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
    }

    @Test
    void testEvalKeysOutOfRange() {
        RedisMessage r = dispatcher.dispatch("EVAL", args("return redis.call('SET', KEYS[0], ARGV[1])", "1", "mykey", "myval"));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
    }
}
