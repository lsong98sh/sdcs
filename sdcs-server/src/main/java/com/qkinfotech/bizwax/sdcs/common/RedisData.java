package com.qkinfotech.bizwax.sdcs.common;

import com.qkinfotech.bizwax.sdcs.buffer.ByteArrayChain;

public class RedisData {

    private final RedisDataType type;
    private final Object value;
    private long expireAtMs;

    public RedisData(RedisDataType type, Object value) {
        this.type = type;
        this.value = value;
        this.expireAtMs = -1;
    }

    public RedisData(RedisDataType type, Object value, long expireAtMs) {
        this.type = type;
        this.value = value;
        this.expireAtMs = expireAtMs;
    }

    public RedisDataType getType() {
        return type;
    }

    /**
     * Get the stored value. If the expected type is byte[] and the actual
     * value is a {@link ByteArrayChain}, it is automatically converted.
     */
    @SuppressWarnings("unchecked")
    public <T> T getValue(Class<T> clazz) {
        if (clazz == byte[].class && value instanceof ByteArrayChain chain) {
            return (T) chain.toByteArray();
        }
        return (T) value;
    }

    public long getExpireAtMs() {
        return expireAtMs;
    }

    public void setExpireAt(long expireAtMs) {
        this.expireAtMs = expireAtMs;
    }

    public boolean isExpired() {
        return expireAtMs >= 0 && System.currentTimeMillis() > expireAtMs;
    }

    public long ttlMs() {
        if (expireAtMs < 0) return -1;
        long remaining = expireAtMs - System.currentTimeMillis();
        return remaining > 0 ? remaining : -2;
    }
}
