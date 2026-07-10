package com.qkinfotech.bizwax.sdcs.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedisZSetTest {

    @Test
    void testZaddAndZscore() {
        RedisZSet zset = new RedisZSet();
        byte[] member1 = "member1".getBytes();
        byte[] member2 = "member2".getBytes();

        assertEquals(1, zset.zadd(1.0, member1));
        assertEquals(0, zset.zadd(1.0, member1));  // same score, returns 0
        assertEquals(0, zset.zadd(2.0, member1));  // update score, returns 0
        assertEquals(1, zset.zadd(2.0, member2));

        assertEquals(2.0, zset.zscore(member1), 1e-10);
        assertEquals(2.0, zset.zscore(member2), 1e-10);
        assertNull(zset.zscore("nonexistent".getBytes()));
    }

    @Test
    void testZremMemberNotFound() {
        RedisZSet zset = new RedisZSet();
        byte[] member = "member".getBytes();
        byte[] member2 = "member2".getBytes();

        zset.zadd(1.0, member);

        // zrem existing member
        assertEquals(1, zset.zrem(member));
        assertEquals(0, zset.zcard());

        // zrem nonexistent member returns 0
        assertEquals(0, zset.zrem(member2));
        assertEquals(0, zset.zrem(member));  // already removed
    }

    @Test
    void testZcountNoElementsInRange() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "a".getBytes());
        zset.zadd(2.0, "b".getBytes());
        zset.zadd(3.0, "c".getBytes());

        // no elements in range
        assertEquals(0, zset.zcount(10.0, 20.0));
        assertEquals(0, zset.zcount(-10.0, -5.0));
        assertEquals(1, zset.zcount(1.0, 1.0));
        assertEquals(3, zset.zcount(0.0, 100.0));
    }

    @Test
    void testZpopmin() {
        RedisZSet zset = new RedisZSet();
        byte[] a = "a".getBytes();
        byte[] b = "b".getBytes();
        byte[] c = "c".getBytes();

        zset.zadd(3.0, a);
        zset.zadd(1.0, b);
        zset.zadd(2.0, c);

        List<RedisZSet.ZSetEntry> popped = zset.zpopmin(2);
        assertEquals(2, popped.size());
        assertTrue(Arrays.equals(b, popped.get(0).member()));
        assertEquals(1.0, popped.get(0).score(), 1e-10);
        assertTrue(Arrays.equals(c, popped.get(1).member()));
        assertEquals(2.0, popped.get(1).score(), 1e-10);

        assertEquals(1, zset.zcard());
        assertTrue(Arrays.equals(a, zset.zpopmin(1).get(0).member()));
        assertEquals(0, zset.zcard());

        // pop from empty set
        assertTrue(zset.zpopmin(1).isEmpty());
    }

    @Test
    void testZpopmax() {
        RedisZSet zset = new RedisZSet();
        byte[] a = "a".getBytes();
        byte[] b = "b".getBytes();
        byte[] c = "c".getBytes();

        zset.zadd(1.0, a);
        zset.zadd(3.0, b);
        zset.zadd(2.0, c);

        List<RedisZSet.ZSetEntry> popped = zset.zpopmax(2);
        assertEquals(2, popped.size());
        assertTrue(Arrays.equals(b, popped.get(0).member()));
        assertEquals(3.0, popped.get(0).score(), 1e-10);
        assertTrue(Arrays.equals(c, popped.get(1).member()));
        assertEquals(2.0, popped.get(1).score(), 1e-10);

        assertEquals(1, zset.zcard());
        assertTrue(Arrays.equals(a, zset.zpopmax(1).get(0).member()));
        assertEquals(0, zset.zcard());

        // pop from empty set
        assertTrue(zset.zpopmax(1).isEmpty());
    }

    @Test
    void testZrankMemberNotFound() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "a".getBytes());

        assertNull(zset.zrank("b".getBytes()));
        assertNull(zset.zrank("nonexistent".getBytes()));
        // existing member
        assertEquals(0, zset.zrank("a".getBytes()));
    }

    @Test
    void testZrevrankMemberNotFound() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "a".getBytes());
        zset.zadd(2.0, "b".getBytes());

        assertNull(zset.zrevrank("c".getBytes()));
        assertNull(zset.zrevrank("nonexistent".getBytes()));
        // existing members
        assertEquals(1, zset.zrevrank("a".getBytes()));
        assertEquals(0, zset.zrevrank("b".getBytes()));
    }

    @Test
    void testZrangeBoundaries() {
        RedisZSet zset = new RedisZSet();
        byte[] a = "a".getBytes();
        byte[] b = "b".getBytes();
        byte[] c = "c".getBytes();
        byte[] d = "d".getBytes();

        zset.zadd(1.0, a);
        zset.zadd(2.0, b);
        zset.zadd(3.0, c);
        zset.zadd(4.0, d);

        // normal range
        List<RedisZSet.ZSetEntry> range = zset.zrange(0, 1);
        assertEquals(2, range.size());
        assertTrue(Arrays.equals(a, range.get(0).member()));
        assertTrue(Arrays.equals(b, range.get(1).member()));

        // negative indices (last two)
        range = zset.zrange(-2, -1);
        assertEquals(2, range.size());
        assertTrue(Arrays.equals(c, range.get(0).member()));
        assertTrue(Arrays.equals(d, range.get(1).member()));

        // start > stop
        assertTrue(zset.zrange(3, 1).isEmpty());

        // out of bounds
        range = zset.zrange(-10, 10);
        assertEquals(4, range.size());
    }

    @Test
    void testZrevrangeBoundaries() {
        RedisZSet zset = new RedisZSet();
        byte[] a = "a".getBytes();
        byte[] b = "b".getBytes();
        byte[] c = "c".getBytes();

        zset.zadd(1.0, a);
        zset.zadd(2.0, b);
        zset.zadd(3.0, c);

        // normal rev range
        List<RedisZSet.ZSetEntry> range = zset.zrevrange(0, 1);
        assertEquals(2, range.size());
        assertTrue(Arrays.equals(c, range.get(0).member()));
        assertTrue(Arrays.equals(b, range.get(1).member()));

        // negative indices
        range = zset.zrevrange(-2, -1);
        assertEquals(2, range.size());
        assertTrue(Arrays.equals(b, range.get(0).member()));
        assertTrue(Arrays.equals(a, range.get(1).member()));

        // start > stop
        assertTrue(zset.zrevrange(2, 0).isEmpty());

        // out of bounds
        range = zset.zrevrange(-10, 10);
        assertEquals(3, range.size());
    }

    @Test
    void testZinter() {
        RedisZSet zset1 = new RedisZSet();
        RedisZSet zset2 = new RedisZSet();

        zset1.zadd(1.0, "a".getBytes());
        zset1.zadd(2.0, "b".getBytes());
        zset1.zadd(3.0, "c".getBytes());

        zset2.zadd(10.0, "a".getBytes());
        zset2.zadd(20.0, "b".getBytes());
        zset2.zadd(30.0, "d".getBytes());

        // intersection should be a, b (c is only in first, d is only in second)
        List<RedisZSet.ZSetEntry> result = RedisZSet.zinter(List.of(zset1, zset2), null, "SUM");
        assertEquals(2, result.size());

        // default aggregate is SUM, so score = 1.0*1 + 10.0*1 = 11.0 for 'a', 2.0+20.0=22.0 for 'b'
        assertTrue(Arrays.equals("a".getBytes(), result.get(0).member()));
        assertEquals(11.0, result.get(0).score(), 1e-10);
        assertTrue(Arrays.equals("b".getBytes(), result.get(1).member()));
        assertEquals(22.0, result.get(1).score(), 1e-10);

        // test with MIN aggregate and weights
        result = RedisZSet.zinter(List.of(zset1, zset2), List.of(2.0, 1.0), "MIN");
        assertEquals(2, result.size());
        // a: min(1.0*2, 10.0*1) = min(2.0, 10.0) = 2.0
        // b: min(2.0*2, 20.0*1) = min(4.0, 20.0) = 4.0
        assertTrue(Arrays.equals("a".getBytes(), result.get(0).member()));
        assertEquals(2.0, result.get(0).score(), 1e-10);
        assertTrue(Arrays.equals("b".getBytes(), result.get(1).member()));
        assertEquals(4.0, result.get(1).score(), 1e-10);

        // empty list
        assertTrue(RedisZSet.zinter(List.of(), null, "SUM").isEmpty());
    }

    @Test
    void testZunion() {
        RedisZSet zset1 = new RedisZSet();
        RedisZSet zset2 = new RedisZSet();

        zset1.zadd(1.0, "a".getBytes());
        zset1.zadd(2.0, "b".getBytes());

        zset2.zadd(10.0, "a".getBytes());
        zset2.zadd(30.0, "c".getBytes());

        List<RedisZSet.ZSetEntry> result = RedisZSet.zunion(List.of(zset1, zset2), null, "SUM");
        assertEquals(3, result.size());

        // sorted by score: b(2.0), a(11.0), c(30.0)
        assertTrue(Arrays.equals("b".getBytes(), result.get(0).member()));
        assertEquals(2.0, result.get(0).score(), 1e-10);
        assertTrue(Arrays.equals("a".getBytes(), result.get(1).member()));
        assertEquals(11.0, result.get(1).score(), 1e-10);
        assertTrue(Arrays.equals("c".getBytes(), result.get(2).member()));
        assertEquals(30.0, result.get(2).score(), 1e-10);

        // test with MAX aggregate
        result = RedisZSet.zunion(List.of(zset1, zset2), List.of(1.0, 1.0), "MAX");
        assertEquals(3, result.size());
        assertTrue(Arrays.equals("b".getBytes(), result.get(0).member())); // b: max(2) = 2
        assertEquals(2.0, result.get(0).score(), 1e-10);
        assertTrue(Arrays.equals("a".getBytes(), result.get(1).member())); // a: max(1, 10) = 10
        assertEquals(10.0, result.get(1).score(), 1e-10);
        assertTrue(Arrays.equals("c".getBytes(), result.get(2).member())); // c: max(30) = 30
        assertEquals(30.0, result.get(2).score(), 1e-10);

        // empty list
        assertTrue(RedisZSet.zunion(List.of(), null, "SUM").isEmpty());
    }

    @Test
    void testZdiff() {
        RedisZSet zset1 = new RedisZSet();
        RedisZSet zset2 = new RedisZSet();

        zset1.zadd(1.0, "a".getBytes());
        zset1.zadd(2.0, "b".getBytes());
        zset1.zadd(3.0, "c".getBytes());

        zset2.zadd(10.0, "a".getBytes());
        zset2.zadd(20.0, "b".getBytes());

        List<RedisZSet.ZSetEntry> result = RedisZSet.zdiff(List.of(zset1, zset2));
        assertEquals(1, result.size());
        assertTrue(Arrays.equals("c".getBytes(), result.get(0).member()));
        assertEquals(3.0, result.get(0).score(), 1e-10);

        // empty list
        assertTrue(RedisZSet.zdiff(List.of()).isEmpty());

        // diff where all elements are in both sets
        RedisZSet zset3 = new RedisZSet();
        zset3.zadd(1.0, "a".getBytes());
        result = RedisZSet.zdiff(List.of(zset1, zset2, zset3));
        assertEquals(1, result.size());
        assertTrue(Arrays.equals("c".getBytes(), result.get(0).member()));
    }

    @Test
    void testZincrbyNewMember() {
        RedisZSet zset = new RedisZSet();
        byte[] member = "member".getBytes();

        // zincrby on new member (oldScore is null)
        double newScore = zset.zincrby(5.0, member);
        assertEquals(5.0, newScore, 1e-10);
        assertEquals(5.0, zset.zscore(member), 1e-10);

        // zincrby on existing member
        newScore = zset.zincrby(3.0, member);
        assertEquals(8.0, newScore, 1e-10);
        assertEquals(8.0, zset.zscore(member), 1e-10);

        // zincrby with negative delta
        newScore = zset.zincrby(-2.0, member);
        assertEquals(6.0, newScore, 1e-10);
        assertEquals(6.0, zset.zscore(member), 1e-10);
    }

    @Test
    void testZcard() {
        RedisZSet zset = new RedisZSet();
        assertEquals(0, zset.zcard());

        zset.zadd(1.0, "a".getBytes());
        assertEquals(1, zset.zcard());

        zset.zadd(2.0, "b".getBytes());
        assertEquals(2, zset.zcard());
    }

    @Test
    void testZrangebyscore() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "a".getBytes());
        zset.zadd(2.0, "b".getBytes());
        zset.zadd(3.0, "c".getBytes());

        List<RedisZSet.ZSetEntry> result = zset.zrangebyscore(1.0, 2.0, 0, -1);
        assertEquals(2, result.size());

        // with offset and count
        result = zset.zrangebyscore(0.0, 100.0, 1, 1);
        assertEquals(1, result.size());
        assertTrue(Arrays.equals("b".getBytes(), result.get(0).member()));

        // offset beyond size
        result = zset.zrangebyscore(0.0, 100.0, 10, 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void testZrevrangebyscore() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "a".getBytes());
        zset.zadd(2.0, "b".getBytes());
        zset.zadd(3.0, "c".getBytes());

        List<RedisZSet.ZSetEntry> result = zset.zrevrangebyscore(3.0, 1.0, 0, -1);
        assertEquals(3, result.size());
        assertTrue(Arrays.equals("c".getBytes(), result.get(0).member()));

        // with offset and count
        result = zset.zrevrangebyscore(3.0, 0.0, 1, 1);
        assertEquals(1, result.size());
        assertTrue(Arrays.equals("b".getBytes(), result.get(0).member()));
    }

    @Test
    void testZremrangebyrank() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "a".getBytes());
        zset.zadd(2.0, "b".getBytes());
        zset.zadd(3.0, "c".getBytes());

        assertEquals(2, zset.zremrangebyrank(0, 1));
        assertEquals(1, zset.zcard());
        assertNotNull(zset.zscore("c".getBytes()));
    }

    @Test
    void testZremrangebyscore() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "a".getBytes());
        zset.zadd(2.0, "b".getBytes());
        zset.zadd(3.0, "c".getBytes());

        assertEquals(2, zset.zremrangebyscore(1.0, 2.0));
        assertEquals(1, zset.zcard());
        assertNotNull(zset.zscore("c".getBytes()));
    }

    @Test
    void testZmscore() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "a".getBytes());
        zset.zadd(2.0, "b".getBytes());

        List<Double> scores = zset.zmscore("a".getBytes(), "b".getBytes(), "c".getBytes());
        assertEquals(3, scores.size());
        assertEquals(1.0, scores.get(0), 1e-10);
        assertEquals(2.0, scores.get(1), 1e-10);
        assertNull(scores.get(2));
    }

    @Test
    void testZrandmember() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "a".getBytes());
        zset.zadd(2.0, "b".getBytes());
        zset.zadd(3.0, "c".getBytes());

        // positive count
        List<byte[]> result = zset.zrandmember(2);
        assertEquals(2, result.size());

        // count > size
        result = zset.zrandmember(10);
        assertEquals(3, result.size());

        // negative count (allow duplicates)
        result = zset.zrandmember(-10);
        assertEquals(10, result.size());

        // empty set
        RedisZSet empty = new RedisZSet();
        assertTrue(empty.zrandmember(1).isEmpty());
    }

    @Test
    void testZlexcount() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "aaa".getBytes());
        zset.zadd(1.0, "bbb".getBytes());
        zset.zadd(1.0, "ccc".getBytes());

        assertEquals(3, zset.zlexcount("aaa".getBytes(), "ccc".getBytes()));
        assertEquals(1, zset.zlexcount("aaa".getBytes(), "aaa".getBytes()));
        assertEquals(0, zset.zlexcount("ddd".getBytes(), "eee".getBytes()));
    }

    @Test
    void testZrangebylex() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "aaa".getBytes());
        zset.zadd(1.0, "bbb".getBytes());
        zset.zadd(1.0, "ccc".getBytes());

        List<byte[]> result = zset.zrangebylex("aaa".getBytes(), "ccc".getBytes(), 0, -1);
        assertEquals(3, result.size());

        // with offset and count
        result = zset.zrangebylex("aaa".getBytes(), "ccc".getBytes(), 1, 1);
        assertEquals(1, result.size());

        // offset beyond size
        result = zset.zrangebylex("aaa".getBytes(), "ccc".getBytes(), 10, 5);
        assertTrue(result.isEmpty());
    }

    @Test
    void testZrevrangebylex() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "aaa".getBytes());
        zset.zadd(1.0, "bbb".getBytes());
        zset.zadd(1.0, "ccc".getBytes());

        List<byte[]> result = zset.zrevrangebylex("ccc".getBytes(), "aaa".getBytes(), 0, -1);
        assertEquals(3, result.size());
        // should be reverse of zrangebylex
        assertTrue(Arrays.equals("ccc".getBytes(), result.get(0)));
        assertTrue(Arrays.equals("bbb".getBytes(), result.get(1)));
        assertTrue(Arrays.equals("aaa".getBytes(), result.get(2)));

        // with offset
        result = zset.zrevrangebylex("ccc".getBytes(), "aaa".getBytes(), 1, 1);
        assertEquals(1, result.size());
    }

    @Test
    void testZremrangebylex() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "aaa".getBytes());
        zset.zadd(1.0, "bbb".getBytes());
        zset.zadd(1.0, "ccc".getBytes());

        assertEquals(2, zset.zremrangebylex("aaa".getBytes(), "bbb".getBytes()));
        assertEquals(1, zset.zcard());
    }

    @Test
    void testScan() {
        RedisZSet zset = new RedisZSet();
        zset.zadd(1.0, "a".getBytes());
        zset.zadd(2.0, "b".getBytes());

        List<Object> result = zset.scan(0, 10);
        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof Long);
        assertEquals(0L, result.get(0));
        assertTrue(result.get(1) instanceof byte[][]);

        // cursor beyond total
        result = zset.scan(100, 10);
        assertEquals(2, result.size());
        assertEquals(0L, result.get(0));
    }

    @Test
    void testCombinedOperations() {
        RedisZSet zset = new RedisZSet();
        byte[] a = "a".getBytes();
        byte[] b = "b".getBytes();
        byte[] c = "c".getBytes();
        byte[] d = "d".getBytes();

        // add members
        assertEquals(1, zset.zadd(1.0, a));
        assertEquals(1, zset.zadd(2.0, b));
        assertEquals(1, zset.zadd(3.0, c));
        assertEquals(1, zset.zadd(4.0, d));

        // zrank
        assertEquals(0, zset.zrank(a));
        assertEquals(1, zset.zrank(b));
        assertEquals(2, zset.zrank(c));
        assertEquals(3, zset.zrank(d));
        assertNull(zset.zrank("nonexistent".getBytes()));

        // zrevrank
        assertEquals(3, zset.zrevrank(a));
        assertEquals(2, zset.zrevrank(b));
        assertEquals(1, zset.zrevrank(c));
        assertEquals(0, zset.zrevrank(d));
        assertNull(zset.zrevrank("nonexistent".getBytes()));

        // zcount
        assertEquals(4, zset.zcount(0.0, 10.0));
        assertEquals(2, zset.zcount(2.0, 3.0));
        assertEquals(0, zset.zcount(10.0, 20.0));
    }

    @Test
    void testZinterWithWeights() {
        RedisZSet zset1 = new RedisZSet();
        RedisZSet zset2 = new RedisZSet();

        zset1.zadd(1.0, "a".getBytes());
        zset1.zadd(2.0, "b".getBytes());

        zset2.zadd(10.0, "a".getBytes());
        zset2.zadd(20.0, "b".getBytes());

        // with weights, SUM
        List<RedisZSet.ZSetEntry> result = RedisZSet.zinter(List.of(zset1, zset2), List.of(2.0, 3.0), "SUM");
        assertEquals(2, result.size());
        // a: 1.0*2 + 10.0*3 = 2 + 30 = 32
        // b: 2.0*2 + 20.0*3 = 4 + 60 = 64
        assertEquals(32.0, result.get(0).score(), 1e-10);
        assertEquals(64.0, result.get(1).score(), 1e-10);

        // MAX with weights
        result = RedisZSet.zinter(List.of(zset1, zset2), List.of(2.0, 3.0), "MAX");
        assertEquals(2, result.size());
        assertEquals(30.0, result.get(0).score(), 1e-10);
        assertEquals(60.0, result.get(1).score(), 1e-10);
    }

    @Test
    void testZunionWithWeights() {
        RedisZSet zset1 = new RedisZSet();
        RedisZSet zset2 = new RedisZSet();

        zset1.zadd(1.0, "a".getBytes());
        zset2.zadd(10.0, "a".getBytes());

        // SUM with weights, only zset2 has weight
        List<RedisZSet.ZSetEntry> result = RedisZSet.zunion(List.of(zset1, zset2), List.of(1.0, 2.0), "SUM");
        assertEquals(1, result.size());
        // a: 1.0*1 + 10.0*2 = 1 + 20 = 21
        assertEquals(21.0, result.get(0).score(), 1e-10);

        // MIN with weights
        result = RedisZSet.zunion(List.of(zset1, zset2), List.of(1.0, 2.0), "MIN");
        assertEquals(1, result.size());
        // a: min(1.0*1, 10.0*2) = min(1, 20) = 1
        assertEquals(1.0, result.get(0).score(), 1e-10);
    }
}
