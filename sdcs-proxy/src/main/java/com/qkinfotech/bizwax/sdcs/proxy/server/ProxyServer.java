package com.qkinfotech.bizwax.sdcs.proxy.server;

import com.qkinfotech.bizwax.sdcs.proxy.config.ProxyConfig;
import com.qkinfotech.bizwax.sdcs.proxy.registry.RegistryManager;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.protocol.RespDecoder;
import com.qkinfotech.bizwax.sdcs.protocol.RespEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class ProxyServer {

    private static final Logger log = LoggerFactory.getLogger(ProxyServer.class);

    private final ProxyConfig config;
    private final RegistryManager registryManager;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ProxyStatusHandler statusHandler;

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private Thread eventLoopThread;

    // Track all client contexts
    private final ConcurrentLinkedQueue<ClientContext> allClients = new ConcurrentLinkedQueue<>();
    // Clients with pending responses to flush (cross-thread communication)
    private final ConcurrentLinkedQueue<ClientContext> clientsWithPendingWrites = new ConcurrentLinkedQueue<>();

    public ProxyServer(ProxyConfig config, RegistryManager registryManager) {
        this.config = config;
        this.registryManager = registryManager;
        this.statusHandler = new ProxyStatusHandler(config, this, registryManager.getConnectionPool());
    }

    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().setReuseAddress(true);
        serverChannel.bind(new InetSocketAddress(config.getProxyBind(), config.getProxyPort()));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        running.set(true);
        eventLoopThread = Thread.ofPlatform()
                .name("proxy-event-loop")
                .daemon(true)
                .start(this::eventLoop);
        log.info("Proxy server started on {}:{}", config.getProxyBind(), config.getProxyPort());
    }

    private void eventLoop() {
        while (running.get()) {
            try {
                int n = selector.select(500);
                if (n > 0) {
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        if (!key.isValid()) continue;
                        try {
                            if (key.isAcceptable()) {
                                acceptClient(key);
                            } else if (key.isReadable()) {
                                handleRead(key);
                            } else if (key.isWritable()) {
                                handleWrite(key);
                            }
                        } catch (Exception e) {
                            closeKey(key);
                        }
                    }
                }

                // Drain pending client writes (cross-thread from virtual threads)
                ClientContext pendingCtx;
                while ((pendingCtx = clientsWithPendingWrites.poll()) != null) {
                    flushClientResponses(pendingCtx);
                }
            } catch (IOException e) {
                if (running.get()) {
                    log.error("Event loop select error", e);
                }
            }
        }
    }

    private void acceptClient(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel client = ssc.accept();
        if (client == null) return;
        client.configureBlocking(false);
        client.socket().setTcpNoDelay(true);
        client.socket().setKeepAlive(true);

        ClientContext ctx = new ClientContext(client);
        client.register(selector, SelectionKey.OP_READ, ctx);
        allClients.add(ctx);
        log.debug("Client connected: {}", client.getRemoteAddress());
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ClientContext ctx = (ClientContext) key.attachment();

        ctx.readBuf.clear();
        int n = ch.read(ctx.readBuf);
        if (n == -1) {
            closeClient(ctx);
            return;
        }
        ctx.readBuf.flip();

        // Decode as many complete commands as possible from buffer
        while (true) {
            int pos = ctx.readBuf.position();
            RedisMessage msg = ctx.decoder.decode(ctx.readBuf);
            if (msg == null) {
                // Need more data - compact and wait
                ctx.readBuf.compact();
                break;
            }
            // Full command received - process in a virtual thread
            processClientCommand(ctx, msg);
        }
    }

    private void processClientCommand(ClientContext ctx, RedisMessage msg) {
        List<String> args = RespCodec.decode(msg);
        if (args == null || args.isEmpty()) {
            log.warn("Empty or unparseable command from {}", remoteAddr(ctx.channel));
            return;
        }

        String command = args.get(0).toUpperCase();
        List<String> cmdArgs = args.subList(1, args.size());

        // Local proxy commands handled directly without routing
        if ("SDCS_PROXY_STATUS".equals(command)) {
            RedisMessage response = statusHandler.handle();
            long seq = ctx.nextCmdSeq.getAndIncrement();
            ctx.enqueueResponse(seq, response);
            clientsWithPendingWrites.add(ctx);
            selector.wakeup();
            return;
        }

        // Assign sequence number to maintain pipeline ordering
        long seq = ctx.nextCmdSeq.getAndIncrement();

        // Create a client writer that enqueues responses for the event loop to send
        ClientWriter writer = response -> {
            ctx.enqueueResponse(seq, response);
            clientsWithPendingWrites.add(ctx);
            selector.wakeup();
        };

        CommandRouter.dispatch(writer, command, cmdArgs, registryManager);
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ClientContext ctx = (ClientContext) key.attachment();
        flushClientResponses(ctx);
        if (ctx.orderedResponses.isEmpty()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    private void flushClientResponses(ClientContext ctx) {
        // Deliver responses in strict FIFO order (pipeline ordering)
        RedisMessage msg;
        while ((msg = ctx.pollNextDeliverable()) != null) {
            ctx.encoder.reset();
            ctx.encoder.encode(msg, ctx.writeBuf);
            ctx.writeBuf.flip();
            try {
                while (ctx.writeBuf.hasRemaining()) {
                    ctx.channel.write(ctx.writeBuf);
                }
                ctx.writeBuf.clear();
            } catch (IOException e) {
                log.error("Error writing to client {}: {}", remoteAddr(ctx.channel), e.getMessage());
                closeClient(ctx);
                return;
            }
        }

        // If still more data to deliver, register OP_WRITE on selector
        if (!ctx.orderedResponses.isEmpty()) {
            SelectionKey sk = ctx.channel.keyFor(selector);
            if (sk != null && sk.isValid()) {
                sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    private void closeKey(SelectionKey key) {
        if (key.attachment() instanceof ClientContext) {
            closeClient((ClientContext) key.attachment());
        } else {
            try { key.channel().close(); } catch (IOException ignored) {}
            key.cancel();
        }
    }

    private void closeClient(ClientContext ctx) {
        try {
            ctx.channel.close();
        } catch (IOException ignored) {}
        allClients.remove(ctx);
        log.debug("Client disconnected: {}", remoteAddr(ctx.channel));
    }

    private static String remoteAddr(SocketChannel ch) {
        try {
            return ch.getRemoteAddress().toString();
        } catch (IOException e) {
            return "unknown";
        }
    }

    public void await() throws InterruptedException {
        if (eventLoopThread != null) {
            eventLoopThread.join();
        }
    }

    public void stop() {
        log.info("Stopping proxy server...");
        running.set(false);
        if (selector != null) {
            selector.wakeup();
        }
        // Close all client connections
        ClientContext ctx;
        while ((ctx = allClients.poll()) != null) {
            try { ctx.channel.close(); } catch (IOException ignored) {}
        }
        try { serverChannel.close(); } catch (IOException ignored) {}
        try { selector.close(); } catch (IOException ignored) {}
        log.info("Proxy server stopped.");
    }

    public int getClientCount() {
        return allClients.size();
    }

    /**
     * Per-client connection state, used by the event loop thread.
     * Maintains strict pipeline ordering via sequence numbers:
     * - Each command gets an incrementing sequence number from nextCmdSeq
     * - Responses arrive via virtual threads and are stored in orderedResponses
     * - The event loop drains responses in sequence order (nextDeliverSeq)
     */
    static class ClientContext {
        final SocketChannel channel;
        final RespDecoder decoder = new RespDecoder();
        final ByteBuffer readBuf = ByteBuffer.allocate(8192);
        final RespEncoder encoder = new RespEncoder(8192);
        final ByteBuffer writeBuf = ByteBuffer.allocate(8192);

        /** Monotonically increasing command sequence number (event loop thread only) */
        final AtomicLong nextCmdSeq = new AtomicLong(0);
        /** Next sequence expected for ordered delivery (event loop thread only) */
        final AtomicLong nextDeliverSeq = new AtomicLong(0);
        /** Thread-safe ordered map: seq → RedisMessage, populated by virtual threads */
        final ConcurrentSkipListMap<Long, RedisMessage> orderedResponses = new ConcurrentSkipListMap<>();

        ClientContext(SocketChannel channel) {
            this.channel = channel;
        }

        /**
         * Thread-safe: called from virtual threads to enqueue a response.
         */
        void enqueueResponse(long seq, RedisMessage response) {
            orderedResponses.put(seq, response);
        }

        /**
         * Called only from the event loop thread.
         * Delivers the next in-order response, or null if the next sequence is not yet available.
         */
        RedisMessage pollNextDeliverable() {
            long expected = nextDeliverSeq.get();
            RedisMessage msg = orderedResponses.get(expected);
            if (msg != null) {
                orderedResponses.remove(expected);
                nextDeliverSeq.incrementAndGet();
            }
            return msg;
        }
    }
}
