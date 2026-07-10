package com.qkinfotech.bizwax.sdcs.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.Selector;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Dedicated acceptor thread — only responsible for accepting new TCP connections.
 * <p>
 * Accepted channels are handed to the IO thread via a lock-free queue.
 * </p>
 */
public class NIOAcceptor extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(NIOAcceptor.class);

    private final ServerSocketChannel serverChannel;
    private final Selector ioSelector;
    private final ConcurrentLinkedQueue<SocketChannel> newConnectionQueue;

    private volatile boolean running;

    public NIOAcceptor(ServerSocketChannel serverChannel,
                       Selector ioSelector,
                       ConcurrentLinkedQueue<SocketChannel> newConnectionQueue) {
        super("SDCS-Acceptor");
        this.serverChannel = serverChannel;
        this.ioSelector = ioSelector;
        this.newConnectionQueue = newConnectionQueue;
    }

    @Override
    public void run() {
        running = true;
        logger.info("NIOAcceptor started");
        while (running) {
            try {
                SocketChannel clientChannel = serverChannel.accept();
                clientChannel.configureBlocking(false);
                newConnectionQueue.offer(clientChannel);
                ioSelector.wakeup();

                logger.debug("Accepted connection from {}", clientChannel.getRemoteAddress());
            } catch (IOException e) {
                if (running) {
                    logger.error("Error accepting connection: {}", e.getMessage(), e);
                }
            }
        }
        logger.info("NIOAcceptor stopped");
    }

    public void shutdown() {
        running = false;
        interrupt();
    }
}
