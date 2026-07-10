package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.common.*;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RdbDecoder {

    private final DataInputStream in;

    public RdbDecoder(InputStream in) {
        this.in = new DataInputStream(in);
    }

    public int readByte() throws IOException {
        return in.readUnsignedByte();
    }

    public byte[] readBytes(int len) throws IOException {
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return bytes;
    }

    public long readLength() throws IOException {
        int b = in.readUnsignedByte();
        int type = (b & 0xC0) >> 6;

        return switch (type) {
            case 0 -> b & 0x3F;
            case 1 -> {
                int next = in.readUnsignedByte();
                yield ((b & 0x3F) << 8) | next;
            }
            case 2 -> in.readInt() & 0xFFFFFFFFL;
            default -> throw new IOException("Unsupported RDB length encoding type: " + type);
        };
    }

    public byte[] readString() throws IOException {
        long len = readLength();
        if (len > Integer.MAX_VALUE) {
            throw new IOException("String too long: " + len);
        }
        return readBytes((int) len);
    }

    public String readStringUtf8() throws IOException {
        return new String(readString(), StandardCharsets.UTF_8);
    }

    public RedisList readList() throws IOException {
        long size = readLength();
        RedisList list = new RedisList();
        for (int i = 0; i < size; i++) {
            list.rpush(readString());
        }
        return list;
    }

    public RedisSet readSet() throws IOException {
        long size = readLength();
        RedisSet set = new RedisSet();
        for (int i = 0; i < size; i++) {
            set.sadd(readString());
        }
        return set;
    }

    public RedisHash readHash() throws IOException {
        long size = readLength();
        RedisHash hash = new RedisHash();
        for (int i = 0; i < size; i++) {
            String key = readStringUtf8();
            byte[] val = readString();
            hash.hset(key, val);
        }
        return hash;
    }

    public RedisZSet readZSet() throws IOException {
        long size = readLength();
        RedisZSet zset = new RedisZSet();
        for (int i = 0; i < size; i++) {
            byte[] member = readString();
            String scoreStr = readStringUtf8();
            double score;
            try {
                score = Double.parseDouble(scoreStr);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid ZSet score: " + scoreStr);
            }
            zset.zadd(score, member);
        }
        return zset;
    }

    public long readLong() throws IOException {
        return in.readLong();
    }

    public int readInt() throws IOException {
        return in.readInt();
    }
}
