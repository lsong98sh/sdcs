package com.qkinfotech.bizwax.sdcs.protocol;

import com.qkinfotech.bizwax.sdcs.buffer.ByteArrayChain;

import java.util.ArrayList;
import java.util.List;

public class RedisMessage {

    public enum Type {
        SIMPLE_STRING,
        ERROR,
        INTEGER,
        BULK_STRING,
        ARRAY
    }

    private final Type type;
    private final byte[] data;
    private final ByteArrayChain chain;
    private final List<RedisMessage> elements;
    private final long integerValue;

    private RedisMessage(Type type, byte[] data, ByteArrayChain chain, List<RedisMessage> elements, long integerValue) {
        this.type = type;
        this.data = data;
        this.chain = chain;
        this.elements = elements;
        this.integerValue = integerValue;
    }

    public static RedisMessage simpleString(String content) {
        return new RedisMessage(Type.SIMPLE_STRING, content.getBytes(), null, null, 0);
    }

    public static RedisMessage error(String content) {
        return new RedisMessage(Type.ERROR, ("ERR " + content).getBytes(), null, null, 0);
    }

    public static RedisMessage integer(long value) {
        return new RedisMessage(Type.INTEGER, null, null, null, value);
    }

    public static RedisMessage bulkString(byte[] data) {
        return new RedisMessage(Type.BULK_STRING, data, null, null, 0);
    }

    /**
     * Create a bulk string message backed by a ByteArrayChain.
     * The chain is stored directly without copying.
     */
    public static RedisMessage bulkString(ByteArrayChain chain) {
        return new RedisMessage(Type.BULK_STRING, null, chain, null, 0);
    }

    public static RedisMessage bulkString(String content) {
        return content == null ? new RedisMessage(Type.BULK_STRING, null, null, null, 0)
                : new RedisMessage(Type.BULK_STRING, content.getBytes(), null, null, 0);
    }

    public static RedisMessage array(List<RedisMessage> elements) {
        return new RedisMessage(Type.ARRAY, null, null, elements, 0);
    }

    public static RedisMessage array(RedisMessage... elements) {
        List<RedisMessage> list = new ArrayList<>();
        for (RedisMessage e : elements) {
            list.add(e);
        }
        return new RedisMessage(Type.ARRAY, null, null, list, 0);
    }

    public Type getType() {
        return type;
    }

    /**
     * Get data as a contiguous byte[].
     * If the message was created from a ByteArrayChain, this merges it.
     */
    public byte[] getData() {
        if (data != null) return data;
        if (chain != null) return chain.toByteArray();
        return null;
    }

    /**
     * Get the underlying ByteArrayChain (may be null).
     */
    public ByteArrayChain getChain() {
        return chain;
    }

    public String asString() {
        byte[] d = getData();
        return d != null ? new String(d) : null;
    }

    public List<RedisMessage> getElements() {
        return elements;
    }

    public long getIntegerValue() {
        return integerValue;
    }

    public boolean isNullBulkString() {
        return type == Type.BULK_STRING && data == null && chain == null;
    }

    @Override
    public String toString() {
        return switch (type) {
            case SIMPLE_STRING -> "+" + asString();
            case ERROR -> "-" + asString();
            case INTEGER -> ":" + integerValue;
            case BULK_STRING -> {
                if (data != null) yield "$" + data.length;
                if (chain != null) yield "$" + chain.length();
                yield "$-1";
            }
            case ARRAY -> "*" + (elements != null ? elements.size() : -1);
        };
    }
}
