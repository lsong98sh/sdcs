package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AuthTest {

    private DatabaseManager dbManager;
    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        new SDCSConfig();
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
    void testAuthWithoutPassword() {
        RedisMessage r = dispatcher.dispatch("AUTH", args("anything"));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("AUTH"));
    }

    @Test
    void testAuthCorrect() {
        SDCSConfig.getInstance().setRequirepass("test123");
        RedisMessage r = dispatcher.dispatch("AUTH", args("test123"));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("AUTH"));
        SDCSConfig.getInstance().setRequirepass(null);
    }

    @Test
    void testAuthIncorrect() {
        SDCSConfig.getInstance().setRequirepass("test123");
        RedisMessage r = dispatcher.dispatch("AUTH", args("wrong"));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("AUTH"));
        SDCSConfig.getInstance().setRequirepass(null);
    }

    @Test
    void testFlushDbRequiresForceWhenAuthSet() {
        SDCSConfig.getInstance().setRequirepass("test123");
        RedisMessage r = dispatcher.dispatch("FLUSHDB", List.of());
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("FORCE"));
        SDCSConfig.getInstance().setRequirepass(null);
    }

    @Test
    void testFlushDbWithForce() {
        SDCSConfig.getInstance().setRequirepass("test123");
        RedisMessage r = dispatcher.dispatch("FLUSHDB", List.of(bulkString("FORCE")));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());
        SDCSConfig.getInstance().setRequirepass(null);
    }

    @Test
    void testConfigSetRequirepassRequiresOldPass() {
        SDCSConfig.getInstance().setRequirepass("oldpass");
        RedisMessage r = dispatcher.dispatch("CONFIG", List.of(
                bulkString("SET"), bulkString("requirepass"), bulkString("newpass")
        ));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("old password"));
        SDCSConfig.getInstance().setRequirepass(null);
    }

    @Test
    void testFlushAllRequiresForceWhenAuthSet() {
        SDCSConfig.getInstance().setRequirepass("test123");
        RedisMessage r = dispatcher.dispatch("FLUSHALL", List.of());
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("FORCE"));
        SDCSConfig.getInstance().setRequirepass(null);
    }

    @Test
    void testFlushAllWithForce() {
        SDCSConfig.getInstance().setRequirepass("test123");
        dispatcher.dispatch("SET", args("keep", "val"));
        RedisMessage r = dispatcher.dispatch("FLUSHALL", List.of(bulkString("FORCE")));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());
        RedisMessage get = dispatcher.dispatch("GET", args("keep"));
        assertTrue(get.isNullBulkString());
        SDCSConfig.getInstance().setRequirepass(null);
    }

    @Test
    void testConfigGetRequirepassMasked() {
        SDCSConfig.getInstance().setRequirepass("secret123");
        RedisMessage r = dispatcher.dispatch("CONFIG", List.of(
                bulkString("GET"), bulkString("requirepass")
        ));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elems = r.getElements();
        assertEquals(2, elems.size());
        assertEquals("requirepass", elems.get(0).asString());
        assertEquals("****", elems.get(1).asString());
        SDCSConfig.getInstance().setRequirepass(null);
    }

    @Test
    void testConfigSetRequirepassWithOldPass() {
        SDCSConfig.getInstance().setRequirepass("oldpass");
        RedisMessage r = dispatcher.dispatch("CONFIG", List.of(
                bulkString("SET"), bulkString("requirepass"),
                bulkString("newpass"), bulkString("oldpass")
        ));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());
        assertEquals("newpass", SDCSConfig.getInstance().getRequirepass());
        SDCSConfig.getInstance().setRequirepass(null);
    }

    @Test
    void testConfigSetRequirepassWithWrongOldPass() {
        SDCSConfig.getInstance().setRequirepass("oldpass");
        RedisMessage r = dispatcher.dispatch("CONFIG", List.of(
                bulkString("SET"), bulkString("requirepass"),
                bulkString("newpass"), bulkString("wrongpass")
        ));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("not match"));
        SDCSConfig.getInstance().setRequirepass(null);
    }

    @AfterEach
    void tearDown() {
        SDCSConfig.getInstance().setRequirepass(null);
    }
}
