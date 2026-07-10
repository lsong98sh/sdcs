package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class StreamTest {

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
    void testXAddAndXLen() {
        RedisMessage r = dispatcher.dispatch("XADD", args("mystream", "*", "field1", "val1"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());

        r = dispatcher.dispatch("XLEN", args("mystream"));
        assertEquals(1L, r.getIntegerValue());
    }

    @Test
    void testXRange() {
        dispatcher.dispatch("XADD", args("srange", "1-0", "f", "v1"));
        dispatcher.dispatch("XADD", args("srange", "2-0", "f", "v2"));
        dispatcher.dispatch("XADD", args("srange", "3-0", "f", "v3"));

        RedisMessage r = dispatcher.dispatch("XRANGE", args("srange", "1-0", "3-0"));
        assertNotNull(r);
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
    }

    @Test
    void testXDel() {
        dispatcher.dispatch("XADD", args("sdel", "1-0", "f", "v1"));
        dispatcher.dispatch("XADD", args("sdel", "2-0", "f", "v2"));

        RedisMessage r = dispatcher.dispatch("XDEL", args("sdel", "1-0"));
        assertEquals(1L, r.getIntegerValue());

        r = dispatcher.dispatch("XLEN", args("sdel"));
        assertEquals(1L, r.getIntegerValue());
    }
}
