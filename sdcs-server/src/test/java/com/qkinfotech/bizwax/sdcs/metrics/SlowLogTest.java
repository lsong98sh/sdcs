package com.qkinfotech.bizwax.sdcs.metrics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SlowLogTest {

    @Test
    void testDefaultConstructor() {
        SlowLog log = SlowLog.getInstance();
        log.reset();
        assertEquals(0, log.getCount());
    }

    @Test
    void testParameterConstructor() {
        SlowLog log = new SlowLog(64);
        assertEquals(0, log.getCount());
    }

    @Test
    void testSetGetSlowLogThresholdUs() {
        SlowLog log = new SlowLog(128);
        log.setSlowLogThresholdUs(5000);
        assertEquals(5000, log.getSlowLogThresholdUs());
    }

    @Test
    void testRecordIfSlow() {
        SlowLog log = new SlowLog(128);
        log.setSlowLogThresholdUs(1000);
        log.recordIfSlow("SET", List.of("key", "value"), 2_000_000);
        assertEquals(1, log.getCount());
    }

    @Test
    void testRecordIfSlowNotRecorded() {
        SlowLog log = new SlowLog(128);
        log.setSlowLogThresholdUs(10000);
        log.recordIfSlow("GET", List.of("key"), 1_000_000);
        assertEquals(0, log.getCount());
    }

    @Test
    void testRecordIfSlowBoundary() {
        SlowLog log = new SlowLog(128);
        log.setSlowLogThresholdUs(1000);
        log.recordIfSlow("SET", List.of("key", "value"), 1_000_000);
        assertEquals(1, log.getCount());
    }

    @Test
    void testFifoEviction() {
        SlowLog log = new SlowLog(3);
        log.setSlowLogThresholdUs(0);
        log.recordIfSlow("CMD1", List.of(), 1_000_000);
        log.recordIfSlow("CMD2", List.of(), 1_000_000);
        log.recordIfSlow("CMD3", List.of(), 1_000_000);
        log.recordIfSlow("CMD4", List.of(), 1_000_000);
        assertEquals(3, log.getCount());
        List<SlowLog.SlowLogEntry> entries = log.getEntries(10);
        assertEquals("CMD2", entries.get(0).command());
        assertEquals("CMD3", entries.get(1).command());
        assertEquals("CMD4", entries.get(2).command());
    }

    @Test
    void testGetEntries() {
        SlowLog log = new SlowLog(128);
        log.setSlowLogThresholdUs(0);
        log.recordIfSlow("SET", List.of("a"), 1_000_000);
        log.recordIfSlow("GET", List.of("b"), 1_000_000);
        log.recordIfSlow("DEL", List.of("c"), 1_000_000);
        List<SlowLog.SlowLogEntry> entries = log.getEntries(2);
        assertEquals(2, entries.size());
        assertEquals("GET", entries.get(0).command());
        assertEquals("DEL", entries.get(1).command());
    }

    @Test
    void testGetEntriesZero() {
        SlowLog log = new SlowLog(128);
        log.setSlowLogThresholdUs(0);
        log.recordIfSlow("SET", List.of("a"), 1_000_000);
        List<SlowLog.SlowLogEntry> entries = log.getEntries(0);
        assertTrue(entries.isEmpty());
    }

    @Test
    void testGetEntriesNegative() {
        SlowLog log = new SlowLog(128);
        log.setSlowLogThresholdUs(0);
        log.recordIfSlow("SET", List.of("a"), 1_000_000);
        assertThrows(IllegalArgumentException.class, () -> log.getEntries(-1),
                "BUG: getEntries(-1) should handle negative count gracefully, " +
                "but currently throws IllegalArgumentException due to ArrayList(-1) capacity");
    }

    @Test
    void testGetCount() {
        SlowLog log = new SlowLog(128);
        log.setSlowLogThresholdUs(0);
        log.recordIfSlow("A", List.of(), 1_000_000);
        log.recordIfSlow("B", List.of(), 1_000_000);
        assertEquals(2, log.getCount());
    }

    @Test
    void testReset() {
        SlowLog log = new SlowLog(128);
        log.setSlowLogThresholdUs(0);
        log.recordIfSlow("A", List.of(), 1_000_000);
        log.recordIfSlow("B", List.of(), 1_000_000);
        assertEquals(2, log.getCount());
        log.reset();
        assertEquals(0, log.getCount());
    }

    @Test
    void testArgsTruncation() {
        SlowLog log = new SlowLog(128);
        log.setSlowLogThresholdUs(0);
        List<String> longArgs = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            longArgs.add("arg" + i);
        }
        log.recordIfSlow("SET", longArgs, 1_000_000);
        List<SlowLog.SlowLogEntry> entries = log.getEntries(10);
        assertEquals(1, entries.size());
        assertEquals(10, entries.get(0).args().size());
        assertEquals("arg0", entries.get(0).args().get(0));
        assertEquals("arg9", entries.get(0).args().get(9));
    }

    @Test
    void testSlowLogEntryFields() {
        SlowLog log = new SlowLog(128);
        log.setSlowLogThresholdUs(0);
        log.recordIfSlow("PING", List.of("hello"), 5_000_000);
        List<SlowLog.SlowLogEntry> entries = log.getEntries(10);
        assertEquals(1, entries.size());
        SlowLog.SlowLogEntry entry = entries.get(0);
        assertTrue(entry.timestamp() > 0);
        assertEquals(5000, entry.durationUs());
        assertEquals("PING", entry.command());
        assertEquals(List.of("hello"), entry.args());
    }
}
