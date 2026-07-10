package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PubSubTest {

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
    void testPublishNoSubscribers() {
        RedisMessage r = dispatcher.dispatch("PUBLISH", args("ch1", "msg1"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(0L, r.getIntegerValue());
    }

    @Test
    void testSubscribeReturnsError() {
        RedisMessage r = dispatcher.dispatch("SUBSCRIBE", args("ch1"));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
        assertTrue(r.asString().contains("not supported"));
    }

    @Test
    void testPubSubChannelsEmpty() {
        RedisMessage r = dispatcher.dispatch("PUBSUB", List.of(bulkString("CHANNELS")));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        assertTrue(r.getElements().isEmpty());
    }

    @Test
    void testPubSubChannelsWithPattern() {
        RedisMessage r = dispatcher.dispatch("PUBSUB", List.of(bulkString("CHANNELS"), bulkString("*")));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        assertTrue(r.getElements().isEmpty());
    }

    @Test
    void testPubSubNumSub() {
        RedisMessage r = dispatcher.dispatch("PUBSUB", List.of(bulkString("NUMSUB"), bulkString("ch1")));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertEquals(2, elements.size());
        assertEquals("ch1", elements.get(0).asString());
        assertEquals(0L, elements.get(1).getIntegerValue());
    }

    @Test
    void testPublishAfterSubscribeNoEffect() {
        RedisMessage r = dispatcher.dispatch("SUBSCRIBE", args("ch2"));
        assertEquals(RedisMessage.Type.ERROR, r.getType());

        r = dispatcher.dispatch("PUBLISH", args("ch2", "hello"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(0L, r.getIntegerValue());
    }

    @Test
    void testPubSubNumPat() {
        RedisMessage r = dispatcher.dispatch("PUBSUB", List.of(bulkString("NUMPAT")));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(0L, r.getIntegerValue());
    }

    @Test
    void testPubSubInvalidSubcommand() {
        RedisMessage r = dispatcher.dispatch("PUBSUB", List.of(bulkString("INVALID")));
        assertEquals(RedisMessage.Type.ERROR, r.getType());
    }
}
