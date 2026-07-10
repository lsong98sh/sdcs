package com.qkinfotech.bizwax.sdcs.store;

import com.qkinfotech.bizwax.sdcs.buffer.ByteArrayChain;
import com.qkinfotech.bizwax.sdcs.common.RedisData;
import com.qkinfotech.bizwax.sdcs.common.RedisDataType;
import com.qkinfotech.bizwax.sdcs.common.RedisList;
import com.qkinfotech.bizwax.sdcs.common.RedisHash;
import com.qkinfotech.bizwax.sdcs.common.RedisSet;
import com.qkinfotech.bizwax.sdcs.common.RedisZSet;
import com.qkinfotech.bizwax.sdcs.common.RedisZSet.ZSetEntry;
import com.qkinfotech.bizwax.sdcs.common.RedisStream;
import com.qkinfotech.bizwax.sdcs.common.RedisStream.StreamEntry;
import com.qkinfotech.bizwax.sdcs.common.HyperLogLog;
import com.qkinfotech.bizwax.sdcs.persistence.StoreListener;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryStore {

    private final List<StoreListener> listeners = new CopyOnWriteArrayList<>();

    private final Map<String, RedisData> store = new ConcurrentHashMap<>() {
        @Override
        public RedisData put(String key, RedisData value) {
            RedisData old = super.put(key, value);
            notifyPut(key, value);
            return old;
        }

        @Override
        public RedisData remove(Object key) {
            RedisData old = super.remove(key);
            if (old != null) {
                notifyRemove((String) key);
            }
            return old;
        }

        @Override
        public void clear() {
            Set<String> keys = new HashSet<>(keySet());
            super.clear();
            for (String key : keys) {
                notifyRemove(key);
            }
        }
    };

    public Map<String, RedisData> getStore() {
        return store;
    }

    public void addListener(StoreListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(StoreListener listener) {
        listeners.remove(listener);
    }

    private void notifyPut(String key, RedisData value) {
        for (StoreListener listener : listeners) {
            try {
                listener.onPut(key, value);
            } catch (Exception e) {
                // listener error ignored
            }
        }
    }

    private void notifyRemove(String key) {
        for (StoreListener listener : listeners) {
            try {
                listener.onRemove(key);
            } catch (Exception e) {
                // listener error ignored
            }
        }
    }

    public RedisData getEntry(String key) {
        RedisData entry = store.get(key);
        if (entry != null && entry.isExpired()) {
            store.remove(key);
            return null;
        }
        return entry;
    }

    public byte[] get(String key) {
        RedisData entry = getEntry(key);
        if (entry == null) return null;
        if (entry.getType() != RedisDataType.STRING) return null;
        Object val = entry.getValue(Object.class);
        if (val instanceof ByteArrayChain chain) {
            return chain.toByteArray();
        }
        return (byte[]) val;
    }

    /**
     * Get the string value as a ByteArrayChain without copying.
     * Returns null if the key does not exist or is not a STRING.
     */
    public ByteArrayChain getChain(String key) {
        RedisData entry = getEntry(key);
        if (entry == null) return null;
        if (entry.getType() != RedisDataType.STRING) return null;
        Object val = entry.getValue(Object.class);
        if (val instanceof ByteArrayChain chain) {
            return chain;
        }
        if (val instanceof byte[] bytes) {
            ByteArrayChain chain = new ByteArrayChain();
            chain.write(bytes);
            return chain;
        }
        return null;
    }

    public void put(String key, byte[] value) {
        store.put(key, new RedisData(RedisDataType.STRING, value));
    }

    /**
     * Store a string value from a ByteArrayChain without copying.
     */
    public void put(String key, ByteArrayChain chain) {
        store.put(key, new RedisData(RedisDataType.STRING, chain));
    }

    public void put(String key, byte[] value, long expireAtMs) {
        store.put(key, new RedisData(RedisDataType.STRING, value, expireAtMs));
    }

    /**
     * Store a string value with expiry from a ByteArrayChain without copying.
     */
    public void put(String key, ByteArrayChain chain, long expireAtMs) {
        store.put(key, new RedisData(RedisDataType.STRING, chain, expireAtMs));
    }

    public boolean setNX(String key, byte[] value) {
        RedisData entry = store.get(key);
        if (entry != null && !entry.isExpired()) {
            return false;
        }
        store.put(key, new RedisData(RedisDataType.STRING, value));
        return true;
    }

    public void set(String key, byte[] value, long seconds) {
        put(key, value, System.currentTimeMillis() + seconds * 1000);
    }

    public void psetex(String key, byte[] value, long millis) {
        put(key, value, System.currentTimeMillis() + millis);
    }

    public boolean setXX(String key, byte[] value) {
        RedisData entry = store.get(key);
        if (entry == null || entry.isExpired()) {
            return false;
        }
        store.put(key, new RedisData(RedisDataType.STRING, value));
        return true;
    }

    public boolean remove(String key) {
        return store.remove(key) != null;
    }

    public boolean exists(String key) {
        return getEntry(key) != null;
    }

    public long size() {
        return store.entrySet().stream().filter(e -> !e.getValue().isExpired()).count();
    }

    public void clear() {
        store.clear();
    }

    public String type(String key) {
        RedisData entry = getEntry(key);
        if (entry == null) return "none";
        return entry.getType().name().toLowerCase();
    }

    public long incr(String key) {
        return incrBy(key, 1);
    }

    public long incrBy(String key, long delta) {
        byte[] current = get(key);
        long value = 0;
        if (current != null) {
            try {
                value = Long.parseLong(new String(current));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ERR value is not an integer or out of range");
            }
        }
        value += delta;
        put(key, String.valueOf(value).getBytes());
        return value;
    }

    public long decr(String key) {
        return incrBy(key, -1);
    }

    public long decrBy(String key, long delta) {
        return incrBy(key, -delta);
    }

    public long append(String key, byte[] suffix) {
        byte[] current = get(key);
        byte[] newValue;
        if (current == null) {
            newValue = suffix;
        } else {
            newValue = new byte[current.length + suffix.length];
            System.arraycopy(current, 0, newValue, 0, current.length);
            System.arraycopy(suffix, 0, newValue, current.length, suffix.length);
        }
        put(key, newValue);
        return newValue.length;
    }

    public long strLen(String key) {
        byte[] value = get(key);
        return value != null ? value.length : 0;
    }

    public byte[] getRange(String key, long start, long end) {
        byte[] value = get(key);
        if (value == null) return null;

        int len = value.length;
        if (start < 0) start = len + start;
        if (end < 0) end = len + end;

        if (start < 0) start = 0;
        if (end >= len) end = len - 1;
        if (start > end) return new byte[0];

        byte[] result = new byte[(int) (end - start + 1)];
        System.arraycopy(value, (int) start, result, 0, result.length);
        return result;
    }

    public long setRange(String key, long offset, byte[] value) {
        byte[] current = get(key);
        if (current == null) current = new byte[0];

        long newLen = Math.max(current.length, offset + value.length);
        byte[] newValue = new byte[(int) newLen];
        System.arraycopy(current, 0, newValue, 0, current.length);
        System.arraycopy(value, 0, newValue, (int) offset, value.length);

        put(key, newValue);
        return newLen;
    }

    public long expire(String key, long seconds) {
        return pexpire(key, seconds * 1000);
    }

    public byte[] getSet(String key, byte[] value) {
        byte[] old = get(key);
        put(key, value);
        return old;
    }

    public byte[] getEx(String key, long expireAtMs) {
        byte[] value = get(key);
        if (value != null) {
            RedisData entry = store.get(key);
            if (entry != null) {
                entry.setExpireAt(expireAtMs);
            }
        }
        return value;
    }

    public byte[] getDel(String key) {
        byte[] value = get(key);
        remove(key);
        return value;
    }

    public double incrByFloat(String key, double delta) {
        byte[] current = get(key);
        double value = 0;
        if (current != null) {
            try {
                value = Double.parseDouble(new String(current));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ERR value is not a valid float");
            }
        }
        value += delta;
        put(key, String.valueOf(value).getBytes());
        return value;
    }

    public boolean msetNx(byte[]... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("ERR wrong number of arguments");
        }
        for (int i = 0; i < keyValues.length; i += 2) {
            if (exists(new String(keyValues[i]))) {
                return false;
            }
        }
        for (int i = 0; i < keyValues.length; i += 2) {
            put(new String(keyValues[i]), keyValues[i + 1]);
        }
        return true;
    }

    public long pexpire(String key, long milliseconds) {
        RedisData entry = getEntry(key);
        if (entry == null) return 0;
        long expireAt = System.currentTimeMillis() + milliseconds;
        store.put(key, new RedisData(entry.getType(), entry.getValue(Object.class), expireAt));
        return 1;
    }

    public long expireAt(String key, long unixTimeSeconds) {
        return pexpireAt(key, unixTimeSeconds * 1000);
    }

    public long pexpireAt(String key, long unixTimeMs) {
        RedisData entry = getEntry(key);
        if (entry == null) return 0;
        store.put(key, new RedisData(entry.getType(), entry.getValue(Object.class), unixTimeMs));
        return 1;
    }

    public long ttl(String key) {
        RedisData entry = getEntry(key);
        if (entry == null) return -2;
        long ttl = entry.ttlMs();
        return ttl == -1 ? -1 : (ttl + 999) / 1000;
    }

    public long pttl(String key) {
        RedisData entry = getEntry(key);
        if (entry == null) return -2;
        return entry.ttlMs();
    }

    public long persist(String key) {
        RedisData entry = getEntry(key);
        if (entry == null || entry.getExpireAtMs() < 0) return 0;
        store.put(key, new RedisData(entry.getType(), entry.getValue(Object.class)));
        return 1;
    }

    // Bitmap operations

    public int setbit(String key, long offset, int value) {
        byte[] bytes = get(key);
        if (bytes == null) {
            bytes = new byte[0];
        }
        int byteIndex = (int)(offset / 8);
        int bitIndex = 7 - (int)(offset % 8);
        if (byteIndex >= bytes.length) {
            byte[] newBytes = new byte[byteIndex + 1];
            System.arraycopy(bytes, 0, newBytes, 0, bytes.length);
            bytes = newBytes;
        }
        int oldBit = (bytes[byteIndex] >> bitIndex) & 1;
        if (value == 1) {
            bytes[byteIndex] = (byte)(bytes[byteIndex] | (1 << bitIndex));
        } else {
            bytes[byteIndex] = (byte)(bytes[byteIndex] & ~(1 << bitIndex));
        }
        put(key, bytes);
        return oldBit;
    }

    public int getbit(String key, long offset) {
        byte[] bytes = get(key);
        if (bytes == null) return 0;
        int byteIndex = (int)(offset / 8);
        int bitIndex = 7 - (int)(offset % 8);
        if (byteIndex >= bytes.length) return 0;
        return (bytes[byteIndex] >> bitIndex) & 1;
    }

    public long bitcount(String key, int start, int end) {
        byte[] bytes = get(key);
        if (bytes == null) return 0;
        int s = start;
        int e = end;
        if (s < 0) s = bytes.length + s;
        if (e < 0) e = bytes.length + e;
        if (s < 0) s = 0;
        if (e >= bytes.length) e = bytes.length - 1;
        if (s > e) return 0;
        long count = 0;
        for (int i = s; i <= e; i++) {
            count += Integer.bitCount(Byte.toUnsignedInt(bytes[i]));
        }
        return count;
    }

    public long bitpos(String key, int bit, long start, long end) {
        byte[] bytes = get(key);
        if (bytes == null) return bit == 0 ? 0 : -1;
        long s = start;
        long e = end;
        if (s < 0) s = bytes.length + s;
        if (e < 0) e = bytes.length + e;
        if (s < 0) s = 0;
        if (e >= bytes.length) e = bytes.length - 1;
        if (s > e) return -1;
        for (long i = s; i <= e; i++) {
            int b = Byte.toUnsignedInt(bytes[(int)i]);
            if (bit == 0) {
                b = ~b & 0xFF;
            }
            if (b != 0) {
                long pos = i * 8;
                for (int j = 7; j >= 0; j--) {
                    if ((b & (1 << j)) != 0) {
                        return pos + (7 - j);
                    }
                }
            }
        }
        return -1;
    }

    public long bitop(String operation, String destKey, String... srcKeys) {
        if (srcKeys.length == 0) return 0;
        String op = operation.toUpperCase();
        if ("NOT".equals(op) && srcKeys.length != 1) {
            throw new IllegalArgumentException("ERR BITOP NOT requires exactly one source key");
        }
        List<byte[]> srcBytes = new ArrayList<>();
        int maxLen = 0;
        for (String srcKey : srcKeys) {
            byte[] b = get(srcKey);
            if (b == null) b = new byte[0];
            srcBytes.add(b);
            if (b.length > maxLen) maxLen = b.length;
        }
        byte[] result = new byte[maxLen];
        switch (op) {
            case "AND" -> {
                for (int i = 0; i < maxLen; i++) {
                    int val = 0xFF;
                    for (byte[] b : srcBytes) {
                        val &= (i < b.length ? Byte.toUnsignedInt(b[i]) : 0);
                    }
                    result[i] = (byte) val;
                }
            }
            case "OR" -> {
                for (int i = 0; i < maxLen; i++) {
                    int val = 0;
                    for (byte[] b : srcBytes) {
                        val |= (i < b.length ? Byte.toUnsignedInt(b[i]) : 0);
                    }
                    result[i] = (byte) val;
                }
            }
            case "XOR" -> {
                for (int i = 0; i < maxLen; i++) {
                    int val = 0;
                    for (byte[] b : srcBytes) {
                        val ^= (i < b.length ? Byte.toUnsignedInt(b[i]) : 0);
                    }
                    result[i] = (byte) val;
                }
            }
            case "NOT" -> {
                byte[] src = srcBytes.get(0);
                for (int i = 0; i < maxLen; i++) {
                    result[i] = (byte) ((~(i < src.length ? Byte.toUnsignedInt(src[i]) : 0)) & 0xFF);
                }
            }
            default -> throw new IllegalArgumentException("ERR syntax error");
        }
        put(destKey, result);
        return result.length;
    }

    // List operations

    public RedisList getList(String key) {
        RedisData entry = getEntry(key);
        if (entry == null) return null;
        if (entry.getType() != RedisDataType.LIST) return null;
        return entry.getValue(RedisList.class);
    }

    public long lpush(String key, byte[]... values) {
        RedisData entry = getEntry(key);
        RedisList list;
        if (entry == null) {
            list = new RedisList();
            store.put(key, new RedisData(RedisDataType.LIST, list));
        } else if (entry.getType() != RedisDataType.LIST) {
            return -1;
        } else {
            list = entry.getValue(RedisList.class);
        }
        list.lpush(values);
        return list.llen();
    }

    public long rpush(String key, byte[]... values) {
        RedisData entry = getEntry(key);
        RedisList list;
        if (entry == null) {
            list = new RedisList();
            store.put(key, new RedisData(RedisDataType.LIST, list));
        } else if (entry.getType() != RedisDataType.LIST) {
            return -1;
        } else {
            list = entry.getValue(RedisList.class);
        }
        list.rpush(values);
        return list.llen();
    }

    public long lpushX(String key, byte[] value) {
        RedisData entry = getEntry(key);
        if (entry == null || entry.getType() != RedisDataType.LIST) return 0;
        RedisList list = entry.getValue(RedisList.class);
        if (list.llen() == 0) return 0;
        list.lpush(value);
        return list.llen();
    }

    public long rpushX(String key, byte[] value) {
        RedisData entry = getEntry(key);
        if (entry == null || entry.getType() != RedisDataType.LIST) return 0;
        RedisList list = entry.getValue(RedisList.class);
        if (list.llen() == 0) return 0;
        list.rpush(value);
        return list.llen();
    }

    public List<Integer> lpos(String key, byte[] value, int rank, int count, int maxlen) {
        RedisList list = getList(key);
        if (list == null) return List.of();
        return list.lpos(value, rank, count, maxlen);
    }

    public byte[] lmove(String sourceKey, String destKey, boolean fromLeft, boolean toLeft) {
        RedisList src = getList(sourceKey);
        RedisList dst = getList(destKey);
        if (src == null) return null;
        if (dst == null) {
            dst = new RedisList();
            store.put(destKey, new RedisData(RedisDataType.LIST, dst));
        }
        return src.lmove(dst, fromLeft, toLeft);
    }

    public byte[] lpop(String key) {
        RedisList list = getList(key);
        if (list == null) return null;
        byte[] value = list.lpop();
        if (list.isEmpty()) {
            store.remove(key);
        }
        return value;
    }

    public byte[] rpop(String key) {
        RedisList list = getList(key);
        if (list == null) return null;
        byte[] value = list.rpop();
        if (list.isEmpty()) {
            store.remove(key);
        }
        return value;
    }

    public long llen(String key) {
        RedisList list = getList(key);
        return list != null ? list.llen() : 0;
    }

    public byte[] lindex(String key, long index) {
        RedisList list = getList(key);
        return list != null ? list.lindex(index) : null;
    }

    public List<byte[]> lrange(String key, long start, long stop) {
        RedisList list = getList(key);
        return list != null ? list.lrange(start, stop) : List.of();
    }

    public long lset(String key, long index, byte[] value) {
        RedisList list = getList(key);
        if (list == null) return 0;
        return list.lset(index, value);
    }

    public long ltrim(String key, long start, long stop) {
        RedisList list = getList(key);
        if (list == null) return 0;
        list.ltrim(start, stop);
        if (list.isEmpty()) {
            store.remove(key);
        }
        return 1;
    }

    public long lrem(String key, long count, byte[] value) {
        RedisList list = getList(key);
        return list != null ? list.lrem(count, value) : 0;
    }

    public long linsert(String key, boolean before, byte[] pivot, byte[] value) {
        RedisList list = getList(key);
        return list != null ? list.linsert(before, pivot, value) : 0;
    }

    public byte[] rpoplpush(String source, String destination) {
        RedisList srcList = getList(source);
        if (srcList == null) return null;

        RedisData destEntry = getEntry(destination);
        RedisList destList;
        if (destEntry == null) {
            destList = new RedisList();
            store.put(destination, new RedisData(RedisDataType.LIST, destList));
        } else if (destEntry.getType() != RedisDataType.LIST) {
            return null;
        } else {
            destList = destEntry.getValue(RedisList.class);
        }

        return srcList.rpoplpush(destList);
    }

    // Hash operations

    public RedisHash getHash(String key) {
        RedisData entry = getEntry(key);
        if (entry == null) return null;
        if (entry.getType() != RedisDataType.HASH) return null;
        return entry.getValue(RedisHash.class);
    }

    public long hset(String key, String field, byte[] value) {
        RedisData entry = getEntry(key);
        RedisHash hash;
        if (entry == null) {
            hash = new RedisHash();
            store.put(key, new RedisData(RedisDataType.HASH, hash));
        } else if (entry.getType() != RedisDataType.HASH) {
            return -1;
        } else {
            hash = entry.getValue(RedisHash.class);
        }
        return hash.hset(field, value);
    }

    public byte[] hget(String key, String field) {
        RedisHash hash = getHash(key);
        return hash != null ? hash.hget(field) : null;
    }

    public long hdel(String key, String... fields) {
        RedisHash hash = getHash(key);
        if (hash == null) return 0;
        long deleted = hash.hdel(fields);
        if (hash.isEmpty()) {
            store.remove(key);
        }
        return deleted;
    }

    public boolean hexists(String key, String field) {
        RedisHash hash = getHash(key);
        return hash != null && hash.hexists(field);
    }

    public long hlen(String key) {
        RedisHash hash = getHash(key);
        return hash != null ? hash.hlen() : 0;
    }

    public long hstrlen(String key, String field) {
        RedisHash hash = getHash(key);
        return hash != null ? hash.hstrlen(field) : 0;
    }

    public long hincrBy(String key, String field, long delta) {
        RedisHash hash = getHash(key);
        if (hash == null) {
            hash = new RedisHash();
            store.put(key, new RedisData(RedisDataType.HASH, hash));
        }
        return hash.hincrBy(field, delta);
    }

    public double hincrByFloat(String key, String field, double delta) {
        RedisHash hash = getHash(key);
        if (hash == null) {
            hash = new RedisHash();
            store.put(key, new RedisData(RedisDataType.HASH, hash));
        }
        return hash.hincrByFloat(field, delta);
    }

    public Set<String> hkeys(String key) {
        RedisHash hash = getHash(key);
        return hash != null ? hash.hkeys() : Set.of();
    }

    public List<byte[]> hvals(String key) {
        RedisHash hash = getHash(key);
        return hash != null ? hash.hvals() : List.of();
    }

    public Map<String, byte[]> hgetAll(String key) {
        RedisHash hash = getHash(key);
        return hash != null ? hash.hgetAll() : Map.of();
    }

    public long hmset(String key, Map<String, byte[]> fieldValues) {
        RedisData entry = getEntry(key);
        RedisHash hash;
        if (entry == null) {
            hash = new RedisHash();
            store.put(key, new RedisData(RedisDataType.HASH, hash));
        } else if (entry.getType() != RedisDataType.HASH) {
            return 0;
        } else {
            hash = entry.getValue(RedisHash.class);
        }
        return hash.hmset(fieldValues);
    }

    public List<byte[]> hmget(String key, String... fields) {
        RedisHash hash = getHash(key);
        return hash != null ? hash.hmget(fields) : Arrays.asList(new byte[fields.length]);
    }

    public long hsetnx(String key, String field, byte[] value) {
        RedisData entry = getEntry(key);
        RedisHash hash;
        if (entry == null) {
            hash = new RedisHash();
            store.put(key, new RedisData(RedisDataType.HASH, hash));
        } else if (entry.getType() != RedisDataType.HASH) {
            return -1;
        } else {
            hash = entry.getValue(RedisHash.class);
        }
        return hash.hsetnx(field, value);
    }

    public List<byte[]> hrandfield(String key, long count) {
        RedisHash hash = getHash(key);
        if (hash == null) return List.of();
        return hash.hrandfield(count);
    }

    // Set operations

    public RedisSet getSet(String key) {
        RedisData entry = getEntry(key);
        if (entry == null) return null;
        if (entry.getType() != RedisDataType.SET) return null;
        return entry.getValue(RedisSet.class);
    }

    public long sadd(String key, byte[]... members) {
        RedisData entry = getEntry(key);
        RedisSet set;
        if (entry == null) {
            set = new RedisSet();
            store.put(key, new RedisData(RedisDataType.SET, set));
        } else if (entry.getType() != RedisDataType.SET) {
            return -1;
        } else {
            set = entry.getValue(RedisSet.class);
        }
        return set.sadd(members);
    }

    public long srem(String key, byte[]... members) {
        RedisSet set = getSet(key);
        if (set == null) return 0;
        long removed = set.srem(members);
        if (set.isEmpty()) {
            store.remove(key);
        }
        return removed;
    }

    public boolean sismember(String key, byte[] member) {
        RedisSet set = getSet(key);
        return set != null && set.sismember(member);
    }

    public long scard(String key) {
        RedisSet set = getSet(key);
        return set != null ? set.scard() : 0;
    }

    public Set<byte[]> smembers(String key) {
        RedisSet set = getSet(key);
        return set != null ? set.smembers() : Set.of();
    }

    public byte[] spop(String key) {
        RedisSet set = getSet(key);
        if (set == null) return null;
        byte[] value = set.spop();
        if (set.isEmpty()) {
            store.remove(key);
        }
        return value;
    }

    public List<byte[]> spop(String key, int count) {
        RedisSet set = getSet(key);
        if (set == null) return List.of();
        List<byte[]> values = set.spop(count);
        if (set.isEmpty()) {
            store.remove(key);
        }
        return values;
    }

    public byte[] srandmember(String key) {
        RedisSet set = getSet(key);
        return set != null ? set.srandmember() : null;
    }

    public List<byte[]> srandmember(String key, int count) {
        RedisSet set = getSet(key);
        return set != null ? set.srandmember(count) : List.of();
    }

    public long smove(String source, String destination, byte[] member) {
        RedisSet srcSet = getSet(source);
        if (srcSet == null) return 0;

        RedisData destEntry = getEntry(destination);
        RedisSet destSet;
        if (destEntry == null) {
            destSet = new RedisSet();
            store.put(destination, new RedisData(RedisDataType.SET, destSet));
        } else if (destEntry.getType() != RedisDataType.SET) {
            return 0;
        } else {
            destSet = destEntry.getValue(RedisSet.class);
        }

        return srcSet.smove(destSet, member);
    }

    public Set<byte[]> sunion(String... keys) {
        RedisSet[] sets = Arrays.stream(keys).map(this::getSet).filter(Objects::nonNull).toArray(RedisSet[]::new);
        if (sets.length == 0) return Set.of();
        return sets[0].sunion(Arrays.copyOfRange(sets, 1, sets.length));
    }

    public Set<byte[]> sinter(String... keys) {
        RedisSet[] sets = Arrays.stream(keys).map(this::getSet).filter(Objects::nonNull).toArray(RedisSet[]::new);
        if (sets.length == 0) return Set.of();
        return sets[0].sinter(Arrays.copyOfRange(sets, 1, sets.length));
    }

    public Set<byte[]> sdiff(String... keys) {
        RedisSet[] sets = Arrays.stream(keys).map(this::getSet).filter(Objects::nonNull).toArray(RedisSet[]::new);
        if (sets.length == 0) return Set.of();
        return sets[0].sdiff(Arrays.copyOfRange(sets, 1, sets.length));
    }

    public long sunionstore(String destination, String... keys) {
        Set<byte[]> result = sunion(keys);
        RedisSet destSet = new RedisSet();
        destSet.sadd(result.toArray(new byte[0][]));
        store.put(destination, new RedisData(RedisDataType.SET, destSet));
        return result.size();
    }

    public long sinterstore(String destination, String... keys) {
        Set<byte[]> result = sinter(keys);
        RedisSet destSet = new RedisSet();
        destSet.sadd(result.toArray(new byte[0][]));
        store.put(destination, new RedisData(RedisDataType.SET, destSet));
        return result.size();
    }

    public long sdiffstore(String destination, String... keys) {
        Set<byte[]> result = sdiff(keys);
        RedisSet destSet = new RedisSet();
        destSet.sadd(result.toArray(new byte[0][]));
        store.put(destination, new RedisData(RedisDataType.SET, destSet));
        return result.size();
    }

    public List<Long> smismember(String key, byte[]... members) {
        RedisSet set = getSet(key);
        if (set == null) {
            List<Long> result = new ArrayList<>();
            for (int i = 0; i < members.length; i++) {
                result.add(0L);
            }
            return result;
        }
        return set.smismember(members);
    }

    // ZSet operations

    public RedisZSet getZSet(String key) {
        RedisData entry = getEntry(key);
        if (entry == null) return null;
        if (entry.getType() != RedisDataType.ZSET) return null;
        return entry.getValue(RedisZSet.class);
    }

    public long zadd(String key, double score, byte[] member) {
        RedisData entry = getEntry(key);
        RedisZSet zset;
        if (entry == null) {
            zset = new RedisZSet();
            store.put(key, new RedisData(RedisDataType.ZSET, zset));
        } else if (entry.getType() != RedisDataType.ZSET) {
            return -1;
        } else {
            zset = entry.getValue(RedisZSet.class);
        }
        return zset.zadd(score, member);
    }

    public double zincrby(String key, double delta, byte[] member) {
        RedisData entry = getEntry(key);
        RedisZSet zset;
        if (entry == null) {
            zset = new RedisZSet();
            store.put(key, new RedisData(RedisDataType.ZSET, zset));
        } else if (entry.getType() != RedisDataType.ZSET) {
            throw new IllegalArgumentException("WRONGTYPE Operation against a key holding the wrong kind of value");
        } else {
            zset = entry.getValue(RedisZSet.class);
        }
        return zset.zincrby(delta, member);
    }

    public long zrem(String key, byte[]... members) {
        RedisZSet zset = getZSet(key);
        if (zset == null) return 0;
        long removed = zset.zrem(members);
        if (zset.isEmpty()) {
            store.remove(key);
        }
        return removed;
    }

    public Double zscore(String key, byte[] member) {
        RedisZSet zset = getZSet(key);
        return zset != null ? zset.zscore(member) : null;
    }

    public Long zrank(String key, byte[] member) {
        RedisZSet zset = getZSet(key);
        return zset != null ? zset.zrank(member) : null;
    }

    public Long zrevrank(String key, byte[] member) {
        RedisZSet zset = getZSet(key);
        return zset != null ? zset.zrevrank(member) : null;
    }

    public long zcard(String key) {
        RedisZSet zset = getZSet(key);
        return zset != null ? zset.zcard() : 0;
    }

    public long zcount(String key, double min, double max) {
        RedisZSet zset = getZSet(key);
        return zset != null ? zset.zcount(min, max) : 0;
    }

    public List<ZSetEntry> zrange(String key, long start, long stop) {
        RedisZSet zset = getZSet(key);
        return zset != null ? zset.zrange(start, stop) : List.of();
    }

    public List<ZSetEntry> zrevrange(String key, long start, long stop) {
        RedisZSet zset = getZSet(key);
        return zset != null ? zset.zrevrange(start, stop) : List.of();
    }

    public List<ZSetEntry> zrangebyscore(String key, double min, double max, long offset, long count) {
        RedisZSet zset = getZSet(key);
        return zset != null ? zset.zrangebyscore(min, max, offset, count) : List.of();
    }

    public List<ZSetEntry> zrevrangebyscore(String key, double max, double min, long offset, long count) {
        RedisZSet zset = getZSet(key);
        return zset != null ? zset.zrevrangebyscore(max, min, offset, count) : List.of();
    }

    public long zremrangebyrank(String key, long start, long stop) {
        RedisZSet zset = getZSet(key);
        if (zset == null) return 0;
        long removed = zset.zremrangebyrank(start, stop);
        if (zset.isEmpty()) {
            store.remove(key);
        }
        return removed;
    }

    public long zremrangebyscore(String key, double min, double max) {
        RedisZSet zset = getZSet(key);
        if (zset == null) return 0;
        long removed = zset.zremrangebyscore(min, max);
        if (zset.isEmpty()) {
            store.remove(key);
        }
        return removed;
    }

    public List<ZSetEntry> zpopmin(String key, long count) {
        RedisZSet zset = getZSet(key);
        if (zset == null) return List.of();
        List<ZSetEntry> result = zset.zpopmin(count);
        if (zset.isEmpty()) {
            store.remove(key);
        }
        return result;
    }

    public List<ZSetEntry> zpopmax(String key, long count) {
        RedisZSet zset = getZSet(key);
        if (zset == null) return List.of();
        List<ZSetEntry> result = zset.zpopmax(count);
        if (zset.isEmpty()) {
            store.remove(key);
        }
        return result;
    }

    public List<Double> zmscore(String key, byte[]... members) {
        RedisZSet zset = getZSet(key);
        if (zset == null) return List.of();
        return zset.zmscore(members);
    }

    public List<byte[]> zrandmember(String key, long count) {
        RedisZSet zset = getZSet(key);
        if (zset == null) return List.of();
        return zset.zrandmember(count);
    }

    public List<ZSetEntry> zdiff(String... keys) {
        if (keys.length == 0) return List.of();
        List<RedisZSet> sets = new ArrayList<>();
        for (String key : keys) {
            RedisZSet zset = getZSet(key);
            if (zset != null) {
                sets.add(zset);
            }
        }
        if (sets.isEmpty()) return List.of();
        return RedisZSet.zdiff(sets);
    }

    public List<ZSetEntry> zinter(String[] keys, List<Double> weights, String aggregate) {
        if (keys.length == 0) return List.of();
        List<RedisZSet> sets = new ArrayList<>();
        for (String key : keys) {
            RedisZSet zset = getZSet(key);
            if (zset != null) {
                sets.add(zset);
            }
        }
        if (sets.isEmpty()) return List.of();
        return RedisZSet.zinter(sets, weights, aggregate);
    }

    public long zintercard(String[] keys, long limit) {
        List<ZSetEntry> result = zinter(keys, null, "SUM");
        if (limit > 0 && result.size() > limit) {
            return limit;
        }
        return result.size();
    }

    public List<ZSetEntry> zunion(String[] keys, List<Double> weights, String aggregate) {
        if (keys.length == 0) return List.of();
        List<RedisZSet> sets = new ArrayList<>();
        for (String key : keys) {
            RedisZSet zset = getZSet(key);
            if (zset != null) {
                sets.add(zset);
            }
        }
        if (sets.isEmpty()) return List.of();
        return RedisZSet.zunion(sets, weights, aggregate);
    }

    public long zdiffstore(String destination, String... keys) {
        List<ZSetEntry> result = zdiff(keys);
        RedisZSet newZset = new RedisZSet();
        for (ZSetEntry entry : result) {
            newZset.zadd(entry.score(), entry.member());
        }
        store.put(destination, new RedisData(RedisDataType.ZSET, newZset));
        return result.size();
    }

    public long zinterstore(String destination, String[] keys, List<Double> weights, String aggregate) {
        List<ZSetEntry> result = zinter(keys, weights, aggregate);
        RedisZSet newZset = new RedisZSet();
        for (ZSetEntry entry : result) {
            newZset.zadd(entry.score(), entry.member());
        }
        store.put(destination, new RedisData(RedisDataType.ZSET, newZset));
        return result.size();
    }

    public long zunionstore(String destination, String[] keys, List<Double> weights, String aggregate) {
        List<ZSetEntry> result = zunion(keys, weights, aggregate);
        RedisZSet newZset = new RedisZSet();
        for (ZSetEntry entry : result) {
            newZset.zadd(entry.score(), entry.member());
        }
        store.put(destination, new RedisData(RedisDataType.ZSET, newZset));
        return result.size();
    }

    public long zrangestore(String destination, String source, long start, long stop) {
        RedisZSet zset = getZSet(source);
        if (zset == null) return 0;
        List<ZSetEntry> range = zset.zrange(start, stop);
        RedisZSet newZset = new RedisZSet();
        for (ZSetEntry entry : range) {
            newZset.zadd(entry.score(), entry.member());
        }
        store.put(destination, new RedisData(RedisDataType.ZSET, newZset));
        return range.size();
    }

    public long zlexcount(String key, byte[] min, byte[] max) {
        RedisZSet zset = getZSet(key);
        if (zset == null) return 0;
        return zset.zlexcount(min, max);
    }

    public List<byte[]> zrangebylex(String key, byte[] min, byte[] max, long offset, long count) {
        RedisZSet zset = getZSet(key);
        if (zset == null) return List.of();
        return zset.zrangebylex(min, max, offset, count);
    }

    public List<byte[]> zrevrangebylex(String key, byte[] max, byte[] min, long offset, long count) {
        RedisZSet zset = getZSet(key);
        if (zset == null) return List.of();
        return zset.zrevrangebylex(max, min, offset, count);
    }

    public long zremrangebylex(String key, byte[] min, byte[] max) {
        RedisZSet zset = getZSet(key);
        if (zset == null) return 0;
        long removed = zset.zremrangebylex(min, max);
        if (zset.isEmpty()) {
            store.remove(key);
        }
        return removed;
    }

    public List<byte[]> sort(String key, boolean alpha, boolean desc, boolean hasLimit, long limitOffset, long limitCount) {
        RedisData entry = getEntry(key);
        if (entry == null) return null;

        List<byte[]> elements;
        switch (entry.getType()) {
            case LIST -> {
                RedisList list = entry.getValue(RedisList.class);
                elements = new ArrayList<>(list.lrange(0, -1));
            }
            case SET -> {
                RedisSet set = entry.getValue(RedisSet.class);
                elements = new ArrayList<>(set.smembers());
            }
            case ZSET -> {
                RedisZSet zset = entry.getValue(RedisZSet.class);
                elements = new ArrayList<>();
                for (ZSetEntry ze : zset.zrange(0, -1)) {
                    elements.add(ze.member());
                }
            }
            default -> throw new IllegalArgumentException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }

        sortElements(elements, alpha, desc);

        if (hasLimit && limitCount >= 0) {
            int start = (int) Math.min(limitOffset, elements.size());
            int end = (int) Math.min(limitOffset + limitCount, elements.size());
            elements = new ArrayList<>(elements.subList(start, end));
        }

        if (elements.isEmpty()) {
            return elements;
        }
        return elements;
    }

    private static void sortElements(List<byte[]> elements, boolean alpha, boolean desc) {
        if (alpha) {
            elements.sort((a, b) -> {
                int len = Math.min(a.length, b.length);
                for (int i = 0; i < len; i++) {
                    int cmp = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
                    if (cmp != 0) return desc ? -cmp : cmp;
                }
                int cmp = Integer.compare(a.length, b.length);
                return desc ? -cmp : cmp;
            });
        } else {
            elements.sort((a, b) -> {
                double da, db;
                try {
                    da = Double.parseDouble(new String(a));
                } catch (NumberFormatException e) {
                    da = 0;
                }
                try {
                    db = Double.parseDouble(new String(b));
                } catch (NumberFormatException e) {
                    db = 0;
                }
                return desc ? Double.compare(db, da) : Double.compare(da, db);
            });
        }
    }

    public List<Object> scan(long cursor, String pattern, long count) {
        List<String> allKeys = new ArrayList<>();
        for (Map.Entry<String, RedisData> e : store.entrySet()) {
            if (!e.getValue().isExpired()) {
                allKeys.add(e.getKey());
            }
        }
        long totalKeys = allKeys.size();

        if (cursor >= totalKeys) {
            return List.of(0L, new String[0]);
        }

        long endIndex = Math.min(cursor + count, totalKeys);
        List<String> matchedKeys = new ArrayList<>();

        for (long i = cursor; i < endIndex; i++) {
            String key = allKeys.get((int) i);
            if (pattern == null || pattern.isEmpty() || pattern.equals("*") || matchPattern(key, pattern)) {
                matchedKeys.add(key);
            }
        }

        long nextCursor = endIndex >= totalKeys ? 0 : endIndex;
        return List.of(nextCursor, matchedKeys.toArray(new String[0]));
    }

    public List<Object> sscan(String key, long cursor, String pattern, long count) {
        RedisSet set = getSet(key);
        if (set == null) {
            return List.of(0L, new byte[0][]);
        }
        List<Object> scanResult = set.scan(cursor, count);
        long nextCursor = (Long) scanResult.get(0);
        byte[][] rawMembers = (byte[][]) scanResult.get(1);

        if (pattern == null || pattern.isEmpty() || pattern.equals("*")) {
            return scanResult;
        }

        List<byte[]> matched = new ArrayList<>();
        for (byte[] member : rawMembers) {
            if (matchPattern(new String(member), pattern)) {
                matched.add(member);
            }
        }
        return List.of(nextCursor, matched.toArray(new byte[0][]));
    }

    public List<Object> hscan(String key, long cursor, String pattern, long count) {
        RedisHash hash = getHash(key);
        if (hash == null) {
            return List.of(0L, new byte[0][]);
        }
        List<Object> scanResult = hash.scan(cursor, count);
        long nextCursor = (Long) scanResult.get(0);
        byte[][] entries = (byte[][]) scanResult.get(1);

        if (pattern == null || pattern.isEmpty() || pattern.equals("*")) {
            return scanResult;
        }

        List<byte[]> matched = new ArrayList<>();
        for (int i = 0; i < entries.length; i += 2) {
            String field = new String(entries[i]);
            if (matchPattern(field, pattern)) {
                matched.add(entries[i]);
                matched.add(entries[i + 1]);
            }
        }
        return List.of(nextCursor, matched.toArray(new byte[0][]));
    }

    public List<Object> zscan(String key, long cursor, String pattern, long count) {
        RedisZSet zset = getZSet(key);
        if (zset == null) {
            return List.of(0L, new byte[0][]);
        }
        List<Object> scanResult = zset.scan(cursor, count);
        long nextCursor = (Long) scanResult.get(0);
        byte[][] elements = (byte[][]) scanResult.get(1);

        if (pattern == null || pattern.isEmpty() || pattern.equals("*")) {
            return scanResult;
        }

        List<byte[]> matched = new ArrayList<>();
        for (int i = 0; i < elements.length; i += 2) {
            String member = new String(elements[i]);
            if (matchPattern(member, pattern)) {
                matched.add(elements[i]);
                matched.add(elements[i + 1]);
            }
        }
        return List.of(nextCursor, matched.toArray(new byte[0][]));
    }

    public String[] keys(String pattern) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, RedisData> e : store.entrySet()) {
            if (!e.getValue().isExpired() && matchPattern(e.getKey(), pattern)) {
                result.add(e.getKey());
            }
        }
        return result.toArray(new String[0]);
    }

    public boolean rename(String key, String newKey) {
        RedisData entry = getEntry(key);
        if (entry == null) return false;
        store.remove(key);
        store.put(newKey, entry);
        return true;
    }

    public boolean renamenx(String key, String newKey) {
        if (exists(newKey)) return false;
        return rename(key, newKey);
    }

    public String randomKey() {
        List<String> validKeys = new ArrayList<>();
        for (Map.Entry<String, RedisData> e : store.entrySet()) {
            if (!e.getValue().isExpired()) {
                validKeys.add(e.getKey());
            }
        }
        if (validKeys.isEmpty()) return null;
        return validKeys.get(new Random().nextInt(validKeys.size()));
    }

    public boolean copy(String sourceKey, String destKey) {
        RedisData entry = getEntry(sourceKey);
        if (entry == null) return false;
        Object value = entry.getValue(Object.class);
        RedisData copy = new RedisData(entry.getType(), value, entry.getExpireAtMs());
        store.put(destKey, copy);
        return true;
    }

    public boolean move(String key, DatabaseManager dbManager, int targetDb) {
        if (store.containsKey(key)) {
            MemoryStore targetStore = dbManager.getStore(targetDb);
            if (targetStore == null) return false;
            if (targetStore.exists(key)) return false;
            RedisData entry = store.remove(key);
            if (entry != null) {
                targetStore.getStore().put(key, entry);
                return true;
            }
        }
        return false;
    }

    public long touch(String... keys) {
        long count = 0;
        for (String key : keys) {
            RedisData entry = getEntry(key);
            if (entry != null) {
                entry.setExpireAt(entry.getExpireAtMs());
                count++;
            }
        }
        return count;
    }

    // HyperLogLog operations

    public int pfadd(String key, byte[]... elements) {
        RedisData entry = getEntry(key);
        HyperLogLog hll;
        boolean existed = true;
        if (entry == null) {
            hll = new HyperLogLog();
            existed = false;
        } else if (entry.getType() != RedisDataType.STRING) {
            return 0;
        } else {
            hll = new HyperLogLog(entry.getValue(byte[].class));
        }
        for (byte[] element : elements) {
            hll.add(element);
        }
        store.put(key, new RedisData(RedisDataType.STRING, hll.toBytes()));
        return existed ? 0 : 1;
    }

    public long pfcount(String... keys) {
        if (keys.length == 0) return 0;
        if (keys.length == 1) {
            RedisData entry = getEntry(keys[0]);
            if (entry == null) return 0;
            if (entry.getType() != RedisDataType.STRING) return 0;
            HyperLogLog hll = new HyperLogLog(entry.getValue(byte[].class));
            return hll.count();
        }
        HyperLogLog merged = null;
        for (String key : keys) {
            RedisData entry = getEntry(key);
            if (entry == null || entry.getType() != RedisDataType.STRING) continue;
            HyperLogLog hll = new HyperLogLog(entry.getValue(byte[].class));
            if (merged == null) {
                merged = hll;
            } else {
                merged.merge(hll);
            }
        }
        if (merged == null) return 0;
        return merged.count();
    }

    public String pfmerge(String destKey, String... srcKeys) {
        HyperLogLog merged = new HyperLogLog();
        for (String key : srcKeys) {
            RedisData entry = getEntry(key);
            if (entry == null || entry.getType() != RedisDataType.STRING) continue;
            HyperLogLog hll = new HyperLogLog(entry.getValue(byte[].class));
            merged.merge(hll);
        }
        store.put(destKey, new RedisData(RedisDataType.STRING, merged.toBytes()));
        return "OK";
    }

    // Stream operations

    public RedisStream getStream(String key) {
        RedisData entry = getEntry(key);
        if (entry == null) return null;
        if (entry.getType() != RedisDataType.STREAM) return null;
        return entry.getValue(RedisStream.class);
    }

    public String xadd(String key, String id, Map<String, byte[]> fields) {
        RedisData entry = getEntry(key);
        RedisStream stream;
        if (entry == null) {
            stream = new RedisStream();
            store.put(key, new RedisData(RedisDataType.STREAM, stream));
        } else if (entry.getType() != RedisDataType.STREAM) {
            throw new IllegalArgumentException("WRONGTYPE Operation against a key holding the wrong kind of value");
        } else {
            stream = entry.getValue(RedisStream.class);
        }
        return stream.xadd(id, fields);
    }

    public long xlen(String key) {
        RedisStream stream = getStream(key);
        return stream != null ? stream.xlen() : 0;
    }

    public List<StreamEntry> xrange(String key, String start, String end, long count) {
        RedisStream stream = getStream(key);
        return stream != null ? stream.xrange(start, end, count) : List.of();
    }

    public List<StreamEntry> xrevrange(String key, String end, String start, long count) {
        RedisStream stream = getStream(key);
        return stream != null ? stream.xrevrange(end, start, count) : List.of();
    }

    public List<StreamEntry> xread(String key, String startId, long count) {
        RedisStream stream = getStream(key);
        return stream != null ? stream.xread(startId, count) : List.of();
    }

    public long xtrim(String key, long maxLen, boolean approx) {
        RedisStream stream = getStream(key);
        if (stream == null) return 0;
        long removed = stream.xtrim(maxLen, approx);
        if (stream.isEmpty()) {
            store.remove(key);
        }
        return removed;
    }

    public long xdel(String key, String... ids) {
        RedisStream stream = getStream(key);
        if (stream == null) return 0;
        long removed = stream.xdel(ids);
        if (stream.isEmpty()) {
            store.remove(key);
        }
        return removed;
    }

    public List<StreamEntry> xpending(String key, String group, String start, String end, long count, String consumer) {
        RedisStream stream = getStream(key);
        return stream != null ? stream.xpending(group, start, end, count, consumer) : List.of();
    }

    public void deleteExpired() {
        store.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    public static boolean matchPattern(String key, String pattern) {
        if (pattern.equals("*")) return true;
        int i = 0, j = 0;
        int starIndex = -1, matchIndex = 0;

        while (i < key.length()) {
            if (j < pattern.length() && (pattern.charAt(j) == '?' || pattern.charAt(j) == key.charAt(i))) {
                i++;
                j++;
            } else if (j < pattern.length() && pattern.charAt(j) == '*') {
                starIndex = j;
                matchIndex = i;
                j++;
            } else if (starIndex != -1) {
                j = starIndex + 1;
                matchIndex++;
                i = matchIndex;
            } else {
                return false;
            }
        }

        while (j < pattern.length() && pattern.charAt(j) == '*') {
            j++;
        }

        return j == pattern.length();
    }
}
