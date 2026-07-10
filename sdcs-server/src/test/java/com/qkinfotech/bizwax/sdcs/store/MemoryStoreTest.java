package com.qkinfotech.bizwax.sdcs.store;

import com.qkinfotech.bizwax.sdcs.common.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
    }

    @Test
    void testPutAndGet() {
        assertNull(store.get("key1"));
        store.put("key1", "value1".getBytes());
        assertArrayEquals("value1".getBytes(), store.get("key1"));
    }

    @Test
    void testPutWithExpiry() {
        long expireAt = System.currentTimeMillis() + 1000;
        store.put("key1", "value1".getBytes(), expireAt);
        assertArrayEquals("value1".getBytes(), store.get("key1"));
    }

    @Test
    void testIncr() {
        assertEquals(1, store.incr("counter"));
        assertEquals(2, store.incr("counter"));
    }

    @Test
    void testIncrBy() {
        assertEquals(10, store.incrBy("counter", 10));
        assertEquals(15, store.incrBy("counter", 5));
    }

    @Test
    void testDecr() {
        store.put("counter", "10".getBytes());
        assertEquals(9, store.decr("counter"));
    }

    @Test
    void testExists() {
        store.put("key", "value".getBytes());
        assertTrue(store.exists("key"));
        assertFalse(store.exists("nonexistent"));
    }

    // --- List operations ---

    @Test
    void testLPushAndLPop() {
        assertEquals(1, store.lpush("list", "a".getBytes()));
        assertEquals(2, store.lpush("list", "b".getBytes()));
        assertArrayEquals("b".getBytes(), store.lpop("list"));
        assertArrayEquals("a".getBytes(), store.lpop("list"));
        assertNull(store.lpop("list"));
    }

    @Test
    void testRPushAndRPop() {
        store.rpush("list", "a".getBytes());
        store.rpush("list", "b".getBytes());
        assertArrayEquals("b".getBytes(), store.rpop("list"));
        assertArrayEquals("a".getBytes(), store.rpop("list"));
        assertNull(store.rpop("list"));
    }

    @Test
    void testLRange() {
        store.rpush("list", "a".getBytes());
        store.rpush("list", "b".getBytes());
        store.rpush("list", "c".getBytes());

        List<byte[]> range = store.lrange("list", 0, -1);
        assertEquals(3, range.size());
        assertArrayEquals("a".getBytes(), range.get(0));
        assertArrayEquals("c".getBytes(), range.get(2));

        range = store.lrange("list", 0, 1);
        assertEquals(2, range.size());
    }

    @Test
    void testLLen() {
        assertEquals(0, store.llen("list"));
        store.rpush("list", "a".getBytes());
        assertEquals(1, store.llen("list"));
    }

    @Test
    void testLIndex() {
        assertNull(store.lindex("list", 0));
        store.rpush("list", "a".getBytes());
        store.rpush("list", "b".getBytes());
        assertArrayEquals("a".getBytes(), store.lindex("list", 0));
        assertArrayEquals("b".getBytes(), store.lindex("list", 1));
        assertNull(store.lindex("list", 5));
    }

    // --- Hash operations ---

    @Test
    void testHSetAndHGet() {
        assertEquals(1, store.hset("hash", "field1", "value1".getBytes()));
        assertEquals(0, store.hset("hash", "field1", "value2".getBytes()));
        assertArrayEquals("value2".getBytes(), store.hget("hash", "field1"));
        assertNull(store.hget("hash", "nonexistent"));
    }

    @Test
    void testHDel() {
        store.hset("hash", "f1", "v1".getBytes());
        store.hset("hash", "f2", "v2".getBytes());
        assertEquals(1, store.hdel("hash", "f1"));
        assertEquals(0, store.hdel("hash", "f1"));
        assertEquals(1, store.hlen("hash"));
    }

    @Test
    void testHLen() {
        assertEquals(0, store.hlen("hash"));
        store.hset("hash", "f1", "v1".getBytes());
        assertEquals(1, store.hlen("hash"));
    }

    @Test
    void testHGetAll() {
        store.hset("hash", "f1", "v1".getBytes());
        store.hset("hash", "f2", "v2".getBytes());
        RedisHash hash = store.getHash("hash");
        assertNotNull(hash);
        var all = hash.hgetAll();
        assertEquals(2, all.size());
        assertArrayEquals("v1".getBytes(), all.get("f1"));
    }

    // --- Set operations ---

    @Test
    void testSAddAndSCard() {
        assertEquals(1, store.sadd("set", "a".getBytes()));
        assertEquals(1, store.sadd("set", "b".getBytes()));
        assertEquals(0, store.sadd("set", "a".getBytes()));
        assertEquals(2, store.scard("set"));
    }

    @Test
    void testSRem() {
        store.sadd("set", "a".getBytes());
        store.sadd("set", "b".getBytes());
        assertEquals(1, store.srem("set", "a".getBytes()));
        assertEquals(1, store.scard("set"));
    }

    @Test
    void testSIsMember() {
        store.sadd("set", "a".getBytes());
        assertTrue(store.sismember("set", "a".getBytes()));
        assertFalse(store.sismember("set", "b".getBytes()));
    }

    // --- ZSet operations ---

    @Test
    void testZAddAndZScore() {
        assertEquals(1, store.zadd("zset", 1.0, "a".getBytes()));
        assertEquals(0, store.zadd("zset", 1.0, "a".getBytes()));
        assertEquals(1, store.zadd("zset", 2.0, "b".getBytes()));

        assertEquals(1.0, store.zscore("zset", "a".getBytes()), 0.001);
        assertNull(store.zscore("zset", "nonexistent".getBytes()));
    }

    @Test
    void testZRange() {
        store.zadd("zset", 1.0, "a".getBytes());
        store.zadd("zset", 2.0, "b".getBytes());
        store.zadd("zset", 3.0, "c".getBytes());

        List<RedisZSet.ZSetEntry> range = store.zrange("zset", 0, -1);
        assertEquals(3, range.size());

        range = store.zrange("zset", 0, 1);
        assertEquals(2, range.size());
    }

    @Test
    void testZRem() {
        store.zadd("zset", 1.0, "a".getBytes());
        store.zadd("zset", 2.0, "b".getBytes());
        assertEquals(1, store.zrem("zset", "a".getBytes()));
        assertEquals(1, store.zcard("zset"));
    }

    // --- Basic operations ---

    @Test
    void testType() {
        store.put("str", "val".getBytes());
        assertEquals("string", store.type("str"));
        assertEquals("none", store.type("nonexistent"));
    }

    @Test
    void testRemove() {
        store.put("key", "value".getBytes());
        assertTrue(store.remove("key"));
        assertFalse(store.remove("key"));
        assertNull(store.get("key"));
    }

    @Test
    void testDeleteExpired() {
        store.put("key", "value".getBytes(), System.currentTimeMillis() - 1000);
        store.deleteExpired();
        assertNull(store.get("key"));
    }
}
