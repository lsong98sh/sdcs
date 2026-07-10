package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class IntegrationTest {
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
    void testStringSetGet() {
        RedisMessage r = dispatcher.dispatch("SET", args("skey1", "svalue1"));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());

        r = dispatcher.dispatch("GET", args("skey1"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertEquals("svalue1", r.asString());

        r = dispatcher.dispatch("GET", args("nonexistent"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertTrue(r.isNullBulkString());
    }

    @Test
    void testStringIncr() {
        dispatcher.dispatch("SET", args("counter", "10"));
        RedisMessage r = dispatcher.dispatch("INCR", args("counter"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(11, r.getIntegerValue());

        r = dispatcher.dispatch("GET", args("counter"));
        assertEquals("11", r.asString());
    }

    @Test
    void testStringMset() {
        RedisMessage r = dispatcher.dispatch("MSET", args("k1", "v1", "k2", "v2"));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());

        r = dispatcher.dispatch("GET", args("k1"));
        assertEquals("v1", r.asString());
        r = dispatcher.dispatch("GET", args("k2"));
        assertEquals("v2", r.asString());
    }

    @Test
    void testStringExpiry() {
        RedisMessage r = dispatcher.dispatch("SET", List.of(
                bulkString("ekey"),
                bulkString("evalue"),
                bulkString("EX"),
                bulkString("100")
        ));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());

        r = dispatcher.dispatch("TTL", args("ekey"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertTrue(r.getIntegerValue() > 0);
    }

    @Test
    void testListLPushLRange() {
        RedisMessage r = dispatcher.dispatch("LPUSH", args("mylist", "a", "b", "c"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(3, r.getIntegerValue());

        r = dispatcher.dispatch("LRANGE", args("mylist", "0", "-1"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertEquals(3, elements.size());
        assertEquals("c", elements.get(0).asString());
        assertEquals("b", elements.get(1).asString());
        assertEquals("a", elements.get(2).asString());
    }

    @Test
    void testListLPOP() {
        dispatcher.dispatch("LPUSH", args("mylist2", "x", "y", "z"));
        RedisMessage r = dispatcher.dispatch("LPOP", args("mylist2"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertEquals("z", r.asString());

        r = dispatcher.dispatch("LPOP", args("mylist2"));
        assertEquals("y", r.asString());

        r = dispatcher.dispatch("LPOP", args("mylist2"));
        assertEquals("x", r.asString());

        r = dispatcher.dispatch("LPOP", args("mylist2"));
        assertTrue(r.isNullBulkString());
    }

    @Test
    void testHashHSetHGet() {
        RedisMessage r = dispatcher.dispatch("HSET", args("myhash", "field1", "value1"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(1, r.getIntegerValue());

        r = dispatcher.dispatch("HGET", args("myhash", "field1"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertEquals("value1", r.asString());

        r = dispatcher.dispatch("HGET", args("myhash", "nonexistent"));
        assertTrue(r.isNullBulkString());
    }

    @Test
    void testHashHGetAll() {
        dispatcher.dispatch("HSET", args("myhash2", "f1", "v1"));
        dispatcher.dispatch("HSET", args("myhash2", "f2", "v2"));

        RedisMessage r = dispatcher.dispatch("HGETALL", args("myhash2"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertEquals(4, elements.size());
    }

    @Test
    void testHashHRandField() {
        dispatcher.dispatch("HSET", args("myhash3", "a", "1"));
        dispatcher.dispatch("HSET", args("myhash3", "b", "2"));
        dispatcher.dispatch("HSET", args("myhash3", "c", "3"));

        RedisMessage r = dispatcher.dispatch("HRANDFIELD", args("myhash3"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertNotNull(r.asString());
    }

    @Test
    void testHashHSetNx() {
        dispatcher.dispatch("HSET", args("myhash4", "f1", "v1"));

        RedisMessage r = dispatcher.dispatch("HSETNX", args("myhash4", "f1", "v2"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(0, r.getIntegerValue());

        r = dispatcher.dispatch("HSETNX", args("myhash4", "f2", "v2"));
        assertEquals(1, r.getIntegerValue());

        r = dispatcher.dispatch("HGET", args("myhash4", "f2"));
        assertEquals("v2", r.asString());
    }

    @Test
    void testSetSAddSMembers() {
        RedisMessage r = dispatcher.dispatch("SADD", args("myset", "m1", "m2", "m3"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(3, r.getIntegerValue());

        r = dispatcher.dispatch("SMEMBERS", args("myset"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        assertEquals(3, r.getElements().size());
    }

    @Test
    void testSetSUnionStore() {
        dispatcher.dispatch("SADD", args("setA", "a", "b"));
        dispatcher.dispatch("SADD", args("setB", "b", "c"));

        RedisMessage r = dispatcher.dispatch("SUNIONSTORE", args("setC", "setA", "setB"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(3, r.getIntegerValue());
    }

    @Test
    void testZSetZAddZRange() {
        RedisMessage r = dispatcher.dispatch("ZADD", args("myzset", "1.0", "m1"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertTrue(r.getIntegerValue() >= 0);

        dispatcher.dispatch("ZADD", args("myzset", "2.0", "m2"));
        dispatcher.dispatch("ZADD", args("myzset", "3.0", "m3"));

        r = dispatcher.dispatch("ZRANGE", args("myzset", "0", "-1"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        assertEquals(3, r.getElements().size());
        assertEquals("m1", r.getElements().get(0).asString());
        assertEquals("m2", r.getElements().get(1).asString());
        assertEquals("m3", r.getElements().get(2).asString());
    }

    @Test
    void testZSetZPopMin() {
        dispatcher.dispatch("ZADD", args("myzset2", "1.0", "a"));
        dispatcher.dispatch("ZADD", args("myzset2", "2.0", "b"));

        RedisMessage r = dispatcher.dispatch("ZPOPMIN", args("myzset2"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertEquals(2, elements.size());
        assertEquals("a", elements.get(0).asString());
    }

    @Test
    void testZSetZInterStore() {
        dispatcher.dispatch("ZADD", args("zset1", "1.0", "a"));
        dispatcher.dispatch("ZADD", args("zset1", "2.0", "b"));
        dispatcher.dispatch("ZADD", args("zset2", "2.0", "b"));
        dispatcher.dispatch("ZADD", args("zset2", "3.0", "c"));

        RedisMessage r = dispatcher.dispatch("ZINTERSTORE", args("zset_dest", "2", "zset1", "zset2"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(1, r.getIntegerValue());
    }

    @Test
    void testKeysExpireTTL() {
        dispatcher.dispatch("SET", args("expkey", "val"));
        RedisMessage r = dispatcher.dispatch("EXPIRE", args("expkey", "100"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(1, r.getIntegerValue());

        r = dispatcher.dispatch("TTL", args("expkey"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertTrue(r.getIntegerValue() > 0);

        r = dispatcher.dispatch("EXPIRE", args("nonexistent", "100"));
        assertEquals(0, r.getIntegerValue());

        r = dispatcher.dispatch("TTL", args("nonexistent"));
        assertTrue(r.getIntegerValue() < 0);
    }

    @Test
    void testKeysRename() {
        dispatcher.dispatch("SET", args("oldkey", "myvalue"));
        RedisMessage r = dispatcher.dispatch("RENAME", args("oldkey", "newkey"));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());

        r = dispatcher.dispatch("GET", args("oldkey"));
        assertTrue(r.isNullBulkString());

        r = dispatcher.dispatch("GET", args("newkey"));
        assertEquals("myvalue", r.asString());
    }

    @Test
    void testKeysCopy() {
        dispatcher.dispatch("SET", args("srckey", "copyval"));
        RedisMessage r = dispatcher.dispatch("COPY", args("srckey", "dstkey"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(1, r.getIntegerValue());

        r = dispatcher.dispatch("GET", args("srckey"));
        assertEquals("copyval", r.asString());
        r = dispatcher.dispatch("GET", args("dstkey"));
        assertEquals("copyval", r.asString());
    }

    @Test
    void testKeysMove() {
        dispatcher.dispatch("SET", args("movekey", "moveval"));
        RedisMessage r = dispatcher.dispatch("MOVE", args("movekey", "1"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(1, r.getIntegerValue());

        r = dispatcher.dispatch("GET", args("movekey"));
        assertTrue(r.isNullBulkString());

        dispatcher.dispatch("SELECT", args("1"));
        r = dispatcher.dispatch("GET", args("movekey"));
        assertEquals("moveval", r.asString());
        dispatcher.dispatch("SELECT", args("0"));
    }

    @Test
    void testKeysTouch() {
        dispatcher.dispatch("SET", args("touchkey1", "val"));
        dispatcher.dispatch("SET", args("touchkey2", "val"));
        RedisMessage r = dispatcher.dispatch("TOUCH", args("touchkey1", "touchkey2"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(2, r.getIntegerValue());
    }

    @Test
    void testKeysScan() {
        dispatcher.dispatch("SET", args("scankey1", "v"));
        dispatcher.dispatch("SET", args("scankey2", "v"));
        dispatcher.dispatch("SET", args("scankey3", "v"));

        RedisMessage r = dispatcher.dispatch("SCAN", args("0"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertEquals(2, elements.size());
        assertEquals(RedisMessage.Type.BULK_STRING, elements.get(0).getType());
        assertEquals(RedisMessage.Type.ARRAY, elements.get(1).getType());
    }

    @Test
    void testKeysRandomKey() {
        dispatcher.dispatch("SET", args("randkey", "val"));
        RedisMessage r = dispatcher.dispatch("RANDOMKEY", args());
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertEquals("randkey", r.asString());
    }

    @Test
    void testSelectDB() {
        RedisMessage r = dispatcher.dispatch("SELECT", args("5"));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());

        r = dispatcher.dispatch("SELECT", args("0"));
        assertEquals("OK", r.asString());
    }

    @Test
    void testMoveBetweenDB() {
        dispatcher.dispatch("SET", args("mvkey", "dbval"));
        RedisMessage r = dispatcher.dispatch("MOVE", args("mvkey", "2"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(1, r.getIntegerValue());

        dispatcher.dispatch("SELECT", args("2"));
        r = dispatcher.dispatch("GET", args("mvkey"));
        assertEquals("dbval", r.asString());
        dispatcher.dispatch("SELECT", args("0"));
    }

    @Test
    void testBitmapSetGetBit() {
        RedisMessage r = dispatcher.dispatch("SETBIT", args("bitmap", "7", "1"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(0, r.getIntegerValue());

        r = dispatcher.dispatch("GETBIT", args("bitmap", "7"));
        assertEquals(1, r.getIntegerValue());

        r = dispatcher.dispatch("GETBIT", args("bitmap", "0"));
        assertEquals(0, r.getIntegerValue());
    }

    @Test
    void testBitmapBitCount() {
        dispatcher.dispatch("SETBIT", args("bm", "0", "1"));
        dispatcher.dispatch("SETBIT", args("bm", "2", "1"));

        RedisMessage r = dispatcher.dispatch("BITCOUNT", args("bm"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(2, r.getIntegerValue());
    }

    @Test
    void testBitmapBitOp() {
        dispatcher.dispatch("SETBIT", args("bm1", "0", "1"));
        dispatcher.dispatch("SETBIT", args("bm1", "2", "1"));
        dispatcher.dispatch("SETBIT", args("bm2", "1", "1"));
        dispatcher.dispatch("SETBIT", args("bm2", "2", "1"));

        RedisMessage r = dispatcher.dispatch("BITOP", args("AND", "bm_and", "bm1", "bm2"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());

        r = dispatcher.dispatch("GETBIT", args("bm_and", "2"));
        assertEquals(1, r.getIntegerValue());
    }

    @Test
    void testHyperLogLog() {
        RedisMessage r = dispatcher.dispatch("PFADD", args("hll", "a", "b", "c"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(1, r.getIntegerValue());

        r = dispatcher.dispatch("PFCOUNT", args("hll"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertTrue(r.getIntegerValue() >= 3);
    }

    @Test
    void testGeoAddDist() {
        RedisMessage r = dispatcher.dispatch("GEOADD", args("mygeo", "13.361389", "38.115556", "Palermo"));
        assertEquals(RedisMessage.Type.INTEGER, r.getType());
        assertEquals(1, r.getIntegerValue());

        r = dispatcher.dispatch("GEOADD", args("mygeo", "15.087269", "37.502669", "Catania"));
        assertEquals(1, r.getIntegerValue());

        r = dispatcher.dispatch("GEODIST", args("mygeo", "Palermo", "Catania"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertNotNull(r.asString());
    }

    @Test
    void testSort() {
        dispatcher.dispatch("LPUSH", args("sortlist", "3", "1", "2"));

        RedisMessage r = dispatcher.dispatch("SORT", args("sortlist", "ALPHA"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertEquals(3, elements.size());
        assertEquals("1", elements.get(0).asString());
    }

    @Test
    void testDumpRestore() {
        dispatcher.dispatch("SET", args("dumpkey", "dumpval"));

        RedisMessage r = dispatcher.dispatch("DUMP", args("dumpkey"));
        assertEquals(RedisMessage.Type.BULK_STRING, r.getType());
        assertNotNull(r.getData());

        byte[] dumped = r.getData();
        r = dispatcher.dispatch("RESTORE", List.of(
                bulkString("restoredkey"),
                bulkString("0"),
                RedisMessage.bulkString(dumped)
        ));
        assertEquals(RedisMessage.Type.SIMPLE_STRING, r.getType());
        assertEquals("OK", r.asString());

        r = dispatcher.dispatch("GET", args("restoredkey"));
        assertEquals("dumpval", r.asString());
    }

    @Test
    void testHello() {
        RedisMessage r = dispatcher.dispatch("HELLO", args("3"));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertFalse(elements.isEmpty());
    }

    @Test
    void testRole() {
        RedisMessage r = dispatcher.dispatch("ROLE", args());
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        List<RedisMessage> elements = r.getElements();
        assertEquals(3, elements.size());
        assertEquals("master", elements.get(0).asString());
    }

    @Test
    void testConfig() {
        new SDCSConfig();
        RedisMessage r = dispatcher.dispatch("CONFIG", List.of(
                bulkString("GET"),
                bulkString("*")
        ));
        assertEquals(RedisMessage.Type.ARRAY, r.getType());
        assertFalse(r.getElements().isEmpty());
    }
}
