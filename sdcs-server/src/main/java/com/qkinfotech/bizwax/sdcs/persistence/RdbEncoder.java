package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.common.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class RdbEncoder {

    private final OutputStream out;

    public RdbEncoder(OutputStream out) {
        this.out = out;
    }

    public void writeByte(int b) throws IOException {
        out.write(b);
    }

    public void writeBytes(byte[] bytes) throws IOException {
        out.write(bytes);
    }

    public void writeLength(long len) throws IOException {
        if (len < 64) {
            out.write((int) (len & 0xFF));
        } else if (len < 16384) {
            int b1 = 0x40 | (int) ((len >> 8) & 0x3F);
            int b2 = (int) (len & 0xFF);
            out.write(b1);
            out.write(b2);
        } else {
            out.write(0x80);
            writeInt((int) len);
        }
    }

    private void writeInt(int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public void writeLong(long v) throws IOException {
        out.write((int) (v >>> 56) & 0xFF);
        out.write((int) (v >>> 48) & 0xFF);
        out.write((int) (v >>> 40) & 0xFF);
        out.write((int) (v >>> 32) & 0xFF);
        out.write((int) (v >>> 24) & 0xFF);
        out.write((int) (v >>> 16) & 0xFF);
        out.write((int) (v >>> 8) & 0xFF);
        out.write((int) v & 0xFF);
    }

    public void writeString(byte[] bytes) throws IOException {
        writeLength(bytes.length);
        writeBytes(bytes);
    }

    public void writeString(String str) throws IOException {
        writeString(str.getBytes(StandardCharsets.UTF_8));
    }

    public void writeList(RedisList list) throws IOException {
        List<byte[]> items = list.lrange(0, -1);
        writeLength(items.size());
        for (byte[] item : items) {
            writeString(item);
        }
    }

    public void writeSet(RedisSet set) throws IOException {
        List<byte[]> members = new java.util.ArrayList<>(set.smembers());
        writeLength(members.size());
        for (byte[] member : members) {
            writeString(member);
        }
    }

    public void writeHash(RedisHash hash) throws IOException {
        Map<String, byte[]> map = hash.hgetAll();
        writeLength(map.size());
        for (Map.Entry<String, byte[]> entry : map.entrySet()) {
            writeString(entry.getKey());
            writeString(entry.getValue());
        }
    }

    public void writeZSet(RedisZSet zset) throws IOException {
        List<RedisZSet.ZSetEntry> entries = zset.zrange(0, -1);
        writeLength(entries.size());
        for (RedisZSet.ZSetEntry entry : entries) {
            writeString(entry.member());
            double s = entry.score();
            String scoreStr = (s % 1 == 0) ? String.valueOf((long) s) : String.valueOf(s);
            writeString(scoreStr);
        }
    }
}
