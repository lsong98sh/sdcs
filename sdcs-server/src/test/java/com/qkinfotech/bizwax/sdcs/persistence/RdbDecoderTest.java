package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.common.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RdbDecoderTest {

    @Test
    void testRoundTripString() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeString("hello");
        encoder.writeString("world");

        byte[] data = bos.toByteArray();
        RdbDecoder decoder = new RdbDecoder(new ByteArrayInputStream(data));

        assertEquals("hello", decoder.readStringUtf8());
        assertEquals("world", decoder.readStringUtf8());
    }

    @Test
    void testRoundTripList() throws IOException {
        RedisList original = new RedisList();
        original.rpush("a".getBytes(StandardCharsets.UTF_8));
        original.rpush("b".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeList(original);

        RdbDecoder decoder = new RdbDecoder(new ByteArrayInputStream(bos.toByteArray()));
        RedisList decoded = decoder.readList();

        assertEquals(2, decoded.llen());
        assertArrayEquals("a".getBytes(), decoded.lindex(0));
        assertArrayEquals("b".getBytes(), decoded.lindex(1));
    }

    @Test
    void testRoundTripZSet() throws IOException {
        RedisZSet original = new RedisZSet();
        original.zadd(1.5, "member1".getBytes(StandardCharsets.UTF_8));
        original.zadd(2.0, "member2".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeZSet(original);

        RdbDecoder decoder = new RdbDecoder(new ByteArrayInputStream(bos.toByteArray()));
        RedisZSet decoded = decoder.readZSet();

        assertEquals(2, decoded.zcard());
        assertEquals(1.5, decoded.zscore("member1".getBytes()), 0.001);
        assertEquals(2.0, decoded.zscore("member2".getBytes()), 0.001);
    }

    @Test
    void testRoundTripHash() throws IOException {
        RedisHash original = new RedisHash();
        original.hset("field1", "value1".getBytes(StandardCharsets.UTF_8));
        original.hset("field2", "value2".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeHash(original);

        RdbDecoder decoder = new RdbDecoder(new ByteArrayInputStream(bos.toByteArray()));
        RedisHash decoded = decoder.readHash();

        assertEquals(2, decoded.hlen());
        assertArrayEquals("value1".getBytes(), decoded.hget("field1"));
        assertArrayEquals("value2".getBytes(), decoded.hget("field2"));
    }

    @Test
    void testRoundTripSet() throws IOException {
        RedisSet original = new RedisSet();
        original.sadd("member1".getBytes(StandardCharsets.UTF_8));
        original.sadd("member2".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeSet(original);

        RdbDecoder decoder = new RdbDecoder(new ByteArrayInputStream(bos.toByteArray()));
        RedisSet decoded = decoder.readSet();

        assertEquals(2, decoded.scard());
    }

    @Test
    void testReadLengthCorrupt() {
        // type=3 encoding (bits 7-6 = 11) should throw IOException
        byte[] data = new byte[]{(byte) 0xC0};
        RdbDecoder decoder = new RdbDecoder(new ByteArrayInputStream(data));
        assertThrows(IOException.class, decoder::readLength);
    }

    @Test
    void testReadStringTruncated() {
        // Length says 10 bytes but only 3 bytes available
        byte[] data = new byte[]{0x0A, 'a', 'b', 'c'};
        RdbDecoder decoder = new RdbDecoder(new ByteArrayInputStream(data));
        assertThrows(IOException.class, decoder::readString);
    }

    @Test
    void testReadZSetCorruptScore() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeLength(1); // one entry
        encoder.writeString("member");
        encoder.writeString("not_a_number"); // invalid score string
        byte[] data = bos.toByteArray();
        RdbDecoder decoder = new RdbDecoder(new ByteArrayInputStream(data));
        assertThrows(IOException.class, decoder::readZSet);
    }

    @Test
    void testReadEmptyCollection() throws IOException {
        // Empty list: write size=0 and read back
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        RdbEncoder encoder = new RdbEncoder(bos);
        encoder.writeLength(0); // empty list
        byte[] data = bos.toByteArray();
        RdbDecoder decoder = new RdbDecoder(new ByteArrayInputStream(data));
        RedisList list = decoder.readList();
        assertEquals(0, list.llen());
        assertTrue(list.isEmpty());

        // Empty set
        bos.reset();
        encoder = new RdbEncoder(bos);
        encoder.writeLength(0);
        data = bos.toByteArray();
        decoder = new RdbDecoder(new ByteArrayInputStream(data));
        RedisSet set = decoder.readSet();
        assertEquals(0, set.scard());

        // Empty hash
        bos.reset();
        encoder = new RdbEncoder(bos);
        encoder.writeLength(0);
        data = bos.toByteArray();
        decoder = new RdbDecoder(new ByteArrayInputStream(data));
        RedisHash hash = decoder.readHash();
        assertEquals(0, hash.hlen());

        // Empty zset
        bos.reset();
        encoder = new RdbEncoder(bos);
        encoder.writeLength(0);
        data = bos.toByteArray();
        decoder = new RdbDecoder(new ByteArrayInputStream(data));
        RedisZSet zset = decoder.readZSet();
        assertEquals(0, zset.zcard());
    }
}
