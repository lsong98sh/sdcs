package com.qkinfotech.bizwax.sdcs.server;

import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.metrics.MetricsCollector;
import com.qkinfotech.bizwax.sdcs.protocol.RespEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * IO event loop thread — handles all socket reads and writes via a single Selector.
 * <p>
 * Reads from the pending accept queue (submitted by {@link NIOAcceptor}) to register
 * new connections. Reads data into each node's read buffer and hands the node to the
 * {@link WorkProcessor} via the read queue. Writes pending responses from the write queue
 * back to the sockets.
 * </p>
 */
public class IOEventLoop extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(IOEventLoop.class);

    private final Selector selector;
    private final ConcurrentLinkedQueue<SocketChannel> newConnectionQueue;
    private final ConcurrentLinkedQueue<SDCSNode> readQueue;
    private final ConcurrentLinkedQueue<SDCSNode> writeQueue;
    private final WorkProcessor worker;
    private final SDCSConfig config;
    private final int clientTimeoutMs;
    private final int selectorTimeoutMs = 500;

    private volatile boolean running;

    public IOEventLoop(Selector selector,
                       ConcurrentLinkedQueue<SocketChannel> newConnectionQueue,
                       ConcurrentLinkedQueue<SDCSNode> readQueue,
                       ConcurrentLinkedQueue<SDCSNode> writeQueue,
                       WorkProcessor worker,
                       SDCSConfig config) {
        super("SDCS-IO");
        this.selector = selector;
        this.newConnectionQueue = newConnectionQueue;
        this.readQueue = readQueue;
        this.writeQueue = writeQueue;
        this.worker = worker;
        this.config = config;
        this.clientTimeoutMs = config.getConnectionTimeout() * 1000;
    }

    @Override
    public void run() {
        running = true;
        logger.info("IOEventLoop started");
        while (running) {
            try {
                // Process pending accepts before selecting
                drainPendingAccepts();

                int readyCount;
                try {
                    readyCount = selector.select(selectorTimeoutMs);
                } catch (IOException e) {
                    logger.error("Selector select error: {}", e.getMessage(), e);
                    continue;
                }

                if (readyCount > 0) {
                    Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();

                        if (!key.isValid()) {
                            continue;
                        }

                        try {
                            if (key.isReadable()) {
                                handleRead(key);
                            } else if (key.isWritable()) {
                                handleWrite(key);
                            }
                        } catch (Exception e) {
                            logger.error("Error handling key: {}", e.getMessage(), e);
                            SDCSNode node = (SDCSNode) key.attachment();
                            if (node != null) {
                                closeNode(node);
                            }
                        }
                    }
                }

                // Try to flush pending writes
                drainWriteQueue();

                // Check timeouts
                checkTimeouts();

            } catch (Exception e) {
                logger.error("IOEventLoop error: {}", e.getMessage(), e);
            }
        }
        logger.info("IOEventLoop stopped");
    }

    /**
     * Register new connections from the acceptor thread.
     */
    private void drainPendingAccepts() {
        SocketChannel ch;
        while ((ch = newConnectionQueue.poll()) != null) {
            try {
                NIOServer server = NIOServer.getInstance();
                if (server == null) {
                    logger.warn("NIOServer not available, rejecting connection");
                    try { ch.close(); } catch (IOException ignored) {}
                    continue;
                }

                SDCSNode node = server.acquireNode();
                if (node == null) {
                    logger.warn("Node pool is full, rejecting connection");
                    try { ch.close(); } catch (IOException ignored) {}
                    continue;
                }

                SelectionKey clientKey = ch.register(selector, SelectionKey.OP_READ);
                node.initialize(ch, clientKey);
                clientKey.attach(node);
                server.addNode(node);
                MetricsCollector.getInstance().recordConnectionOpened();
                logger.debug("Registered new client: {}", ch.getRemoteAddress());
            } catch (Exception e) {
                logger.error("Error registering new connection: {}", e.getMessage(), e);
                try { ch.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Read data from a socket into the node's read buffer and hand to worker.
     */
    private void handleRead(SelectionKey key) throws IOException {
        SDCSNode node = (SDCSNode) key.attachment();
        if (node == null) return;

        // If node is being processed by worker, skip read (backpressure)
        if (node.isInReadQueue()) {
            return;
        }

        SocketChannel ch = node.getSocketChannel();
        ByteBuffer readBuf = node.getReadBuf();

        // Ensure read buffer has space
        if (readBuf.remaining() < 1024) {
            readBuf = node.expandReadBuf();
        }

        int bytesRead = ch.read(readBuf);
        if (bytesRead == -1) {
            logger.debug("Client disconnected: {}", ch.getRemoteAddress());
            MetricsCollector.getInstance().recordConnectionClosed();
            closeNode(node);
            return;
        }
        if (bytesRead == 0) {
            return;
        }

        logger.info("handleRead: read {} bytes from {}, buf pos={} lim={} cap={}",
                bytesRead, ch.getRemoteAddress(), readBuf.position(), readBuf.limit(), readBuf.capacity());

        MetricsCollector.getInstance().recordBytesRead(bytesRead);
        node.updateLastReadTime();

        // Hand off to worker
        node.setInReadQueue(true);
        readQueue.offer(node);
        LockSupport.unpark(worker);
    }

    /**
     * Write pending response data to a socket.
     */
    private void handleWrite(SelectionKey key) throws IOException {
        SDCSNode node = (SDCSNode) key.attachment();
        if (node == null) return;

        ByteBuffer writeBuf = node.getWriteBuf();
        if (writeBuf == null || !writeBuf.hasRemaining()) {
            // Nothing to write — unregister OP_WRITE
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            node.clearWriteBuf();
            node.setInWriteQueue(false);
            return;
        }

        SocketChannel ch = node.getSocketChannel();
        int written = ch.write(writeBuf);
        MetricsCollector.getInstance().recordBytesWritten(written);

        if (!writeBuf.hasRemaining()) {
            // All data written — unregister OP_WRITE
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            node.clearWriteBuf();
            node.setInWriteQueue(false);
            logger.debug("Write complete for {}", ch.getRemoteAddress());
        }
    }

    /**
     * Flush pending writes from the write queue.
     */
    private void drainWriteQueue() {
        SDCSNode node;
        while ((node = writeQueue.poll()) != null) {
            logger.info("drainWriteQueue: node={} hasData={}", node.getSocketAddress(), node.getWriteBuf().hasRemaining());
            if (node.getSelectionKey() == null || !node.getSelectionKey().isValid()) {
                node.clearWriteBuf();
                node.setInWriteQueue(false);
                continue;
            }

            ByteBuffer writeBuf = node.getWriteBuf();
            if (writeBuf == null || !writeBuf.hasRemaining()) {
                node.clearWriteBuf();
                node.setInWriteQueue(false);
                continue;
            }

            // Try direct write first
            SocketChannel ch = node.getSocketChannel();
            try {
                int written = ch.write(writeBuf);
                MetricsCollector.getInstance().recordBytesWritten(written);

                if (writeBuf.hasRemaining()) {
                    // Need to register for OP_WRITE
                    SelectionKey key = node.getSelectionKey();
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                } else {
                    node.clearWriteBuf();
                    node.setInWriteQueue(false);
                }
            } catch (IOException e) {
                logger.error("Error writing to {}: {}", node.getSocketAddress(), e.getMessage());
                closeNode(node);
            }
        }
    }

    /**
     * Check for idle connections and close them.
     */
    private void checkTimeouts() {
        if (clientTimeoutMs <= 0) return;

        long now = System.currentTimeMillis();
        // Iterate all registered keys
        for (SelectionKey key : selector.keys()) {
            if (!key.isValid()) continue;
            SDCSNode node = (SDCSNode) key.attachment();
            if (node == null) continue;

            if ((now - node.getLastReadTime()) > clientTimeoutMs) {
                logger.debug("Timeout client: {}", node.getSocketAddress());
                closeNode(node);
            }
        }
    }

    /**
     * Close a node — delegates to {@link NIOServer#closeNode(SDCSNode)}.
     */
    void closeNode(SDCSNode node) {
        NIOServer.closeNode(node);
    }

    public void shutdown() {
        running = false;
        selector.wakeup();
        interrupt();
    }
}
