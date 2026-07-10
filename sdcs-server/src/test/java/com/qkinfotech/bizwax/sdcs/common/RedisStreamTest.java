package com.qkinfotech.bizwax.sdcs.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedisStreamTest {

    private byte[] bytes(String s) {
        return s.getBytes();
    }

    private Map<String, byte[]> fields(String... kv) {
        if (kv.length == 0) return Map.of();
        return Map.of(kv[0], bytes(kv[1]));
    }

    @Test
    void testXadd() {
        RedisStream stream = new RedisStream();
        Map<String, byte[]> fields = Map.of("key1", bytes("value1"));

        // auto-generate ID
        String id = stream.xadd("*", fields);
        assertNotNull(id);
        assertTrue(id.contains("-"));
        assertEquals(1, stream.xlen());
    }

    @Test
    void testXaddWithExplicitId() {
        RedisStream stream = new RedisStream();
        Map<String, byte[]> f = Map.of("field", bytes("value"));

        String id = stream.xadd("1234567890123-0", f);
        assertEquals("1234567890123-0", id);
        assertEquals(1, stream.xlen());
    }

    @Test
    void testXaddThrowsOnInvalidId() {
        RedisStream stream = new RedisStream();
        Map<String, byte[]> f = Map.of("field", bytes("value"));

        assertThrows(IllegalArgumentException.class, () -> stream.xadd("invalid", f));
        assertThrows(IllegalArgumentException.class, () -> stream.xadd("0-0", f));

        stream.xadd("100-0", f);
        assertThrows(IllegalArgumentException.class, () -> stream.xadd("99-0", f));
        assertThrows(IllegalArgumentException.class, () -> stream.xadd("100-0", f));
    }

    @Test
    void testXlen() {
        RedisStream stream = new RedisStream();
        assertEquals(0, stream.xlen());

        stream.xadd("100-0", fields("k", "v"));
        assertEquals(1, stream.xlen());

        stream.xadd("101-0", fields("k", "v"));
        assertEquals(2, stream.xlen());
    }

    @Test
    void testXrange() {
        RedisStream stream = new RedisStream();
        stream.xadd("100-0", fields("k", "v1"));
        stream.xadd("101-0", fields("k", "v2"));
        stream.xadd("102-0", fields("k", "v3"));

        // range all
        List<RedisStream.StreamEntry> entries = stream.xrange("-", "+", 0);
        assertEquals(3, entries.size());

        // range with boundaries
        entries = stream.xrange("100-0", "101-0", 0);
        assertEquals(2, entries.size());
        assertEquals("100-0", entries.get(0).getId());
        assertEquals("101-0", entries.get(1).getId());

        // range with count limit
        entries = stream.xrange("-", "+", 2);
        assertEquals(2, entries.size());

        // empty range
        entries = stream.xrange("200-0", "300-0", 0);
        assertTrue(entries.isEmpty());
    }

    @Test
    void testXrangeWithNullBounds() {
        RedisStream stream = new RedisStream();
        stream.xadd("100-0", fields("k", "v"));

        // null start should use "-" (first key)
        List<RedisStream.StreamEntry> entries = stream.xrange(null, "+", 0);
        assertFalse(entries.isEmpty());

        // null end should use "+" (last key)
        entries = stream.xrange("-", null, 0);
        assertFalse(entries.isEmpty());

        // xrange on empty stream with explicit valid bounds returns empty
        assertTrue(new RedisStream().xrange("0-0", "0-0", 0).isEmpty());
    }

    @Test
    void testXrevrange() {
        RedisStream stream = new RedisStream();
        stream.xadd("100-0", fields("k", "v1"));
        stream.xadd("101-0", fields("k", "v2"));
        stream.xadd("102-0", fields("k", "v3"));

        // reverse range all
        List<RedisStream.StreamEntry> entries = stream.xrevrange("+", "-", 0);
        assertEquals(3, entries.size());
        assertEquals("102-0", entries.get(0).getId());
        assertEquals("101-0", entries.get(1).getId());
        assertEquals("100-0", entries.get(2).getId());

        // reverse range with specific bounds
        entries = stream.xrevrange("101-0", "100-0", 0);
        assertEquals(2, entries.size());
        assertEquals("101-0", entries.get(0).getId());
        assertEquals("100-0", entries.get(1).getId());

        // with count limit (count applies to xrange first, then reversed)
        entries = stream.xrevrange("+", "-", 1);
        assertEquals(1, entries.size());
        assertEquals("100-0", entries.get(0).getId());

        // empty range (range between existing entries with no data)
        entries = stream.xrevrange("104-0", "103-0", 0);
        assertTrue(entries.isEmpty());
    }

    @Test
    void testXtrim() {
        RedisStream stream = new RedisStream();
        stream.xadd("100-0", fields("k", "v1"));
        stream.xadd("101-0", fields("k", "v2"));
        stream.xadd("102-0", fields("k", "v3"));

        // trim to maxLen=2, exact
        long removed = stream.xtrim(2, false);
        assertEquals(1, removed);
        assertEquals(2, stream.xlen());
        assertEquals("101-0", stream.xrange("-", "+", 0).get(0).getId());

        // trim again with maxLen=1
        removed = stream.xtrim(1, false);
        assertEquals(1, removed);
        assertEquals(1, stream.xlen());

        // trim with maxLen >= size
        removed = stream.xtrim(10, false);
        assertEquals(0, removed);
        assertEquals(1, stream.xlen());

        // trim with approx=true (after prev trim, 1 entry remains; add 2 more = 3; trim to 2 = removes 1)
        stream.xadd("103-0", fields("k", "v4"));
        stream.xadd("104-0", fields("k", "v5"));
        removed = stream.xtrim(2, true);
        assertEquals(1, removed);
        assertEquals(2, stream.xlen());
    }

    @Test
    void testXtrimOnEmptyStream() {
        RedisStream stream = new RedisStream();
        assertEquals(0, stream.xtrim(10, false));
        assertTrue(stream.isEmpty());
    }

    @Test
    void testXdel() {
        RedisStream stream = new RedisStream();
        stream.xadd("100-0", fields("k", "v1"));
        stream.xadd("101-0", fields("k", "v2"));
        stream.xadd("102-0", fields("k", "v3"));

        // delete existing id
        assertEquals(1, stream.xdel("101-0"));
        assertEquals(2, stream.xlen());

        // delete nonexistent id
        assertEquals(0, stream.xdel("999-0"));
        assertEquals(2, stream.xlen());

        // delete multiple ids (some existent, some not)
        assertEquals(2, stream.xdel("100-0", "999-0", "102-0"));
        assertEquals(0, stream.xlen());
    }

    @Test
    void testXread() {
        RedisStream stream = new RedisStream();
        stream.xadd("100-0", fields("k", "v1"));
        stream.xadd("101-0", fields("k", "v2"));
        stream.xadd("102-0", fields("k", "v3"));

        // read from start
        List<RedisStream.StreamEntry> entries = stream.xread("0-0", 0);
        assertEquals(3, entries.size());

        // read from specific id (exclusive)
        entries = stream.xread("101-0", 0);
        assertEquals(1, entries.size());
        assertEquals("102-0", entries.get(0).getId());

        // read with count
        entries = stream.xread("0-0", 2);
        assertEquals(2, entries.size());

        // read with null startId
        entries = stream.xread(null, 0);
        assertEquals(3, entries.size());

        // read $ (entries after the last entry, so returns empty)
        entries = stream.xread("$", 0);
        assertTrue(entries.isEmpty());

        // read from empty stream
        RedisStream empty = new RedisStream();
        assertTrue(empty.xread("0-0", 0).isEmpty());
    }

    @Test
    void testXreadNullStartId() {
        RedisStream stream = new RedisStream();
        stream.xadd("100-0", fields("k", "v"));

        List<RedisStream.StreamEntry> entries = stream.xread(null, 0);
        assertEquals(1, entries.size());
    }

    @Test
    void testStreamEntryTimestampAndSeq() {
        RedisStream.StreamEntry entry = new RedisStream.StreamEntry("1234567890123-5", fields("k", "v"));
        assertEquals(1234567890123L, entry.getTimestamp());
        assertEquals(5, entry.getSeq());
    }

    @Test
    void testIsEmpty() {
        RedisStream stream = new RedisStream();
        assertTrue(stream.isEmpty());

        stream.xadd("100-0", fields("k", "v"));
        assertFalse(stream.isEmpty());
    }

    @Test
    void testXreadFromEmptyStream() {
        RedisStream empty = new RedisStream();
        assertTrue(empty.xread("0-0", 0).isEmpty());
        assertTrue(empty.xread("", 0).isEmpty());
    }
}