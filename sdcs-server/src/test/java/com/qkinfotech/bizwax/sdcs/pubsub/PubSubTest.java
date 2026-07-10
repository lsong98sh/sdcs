package com.qkinfotech.bizwax.sdcs.pubsub;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PubSubManager 的多线程 publish/subscribe 测试。
 * <p>
 * 模拟两个线程：一个 subscribe 到频道，另一个 publish 消息。
 * 支持精确频道和模式匹配（psubscribe）。
 * <p>
 * 注意：PubSubManager 是单例，callback 使用引用比较。
 * 每次 unsubscribe 必须传入与 subscribe 相同的 Consumer 实例。
 */
class PubSubTest {

    private static final int MESSAGE_COUNT = 10;
    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void testSubscribeAndPublish() throws Exception {
        PubSubManager mgr = PubSubManager.getInstance();
        String channel = "test:basic:" + System.nanoTime();

        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(MESSAGE_COUNT);

        Consumer<RedisMessage> cb = msg -> {
            received.add(msg.asString());
            latch.countDown();
        };

        mgr.subscribe(channel, cb);
        try {
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                mgr.publish(channel, RedisMessage.bulkString(("msg-" + i).getBytes()));
            }

            boolean allReceived = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(allReceived, "Should receive all " + MESSAGE_COUNT + " messages within timeout");

            assertEquals(MESSAGE_COUNT, received.size());
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                assertEquals("msg-" + i, received.get(i));
            }
        } finally {
            mgr.unsubscribe(channel, cb);
        }
    }

    @Test
    void testPSubscribeAndPublish() throws Exception {
        PubSubManager mgr = PubSubManager.getInstance();
        String pattern = "test:pattern:*";
        String matchingChannel = "test:pattern:match";

        List<String> received = new CopyOnWriteArrayList<>();
        List<String> receivedPatterns = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        Consumer<RedisMessage> cb = msg -> {
            List<RedisMessage> parts = msg.getElements();
            receivedPatterns.add(parts.get(1).asString());
            received.add(parts.get(3).asString());
            latch.countDown();
        };

        mgr.psubscribe(pattern, cb);
        try {
            mgr.publish(matchingChannel, RedisMessage.bulkString("pmsg1".getBytes()));
            mgr.publish(matchingChannel, RedisMessage.bulkString("pmsg2".getBytes()));
            mgr.publish("test:nomatch", RedisMessage.bulkString("should_not_receive".getBytes()));
            mgr.publish(matchingChannel, RedisMessage.bulkString("pmsg3".getBytes()));

            boolean allReceived = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(allReceived, "Should receive 3 matching psubscribe messages");
            assertEquals(3, received.size());

            for (String rp : receivedPatterns) {
                assertEquals(pattern, rp);
            }
        } finally {
            mgr.punsubscribe(pattern, cb);
        }
    }

    @Test
    void testPublishToNonExistentChannel() {
        PubSubManager mgr = PubSubManager.getInstance();
        long count = mgr.publish("nonexistent:" + System.nanoTime(),
                RedisMessage.bulkString("data".getBytes()));
        assertEquals(0, count, "No subscribers should return 0");
    }

    @Test
    void testMultipleSubscribers() throws Exception {
        PubSubManager mgr = PubSubManager.getInstance();
        String channel = "test:multi:" + System.nanoTime();

        int subscriberCount = 5;
        AtomicInteger totalReceived = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(subscriberCount * 3);
        List<Consumer<RedisMessage>> callbacks = new ArrayList<>();

        for (int i = 0; i < subscriberCount; i++) {
            Consumer<RedisMessage> cb = msg -> {
                totalReceived.incrementAndGet();
                latch.countDown();
            };
            callbacks.add(cb);
            mgr.subscribe(channel, cb);
        }

        try {
            for (int i = 0; i < 3; i++) {
                mgr.publish(channel, RedisMessage.bulkString(("data-" + i).getBytes()));
            }

            boolean done = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(done, "All subscribers should receive all messages");
            assertEquals(subscriberCount * 3, totalReceived.get());
        } finally {
            for (Consumer<RedisMessage> cb : callbacks) {
                mgr.unsubscribe(channel, cb);
            }
        }
    }

    @Test
    void testSubscribeAndPublishConcurrent() throws Exception {
        PubSubManager mgr = PubSubManager.getInstance();
        String channel = "test:concurrent:" + System.nanoTime();

        List<String> received = new ArrayList<>();
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        Consumer<RedisMessage> cb = msg -> {
            synchronized (received) {
                received.add(msg.asString());
                if (received.size() == MESSAGE_COUNT) {
                    done.countDown();
                }
            }
        };

        Thread subscriber = new Thread(() -> {
            mgr.subscribe(channel, cb);
            subscribed.countDown();
        }, "pubsub-subscriber");
        subscriber.start();

        assertTrue(subscribed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Subscriber should register within timeout");

        Thread publisher = new Thread(() -> {
            try {
                Thread.sleep(100);
                for (int i = 0; i < MESSAGE_COUNT; i++) {
                    mgr.publish(channel, RedisMessage.bulkString(("concurrent-" + i).getBytes()));
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            published.countDown();
        }, "pubsub-publisher");
        publisher.start();

        try {
            boolean completed = done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(completed, "Concurrent subscribe/publish should complete within timeout");

            assertEquals(MESSAGE_COUNT, received.size());
            for (int i = 0; i < MESSAGE_COUNT; i++) {
                assertEquals("concurrent-" + i, received.get(i));
            }
        } finally {
            subscriber.interrupt();
            publisher.join(1000);
            mgr.unsubscribe(channel, cb);
        }
    }

    @Test
    void testUnsubscribe() throws Exception {
        PubSubManager mgr = PubSubManager.getInstance();
        String channel = "test:unsub:" + System.nanoTime();

        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);
        Consumer<RedisMessage> cb = msg -> {
            count.incrementAndGet();
            latch.countDown();
        };

        mgr.subscribe(channel, cb);
        try {
            mgr.publish(channel, RedisMessage.bulkString("before".getBytes()));
            mgr.publish(channel, RedisMessage.bulkString("before2".getBytes()));

            boolean done = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(done, "Should receive 2 messages before unsub");

            // 取消订阅 — 使用同一个 cb 引用
            mgr.unsubscribe(channel, cb);

            // 取消后再发布（不应被接收）
            mgr.publish(channel, RedisMessage.bulkString("after".getBytes()));

            Thread.sleep(200);
            assertEquals(2, count.get(), "Only 2 messages before unsub should be received");
        } finally {
            mgr.unsubscribe(channel, cb);
        }
    }

    @Test
    void testNumSubAndChannels() {
        PubSubManager mgr = PubSubManager.getInstance();
        String channel = "test:numsup:" + System.nanoTime();

        assertEquals(0, mgr.numSub(channel));
        assertEquals(0, mgr.numPat());

        Consumer<RedisMessage> cb1 = msg -> {};
        mgr.subscribe(channel, cb1);
        assertEquals(1, mgr.numSub(channel));

        Consumer<RedisMessage> cb2 = msg -> {};
        mgr.psubscribe("test:pat:*", cb2);
        assertEquals(1, mgr.numPat());

        assertTrue(mgr.getChannels().contains(channel));

        mgr.unsubscribe(channel, cb1);
        mgr.punsubscribe("test:pat:*", cb2);

        assertEquals(0, mgr.numSub(channel));
        assertEquals(0, mgr.numPat());
    }

    @Test
    void testGlobPatterns() throws Exception {
        PubSubManager mgr = PubSubManager.getInstance();
        String pattern = "h?llo*";

        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        Consumer<RedisMessage> cb = msg -> {
            List<RedisMessage> parts = msg.getElements();
            received.add(parts.get(3).asString());
            latch.countDown();
        };

        mgr.psubscribe(pattern, cb);
        try {
            mgr.publish("hxlloworld", RedisMessage.bulkString("match1".getBytes()));
            mgr.publish("hello123", RedisMessage.bulkString("match2".getBytes()));

            boolean done = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(done, "Glob pattern should match");

            mgr.publish("hxi", RedisMessage.bulkString("nomatch".getBytes()));
            Thread.sleep(100);

            assertEquals(2, received.size());
        } finally {
            mgr.punsubscribe(pattern, cb);
        }
    }

    @Test
    void testReSubscribeAfterUnsubscribe() throws Exception {
        PubSubManager mgr = PubSubManager.getInstance();
        String channel = "test:resub:" + System.nanoTime();

        AtomicInteger cb1Count = new AtomicInteger(0);
        CountDownLatch latch1 = new CountDownLatch(1);
        Consumer<RedisMessage> cb1 = msg -> {
            cb1Count.incrementAndGet();
            latch1.countDown();
        };

        mgr.subscribe(channel, cb1);
        try {
            mgr.publish(channel, RedisMessage.bulkString("first".getBytes()));
            assertTrue(latch1.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "cb1 should receive first message");
            assertEquals(1, cb1Count.get());
        } finally {
            mgr.unsubscribe(channel, cb1);
        }

        // Re-subscribe with a new callback on the same channel
        AtomicInteger cb2Count = new AtomicInteger(0);
        CountDownLatch latch2 = new CountDownLatch(1);
        Consumer<RedisMessage> cb2 = msg -> {
            cb2Count.incrementAndGet();
            latch2.countDown();
        };

        mgr.subscribe(channel, cb2);
        try {
            mgr.publish(channel, RedisMessage.bulkString("second".getBytes()));
            assertTrue(latch2.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "cb2 should receive message after re-subscribe");

            Thread.sleep(200);
            assertEquals(1, cb1Count.get(), "cb1 should not receive after unsubscribe");
            assertEquals(1, cb2Count.get(), "cb2 should receive after re-subscribe");
        } finally {
            mgr.unsubscribe(channel, cb2);
        }
    }

    @Test
    void testMultipleChannels() throws Exception {
        PubSubManager mgr = PubSubManager.getInstance();
        String channel1 = "test:mc1:" + System.nanoTime();
        String channel2 = "test:mc2:" + System.nanoTime();
        String channel3 = "test:mc3:" + System.nanoTime();

        List<String> received1 = new CopyOnWriteArrayList<>();
        List<String> received2 = new CopyOnWriteArrayList<>();
        List<String> received3 = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        Consumer<RedisMessage> cb1 = msg -> {
            received1.add(msg.asString());
            latch.countDown();
        };
        Consumer<RedisMessage> cb2 = msg -> {
            received2.add(msg.asString());
            latch.countDown();
        };
        Consumer<RedisMessage> cb3 = msg -> {
            received3.add(msg.asString());
            latch.countDown();
        };

        mgr.subscribe(channel1, cb1);
        mgr.subscribe(channel2, cb2);
        mgr.subscribe(channel3, cb3);

        try {
            mgr.publish(channel1, RedisMessage.bulkString("msg1".getBytes()));
            mgr.publish(channel2, RedisMessage.bulkString("msg2".getBytes()));
            mgr.publish(channel3, RedisMessage.bulkString("msg3".getBytes()));

            assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "All 3 callbacks should receive their messages");
            assertEquals(1, received1.size());
            assertEquals(1, received2.size());
            assertEquals(1, received3.size());
            assertEquals("msg1", received1.get(0));
            assertEquals("msg2", received2.get(0));
            assertEquals("msg3", received3.get(0));
        } finally {
            mgr.unsubscribe(channel1, cb1);
            mgr.unsubscribe(channel2, cb2);
            mgr.unsubscribe(channel3, cb3);
        }
    }

    @Test
    void testPatternWithSpecialChars() throws Exception {
        PubSubManager mgr = PubSubManager.getInstance();
        // Dots are escaped in globToRegex, so they become literal '.' in the regex
        String pattern = "test.special.*";

        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        Consumer<RedisMessage> cb = msg -> {
            List<RedisMessage> parts = msg.getElements();
            received.add(parts.get(3).asString());
            latch.countDown();
        };

        mgr.psubscribe(pattern, cb);
        try {
            mgr.publish("test.special.abc", RedisMessage.bulkString("match1".getBytes()));
            mgr.publish("test.special.xyz", RedisMessage.bulkString("match2".getBytes()));

            assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Pattern with dots should match channels with literal dots");

            // Dots are literal, so "testXspecial.abc" should NOT match "test.special.*"
            mgr.publish("testXspecial.abc", RedisMessage.bulkString("nomatch".getBytes()));
            Thread.sleep(200);
            assertEquals(2, received.size(), "Non-matching channel with wrong dot position should be excluded");
        } finally {
            mgr.punsubscribe(pattern, cb);
        }
    }

    @Test
    void testChannelCleanup() {
        PubSubManager mgr = PubSubManager.getInstance();
        String channel = "test:cleanup:" + System.nanoTime();

        Consumer<RedisMessage> cb1 = msg -> {};
        Consumer<RedisMessage> cb2 = msg -> {};

        mgr.subscribe(channel, cb1);
        mgr.subscribe(channel, cb2);
        assertTrue(mgr.getChannels().contains(channel), "Channel should exist after subscribing 2 callbacks");
        assertEquals(2, mgr.numSub(channel));

        mgr.unsubscribe(channel, cb1);
        assertTrue(mgr.getChannels().contains(channel), "Channel should still exist after removing first callback");
        assertEquals(1, mgr.numSub(channel));

        mgr.unsubscribe(channel, cb2);
        assertFalse(mgr.getChannels().contains(channel), "Channel should be removed from getChannels() after last unsubscribe");
        assertEquals(0, mgr.numSub(channel));
    }

    @Test
    void testPunsubscribeSpecific() throws Exception {
        PubSubManager mgr = PubSubManager.getInstance();
        String pattern = "test:pspec:*";

        AtomicInteger cb1Count = new AtomicInteger(0);
        AtomicInteger cb2Count = new AtomicInteger(0);
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(2);

        Consumer<RedisMessage> cb1 = msg -> {
            cb1Count.incrementAndGet();
            latch1.countDown();
        };
        Consumer<RedisMessage> cb2 = msg -> {
            cb2Count.incrementAndGet();
            latch2.countDown();
        };

        mgr.psubscribe(pattern, cb1);
        mgr.psubscribe(pattern, cb2);
        assertEquals(2, mgr.numPat(), "Both pattern subscriptions should be registered");

        try {
            // First publish: both callbacks should receive
            mgr.publish("test:pspec:ch1", RedisMessage.bulkString("first".getBytes()));
            assertTrue(latch1.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "cb1 should receive first message");
            assertEquals(1, cb1Count.get());
            assertEquals(1, cb2Count.get());

            // Unsubscribe only cb1 — cb2 should remain
            mgr.punsubscribe(pattern, cb1);
            assertEquals(1, mgr.numPat(), "Only cb2's pattern subscription should remain");

            // Second publish: only cb2 should receive
            mgr.publish("test:pspec:ch2", RedisMessage.bulkString("second".getBytes()));
            assertTrue(latch2.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "cb2 should receive second message");

            Thread.sleep(200);
            assertEquals(1, cb1Count.get(), "cb1 should NOT receive after being punsubscribed");
            assertEquals(2, cb2Count.get(), "cb2 should receive both messages");
        } finally {
            mgr.punsubscribe(pattern, cb1);
            mgr.punsubscribe(pattern, cb2);
        }
    }
}
