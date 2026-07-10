package com.qkinfotech.bizwax.sdcs.common;

import java.util.*;
import java.util.stream.Collectors;

public class RedisSet {

    private final Set<ByteArrayKey> members = new HashSet<>();

    public long sadd(byte[]... values) {
        long added = 0;
        for (byte[] value : values) {
            if (members.add(new ByteArrayKey(value))) {
                added++;
            }
        }
        return added;
    }

    public long srem(byte[]... values) {
        long removed = 0;
        for (byte[] value : values) {
            if (members.remove(new ByteArrayKey(value))) {
                removed++;
            }
        }
        return removed;
    }

    public boolean sismember(byte[] value) {
        return members.contains(new ByteArrayKey(value));
    }

    public long scard() {
        return members.size();
    }

    public Set<byte[]> smembers() {
        return members.stream().map(ByteArrayKey::getData).collect(Collectors.toSet());
    }

    public byte[] spop() {
        if (members.isEmpty()) return null;
        Iterator<ByteArrayKey> it = members.iterator();
        byte[] value = it.next().getData();
        it.remove();
        return value;
    }

    public List<byte[]> spop(int count) {
        List<byte[]> result = new ArrayList<>();
        int toPop = Math.min(count, members.size());
        Iterator<ByteArrayKey> it = members.iterator();
        for (int i = 0; i < toPop && it.hasNext(); i++) {
            byte[] value = it.next().getData();
            it.remove();
            result.add(value);
        }
        return result;
    }

    public byte[] srandmember() {
        if (members.isEmpty()) return null;
        int index = new Random().nextInt(members.size());
        Iterator<ByteArrayKey> it = members.iterator();
        for (int i = 0; i < index; i++) it.next();
        return it.next().getData();
    }

    public List<byte[]> srandmember(int count) {
        List<byte[]> all = new ArrayList<>(smembers());
        Collections.shuffle(all);
        if (count >= 0) {
            return all.stream().limit(Math.min(count, all.size())).toList();
        } else {
            List<byte[]> result = new ArrayList<>();
            Random rand = new Random();
            for (int i = 0; i < -count; i++) {
                result.add(all.get(rand.nextInt(all.size())));
            }
            return result;
        }
    }

    public long smove(RedisSet dest, byte[] member) {
        if (!members.remove(new ByteArrayKey(member))) return 0;
        dest.sadd(member);
        return 1;
    }

    public List<Long> smismember(byte[]... values) {
        List<Long> result = new ArrayList<>();
        for (byte[] value : values) {
            result.add(members.contains(new ByteArrayKey(value)) ? 1L : 0L);
        }
        return result;
    }

    public Set<byte[]> sunion(RedisSet... others) {
        Set<ByteArrayKey> result = new HashSet<>(members);
        for (RedisSet other : others) {
            result.addAll(other.members);
        }
        return result.stream().map(ByteArrayKey::getData).collect(Collectors.toSet());
    }

    public Set<byte[]> sinter(RedisSet... others) {
        Set<ByteArrayKey> result = new HashSet<>(members);
        for (RedisSet other : others) {
            result.retainAll(other.members);
        }
        return result.stream().map(ByteArrayKey::getData).collect(Collectors.toSet());
    }

    public Set<byte[]> sdiff(RedisSet... others) {
        Set<ByteArrayKey> result = new HashSet<>(members);
        for (RedisSet other : others) {
            result.removeAll(other.members);
        }
        return result.stream().map(ByteArrayKey::getData).collect(Collectors.toSet());
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public List<Object> scan(long cursor, long count) {
        List<ByteArrayKey> allMembers = new ArrayList<>(members);
        long totalMembers = allMembers.size();

        if (cursor >= totalMembers) {
            return List.of(0L, new byte[0][]);
        }

        long endIndex = Math.min(cursor + count, totalMembers);
        List<byte[]> matchedMembers = new ArrayList<>();

        for (long i = cursor; i < endIndex; i++) {
            matchedMembers.add(allMembers.get((int) i).getData());
        }

        long nextCursor = endIndex >= totalMembers ? 0 : endIndex;
        return List.of(nextCursor, matchedMembers.toArray(new byte[0][]));
    }
}
