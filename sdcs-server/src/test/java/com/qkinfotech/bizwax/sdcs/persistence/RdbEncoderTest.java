package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.common.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RdbEncoderTest {

    @Test
    void testWriteLengthSmall() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeLength(5);
        assertArrayEquals(new byte[]{0x05}, bos.toByteArray());
    }

    @Test
    void testWriteLengthMedium() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeLength(100);
        assertArrayEquals(new byte[]{0x40, 0x64}, bos.toByteArray());
    }

    @Test
    void testWriteLengthLarge() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeLength(20000);
        assertArrayEquals(new byte[]{(byte) 0x80, 0, 0, 0x4E, 0x20}, bos.toByteArray());
    }

    @Test
    void testWriteString() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeString("foo");
        assertArrayEquals(new byte[]{0x03, 'f', 'o', 'o'}, bos.toByteArray());
    }

    @Test
    void testWriteList() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        RedisList list = new RedisList();
        list.rpush("a".getBytes(StandardCharsets.UTF_8));
        list.rpush("b".getBytes(StandardCharsets.UTF_8));
        encoder.writeList(list);
        assertArrayEquals(new byte[]{0x02, 0x01, 'a', 0x01, 'b'}, bos.toByteArray());
    }

    @Test
    void testWriteSet() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        RedisSet set = new RedisSet();
        set.sadd("a".getBytes(StandardCharsets.UTF_8));
        encoder.writeSet(set);
        assertArrayEquals(new byte[]{0x01, 0x01, 'a'}, bos.toByteArray());
    }

    @Test
    void testWriteHash() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        RedisHash hash = new RedisHash();
        hash.hset("k", "v".getBytes(StandardCharsets.UTF_8));
        encoder.writeHash(hash);
        assertArrayEquals(new byte[]{0x01, 0x01, 'k', 0x01, 'v'}, bos.toByteArray());
    }

    @Test
    void testWriteZSet() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        RedisZSet zset = new RedisZSet();
        zset.zadd(10.0, "m".getBytes(StandardCharsets.UTF_8));
        encoder.writeZSet(zset);
        assertArrayEquals(new byte[]{0x01, 0x01, 'm', 0x02, '1', '0'}, bos.toByteArray());
    }

    @Test
    void testWriteLengthLarge4ByteBoundary() throws IOException {
        // len=16384 triggers 4-byte encoding (0x80 + 4-byte int)
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeLength(16384);
        // 16384 = 0x4000, big-endian int: 0x00, 0x00, 0x40, 0x00
        assertArrayEquals(new byte[]{(byte) 0x80, 0x00, 0x00, 0x40, 0x00}, bos.toByteArray());
    }

    @Test
    void testWriteLengthNegative() throws IOException {
        // Negative length: encode -1 and verify decoding throws IOException
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeLength(-1);
        byte[] data = bos.toByteArray();
        RdbDecoder decoder = new RdbDecoder(new ByteArrayInputStream(data));
        assertThrows(IOException.class, decoder::readLength);
    }

    @Test
    void testWriteStringEmpty() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeString("");
        // writeLength(0) → 0x00, then no data bytes
        assertArrayEquals(new byte[]{0x00}, bos.toByteArray());
    }

    @Test
    void testWriteZSetSpecialScores() throws IOException {
        // NaN score
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        RedisZSet zsetNaN = new RedisZSet();
        zsetNaN.zadd(Double.NaN, "m".getBytes(StandardCharsets.UTF_8));
        encoder.writeZSet(zsetNaN);
        // writeLength(1)=0x01, writeString("m")=0x01'm', writeString("NaN")=0x03'N''a''N'
        assertArrayEquals(new byte[]{0x01, 0x01, 'm', 0x03, 'N', 'a', 'N'}, bos.toByteArray());

        // Infinity score
        bos.reset();
        encoder = new RdbEncoder(bos);
        RedisZSet zsetInf = new RedisZSet();
        zsetInf.zadd(Double.POSITIVE_INFINITY, "m".getBytes(StandardCharsets.UTF_8));
        encoder.writeZSet(zsetInf);
        // writeString("Infinity") = 0x08, 'I', 'n', 'f', 'i', 'n', 'i', 't', 'y'
        assertArrayEquals(new byte[]{0x01, 0x01, 'm', 0x08, 'I', 'n', 'f', 'i', 'n', 'i', 't', 'y'}, bos.toByteArray());

        // -0.0 score (encoded as "0" since -0.0 % 1 == 0.0 is true)
        bos.reset();
        encoder = new RdbEncoder(bos);
        RedisZSet zsetNegZero = new RedisZSet();
        zsetNegZero.zadd(-0.0, "m".getBytes(StandardCharsets.UTF_8));
        encoder.writeZSet(zsetNegZero);
        // writeString("0") = 0x01, '0'
        assertArrayEquals(new byte[]{0x01, 0x01, 'm', 0x01, '0'}, bos.toByteArray());
    }
}
