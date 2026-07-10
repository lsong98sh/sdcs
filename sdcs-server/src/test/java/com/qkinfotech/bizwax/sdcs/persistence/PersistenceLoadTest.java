package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.common.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RDB 和 AOF 的生成→加载往返测试。
 * <p>
 * 先使用 RdbSaver / 手工构造 AOF 文件，再通过 RdbLoader / AofLoader 读取验证。
 */
class PersistenceLoadTest {

    // ==================== RDB 往返测试 ====================

    @Test
    void testRdbSaveAndLoadString(@TempDir Path tempDir) throws Exception {
        Map<String, RedisData> store = new HashMap<>();
        store.put("k_str", new RedisData(RedisDataType.STRING, "hello".getBytes()));

        File rdbFile = tempDir.resolve("test_string.rdb").toFile();
        new RdbSaver().save(store, rdbFile, 0);

        RdbLoader.LoadResult result = new RdbLoader().load(rdbFile);
        assertNotNull(result);
        assertEquals(0, result.getDbIndex());
        assertEquals(1, result.getData().size());
        assertArrayEquals("hello".getBytes(), result.getData().get("k_str").getValue(byte[].class));
    }

    @Test
    void testRdbSaveAndLoadList(@TempDir Path tempDir) throws Exception {
        Map<String, RedisData> store = new HashMap<>();
        RedisList list = new RedisList();
        list.rpush("a".getBytes());
        list.rpush("b".getBytes());
        list.rpush("c".getBytes());
        store.put("k_list", new RedisData(RedisDataType.LIST, list));

        File rdbFile = tempDir.resolve("test_list.rdb").toFile();
        new RdbSaver().save(store, rdbFile, 0);

        RdbLoader.LoadResult result = new RdbLoader().load(rdbFile);
        assertNotNull(result);
        RedisList loaded = result.getData().get("k_list").getValue(RedisList.class);
        assertNotNull(loaded);
        assertEquals(3, loaded.llen());
        assertArrayEquals("a".getBytes(), loaded.lindex(0));
        assertArrayEquals("c".getBytes(), loaded.lindex(2));
    }

    @Test
    void testRdbSaveAndLoadSet(@TempDir Path tempDir) throws Exception {
        Map<String, RedisData> store = new HashMap<>();
        RedisSet set = new RedisSet();
        set.sadd("x".getBytes());
        set.sadd("y".getBytes());
        set.sadd("z".getBytes());
        store.put("k_set", new RedisData(RedisDataType.SET, set));

        File rdbFile = tempDir.resolve("test_set.rdb").toFile();
        new RdbSaver().save(store, rdbFile, 0);

        RdbLoader.LoadResult result = new RdbLoader().load(rdbFile);
        assertNotNull(result);
        RedisSet loaded = result.getData().get("k_set").getValue(RedisSet.class);
        assertNotNull(loaded);
        assertEquals(3, loaded.scard());
        assertTrue(loaded.sismember("x".getBytes()));
        assertTrue(loaded.sismember("z".getBytes()));
    }

    @Test
    void testRdbSaveAndLoadZSet(@TempDir Path tempDir) throws Exception {
        Map<String, RedisData> store = new HashMap<>();
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "a".getBytes());
        zset.zadd(2.5, "b".getBytes());
        zset.zadd(3.0, "c".getBytes());
        store.put("k_zset", new RedisData(RedisDataType.ZSET, zset));

        File rdbFile = tempDir.resolve("test_zset.rdb").toFile();
        new RdbSaver().save(store, rdbFile, 0);

        RdbLoader.LoadResult result = new RdbLoader().load(rdbFile);
        assertNotNull(result);
        RedisZSet loaded = result.getData().get("k_zset").getValue(RedisZSet.class);
        assertNotNull(loaded);
        assertEquals(3, loaded.zcard());
        assertEquals(2.5, loaded.zscore("b".getBytes()), 0.001);
    }

    @Test
    void testRdbSaveAndLoadHash(@TempDir Path tempDir) throws Exception {
        Map<String, RedisData> store = new HashMap<>();
        RedisHash hash = new RedisHash();
        hash.hset("f1", "v1".getBytes());
        hash.hset("f2", "v2".getBytes());
        store.put("k_hash", new RedisData(RedisDataType.HASH, hash));

        File rdbFile = tempDir.resolve("test_hash.rdb").toFile();
        new RdbSaver().save(store, rdbFile, 0);

        RdbLoader.LoadResult result = new RdbLoader().load(rdbFile);
        assertNotNull(result);
        RedisHash loaded = result.getData().get("k_hash").getValue(RedisHash.class);
        assertNotNull(loaded);
        assertEquals(2, loaded.hlen());
        assertArrayEquals("v1".getBytes(), loaded.hget("f1"));
        assertArrayEquals("v2".getBytes(), loaded.hget("f2"));
    }

    @Test
    void testRdbSaveAndLoadMixedTypes(@TempDir Path tempDir) throws Exception {
        Map<String, RedisData> store = new HashMap<>();

        // String with expiry
        store.put("s1", new RedisData(RedisDataType.STRING, "val".getBytes(), System.currentTimeMillis() + 3600000));

        // List
        RedisList list = new RedisList();
        list.rpush("item".getBytes());
        store.put("l1", new RedisData(RedisDataType.LIST, list));

        // Set
        RedisSet set = new RedisSet();
        set.sadd("mem".getBytes());
        store.put("set1", new RedisData(RedisDataType.SET, set));

        // ZSet
        RedisZSet zset = new RedisZSet();
        zset.zadd(9.9, "top".getBytes());
        store.put("z1", new RedisData(RedisDataType.ZSET, zset));

        // Hash
        RedisHash hash = new RedisHash();
        hash.hset("field", "data".getBytes());
        store.put("h1", new RedisData(RedisDataType.HASH, hash));

        File rdbFile = tempDir.resolve("test_mixed.rdb").toFile();
        new RdbSaver().save(store, rdbFile, 0);

        RdbLoader.LoadResult result = new RdbLoader().load(rdbFile);
        assertNotNull(result);
        assertEquals(5, result.getData().size());
        assertArrayEquals("val".getBytes(), result.getData().get("s1").getValue(byte[].class));
        assertEquals(1L, result.getData().get("l1").getValue(RedisList.class).llen());
        assertEquals(1L, result.getData().get("set1").getValue(RedisSet.class).scard());
        assertEquals(1L, result.getData().get("z1").getValue(RedisZSet.class).zcard());
        assertEquals(1L, result.getData().get("h1").getValue(RedisHash.class).hlen());
    }

    @Test
    void testRdbLoadNonExistentFile(@TempDir Path tempDir) throws Exception {
        RdbLoader.LoadResult result = new RdbLoader().load(tempDir.resolve("nonexistent.rdb").toFile());
        assertNull(result);
    }

    @Test
    void testRdbLoadInvalidMagic(@TempDir Path tempDir) throws Exception {
        File bad = tempDir.resolve("bad.rdb").toFile();
        try (OutputStream os = new FileOutputStream(bad)) {
            os.write("BADMAGIC0009".getBytes());
        }
        assertThrows(IOException.class, () -> new RdbLoader().load(bad));
    }

    // ==================== AOF 加载测试 ====================

    @Test
    void testAofLoadCommands(@TempDir Path tempDir) throws Exception {
        // 手工构造 AOF 内容（RESP 协议格式），直接写字节
        Path aofPath = tempDir.resolve("test.aof");
        byte[] crlf = new byte[]{'\r', '\n'};
        byte[] cmd1 = join(
                "*3".getBytes(), crlf, "$3".getBytes(), crlf, "SET".getBytes(), crlf,
                "$3".getBytes(), crlf, "key".getBytes(), crlf,
                "$5".getBytes(), crlf, "value".getBytes(), crlf);
        byte[] cmd2 = join(
                "*2".getBytes(), crlf, "$3".getBytes(), crlf, "GET".getBytes(), crlf,
                "$3".getBytes(), crlf, "key".getBytes(), crlf);
        byte[] cmd3 = join(
                "*5".getBytes(), crlf, "$4".getBytes(), crlf, "SADD".getBytes(), crlf,
                "$5".getBytes(), crlf, "myset".getBytes(), crlf,
                "$3".getBytes(), crlf, "one".getBytes(), crlf,
                "$3".getBytes(), crlf, "two".getBytes(), crlf,
                "$5".getBytes(), crlf, "three".getBytes(), crlf);
        byte[] content = join(cmd1, cmd2, cmd3);
        java.nio.file.Files.write(aofPath, content);
        System.out.println("AOF content length: " + content.length + " bytes");
        System.out.println("AOF hex: " + bytesToHex(content));

        AofLoader loader = new AofLoader();
        List<List<String>> commands = loader.loadCommands(aofPath);

        System.out.println("Parsed " + commands.size() + " commands:");
        for (int i = 0; i < commands.size(); i++) {
            System.out.println("  [" + i + "]: " + commands.get(i));
        }

        assertNotNull(commands);
        assertEquals(3, commands.size());

        // 第一条: SET key value
        assertEquals("SET", commands.get(0).get(0));
        assertEquals("key", commands.get(0).get(1));
        assertEquals("value", commands.get(0).get(2));

        // 第二条: GET key
        assertEquals("GET", commands.get(1).get(0));
        assertEquals("key", commands.get(1).get(1));

        // 第三条: SADD myset one two three
        assertEquals("SADD", commands.get(2).get(0));
        assertEquals("myset", commands.get(2).get(1));
        assertEquals("one", commands.get(2).get(2));
        assertEquals("two", commands.get(2).get(3));
        assertEquals("three", commands.get(2).get(4));
    }

    @Test
    void testAofLoadLargeCommands(@TempDir Path tempDir) throws Exception {
        // 大值测试
        StringBuilder sb = new StringBuilder();
        sb.append("*3\r\n$3\r\nSET\r\n$10\r\nlarge_key_\r\n$1000\r\n");
        char[] bigVal = new char[1000];
        Arrays.fill(bigVal, 'A');
        sb.append(bigVal);
        sb.append("\r\n");

        Path aofPath = tempDir.resolve("large.aof");
        java.nio.file.Files.write(aofPath, sb.toString().getBytes(StandardCharsets.UTF_8));

        AofLoader loader = new AofLoader();
        List<List<String>> commands = loader.loadCommands(aofPath);

        assertNotNull(commands);
        assertEquals(1, commands.size());
        assertEquals("SET", commands.get(0).get(0));
        assertEquals(1000, commands.get(0).get(2).length());
        assertTrue(commands.get(0).get(2).chars().allMatch(c -> c == 'A'));
    }

    @Test
    void testAofLoadEmptyFile(@TempDir Path tempDir) throws Exception {
        Path aofPath = tempDir.resolve("empty.aof");
        java.nio.file.Files.write(aofPath, new byte[0]);

        AofLoader loader = new AofLoader();
        List<List<String>> commands = loader.loadCommands(aofPath);
        assertNotNull(commands);
        assertTrue(commands.isEmpty());
    }

    @Test
    void testAofLoadNonExistentFile(@TempDir Path tempDir) throws Exception {
        Path aofPath = tempDir.resolve("nonexistent.aof");
        AofLoader loader = new AofLoader();
        List<List<String>> commands = loader.loadCommands(aofPath);
        assertNotNull(commands);
        assertTrue(commands.isEmpty());
    }

    @Test
    void testAofLoadBinaryData(@TempDir Path tempDir) throws Exception {
        // 二进制 key 和 value（含 null 字节）
        byte[] keyWithNull = "key\0bin".getBytes(StandardCharsets.UTF_8);
        byte[] valWithNull = "val\0data".getBytes(StandardCharsets.UTF_8);

        StringBuilder sb = new StringBuilder();
        sb.append("*3\r\n$3\r\nSET\r\n$").append(keyWithNull.length).append("\r\n");
        sb.append(new String(keyWithNull, StandardCharsets.UTF_8)).append("\r\n");
        sb.append("$").append(valWithNull.length).append("\r\n");
        sb.append(new String(valWithNull, StandardCharsets.UTF_8)).append("\r\n");

        Path aofPath = tempDir.resolve("binary.aof");
        java.nio.file.Files.write(aofPath, sb.toString().getBytes(StandardCharsets.UTF_8));

        AofLoader loader = new AofLoader();
        List<List<String>> commands = loader.loadCommands(aofPath);

        assertNotNull(commands);
        assertEquals(1, commands.size());
        assertEquals("SET", commands.get(0).get(0));
        assertEquals(new String(keyWithNull, StandardCharsets.UTF_8), commands.get(0).get(1));
        assertEquals(new String(valWithNull, StandardCharsets.UTF_8), commands.get(0).get(2));
    }

    @Test
    void testRdbSaveAndLoadWithExpiry(@TempDir Path tempDir) throws Exception {
        // 已过期 key 不应被加载
        Map<String, RedisData> store = new HashMap<>();
        store.put("alive", new RedisData(RedisDataType.STRING, "ok".getBytes(), System.currentTimeMillis() + 3600000));
        store.put("expired", new RedisData(RedisDataType.STRING, "gone".getBytes(), System.currentTimeMillis() - 1000));

        File rdbFile = tempDir.resolve("test_expiry.rdb").toFile();
        new RdbSaver().save(store, rdbFile, 0);

        RdbLoader.LoadResult result = new RdbLoader().load(rdbFile);
        assertNotNull(result);
        assertEquals(1, result.getData().size());
        assertFalse(result.getData().containsKey("expired"));
        assertArrayEquals("ok".getBytes(), result.getData().get("alive").getValue(byte[].class));
    }

    // ==================== 边界情况测试 ====================

    @Test
    void testAofCorruptedData(@TempDir Path tempDir) throws Exception {
        // AOF 包含部分损坏的 RESP 内容：*3 声明 3 个参数但只提供了 2 个即被截断
        // 损坏的命令应被跳过，后续有效命令依然可被解析
        Path aofPath = tempDir.resolve("corrupted.aof");
        byte[] crlf = new byte[]{'\r', '\n'};
        // *3\r\n$3\r\nSET\r\n$1\r\na 缺少第 3 个参数，紧接着 *2\r\n$3\r\nGET\r\n$1\r\na\r\n
        byte[] content = join(
                "*3".getBytes(), crlf, "$3".getBytes(), crlf, "SET".getBytes(), crlf,
                "$1".getBytes(), crlf, "a".getBytes(),  // 缺少第 3 个参数的 $ 头
                "*2".getBytes(), crlf, "$3".getBytes(), crlf, "GET".getBytes(), crlf,
                "$1".getBytes(), crlf, "a".getBytes(), crlf);
        java.nio.file.Files.write(aofPath, content);

        AofLoader loader = new AofLoader();
        List<List<String>> commands = loader.loadCommands(aofPath);

        assertNotNull(commands);
        // 损坏的 SET 命令被跳过，只有 GET a 被解析
        assertEquals(1, commands.size());
        assertEquals("GET", commands.get(0).get(0));
        assertEquals("a", commands.get(0).get(1));
    }

    @Test
    void testAofWithJunkBytes(@TempDir Path tempDir) throws Exception {
        // AOF 文件开头包含非 RESP 的垃圾字节，解析器应跳过非 * 开头的字节
        Path aofPath = tempDir.resolve("junk.aof");
        byte[] crlf = new byte[]{'\r', '\n'};
        byte[] content = join(
                "JUNK".getBytes(), crlf,
                "*2".getBytes(), crlf, "$3".getBytes(), crlf, "GET".getBytes(), crlf,
                "$1".getBytes(), crlf, "a".getBytes(), crlf);
        java.nio.file.Files.write(aofPath, content);

        AofLoader loader = new AofLoader();
        List<List<String>> commands = loader.loadCommands(aofPath);

        assertNotNull(commands);
        assertEquals(1, commands.size());
        assertEquals("GET", commands.get(0).get(0));
        assertEquals("a", commands.get(0).get(1));
    }

    @Test
    void testRdbWithExpiredKeyAtLoad(@TempDir Path tempDir) throws Exception {
        // 已过期 key（过期时间在过去）在 RDB 加载时不应被加载
        Map<String, RedisData> store = new HashMap<>();
        store.put("valid", new RedisData(RedisDataType.STRING, "ok".getBytes(), System.currentTimeMillis() + 3600000));
        store.put("past", new RedisData(RedisDataType.STRING, "gone".getBytes(), System.currentTimeMillis() - 5000));

        File rdbFile = tempDir.resolve("test_expired_at_load.rdb").toFile();
        new RdbSaver().save(store, rdbFile, 0);

        RdbLoader.LoadResult result = new RdbLoader().load(rdbFile);
        assertNotNull(result);
        assertEquals(1, result.getData().size());
        assertFalse(result.getData().containsKey("past"));
        assertArrayEquals("ok".getBytes(), result.getData().get("valid").getValue(byte[].class));
    }

    @Test
    void testAofMultipleEmptyCommands(@TempDir Path tempDir) throws Exception {
        // AOF 包含大量 \r\n 空行，解析器应跳过非 * 字符
        Path aofPath = tempDir.resolve("empty_cmds.aof");
        byte[] crlf = new byte[]{'\r', '\n'};
        byte[] content = join(
                crlf, crlf, crlf,
                "*1".getBytes(), crlf, "$4".getBytes(), crlf, "PING".getBytes(), crlf,
                crlf, crlf);
        java.nio.file.Files.write(aofPath, content);

        AofLoader loader = new AofLoader();
        List<List<String>> commands = loader.loadCommands(aofPath);

        assertNotNull(commands);
        assertEquals(1, commands.size());
        assertEquals("PING", commands.get(0).get(0));
    }

    @Test
    void testRdbEmptyStore(@TempDir Path tempDir) throws Exception {
        // 空 store 应成功保存和加载，返回 0 个 key
        Map<String, RedisData> store = new HashMap<>();

        File rdbFile = tempDir.resolve("empty.rdb").toFile();
        new RdbSaver().save(store, rdbFile, 0);

        RdbLoader.LoadResult result = new RdbLoader().load(rdbFile);
        assertNotNull(result);
        assertEquals(0, result.getData().size());
    }

    @Test
    void testAofLoadWithNullValues(@TempDir Path tempDir) throws Exception {
        // AOF 中值长度为 0（$0\r\n\r\n），应解析为空字符串
        Path aofPath = tempDir.resolve("null_val.aof");
        byte[] crlf = new byte[]{'\r', '\n'};
        byte[] content = join(
                "*3".getBytes(), crlf, "$3".getBytes(), crlf, "SET".getBytes(), crlf,
                "$3".getBytes(), crlf, "key".getBytes(), crlf,
                "$0".getBytes(), crlf, crlf);
        java.nio.file.Files.write(aofPath, content);

        AofLoader loader = new AofLoader();
        List<List<String>> commands = loader.loadCommands(aofPath);

        assertNotNull(commands);
        assertEquals(1, commands.size());
        assertEquals("SET", commands.get(0).get(0));
        assertEquals("key", commands.get(0).get(1));
        assertEquals("", commands.get(0).get(2));
    }

    // ==================== 辅助方法 ====================

    private static byte[] join(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }
}
