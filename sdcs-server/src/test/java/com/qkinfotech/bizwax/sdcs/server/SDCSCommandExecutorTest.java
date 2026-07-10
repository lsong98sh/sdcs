package com.qkinfotech.bizwax.sdcs.server;

import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SDCSCommandExecutor 测试。
 * <p>
 * SDCSCommandExecutor 的静态初始化块在类首次加载时执行，
 * 会读取 {@link SDCSConfig#getInstance()} 创建 PersistenceManager。
 * 因此必须在 {@link BeforeAll} 中提前初始化 SDCSConfig 单例。
 */
class SDCSCommandExecutorTest {

    private static Path tempDataDir;

    @BeforeAll
    static void initConfig() throws IOException {
        tempDataDir = Files.createTempDirectory("sdcs-cmd-exec-test-");
        SDCSConfig config = new SDCSConfig();
        config.setPersistenceType(SDCSConfig.PersistenceType.NONE);
        config.setDataDir(tempDataDir.toString());
        // SDCSCommandExecutor 的静态初始化块将在首次引用时读取此配置
    }

    @AfterAll
    static void cleanup() throws IOException {
        // 停止 PersistenceManager 后台线程
        try {
            PersistenceManager pm = SDCSCommandExecutor.getPersistenceManager();
            if (pm != null) {
                pm.stop();
            }
        } catch (Exception e) {
            // ignore cleanup errors
        }
        // 清理临时目录
        if (tempDataDir != null && Files.exists(tempDataDir)) {
            try (Stream<Path> walk = Files.walk(tempDataDir)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
            }
        }
    }

    @BeforeEach
    void setUp() {
        // 清理可能残留的脏数据保证测试隔离
        SDCSCommandExecutor.getDatabaseManager().flushAll();
    }

    // ==================== 辅助方法 ====================

    private static List<RedisMessage> args(String... values) {
        return java.util.Arrays.stream(values)
                .map(s -> RedisMessage.bulkString(s.getBytes()))
                .toList();
    }

    private static String asString(RedisMessage msg) {
        return msg != null ? msg.asString() : null;
    }

    // ==================== PING ====================

    @Test
    void executePingReturnsPong() {
        RedisMessage result = SDCSCommandExecutor.execute("PING", List.of());
        assertEquals("PONG", asString(result));
    }

    @Test
    void executePingWithArgReturnsArg() {
        RedisMessage result = SDCSCommandExecutor.execute("PING", args("hello"));
        assertEquals("hello", asString(result));
    }

    // ==================== SET / GET ====================

    @Test
    void executeSetReturnsOk() {
        RedisMessage result = SDCSCommandExecutor.execute("SET", args("mykey", "myvalue"));
        assertEquals("OK", asString(result));
    }

    @Test
    void executeSetAndGet() {
        SDCSCommandExecutor.execute("SET", args("k1", "v1"));
        RedisMessage result = SDCSCommandExecutor.execute("GET", args("k1"));
        assertEquals("v1", asString(result));
    }

    @Test
    void executeGetNonExistentReturnsNull() {
        RedisMessage result = SDCSCommandExecutor.execute("GET", args("nonexistent"));
        assertTrue(result.isNullBulkString());
    }

    @Test
    void executeSetOverwritesExisting() {
        SDCSCommandExecutor.execute("SET", args("k2", "old"));
        SDCSCommandExecutor.execute("SET", args("k2", "new"));
        assertEquals("new", asString(SDCSCommandExecutor.execute("GET", args("k2"))));
    }

    // ==================== 带回调的 execute ====================

    @Test
    void executeWithCallbackWhenDataAvailableReturnsDirectly() {
        // 先往列表放数据
        SDCSCommandExecutor.execute("RPUSH", args("cb_list", "item1", "item2"));

        AtomicReference<RedisMessage> callbackResult = new AtomicReference<>();
        // BLPOP 在数据存在时直接返回，不会触发回调
        RedisMessage result = SDCSCommandExecutor.execute("BLPOP", args("cb_list", "1"),
                callbackResult::set);

        assertNotNull(result, "BLPOP should return immediately when data exists");
        assertEquals(RedisMessage.Type.ARRAY, result.getType());
        assertEquals("cb_list", result.getElements().get(0).asString());
        assertEquals("item1", result.getElements().get(1).asString());
        // 回调不应被调用（数据直接返回）
        assertNull(callbackResult.get(), "callback should not be invoked when data is immediately available");
    }

    @Test
    void executeWithTimeoutZeroReturnsNullArray() {
        // 空列表，timeout=0 返回 null array（Type.ARRAY, elements=null）
        AtomicReference<RedisMessage> callbackResult = new AtomicReference<>();
        RedisMessage result = SDCSCommandExecutor.execute("BLPOP", args("empty_list", "0"),
                callbackResult::set);
        assertNotNull(result, "BLPOP should return a non-null message");
        assertEquals(RedisMessage.Type.ARRAY, result.getType(),
                "BLPOP with timeout 0 and no data should return ARRAY type");
        assertNull(result.getElements(), "null array should have null elements");
    }

    // ==================== 静态访问器 ====================

    @Test
    void getStoreReturnsNonNull() {
        assertNotNull(SDCSCommandExecutor.getStore());
    }

    @Test
    void getDatabaseManagerReturnsNonNull() {
        assertNotNull(SDCSCommandExecutor.getDatabaseManager());
    }

    @Test
    void getPersistenceManagerReturnsNonNull() {
        assertNotNull(SDCSCommandExecutor.getPersistenceManager());
    }

    // ==================== 其他命令 ====================

    @Test
    void executeDel() {
        SDCSCommandExecutor.execute("SET", args("del_key", "value"));
        assertEquals(1L, SDCSCommandExecutor.execute("DEL", args("del_key")).getIntegerValue());
        assertTrue(SDCSCommandExecutor.execute("GET", args("del_key")).isNullBulkString());
    }

    @Test
    void executeExists() {
        SDCSCommandExecutor.execute("SET", args("ex_key", "v"));
        assertEquals(1L, SDCSCommandExecutor.execute("EXISTS", args("ex_key")).getIntegerValue());
        assertEquals(0L, SDCSCommandExecutor.execute("EXISTS", args("nonexistent")).getIntegerValue());
    }

    @Test
    void executeEcho() {
        RedisMessage result = SDCSCommandExecutor.execute("ECHO", args("hello echo"));
        assertEquals("hello echo", asString(result));
    }

    @Test
    void executeUnknownCommandReturnsError() {
        RedisMessage result = SDCSCommandExecutor.execute("UNKNOWNCMD", List.of());
        assertEquals(RedisMessage.Type.ERROR, result.getType());
    }
}
