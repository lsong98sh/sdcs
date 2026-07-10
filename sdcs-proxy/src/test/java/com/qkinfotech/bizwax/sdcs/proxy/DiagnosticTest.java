package com.qkinfotech.bizwax.sdcs.proxy;

import com.qkinfotech.bizwax.sdcs.SDCSServer;
import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticTest {

    /**
     * Test SDCS server using NIO SocketChannel (non-blocking with selector)
     * to match the server-side NIO behavior.
     */
    @Test
    void testNioConnectionToSDCS() throws Exception {
        SDCSConfig config = new SDCSConfig();
        config.setPort(16383);
        config.setPersistenceType(SDCSConfig.PersistenceType.NONE);
        config.setMetricsEnabled(false);
        config.setDataDir("data_diag2");

        SDCSServer server = new SDCSServer(config);
        server.start();
        Thread.sleep(500);

        try {
            Selector selector = Selector.open();
            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.connect(new InetSocketAddress("127.0.0.1", 16383));
            channel.register(selector, SelectionKey.OP_CONNECT);

            // Wait for connect
            while (true) {
                selector.select(1000);
                for (var key : selector.selectedKeys()) {
                    if (key.isConnectable()) {
                        channel.finishConnect();
                        System.out.println("Connected!");
                        key.interestOps(SelectionKey.OP_WRITE);
                    }
                }
                selector.selectedKeys().clear();
                if (channel.finishConnect() || channel.isConnected()) {
                    break;
                }
            }

            // Clear interest ops and prepare to write
            channel.register(selector, SelectionKey.OP_WRITE);

            // Send PING
            byte[] pingCmd = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8);
            ByteBuffer wbuf = ByteBuffer.wrap(pingCmd);

            long writeDeadline = System.currentTimeMillis() + 2000;
            while (wbuf.hasRemaining() && System.currentTimeMillis() < writeDeadline) {
                selector.select(500);
                for (var key : selector.selectedKeys()) {
                    if (key.isWritable()) {
                        int n = ((SocketChannel) key.channel()).write(wbuf);
                        System.out.println("Written " + n + " bytes");
                    }
                }
                selector.selectedKeys().clear();
            }
            System.out.println("All data sent, switching to read mode");

            // Switch to read mode
            channel.register(selector, SelectionKey.OP_READ);
            ByteBuffer rbuf = ByteBuffer.allocate(1024);

            long deadline = System.currentTimeMillis() + 5000;
            boolean gotResponse = false;
            while (System.currentTimeMillis() < deadline && !gotResponse) {
                int n = selector.select(500);
                if (n > 0) {
                    for (var key : selector.selectedKeys()) {
                        if (key.isReadable()) {
                            int bytes = ((SocketChannel) key.channel()).read(rbuf);
                            if (bytes > 0) {
                                rbuf.flip();
                                String response = new String(rbuf.array(), 0, rbuf.limit(), StandardCharsets.UTF_8);
                                System.out.println("Got response: " + response.replace("\r", "\\r").replace("\n", "\\n"));
                                gotResponse = true;
                                assertTrue(response.startsWith("+PONG"), "Expected +PONG but got: " + response);
                            } else if (bytes == -1) {
                                System.out.println("Connection closed by server");
                                gotResponse = true;
                                fail("Connection closed without response");
                            }
                        }
                    }
                    selector.selectedKeys().clear();
                } else {
                    System.out.println("Select returned 0, still waiting...");
                }
            }
            assertTrue(gotResponse, "No response received within timeout");
            channel.close();
        } finally {
            server.stop();
        }
    }

    /**
     * Test SDCS server with blocking socket but wait after connect.
     */
    @Test
    void testBlockingSocketWithDelay() throws Exception {
        SDCSConfig config = new SDCSConfig();
        config.setPort(16384);
        config.setPersistenceType(SDCSConfig.PersistenceType.NONE);
        config.setMetricsEnabled(false);
        config.setDataDir("data_diag3");

        SDCSServer server = new SDCSServer(config);
        server.start();
        Thread.sleep(500);

        try {
            // Connect, wait for SDCS selector to register, then send
            try (Socket socket = new Socket("127.0.0.1", 16384)) {
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // Wait a bit for the SDCS selector to register the connection
                Thread.sleep(100);

                byte[] pingCmd = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8);
                System.out.println("Sending PING...");
                out.write(pingCmd);
                out.flush();

                byte[] buf = new byte[1024];
                int n = in.read(buf);
                String response = new String(buf, 0, n, StandardCharsets.UTF_8);
                System.out.println("Got response: " + response.replace("\r", "\\r").replace("\n", "\\n"));
                assertTrue(response.startsWith("+PONG"), "Expected +PONG but got: " + response);
            }
        } finally {
            server.stop();
        }
    }
}
