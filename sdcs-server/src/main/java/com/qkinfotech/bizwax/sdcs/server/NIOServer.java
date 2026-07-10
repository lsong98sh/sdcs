package com.qkinfotech.bizwax.sdcs.server;

import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.transaction.TransactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * NIOServer — orchestrates three dedicated physical threads:
 * <ol>
 *     <li><b>NIOAcceptor</b> — only calls {@code ServerSocketChannel.accept()}</li>
 *     <li><b>IOEventLoop</b> — selector-based socket reads and writes</li>
 *     <li><b>WorkProcessor</b> — RESP decoding, command execution, response encoding</li>
 * </ol>
 * <p>
 * The three threads communicate via lock-free {@link ConcurrentLinkedQueue} queues.
 * </p>
 */
public class NIOServer {

    private static final Logger logger = LoggerFactory.getLogger(NIOServer.class);

    private final SDCSConfig config;

    // === Server channel and selector ===
    private ServerSocketChannel serverSocketChannel;
    private Selector ioSelector;

    // === Three threads ===
    private NIOAcceptor acceptor;
    private IOEventLoop ioLoop;
    private WorkProcessor worker;

    // === Inter-thread queues ===
    private final ConcurrentLinkedQueue<SocketChannel> newConnectionQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SDCSNode> readQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SDCSNode> writeQueue = new ConcurrentLinkedQueue<>();

    // === Connection management ===
    private ObjectPool<SDCSNode> nodePool;
    private SDCSNode head;
    private SDCSNode tail;
    private volatile int clientCount;

    private static volatile NIOServer instance;

    public NIOServer(SDCSConfig config) {
        this.config = config;
    }

    // ==================== Lifecycle ====================

    public void start() throws IOException {
        if (isRunning()) {
            return;
        }

        // Initialize server channel with SO_REUSEADDR for fast restart
        this.serverSocketChannel = ServerSocketChannel.open();
        this.serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        this.serverSocketChannel.configureBlocking(true); // Acceptor uses blocking mode
        this.serverSocketChannel.bind(
                new InetSocketAddress(config.getBindAddress(), config.getPort()),
                config.getAcceptCount());

        // Initialize IO selector
        this.ioSelector = Selector.open();

        // Initialize node pool
        int poolCapacity = config.getMaxConnections();
        this.nodePool = new ObjectPool<>(64, poolCapacity, pool -> new SDCSNode(config));

        // Create worker
        this.worker = new WorkProcessor(readQueue, writeQueue, ioSelector);

        // Create IO event loop
        this.ioLoop = new IOEventLoop(ioSelector, newConnectionQueue, readQueue, writeQueue, worker, config);

        // Create acceptor
        this.acceptor = new NIOAcceptor(serverSocketChannel, ioSelector, newConnectionQueue);

        // Start all three threads
        worker.start();
        ioLoop.start();
        acceptor.start();

        // Publish instance only after everything is fully initialized
        instance = this;

        logger.info("SDCS NIOServer started on {}:{}, 3-thread mode",
                config.getBindAddress(), config.getPort());
    }

    public void stop() {
        logger.info("Stopping SDCS NIOServer");

        // Shutdown threads in order: acceptor → io → worker
        if (acceptor != null) {
            acceptor.shutdown();
        }
        if (ioLoop != null) {
            ioLoop.shutdown();
        }
        if (worker != null) {
            worker.shutdown();
        }

        // Wait for threads to fully terminate before closing resources
        try {
            if (acceptor != null) acceptor.join(2000);
            if (ioLoop != null) ioLoop.join(2000);
            if (worker != null) worker.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Close all client connections
        releaseAll();

        // Close server socket
        if (serverSocketChannel != null) {
            try {
                serverSocketChannel.close();
            } catch (IOException e) {
                logger.warn("Error closing server socket: {}", e.getMessage());
            }
        }

        // Close IO selector
        if (ioSelector != null && ioSelector.isOpen()) {
            try {
                ioSelector.close();
            } catch (IOException e) {
                logger.warn("Error closing IO selector: {}", e.getMessage());
            }
        }

        logger.info("SDCS NIOServer stopped");

        // Clear instance so a new server can bind the same port
        instance = null;
    }

    // ==================== Node pool ====================

    public SDCSNode acquireNode() {
        return nodePool.acquire();
    }

    private void releaseNode(SDCSNode node) {
        node.close();
        nodePool.release(node);
    }

    // ==================== Connection list ====================

    public synchronized void addNode(SDCSNode node) {
        node.prev = tail;
        node.next = null;

        if (tail != null) {
            tail.next = node;
        }
        tail = node;

        if (head == null) {
            head = node;
        }
        clientCount++;
    }

    public synchronized void removeNode(SDCSNode node) {
        if (node.prev == null && node.next == null && head != node) {
            return; // Already removed
        }

        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }

        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }

        node.prev = null;
        node.next = null;
        clientCount--;

        try {
            if (node.getSocketChannel() != null && node.getSocketChannel().isOpen()) {
                node.getSocketChannel().close();
            }
        } catch (IOException e) {
            logger.warn("Error closing channel in removeNode: {}", e.getMessage());
        }

        TransactionManager.cleanup();
        releaseNode(node);
    }

    // ==================== Shared close logic ====================

    /**
     * Shared close logic used by both {@link IOEventLoop} and {@link WorkProcessor}.
     * Cancels the selection key, closes the channel, and removes the node.
     */
    public static void closeNode(SDCSNode node) {
        node.setInReadQueue(false);
        node.setInWriteQueue(false);
        SelectionKey key = node.getSelectionKey();
        if (key != null) {
            key.cancel();
        }
        try {
            if (node.getSocketChannel() != null && node.getSocketChannel().isOpen()) {
                node.getSocketChannel().close();
            }
        } catch (IOException e) {
            logger.warn("Error closing channel: {}", e.getMessage());
        }
        NIOServer server = getInstance();
        if (server != null) {
            server.removeNode(node);
        }
    }

    // ==================== Shutdown helpers ====================

    private synchronized void releaseAll() {
        SDCSNode node = head;
        while (node != null) {
            SDCSNode next = node.next;
            try {
                SelectionKey key = node.getSelectionKey();
                if (key != null && key.isValid()) {
                    key.cancel();
                }
                if (node.getSocketChannel() != null && node.getSocketChannel().isOpen()) {
                    node.getSocketChannel().close();
                }
            } catch (IOException e) {
                logger.warn("Error closing client channel during release: {}", e.getMessage());
            }
            node.close();
            node = next;
        }
        head = null;
        tail = null;
        clientCount = 0;
    }

    // ==================== Static accessor ====================

    public static NIOServer getInstance() {
        return instance;
    }

    // ==================== Convenience ====================

    public boolean isRunning() {
        return acceptor != null && acceptor.isAlive()
                && ioLoop != null && ioLoop.isAlive()
                && worker != null && worker.isAlive();
    }

    public int getClientCount() {
        return clientCount;
    }

    /**
     * Shutdown with optional RDB save (called from SDCSServer or shutdown hook).
     */
    public void shutdown(boolean save) {
        if (save) {
            try {
                SDCSCommandExecutor.getPersistenceManager().saveRdb();
            } catch (Exception e) {
                logger.error("Error saving RDB on shutdown: {}", e.getMessage());
            }
        }
        stop();
    }
}
