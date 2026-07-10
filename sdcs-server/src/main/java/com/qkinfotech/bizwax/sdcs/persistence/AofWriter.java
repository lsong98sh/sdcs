package com.qkinfotech.bizwax.sdcs.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AofWriter {

    private static final Logger log = LoggerFactory.getLogger(AofWriter.class);
    private static final int MAX_QUEUE_SIZE = 100000;

    public enum FsyncStrategy {
        EVERYSEC, ALWAYS, NO
    }

    private final Path aofPath;
    private final FsyncStrategy fsyncStrategy;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);
    private volatile boolean running = false;
    private Thread writerThread;
    private ScheduledExecutorService fsyncExecutor;
    private FileChannel fileChannel;
    private final AtomicBoolean isFsyncing = new AtomicBoolean(false);

    public AofWriter(Path aofPath) {
        this(aofPath, FsyncStrategy.EVERYSEC);
    }

    public AofWriter(Path aofPath, FsyncStrategy fsyncStrategy) {
        this.aofPath = aofPath;
        this.fsyncStrategy = fsyncStrategy;
    }

    public void start() throws IOException {
        running = true;
        fileChannel = FileChannel.open(aofPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);

        writerThread = new Thread(this::runWriter, "SDCS-AofWriter");
        writerThread.setDaemon(true);
        writerThread.start();

        if (fsyncStrategy == FsyncStrategy.EVERYSEC) {
            fsyncExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SDCS-AofFsync");
                t.setDaemon(true);
                return t;
            });
            fsyncExecutor.scheduleAtFixedRate(this::performFsync, 1, 1, TimeUnit.SECONDS);
        }
    }

    public void stop() {
        running = false;
        if (fsyncExecutor != null) {
            fsyncExecutor.shutdown();
        }
        if (writerThread != null) {
            writerThread.interrupt();
            try { writerThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        synchronized (this) {
            if (fileChannel != null && fileChannel.isOpen()) {
                try {
                    fileChannel.force(true);
                    fileChannel.close();
                } catch (IOException e) {
                    log.warn("Error closing AOF file channel: {}", e.getMessage());
                }
            }
        }
    }

    public void append(byte[] command) {
        if (!running) return;
        if (!queue.offer(command)) {
            log.warn("AOF queue full ({}/{}), applying backpressure", queue.size(), MAX_QUEUE_SIZE);
            try {
                queue.put(command);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runWriter() {
        while (running) {
            try {
                byte[] data = queue.poll(100, TimeUnit.MILLISECONDS);
                if (data == null) continue;

                synchronized (this) {
                    if (fileChannel != null && fileChannel.isOpen()) {
                        ByteBuffer buf = ByteBuffer.wrap(data);
                        while (buf.hasRemaining()) {
                            fileChannel.write(buf);
                        }
                        if (fsyncStrategy == FsyncStrategy.ALWAYS) {
                            fileChannel.force(false);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException e) {
                log.error("AOF write error: {}", e.getMessage(), e);
            }
        }
    }

    private void performFsync() {
        synchronized (this) {
            if (fileChannel == null || !fileChannel.isOpen()) return;
            if (!isFsyncing.compareAndSet(false, true)) return;
            try {
                fileChannel.force(false);
            } catch (IOException e) {
                log.warn("AOF fsync error: {}", e.getMessage());
            } finally {
                isFsyncing.set(false);
            }
        }
    }

    public FsyncStrategy getFsyncStrategy() {
        return fsyncStrategy;
    }
}
