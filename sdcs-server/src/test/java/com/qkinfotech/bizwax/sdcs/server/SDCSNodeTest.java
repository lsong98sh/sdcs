package com.qkinfotech.bizwax.sdcs.server;

import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class SDCSNodeTest {

    private SDCSConfig config;
    private SDCSNode node;

    @BeforeEach
    void setUp() {
        config = new SDCSConfig();
        config.setPersistenceType(SDCSConfig.PersistenceType.NONE);
        node = new SDCSNode(config);
    }

    // ==================== 构造 & 默认状态 ====================

    @Test
    void constructionDefaultState() {
        assertFalse(node.isAuthenticated(), "new node should not be authenticated");
        assertFalse(node.isInReadQueue(), "new node should not be in read queue");
        assertFalse(node.isInWriteQueue(), "new node should not be in write queue");
        assertNull(node.getSocketChannel(), "socketChannel should be null initially");
        assertNull(node.getSelectionKey(), "selectionKey should be null initially");
        assertSame(config, node.getConfig(), "getConfig should return the config passed to constructor");
    }

    // ==================== initialize ====================

    @Test
    void initializeAcceptsNullAndResetsState() {
        node.setAuthenticated(true);
        node.setInReadQueue(true);
        node.setInWriteQueue(true);
        node.enqueueResponse(RedisMessage.simpleString("test"));

        node.initialize(null, null);

        assertNull(node.getSocketChannel());
        assertNull(node.getSelectionKey());
        assertFalse(node.isAuthenticated());
        assertFalse(node.isInReadQueue());
        assertFalse(node.isInWriteQueue());
        assertEquals(0, node.getReadBuf().position(), "readBuf should be cleared");
        assertEquals(0, node.getWriteBuf().position(), "writeBuf should be cleared");
    }

    // ==================== 读缓冲 ====================

    @Test
    void getReadBufReturnsNonNullBuffer() {
        ByteBuffer buf = node.getReadBuf();
        assertNotNull(buf);
        assertEquals(8192, buf.capacity());
    }

    @Test
    void expandReadBufDoublesCapacity() {
        ByteBuffer original = node.getReadBuf();
        int originalCapacity = original.capacity();

        ByteBuffer expanded = node.expandReadBuf();
        assertEquals(originalCapacity * 2, expanded.capacity());
        assertSame(expanded, node.getReadBuf(), "expandReadBuf should update the node's readBuf reference");
    }

    @Test
    void expandReadBufPreservesExistingData() {
        ByteBuffer buf = node.getReadBuf();
        buf.put("hello".getBytes());
        int pos = buf.position();

        ByteBuffer expanded = node.expandReadBuf();
        assertEquals(8192 * 2, expanded.capacity());
        // After expand, existing data should be preserved (flip + put)
        // The original position bytes should be readable after expansion
        assertTrue(expanded.position() >= pos, "expanded buffer should contain original data");
    }

    // ==================== 写缓冲 ====================

    @Test
    void getWriteBufReturnsNonNullBuffer() {
        ByteBuffer buf = node.getWriteBuf();
        assertNotNull(buf);
        assertEquals(8192, buf.capacity());
    }

    @Test
    void enqueueResponseAndFlipWriteBuf() {
        RedisMessage response = RedisMessage.simpleString("OK");
        node.enqueueResponse(response);
        assertTrue(node.getWriteBuf().position() > 0, "writeBuf position should advance after enqueueResponse");

        node.flipWriteBuf();
        assertEquals(0, node.getWriteBuf().position(), "after flip, position should be 0");
        assertTrue(node.getWriteBuf().remaining() > 0, "after flip, remaining should reflect encoded data");
    }

    @Test
    void hasPendingWritesAfterEnqueue() {
        // Fresh buffer may report hasPendingWrites=true due to position=0, remaining>0
        // Focus on verifying behavior after actual data is written
        node.enqueueResponse(RedisMessage.simpleString("OK"));
        assertTrue(node.hasPendingWrites(), "should have pending writes after enqueueing response");
    }

    @Test
    void hasPendingWritesAfterFlip() {
        node.enqueueResponse(RedisMessage.simpleString("OK"));
        node.flipWriteBuf();
        assertTrue(node.hasPendingWrites(), "should have pending writes after flip when data is present");
    }

    @Test
    void clearWriteBufResetsBufferState() {
        node.enqueueResponse(RedisMessage.simpleString("data"));
        int posBefore = node.getWriteBuf().position();
        assertTrue(posBefore > 0, "writeBuf position should advance after enqueueResponse");

        node.clearWriteBuf();
        assertEquals(0, node.getWriteBuf().position(), "writeBuf position should be 0 after clear");
        assertEquals(node.getWriteBuf().capacity(), node.getWriteBuf().limit(),
                "writeBuf limit should equal capacity after clear");
    }

    @Test
    void enqueueResponseAutoExpandsBuffer() {
        // Enqueue a large response that exceeds the default buffer size
        byte[] largeData = new byte[8192];
        java.util.Arrays.fill(largeData, (byte) 'X');
        RedisMessage largeMsg = RedisMessage.bulkString(largeData);
        node.enqueueResponse(largeMsg);
        // The buffer should have been automatically expanded
        assertTrue(node.getWriteBuf().capacity() > 8192,
                "writeBuf should be auto-expanded for large responses");
    }

    // ==================== 编解码器 ====================

    @Test
    void getDecoderReturnsNonNull() {
        assertNotNull(node.getDecoder());
    }

    @Test
    void resetDecoderDoesNotThrow() {
        node.resetDecoder();
        assertNotNull(node.getDecoder());
        // Reset again to verify idempotency
        node.resetDecoder();
        assertNotNull(node.getDecoder());
    }

    // ==================== 认证状态 ====================

    @Test
    void authenticatedStateTransitions() {
        assertFalse(node.isAuthenticated(), "initially not authenticated");
        node.setAuthenticated(true);
        assertTrue(node.isAuthenticated());
        node.setAuthenticated(false);
        assertFalse(node.isAuthenticated());
    }

    // ==================== 最后读取时间 ====================

    @Test
    void updateLastReadTimeAdvancesTime() throws InterruptedException {
        long before = node.getLastReadTime();
        Thread.sleep(2);
        node.updateLastReadTime();
        long after = node.getLastReadTime();
        assertTrue(after >= before, "lastReadTime should not decrease");
        assertTrue(after > before, "lastReadTime should advance after update");
    }

    @Test
    void getLastReadTimeInitialValue() {
        assertTrue(node.getLastReadTime() > 0, "lastReadTime should be initialized to a positive value");
    }

    // ==================== 队列标志 ====================

    @Test
    void inReadQueueFlag() {
        assertFalse(node.isInReadQueue());
        node.setInReadQueue(true);
        assertTrue(node.isInReadQueue());
        node.setInReadQueue(false);
        assertFalse(node.isInReadQueue());
    }

    @Test
    void inWriteQueueFlag() {
        assertFalse(node.isInWriteQueue());
        node.setInWriteQueue(true);
        assertTrue(node.isInWriteQueue());
        node.setInWriteQueue(false);
        assertFalse(node.isInWriteQueue());
    }

    // ==================== close ====================

    @Test
    void closeResetsState() {
        node.setAuthenticated(true);
        node.setInReadQueue(true);
        node.setInWriteQueue(true);
        node.enqueueResponse(RedisMessage.simpleString("data"));

        node.close();

        assertFalse(node.isInReadQueue());
        assertFalse(node.isInWriteQueue());
        assertEquals(0, node.getReadBuf().position(), "readBuf should be cleared after close");
        assertEquals(0, node.getWriteBuf().position(), "writeBuf should be cleared after close");
    }

    // ==================== getSocketAddress ====================

    @Test
    void getSocketAddressReturnsNullWhenNotInitialized() {
        assertNull(node.getSocketAddress());
    }
}
