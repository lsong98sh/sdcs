package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class StressTest {

    static {
        new SDCSConfig().parseArgs(new String[]{"--persistence", "none"});
    }

    private static final int NUM_CLIENTS = 10;
    private static final int THREADS_PER_CLIENT = 5;
    private static final int OPS_PER_THREAD = 2000;
    private static final int TOTAL_OPS = NUM_CLIENTS * THREADS_PER_CLIENT * OPS_PER_THREAD;
    private static final String[] COMMANDS = {"SET", "GET", "DEL", "HSET", "HGET", "LPUSH", "LRANGE", "SADD", "ZADD"};

    @Test
    void stressTest() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(NUM_CLIENTS * THREADS_PER_CLIENT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(NUM_CLIENTS * THREADS_PER_CLIENT);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger errorCount = new AtomicInteger();
        AtomicLong totalLatencyNanos = new AtomicLong();
        AtomicLong maxLatencyNanos = new AtomicLong();

        Random sharedRandom = new Random();

        for (int client = 0; client < NUM_CLIENTS; client++) {
            final int clientId = client;
            for (int thread = 0; thread < THREADS_PER_CLIENT; thread++) {
                final int threadId = thread;
                final Random threadRandom = new Random(sharedRandom.nextLong());

                executor.submit(() -> {
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    DatabaseManager dbManager = new DatabaseManager();
                    CommandDispatcher dispatcher = new CommandDispatcher(dbManager);

                    for (int op = 0; op < OPS_PER_THREAD; op++) {
                        String command = COMMANDS[threadRandom.nextInt(COMMANDS.length)];
                        String key = "client" + clientId + "_key" + threadRandom.nextInt(100);

                        List<RedisMessage> args = buildArgs(command, key, threadRandom);

                        long start = System.nanoTime();
                        try {
                            RedisMessage result = dispatcher.dispatch(command, args);
                            long latency = System.nanoTime() - start;

                            successCount.incrementAndGet();
                            totalLatencyNanos.addAndGet(latency);

                            long currentMax;
                            do {
                                currentMax = maxLatencyNanos.get();
                            } while (latency > currentMax && !maxLatencyNanos.compareAndSet(currentMax, latency));

                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        }
                    }

                    doneLatch.countDown();
                });
            }
        }

        long startTime = System.nanoTime();
        startLatch.countDown();
        doneLatch.await(120, TimeUnit.SECONDS);
        long elapsedNanos = System.nanoTime() - startTime;

        double elapsedSec = elapsedNanos / 1_000_000_000.0;
        int totalSuccess = successCount.get();
        int totalErrors = errorCount.get();
        long avgLatencyUs = totalSuccess > 0 ? (totalLatencyNanos.get() / totalSuccess) / 1000 : 0;
        long maxLatencyUs = maxLatencyNanos.get() / 1000;
        double qps = totalSuccess > 0 ? totalSuccess / elapsedSec : 0;

        System.out.println("=========================================");
        System.out.println("  SDCS STRESS TEST RESULTS");
        System.out.println("=========================================");
        System.out.println("  Clients:           " + NUM_CLIENTS);
        System.out.println("  Threads/Client:    " + THREADS_PER_CLIENT);
        System.out.println("  Ops/Thread:        " + OPS_PER_THREAD);
        System.out.println("  Total Target Ops:  " + TOTAL_OPS);
        System.out.println("  -----------------------------------------");
        System.out.println("  Success:           " + totalSuccess);
        System.out.println("  Errors:            " + totalErrors);
        System.out.println("  Time:              " + String.format("%.3f", elapsedSec) + "s");
        System.out.println("  QPS:               " + String.format("%.0f", qps));
        System.out.println("  Avg Latency:       " + avgLatencyUs + "us");
        System.out.println("  Max Latency:       " + maxLatencyUs + "us");
        System.out.println("=========================================");

        assertEquals(TOTAL_OPS, totalSuccess + totalErrors, "All operations should complete");
        assertTrue(totalErrors == 0, "No errors should occur");
        assertTrue(qps > 100, "QPS should be reasonable");

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    private static List<RedisMessage> buildArgs(String command, String key, Random random) {
        return switch (command) {
            case "SET" -> List.of(
                    RedisMessage.bulkString(key.getBytes()),
                    RedisMessage.bulkString(("value_" + random.nextInt(1000000)).getBytes())
            );
            case "GET" -> List.of(RedisMessage.bulkString(key.getBytes()));
            case "DEL" -> List.of(RedisMessage.bulkString(key.getBytes()));
            case "HSET" -> List.of(
                    RedisMessage.bulkString(key.getBytes()),
                    RedisMessage.bulkString(("field_" + random.nextInt(10)).getBytes()),
                    RedisMessage.bulkString(("hval_" + random.nextInt(1000)).getBytes())
            );
            case "HGET" -> List.of(
                    RedisMessage.bulkString(key.getBytes()),
                    RedisMessage.bulkString(("field_" + random.nextInt(10)).getBytes())
            );
            case "LPUSH" -> {
                List<RedisMessage> list = new ArrayList<>();
                list.add(RedisMessage.bulkString(key.getBytes()));
                int count = 1 + random.nextInt(5);
                for (int i = 0; i < count; i++) {
                    list.add(RedisMessage.bulkString(("item_" + random.nextInt(100)).getBytes()));
                }
                yield list;
            }
            case "LRANGE" -> List.of(
                    RedisMessage.bulkString(key.getBytes()),
                    RedisMessage.bulkString("0"),
                    RedisMessage.bulkString("-1")
            );
            case "SADD" -> {
                List<RedisMessage> list = new ArrayList<>();
                list.add(RedisMessage.bulkString(key.getBytes()));
                int count = 1 + random.nextInt(5);
                for (int i = 0; i < count; i++) {
                    list.add(RedisMessage.bulkString(("member_" + random.nextInt(100)).getBytes()));
                }
                yield list;
            }
            case "ZADD" -> List.of(
                    RedisMessage.bulkString(key.getBytes()),
                    RedisMessage.bulkString(String.valueOf(random.nextDouble() * 100).getBytes()),
                    RedisMessage.bulkString(("member_" + random.nextInt(100)).getBytes())
            );
            default -> List.of(RedisMessage.bulkString(key.getBytes()));
        };
    }

    @Test
    void gcPressureTest() throws Exception {
        DatabaseManager dbManager = new DatabaseManager();
        CommandDispatcher dispatcher = new CommandDispatcher(dbManager);

        int iterations = 50000;
        long beforeGcCount = getGcCount();

        for (int i = 0; i < iterations; i++) {
            String key = "gc_key_" + i;
            String value = "gc_value_" + i + "_".repeat(100);

            dispatcher.dispatch("SET", List.of(
                    RedisMessage.bulkString(key.getBytes()),
                    RedisMessage.bulkString(value.getBytes())
            ));
            dispatcher.dispatch("GET", List.of(RedisMessage.bulkString(key.getBytes())));

            if (i % 100 == 0) {
                dispatcher.dispatch("DEL", List.of(RedisMessage.bulkString(key.getBytes())));
            }
        }

        long afterGcCount = getGcCount();
        long gcCountDiff = afterGcCount - beforeGcCount;

        System.out.println("GC Pressure Test: " + iterations + " SET/GET/DEL cycles");
        System.out.println("GC count before: " + beforeGcCount);
        System.out.println("GC count after:  " + afterGcCount);
        System.out.println("GC triggered:    " + gcCountDiff);

        assertTrue(gcCountDiff < 50, "GC should not be excessive");
    }

    private static long getGcCount() {
        long total = 0;
        for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            total += gc.getCollectionCount();
        }
        return total;
    }

    @Test
    void longStabilityTest() throws Exception {
        DatabaseManager dbManager = new DatabaseManager();
        CommandDispatcher dispatcher = new CommandDispatcher(dbManager);

        System.out.println("Long stability test: 30s sustained operations...");
        System.out.println("Started: " + System.currentTimeMillis());

        long start = System.currentTimeMillis();
        int count = 0;

        while (System.currentTimeMillis() - start < 30000) {
            String key = "stability_" + (count % 500);
            dispatcher.dispatch("SET", List.of(
                    RedisMessage.bulkString(key.getBytes()),
                    RedisMessage.bulkString(("val_" + count).getBytes())
            ));
            dispatcher.dispatch("GET", List.of(RedisMessage.bulkString(key.getBytes())));

            if (count % 100 == 0) {
                dispatcher.dispatch("DEL", List.of(RedisMessage.bulkString(("stability_" + ((count + 250) % 500)).getBytes())));
            }

            count++;
        }

        long elapsed = System.currentTimeMillis() - start;
        double opsPerSec = count / (elapsed / 1000.0);

        System.out.println("Completed: " + System.currentTimeMillis());
        System.out.println("Total ops:   " + count);
        System.out.println("Time:        " + elapsed + "ms");
        System.out.println("Ops/sec:     " + String.format("%.0f", opsPerSec));

        assertTrue(count > 10000, "Should complete > 10k ops in 30s");
        assertTrue(opsPerSec > 1000, "Should sustain > 1000 ops/sec");
    }
}
