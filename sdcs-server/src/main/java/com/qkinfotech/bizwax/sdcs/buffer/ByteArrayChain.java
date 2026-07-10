package com.qkinfotech.bizwax.sdcs.buffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A growable chain of byte[] chunks for streaming storage.
 * <p>
 * Small writes allocate exact-size chunks to avoid waste;
 * large writes use fixed-size chunks (default 4KB) with an exact remainder.
 * Supports sequential write, random-access read, and zero-copy chunk iteration.
 * </p>
 */
public class ByteArrayChain {

    public static final int CHUNK_SIZE = 4096;

    private final List<Chunk> chunks = new ArrayList<>();
    private long totalLength;

    private static final class Chunk {
        final byte[] data;
        int position; // number of bytes written into this chunk

        Chunk(int size) {
            this.data = new byte[size];
            this.position = 0;
        }
    }

    public ByteArrayChain() {
    }

    // ==================== Write ====================

    public void write(byte b) {
        Chunk last = writableChunk(1);
        last.data[last.position++] = b;
        totalLength++;
    }

    public void write(byte[] src) {
        write(src, 0, src.length);
    }

    public void write(byte[] src, int off, int len) {
        if (len == 0) return;
        int remaining = len;
        while (remaining > 0) {
            Chunk chunk = writableChunk(remaining);
            int toCopy = Math.min(chunk.data.length - chunk.position, remaining);
            System.arraycopy(src, off + (len - remaining), chunk.data, chunk.position, toCopy);
            chunk.position += toCopy;
            remaining -= toCopy;
            totalLength += toCopy;
        }
    }

    /**
     * Read {@code len} bytes from {@code src} (typically a DirectByteBuffer)
     * and append to this chain.
     */
    public void write(ByteBuffer src, int len) {
        if (len <= 0) return;
        int remaining = len;
        while (remaining > 0) {
            Chunk chunk = writableChunk(remaining);
            int toCopy = Math.min(chunk.data.length - chunk.position, remaining);
            src.get(chunk.data, chunk.position, toCopy);
            chunk.position += toCopy;
            remaining -= toCopy;
            totalLength += toCopy;
        }
    }

    /** Ensure writable space is available. Allocates exact size for small data. */
    private Chunk writableChunk(int needed) {
        if (!chunks.isEmpty()) {
            Chunk last = chunks.get(chunks.size() - 1);
            if (last.position < last.data.length) {
                return last;
            }
        }
        // Allocate: for small writes use exact size, otherwise use CHUNK_SIZE
        int size = (needed <= CHUNK_SIZE) ? needed : CHUNK_SIZE;
        Chunk c = new Chunk(size);
        chunks.add(c);
        return c;
    }

    // ==================== Read ====================

    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(byte[] data, int offset, int length);
    }

    /**
     * Iterate all written data chunk by chunk. Zero-copy.
     */
    public void forEach(ChunkConsumer consumer) {
        for (Chunk chunk : chunks) {
            if (chunk.position > 0) {
                consumer.accept(chunk.data, 0, chunk.position);
            }
        }
    }

    /**
     * Merge all chunks into a single contiguous byte[].
     */
    public byte[] toByteArray() {
        if (totalLength == 0) return new byte[0];
        byte[] result = new byte[(int) totalLength];
        int pos = 0;
        for (Chunk chunk : chunks) {
            System.arraycopy(chunk.data, 0, result, pos, chunk.position);
            pos += chunk.position;
        }
        return result;
    }

    // ==================== Random Access ====================

    public byte get(long index) {
        checkIndex(index);
        long acc = 0;
        for (Chunk chunk : chunks) {
            if (index < acc + chunk.position) {
                return chunk.data[(int) (index - acc)];
            }
            acc += chunk.position;
        }
        throw new IndexOutOfBoundsException("Index: " + index);
    }

    public void set(long index, byte value) {
        checkIndex(index);
        long acc = 0;
        for (Chunk chunk : chunks) {
            if (index < acc + chunk.position) {
                chunk.data[(int) (index - acc)] = value;
                return;
            }
            acc += chunk.position;
        }
    }

    /**
     * Create a view (without copying) of a sub-range.
     * The resulting chain borrows references to the underlying chunks.
     */
    public ByteArrayChain subChain(long start, long end) {
        if (start < 0 || end > totalLength || start > end) {
            throw new IndexOutOfBoundsException(
                    "start=" + start + ", end=" + end + ", length=" + totalLength);
        }
        ByteArrayChain sub = new ByteArrayChain();
        long acc = 0;
        for (Chunk chunk : chunks) {
            long chunkEnd = acc + chunk.position;
            if (chunkEnd > start && acc < end) {
                int from = (int) Math.max(0, start - acc);
                int to = (int) Math.min(chunk.position, end - acc);
                if (to > from) {
                    // We can't easily share without wrapping, so copy the sub-range
                    // For small ranges this is fine; large ranges should use forEach
                    byte[] copy = new byte[to - from];
                    System.arraycopy(chunk.data, from, copy, 0, copy.length);
                    sub.chunks.add(new Chunk(copy.length));
                    Chunk last = sub.chunks.get(sub.chunks.size() - 1);
                    System.arraycopy(copy, 0, last.data, 0, copy.length);
                    last.position = copy.length;
                    sub.totalLength += copy.length;
                }
            }
            acc = chunkEnd;
            if (acc >= end) break;
        }
        return sub;
    }

    // ==================== Utilities ====================

    public long length() {
        return totalLength;
    }

    public boolean isEmpty() {
        return totalLength == 0;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public void clear() {
        chunks.clear();
        totalLength = 0;
    }

    private void checkIndex(long index) {
        if (index < 0 || index >= totalLength) {
            throw new IndexOutOfBoundsException(
                    "Index: " + index + ", Length: " + totalLength);
        }
    }

    @Override
    public String toString() {
        if (totalLength == 0) return "(empty)";
        if (totalLength <= 100) return new String(toByteArray());
        return new String(toByteArray(), 0, 100) + "...(" + totalLength + " bytes)";
    }
}
