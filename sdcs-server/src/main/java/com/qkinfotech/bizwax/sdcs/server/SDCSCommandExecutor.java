package com.qkinfotech.bizwax.sdcs.server;

import com.qkinfotech.bizwax.sdcs.blocking.BlockingManager;
import com.qkinfotech.bizwax.sdcs.blocking.BlockingHandler;
import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.common.RedisZSet.ZSetEntry;
import com.qkinfotech.bizwax.sdcs.common.StripedLock;
import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.metrics.MetricsCollector;
import com.qkinfotech.bizwax.sdcs.metrics.SlowLog;
import com.qkinfotech.bizwax.sdcs.metrics.MonitorManager;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import com.qkinfotech.bizwax.sdcs.transaction.TransactionManager;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class SDCSCommandExecutor {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SDCSCommandExecutor.class);

    private static final DatabaseManager databaseManager = new DatabaseManager();
    private static final PersistenceManager persistenceManager;
    private static final CommandDispatcher dispatcher;
    private static final StripedLock stripedLock = new StripedLock();

    private static final Set<String> WRITE_COMMANDS = Set.of(
            "SET", "MSET", "MSETNX", "DEL", "INCR", "INCRBY", "INCRBYFLOAT", "DECR", "DECRBY",
            "APPEND", "SETRANGE", "GETSET", "GETDEL", "SETEX", "PSETEX",
            "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT", "GETEX",
            "PERSIST", "LPUSH", "RPUSH", "LPUSHX", "RPUSHX", "LPOP", "RPOP",
            "LSET", "LTRIM", "LREM", "LINSERT", "LMOVE", "RPOPLPUSH",
            "HSET", "HMSET", "HDEL",
            "HINCRBY", "HINCRBYFLOAT", "HSETNX", "SADD", "SREM", "SPOP", "SMOVE",
            "SUNIONSTORE", "SINTERSTORE", "SDIFFSTORE",
            "GEOADD", "ZADD", "ZINCRBY", "ZREM", "ZPOPMIN", "ZPOPMAX",
            "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE", "ZREMRANGEBYLEX",
            "ZDIFFSTORE", "ZINTERSTORE", "ZUNIONSTORE", "ZRANGESTORE",
            "RENAME", "RENAMENX", "COPY", "MOVE",
            "FLUSHDB", "FLUSHALL", "SAVE", "BGSAVE", "BGREWRITEAOF",
            "SORT", "PFADD", "PFMERGE", "SETBIT", "BITOP",
            "XADD", "XTRIM", "XDEL", "XGROUP",
            "SLAVEOF", "EVAL", "EVALSHA", "SCRIPT"
    );

    private static final SDCSConfig.PersistenceType persistenceType = SDCSConfig.getInstance().getPersistenceType();
    private static volatile int lastAofDb = 0;

    static {
        String dataDirStr = SDCSConfig.getInstance().getDataDir();
        Path dataDir = Paths.get(dataDirStr);
        try {
            persistenceManager = new PersistenceManager(dataDir, databaseManager);
            persistenceManager.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize persistence: " + e.getMessage(), e);
        }
        dispatcher = new CommandDispatcher(databaseManager, persistenceManager);
    }

    public static RedisMessage execute(String commandName, List<RedisMessage> args) {
        return execute(commandName, args, null);
    }

    public static RedisMessage execute(String commandName, List<RedisMessage> args, Consumer<RedisMessage> deferredCallback) {
        long startNanos = System.nanoTime();
        boolean isError = false;

        try {
            if ("BLPOP".equals(commandName) || "BRPOP".equals(commandName)) {
                return executeBlockingPop(commandName, args, deferredCallback);
            }
            if ("BLMOVE".equals(commandName)) {
                return executeBlockingMove(args, deferredCallback);
            }
            if ("BRPOPLPUSH".equals(commandName)) {
                return executeBRPopLPush(args, deferredCallback);
            }
            if ("BZPOPMIN".equals(commandName)) {
                return executeBlockingZPop("ZPOPMIN", args, deferredCallback);
            }
            if ("BZPOPMAX".equals(commandName)) {
                return executeBlockingZPop("ZPOPMAX", args, deferredCallback);
            }

            if (WRITE_COMMANDS.contains(commandName) && !TransactionManager.isInTransaction()) {
                return executeWithLock(commandName, args);
            }

            return dispatcher.dispatch(commandName, args);
        } catch (Exception e) {
            isError = true;
            return RedisMessage.error("ERR " + e.getMessage());
        } finally {
            long durationNanos = System.nanoTime() - startNanos;
            MetricsCollector.getInstance().recordCommand(commandName, durationNanos, isError);
            long durationUs = durationNanos / 1000;
            if (durationUs >= SlowLog.getInstance().getSlowLogThresholdUs()) {
                List<String> argStrs = new ArrayList<>(args.size());
                for (RedisMessage arg : args) {
                    try { argStrs.add(arg.asString()); } catch (Exception e) { argStrs.add("?"); }
                }
                SlowLog.getInstance().recordIfSlow(commandName, argStrs, durationNanos);
            }
        }
    }

    private static RedisMessage executeWithLock(String commandName, List<RedisMessage> args) {
        if (StripedLock.isGlobalWrite(commandName)) {
            stripedLock.lockAll();
            try {
                return doWrite(commandName, args);
            } finally {
                stripedLock.unlockAll();
            }
        }
        String key = StripedLock.extractKey(commandName, args);
        if (key != null) {
            stripedLock.lock(key);
            try {
                return doWrite(commandName, args);
            } finally {
                stripedLock.unlock(key);
            }
        }
        return doWrite(commandName, args);
    }

    private static RedisMessage doWrite(String commandName, List<RedisMessage> args) {
        if (!TransactionManager.isInTransaction()) {
            if (persistenceType == SDCSConfig.PersistenceType.RDB_AOF) {
                appendToAof(commandName, args);
            }
            notifyAfterWrite(commandName, args);
            markDirtyKeys(commandName, args);
        }

        if (MonitorManager.getInstance().hasListeners()) {
            MonitorManager.getInstance().broadcast(buildMonitorLine(commandName, args));
        }

        return dispatcher.dispatch(commandName, args);
    }

    private static String buildMonitorLine(String commandName, List<RedisMessage> args) {
        StringBuilder sb = new StringBuilder(commandName);
        for (RedisMessage arg : args) {
            sb.append(' ');
            try {
                String val = arg.asString();
                if (val.length() > 64) {
                    sb.append('"').append(val, 0, 64).append("...\"");
                } else {
                    sb.append('"').append(val).append('"');
                }
            } catch (Exception e) {
                sb.append("\"?\"");
            }
        }
        return sb.toString();
    }

    private static RedisMessage executeBlockingPop(String commandName, List<RedisMessage> args, Consumer<RedisMessage> deferredCallback) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for '" + commandName.toLowerCase() + "' command");
        }

        List<RedisMessage> keys = args.subList(0, args.size() - 1);
        long timeout;

        try {
            timeout = Long.parseLong(args.get(args.size() - 1).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR timeout is not a float or integer");
        }

        timeout = Math.max(0, (long) (timeout * 1000));

        MemoryStore store = databaseManager.getStore();

        String firstKey = keys.get(0).asString();
        stripedLock.lock(firstKey);
        try {
            for (RedisMessage keyMsg : keys) {
                String key = keyMsg.asString();
                List<byte[]> items = store.lrange(key, 0, 0);
                if (items != null && !items.isEmpty()) {
                    byte[] value = store.lpop(key);
                    return RedisMessage.array(
                            RedisMessage.bulkString(key.getBytes()),
                            RedisMessage.bulkString(value)
                    );
                }
            }

            if (deferredCallback == null || timeout == 0) {
                return RedisMessage.array((java.util.List<RedisMessage>) null);
            }

            BlockingHandler handler = (s, k) -> {
                stripedLock.lock(firstKey);
                try {
                    MemoryStore currentStore = databaseManager.getStore();
                    byte[] val = currentStore.lpop(k);
                    if (val != null) {
                        return RedisMessage.array(
                                RedisMessage.bulkString(k.getBytes()),
                                RedisMessage.bulkString(val)
                        );
                    }
                    return null;
                } finally {
                    stripedLock.unlock(firstKey);
                }
            };

            BlockingManager.getInstance().block(firstKey,
                    new BlockingManager.BlockingContext(timeout, handler, deferredCallback));
            return null;

        } finally {
            stripedLock.unlock(firstKey);
        }
    }

    private static RedisMessage executeBlockingMove(List<RedisMessage> args, Consumer<RedisMessage> deferredCallback) {
        if (args.size() != 5) {
            return RedisMessage.error("wrong number of arguments for 'blmove' command");
        }

        String source = args.get(0).asString();
        String destination = args.get(1).asString();
        String whereFrom = args.get(2).asString();
        String whereTo = args.get(3).asString();
        long timeout;

        try {
            timeout = Long.parseLong(args.get(4).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR timeout is not a float or integer");
        }

        timeout = Math.max(0, (long) (timeout * 1000));
        boolean fromLeft = "LEFT".equalsIgnoreCase(whereFrom);
        boolean toLeft = "LEFT".equalsIgnoreCase(whereTo);

        MemoryStore store = databaseManager.getStore();

        stripedLock.lock(source);
        try {
            byte[] value = store.lmove(source, destination, fromLeft, toLeft);
            if (value != null) {
                return RedisMessage.bulkString(value);
            }

            if (deferredCallback == null || timeout == 0) {
                return RedisMessage.bulkString((byte[]) null);
            }

            BlockingHandler handler = (s, k) -> {
                stripedLock.lock(source);
                try {
                    MemoryStore currentStore = databaseManager.getStore();
                    byte[] val = currentStore.lmove(source, destination, fromLeft, toLeft);
                    return val != null ? RedisMessage.bulkString(val) : null;
                } finally {
                    stripedLock.unlock(source);
                }
            };

            BlockingManager.getInstance().block(source,
                    new BlockingManager.BlockingContext(timeout, handler, deferredCallback));
            return null;

        } finally {
            stripedLock.unlock(source);
        }
    }

    private static RedisMessage executeBRPopLPush(List<RedisMessage> args, Consumer<RedisMessage> deferredCallback) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'brpoplpush' command");
        }

        String source = args.get(0).asString();
        String destination = args.get(1).asString();
        long timeout;

        try {
            timeout = Long.parseLong(args.get(2).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR timeout is not a float or integer");
        }

        timeout = Math.max(0, (long) (timeout * 1000));

        MemoryStore store = databaseManager.getStore();

        stripedLock.lock(source);
        try {
            byte[] value = store.lmove(source, destination, false, true);
            if (value != null) {
                return RedisMessage.bulkString(value);
            }

            if (deferredCallback == null || timeout == 0) {
                return RedisMessage.bulkString((byte[]) null);
            }

            BlockingHandler handler = (s, k) -> {
                stripedLock.lock(source);
                try {
                    MemoryStore currentStore = databaseManager.getStore();
                    byte[] val = currentStore.lmove(source, destination, false, true);
                    return val != null ? RedisMessage.bulkString(val) : null;
                } finally {
                    stripedLock.unlock(source);
                }
            };

            BlockingManager.getInstance().block(source,
                    new BlockingManager.BlockingContext(timeout, handler, deferredCallback));
            return null;

        } finally {
            stripedLock.unlock(source);
        }
    }

    private static RedisMessage executeBlockingZPop(String zpopCmd, List<RedisMessage> args, Consumer<RedisMessage> deferredCallback) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for '" + zpopCmd.toLowerCase() + "' command");
        }

        List<RedisMessage> keys = args.subList(0, args.size() - 1);
        long timeout;

        try {
            timeout = Long.parseLong(args.get(args.size() - 1).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR timeout is not a float or integer");
        }

        timeout = Math.max(0, (long) (timeout * 1000));

        MemoryStore store = databaseManager.getStore();

        String firstKey = keys.get(0).asString();
        stripedLock.lock(firstKey);
        try {
            for (RedisMessage keyMsg : keys) {
                String key = keyMsg.asString();
                if ("ZPOPMIN".equals(zpopCmd)) {
                    List<ZSetEntry> popped = store.zpopmin(key, 1);
                    if (!popped.isEmpty()) {
                        ZSetEntry entry = popped.get(0);
                        return RedisMessage.array(
                                RedisMessage.bulkString(key.getBytes()),
                                RedisMessage.bulkString(entry.member()),
                                RedisMessage.bulkString(String.valueOf(entry.score()).getBytes())
                        );
                    }
                } else {
                    List<ZSetEntry> popped = store.zpopmax(key, 1);
                    if (!popped.isEmpty()) {
                        ZSetEntry entry = popped.get(0);
                        return RedisMessage.array(
                                RedisMessage.bulkString(key.getBytes()),
                                RedisMessage.bulkString(entry.member()),
                                RedisMessage.bulkString(String.valueOf(entry.score()).getBytes())
                        );
                    }
                }
            }

            if (deferredCallback == null || timeout == 0) {
                return RedisMessage.array((java.util.List<RedisMessage>) null);
            }

            BlockingHandler handler = (s, k) -> {
                stripedLock.lock(firstKey);
                try {
                    MemoryStore currentStore = databaseManager.getStore();
                    if ("ZPOPMIN".equals(zpopCmd)) {
                        List<ZSetEntry> popped = currentStore.zpopmin(k, 1);
                        if (!popped.isEmpty()) {
                            ZSetEntry entry = popped.get(0);
                            return RedisMessage.array(
                                    RedisMessage.bulkString(k.getBytes()),
                                    RedisMessage.bulkString(entry.member()),
                                    RedisMessage.bulkString(String.valueOf(entry.score()).getBytes())
                            );
                        }
                    } else {
                        List<ZSetEntry> popped = currentStore.zpopmax(k, 1);
                        if (!popped.isEmpty()) {
                            ZSetEntry entry = popped.get(0);
                            return RedisMessage.array(
                                    RedisMessage.bulkString(k.getBytes()),
                                    RedisMessage.bulkString(entry.member()),
                                    RedisMessage.bulkString(String.valueOf(entry.score()).getBytes())
                            );
                        }
                    }
                    return null;
                } finally {
                    stripedLock.unlock(firstKey);
                }
            };

            BlockingManager.getInstance().block(firstKey,
                    new BlockingManager.BlockingContext(timeout, handler, deferredCallback));
            return null;

        } finally {
            stripedLock.unlock(firstKey);
        }
    }

    private static void notifyAfterWrite(String commandName, List<RedisMessage> args) {
        if ("LPUSH".equals(commandName) || "RPUSH".equals(commandName) || "RPOPLPUSH".equals(commandName)) {
            if (!args.isEmpty()) {
                BlockingManager.getInstance().notifyKey(args.get(0).asString());
            }
        }
        if ("ZADD".equals(commandName) || "GEOADD".equals(commandName)) {
            if (!args.isEmpty()) {
                BlockingManager.getInstance().notifyKey(args.get(0).asString());
            }
        }
    }

    private static void markDirtyKeys(String commandName, List<RedisMessage> args) {
        if (args.isEmpty()) return;
        String key = args.get(0).asString();
        TransactionManager.markDirty(key);
    }

    private static void appendToAof(String commandName, List<RedisMessage> args) {
        int currentDb = databaseManager.getCurrentDb();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(512);
        try {
            if (currentDb != lastAofDb) {
                String dbStr = String.valueOf(currentDb);
                String select = "*2\r\n$6\r\nSELECT\r\n$" + dbStr.length() + "\r\n" + dbStr + "\r\n";
                bos.write(select.getBytes());
                lastAofDb = currentDb;
            }
            bos.write(("*" + (args.size() + 1) + "\r\n").getBytes());
            bos.write(("$" + commandName.length() + "\r\n").getBytes());
            bos.write(commandName.getBytes());
            bos.write("\r\n".getBytes());
            for (RedisMessage arg : args) {
                byte[] data = arg.getData();
                bos.write(("$" + data.length + "\r\n").getBytes());
                bos.write(data);
                bos.write("\r\n".getBytes());
            }
            persistenceManager.appendAof(bos.toByteArray());
        } catch (java.io.IOException e) {
            logger.error("Error building AOF content: {}", e.getMessage(), e);
        }
    }

    public static MemoryStore getStore() {
        return databaseManager.getStore();
    }

    public static DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public static PersistenceManager getPersistenceManager() {
        return persistenceManager;
    }
}
