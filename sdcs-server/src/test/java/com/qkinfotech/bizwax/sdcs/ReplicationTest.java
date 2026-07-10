package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ReplicationTest {
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
    void testRole() {
        RedisMessage r = dispatcher.dispatch("ROLE", List.of());
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        assertEquals("master", r.getElements().get(0).asString());
    }

    @Test
    void testSlaveOfNoOne() {
        RedisMessage r = dispatcher.dispatch("SLAVEOF", List.of(bulkString("NO"), bulkString("ONE")));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());
    }

    @Test
    void testPsync() {
        RedisMessage r = dispatcher.dispatch("PSYNC", List.of(bulkString("?"), bulkString("-1")));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertNotNull(r.asString());
    }
}
