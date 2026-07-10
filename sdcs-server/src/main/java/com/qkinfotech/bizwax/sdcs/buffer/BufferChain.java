package com.qkinfotech.bizwax.sdcs.buffer;

import java.nio.ByteBuffer;

/**
 * A chain of DirectByteBuffer slots for network IO.
 * <p>
 * Designed as a 2-slot ring: the IO thread fills one slot while
 * the decoder/worker reads from the other. In the current single-threaded
 * model the slots are accessed sequentially; in the future three-thread
 * model (accept/io/work) this enables lock-free handoff.
 * </p>
 */
public class BufferChain {

    private final ByteBuffer slot0;
    private final ByteBuffer slot1;
    private int writeSlot;  // index being filled by IO
    private int readSlot;   // index being drained by decoder

    private int readableCount; // how many slots are ready for reading

    public BufferChain(BufferPool pool) {
        this.slot0 = pool.acquire();
        this.slot1 = pool.acquire();
        this.writeSlot = 0;
        this.readSlot = 0;
        this.readableCount = 0;
    }

    /**
     * Get the current writable buffer for {@code SocketChannel.read()}.
     * Caller must flip via {@link #flipWrite()} after reading.
     */
    public ByteBuffer writable() {
        return writeSlot == 0 ? slot0 : slot1;
    }

    /**
     * Flip the write slot to make data readable, then advance the write slot.
     */
    public void flipWrite() {
        writable().flip();
        readableCount++;
        writeSlot = (writeSlot + 1) % 2;
        ByteBuffer next = writable();
        next.clear();
    }

    /**
     * Get a readable buffer for the decoder.
     * Returns {@code null} if no data is ready.
     */
    public ByteBuffer readable() {
        if (readableCount <= 0) return null;
        return readSlot == 0 ? slot0 : slot1;
    }

    /**
     * Release the current read slot back to the pool and advance the read slot.
     * Call this after the decoder has consumed all data from {@link #readable()}.
     */
    public void releaseRead(BufferPool pool) {
        if (readableCount <= 0) return;
        ByteBuffer buf = readable();
        pool.release(buf);
        readableCount--;
        readSlot = (readSlot + 1) % 2;
    }

    /**
     * Release both slots back to the pool (e.g. on connection close).
     */
    public void releaseAll(BufferPool pool) {
        pool.release(slot0);
        pool.release(slot1);
        readableCount = 0;
    }

    public int readableSlots() {
        return readableCount;
    }
}
