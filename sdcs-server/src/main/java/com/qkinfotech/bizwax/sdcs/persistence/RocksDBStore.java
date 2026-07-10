package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.buffer.ByteArrayChain;
import com.qkinfotech.bizwax.sdcs.common.RedisData;
import com.qkinfotech.bizwax.sdcs.common.RedisDataType;
import com.qkinfotech.bizwax.sdcs.common.RedisHash;
import com.qkinfotech.bizwax.sdcs.common.RedisList;
import com.qkinfotech.bizwax.sdcs.common.RedisSet;
import com.qkinfotech.bizwax.sdcs.common.RedisZSet;
import com.qkinfotech.bizwax.sdcs.common.RedisZSet.ZSetEntry;
import com.qkinfotech.bizwax.sdcs.common.RedisStream;
import com.qkinfotech.bizwax.sdcs.common.RedisStream.StreamEntry;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class RocksDBStore implements StoreListener {

    private static final Logger log = LoggerFactory.getLogger(RocksDBStore.class);

    private static final byte TYPE_STRING = 0;
    private static final byte TYPE_LIST = 1;
    private static final byte TYPE_HASH = 2;
    private static final byte TYPE_SET = 3;
    private static final byte TYPE_ZSET = 4;
    private static final byte TYPE_STREAM = 5;

    private static final int BATCH_MAX_SIZE = 1000;
    private static final int BATCH_FLUSH_MS = 250;

    private final RocksDB db;
    private final Path dbPath;
    private final LinkedBlockingQueue<WriteTask> writeQueue = new LinkedBlockingQueue<>();
    private final AtomicLong totalWritten = new AtomicLong(0);
    private volatile boolean running = true;
    private Thread writeThread;
    private final WriteOptions writeOptions;

    public RocksDBStore(Path dbPath) {
        this.dbPath = dbPath;
        RocksDB.loadLibrary();
        Options options = new Options()
                .setCreateIfMissing(true)
                .setKeepLogFileNum(1)
                .setMaxOpenFiles(-1);
        try {
            File dir = dbPath.toFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            this.db = RocksDB.open(options, dbPath.toString());
            this.writeOptions = new WriteOptions().setDisableWAL(false);
            startWriterThread();
            log.info("RocksDB opened at {}", dbPath);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to open RocksDB at " + dbPath, e);
        }
    }

    private void startWriterThread() {
        writeThread = new Thread(() -> {
            List<WriteTask> batch = new ArrayList<>(BATCH_MAX_SIZE);
            while (running) {
                try {
                    WriteTask first = writeQueue.poll(BATCH_FLUSH_MS, TimeUnit.MILLISECONDS);
                    if (first != null) {
                        batch.add(first);
                        writeQueue.drainTo(batch, BATCH_MAX_SIZE - 1);
                        flushBatch(batch);
                        batch.clear();
                    } else if (!batch.isEmpty()) {
                        flushBatch(batch);
                        batch.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("RocksDB batch write error: {}", e.getMessage(), e);
                }
            }
            List<WriteTask> remaining = new ArrayList<>();
            writeQueue.drainTo(remaining);
            if (!remaining.isEmpty()) {
                flushBatch(remaining);
            }
        }, "SDCS-RocksDB-Writer");
        writeThread.setDaemon(true);
        writeThread.start();
    }

    private void flushBatch(List<WriteTask> batch) {
        if (batch.isEmpty()) return;
        try (WriteBatch writeBatch = new WriteBatch()) {
            for (WriteTask task : batch) {
                if (task.valueBytes == null) {
                    writeBatch.delete(task.keyBytes);
                } else {
                    writeBatch.put(task.keyBytes, task.valueBytes);
                }
            }
            db.write(writeOptions, writeBatch);
            totalWritten.addAndGet(batch.size());
        } catch (RocksDBException e) {
            log.error("RocksDB flush batch error ({} entries): {}", batch.size(), e.getMessage(), e);
        }
    }

    @Override
    public void onPut(String key, RedisData value) {
        if (key == null || value == null) return;
        try {
            byte[] keyBytes = key.getBytes();
            byte[] valueBytes = serialize(value);
            writeQueue.offer(new WriteTask(keyBytes, valueBytes));
        } catch (Exception e) {
            log.error("Error serializing key {}: {}", key, e.getMessage());
        }
    }

    @Override
    public void onRemove(String key) {
        if (key == null) return;
        writeQueue.offer(new WriteTask(key.getBytes(), null));
    }

    public void preload(MemoryStore store) {
        long count = 0;
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                String key = new String(iterator.key());
                byte[] valueBytes = iterator.value();
                try {
                    RedisData data = deserialize(valueBytes);
                    store.getStore().put(key, data);
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to deserialize key {}: {}", key, e.getMessage());
                }
                iterator.next();
            }
        }
        log.info("Preloaded {} keys from RocksDB into MemoryStore", count);
    }

    public long estimateNumKeys() {
        try {
            return db.getLongProperty("rocksdb.estimate-num-keys");
        } catch (RocksDBException e) {
            log.warn("Failed to get estimate-num-keys: {}", e.getMessage());
            return 0;
        }
    }

    public void shutdown() {
        running = false;
        if (writeThread != null) {
            try {
                writeThread.interrupt();
                writeThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        writeOptions.close();
        db.close();
        log.info("RocksDB closed. Total writes: {}", totalWritten.get());
    }

    private byte[] serialize(RedisData data) {
        RedisDataType type = data.getType();
        long expireAtMs = data.getExpireAtMs();
        Object value = data.getValue(Object.class);

        byte[] valueBytes = serializeValue(type, value);
        ByteBuffer buf = ByteBuffer.allocate(1 + 8 + valueBytes.length);
        buf.put(typeByte(type));
        buf.putLong(expireAtMs);
        buf.put(valueBytes);
        return buf.array();
    }

    private byte typeByte(RedisDataType type) {
        return switch (type) {
            case STRING -> TYPE_STRING;
            case LIST -> TYPE_LIST;
            case HASH -> TYPE_HASH;
            case SET -> TYPE_SET;
            case ZSET -> TYPE_ZSET;
            case STREAM -> TYPE_STREAM;
        };
    }

    private RedisDataType typeFromByte(byte b) {
        return switch (b) {
            case TYPE_STRING -> RedisDataType.STRING;
            case TYPE_LIST -> RedisDataType.LIST;
            case TYPE_HASH -> RedisDataType.HASH;
            case TYPE_SET -> RedisDataType.SET;
            case TYPE_ZSET -> RedisDataType.ZSET;
            case TYPE_STREAM -> RedisDataType.STREAM;
            default -> throw new IllegalArgumentException("Unknown type byte: " + b);
        };
    }

    private byte[] serializeValue(RedisDataType type, Object value) {
        return switch (type) {
            case STRING -> {
                if (value instanceof ByteArrayChain chain) {
                    yield chain.toByteArray();
                }
                yield (byte[]) value;
            }
            case LIST -> serializeList((RedisList) value);
            case HASH -> serializeHash((RedisHash) value);
            case SET -> serializeSet((RedisSet) value);
            case ZSET -> serializeZSet((RedisZSet) value);
            case STREAM -> serializeStream((RedisStream) value);
        };
    }

    private byte[] serializeList(RedisList list) {
        List<byte[]> elements = list.lrange(0, -1);
        int totalLen = 4;
        for (byte[] elem : elements) {
            totalLen += 4 + elem.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        buf.putInt(elements.size());
        for (byte[] elem : elements) {
            buf.putInt(elem.length);
            buf.put(elem);
        }
        return buf.array();
    }

    private byte[] serializeHash(RedisHash hash) {
        Map<String, byte[]> all = hash.hgetAll();
        int totalLen = 4;
        for (Map.Entry<String, byte[]> entry : all.entrySet()) {
            byte[] fieldBytes = entry.getKey().getBytes();
            byte[] valBytes = entry.getValue();
            totalLen += 2 + fieldBytes.length + 4 + valBytes.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        buf.putInt(all.size());
        for (Map.Entry<String, byte[]> entry : all.entrySet()) {
            byte[] fieldBytes = entry.getKey().getBytes();
            byte[] valBytes = entry.getValue();
            buf.putShort((short) fieldBytes.length);
            buf.put(fieldBytes);
            buf.putInt(valBytes.length);
            buf.put(valBytes);
        }
        return buf.array();
    }

    private byte[] serializeSet(RedisSet set) {
        Set<byte[]> members;
        try {
            members = set.smembers();
        } catch (Exception e) {
            log.warn("Failed to read set members for serialization: {}", e.getMessage(), e);
            members = java.util.Collections.emptySet();
        }
        int totalLen = 4;
        for (byte[] member : members) {
            totalLen += 4 + member.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        buf.putInt(members.size());
        for (byte[] member : members) {
            buf.putInt(member.length);
            buf.put(member);
        }
        return buf.array();
    }

    private byte[] serializeZSet(RedisZSet zset) {
        List<ZSetEntry> entries = zset.zrange(0, -1);
        int totalLen = 4;
        for (ZSetEntry entry : entries) {
            totalLen += 4 + entry.member().length + 8;
        }
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        buf.putInt(entries.size());
        for (ZSetEntry entry : entries) {
            buf.putInt(entry.member().length);
            buf.put(entry.member());
            buf.putDouble(entry.score());
        }
        return buf.array();
    }

    private byte[] serializeStream(RedisStream stream) {
        List<StreamEntry> allEntries = new ArrayList<>();
        try {
            List<StreamEntry> range = stream.xrange("-", "+", Long.MAX_VALUE);
            if (range != null) {
                allEntries = range;
            }
        } catch (Exception e) {
            log.warn("Failed to read stream entries: {}", e.getMessage());
        }

        int totalLen = 4;
        for (StreamEntry entry : allEntries) {
            byte[] idBytes = entry.getId().getBytes();
            Map<String, byte[]> fields = entry.getFields();
            totalLen += 2 + idBytes.length + 4;
            for (Map.Entry<String, byte[]> f : fields.entrySet()) {
                byte[] fk = f.getKey().getBytes();
                byte[] fv = f.getValue();
                totalLen += 2 + fk.length + 4 + fv.length;
            }
        }

        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        buf.putInt(allEntries.size());
        for (StreamEntry entry : allEntries) {
            byte[] idBytes = entry.getId().getBytes();
            Map<String, byte[]> fields = entry.getFields();
            buf.putShort((short) idBytes.length);
            buf.put(idBytes);
            buf.putInt(fields.size());
            for (Map.Entry<String, byte[]> f : fields.entrySet()) {
                byte[] fk = f.getKey().getBytes();
                byte[] fv = f.getValue();
                buf.putShort((short) fk.length);
                buf.put(fk);
                buf.putInt(fv.length);
                buf.put(fv);
            }
        }
        return buf.array();
    }

    private RedisData deserialize(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        byte typeByteVal = buf.get();
        long expireAtMs = buf.getLong();
        RedisDataType type = typeFromByte(typeByteVal);
        byte[] valueBytes = new byte[buf.remaining()];
        buf.get(valueBytes);
        Object value = deserializeValue(type, valueBytes);
        return new RedisData(type, value, expireAtMs);
    }

    private Object deserializeValue(RedisDataType type, byte[] bytes) {
        return switch (type) {
            case STRING -> bytes;
            case LIST -> deserializeList(bytes);
            case HASH -> deserializeHash(bytes);
            case SET -> deserializeSet(bytes);
            case ZSET -> deserializeZSet(bytes);
            case STREAM -> deserializeStream(bytes);
        };
    }

    private RedisList deserializeList(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int count = buf.getInt();
        RedisList list = new RedisList();
        List<byte[]> elements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int len = buf.getInt();
            byte[] elem = new byte[len];
            buf.get(elem);
            elements.add(elem);
        }
        if (!elements.isEmpty()) {
            list.rpush(elements.toArray(new byte[0][]));
        }
        return list;
    }

    private RedisHash deserializeHash(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int count = buf.getInt();
        RedisHash hash = new RedisHash();
        Map<String, byte[]> fieldValues = new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            short fieldLen = buf.getShort();
            byte[] fieldBytes = new byte[fieldLen];
            buf.get(fieldBytes);
            int valLen = buf.getInt();
            byte[] valBytes = new byte[valLen];
            buf.get(valBytes);
            fieldValues.put(new String(fieldBytes), valBytes);
        }
        hash.hmset(fieldValues);
        return hash;
    }

    private RedisSet deserializeSet(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int count = buf.getInt();
        RedisSet set = new RedisSet();
        List<byte[]> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int len = buf.getInt();
            byte[] member = new byte[len];
            buf.get(member);
            members.add(member);
        }
        if (!members.isEmpty()) {
            set.sadd(members.toArray(new byte[0][]));
        }
        return set;
    }

    private RedisZSet deserializeZSet(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int count = buf.getInt();
        RedisZSet zset = new RedisZSet();
        for (int i = 0; i < count; i++) {
            int memLen = buf.getInt();
            byte[] member = new byte[memLen];
            buf.get(member);
            double score = buf.getDouble();
            zset.zadd(score, member);
        }
        return zset;
    }

    private RedisStream deserializeStream(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int count = buf.getInt();
        RedisStream stream = new RedisStream();
        for (int i = 0; i < count; i++) {
            short idLen = buf.getShort();
            byte[] idBytes = new byte[idLen];
            buf.get(idBytes);
            String id = new String(idBytes);
            int fieldCount = buf.getInt();
            Map<String, byte[]> fields = new LinkedHashMap<>(fieldCount);
            for (int j = 0; j < fieldCount; j++) {
                short fkLen = buf.getShort();
                byte[] fkBytes = new byte[fkLen];
                buf.get(fkBytes);
                int fvLen = buf.getInt();
                byte[] fvBytes = new byte[fvLen];
                buf.get(fvBytes);
                fields.put(new String(fkBytes), fvBytes);
            }
            stream.xadd(id, fields);
        }
        return stream;
    }

    private static class WriteTask {
        final byte[] keyBytes;
        final byte[] valueBytes;

        WriteTask(byte[] keyBytes, byte[] valueBytes) {
            this.keyBytes = keyBytes;
            this.valueBytes = valueBytes;
        }
    }
}
