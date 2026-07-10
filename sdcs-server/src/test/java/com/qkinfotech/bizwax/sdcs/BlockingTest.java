package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BlockingTest {

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
    void testBLPopWithData() {
        dispatcher.dispatch("LPUSH", args("blist", "a", "b", "c"));
        RedisMessage r = dispatcher.dispatch("BLPOP", args("blist", "1"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertNotNull(elements);
        assertEquals(2, elements.size());
        assertEquals("blist", elements.get(0).asString());
        assertEquals("c", elements.get(1).asString());
    }

    @Test
    void testBLPopTimeoutReturnsNil() {
        RedisMessage r = dispatcher.dispatch("BLPOP", args("nonexistent", "0"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        assertNull(r.getElements());
    }

    @Test
    void testBLPopRespectsOrder() {
        dispatcher.dispatch("LPUSH", args("bordered", "x", "y"));
        RedisMessage r = dispatcher.dispatch("BLPOP", args("bordered", "1"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertNotNull(elements);
        assertEquals("y", elements.get(1).asString());

        r = dispatcher.dispatch("BLPOP", args("bordered", "1"));
        elements = r.getElements();
        assertNotNull(elements);
        assertEquals("x", elements.get(1).asString());

        r = dispatcher.dispatch("BLPOP", args("bordered", "1"));
        assertNull(r.getElements());
    }

    @Test
    void testBRPopWithData() {
        dispatcher.dispatch("RPUSH", args("brlist", "a", "b", "c"));
        RedisMessage r = dispatcher.dispatch("BRPOP", args("brlist", "1"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertNotNull(elements);
        assertEquals(2, elements.size());
        assertEquals("brlist", elements.get(0).asString());
        assertEquals("c", elements.get(1).asString());
    }

    @Test
    void testBRPopTimeoutReturnsNil() {
        RedisMessage r = dispatcher.dispatch("BRPOP", args("nonexistent", "0"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        assertNull(r.getElements());
    }

    @Test
    void testBLPopMultipleKeys() {
        dispatcher.dispatch("LPUSH", args("k1", "v1"));
        RedisMessage r = dispatcher.dispatch("BLPOP", args("k1", "k2", "1"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertNotNull(elements);
        assertEquals(2, elements.size());
        assertEquals("k1", elements.get(0).asString());
        assertEquals("v1", elements.get(1).asString());
    }

    @Test
    void testBZPopMinNotYetSupported() {
        RedisMessage r = dispatcher.dispatch("BZPOPMIN", args("z", "1"));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("not yet supported"));
    }

    @Test
    void testBZPopMaxNotYetSupported() {
        RedisMessage r = dispatcher.dispatch("BZPOPMAX", args("z", "1"));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("not yet supported"));
    }
}
