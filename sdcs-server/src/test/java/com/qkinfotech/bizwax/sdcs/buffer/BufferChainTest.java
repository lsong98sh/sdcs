package com.qkinfotech.bizwax.sdcs.buffer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class BufferChainTest {

    private BufferPool pool;

    @BeforeEach
    void setUp() {
        pool = new BufferPool(64, 10);
    }

    @Test
    void constructorProvidesWritableBuffer() {
        BufferChain chain = new BufferChain(pool);
        ByteBuffer writable = chain.writable();
        assertNotNull(writable);
        assertTrue(writable.isDirect());
        // writable buffer should be in write mode (position=0, limit=capacity)
        assertEquals(0, writable.position());
        assertEquals(64, writable.limit());
    }

    @Test
    void flipWriteSwitchesSlotAndMakesDataReadable() {
        BufferChain chain = new BufferChain(pool);
        // write some data to the writable slot
        ByteBuffer writable = chain.writable();
        writable.put((byte) 'A');
        writable.put((byte) 'B');
        assertEquals(2, writable.position());

        chain.flipWrite();

        // after flip, readable should return the data we wrote
        ByteBuffer readable = chain.readable();
        assertNotNull(readable);
        assertEquals(2, readable.limit());
        assertEquals('A', readable.get());
        assertEquals('B', readable.get());
    }

    @Test
    void readableAndReleaseReadBasicFlow() {
        BufferChain chain = new BufferChain(pool);
        assertEquals(0, chain.readableSlots());

        // write some data and flip
        chain.writable().put((byte) 'X');
        chain.flipWrite();
        assertEquals(1, chain.readableSlots());

        // read the data
        ByteBuffer readable = chain.readable();
        assertNotNull(readable);
        assertEquals('X', readable.get());

        // release the read slot
        chain.releaseRead(pool);
        assertEquals(0, chain.readableSlots());
    }

    @Test
    void multipleFlipWritesIncrementReadableCount() {
        BufferChain chain = new BufferChain(pool);
        assertEquals(0, chain.readableSlots());

        chain.writable().put((byte) '1');
        chain.flipWrite();
        assertEquals(1, chain.readableSlots());

        chain.writable().put((byte) '2');
        chain.flipWrite();
        assertEquals(2, chain.readableSlots());

        // release both
        chain.releaseRead(pool);
        assertEquals(1, chain.readableSlots());

        chain.releaseRead(pool);
        assertEquals(0, chain.readableSlots());
    }

    @Test
    void releaseAllReleasesBothSlots() {
        BufferChain chain = new BufferChain(pool);
        chain.writable().put((byte) 'a');
        chain.flipWrite();
        chain.writable().put((byte) 'b');
        chain.flipWrite();
        assertEquals(2, chain.readableSlots());

        chain.releaseAll(pool);
        assertEquals(0, chain.readableSlots());
        // readable returns null after releaseAll
        assertNull(chain.readable());
    }

    @Test
    void releaseReadWhenReadableCountZeroIsSafe() {
        BufferChain chain = new BufferChain(pool);
        assertEquals(0, chain.readableSlots());
        // should not throw
        chain.releaseRead(pool);
        assertEquals(0, chain.readableSlots());
    }

    @Test
    void flipWriteClearsNextSlot() {
        BufferChain chain = new BufferChain(pool);
        ByteBuffer slot0 = chain.writable();
        slot0.put((byte) 'X');
        chain.flipWrite();

        // after flip, the write slot should be the other buffer, cleared
        ByteBuffer nextWritable = chain.writable();
        assertNotSame(slot0, nextWritable);
        assertEquals(0, nextWritable.position());
        assertEquals(64, nextWritable.limit());
    }

    @Test
    void readableReturnsNullWhenNoData() {
        BufferChain chain = new BufferChain(pool);
        assertNull(chain.readable());
    }
}
