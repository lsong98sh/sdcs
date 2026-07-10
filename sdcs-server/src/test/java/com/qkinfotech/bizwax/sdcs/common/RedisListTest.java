package com.qkinfotech.bizwax.sdcs.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedisListTest {

    // Helper methods
    private byte[] bytes(String s) {
        return s.getBytes();
    }

    private void assertBytesEquals(byte[] expected, byte[] actual) {
        assertTrue(Arrays.equals(expected, actual),
                "Expected " + (expected == null ? "null" : new String(expected))
                        + " but got " + (actual == null ? "null" : new String(actual)));
    }

    @Test
    void testLremNegativeCount() {
        RedisList list = new RedisList();
        byte[] a = bytes("a");
        byte[] b = bytes("b");
        byte[] c = bytes("c");

        // lrem with negative count removes from tail
        list.rpush(a, b, a, c, a);
        assertEquals(2, list.lrem(-2, a));
        assertEquals(3, list.llen());
        // The last 2 'a's should be removed, leaving: [a, b, c]
        assertBytesEquals(a, list.lindex(0));
        assertBytesEquals(b, list.lindex(1));
        assertBytesEquals(c, list.lindex(2));

        // lrem with negative count where count > occurrences
        RedisList list2 = new RedisList();
        list2.rpush(a, b, c);
        assertEquals(0, list2.lrem(-5, bytes("z")));
        assertEquals(3, list2.llen());
    }

    @Test
    void testLremPositiveCount() {
        RedisList list = new RedisList();
        byte[] a = bytes("a");

        list.rpush(a, bytes("b"), a, bytes("c"), a);
        assertEquals(2, list.lrem(2, a));
        assertEquals(3, list.llen());
        // First 2 'a's removed, remaining: [b, c, a]
        assertBytesEquals(bytes("b"), list.lindex(0));
        assertBytesEquals(bytes("c"), list.lindex(1));
        assertBytesEquals(a, list.lindex(2));
    }

    @Test
    void testLremZeroCount() {
        RedisList list = new RedisList();
        byte[] a = bytes("a");

        list.rpush(a, bytes("b"), a, bytes("c"), a);
        assertEquals(3, list.lrem(0, a));
        assertEquals(2, list.llen());
        // All 'a's removed
        assertBytesEquals(bytes("b"), list.lindex(0));
        assertBytesEquals(bytes("c"), list.lindex(1));
    }

    @Test
    void testLposVariousCombinations() {
        RedisList list = new RedisList();
        byte[] a = bytes("a");
        byte[] b = bytes("b");

        list.rpush(a, b, a, b, a, b, a, b);
        // List: [a, b, a, b, a, b, a, b]

        // basic lpos with rank=1 (find all starting from first), count=0, maxlen=0
        List<Integer> positions = list.lpos(a, 1, 0, 0);
        assertEquals(4, positions.size());
        assertEquals(0, positions.get(0));
        assertEquals(2, positions.get(1));
        assertEquals(4, positions.get(2));
        assertEquals(6, positions.get(3));

        // lpos with count > 0 (limit results)
        positions = list.lpos(a, 1, 3, 0);
        assertEquals(3, positions.size());
        assertEquals(0, positions.get(0));
        assertEquals(2, positions.get(1));
        assertEquals(4, positions.get(2));

        // lpos with maxlen (limit search range)
        positions = list.lpos(a, 1, 0, 4);
        assertEquals(2, positions.size());
        assertEquals(0, positions.get(0));
        assertEquals(2, positions.get(1));

        // lpos with rank > 1 (second occurrence only)
        positions = list.lpos(a, 2, 1, 0);
        assertEquals(1, positions.size());
        assertEquals(2, positions.get(0));

        // lpos with rank=3 (third occurrence)
        positions = list.lpos(a, 3, 1, 0);
        assertEquals(1, positions.size());
        assertEquals(4, positions.get(0));

        // lpos with negative rank (search from tail)
        positions = list.lpos(a, -1, 2, 0);
        assertEquals(2, positions.size());
        assertEquals(6, positions.get(0)); // last 'a' from tail
        assertEquals(4, positions.get(1)); // second to last 'a' from tail

        // lpos with negative rank, count, maxlen combined
        positions = list.lpos(a, -2, 1, 6);
        assertEquals(1, positions.size());
    }

    @Test
    void testLpushXOnEmptyList() {
        RedisList list = new RedisList();
        byte[] a = bytes("a");

        // lpushX on empty list - no-op
        assertTrue(list.isEmpty());
        list.lpushX(a);
        assertTrue(list.isEmpty());

        // lpushX on non-empty list - works
        list.rpush(a);
        assertEquals(1, list.llen());
        list.lpushX(bytes("b"));
        assertEquals(2, list.llen());
        assertBytesEquals(bytes("b"), list.lindex(0));
    }

    @Test
    void testRpushXOnEmptyList() {
        RedisList list = new RedisList();
        byte[] a = bytes("a");

        // rpushX on empty list - no-op
        assertTrue(list.isEmpty());
        list.rpushX(a);
        assertTrue(list.isEmpty());

        // rpushX on non-empty list - works
        list.rpush(bytes("b"));
        assertEquals(1, list.llen());
        list.rpushX(a);
        assertEquals(2, list.llen());
        assertBytesEquals(a, list.lindex(1));
    }

    @Test
    void testLmoveAllDirections() {
        RedisList src = new RedisList();
        RedisList dest = new RedisList();

        src.rpush(bytes("a"), bytes("b"), bytes("c"));

        // lmove from left to left (pop left, push left)
        byte[] moved = src.lmove(dest, true, true);
        assertBytesEquals(bytes("a"), moved);
        assertEquals(2, src.llen());
        assertEquals(1, dest.llen());
        assertBytesEquals(bytes("a"), dest.lindex(0));

        // lmove from left to right (pop left, push right)
        moved = src.lmove(dest, true, false);
        assertBytesEquals(bytes("b"), moved);
        assertEquals(1, src.llen());
        assertEquals(2, dest.llen());
        assertBytesEquals(bytes("b"), dest.lindex(1));

        // lmove from right to left (pop right, push left)
        moved = src.lmove(dest, false, true);
        assertBytesEquals(bytes("c"), moved);
        assertTrue(src.isEmpty());
        assertEquals(3, dest.llen());
        assertBytesEquals(bytes("c"), dest.lindex(0));

        // lmove from empty list
        assertNull(src.lmove(dest, true, true));
    }

    @Test
    void testLmoveRightToRight() {
        RedisList src = new RedisList();
        RedisList dest = new RedisList();

        src.rpush(bytes("x"), bytes("y"), bytes("z"));

        // lmove from right to right (pop right, push right)
        byte[] moved = src.lmove(dest, false, false);
        assertBytesEquals(bytes("z"), moved);
        assertEquals(2, src.llen());
        assertEquals(1, dest.llen());
        assertBytesEquals(bytes("z"), dest.lindex(0));
    }

    @Test
    void testLpushAndRpush() {
        RedisList list = new RedisList();

        list.lpush(bytes("a"), bytes("b"));
        assertEquals(2, list.llen());
        assertBytesEquals(bytes("b"), list.lindex(0)); // b was pushed last to the left
        assertBytesEquals(bytes("a"), list.lindex(1));

        list.rpush(bytes("c"), bytes("d"));
        assertEquals(4, list.llen());
        assertBytesEquals(bytes("b"), list.lindex(0));
        assertBytesEquals(bytes("a"), list.lindex(1));
        assertBytesEquals(bytes("c"), list.lindex(2));
        assertBytesEquals(bytes("d"), list.lindex(3));
    }

    @Test
    void testLpopAndRpop() {
        RedisList list = new RedisList();
        list.rpush(bytes("a"), bytes("b"), bytes("c"));

        assertBytesEquals(bytes("a"), list.lpop());
        assertEquals(2, list.llen());

        assertBytesEquals(bytes("c"), list.rpop());
        assertEquals(1, list.llen());

        assertBytesEquals(bytes("b"), list.lpop());
        assertTrue(list.isEmpty());

        assertNull(list.lpop());
        assertNull(list.rpop());
    }

    @Test
    void testLindex() {
        RedisList list = new RedisList();
        list.rpush(bytes("a"), bytes("b"), bytes("c"));

        assertBytesEquals(bytes("a"), list.lindex(0));
        assertBytesEquals(bytes("b"), list.lindex(1));
        assertBytesEquals(bytes("c"), list.lindex(2));
        assertNull(list.lindex(10));
        assertNull(list.lindex(-10));
    }

    @Test
    void testLrange() {
        RedisList list = new RedisList();
        list.rpush(bytes("a"), bytes("b"), bytes("c"), bytes("d"), bytes("e"));

        List<byte[]> range = list.lrange(1, 3);
        assertEquals(3, range.size());
        assertBytesEquals(bytes("b"), range.get(0));
        assertBytesEquals(bytes("c"), range.get(1));
        assertBytesEquals(bytes("d"), range.get(2));

        // negative indices
        range = list.lrange(-3, -1);
        assertEquals(3, range.size());
        assertBytesEquals(bytes("c"), range.get(0));
        assertBytesEquals(bytes("d"), range.get(1));
        assertBytesEquals(bytes("e"), range.get(2));

        // start > stop
        assertTrue(list.lrange(4, 2).isEmpty());

        // out of bounds
        range = list.lrange(-10, 10);
        assertEquals(5, range.size());
    }

    @Test
    void testLset() {
        RedisList list = new RedisList();
        list.rpush(bytes("a"), bytes("b"));

        assertEquals(1, list.lset(0, bytes("x")));
        assertBytesEquals(bytes("x"), list.lindex(0));

        // out of bounds
        assertEquals(0, list.lset(10, bytes("z")));
        assertEquals(0, list.lset(-10, bytes("z")));
    }

    @Test
    void testLtrim() {
        RedisList list = new RedisList();
        list.rpush(bytes("a"), bytes("b"), bytes("c"), bytes("d"), bytes("e"));

        list.ltrim(1, 3);
        assertEquals(3, list.llen());
        assertBytesEquals(bytes("b"), list.lindex(0));
        assertBytesEquals(bytes("d"), list.lindex(2));

        // clear list with start > stop
        list.ltrim(1, 0);
        assertTrue(list.isEmpty());
    }

    @Test
    void testLinsert() {
        RedisList list = new RedisList();
        list.rpush(bytes("a"), bytes("c"));

        // insert before
        long size = list.linsert(true, bytes("c"), bytes("b"));
        assertEquals(3, size);
        assertBytesEquals(bytes("a"), list.lindex(0));
        assertBytesEquals(bytes("b"), list.lindex(1));
        assertBytesEquals(bytes("c"), list.lindex(2));

        // insert after
        size = list.linsert(false, bytes("c"), bytes("d"));
        assertEquals(4, size);
        assertBytesEquals(bytes("d"), list.lindex(3));

        // pivot not found
        assertEquals(-1, list.linsert(true, bytes("z"), bytes("x")));
    }

    @Test
    void testRpoplpush() {
        RedisList src = new RedisList();
        RedisList dest = new RedisList();

        src.rpush(bytes("a"), bytes("b"), bytes("c"));

        byte[] moved = src.rpoplpush(dest);
        assertBytesEquals(bytes("c"), moved);
        assertEquals(2, src.llen());
        assertEquals(1, dest.llen());
        assertBytesEquals(bytes("c"), dest.lindex(0));

        // rpoplpush from empty list
        src.lpop(); src.lpop(); // empty src
        assertNull(src.rpoplpush(dest));
    }

    @Test
    void testIsEmpty() {
        RedisList list = new RedisList();
        assertTrue(list.isEmpty());

        list.rpush(bytes("a"));
        assertFalse(list.isEmpty());

        list.lpop();
        assertTrue(list.isEmpty());
    }
}
