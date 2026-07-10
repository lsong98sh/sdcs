package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.transaction.TransactionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommandDispatcher 指令覆盖测试。
 * <p>
 * 直接通过 dispatch() 测试各种 Redis 命令，验证返回值类型和内容。
 */
class CommandDispatcherTest {

    private DatabaseManager dbManager;
    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // 初始化 SDCSConfig 单例（CONFIG、FLUSHDB、FLUSHALL 等需要）
        new SDCSConfig();
        dbManager = new DatabaseManager();
        dispatcher = new CommandDispatcher(dbManager, null);
        TransactionManager.cleanup();
    }

    @AfterEach
    void tearDown() {
        TransactionManager.cleanup();
    }

    // ==================== 辅助方法 ====================

    private RedisMessage cmd(String name, String... args) {
        List<RedisMessage> list = java.util.Arrays.stream(args)
                .map(s -> RedisMessage.bulkString(s.getBytes()))
                .toList();
        return dispatcher.dispatch(name, list);
    }

    private RedisMessage cmdRaw(String name, RedisMessage... args) {
        return dispatcher.dispatch(name, java.util.Arrays.asList(args));
    }

    private String asString(RedisMessage msg) {
        return msg != null ? msg.asString() : null;
    }

    private long asInt(RedisMessage msg) {
        return msg != null ? msg.getIntegerValue() : -1;
    }

    // ==================== String 命令 ====================

    @Test
    void testPing() {
        assertEquals("PONG", asString(cmd("PING")));
    }

    @Test
    void testPingWithArg() {
        assertEquals("hello", asString(cmd("PING", "hello")));
    }

    @Test
    void testEcho() {
        assertArrayEquals("hello".getBytes(), cmd("ECHO", "hello").getData());
    }

    @Test
    void testSetAndGet() {
        assertEquals("OK", asString(cmd("SET", "k1", "v1")));
        assertEquals("v1", asString(cmd("GET", "k1")));
    }

    @Test
    void testGetNonExistent() {
        assertTrue(cmd("GET", "nonexistent").isNullBulkString());
    }

    @Test
    void testSetWithNx() {
        // NX — key 不存在时设置
        assertEquals("OK", asString(cmdRaw("SET", RedisMessage.bulkString("k_nx".getBytes()),
                RedisMessage.bulkString("v".getBytes()), RedisMessage.bulkString("NX".getBytes()))));
        // NX — key 已存在时返回 null
        assertTrue(cmdRaw("SET", RedisMessage.bulkString("k_nx".getBytes()),
                RedisMessage.bulkString("v2".getBytes()), RedisMessage.bulkString("NX".getBytes())).isNullBulkString());
    }

    @Test
    void testSetWithXx() {
        // XX — key 不存在时返回 null
        assertTrue(cmdRaw("SET", RedisMessage.bulkString("k_xx".getBytes()),
                RedisMessage.bulkString("v".getBytes()), RedisMessage.bulkString("XX".getBytes())).isNullBulkString());
        // EX 后 XX
        cmd("SET", "k_xx", "old");
        assertEquals("OK", asString(cmdRaw("SET", RedisMessage.bulkString("k_xx".getBytes()),
                RedisMessage.bulkString("new".getBytes()), RedisMessage.bulkString("XX".getBytes()))));
    }

    @Test
    void testSetWithEx() {
        assertEquals("OK", asString(cmdRaw("SET", RedisMessage.bulkString("k_ex".getBytes()),
                RedisMessage.bulkString("v".getBytes()), RedisMessage.bulkString("EX".getBytes()),
                RedisMessage.bulkString("10".getBytes()))));
        assertEquals("v", asString(cmd("GET", "k_ex")));
    }

    @Test
    void testSetWithPx() {
        assertEquals("OK", asString(cmdRaw("SET", RedisMessage.bulkString("k_px".getBytes()),
                RedisMessage.bulkString("v".getBytes()), RedisMessage.bulkString("PX".getBytes()),
                RedisMessage.bulkString("10000".getBytes()))));
        assertEquals("v", asString(cmd("GET", "k_px")));
    }

    @Test
    void testGetSet() {
        cmd("SET", "gs", "old");
        assertEquals("old", asString(cmd("GETSET", "gs", "new")));
        assertEquals("new", asString(cmd("GET", "gs")));
    }

    @Test
    void testGetDel() {
        cmd("SET", "gd", "val");
        assertEquals("val", asString(cmd("GETDEL", "gd")));
        assertTrue(cmd("GET", "gd").isNullBulkString());
    }

    @Test
    void testGetEx() {
        cmd("SET", "ge", "val");
        // GETEX key PERSIST — 获取值同时移除过期时间
        assertEquals("val", asString(cmdRaw("GETEX", RedisMessage.bulkString("ge".getBytes()),
                RedisMessage.bulkString("PERSIST".getBytes()))));
        assertEquals("val", asString(cmd("GET", "ge")));
    }

    @Test
    void testGetExWithEx() {
        cmd("SET", "ge2", "val");
        assertEquals("val", asString(cmdRaw("GETEX", RedisMessage.bulkString("ge2".getBytes()),
                RedisMessage.bulkString("EX".getBytes()), RedisMessage.bulkString("100".getBytes()))));
    }

    @Test
    void testSetEx() {
        assertEquals("OK", asString(cmd("SETEX", "sex", "10", "val")));
        assertEquals("val", asString(cmd("GET", "sex")));
    }

    @Test
    void testPSetEx() {
        assertEquals("OK", asString(cmd("PSETEX", "psex", "10000", "val")));
        assertEquals("val", asString(cmd("GET", "psex")));
    }

    @Test
    void testMGet() {
        cmd("SET", "a", "1");
        cmd("SET", "b", "2");
        RedisMessage r = cmd("MGET", "a", "b", "c");
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        assertEquals(3, r.getElements().size());
        assertEquals("1", r.getElements().get(0).asString());
        assertEquals("2", r.getElements().get(1).asString());
        assertTrue(r.getElements().get(2).isNullBulkString());
    }

    @Test
    void testMSet() {
        assertEquals("OK", asString(cmd("MSET", "x", "1", "y", "2")));
        assertEquals("1", asString(cmd("GET", "x")));
        assertEquals("2", asString(cmd("GET", "y")));
    }

    @Test
    void testMSetNx() {
        cmd("SET", "z", "old");
        assertEquals(0L, asInt(cmd("MSETNX", "z", "new", "w", "val")));
        assertEquals("old", asString(cmd("GET", "z")));
        assertTrue(cmd("GET", "w").isNullBulkString());
    }

    @Test
    void testMSetNxAllNew() {
        assertEquals(1L, asInt(cmd("MSETNX", "new1", "a", "new2", "b")));
    }

    // ==================== 删除/存在 ====================

    @Test
    void testDel() {
        cmd("SET", "d1", "v");
        cmd("SET", "d2", "v");
        assertEquals(2L, asInt(cmd("DEL", "d1", "d2", "d3")));
        assertTrue(cmd("GET", "d1").isNullBulkString());
    }

    @Test
    void testExists() {
        cmd("SET", "e1", "v");
        assertEquals(1L, asInt(cmd("EXISTS", "e1")));
        assertEquals(0L, asInt(cmd("EXISTS", "e2")));
    }

    // ==================== 数值命令 ====================

    @Test
    void testIncr() {
        assertEquals(1L, asInt(cmd("INCR", "c1")));
        assertEquals(2L, asInt(cmd("INCR", "c1")));
    }

    @Test
    void testIncrBy() {
        assertEquals(10L, asInt(cmd("INCRBY", "c2", "10")));
        assertEquals(15L, asInt(cmd("INCRBY", "c2", "5")));
    }

    @Test
    void testDecr() {
        cmd("SET", "c3", "10");
        assertEquals(9L, asInt(cmd("DECR", "c3")));
    }

    @Test
    void testDecrBy() {
        cmd("SET", "c4", "20");
        assertEquals(15L, asInt(cmd("DECRBY", "c4", "5")));
    }

    @Test
    void testIncrByFloat() {
        assertEquals("1.5", asString(cmd("INCRBYFLOAT", "f1", "1.5")));
    }

    @Test
    void testAppend() {
        cmd("SET", "ap", "hello");
        assertEquals(10L, asInt(cmd("APPEND", "ap", "world"))); // hello(5) + world(5) = 10
        assertEquals("helloworld", asString(cmd("GET", "ap")));
    }

    @Test
    void testStrLen() {
        cmd("SET", "sl", "hello");
        assertEquals(5L, asInt(cmd("STRLEN", "sl")));
        assertEquals(0L, asInt(cmd("STRLEN", "nonexistent")));
    }

    // ==================== Key 操作 ====================

    @Test
    void testKeys() {
        cmd("SET", "ka", "1");
        cmd("SET", "kb", "2");
        RedisMessage r = cmd("KEYS", "*");
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        assertTrue(r.getElements().size() >= 2);
    }

    @Test
    void testDbSize() {
        cmd("SET", "ds1", "v");
        cmd("SET", "ds2", "v");
        assertTrue(asInt(cmd("DBSIZE")) >= 2);
    }

    @Test
    void testType() {
        cmd("SET", "t1", "v");
        assertEquals("string", asString(cmd("TYPE", "t1")));
        assertEquals("none", asString(cmd("TYPE", "nonexistent")));
    }

    @Test
    void testRename() {
        cmd("SET", "old", "val");
        assertEquals("OK", asString(cmd("RENAME", "old", "new")));
        assertTrue(cmd("GET", "old").isNullBulkString());
        assertEquals("val", asString(cmd("GET", "new")));
    }

    @Test
    void testRenameNx() {
        cmd("SET", "rn1", "val");
        assertEquals(1L, asInt(cmd("RENAMENX", "rn1", "rn2")));
    }

    @Test
    void testRenameNxExistingDest() {
        cmd("SET", "rn3", "a");
        cmd("SET", "rn4", "b");
        assertEquals(0L, asInt(cmd("RENAMENX", "rn3", "rn4")));
    }

    @Test
    void testRandomKey() {
        cmd("SET", "rk1", "v");
        assertNotNull(asString(cmd("RANDOMKEY")));
    }

    @Test
    void testTouch() {
        cmd("SET", "tc1", "v");
        assertEquals(1L, asInt(cmd("TOUCH", "tc1", "nonexistent")));
    }

    @Test
    void testCopy() {
        cmd("SET", "src", "val");
        assertEquals(1L, asInt(cmd("COPY", "src", "dst")));
        assertEquals("val", asString(cmd("GET", "dst")));
    }

    @Test
    void testMove() {
        cmd("SET", "mv1", "val");
        assertEquals(1L, asInt(cmd("MOVE", "mv1", "1")));
        assertTrue(cmd("GET", "mv1").isNullBulkString());
    }

    // ==================== 过期命令 ====================

    @Test
    void testExpireAndTTL() {
        cmd("SET", "ex1", "v");
        assertEquals(1L, asInt(cmd("EXPIRE", "ex1", "100")));
        assertTrue(asInt(cmd("TTL", "ex1")) > 0);
    }

    @Test
    void testPExpireAndPTTL() {
        cmd("SET", "pex1", "v");
        assertEquals(1L, asInt(cmd("PEXPIRE", "pex1", "100000")));
        assertTrue(asInt(cmd("PTTL", "pex1")) > 0);
    }

    @Test
    void testExpireAt() {
        cmd("SET", "ea1", "v");
        assertEquals(1L, asInt(cmd("EXPIREAT", "ea1", String.valueOf(System.currentTimeMillis() / 1000 + 100))));
    }

    @Test
    void testPExpireAt() {
        cmd("SET", "pea1", "v");
        assertEquals(1L, asInt(cmd("PEXPIREAT", "pea1", String.valueOf(System.currentTimeMillis() + 100000))));
    }

    @Test
    void testPersist() {
        cmd("SET", "per1", "v");
        cmd("EXPIRE", "per1", "100");
        assertEquals(1L, asInt(cmd("PERSIST", "per1")));
        assertEquals(-1L, asInt(cmd("TTL", "per1")));
    }

    @Test
    void testTTLNonExistent() {
        assertEquals(-2L, asInt(cmd("TTL", "nonexistent")));
    }

    // ==================== List 命令 ====================

    @Test
    void testListPushAndPop() {
        assertEquals(1L, asInt(cmd("LPUSH", "list1", "a")));
        assertEquals(2L, asInt(cmd("LPUSH", "list1", "b")));
        assertEquals("b", asString(cmd("LPOP", "list1")));
        assertEquals("a", asString(cmd("RPOP", "list1")));
    }

    @Test
    void testRPush() {
        assertEquals(1L, asInt(cmd("RPUSH", "rlist", "x")));
        assertEquals(2L, asInt(cmd("RPUSH", "rlist", "y")));
    }

    @Test
    void testLLen() {
        cmd("RPUSH", "ll", "a", "b", "c");
        assertEquals(3L, asInt(cmd("LLEN", "ll")));
        assertEquals(0L, asInt(cmd("LLEN", "nonexistent")));
    }

    @Test
    void testLRange() {
        cmd("RPUSH", "lr", "a", "b", "c", "d");
        RedisMessage r = cmd("LRANGE", "lr", "1", "2");
        assertEquals(2, r.getElements().size());
        assertEquals("b", r.getElements().get(0).asString());
    }

    @Test
    void testLIndex() {
        cmd("RPUSH", "li", "a", "b", "c");
        assertEquals("a", asString(cmd("LINDEX", "li", "0")));
        assertTrue(cmd("LINDEX", "li", "100").isNullBulkString());
    }

    @Test
    void testLSet() {
        cmd("RPUSH", "ls", "a", "b");
        assertEquals("OK", asString(cmd("LSET", "ls", "0", "x")));
        assertEquals("x", asString(cmd("LINDEX", "ls", "0")));
    }

    @Test
    void testLRem() {
        cmd("RPUSH", "lrem", "a", "b", "a", "c");
        // count=0 删除所有匹配元素
        assertEquals(2L, asInt(cmd("LREM", "lrem", "0", "a")));
        assertEquals(2L, asInt(cmd("LLEN", "lrem")));
    }

    @Test
    void testLTrim() {
        cmd("RPUSH", "lt", "a", "b", "c", "d");
        assertEquals("OK", asString(cmd("LTRIM", "lt", "1", "2")));
        assertEquals(2L, asInt(cmd("LLEN", "lt")));
    }

    @Test
    void testLInsert() {
        cmd("RPUSH", "li2", "a", "c");
        assertEquals(3L, asInt(cmd("LINSERT", "li2", "BEFORE", "c", "b")));
        assertEquals("b", asString(cmd("LINDEX", "li2", "1")));
    }

    @Test
    void testLPushX() {
        assertEquals(0L, asInt(cmd("LPUSHX", "lpx", "a")));
        cmd("RPUSH", "lpx", "b");
        assertEquals(2L, asInt(cmd("LPUSHX", "lpx", "a")));
    }

    @Test
    void testRPushX() {
        assertEquals(0L, asInt(cmd("RPUSHX", "rpx", "a")));
        cmd("RPUSH", "rpx", "b");
        assertEquals(2L, asInt(cmd("RPUSHX", "rpx", "c")));
    }

    @Test
    void testLPos() {
        cmd("RPUSH", "lp", "a", "b", "a", "c");
        assertEquals(0L, cmd("LPOS", "lp", "a").getIntegerValue());
    }

    // ==================== Hash 命令 ====================

    @Test
    void testHSetAndHGet() {
        assertEquals(1L, asInt(cmd("HSET", "h1", "f1", "v1")));
        assertEquals("v1", asString(cmd("HGET", "h1", "f1")));
    }

    @Test
    void testHDel() {
        cmd("HSET", "h2", "f1", "v1");
        cmd("HSET", "h2", "f2", "v2");
        assertEquals(1L, asInt(cmd("HDEL", "h2", "f1")));
        assertNull(asString(cmd("HGET", "h2", "f1")));
    }

    @Test
    void testHLen() {
        cmd("HSET", "h3", "f1", "v1");
        cmd("HSET", "h3", "f2", "v2");
        assertEquals(2L, asInt(cmd("HLEN", "h3")));
        assertEquals(0L, asInt(cmd("HLEN", "nonexistent")));
    }

    @Test
    void testHExists() {
        cmd("HSET", "h4", "f1", "v1");
        assertEquals(1L, asInt(cmd("HEXISTS", "h4", "f1")));
        assertEquals(0L, asInt(cmd("HEXISTS", "h4", "f2")));
    }

    @Test
    void testHGetAll() {
        cmd("HSET", "h5", "f1", "v1");
        cmd("HSET", "h5", "f2", "v2");
        assertEquals(4, cmd("HGETALL", "h5").getElements().size());
    }

    @Test
    void testHKeys() {
        cmd("HSET", "h6", "f1", "v1");
        cmd("HSET", "h6", "f2", "v2");
        assertTrue(cmd("HKEYS", "h6").getElements().size() >= 2);
    }

    @Test
    void testHVals() {
        cmd("HSET", "h7", "f1", "v1");
        cmd("HSET", "h7", "f2", "v2");
        assertTrue(cmd("HVALS", "h7").getElements().size() >= 2);
    }

    @Test
    void testHMSetAndHMGet() {
        assertEquals("OK", asString(cmd("HMSET", "h8", "f1", "v1", "f2", "v2")));
        RedisMessage r = cmd("HMGET", "h8", "f1", "f2", "f3");
        assertEquals("v1", r.getElements().get(0).asString());
        assertEquals("v2", r.getElements().get(1).asString());
        // 不存在的 field 返回 null bulk string
        assertTrue(r.getElements().get(2).isNullBulkString());
    }

    @Test
    void testHSetNx() {
        cmd("HSET", "h9", "f1", "v1");
        assertEquals(0L, asInt(cmd("HSETNX", "h9", "f1", "v2")));
        assertEquals(1L, asInt(cmd("HSETNX", "h9", "f2", "v2")));
    }

    @Test
    void testHIncrBy() {
        assertEquals(10L, asInt(cmd("HINCRBY", "h10", "f1", "10")));
        assertEquals(15L, asInt(cmd("HINCRBY", "h10", "f1", "5")));
    }

    @Test
    void testHStrLen() {
        cmd("HSET", "h11", "f1", "hello");
        assertEquals(5L, asInt(cmd("HSTRLEN", "h11", "f1")));
    }

    @Test
    void testHRandField() {
        cmd("HSET", "h12", "f1", "v1");
        cmd("HSET", "h12", "f2", "v2");
        assertTrue(cmd("HRANDFIELD", "h12", "1").getElements().size() >= 1);
    }

    @Test
    void testHIncrByFloat() {
        assertEquals("1.5", asString(cmd("HINCRBYFLOAT", "h13", "f1", "1.5")));
    }

    // ==================== Set 命令 ====================

    @Test
    void testSAdd() {
        assertEquals(1L, asInt(cmd("SADD", "s1", "a")));
        assertEquals(1L, asInt(cmd("SADD", "s1", "b"))); // 返回新增数量
    }

    @Test
    void testSMembers() {
        cmd("SADD", "sm1", "a", "b");
        assertTrue(cmd("SMEMBERS", "sm1").getElements().size() >= 2);
    }

    @Test
    void testSRem() {
        cmd("SADD", "s2", "a", "b", "c");
        assertEquals(1L, asInt(cmd("SREM", "s2", "a")));
    }

    @Test
    void testSIsMember() {
        cmd("SADD", "s3", "a");
        assertEquals(1L, asInt(cmd("SISMEMBER", "s3", "a")));
        assertEquals(0L, asInt(cmd("SISMEMBER", "s3", "b")));
    }

    @Test
    void testSCard() {
        assertEquals(0L, asInt(cmd("SCARD", "nonexistent")));
        cmd("SADD", "s4", "a", "b", "c");
        assertEquals(3L, asInt(cmd("SCARD", "s4")));
    }

    @Test
    void testSPop() {
        cmd("SADD", "s5", "a", "b");
        assertNotNull(asString(cmd("SPOP", "s5")));
    }

    @Test
    void testSRandMember() {
        cmd("SADD", "s6", "a", "b", "c");
        assertNotNull(asString(cmd("SRANDMEMBER", "s6")));
    }

    @Test
    void testSMIsMember() {
        cmd("SADD", "s7", "a", "b");
        RedisMessage r = cmd("SMISMEMBER", "s7", "a", "c");
        assertEquals(1L, r.getElements().get(0).getIntegerValue());
        assertEquals(0L, r.getElements().get(1).getIntegerValue());
    }

    // ==================== ZSet 命令 ====================

    @Test
    void testZAddAndZCard() {
        cmd("ZADD", "z1", "1.0", "a");
        cmd("ZADD", "z1", "2.0", "b");
        assertEquals(2L, asInt(cmd("ZCARD", "z1")));
    }

    @Test
    void testZScore() {
        cmd("ZADD", "z2", "3.5", "a");
        assertEquals("3.5", asString(cmd("ZSCORE", "z2", "a")));
    }

    @Test
    void testZRank() {
        cmd("ZADD", "z3", "1.0", "a");
        cmd("ZADD", "z3", "2.0", "b");
        assertEquals(0L, asInt(cmd("ZRANK", "z3", "a")));
    }

    @Test
    void testZRevRank() {
        cmd("ZADD", "z4", "1.0", "a");
        cmd("ZADD", "z4", "2.0", "b");
        assertEquals(1L, asInt(cmd("ZREVRANK", "z4", "a")));
        assertEquals(0L, asInt(cmd("ZREVRANK", "z4", "b")));
    }

    @Test
    void testZRem() {
        cmd("ZADD", "z5", "1.0", "a");
        cmd("ZADD", "z5", "2.0", "b");
        assertEquals(1L, asInt(cmd("ZREM", "z5", "a")));
    }

    @Test
    void testZRange() {
        cmd("ZADD", "z6", "1.0", "a");
        cmd("ZADD", "z6", "2.0", "b");
        cmd("ZADD", "z6", "3.0", "c");
        assertTrue(cmd("ZRANGE", "z6", "0", "-1").getElements().size() >= 3);
    }

    @Test
    void testZRevRange() {
        cmd("ZADD", "z7", "1.0", "a");
        cmd("ZADD", "z7", "2.0", "b");
        assertEquals("b", cmd("ZREVRANGE", "z7", "0", "-1").getElements().get(0).asString());
    }

    @Test
    void testZCount() {
        cmd("ZADD", "z8", "1.0", "a");
        cmd("ZADD", "z8", "2.0", "b");
        cmd("ZADD", "z8", "3.0", "c");
        assertEquals(2L, asInt(cmd("ZCOUNT", "z8", "1", "2")));
    }

    @Test
    void testZPopMin() {
        cmd("ZADD", "z9", "1.0", "a");
        cmd("ZADD", "z9", "2.0", "b");
        assertEquals("a", cmd("ZPOPMIN", "z9", "1").getElements().get(0).asString());
    }

    @Test
    void testZPopMax() {
        cmd("ZADD", "z10", "1.0", "a");
        cmd("ZADD", "z10", "2.0", "b");
        assertEquals("b", cmd("ZPOPMAX", "z10", "1").getElements().get(0).asString());
    }

    @Test
    void testZIncrBy() {
        cmd("ZADD", "z11", "1.0", "a");
        assertEquals("3.5", asString(cmd("ZINCRBY", "z11", "2.5", "a")));
    }

    @Test
    void testZMScore() {
        cmd("ZADD", "z12", "1.0", "a");
        cmd("ZADD", "z12", "2.0", "b");
        RedisMessage r = cmd("ZMSCORE", "z12", "a", "b", "c");
        assertEquals("1.0", r.getElements().get(0).asString());
        assertEquals("2.0", r.getElements().get(1).asString());
        assertTrue(r.getElements().get(2).isNullBulkString());
    }

    @Test
    void testZRandMember() {
        cmd("ZADD", "z13", "1.0", "a");
        cmd("ZADD", "z13", "2.0", "b");
        assertFalse(cmd("ZRANDMEMBER", "z13", "1").getElements().isEmpty());
    }

    @Test
    void testZRangeByScore() {
        cmd("ZADD", "z14", "1.0", "a");
        cmd("ZADD", "z14", "2.0", "b");
        cmd("ZADD", "z14", "3.0", "c");
        assertTrue(cmd("ZRANGEBYSCORE", "z14", "1", "2").getElements().size() >= 2);
    }

    @Test
    void testZRevRangeByScore() {
        cmd("ZADD", "z14_rev", "1.0", "a");
        cmd("ZADD", "z14_rev", "2.0", "b");
        cmd("ZADD", "z14_rev", "3.0", "c");
        List<RedisMessage> rev = cmd("ZREVRANGEBYSCORE", "z14_rev", "2", "1").getElements();
        assertTrue(rev.size() >= 2);
        // 应该是降序: b(2.0) 在 a(1.0) 前面
        if (rev.size() >= 2) {
            assertEquals("b", rev.get(0).asString());
            assertEquals("a", rev.get(1).asString());
        }
    }

    @Test
    void testZRemRangeByRank() {
        cmd("ZADD", "z15", "1.0", "a");
        cmd("ZADD", "z15", "2.0", "b");
        cmd("ZADD", "z15", "3.0", "c");
        assertEquals(2L, asInt(cmd("ZREMRANGEBYRANK", "z15", "0", "1")));
        assertEquals(1L, asInt(cmd("ZCARD", "z15")));
    }

    @Test
    void testZRemRangeByScore() {
        cmd("ZADD", "z16", "1.0", "a");
        cmd("ZADD", "z16", "2.0", "b");
        cmd("ZADD", "z16", "3.0", "c");
        assertEquals(2L, asInt(cmd("ZREMRANGEBYSCORE", "z16", "1", "2")));
        assertEquals(1L, asInt(cmd("ZCARD", "z16")));
    }

    @Test
    void testZInterCard() {
        cmd("ZADD", "za", "1.0", "a");
        cmd("ZADD", "za", "2.0", "b");
        cmd("ZADD", "zb", "2.0", "b");
        cmd("ZADD", "zb", "3.0", "c");
        // 交集只有 "b"（1个）
        assertEquals(1L, asInt(cmd("ZINTERCARD", "2", "za", "zb")));
    }

    // ==================== Set 集合运算 ====================

    @Test
    void testSUnion() {
        cmd("SADD", "su1", "a", "b");
        cmd("SADD", "su2", "b", "c");
        assertTrue(cmd("SUNION", "su1", "su2").getElements().size() >= 3);
    }

    @Test
    void testSInter() {
        cmd("SADD", "si1", "a", "b");
        cmd("SADD", "si2", "b", "c");
        assertEquals(1, cmd("SINTER", "si1", "si2").getElements().size());
    }

    @Test
    void testSDiff() {
        cmd("SADD", "sd1", "a", "b", "c");
        cmd("SADD", "sd2", "a");
        assertTrue(cmd("SDIFF", "sd1", "sd2").getElements().size() >= 2);
    }

    @Test
    void testSUnionStore() {
        cmd("SADD", "sus1", "a", "b");
        cmd("SADD", "sus2", "b", "c");
        assertEquals(3L, asInt(cmd("SUNIONSTORE", "sus_dest", "sus1", "sus2")));
    }

    @Test
    void testSInterStore() {
        cmd("SADD", "sis1", "a", "b");
        cmd("SADD", "sis2", "b", "c");
        assertEquals(1L, asInt(cmd("SINTERSTORE", "sis_dest", "sis1", "sis2")));
    }

    @Test
    void testSDiffStore() {
        cmd("SADD", "sds1", "a", "b", "c");
        cmd("SADD", "sds2", "a");
        assertEquals(2L, asInt(cmd("SDIFFSTORE", "sds_dest", "sds1", "sds2")));
    }

    // ==================== ZSet 集合运算 ====================

    @Test
    void testZDiff() {
        cmd("ZADD", "zd1", "1.0", "a");
        cmd("ZADD", "zd1", "2.0", "b");
        cmd("ZADD", "zd2", "2.0", "b");
        cmd("ZADD", "zd2", "3.0", "c");
        assertFalse(cmd("ZDIFF", "2", "zd1", "zd2").getElements().isEmpty());
    }

    @Test
    void testZInter() {
        cmd("ZADD", "zi1", "1.0", "a");
        cmd("ZADD", "zi1", "2.0", "b");
        cmd("ZADD", "zi2", "2.0", "b");
        cmd("ZADD", "zi2", "3.0", "c");
        assertEquals("b", cmd("ZINTER", "2", "zi1", "zi2").getElements().get(0).asString());
    }

    @Test
    void testZUnion() {
        cmd("ZADD", "zu1", "1.0", "a");
        cmd("ZADD", "zu1", "2.0", "b");
        cmd("ZADD", "zu2", "2.0", "b");
        cmd("ZADD", "zu2", "3.0", "c");
        assertTrue(cmd("ZUNION", "2", "zu1", "zu2").getElements().size() >= 3);
    }

    // ==================== DB 命令 ====================

    @Test
    void testSelect() {
        assertEquals("OK", asString(cmd("SELECT", "0")));
    }

    @Test
    void testFlushDb() {
        cmd("SET", "fd", "v");
        assertEquals("OK", asString(cmd("FLUSHDB")));
        assertTrue(cmd("GET", "fd").isNullBulkString());
    }

    @Test
    void testFlushAll() {
        cmd("SET", "fa", "v");
        assertEquals("OK", asString(cmd("FLUSHALL")));
        assertTrue(cmd("GET", "fa").isNullBulkString());
    }

    @Test
    void testQuit() {
        assertNull(cmd("QUIT"));
    }

    // ==================== Bitmap 命令 ====================

    @Test
    void testSetBitAndGetBit() {
        assertEquals(0L, asInt(cmd("SETBIT", "bit1", "7", "1")));
        assertEquals(1L, asInt(cmd("GETBIT", "bit1", "7")));
        assertEquals(0L, asInt(cmd("GETBIT", "bit1", "6")));
    }

    @Test
    void testBitCount() {
        cmd("SETBIT", "bc1", "0", "1");
        cmd("SETBIT", "bc1", "7", "1");
        assertEquals(2L, asInt(cmd("BITCOUNT", "bc1")));
    }

    // ==================== Scan 命令 ====================

    @Test
    void testScan() {
        cmd("SET", "scan_key1", "v");
        cmd("SET", "scan_key2", "v");
        assertEquals(2, cmd("SCAN", "0").getElements().size());
    }

    @Test
    void testSScan() {
        cmd("SADD", "sscan_set", "a", "b", "c");
        assertEquals(2, cmd("SSCAN", "sscan_set", "0").getElements().size());
    }

    @Test
    void testHScan() {
        cmd("HSET", "hscan_h", "f1", "v1", "f2", "v2");
        assertEquals(2, cmd("HSCAN", "hscan_h", "0").getElements().size());
    }

    @Test
    void testZScan() {
        cmd("ZADD", "zscan_z", "1.0", "a", "2.0", "b");
        assertEquals(2, cmd("ZSCAN", "zscan_z", "0").getElements().size());
    }

    // ==================== HyperLogLog ====================

    @Test
    void testPFAdd() {
        assertEquals(1L, asInt(cmd("PFADD", "hll1", "a", "b", "c")));
    }

    @Test
    void testPFCount() {
        cmd("PFADD", "hll2", "a", "b");
        assertTrue(asInt(cmd("PFCOUNT", "hll2")) > 0);
    }

    @Test
    void testPFMerge() {
        cmd("PFADD", "hll3", "a", "b");
        cmd("PFADD", "hll4", "c", "d");
        assertEquals("OK", asString(cmd("PFMERGE", "hll5", "hll3", "hll4")));
    }

    // ==================== 字符串高级操作 ====================

    @Test
    void testGetRange() {
        cmd("SET", "gr", "hello");
        assertEquals("ell", asString(cmd("GETRANGE", "gr", "1", "3")));
    }

    @Test
    void testSetRange() {
        cmd("SET", "sr", "hello");
        assertEquals(5L, asInt(cmd("SETRANGE", "sr", "1", "ELL")));
    }

    @Test
    void testBitOp() {
        cmd("SET", "bo1", "A");
        cmd("SET", "bo2", "B");
        assertEquals(1L, asInt(cmd("BITOP", "AND", "bo_dest", "bo1", "bo2")));
    }

    @Test
    void testBitPos() {
        cmd("SETBIT", "bp1", "0", "1");
        assertEquals(0L, asInt(cmd("BITPOS", "bp1", "1")));
    }

    // ==================== 事务命令 ====================

    @Test
    void testMultiExec() {
        assertEquals("OK", asString(cmd("MULTI")));
        assertEquals("QUEUED", asString(cmd("SET", "tx1", "v1")));
        assertEquals(RedisMessage.Type.ARRAY, cmd("EXEC").getType());
    }

    @Test
    void testDiscard() {
        assertEquals("OK", asString(cmd("MULTI")));
        assertEquals("QUEUED", asString(cmd("SET", "tx2", "v")));
        assertEquals("OK", asString(cmd("DISCARD")));
        assertTrue(cmd("GET", "tx2").isNullBulkString());
    }

    @Test
    void testWatch() {
        assertEquals("OK", asString(cmd("WATCH", "wkey")));
    }

    @Test
    void testUnwatch() {
        assertEquals("OK", asString(cmd("WATCH", "wkey")));
        assertEquals("OK", asString(cmd("UNWATCH")));
    }

    // ==================== 信息/管理命令 ====================

    @Test
    void testInfo() {
        assertTrue(cmd("INFO").asString().contains("# Server"));
    }

    @Test
    void testInfoWithSection() {
        assertNotNull(cmd("INFO", "server").asString());
    }

    @Test
    void testTime() {
        assertEquals(2, cmd("TIME").getElements().size());
    }

    @Test
    void testLastSave() {
        assertTrue(asInt(cmd("LASTSAVE")) > 0);
    }

    @Test
    void testCommand() {
        assertNotNull(cmd("COMMAND"));
    }

    @Test
    void testRole() {
        assertEquals("master", cmd("ROLE").getElements().get(0).asString());
    }

    @Test
    void testSlaveOf() {
        assertEquals("OK", asString(cmd("SLAVEOF", "NO", "ONE")));
    }

    @Test
    void testPSync() {
        assertTrue(cmd("PSYNC", "?", "-1").asString().contains("FULLRESYNC"));
    }

    @Test
    void testReplConf() {
        assertEquals("OK", asString(cmd("REPLCONF", "listening-port", "6380")));
        assertEquals("OK", asString(cmd("REPLCONF", "capa", "psync2")));
        assertEquals("OK", asString(cmd("REPLCONF", "ACK", "100")));
    }

    @Test
    void testReplicaOf() {
        assertEquals("OK", asString(cmd("REPLICAOF", "NO", "ONE")));
        assertEquals("OK", asString(cmd("REPLICAOF", "127.0.0.1", "6379")));
    }

    @Test
    void testHello() {
        assertEquals(RedisMessage.Type.ARRAY, cmd("HELLO").getType());
    }

    @Test
    void testConfig() {
        assertNotNull(cmd("CONFIG", "GET", "*"));
    }

    // ==================== 列表阻塞命令 ====================

    @Test
    void testBLPop() {
        cmd("RPUSH", "bl", "a", "b");
        assertEquals("a", cmd("BLPOP", "bl", "1").getElements().get(1).asString());
    }

    @Test
    void testBRPop() {
        cmd("RPUSH", "br", "a", "b");
        assertEquals("b", cmd("BRPOP", "br", "1").getElements().get(1).asString());
    }

    @Test
    void testBZPopMin() {
        cmd("ZADD", "bzmin", "1.0", "a");
        cmd("ZADD", "bzmin", "2.0", "b");
        // BZPOPMIN 目前返回错误（未完全实现）
        assertEquals(RedisMessage.Type.ERROR, cmd("BZPOPMIN", "bzmin", "1").getType());
    }

    @Test
    void testBZPopMax() {
        cmd("ZADD", "bzmax", "1.0", "a");
        cmd("ZADD", "bzmax", "2.0", "b");
        // BZPOPMAX 目前返回错误（未完全实现）
        assertEquals(RedisMessage.Type.ERROR, cmd("BZPOPMAX", "bzmax", "1").getType());
    }

    // ==================== 错误命令 ====================

    @Test
    void testAuth() {
        assertEquals(RedisMessage.Type.ERROR, cmd("AUTH", "password").getType());
    }

    @Test
    void testUnknownCommand() {
        assertEquals(RedisMessage.Type.ERROR, cmd("UNKNOWNCMD").getType());
    }

    @Test
    void testWrongType() {
        // GET 一个 SET 类型，返回 null bulk string（非错误）
        cmd("SADD", "wt", "a");
        assertTrue(cmd("GET", "wt").isNullBulkString());
    }

    // ==================== 发布订阅（空操作） ====================

    @Test
    void testPublish() {
        // PUBLISH 返回订阅者数量（整数）
        assertEquals(RedisMessage.Type.INTEGER, cmd("PUBLISH", "ch", "msg").getType());
    }

    @Test
    void testSubscribe() {
        assertEquals(RedisMessage.Type.ERROR, cmd("SUBSCRIBE", "ch").getType());
    }

    @Test
    void testUnsubscribe() {
        assertEquals(RedisMessage.Type.ERROR, cmd("UNSUBSCRIBE", "ch").getType());
    }

    @Test
    void testPSubscribe() {
        assertEquals(RedisMessage.Type.ERROR, cmd("PSUBSCRIBE", "ch").getType());
    }

    @Test
    void testPUnsubscribe() {
        assertEquals(RedisMessage.Type.ERROR, cmd("PUNSUBSCRIBE", "ch").getType());
    }

    @Test
    void testPubSub() {
        assertEquals(RedisMessage.Type.ARRAY, cmd("PUBSUB", "CHANNELS").getType());
    }

    @Test
    void testMonitor() {
        assertEquals(RedisMessage.Type.ERROR, cmd("MONITOR").getType());
    }

    // ==================== 复制/主从命令 ====================

    @Test
    void testDebug() {
        assertEquals("OK", asString(cmd("DEBUG", "SLEEP", "1")));
    }

    @Test
    void testClient() {
        assertNotNull(cmd("CLIENT", "LIST"));
    }

    // ==================== 流命令 ====================

    @Test
    void testXAddAndXLen() {
        // XADD 返回生成的 ID（bulk string）
        String id = asString(cmdRaw("XADD", RedisMessage.bulkString("mystream".getBytes()),
                RedisMessage.bulkString("*".getBytes()), RedisMessage.bulkString("field1".getBytes()),
                RedisMessage.bulkString("val1".getBytes())));
        assertNotNull(id);
        assertTrue(asInt(cmd("XLEN", "mystream")) > 0);
    }

    @Test
    void testXRange() {
        cmdRaw("XADD", RedisMessage.bulkString("xr_stream".getBytes()),
                RedisMessage.bulkString("0-1".getBytes()), RedisMessage.bulkString("f".getBytes()),
                RedisMessage.bulkString("v".getBytes()));
        assertEquals(RedisMessage.Type.ARRAY, cmd("XRANGE", "xr_stream", "-", "+").getType());
    }

    @Test
    void testXRevRange() {
        cmdRaw("XADD", RedisMessage.bulkString("xrr_stream".getBytes()),
                RedisMessage.bulkString("0-1".getBytes()), RedisMessage.bulkString("f".getBytes()),
                RedisMessage.bulkString("v".getBytes()));
        assertEquals(RedisMessage.Type.ARRAY, cmd("XREVRANGE", "xrr_stream", "+", "-").getType());
    }

    @Test
    void testXRead() {
        cmdRaw("XADD", RedisMessage.bulkString("xr_stream2".getBytes()),
                RedisMessage.bulkString("0-1".getBytes()), RedisMessage.bulkString("f".getBytes()),
                RedisMessage.bulkString("v".getBytes()));
        assertEquals(RedisMessage.Type.ARRAY,
                cmdRaw("XREAD", RedisMessage.bulkString("STREAMS".getBytes()),
                        RedisMessage.bulkString("xr_stream2".getBytes()),
                        RedisMessage.bulkString("0-0".getBytes())).getType());
    }

    @Test
    void testXTrim() {
        cmdRaw("XADD", RedisMessage.bulkString("xt_stream".getBytes()),
                RedisMessage.bulkString("0-1".getBytes()), RedisMessage.bulkString("f".getBytes()),
                RedisMessage.bulkString("v".getBytes()));
        cmdRaw("XADD", RedisMessage.bulkString("xt_stream".getBytes()),
                RedisMessage.bulkString("0-2".getBytes()), RedisMessage.bulkString("f".getBytes()),
                RedisMessage.bulkString("v".getBytes()));
        assertEquals(RedisMessage.Type.INTEGER, cmd("XTRIM", "xt_stream", "MAXLEN", "1").getType());
    }

    @Test
    void testXDel() {
        // XADD returns stream entry ID, not "OK"
        assertNotNull(asString(cmdRaw("XADD", RedisMessage.bulkString("xd_stream".getBytes()),
                RedisMessage.bulkString("0-1".getBytes()), RedisMessage.bulkString("f".getBytes()),
                RedisMessage.bulkString("v".getBytes()))));
        // XDEL 返回删条数（整数）
        assertEquals(1L, asInt(cmd("XDEL", "xd_stream", "0-1")));
    }

    @Test
    void testXPending() {
        // XADD returns stream entry ID, not "OK"
        assertNotNull(asString(cmdRaw("XADD", RedisMessage.bulkString("xp_stream".getBytes()),
                RedisMessage.bulkString("0-1".getBytes()), RedisMessage.bulkString("f".getBytes()),
                RedisMessage.bulkString("v".getBytes()))));
        assertNotNull(cmd("XPENDING", "xp_stream", "mygroup", "-", "+", "10"));
    }

    @Test
    void testXGroup() {
        assertEquals(RedisMessage.Type.ERROR, cmd("XGROUP", "CREATE", "xg_stream", "mygroup", "$").getType());
    }

    @Test
    void testXReadGroup() {
        assertEquals(RedisMessage.Type.ERROR, cmd("XREADGROUP", "GROUP", "g", "c", "STREAMS", "s", ">").getType());
    }

    // ==================== Geo 命令 ====================

    @Test
    void testGeoAdd() {
        assertEquals(1L, asInt(cmd("GEOADD", "geo1", "13.361389", "38.115556", "Palermo")));
    }

    @Test
    void testGeoDist() {
        cmd("GEOADD", "geo2", "13.361389", "38.115556", "Palermo");
        cmd("GEOADD", "geo2", "15.087269", "37.502669", "Catania");
        assertNotNull(cmd("GEODIST", "geo2", "Palermo", "Catania").asString());
    }

    @Test
    void testGeoPos() {
        cmd("GEOADD", "geo3", "13.361389", "38.115556", "Palermo");
        assertEquals(RedisMessage.Type.ARRAY, cmd("GEOPOS", "geo3", "Palermo").getType());
    }

    @Test
    void testGeoHash() {
        cmd("GEOADD", "geo4", "13.361389", "38.115556", "Palermo");
        assertEquals(RedisMessage.Type.ARRAY, cmd("GEOHASH", "geo4", "Palermo").getType());
    }

    @Test
    void testGeoRadius() {
        cmd("GEOADD", "geo5", "13.361389", "38.115556", "Palermo");
        assertEquals(RedisMessage.Type.ARRAY, cmd("GEORADIUS", "geo5", "15", "37", "200", "km").getType());
    }

    @Test
    void testGeoRadiusByMember() {
        cmd("GEOADD", "geo6", "13.361389", "38.115556", "Palermo");
        cmd("GEOADD", "geo6", "15.087269", "37.502669", "Catania");
        assertEquals(RedisMessage.Type.ARRAY, cmd("GEORADIUSBYMEMBER", "geo6", "Palermo", "200", "km").getType());
    }

    // ==================== Lua 脚本 ====================

    @Test
    void testEval() {
        assertEquals("42", asString(cmd("EVAL", "return 42", "0")));
    }

    @Test
    void testScript() {
        assertEquals(RedisMessage.Type.BULK_STRING, cmd("SCRIPT", "LOAD", "return 1").getType());
    }

    @Test
    void testEvalSha() {
        assertEquals(RedisMessage.Type.ERROR, cmd("EVALSHA", "unknown_sha", "0").getType());
    }

    // ==================== 排序 ====================

    @Test
    void testSort() {
        cmd("RPUSH", "sort_list", "3", "1", "2");
        assertEquals("1", cmd("SORT", "sort_list").getElements().get(0).asString());
    }

    @Test
    void testSortAlpha() {
        cmd("RPUSH", "sort_alpha", "c", "a", "b");
        assertEquals("a", cmdRaw("SORT", RedisMessage.bulkString("sort_alpha".getBytes()),
                RedisMessage.bulkString("ALPHA".getBytes())).getElements().get(0).asString());
    }

    // ==================== 持久化命令 ====================

    @Test
    void testSave() {
        // PersistenceManager 为 null，返回错误
        assertEquals(RedisMessage.Type.ERROR, cmd("SAVE").getType());
    }

    @Test
    void testBgSave() {
        assertEquals(RedisMessage.Type.ERROR, cmd("BGSAVE").getType());
    }

    @Test
    void testBgRewriteAof() {
        assertEquals(RedisMessage.Type.ERROR, cmd("BGREWRITEAOF").getType());
    }

    // ==================== RPopLPush / LMove / SMove ====================

    @Test
    void testRPopLPush() {
        cmd("RPUSH", "rp_source", "a", "b");
        assertEquals("b", asString(cmd("RPOPLPUSH", "rp_source", "rp_dest")));
    }

    @Test
    void testSMove() {
        cmd("SADD", "sm_src", "a", "b");
        cmd("SADD", "sm_dst", "c");
        assertEquals(1L, asInt(cmd("SMOVE", "sm_src", "sm_dst", "a")));
    }

    // ==================== 空值/边界测试 ====================

    @Test
    void testGetNonExistentList() {
        assertNull(asString(cmd("LPOP", "no_list")));
    }

    @Test
    void testDumpAndRestore() {
        cmd("SET", "dump_key", "hello");
        RedisMessage dump = cmd("DUMP", "dump_key");
        assertNotNull(dump.getData());
        assertEquals("OK", asString(cmd("RESTORE", "restored", "0", asString(dump))));
        assertEquals("hello", asString(cmd("GET", "restored")));
    }

    @Test
    void testSlowLog() {
        assertEquals(RedisMessage.Type.ARRAY, cmd("SLOWLOG", "GET", "10").getType());
    }

    // ==================== 错误输入测试 ====================

    @Test
    void testSetWrongArgs() {
        assertEquals(RedisMessage.Type.ERROR, cmd("SET").getType());
    }

    @Test
    void testGetWrongArgs() {
        assertEquals(RedisMessage.Type.ERROR, cmd("GET").getType());
    }

    @Test
    void testInvalidCommandArgs() {
        assertEquals(RedisMessage.Type.ERROR, cmd("LREM", "key").getType());
    }

    @Test
    void testShutdown() {
        assertEquals("OK", asString(cmd("SHUTDOWN")));
    }

    // ==================== GETRANGE 边界 ====================

    @Test
    void testGetRangeNegative() {
        cmd("SET", "grn", "hello");
        assertNotNull(asString(cmd("GETRANGE", "grn", "-3", "-1")));
    }

    // ==================== LRange 边界 ====================

    @Test
    void testLRangeNegative() {
        cmd("RPUSH", "lrn", "a", "b", "c");
        assertEquals(3, cmd("LRANGE", "lrn", "0", "-1").getElements().size());
    }

    // ==================== SCAN with pattern ====================

    @Test
    void testScanWithPattern() {
        cmd("SET", "sp_a", "1");
        cmd("SET", "sp_b", "2");
        cmd("SET", "xx_c", "3");
        assertEquals(2, cmdRaw("SCAN", RedisMessage.bulkString("0".getBytes()),
                RedisMessage.bulkString("MATCH".getBytes()),
                RedisMessage.bulkString("sp_*".getBytes())).getElements().size());
    }

    // ==================== 额外边界分支 ====================

    @Test
    void testSetWithNxXxIncompatible() {
        assertEquals(RedisMessage.Type.ERROR,
                cmdRaw("SET", RedisMessage.bulkString("k".getBytes()),
                        RedisMessage.bulkString("v".getBytes()), RedisMessage.bulkString("NX".getBytes()),
                        RedisMessage.bulkString("XX".getBytes())).getType());
    }

    @Test
    void testSetWithExat() {
        long future = System.currentTimeMillis() / 1000 + 3600;
        assertEquals("OK", asString(cmdRaw("SET", RedisMessage.bulkString("k_exat".getBytes()),
                RedisMessage.bulkString("v".getBytes()), RedisMessage.bulkString("EXAT".getBytes()),
                RedisMessage.bulkString(String.valueOf(future).getBytes()))));
    }

    @Test
    void testSetWithPxat() {
        long future = System.currentTimeMillis() + 3600000;
        assertEquals("OK", asString(cmdRaw("SET", RedisMessage.bulkString("k_pxat".getBytes()),
                RedisMessage.bulkString("v".getBytes()), RedisMessage.bulkString("PXAT".getBytes()),
                RedisMessage.bulkString(String.valueOf(future).getBytes()))));
    }

    @Test
    void testSetWithKeepTtl() {
        cmd("SET", "k_keep", "v");
        assertEquals("OK", asString(cmdRaw("SET", RedisMessage.bulkString("k_keep".getBytes()),
                RedisMessage.bulkString("new".getBytes()), RedisMessage.bulkString("KEEPTTL".getBytes()))));
        assertEquals("new", asString(cmd("GET", "k_keep")));
    }

    @Test
    void testSetInvalidOption() {
        assertEquals(RedisMessage.Type.ERROR,
                cmdRaw("SET", RedisMessage.bulkString("k".getBytes()),
                        RedisMessage.bulkString("v".getBytes()),
                        RedisMessage.bulkString("INVALID".getBytes())).getType());
    }

    @Test
    void testGetExWithPx() {
        cmd("SET", "ge_px", "val");
        assertEquals("val", asString(cmdRaw("GETEX", RedisMessage.bulkString("ge_px".getBytes()),
                RedisMessage.bulkString("PX".getBytes()), RedisMessage.bulkString("100000".getBytes()))));
    }

    @Test
    void testGetExWithExat() {
        cmd("SET", "ge_exat", "val");
        long future = System.currentTimeMillis() / 1000 + 3600;
        assertEquals("val", asString(cmdRaw("GETEX", RedisMessage.bulkString("ge_exat".getBytes()),
                RedisMessage.bulkString("EXAT".getBytes()),
                RedisMessage.bulkString(String.valueOf(future).getBytes()))));
    }

    @Test
    void testLRemPositiveCount() {
        cmd("RPUSH", "lrem_p", "a", "b", "a", "c", "a");
        // count=2 从头删除最多2个"a"
        assertEquals(2L, asInt(cmd("LREM", "lrem_p", "2", "a")));
    }

    @Test
    void testLRemNegativeCount() {
        cmd("RPUSH", "lrem_n", "a", "b", "a", "c", "a");
        // count=-2 从尾删除最多2个"a"
        assertEquals(2L, asInt(cmd("LREM", "lrem_n", "-2", "a")));
        assertEquals(3L, asInt(cmd("LLEN", "lrem_n"))); // 剩余[b, c, a]
    }

    @Test
    void testLInsertAfter() {
        cmd("RPUSH", "li_after", "a", "c");
        assertEquals(3L, asInt(cmd("LINSERT", "li_after", "AFTER", "a", "b")));
        assertEquals("b", asString(cmd("LINDEX", "li_after", "1")));
    }

    @Test
    void testLInsertPivotNotFound() {
        cmd("RPUSH", "li_nf", "a", "b");
        assertEquals(-1L, asInt(cmd("LINSERT", "li_nf", "BEFORE", "z", "x")));
    }

    @Test
    void testLInsertInvalidWhere() {
        cmd("RPUSH", "li_iw", "a");
        assertEquals(RedisMessage.Type.ERROR, cmd("LINSERT", "li_iw", "WRONG", "a", "b").getType());
    }

    @Test
    void testLMoveLeftToRight() {
        cmd("RPUSH", "lm_src", "a", "b", "c");
        assertEquals("a", asString(cmd("LMOVE", "lm_src", "lm_dst", "LEFT", "RIGHT")));
        assertEquals("a", asString(cmd("LINDEX", "lm_dst", "0")));
    }

    @Test
    void testLMoveRightToLeft() {
        cmd("RPUSH", "lm_src2", "a", "b");
        assertEquals("b", asString(cmd("LMOVE", "lm_src2", "lm_dst2", "RIGHT", "LEFT")));
    }

    @Test
    void testGetSetNonExistent() {
        assertTrue(cmd("GETSET", "gs_ne", "val").isNullBulkString());
        assertEquals("val", asString(cmd("GET", "gs_ne")));
    }

    @Test
    void testBitOpOr() {
        cmd("SET", "bo_or1", "A");
        cmd("SET", "bo_or2", "B");
        assertEquals(1L, asInt(cmd("BITOP", "OR", "bo_or_dest", "bo_or1", "bo_or2")));
    }

    @Test
    void testBitOpXor() {
        cmd("SET", "bo_xor1", "A");
        cmd("SET", "bo_xor2", "B");
        assertEquals(1L, asInt(cmd("BITOP", "XOR", "bo_xor_dest", "bo_xor1", "bo_xor2")));
    }

    @Test
    void testBitOpNot() {
        cmd("SET", "bo_not", "A");
        assertEquals(1L, asInt(cmd("BITOP", "NOT", "bo_not_dest", "bo_not")));
    }

    @Test
    void testLPosWithRank() {
        cmd("RPUSH", "lp_rank", "a", "b", "a", "c", "a");
        assertEquals(2L, cmd("LPOS", "lp_rank", "a", "RANK", "2").getIntegerValue());
    }

    @Test
    void testZAddExistingSameScore() {
        cmd("ZADD", "z_same", "1.0", "a");
        assertEquals(0L, asInt(cmd("ZADD", "z_same", "1.0", "a")));
    }

    @Test
    void testZDiffStore() {
        cmd("ZADD", "zds1", "1.0", "a");
        cmd("ZADD", "zds1", "2.0", "b");
        cmd("ZADD", "zds2", "2.0", "b");
        assertEquals(1L, asInt(cmd("ZDIFFSTORE", "zds_dest", "2", "zds1", "zds2")));
    }

    @Test
    void testZInterStore() {
        cmd("ZADD", "zis1", "1.0", "a");
        cmd("ZADD", "zis1", "2.0", "b");
        cmd("ZADD", "zis2", "2.0", "b");
        assertEquals(1L, asInt(cmd("ZINTERSTORE", "zis_dest", "2", "zis1", "zis2")));
    }

    @Test
    void testZUnionStore() {
        cmd("ZADD", "zus1", "1.0", "a");
        cmd("ZADD", "zus1", "2.0", "b");
        cmd("ZADD", "zus2", "2.0", "b");
        cmd("ZADD", "zus2", "3.0", "c");
        assertEquals(3L, asInt(cmd("ZUNIONSTORE", "zus_dest", "2", "zus1", "zus2")));
    }

    @Test
    void testZLexCount() {
        cmd("ZADD", "zlc", "0", "a", "0", "b", "0", "c");
        assertEquals(2L, asInt(cmd("ZLEXCOUNT", "zlc", "[a", "[b")));
    }

    @Test
    void testZRangeByLex() {
        cmd("ZADD", "zrbl", "0", "a", "0", "b", "0", "c");
        assertEquals(2, cmd("ZRANGEBYLEX", "zrbl", "[a", "[b").getElements().size());
    }

    @Test
    void testZRevRangeByLex() {
        cmd("ZADD", "zrrbl", "0", "a", "0", "b", "0", "c");
        assertFalse(cmd("ZREVRANGEBYLEX", "zrrbl", "[c", "[a").getElements().isEmpty());
    }

    @Test
    void testZRemRangeByLex() {
        cmd("ZADD", "zrrml", "0", "a", "0", "b", "0", "c");
        assertEquals(2L, asInt(cmd("ZREMRANGEBYLEX", "zrrml", "[a", "[b")));
    }

    @Test
    void testZRangeStore() {
        cmd("ZADD", "zrs", "1.0", "a", "2.0", "b", "3.0", "c");
        assertEquals(3L, asInt(cmd("ZRANGESTORE", "zrs_dest", "zrs", "0", "-1")));
    }

    @Test
    void testSInterCard() {
        cmd("SADD", "sic1", "a", "b");
        cmd("SADD", "sic2", "b", "c");
        assertEquals(1L, asInt(cmd("SINTERCARD", "2", "sic1", "sic2")));
    }

    @Test
    void testTouchNonExistent() {
        assertEquals(0L, asInt(cmd("TOUCH", "nonexistent_touch")));
    }

    @Test
    void testCopyNonExistent() {
        assertEquals(0L, asInt(cmd("COPY", "nonexistent_src", "dest")));
    }

    @Test
    void testMoveNonExistent() {
        assertEquals(0L, asInt(cmd("MOVE", "nonexistent_move", "1")));
    }

    @Test
    void testPersistNonExistent() {
        assertEquals(0L, asInt(cmd("PERSIST", "nonexistent_persist")));
    }

    @Test
    void testExpireNonExistent() {
        assertEquals(0L, asInt(cmd("EXPIRE", "nonexistent_exp", "100")));
    }

    @Test
    void testZRankNonExistent() {
        assertTrue(cmd("ZRANK", "zrank_ne", "a").isNullBulkString());
    }

    @Test
    void testZScoreNonExistent() {
        assertTrue(cmd("ZSCORE", "zscore_ne", "a").isNullBulkString());
    }

    @Test
    void testPingTooManyArgs() {
        assertEquals(RedisMessage.Type.ERROR, cmd("PING", "a", "b").getType());
    }
}
