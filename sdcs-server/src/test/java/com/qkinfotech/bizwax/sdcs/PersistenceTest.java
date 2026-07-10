package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PersistenceTest {
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
    void testSave() {
        RedisMessage r = dispatcher.dispatch("SAVE", List.of());
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("ERR"));
    }

    @Test
    void testBgSave() {
        RedisMessage r = dispatcher.dispatch("BGSAVE", List.of());
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("ERR"));
    }

    @Test
    void testLastSave() {
        RedisMessage r = dispatcher.dispatch("LASTSAVE", List.of());
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertTrue(r.getIntegerValue() > 0);
    }

    @Test
    void testBgRewriteAof() {
        RedisMessage r = dispatcher.dispatch("BGREWRITEAOF", List.of());
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("ERR"));
    }
}
