package com.qkinfotech.bizwax.sdcs.blocking;

import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockingManagerTest {

    private static final BlockingManager manager = BlockingManager.getInstance();

    @BeforeAll
    static void setup() {
        SDCSConfig config = new SDCSConfig();
        config.setPersistenceType(SDCSConfig.PersistenceType.NONE);
        config.setDataDir("target/test-data/blocking-manager-test");
    }

    @Test
    void testBasicBlockAndNotifyKey() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RedisMessage> result = new AtomicReference<>();

        BlockingManager.BlockingContext ctx = new BlockingManager.BlockingContext(
                0,
                (store, key) -> RedisMessage.simpleString("OK"),
                msg -> {
                    result.set(msg);
                    latch.countDown();
                }
        );

        manager.block("basic-key", ctx);
        manager.notifyKey("basic-key");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Callback was not invoked after notifyKey");
        assertNotNull(result.get());
        assertEquals("OK", result.get().asString());
    }

    @Test
    void testMultipleContextsSameKey() throws Exception {
        int count = 3;
        CountDownLatch latch = new CountDownLatch(count);
        List<RedisMessage> results = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            int idx = i;
            BlockingManager.BlockingContext ctx = new BlockingManager.BlockingContext(
                    0,
                    (store, key) -> RedisMessage.integer(idx),
                    msg -> {
                        synchronized (results) {
                            results.add(msg);
                        }
                        latch.countDown();
                    }
            );
            manager.block("multi-key", ctx);
        }

        manager.notifyKey("multi-key");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all callbacks were invoked");
        assertEquals(count, results.size());
    }

    @Test
    void testNotifyKeyWithNoWaiters() {
        manager.notifyKey("nonexistent-key");
    }

    @Test
    void testHandlerReturnsNullCallbackNotCalled() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        BlockingManager.BlockingContext ctx = new BlockingManager.BlockingContext(
                0,
                (store, key) -> null,
                msg -> latch.countDown()
        );

        manager.block("null-handler-key", ctx);
        manager.notifyKey("null-handler-key");

        assertFalse(latch.await(500, TimeUnit.MILLISECONDS),
                "Callback should not be invoked when handler returns null");
    }

    @Test
    void testHandlerExceptionDoesNotAffectOthers() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        List<RedisMessage> results = new ArrayList<>();

        BlockingManager.BlockingContext ctx1 = new BlockingManager.BlockingContext(
                0,
                (store, key) -> RedisMessage.simpleString("first"),
                msg -> {
                    synchronized (results) {
                        results.add(msg);
                    }
                    latch.countDown();
                }
        );
        manager.block("exception-key", ctx1);

        BlockingManager.BlockingContext ctx2 = new BlockingManager.BlockingContext(
                0,
                (store, key) -> {
                    throw new RuntimeException("handler error");
                },
                msg -> {
                }
        );
        manager.block("exception-key", ctx2);

        BlockingManager.BlockingContext ctx3 = new BlockingManager.BlockingContext(
                0,
                (store, key) -> RedisMessage.simpleString("third"),
                msg -> {
                    synchronized (results) {
                        results.add(msg);
                    }
                    latch.countDown();
                }
        );
        manager.block("exception-key", ctx3);

        manager.notifyKey("exception-key");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all successful callbacks were invoked");
        assertEquals(2, results.size());
    }

    @Test
    void testMaxWaitersPerKey() throws Exception {
        int maxWaiters = 1000;
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<RedisMessage> errorResult = new AtomicReference<>();

        for (int i = 0; i < maxWaiters; i++) {
            BlockingManager.BlockingContext ctx = new BlockingManager.BlockingContext(
                    0,
                    (store, key) -> RedisMessage.simpleString("ok"),
                    msg -> {
                    }
            );
            manager.block("max-waiters-key", ctx);
        }

        BlockingManager.BlockingContext errorCtx = new BlockingManager.BlockingContext(
                0,
                (store, key) -> RedisMessage.simpleString("error"),
                msg -> {
                    errorResult.set(msg);
                    errorLatch.countDown();
                }
        );
        manager.block("max-waiters-key", errorCtx);

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "Error callback was not invoked");
        assertNotNull(errorResult.get());
        assertEquals(RedisMessage.Type.ERROR, errorResult.get().getType());
        assertTrue(errorResult.get().asString().contains("too many blocking clients"));

        manager.notifyKey("max-waiters-key");
    }

    @Test
    void testMultipleNotifyKeySameKey() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RedisMessage> result = new AtomicReference<>();

        BlockingManager.BlockingContext ctx = new BlockingManager.BlockingContext(
                0,
                (store, key) -> RedisMessage.simpleString("first-call"),
                msg -> {
                    result.set(msg);
                    latch.countDown();
                }
        );

        manager.block("multi-notify-key", ctx);
        manager.notifyKey("multi-notify-key");

        assertTrue(latch.await(5, TimeUnit.SECONDS), "First notifyKey callback was not invoked");
        assertNotNull(result.get());

        manager.notifyKey("multi-notify-key");
    }

    @Test
    void testTimeoutTriggersCallback() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RedisMessage> result = new AtomicReference<>();

        BlockingManager.BlockingContext ctx = new BlockingManager.BlockingContext(
                100,
                (store, key) -> {
                    throw new RuntimeException("Handler should not be called on timeout");
                },
                msg -> {
                    result.set(msg);
                    latch.countDown();
                }
        );

        manager.block("timeout-key", ctx);

        assertTrue(latch.await(2, TimeUnit.SECONDS), "Timeout callback was not invoked");
        assertNotNull(result.get());
        assertEquals(RedisMessage.Type.ARRAY, result.get().getType());
        assertNull(result.get().getElements());
    }
}
