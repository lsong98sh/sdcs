package com.qkinfotech.bizwax.sdcs.buffer;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class BufferPoolTest {

    @Test
    void defaultConstructorUsesChunkSizeAndMaxPoolSize() {
        BufferPool pool = new BufferPool();
        assertEquals(ByteArrayChain.CHUNK_SIZE, pool.chunkSize());
    }

    @Test
    void parameterizedConstructor() {
        BufferPool pool = new BufferPool(1024, 10);
        assertEquals(1024, pool.chunkSize());
    }

    @Test
    void acquireReturnsNonDirectBuffer() {
        BufferPool pool = new BufferPool(64, 10);
        ByteBuffer buf = pool.acquire();
        assertNotNull(buf);
        assertTrue(buf.isDirect());
        assertEquals(64, buf.capacity());
    }

    @Test
    void acquireReleaseBasicFlow() {
        BufferPool pool = new BufferPool(64, 10);
        assertEquals(0, pool.idleCount());
        assertEquals(0, pool.allocatedCount());

        ByteBuffer buf = pool.acquire();
        assertNotNull(buf);
        assertEquals(1, pool.allocatedCount());
        assertEquals(0, pool.idleCount());

        pool.release(buf);
        assertEquals(1, pool.idleCount());
        assertEquals(1, pool.allocatedCount());
    }

    @Test
    void releasedBufferIsReused() {
        BufferPool pool = new BufferPool(64, 10);
        ByteBuffer first = pool.acquire();
        first.putInt(42);
        pool.release(first);

        ByteBuffer second = pool.acquire();
        assertSame(first, second, "released buffer should be reused");
        // buffer was cleared on release, so position should be 0
        assertEquals(0, second.position());
        assertEquals(64, second.limit());
    }

    @Test
    void idleCountAndAllocatedCount() {
        BufferPool pool = new BufferPool(64, 5);
        ByteBuffer b1 = pool.acquire();
        ByteBuffer b2 = pool.acquire();
        ByteBuffer b3 = pool.acquire();
        assertEquals(3, pool.allocatedCount());
        assertEquals(0, pool.idleCount());

        pool.release(b1);
        assertEquals(1, pool.idleCount());
        pool.release(b2);
        assertEquals(2, pool.idleCount());

        // still 3 allocated, but 2 idle
        assertEquals(3, pool.allocatedCount());
        assertEquals(2, pool.idleCount());
    }

    @Test
    void drainAllClearsPool() {
        BufferPool pool = new BufferPool(64, 10);
        ByteBuffer b1 = pool.acquire();
        ByteBuffer b2 = pool.acquire();
        pool.release(b1);
        pool.release(b2);
        assertEquals(2, pool.idleCount());

        pool.drainAll();
        assertEquals(0, pool.idleCount());
        // allocated count remains unchanged
        assertEquals(2, pool.allocatedCount());
        // new acquire creates a fresh buffer (since pool is drained)
        ByteBuffer buf = pool.acquire();
        assertNotNull(buf);
        assertEquals(3, pool.allocatedCount());
    }

    @Test
    void releaseWithMaxPoolSizeZeroDoesNotReturnToPool() {
        BufferPool pool = new BufferPool(64, 0);
        ByteBuffer buf = pool.acquire();
        assertEquals(1, pool.allocatedCount());
        assertEquals(0, pool.idleCount());

        pool.release(buf);
        assertEquals(0, pool.idleCount(), "buffer should not be returned when maxPoolSize=0");
    }

    @Test
    void releaseNullThrowsNPE() {
        BufferPool pool = new BufferPool();
        assertThrows(NullPointerException.class, () -> pool.release(null));
    }
}
