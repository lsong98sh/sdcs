package com.qkinfotech.bizwax.sdcs.server;

import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.protocol.RespDecoder;
import com.qkinfotech.bizwax.sdcs.protocol.RespEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * Represents a single client connection managed across three threads:
 * <ul>
 *     <li><b>IO thread</b> — reads raw bytes into {@link #readBuf}, writes {@link #writeBuf} to socket</li>
 *     <li><b>Worker thread</b> — decodes from {@link #readBuf}, executes commands, encodes into {@link #writeBuf}</li>
 *     <li><b>Acceptor thread</b> — creates this node and hands the socket to the IO thread</li>
 * </ul>
 */
public class SDCSNode {

    private static final Logger logger = LoggerFactory.getLogger(SDCSNode.class);

    private final SDCSConfig config;
    private volatile ByteBuffer readBuf;
    private volatile ByteBuffer writeBuf;
    private final RespDecoder decoder;
    private final RespEncoder encoder;

    private SocketChannel socketChannel;
    private SelectionKey selectionKey;
    private volatile long lastReadTime;
    private volatile boolean authenticated;

    /** True while this node is in the worker's read queue (being processed). */
    private volatile boolean inReadQueue;
    /** True while this node is in the IO thread's write queue. */
    private volatile boolean inWriteQueue;

    // Linked list pointers for connection management (used by NIOServer)
    SDCSNode prev;
    SDCSNode next;

    public SDCSNode(SDCSConfig config) {
        this.config = config;
        this.readBuf = ByteBuffer.allocate(8192);
        this.writeBuf = ByteBuffer.allocate(8192);
        this.decoder = new RespDecoder();
        this.encoder = new RespEncoder(8192);
        this.lastReadTime = System.currentTimeMillis();
    }

    // ==================== Lifecycle ====================

    public void initialize(SocketChannel channel, SelectionKey key) {
        this.socketChannel = channel;
        this.selectionKey = key;
        this.lastReadTime = System.currentTimeMillis();
        this.authenticated = false;
        this.inReadQueue = false;
        this.inWriteQueue = false;
        readBuf.clear();
        writeBuf.clear();
        decoder.reset();
    }

    public void close() {
        decoder.reset();
        readBuf.clear();
        writeBuf.clear();
        inReadQueue = false;
        inWriteQueue = false;
    }

    // ==================== Read buffer ====================

    public ByteBuffer getReadBuf() {
        return readBuf;
    }

    /**
     * Expand the read buffer when it's nearly full (remaining &lt; 1024).
     * New capacity = current capacity * 2.
     */
    public ByteBuffer expandReadBuf() {
        ByteBuffer old = readBuf;
        ByteBuffer expanded = ByteBuffer.allocate(old.capacity() * 2);
        old.flip();
        expanded.put(old);
        readBuf = expanded;
        return expanded;
    }

    // ==================== Write buffer ====================

    public ByteBuffer getWriteBuf() {
        return writeBuf;
    }

    /**
     * Encode a response message into the write buffer.
     * The buffer stays in write mode (position advances) — call {@link #flipWriteBuf()}
     * before handing to the IO thread.
     */
    public void enqueueResponse(RedisMessage response) {
        // If buffer is in read mode (flipped), something went wrong — clear it
        if (writeBuf.position() > 0 && writeBuf.position() == writeBuf.limit()) {
            writeBuf.clear();
        }
        while (true) {
            try {
                // Encode into the write buffer at the current position
                encoder.encode(response, writeBuf);
                break;
            } catch (BufferOverflowException e) {
                // Double the write buffer capacity
                ByteBuffer expanded = ByteBuffer.allocate(writeBuf.capacity() * 2);
                writeBuf.flip();
                expanded.put(writeBuf);
                writeBuf = expanded;
            }
        }
    }

    /**
     * Flip the write buffer into read mode so the IO thread can write it to the socket.
     */
    public void flipWriteBuf() {
        writeBuf.flip();
    }

    /**
     * Clear the write buffer after the IO thread has fully written its contents.
     */
    public void clearWriteBuf() {
        writeBuf.clear();
    }

    /**
     * Returns true if the write buffer has pending data to send.
     * Checks position because the buffer may be in write mode (encoding in progress).
     */
    public boolean hasPendingWrites() {
        if (writeBuf == null) return false;
        // In read mode (flipped): remaining() > 0 means data to write
        if (writeBuf.position() == 0 && writeBuf.limit() > 0) {
            return writeBuf.remaining() > 0;
        }
        // In write mode: position > 0 means data was written
        return writeBuf.position() > 0;
    }

    // ==================== Decoder ====================

    public RespDecoder getDecoder() {
        return decoder;
    }

    public void resetDecoder() {
        decoder.reset();
    }

    // ==================== Queue flags ====================

    public boolean isInReadQueue() {
        return inReadQueue;
    }

    public void setInReadQueue(boolean inReadQueue) {
        this.inReadQueue = inReadQueue;
    }

    public boolean isInWriteQueue() {
        return inWriteQueue;
    }

    public void setInWriteQueue(boolean inWriteQueue) {
        this.inWriteQueue = inWriteQueue;
    }

    // ==================== Accessors ====================

    public SDCSConfig getConfig() {
        return config;
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public SelectionKey getSelectionKey() {
        return selectionKey;
    }

    public long getLastReadTime() {
        return lastReadTime;
    }

    public void updateLastReadTime() {
        this.lastReadTime = System.currentTimeMillis();
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public SocketAddress getSocketAddress() {
        try {
            return socketChannel != null ? socketChannel.getRemoteAddress() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
