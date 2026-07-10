package com.qkinfotech.bizwax.sdcs.buffer;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Pool of fixed-size DirectByteBuffer chunks for network IO.
 * <p>
 * Buffers are allocated once and reused throughout the connection lifecycle.
 * This avoids repeated allocation/deallocation and keeps GC pressure minimal.
 * </p>
 */
public class BufferPool {

    private final int chunkSize;
    private final int maxPoolSize;
    private final ConcurrentLinkedQueue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();

    private volatile int allocated;

    /**
     * @param chunkSize   size of each buffer chunk (default {@link ByteArrayChain#CHUNK_SIZE})
     * @param maxPoolSize maximum number of idle buffers to keep in the pool
     */
    public BufferPool(int chunkSize, int maxPoolSize) {
        this.chunkSize = chunkSize;
        this.maxPoolSize = maxPoolSize;
    }

    public BufferPool() {
        this(ByteArrayChain.CHUNK_SIZE, 4096);
    }

    /**
     * Acquire a DirectByteBuffer from the pool or allocate a new one.
     */
    public ByteBuffer acquire() {
        ByteBuffer buf = pool.poll();
        if (buf != null) {
            buf.clear();
            return buf;
        }
        allocated++;
        return ByteBuffer.allocateDirect(chunkSize);
    }

    /**
     * Release a buffer back to the pool.
     * If the pool is full, the buffer is discarded (GC).
     */
    public void release(ByteBuffer buf) {
        buf.clear();
        if (pool.size() < maxPoolSize) {
            pool.offer(buf);
        }
    }

    /**
     * Current number of idle buffers in the pool.
     */
    public int idleCount() {
        return pool.size();
    }

    /**
     * Total number of buffers ever allocated from this pool.
     */
    public int allocatedCount() {
        return allocated;
    }

    /**
     * Drain all idle buffers (for shutdown).
     */
    public void drainAll() {
        pool.clear();
    }

    public int chunkSize() {
        return chunkSize;
    }
}
