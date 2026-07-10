package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.common.*;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AofRewriterTest {

    @TempDir
    Path tempDir;

    @Test
    void testRewriteStringKey() throws IOException {
        DatabaseManager dbManager = new DatabaseManager();
        MemoryStore store = dbManager.getStore(0);
        store.put("key", "value".getBytes(StandardCharsets.UTF_8));

        File aofFile = tempDir.resolve("string.aof").toFile();
        AofRewriter rewriter = new AofRewriter(dbManager);
        rewriter.rewrite(aofFile);

        byte[] content = Files.readAllBytes(aofFile.toPath());
        String resp = new String(content, StandardCharsets.UTF_8);

        // Verify SET command is written in RESP format
        assertTrue(resp.contains("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n"));
    }

    @Test
    void testRewriteMultipleDataTypes() throws IOException {
        DatabaseManager dbManager = new DatabaseManager();
        MemoryStore store = dbManager.getStore(0);

        store.put("str", "abc".getBytes(StandardCharsets.UTF_8));
        store.rpush("list", "a".getBytes(StandardCharsets.UTF_8), "b".getBytes(StandardCharsets.UTF_8));
        store.hset("hash", "f1", "v1".getBytes(StandardCharsets.UTF_8));
        store.sadd("set", "m1".getBytes(StandardCharsets.UTF_8));
        store.zadd("zset", 1.5, "m1".getBytes(StandardCharsets.UTF_8));

        File aofFile = tempDir.resolve("multi.aof").toFile();
        AofRewriter rewriter = new AofRewriter(dbManager);
        rewriter.rewrite(aofFile);

        byte[] content = Files.readAllBytes(aofFile.toPath());
        String resp = new String(content, StandardCharsets.UTF_8);

        // Verify SELECT command
        assertTrue(resp.contains("*2\r\n$6\r\nSELECT\r\n$1\r\n0\r\n"));
        // Verify STRING
        assertTrue(resp.contains("*3\r\n$3\r\nSET\r\n$3\r\nstr\r\n$3\r\nabc\r\n"));
        // Verify LIST
        assertTrue(resp.contains("*4\r\n$5\r\nRPUSH\r\n$4\r\nlist\r\n$1\r\na\r\n$1\r\nb\r\n"));
        // Verify HASH
        assertTrue(resp.contains("*4\r\n$4\r\nHSET\r\n$4\r\nhash\r\n$2\r\nf1\r\n$2\r\nv1\r\n"));
        // Verify SET
        assertTrue(resp.contains("*3\r\n$4\r\nSADD\r\n$3\r\nset\r\n$2\r\nm1\r\n"));
        // Verify ZSET
        assertTrue(resp.contains("*4\r\n$4\r\nZADD\r\n$4\r\nzset\r\n$3\r\n1.5\r\n$2\r\nm1\r\n"));
    }

    @Test
    void testRewriteEmptyStore() throws IOException {
        DatabaseManager dbManager = new DatabaseManager();

        File aofFile = tempDir.resolve("empty.aof").toFile();
        AofRewriter rewriter = new AofRewriter(dbManager);
        rewriter.rewrite(aofFile);

        byte[] content = Files.readAllBytes(aofFile.toPath());
        // Empty store should produce empty AOF (no SELECT commands)
        assertEquals(0, content.length);
    }

    @Test
    void testRewriteKeyWithExpiry() throws IOException {
        DatabaseManager dbManager = new DatabaseManager();
        MemoryStore store = dbManager.getStore(0);

        long expireAt = System.currentTimeMillis() + 60000;
        store.getStore().put("expKey", new RedisData(
                RedisDataType.STRING,
                "v".getBytes(StandardCharsets.UTF_8),
                expireAt));

        File aofFile = tempDir.resolve("expiry.aof").toFile();
        AofRewriter rewriter = new AofRewriter(dbManager);
        rewriter.rewrite(aofFile);

        byte[] content = Files.readAllBytes(aofFile.toPath());
        String resp = new String(content, StandardCharsets.UTF_8);

        // Should contain SET command
        assertTrue(resp.contains("*3\r\n$3\r\nSET\r\n$6\r\nexpKey\r\n$1\r\nv\r\n"));
        // Should contain PEXPIREAT command with the timestamp
        assertTrue(resp.contains("*3\r\n$9\r\nPEXPIREAT\r\n$6\r\nexpKey\r\n"));
        assertTrue(resp.contains(String.valueOf(expireAt)));
    }

    @Test
    void testRewriteExpiredKeySkipped() throws IOException {
        DatabaseManager dbManager = new DatabaseManager();
        MemoryStore store = dbManager.getStore(0);

        // Add an expired key (expireAtMs in the past)
        store.getStore().put("expiredKey", new RedisData(
                RedisDataType.STRING,
                "v".getBytes(StandardCharsets.UTF_8),
                System.currentTimeMillis() - 10000));
        // Add a valid key
        store.put("validKey", "valid".getBytes(StandardCharsets.UTF_8));

        File aofFile = tempDir.resolve("expired.aof").toFile();
        AofRewriter rewriter = new AofRewriter(dbManager);
        rewriter.rewrite(aofFile);

        byte[] content = Files.readAllBytes(aofFile.toPath());
        String resp = new String(content, StandardCharsets.UTF_8);

        // Expired key should not be in AOF
        assertFalse(resp.contains("expiredKey"));
        // Valid key should be in AOF
        assertTrue(resp.contains("validKey"));
    }

    @Test
    void testRewriteEmptyCollectionsSkipped() throws IOException {
        DatabaseManager dbManager = new DatabaseManager();
        MemoryStore store = dbManager.getStore(0);

        // Add empty collections directly to the store
        store.getStore().put("emptyList", new RedisData(RedisDataType.LIST, new RedisList()));
        store.getStore().put("emptySet", new RedisData(RedisDataType.SET, new RedisSet()));
        store.getStore().put("emptyHash", new RedisData(RedisDataType.HASH, new RedisHash()));
        store.getStore().put("emptyZSet", new RedisData(RedisDataType.ZSET, new RedisZSet()));
        // Add a valid key to ensure SELECT is written
        store.put("validKey", "v".getBytes(StandardCharsets.UTF_8));

        File aofFile = tempDir.resolve("emptyColl.aof").toFile();
        AofRewriter rewriter = new AofRewriter(dbManager);
        rewriter.rewrite(aofFile);

        byte[] content = Files.readAllBytes(aofFile.toPath());
        String resp = new String(content, StandardCharsets.UTF_8);

        // Empty collections should not appear in AOF
        assertFalse(resp.contains("emptyList"));
        assertFalse(resp.contains("emptySet"));
        assertFalse(resp.contains("emptyHash"));
        assertFalse(resp.contains("emptyZSet"));
        // Valid key should appear
        assertTrue(resp.contains("validKey"));
    }

    @Test
    void testRewriteMultipleDb() throws IOException {
        DatabaseManager dbManager = new DatabaseManager();
        MemoryStore store0 = dbManager.getStore(0);
        MemoryStore store1 = dbManager.getStore(1);

        store0.put("k0", "v0".getBytes(StandardCharsets.UTF_8));
        store1.put("k1", "v1".getBytes(StandardCharsets.UTF_8));

        File aofFile = tempDir.resolve("multiDb.aof").toFile();
        AofRewriter rewriter = new AofRewriter(dbManager);
        rewriter.rewrite(aofFile);

        byte[] content = Files.readAllBytes(aofFile.toPath());
        String resp = new String(content, StandardCharsets.UTF_8);

        // Both SELECT commands should be present
        assertTrue(resp.contains("*2\r\n$6\r\nSELECT\r\n$1\r\n0\r\n"));
        assertTrue(resp.contains("*2\r\n$6\r\nSELECT\r\n$1\r\n1\r\n"));
        // Both keys should appear
        assertTrue(resp.contains("k0"));
        assertTrue(resp.contains("k1"));
    }
}
