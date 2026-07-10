package com.qkinfotech.bizwax.sdcs.buffer;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ByteArrayChainTest {

    @Test
    void emptyChain() {
        ByteArrayChain chain = new ByteArrayChain();
        assertTrue(chain.isEmpty());
        assertEquals(0, chain.length());
        assertEquals(0, chain.chunkCount());
        assertArrayEquals(new byte[0], chain.toByteArray());
    }

    @Test
    void writeSingleByte() {
        ByteArrayChain chain = new ByteArrayChain();
        chain.write((byte) 'A');
        assertEquals(1, chain.length());
        assertFalse(chain.isEmpty());
        assertEquals('A', chain.get(0));
    }

    @Test
    void writeByteArray() {
        ByteArrayChain chain = new ByteArrayChain();
        byte[] data = "hello".getBytes();
        chain.write(data);
        assertEquals(5, chain.length());
        assertArrayEquals(data, chain.toByteArray());
    }

    @Test
    void writeSubRange() {
        ByteArrayChain chain = new ByteArrayChain();
        byte[] data = "hello world".getBytes();
        chain.write(data, 6, 5);
        assertEquals(5, chain.length());
        assertArrayEquals("world".getBytes(), chain.toByteArray());
    }

    @Test
    void forEachIteratesAllChunks() {
        ByteArrayChain chain = new ByteArrayChain();
        chain.write("abc".getBytes());
        chain.write("def".getBytes());

        List<byte[]> chunks = new ArrayList<>();
        chain.forEach((data, off, len) -> {
            byte[] copy = new byte[len];
            System.arraycopy(data, off, copy, 0, len);
            chunks.add(copy);
        });
        assertEquals(2, chunks.size());
        assertArrayEquals("abc".getBytes(), chunks.get(0));
        assertArrayEquals("def".getBytes(), chunks.get(1));
    }

    @Test
    void toByteArrayMergesAllChunks() {
        ByteArrayChain chain = new ByteArrayChain();
        chain.write("Hello, ".getBytes());
        chain.write("World!".getBytes());
        assertArrayEquals("Hello, World!".getBytes(), chain.toByteArray());
    }

    @Test
    void getAndSetRandomAccess() {
        ByteArrayChain chain = new ByteArrayChain();
        chain.write("test".getBytes());
        assertEquals('t', chain.get(0));
        assertEquals('e', chain.get(1));
        assertEquals('s', chain.get(2));
        assertEquals('t', chain.get(3));

        chain.set(1, (byte) 'a');
        assertEquals('a', chain.get(1));
        assertArrayEquals("tast".getBytes(), chain.toByteArray());
    }

    @Test
    void getOutOfBoundsThrows() {
        ByteArrayChain chain = new ByteArrayChain();
        chain.write("abc".getBytes());
        assertThrows(IndexOutOfBoundsException.class, () -> chain.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> chain.get(3));
    }

    @Test
    void setOutOfBoundsThrows() {
        ByteArrayChain chain = new ByteArrayChain();
        chain.write("abc".getBytes());
        assertThrows(IndexOutOfBoundsException.class, () -> chain.set(-1, (byte) 'x'));
        assertThrows(IndexOutOfBoundsException.class, () -> chain.set(3, (byte) 'x'));
    }

    @Test
    void subChainEmpty() {
        ByteArrayChain chain = new ByteArrayChain();
        chain.write("data".getBytes());
        ByteArrayChain sub = chain.subChain(2, 2);
        assertTrue(sub.isEmpty());
        assertEquals(0, sub.length());
    }

    @Test
    void subChainWithinSingleChunk() {
        ByteArrayChain chain = new ByteArrayChain();
        chain.write("hello world".getBytes());
        ByteArrayChain sub = chain.subChain(6, 11);
        assertArrayEquals("world".getBytes(), sub.toByteArray());
    }

    @Test
    void subChainFullRange() {
        ByteArrayChain chain = new ByteArrayChain();
        chain.write("complete".getBytes());
        ByteArrayChain sub = chain.subChain(0, 8);
        assertArrayEquals("complete".getBytes(), sub.toByteArray());
    }

    @Test
    void subChainInvalidRangeThrows() {
        ByteArrayChain chain = new ByteArrayChain();
        chain.write("abc".getBytes());
        assertThrows(IndexOutOfBoundsException.class, () -> chain.subChain(-1, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> chain.subChain(0, 4));
        assertThrows(IndexOutOfBoundsException.class, () -> chain.subChain(2, 1));
    }

    @Test
    void chunkCountAndClear() {
        ByteArrayChain chain = new ByteArrayChain();
        // write enough to span multiple chunks (CHUNK_SIZE = 4096)
        byte[] big = new byte[8192];
        chain.write(big);
        assertEquals(2, chain.chunkCount());
        assertEquals(8192, chain.length());

        chain.clear();
        assertTrue(chain.isEmpty());
        assertEquals(0, chain.length());
        assertEquals(0, chain.chunkCount());
    }

    @Test
    void writeZeroLengthDoesNothing() {
        ByteArrayChain chain = new ByteArrayChain();
        byte[] data = "abc".getBytes();
        chain.write(data, 0, 0);
        assertTrue(chain.isEmpty());
    }

    @Test
    void writeNullArrayThrowsNPE() {
        ByteArrayChain chain = new ByteArrayChain();
        assertThrows(NullPointerException.class, () -> chain.write((byte[]) null));
    }

    @Test
    void getOnEmptyChainThrows() {
        ByteArrayChain chain = new ByteArrayChain();
        assertThrows(IndexOutOfBoundsException.class, () -> chain.get(0));
    }

    @Test
    void writeByteBuffer() {
        ByteArrayChain chain = new ByteArrayChain();
        ByteBuffer src = ByteBuffer.wrap("buffer data".getBytes());
        chain.write(src, 6);
        assertArrayEquals("buffer".getBytes(), chain.toByteArray());
    }

    @Test
    void largeWriteSpansMultipleChunks() {
        ByteArrayChain chain = new ByteArrayChain();
        byte[] large = new byte[ByteArrayChain.CHUNK_SIZE * 3 + 100];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 128);
        }
        chain.write(large);
        assertEquals(large.length, chain.length());
        assertTrue(chain.chunkCount() >= 4);
        assertArrayEquals(large, chain.toByteArray());
    }
}
