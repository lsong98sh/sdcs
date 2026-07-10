package com.qkinfotech.bizwax.sdcs.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RedisSetTest {

    private byte[] bytes(String s) {
        return s.getBytes();
    }

    @Test
    void testSadd() {
        RedisSet set = new RedisSet();
        assertEquals(1, set.sadd(bytes("a")));
        assertEquals(0, set.sadd(bytes("a")));  // duplicate
        assertEquals(2, set.sadd(bytes("b"), bytes("c")));
        assertEquals(3, set.scard());
    }

    @Test
    void testSremMemberNotFound() {
        RedisSet set = new RedisSet();
        set.sadd(bytes("a"), bytes("b"));

        // srem nonexistent member returns 0
        assertEquals(0, set.srem(bytes("c")));
        assertEquals(2, set.scard());

        // srem existing members
        assertEquals(1, set.srem(bytes("a")));
        assertEquals(1, set.scard());
    }

    @Test
    void testSremMultipleMembers() {
        RedisSet set = new RedisSet();
        set.sadd(bytes("a"), bytes("b"), bytes("c"));

        assertEquals(2, set.srem(bytes("a"), bytes("c")));
        assertEquals(1, set.scard());
        assertTrue(set.sismember(bytes("b")));

        // removing nonexistent among existent
        assertEquals(0, set.srem(bytes("x")));
        assertEquals(1, set.srem(bytes("b"), bytes("x")));
        assertEquals(0, set.scard());
    }

    @Test
    void testSmoveDifferentScenarios() {
        RedisSet src = new RedisSet();
        RedisSet dest = new RedisSet();

        src.sadd(bytes("a"), bytes("b"), bytes("c"));

        // smove member that exists in src
        assertEquals(1, src.smove(dest, bytes("a")));
        assertFalse(src.sismember(bytes("a")));
        assertTrue(dest.sismember(bytes("a")));
        assertEquals(2, src.scard());
        assertEquals(1, dest.scard());

        // smove member not in src
        assertEquals(0, src.smove(dest, bytes("z")));
        assertEquals(2, src.scard());
        assertEquals(1, dest.scard());

        // smove member already in dest
        assertEquals(1, src.smove(dest, bytes("b")));
        assertTrue(dest.sismember(bytes("b")));
        assertEquals(1, src.scard());
        assertEquals(2, dest.scard());

        // smove from empty src
        RedisSet empty = new RedisSet();
        assertEquals(0, empty.smove(dest, bytes("x")));
    }

    @Test
    void testSrandmemberWithNegativeCount() {
        RedisSet set = new RedisSet();
        set.sadd(bytes("a"), bytes("b"), bytes("c"));

        // negative count: allow duplicates, result size = |count|
        java.util.List<byte[]> result = set.srandmember(-10);
        assertEquals(10, result.size());

        // all returned elements should be from the set
        for (byte[] member : result) {
            assertTrue(set.sismember(member));
        }

        // positive count: no duplicates, result size = min(count, size)
        result = set.srandmember(2);
        assertEquals(2, result.size());

        // count > size with positive count
        result = set.srandmember(10);
        assertEquals(3, result.size());
    }

    @Test
    void testSrandmemberSingle() {
        RedisSet set = new RedisSet();
        assertNull(set.srandmember());

        set.sadd(bytes("a"));
        assertNotNull(set.srandmember());
    }

    @Test
    void testSunionMultipleSets() {
        RedisSet set1 = new RedisSet();
        RedisSet set2 = new RedisSet();
        RedisSet set3 = new RedisSet();

        set1.sadd(bytes("a"), bytes("b"));
        set2.sadd(bytes("b"), bytes("c"));
        set3.sadd(bytes("d"));

        Set<byte[]> result = set1.sunion(set2, set3);
        assertEquals(4, result.size());

        assertTrue(result.stream().anyMatch(v -> Arrays.equals(v, bytes("a"))));
        assertTrue(result.stream().anyMatch(v -> Arrays.equals(v, bytes("b"))));
        assertTrue(result.stream().anyMatch(v -> Arrays.equals(v, bytes("c"))));
        assertTrue(result.stream().anyMatch(v -> Arrays.equals(v, bytes("d"))));
    }

    @Test
    void testSunionWithEmptySets() {
        RedisSet set1 = new RedisSet();
        RedisSet set2 = new RedisSet();

        set1.sadd(bytes("a"));
        Set<byte[]> result = set1.sunion(set2);
        assertEquals(1, result.size());
    }

    @Test
    void testSinterMultipleSets() {
        RedisSet set1 = new RedisSet();
        RedisSet set2 = new RedisSet();
        RedisSet set3 = new RedisSet();

        set1.sadd(bytes("a"), bytes("b"), bytes("c"), bytes("d"));
        set2.sadd(bytes("a"), bytes("b"), bytes("e"));
        set3.sadd(bytes("a"), bytes("b"), bytes("f"));

        Set<byte[]> result = set1.sinter(set2, set3);
        assertEquals(2, result.size());

        assertTrue(result.stream().anyMatch(v -> Arrays.equals(v, bytes("a"))));
        assertTrue(result.stream().anyMatch(v -> Arrays.equals(v, bytes("b"))));
    }

    @Test
    void testSinterNoIntersection() {
        RedisSet set1 = new RedisSet();
        RedisSet set2 = new RedisSet();

        set1.sadd(bytes("a"), bytes("b"));
        set2.sadd(bytes("c"), bytes("d"));

        Set<byte[]> result = set1.sinter(set2);
        assertTrue(result.isEmpty());
    }

    @Test
    void testSdiffMultipleSets() {
        RedisSet set1 = new RedisSet();
        RedisSet set2 = new RedisSet();
        RedisSet set3 = new RedisSet();

        set1.sadd(bytes("a"), bytes("b"), bytes("c"), bytes("d"));
        set2.sadd(bytes("a"), bytes("b"));
        set3.sadd(bytes("c"));

        Set<byte[]> result = set1.sdiff(set2, set3);
        assertEquals(1, result.size());
        assertTrue(result.stream().anyMatch(v -> Arrays.equals(v, bytes("d"))));
    }

    @Test
    void testSdiffNoDifference() {
        RedisSet set1 = new RedisSet();
        RedisSet set2 = new RedisSet();

        set1.sadd(bytes("a"), bytes("b"));
        set2.sadd(bytes("a"), bytes("b"));

        Set<byte[]> result = set1.sdiff(set2);
        assertTrue(result.isEmpty());
    }

    @Test
    void testSdiffWithEmptySets() {
        RedisSet set1 = new RedisSet();
        RedisSet set2 = new RedisSet();
        RedisSet empty = new RedisSet();

        set1.sadd(bytes("a"), bytes("b"));
        set2.sadd(bytes("a"));

        Set<byte[]> result = set1.sdiff(set2, empty);
        assertEquals(1, result.size());
        assertTrue(result.stream().anyMatch(v -> Arrays.equals(v, bytes("b"))));
    }

    @Test
    void testSismember() {
        RedisSet set = new RedisSet();
        set.sadd(bytes("a"));

        assertTrue(set.sismember(bytes("a")));
        assertFalse(set.sismember(bytes("b")));
    }

    @Test
    void testSpop() {
        RedisSet set = new RedisSet();
        assertNull(set.spop());

        set.sadd(bytes("a"), bytes("b"), bytes("c"));
        byte[] popped = set.spop();
        assertNotNull(popped);
        assertEquals(2, set.scard());
        assertFalse(set.sismember(popped));
    }

    @Test
    void testSpopWithCount() {
        RedisSet set = new RedisSet();
        set.sadd(bytes("a"), bytes("b"), bytes("c"));

        java.util.List<byte[]> popped = set.spop(2);
        assertEquals(2, popped.size());
        assertEquals(1, set.scard());

        for (byte[] member : popped) {
            assertFalse(set.sismember(member));
        }

        // spop with count > size
        popped = set.spop(10);
        assertEquals(1, popped.size());
        assertTrue(set.isEmpty());
    }

    @Test
    void testSmembers() {
        RedisSet set = new RedisSet();
        set.sadd(bytes("a"), bytes("b"));

        Set<byte[]> members = set.smembers();
        assertEquals(2, members.size());
    }

    @Test
    void testSmismember() {
        RedisSet set = new RedisSet();
        set.sadd(bytes("a"), bytes("b"));

        java.util.List<Long> result = set.smismember(bytes("a"), bytes("c"), bytes("b"));
        assertEquals(3, result.size());
        assertEquals(1L, result.get(0));
        assertEquals(0L, result.get(1));
        assertEquals(1L, result.get(2));
    }

    @Test
    void testScan() {
        RedisSet set = new RedisSet();
        set.sadd(bytes("a"), bytes("b"), bytes("c"));

        java.util.List<Object> result = set.scan(0, 10);
        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof Long);
        assertEquals(0L, result.get(0));

        // cursor beyond total
        result = set.scan(100, 10);
        assertEquals(2, result.size());
        assertEquals(0L, result.get(0));
        assertTrue(result.get(1) instanceof byte[][]);
        assertEquals(0, ((byte[][]) result.get(1)).length);
    }

    @Test
    void testIsEmpty() {
        RedisSet set = new RedisSet();
        assertTrue(set.isEmpty());

        set.sadd(bytes("a"));
        assertFalse(set.isEmpty());
    }
}
