package com.qkinfotech.bizwax.sdcs.common;

import java.util.*;

public class RedisHash {

    private final Map<String, byte[]> map = new LinkedHashMap<>();

    public long hset(String field, byte[] value) {
        boolean exists = map.containsKey(field);
        map.put(field, value);
        return exists ? 0 : 1;
    }

    public byte[] hget(String field) {
        return map.get(field);
    }

    public long hdel(String... fields) {
        long deleted = 0;
        for (String field : fields) {
            if (map.remove(field) != null) {
                deleted++;
            }
        }
        return deleted;
    }

    public boolean hexists(String field) {
        return map.containsKey(field);
    }

    public long hlen() {
        return map.size();
    }

    public long hstrlen(String field) {
        byte[] value = map.get(field);
        return value != null ? value.length : 0;
    }

    public long hincrBy(String field, long delta) {
        byte[] current = map.get(field);
        long value = 0;
        if (current != null) {
            try {
                value = Long.parseLong(new String(current));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ERR hash value is not an integer");
            }
        }
        value += delta;
        map.put(field, String.valueOf(value).getBytes());
        return value;
    }

    public double hincrByFloat(String field, double delta) {
        byte[] current = map.get(field);
        double value = 0.0;
        if (current != null) {
            try {
                value = Double.parseDouble(new String(current));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ERR hash value is not a float");
            }
        }
        value += delta;
        map.put(field, String.valueOf(value).getBytes());
        return value;
    }

    public Set<String> hkeys() {
        return Collections.unmodifiableSet(map.keySet());
    }

    public List<byte[]> hvals() {
        return Collections.unmodifiableList(new ArrayList<>(map.values()));
    }

    public Map<String, byte[]> hgetAll() {
        return Collections.unmodifiableMap(map);
    }

    public long hmset(Map<String, byte[]> fieldValues) {
        map.putAll(fieldValues);
        return 1;
    }

    public List<byte[]> hmget(String... fields) {
        List<byte[]> result = new ArrayList<>();
        for (String field : fields) {
            result.add(map.getOrDefault(field, null));
        }
        return result;
    }

    public long hsetnx(String field, byte[] value) {
        if (map.containsKey(field)) {
            return 0;
        }
        map.put(field, value);
        return 1;
    }

    public List<byte[]> hrandfield(long count) {
        if (count >= 0) {
            count = Math.min(count, map.size());
            List<String> keys = new ArrayList<>(map.keySet());
            Collections.shuffle(keys);
            List<byte[]> result = new ArrayList<>((int) count);
            for (int i = 0; i < count; i++) {
                result.add(map.get(keys.get(i)));
            }
            return result;
        } else {
            count = -count;
            List<String> keys = new ArrayList<>(map.keySet());
            List<byte[]> result = new ArrayList<>((int) count);
            Random random = new Random();
            for (int i = 0; i < count; i++) {
                result.add(map.get(keys.get(random.nextInt(keys.size()))));
            }
            return result;
        }
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public List<Object> scan(long cursor, long count) {
        List<String> allFields = new ArrayList<>(map.keySet());
        long totalFields = allFields.size();

        if (cursor >= totalFields) {
            return List.of(0L, new byte[0][]);
        }

        long endIndex = Math.min(cursor + count, totalFields);
        List<byte[]> entries = new ArrayList<>();

        for (long i = cursor; i < endIndex; i++) {
            String field = allFields.get((int) i);
            entries.add(field.getBytes());
            entries.add(map.get(field));
        }

        long nextCursor = endIndex >= totalFields ? 0 : endIndex;
        return List.of(nextCursor, entries.toArray(new byte[0][]));
    }
}
