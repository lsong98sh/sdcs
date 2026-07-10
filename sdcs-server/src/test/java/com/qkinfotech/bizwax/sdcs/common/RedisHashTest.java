package com.qkinfotech.bizwax.sdcs.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedisHashTest {

    private byte[] bytes(String s) {
        return s.getBytes();
    }

    @Test
    void testHsetNewField() {
        RedisHash hash = new RedisHash();
        assertEquals(1, hash.hset("field1", bytes("value1")));
        assertTrue(Arrays.equals(bytes("value1"), hash.hget("field1")));
    }

    @Test
    void testHsetUpdateExistingField() {
        RedisHash hash = new RedisHash();
        hash.hset("field1", bytes("value1"));

        // updating existing field returns 0
        assertEquals(0, hash.hset("field1", bytes("value2")));
        assertTrue(Arrays.equals(bytes("value2"), hash.hget("field1")));
    }

    @Test
    void testHgetNonExistent() {
        RedisHash hash = new RedisHash();
        assertNull(hash.hget("nonexistent"));
    }

    @Test
    void testHincrByFloat() {
        RedisHash hash = new RedisHash();

        // hincrByFloat on non-existent field
        double result = hash.hincrByFloat("field1", 3.5);
        assertEquals(3.5, result, 1e-10);
        assertEquals("3.5", new String(hash.hget("field1")));

        // hincrByFloat on existing field
        result = hash.hincrByFloat("field1", 2.5);
        assertEquals(6.0, result, 1e-10);
        assertEquals("6.0", new String(hash.hget("field1")));

        // hincrByFloat with negative delta
        result = hash.hincrByFloat("field1", -1.5);
        assertEquals(4.5, result, 1e-10);
        assertEquals("4.5", new String(hash.hget("field1")));
    }

    @Test
    void testHincrByFloatThrowsOnInvalidValue() {
        RedisHash hash = new RedisHash();
        hash.hset("field1", bytes("not-a-float"));

        assertThrows(IllegalArgumentException.class, () -> hash.hincrByFloat("field1", 1.0),
                "ERR hash value is not a float");
    }

    @Test
    void testHstrlen() {
        RedisHash hash = new RedisHash();

        // non-existent field returns 0
        assertEquals(0, hash.hstrlen("nonexistent"));

        // existing field
        hash.hset("field1", bytes("hello"));
        assertEquals(5, hash.hstrlen("field1"));

        // empty value
        hash.hset("field2", bytes(""));
        assertEquals(0, hash.hstrlen("field2"));

        // unicode value (multi-byte)
        hash.hset("field3", "测试".getBytes());
        assertEquals(6, hash.hstrlen("field3"));  // 2 Chinese characters = 6 bytes in UTF-8
    }

    @Test
    void testHrandfield() {
        RedisHash hash = new RedisHash();
        hash.hset("field1", bytes("value1"));
        hash.hset("field2", bytes("value2"));
        hash.hset("field3", bytes("value3"));

        // positive count < size
        List<byte[]> result = hash.hrandfield(2);
        assertEquals(2, result.size());

        // positive count > size
        result = hash.hrandfield(10);
        assertEquals(3, result.size());

        // negative count (allow duplicates)
        result = hash.hrandfield(-10);
        assertEquals(10, result.size());

        // zero count
        result = hash.hrandfield(0);
        assertTrue(result.isEmpty());
    }

    @Test
    void testHrandfieldOnEmptyHash() {
        RedisHash hash = new RedisHash();
        List<byte[]> result = hash.hrandfield(1);
        assertTrue(result.isEmpty());

        // positive count on empty hash always returns empty
        result = hash.hrandfield(10);
        assertTrue(result.isEmpty());
    }

    @Test
    void testHdel() {
        RedisHash hash = new RedisHash();
        hash.hset("field1", bytes("value1"));
        hash.hset("field2", bytes("value2"));

        assertEquals(1, hash.hdel("field1"));
        assertNull(hash.hget("field1"));

        // deleting multiple
        assertEquals(1, hash.hdel("field2", "nonexistent"));
        assertEquals(0, hash.hdel("nonexistent"));
    }

    @Test
    void testHexists() {
        RedisHash hash = new RedisHash();
        assertFalse(hash.hexists("field1"));

        hash.hset("field1", bytes("value1"));
        assertTrue(hash.hexists("field1"));
        assertFalse(hash.hexists("nonexistent"));
    }

    @Test
    void testHlen() {
        RedisHash hash = new RedisHash();
        assertEquals(0, hash.hlen());

        hash.hset("field1", bytes("value1"));
        assertEquals(1, hash.hlen());

        hash.hset("field2", bytes("value2"));
        assertEquals(2, hash.hlen());
    }

    @Test
    void testHkeys() {
        RedisHash hash = new RedisHash();
        hash.hset("field1", bytes("value1"));
        hash.hset("field2", bytes("value2"));

        java.util.Set<String> keys = hash.hkeys();
        assertEquals(2, keys.size());
        assertTrue(keys.contains("field1"));
        assertTrue(keys.contains("field2"));
    }

    @Test
    void testHvals() {
        RedisHash hash = new RedisHash();
        hash.hset("field1", bytes("value1"));
        hash.hset("field2", bytes("value2"));

        List<byte[]> vals = hash.hvals();
        assertEquals(2, vals.size());
    }

    @Test
    void testHgetAll() {
        RedisHash hash = new RedisHash();
        hash.hset("field1", bytes("value1"));

        Map<String, byte[]> all = hash.hgetAll();
        assertEquals(1, all.size());
        assertTrue(Arrays.equals(bytes("value1"), all.get("field1")));
    }

    @Test
    void testHmset() {
        RedisHash hash = new RedisHash();
        Map<String, byte[]> fieldValues = Map.of(
                "field1", bytes("value1"),
                "field2", bytes("value2")
        );

        assertEquals(1, hash.hmset(fieldValues));
        assertTrue(Arrays.equals(bytes("value1"), hash.hget("field1")));
        assertTrue(Arrays.equals(bytes("value2"), hash.hget("field2")));
    }

    @Test
    void testHmget() {
        RedisHash hash = new RedisHash();
        hash.hset("field1", bytes("value1"));

        List<byte[]> result = hash.hmget("field1", "field2");
        assertEquals(2, result.size());
        assertTrue(Arrays.equals(bytes("value1"), result.get(0)));
        assertNull(result.get(1));
    }

    @Test
    void testHsetnx() {
        RedisHash hash = new RedisHash();

        // set on non-existent field
        assertEquals(1, hash.hsetnx("field1", bytes("value1")));
        assertTrue(Arrays.equals(bytes("value1"), hash.hget("field1")));

        // set on existing field returns 0
        assertEquals(0, hash.hsetnx("field1", bytes("value2")));
        assertTrue(Arrays.equals(bytes("value1"), hash.hget("field1")));  // value unchanged
    }

    @Test
    void testHincrBy() {
        RedisHash hash = new RedisHash();

        // on non-existent field
        assertEquals(5, hash.hincrBy("counter", 5));
        assertEquals("5", new String(hash.hget("counter")));

        // on existing field
        assertEquals(8, hash.hincrBy("counter", 3));
        assertEquals("8", new String(hash.hget("counter")));

        // negative delta
        assertEquals(3, hash.hincrBy("counter", -5));
        assertEquals("3", new String(hash.hget("counter")));
    }

    @Test
    void testScan() {
        RedisHash hash = new RedisHash();
        hash.hset("field1", bytes("value1"));
        hash.hset("field2", bytes("value2"));

        java.util.List<Object> result = hash.scan(0, 10);
        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof Long);
        assertEquals(0L, result.get(0));

        // cursor beyond total
        result = hash.scan(100, 10);
        assertEquals(2, result.size());
        assertEquals(0L, result.get(0));
    }

    @Test
    void testIsEmpty() {
        RedisHash hash = new RedisHash();
        assertTrue(hash.isEmpty());

        hash.hset("field1", bytes("value1"));
        assertFalse(hash.isEmpty());
    }

    @Test
    void testCombinedOperations() {
        RedisHash hash = new RedisHash();

        // hset new fields
        assertEquals(1, hash.hset("f1", bytes("v1")));
        assertEquals(1, hash.hset("f2", bytes("v2")));
        assertEquals(1, hash.hset("f3", bytes("v3")));

        // hset update
        assertEquals(0, hash.hset("f1", bytes("v1-updated")));
        assertTrue(Arrays.equals(bytes("v1-updated"), hash.hget("f1")));

        // hstrlen
        assertEquals(10, hash.hstrlen("f1"));  // "v1-updated" is 10 chars
        assertEquals(0, hash.hstrlen("nonexistent"));

        // hincrByFloat
        hash.hset("float-field", bytes("10.5"));
        assertEquals(15.7, hash.hincrByFloat("float-field", 5.2), 1e-10);

        // hrandfield
        List<byte[]> randomFields = hash.hrandfield(3);
        assertEquals(3, randomFields.size());
    }
}
