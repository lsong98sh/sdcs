package com.qkinfotech.bizwax.sdcs.server;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.protocol.RespDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Worker thread — decodes RESP commands from accumulated node data,
 * executes them, and encodes responses into the node's write buffer.
 * <p>
 * Consumes nodes from the read queue (submitted by IO thread),
 * then submits them to the write queue for IO thread to flush.
 * </p>
 */
public class WorkProcessor extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(WorkProcessor.class);

    private final ConcurrentLinkedQueue<SDCSNode> readQueue;
    private final ConcurrentLinkedQueue<SDCSNode> writeQueue;
    private final java.nio.channels.Selector ioSelector;

    private volatile boolean running;

    public WorkProcessor(ConcurrentLinkedQueue<SDCSNode> readQueue,
                         ConcurrentLinkedQueue<SDCSNode> writeQueue,
                         java.nio.channels.Selector ioSelector) {
        super("SDCS-Worker");
        this.readQueue = readQueue;
        this.writeQueue = writeQueue;
        this.ioSelector = ioSelector;
    }

    @Override
    public void run() {
        running = true;
        logger.info("WorkProcessor started");
        while (running) {
            SDCSNode node = readQueue.poll();
            if (node == null) {
                LockSupport.park();
                continue;
            }

            try {
                processNode(node);
            } catch (Exception e) {
                logger.error("Error processing node {}: {}",
                        node.getSocketAddress(), e.getMessage(), e);
                NIOServer.closeNode(node);
            }
        }
        logger.info("WorkProcessor stopped");
    }

    private void processNode(SDCSNode node) {
        RespDecoder decoder = node.getDecoder();
        ByteBuffer readBuf = node.getReadBuf();
        readBuf.flip();
        logger.info("processNode: buf pos={} lim={} cap={}", readBuf.position(), readBuf.limit(), readBuf.capacity());

        try {
            while (readBuf.hasRemaining()) {
                RedisMessage msg = decoder.decode(readBuf);
                if (msg == null) {
                    // Need more data — break and keep remaining in readBuf
                    break;
                }
                processCommand(node, msg);
            }
        } catch (RespDecoder.ProtocolException e) {
            logger.warn("Protocol error from {}: {}",
                    node.getSocketAddress(), e.getMessage());
            node.resetDecoder();
            node.enqueueResponse(RedisMessage.error("ERR " + e.getMessage()));
        } finally {
            readBuf.compact();
            // Mark node as ready for more reads
            node.setInReadQueue(false);
        }

        // Flip write buffer for IO thread to send, then notify
        node.flipWriteBuf();
        if (node.hasPendingWrites()) {
            addToWriteQueue(node);
        }

        // Wake IO thread so it can re-enable OP_READ for this node
        ioSelector.wakeup();
    }

    private void processCommand(SDCSNode node, RedisMessage msg) {
        if (msg.getType() != RedisMessage.Type.ARRAY) {
            logger.warn("Received non-array message: {}", msg);
            node.enqueueResponse(RedisMessage.error("protocol error: expected array"));
            return;
        }

        var elements = msg.getElements();
        if (elements == null || elements.isEmpty()) {
            return;
        }

        var cmdNameMsg = elements.get(0);
        if (cmdNameMsg.getType() != RedisMessage.Type.BULK_STRING) {
            node.enqueueResponse(RedisMessage.error("protocol error: command name must be string"));
            return;
        }

        String commandName = cmdNameMsg.asString().toUpperCase();
        var args = elements.subList(1, elements.size());

        // Auth check
        if (!node.isAuthenticated()) {
            String requirepass = node.getConfig().getRequirepass();
            if (requirepass != null && !requirepass.isEmpty()) {
                if ("AUTH".equals(commandName)) {
                    handleAuth(node, args, requirepass);
                    return;
                } else if (!"QUIT".equals(commandName) && !"PING".equals(commandName)) {
                    node.enqueueResponse(RedisMessage.error("NOAUTH Authentication required."));
                    return;
                }
            }
        }

        Consumer<RedisMessage> deferredCallback = response -> {
            if (response != null) {
                node.enqueueResponse(response);
                node.flipWriteBuf();
                // Must also add node to write queue and register OP_WRITE,
                // otherwise the IO thread won't flush this response.
                if (!node.isInWriteQueue()) {
                    node.setInWriteQueue(true);
                    writeQueue.offer(node);
                    SelectionKey sk = node.getSelectionKey();
                    if (sk != null && sk.isValid()) {
                        sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
                    }
                }
                ioSelector.wakeup();
            }
        };

        RedisMessage response = SDCSCommandExecutor.execute(commandName, args, deferredCallback);

        if (response != null) {
            node.enqueueResponse(response);
        }
    }

    private void handleAuth(SDCSNode node, java.util.List<RedisMessage> args, String requirepass) {
        if (args.size() < 1) {
            node.enqueueResponse(RedisMessage.error("ERR wrong number of arguments for 'auth' command"));
            return;
        }
        String password = args.get(0).asString();
        if (requirepass.equals(password)) {
            node.setAuthenticated(true);
            node.enqueueResponse(RedisMessage.simpleString("OK"));
        } else {
            node.enqueueResponse(RedisMessage.error("ERR invalid password"));
        }
    }

    private void addToWriteQueue(SDCSNode node) {
        if (!node.isInWriteQueue()) {
            node.setInWriteQueue(true);
            writeQueue.offer(node);
            ioSelector.wakeup();
        }
    }

    public void shutdown() {
        running = false;
        interrupt();
    }
}
