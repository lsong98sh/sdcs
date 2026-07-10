package com.qkinfotech.bizwax.sdcs.metrics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MetricsCollectorTest {

    @Test
    void testSingleton() {
        MetricsCollector instance1 = MetricsCollector.getInstance();
        MetricsCollector instance2 = MetricsCollector.getInstance();
        assertSame(instance1, instance2);
    }

    @Test
    void testRecordCommand() {
        MetricsCollector collector = MetricsCollector.getInstance();
        collector.recordCommand("SET", 1_000_000, false);
        assertTrue(collector.getTotalCommands() >= 1);
    }

    @Test
    void testRecordCommandMultipleCommands() {
        MetricsCollector collector = MetricsCollector.getInstance();
        long prevTotal = collector.getTotalCommands();
        collector.recordCommand("SET", 1_000_000, false);
        collector.recordCommand("GET", 500_000, false);
        collector.recordCommand("DEL", 200_000, false);
        assertEquals(prevTotal + 3, collector.getTotalCommands());
        Map<String, MetricsCollector.CommandStats> stats = collector.getCommandStats();
        assertTrue(stats.containsKey("SET"));
        assertTrue(stats.containsKey("GET"));
        assertTrue(stats.containsKey("DEL"));
    }

    @Test
    void testRecordCommandWithError() {
        MetricsCollector collector = MetricsCollector.getInstance();
        long prevErrors = collector.getTotalErrors();
        collector.recordCommand("SET", 1_000_000, true);
        assertEquals(prevErrors + 1, collector.getTotalErrors());
    }

    @Test
    void testConnectionOpenedClosed() {
        MetricsCollector collector = MetricsCollector.getInstance();
        long prevCurrent = collector.getCurrentConnections();
        collector.recordConnectionOpened();
        collector.recordConnectionOpened();
        collector.recordConnectionClosed();
        assertEquals(prevCurrent + 1, collector.getCurrentConnections());
    }

    @Test
    void testConnectionClosedWithoutOpen() {
        MetricsCollector collector = MetricsCollector.getInstance();
        long prevCurrent = collector.getCurrentConnections();
        collector.recordConnectionClosed();
        assertEquals(prevCurrent - 1, collector.getCurrentConnections());
    }

    @Test
    void testBytesReadWritten() {
        MetricsCollector collector = MetricsCollector.getInstance();
        long prevRead = collector.getTotalBytesRead();
        long prevWritten = collector.getTotalBytesWritten();
        collector.recordBytesRead(100);
        collector.recordBytesRead(200);
        collector.recordBytesWritten(300);
        assertEquals(prevRead + 300, collector.getTotalBytesRead());
        assertEquals(prevWritten + 300, collector.getTotalBytesWritten());
    }

    @Test
    void testUptimeSeconds() {
        MetricsCollector collector = MetricsCollector.getInstance();
        assertTrue(collector.getUptimeSeconds() >= 0);
    }

    @Test
    void testCommandStatsAvgMaxDuration() {
        MetricsCollector.CommandStats stats = new MetricsCollector.CommandStats();
        stats.record(2_000_000);
        stats.record(4_000_000);
        assertEquals(2, stats.getCount());
        assertEquals(3.0, stats.getAvgDurationMs(), 0.001);
        assertEquals(4.0, stats.getMaxDurationMs(), 0.001);
    }

    @Test
    void testCommandStatsMaxDurationMultipleCalls() {
        MetricsCollector.CommandStats stats = new MetricsCollector.CommandStats();
        stats.record(1_000_000);
        stats.record(5_000_000);
        stats.record(3_000_000);
        assertEquals(3, stats.getCount());
        assertEquals(3.0, stats.getAvgDurationMs(), 0.001);
        assertEquals(5.0, stats.getMaxDurationMs(), 0.001);
    }
}
