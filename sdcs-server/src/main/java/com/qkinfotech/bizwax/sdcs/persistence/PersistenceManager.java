package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.common.*;
import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class PersistenceManager {

    private static final Logger log = LoggerFactory.getLogger(PersistenceManager.class);
    private static final int DB_COUNT = 16;

    private final Path dataDir;
    private final Path aofPath;
    private final DatabaseManager databaseManager;
    private final RdbSaver rdbSaver = new RdbSaver();
    private final RdbLoader rdbLoader = new RdbLoader();
    private final AofWriter aofWriter;
    private final AofLoader aofLoader = new AofLoader();
    private final ScheduledExecutorService cronExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "SDCS-Cron"); t.setDaemon(true); return t; }
    );
    private final SDCSConfig.PersistenceType persistenceType;

    private RocksDBStore rocksDBStore;

    public PersistenceManager(Path dataDir, DatabaseManager databaseManager, AofWriter.FsyncStrategy fsyncStrategy) {
        this.dataDir = dataDir;
        this.aofPath = dataDir.resolve("appendonly.aof");
        this.databaseManager = databaseManager;
        this.aofWriter = new AofWriter(aofPath, fsyncStrategy);
        this.persistenceType = SDCSConfig.getInstance().getPersistenceType();
    }

    public PersistenceManager(Path dataDir, DatabaseManager databaseManager) {
        this(dataDir, databaseManager, AofWriter.FsyncStrategy.EVERYSEC);
    }

    public void start() throws IOException {
        Files.createDirectories(dataDir);

        if (persistenceType == SDCSConfig.PersistenceType.ROCKSDB) {
            Path rocksDbPath = dataDir.resolve("rocksdb");
            rocksDBStore = new RocksDBStore(rocksDbPath);
            rocksDBStore.preload(databaseManager.getStore(0));
            registerRocksDBListeners();
            log.info("RocksDB persistence started at {}", rocksDbPath);
        } else {
            aofWriter.start();
            loadData();
        }

        cronExecutor.scheduleAtFixedRate(this::serverCron, 1, 1, TimeUnit.SECONDS);
    }

    private void registerRocksDBListeners() {
        for (int i = 0; i < DB_COUNT; i++) {
            MemoryStore store = databaseManager.getStore(i);
            if (store != null) {
                store.addListener(rocksDBStore);
            }
        }
    }

    public void stop() {
        cronExecutor.shutdown();

        if (persistenceType == SDCSConfig.PersistenceType.ROCKSDB) {
            if (rocksDBStore != null) {
                rocksDBStore.shutdown();
            }
            return;
        }

        aofWriter.stop();
        try {
            saveRdb();
        } catch (Exception e) {
            log.error("Error saving RDB on shutdown: {}", e.getMessage());
        }
    }

    private void serverCron() {
        if (persistenceType == SDCSConfig.PersistenceType.ROCKSDB) {
            return;
        }
        for (int i = 0; i < DB_COUNT; i++) {
            MemoryStore store = databaseManager.getStore(i);
            if (store == null) continue;
            for (Map.Entry<String, RedisData> entry : new HashMap<>(store.getStore()).entrySet()) {
                if (entry.getValue() != null && entry.getValue().isExpired()) {
                    store.getStore().remove(entry.getKey());
                }
            }
        }
    }

    public void loadData() {
        if (persistenceType == SDCSConfig.PersistenceType.ROCKSDB) {
            return;
        }

        if (Files.exists(aofPath)) {
            try {
                loadFromAof();
                log.info("Data loaded from AOF");
                return;
            } catch (Exception e) {
                log.warn("Failed to load AOF, trying RDB: {}", e.getMessage());
            }
        }

        loadFromRdb();
    }

    private void loadFromAof() throws IOException {
        List<List<String>> commands = aofLoader.loadCommands(aofPath);
        DatabaseManager tempDbManager = new DatabaseManager();
        com.qkinfotech.bizwax.sdcs.command.CommandDispatcher tempDispatcher =
                new com.qkinfotech.bizwax.sdcs.command.CommandDispatcher(tempDbManager);

        for (List<String> command : commands) {
            if (command.isEmpty()) continue;
            String cmdName = command.get(0).toUpperCase();
            List<com.qkinfotech.bizwax.sdcs.protocol.RedisMessage> args = new ArrayList<>();
            for (int i = 1; i < command.size(); i++) {
                args.add(com.qkinfotech.bizwax.sdcs.protocol.RedisMessage.bulkString(command.get(i).getBytes()));
            }
            tempDispatcher.dispatch(cmdName, args);
        }

        for (int i = 0; i < DB_COUNT; i++) {
            MemoryStore tempStore = tempDbManager.getStore(i);
            MemoryStore actualStore = databaseManager.getStore(i);
            if (tempStore != null && actualStore != null && tempStore.size() > 0) {
                actualStore.getStore().putAll(tempStore.getStore());
            }
        }
    }

    private void loadFromRdb() {
        for (int i = 0; i < DB_COUNT; i++) {
            File rdbFile = dataDir.resolve("sdcs_" + i + ".rdb").toFile();
            if (!rdbFile.exists()) continue;
            try {
                RdbLoader.LoadResult result = rdbLoader.load(rdbFile);
                if (result != null) {
                    int dbIdx = result.getDbIndex();
                    MemoryStore store = databaseManager.getStore(dbIdx);
                    if (store != null) {
                        store.getStore().putAll(result.getData());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load RDB for DB {}: {}", i, e.getMessage());
            }
        }
        log.info("RDB data loaded from {} files", dataDir);
    }

    public void saveRdb() throws IOException {
        if (persistenceType == SDCSConfig.PersistenceType.ROCKSDB) {
            return;
        }
        for (int i = 0; i < DB_COUNT; i++) {
            MemoryStore store = databaseManager.getStore(i);
            if (store == null || store.size() == 0) continue;
            File rdbFile = dataDir.resolve("sdcs_" + i + ".rdb").toFile();
            rdbSaver.save(store.getStore(), rdbFile, i);
        }
        log.info("RDB saved to {}sdcs_{db}.rdb", dataDir);
    }

    public void appendAof(byte[] command) {
        if (persistenceType == SDCSConfig.PersistenceType.ROCKSDB) {
            return;
        }
        aofWriter.append(command);
    }

    public void rewriteAof() throws IOException {
        if (persistenceType == SDCSConfig.PersistenceType.ROCKSDB) {
            return;
        }
        Path tempPath = dataDir.resolve("temp-rewrite.aof");
        AofRewriter rewriter = new AofRewriter(databaseManager);
        rewriter.rewrite(tempPath.toFile());

        aofWriter.stop();
        try {
            Files.move(tempPath, aofPath, StandardCopyOption.REPLACE_EXISTING);
            aofWriter.start();
        } catch (Exception e) {
            aofWriter.start();
            throw e;
        }
        log.info("AOF rewrite complete");
    }

    public RocksDBStore getRocksDBStore() {
        return rocksDBStore;
    }

    public Path getRdbPath(int db) {
        return dataDir.resolve("sdcs_" + db + ".rdb");
    }

    public Path getAofPath() {
        return aofPath;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public SDCSConfig.PersistenceType getPersistenceType() {
        return persistenceType;
    }
}
