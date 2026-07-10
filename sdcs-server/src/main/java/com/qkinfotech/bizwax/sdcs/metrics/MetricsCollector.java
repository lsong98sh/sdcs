package com.qkinfotech.bizwax.sdcs.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class MetricsCollector {

    private static final MetricsCollector INSTANCE = new MetricsCollector();

    private final LongAdder totalCommands = new LongAdder();
    private final LongAdder totalConnections = new LongAdder();
    private final LongAdder currentConnections = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final LongAdder totalBytesRead = new LongAdder();
    private final LongAdder totalBytesWritten = new LongAdder();
    private final Map<String, CommandStats> commandStats = new ConcurrentHashMap<>();
    private final AtomicLong startTime = new AtomicLong(System.currentTimeMillis());

    private MetricsCollector() {}

    public static MetricsCollector getInstance() {
        return INSTANCE;
    }

    public void recordCommand(String commandName, long durationNanos, boolean isError) {
        totalCommands.increment();
        commandStats.computeIfAbsent(commandName, k -> new CommandStats())
                .record(durationNanos);
        if (isError) {
            totalErrors.increment();
        }
    }

    public void recordConnectionOpened() {
        totalConnections.increment();
        currentConnections.increment();
    }

    public void recordConnectionClosed() {
        currentConnections.decrement();
    }

    public void recordBytesRead(long bytes) {
        totalBytesRead.add(bytes);
    }

    public void recordBytesWritten(long bytes) {
        totalBytesWritten.add(bytes);
    }

    public long getTotalCommands() {
        return totalCommands.sum();
    }

    public long getTotalConnections() {
        return totalConnections.sum();
    }

    public long getCurrentConnections() {
        return currentConnections.sum();
    }

    public long getTotalErrors() {
        return totalErrors.sum();
    }

    public long getTotalBytesRead() {
        return totalBytesRead.sum();
    }

    public long getTotalBytesWritten() {
        return totalBytesWritten.sum();
    }

    public long getUptimeSeconds() {
        return (System.currentTimeMillis() - startTime.get()) / 1000;
    }

    public Map<String, CommandStats> getCommandStats() {
        return commandStats;
    }

    public static class CommandStats {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalDurationNanos = new LongAdder();
        private volatile long maxDurationNanos = 0;

        void record(long durationNanos) {
            count.increment();
            totalDurationNanos.add(durationNanos);
            if (durationNanos > maxDurationNanos) {
                maxDurationNanos = durationNanos;
            }
        }

        public long getCount() {
            return count.sum();
        }

        public double getAvgDurationMs() {
            long c = count.sum();
            return c == 0 ? 0 : totalDurationNanos.sum() / (double) c / 1_000_000;
        }

        public double getMaxDurationMs() {
            return maxDurationNanos / 1_000_000.0;
        }
    }
}
