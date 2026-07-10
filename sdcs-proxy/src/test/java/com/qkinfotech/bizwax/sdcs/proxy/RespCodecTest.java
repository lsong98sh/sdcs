package com.qkinfotech.bizwax.sdcs.proxy;

import com.qkinfotech.bizwax.sdcs.proxy.server.RespCodec;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RespCodecTest {

    @Test
    void testDecodeArray() {
        List<RedisMessage> children = List.of(
                RedisMessage.bulkString("GET"),
                RedisMessage.bulkString("mykey"));
        RedisMessage msg = RedisMessage.array(children);

        List<String> result = RespCodec.decode(msg);
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("GET", result.get(0));
        assertEquals("mykey", result.get(1));
    }

    @Test
    void testDecodeArrayNull() {
        // Null array (null elements, Redis *-1)
        assertNull(RespCodec.decode(RedisMessage.array((List<RedisMessage>) null)));
    }

    @Test
    void testDecodeArrayEmpty() {
        List<RedisMessage> children = List.of();
        assertNull(RespCodec.decode(RedisMessage.array(children)));
    }

    @Test
    void testBulkStringEmpty() {
        assertEquals("", RespCodec.decodeBulkString(
                RedisMessage.bulkString(new byte[0])));
    }

    @Test
    void testError() {
        RedisMessage err = RespCodec.error("ERR test");
        assertEquals(RedisMessage.Type.ERROR, err.getType());
        // Our error adds "ERR " prefix
        assertTrue(err.asString().contains("test"));
    }

    @Test
    void testSimpleString() {
        assertEquals("OK", RespCodec.OK.asString());
    }

    @Test
    void testEncodeCommand() {
        RedisMessage cmd = RespCodec.encodeCommand("GET", List.of("mykey"));
        assertEquals(RedisMessage.Type.ARRAY, cmd.getType());
        List<RedisMessage> elements = cmd.getElements();
        assertEquals(2, elements.size());
        assertEquals("GET", elements.get(0).asString());
        assertEquals("mykey", elements.get(1).asString());
    }

    @Test
    void testDecodeInlineAsBulkString() {
        // Simulates inline command: RespDecoder produces ARRAY from inline,
        // but RespCodec also handles a plain bulk string as inline
        RedisMessage msg = RedisMessage.bulkString("PING");
        List<String> result = RespCodec.decode(msg);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("PING", result.get(0));
    }

    @Test
    void testDecodeInlineWithArgs() {
        RedisMessage msg = RedisMessage.bulkString("SET foo bar");
        List<String> result = RespCodec.decode(msg);
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("SET", result.get(0));
        assertEquals("foo", result.get(1));
        assertEquals("bar", result.get(2));
    }
}
