package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.common.RedisData;
import com.qkinfotech.bizwax.sdcs.common.RedisDataType;
import com.qkinfotech.bizwax.sdcs.common.RedisList;
import com.qkinfotech.bizwax.sdcs.common.RedisSet;
import com.qkinfotech.bizwax.sdcs.common.RedisZSet;
import com.qkinfotech.bizwax.sdcs.common.RedisHash;
import com.qkinfotech.bizwax.sdcs.persistence.RdbDecoder;
import com.qkinfotech.bizwax.sdcs.persistence.RdbEncoder;
import com.qkinfotech.bizwax.sdcs.persistence.RdbType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Function;

public class KeyHandlers extends BaseHandler {

    public KeyHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public RedisMessage handleDel(List<RedisMessage> args) {
        if (args.isEmpty()) {
            return error("wrong number of arguments for 'del' command");
        }
        long deleted = 0;
        for (RedisMessage arg : args) {
            if (store().remove(arg.asString())) {
                deleted++;
            }
        }
        return integer(deleted);
    }

    public RedisMessage handleExists(List<RedisMessage> args) {
        if (args.isEmpty()) {
            return error("wrong number of arguments for 'exists' command");
        }
        long count = 0;
        for (RedisMessage arg : args) {
            if (store().exists(arg.asString())) {
                count++;
            }
        }
        return integer(count);
    }

    public RedisMessage handleKeys(List<RedisMessage> args) {
        if (args.size() != 1) {
            return error("wrong number of arguments for 'keys' command");
        }
        String pattern = args.get(0).asString();
        String[] keys = store().keys(pattern);
        RedisMessage[] elements = new RedisMessage[keys.length];
        for (int i = 0; i < keys.length; i++) {
            elements[i] = RedisMessage.bulkString(keys[i]);
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleType(List<RedisMessage> args) {
        if (args.size() != 1) {
            return error("wrong number of arguments for 'type' command");
        }
        return RedisMessage.simpleString(store().type(args.get(0).asString()));
    }

    public RedisMessage handleExpire(List<RedisMessage> args) {
        if (args.size() < 2) {
            return error("wrong number of arguments for 'expire' command");
        }
        String key = args.get(0).asString();
        long seconds = Long.parseLong(args.get(1).asString());
        return integer(store().expire(key, seconds));
    }

    public RedisMessage handlePExpire(List<RedisMessage> args) {
        if (args.size() < 2) {
            return error("wrong number of arguments for 'pexpire' command");
        }
        String key = args.get(0).asString();
        long milliseconds = Long.parseLong(args.get(1).asString());
        return integer(store().pexpire(key, milliseconds));
    }

    public RedisMessage handleExpireAt(List<RedisMessage> args) {
        if (args.size() < 2) {
            return error("wrong number of arguments for 'expireat' command");
        }
        String key = args.get(0).asString();
        long unixTimeSeconds = Long.parseLong(args.get(1).asString());
        return integer(store().expireAt(key, unixTimeSeconds));
    }

    public RedisMessage handlePExpireAt(List<RedisMessage> args) {
        if (args.size() < 2) {
            return error("wrong number of arguments for 'pexpireat' command");
        }
        String key = args.get(0).asString();
        long unixTimeMs = Long.parseLong(args.get(1).asString());
        return integer(store().pexpireAt(key, unixTimeMs));
    }

    public RedisMessage handleTTL(List<RedisMessage> args) {
        if (args.size() != 1) {
            return error("wrong number of arguments for 'ttl' command");
        }
        return integer(store().ttl(args.get(0).asString()));
    }

    public RedisMessage handlePTTL(List<RedisMessage> args) {
        if (args.size() != 1) {
            return error("wrong number of arguments for 'pttl' command");
        }
        return integer(store().pttl(args.get(0).asString()));
    }

    public RedisMessage handlePersist(List<RedisMessage> args) {
        if (args.size() != 1) {
            return error("wrong number of arguments for 'persist' command");
        }
        return integer(store().persist(args.get(0).asString()));
    }

    public RedisMessage handleRename(List<RedisMessage> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'rename' command");
        }
        String key = args.get(0).asString();
        String newKey = args.get(1).asString();
        if (!store().rename(key, newKey)) {
            return error("ERR no such key");
        }
        return ok();
    }

    public RedisMessage handleRenameNx(List<RedisMessage> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'renamenx' command");
        }
        String key = args.get(0).asString();
        String newKey = args.get(1).asString();
        if (!store().exists(key)) {
            return error("ERR no such key");
        }
        return integer(store().renamenx(key, newKey) ? 1 : 0);
    }

    public RedisMessage handleCopy(List<RedisMessage> args) {
        if (args.size() < 2) {
            return error("wrong number of arguments for 'copy' command");
        }
        String source = args.get(0).asString();
        String dest = args.get(1).asString();
        boolean replace = false;
        for (int i = 2; i < args.size(); i++) {
            if (args.get(i).asString().equalsIgnoreCase("REPLACE")) {
                replace = true;
            }
        }
        if (!replace && store().exists(dest)) {
            return integer(0);
        }
        return integer(store().copy(source, dest) ? 1 : 0);
    }

    public RedisMessage handleMove(List<RedisMessage> args) {
        if (args.size() != 2) {
            return error("wrong number of arguments for 'move' command");
        }
        String key = args.get(0).asString();
        int targetDb;
        try {
            targetDb = Integer.parseInt(args.get(1).asString());
        } catch (NumberFormatException e) {
            return error("ERR value is not an integer or out of range");
        }
        return integer(store().move(key, databaseManager, targetDb) ? 1 : 0);
    }

    public RedisMessage handleTouch(List<RedisMessage> args) {
        if (args.isEmpty()) {
            return error("wrong number of arguments for 'touch' command");
        }
        String[] keys = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            keys[i] = args.get(i).asString();
        }
        return integer(store().touch(keys));
    }

    private static long parseCursor(RedisMessage msg) {
        return Long.parseLong(msg.asString());
    }

    private static String parsePattern(List<RedisMessage> args, int start) {
        for (int i = start; i < args.size() - 1; i++) {
            if (args.get(i).asString().equalsIgnoreCase("MATCH")) {
                return args.get(i + 1).asString();
            }
        }
        return null;
    }

    private static long parseCount(List<RedisMessage> args, int start) {
        for (int i = start; i < args.size() - 1; i++) {
            if (args.get(i).asString().equalsIgnoreCase("COUNT")) {
                return Long.parseLong(args.get(i + 1).asString());
            }
        }
        return 10;
    }

    public RedisMessage handleScan(List<RedisMessage> args) {
        if (args.size() < 1) {
            return error("wrong number of arguments for 'scan' command");
        }
        long cursor = parseCursor(args.get(0));
        String pattern = parsePattern(args, 1);
        long count = parseCount(args, 1);

        List<Object> result = store().scan(cursor, pattern, count);
        long nextCursor = (Long) result.get(0);
        String[] keys = (String[]) result.get(1);

        RedisMessage[] elements = new RedisMessage[keys.length];
        for (int i = 0; i < keys.length; i++) {
            elements[i] = RedisMessage.bulkString(keys[i].getBytes());
        }
        return RedisMessage.array(
                RedisMessage.bulkString(String.valueOf(nextCursor).getBytes()),
                RedisMessage.array(elements)
        );
    }

    public RedisMessage handleSScan(List<RedisMessage> args) {
        if (args.size() < 2) {
            return error("wrong number of arguments for 'sscan' command");
        }
        String key = args.get(0).asString();
        long cursor = parseCursor(args.get(1));
        String pattern = parsePattern(args, 2);
        long count = parseCount(args, 2);

        List<Object> result = store().sscan(key, cursor, pattern, count);
        long nextCursor = (Long) result.get(0);
        byte[][] members = (byte[][]) result.get(1);

        RedisMessage[] elements = new RedisMessage[members.length];
        for (int i = 0; i < members.length; i++) {
            elements[i] = RedisMessage.bulkString(members[i]);
        }
        return RedisMessage.array(
                RedisMessage.bulkString(String.valueOf(nextCursor).getBytes()),
                RedisMessage.array(elements)
        );
    }

    public RedisMessage handleHScan(List<RedisMessage> args) {
        if (args.size() < 2) {
            return error("wrong number of arguments for 'hscan' command");
        }
        String key = args.get(0).asString();
        long cursor = parseCursor(args.get(1));
        String pattern = parsePattern(args, 2);
        long count = parseCount(args, 2);

        List<Object> result = store().hscan(key, cursor, pattern, count);
        long nextCursor = (Long) result.get(0);
        byte[][] entries = (byte[][]) result.get(1);

        RedisMessage[] elements = new RedisMessage[entries.length];
        for (int i = 0; i < entries.length; i++) {
            elements[i] = RedisMessage.bulkString(entries[i]);
        }
        return RedisMessage.array(
                RedisMessage.bulkString(String.valueOf(nextCursor).getBytes()),
                RedisMessage.array(elements)
        );
    }

    public RedisMessage handleZScan(List<RedisMessage> args) {
        if (args.size() < 2) {
            return error("wrong number of arguments for 'zscan' command");
        }
        String key = args.get(0).asString();
        long cursor = parseCursor(args.get(1));
        String pattern = parsePattern(args, 2);
        long count = parseCount(args, 2);

        List<Object> result = store().zscan(key, cursor, pattern, count);
        long nextCursor = (Long) result.get(0);
        byte[][] elements = (byte[][]) result.get(1);

        RedisMessage[] msgElements = new RedisMessage[elements.length];
        for (int i = 0; i < elements.length; i++) {
            msgElements[i] = RedisMessage.bulkString(elements[i]);
        }
        return RedisMessage.array(
                RedisMessage.bulkString(String.valueOf(nextCursor).getBytes()),
                RedisMessage.array(msgElements)
        );
    }

    public RedisMessage handleSort(List<RedisMessage> args) {
        if (args.size() < 1) {
            return error("wrong number of arguments for 'sort' command");
        }

        String key = args.get(0).asString();
        boolean alpha = false;
        boolean desc = false;
        boolean hasLimit = false;
        long limitOffset = 0;
        long limitCount = -1;
        String storeDest = null;

        for (int i = 1; i < args.size(); i++) {
            String opt = args.get(i).asString().toUpperCase();
            switch (opt) {
                case "ALPHA" -> alpha = true;
                case "ASC" -> desc = false;
                case "DESC" -> desc = true;
                case "LIMIT" -> {
                    if (i + 2 < args.size()) {
                        hasLimit = true;
                        limitOffset = Long.parseLong(args.get(++i).asString());
                        limitCount = Long.parseLong(args.get(++i).asString());
                    }
                }
                case "BY" -> {
                    if (i + 1 < args.size()) {
                        i++;
                    }
                }
                case "STORE" -> {
                    if (i + 1 < args.size()) {
                        storeDest = args.get(++i).asString();
                    }
                }
            }
        }

        try {
            List<byte[]> result = store().sort(key, alpha, desc, hasLimit, limitOffset, limitCount);
            if (result == null) {
                return RedisMessage.array(new RedisMessage[0]);
            }

            if (storeDest != null) {
                store().remove(storeDest);
                store().rpush(storeDest, result.toArray(new byte[0][]));
                return integer(result.size());
            }

            RedisMessage[] elements = new RedisMessage[result.size()];
            for (int i = 0; i < result.size(); i++) {
                elements[i] = RedisMessage.bulkString(result.get(i));
            }
            return RedisMessage.array(elements);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    public RedisMessage handleDump(List<RedisMessage> args) {
        if (args.size() != 1) {
            return error("wrong number of arguments for 'dump' command");
        }
        String key = args.get(0).asString();
        RedisData entry = store().getEntry(key);
        if (entry == null) {
            return nullBulkString();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            RdbEncoder encoder = new RdbEncoder(baos);
            int type = switch (entry.getType()) {
                case STRING -> RdbType.STRING;
                case LIST -> RdbType.LIST;
                case SET -> RdbType.SET;
                case ZSET -> RdbType.ZSET;
                case HASH -> RdbType.HASH;
                default -> RdbType.STRING;
            };
            encoder.writeByte(type);
            switch (entry.getType()) {
                case STRING -> encoder.writeString(entry.getValue(byte[].class));
                case LIST -> encoder.writeList(entry.getValue(RedisList.class));
                case SET -> encoder.writeSet(entry.getValue(RedisSet.class));
                case ZSET -> encoder.writeZSet(entry.getValue(RedisZSet.class));
                case HASH -> encoder.writeHash(entry.getValue(RedisHash.class));
                case STREAM -> {}
            }
        } catch (IOException e) {
            return error("ERR " + e.getMessage());
        }
        return RedisMessage.bulkString(baos.toByteArray());
    }

    public RedisMessage handleRestore(List<RedisMessage> args) {
        if (args.size() < 3) {
            return error("wrong number of arguments for 'restore' command");
        }
        String key = args.get(0).asString();
        long ttl;
        try {
            ttl = Long.parseLong(args.get(1).asString());
        } catch (NumberFormatException e) {
            return error("ERR value is not an integer or out of range");
        }
        byte[] data = args.get(2).getData();
        if (data == null) {
            return error("ERR DUMP payload version or checksum are wrong");
        }
        boolean replace = false;
        for (int i = 3; i < args.size(); i++) {
            if (args.get(i).asString().equalsIgnoreCase("REPLACE")) {
                replace = true;
            }
        }
        if (!replace && store().exists(key)) {
            return error("BUSYKEY Target key name already exists.");
        }
        if (replace) {
            store().remove(key);
        }
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            RdbDecoder decoder = new RdbDecoder(bais);
            int type = decoder.readByte();
            RedisData redisData = switch (type) {
                case RdbType.STRING -> {
                    byte[] val = decoder.readString();
                    yield new RedisData(RedisDataType.STRING, val);
                }
                case RdbType.LIST -> {
                    RedisList val = decoder.readList();
                    yield new RedisData(RedisDataType.LIST, val);
                }
                case RdbType.SET -> {
                    RedisSet val = decoder.readSet();
                    yield new RedisData(RedisDataType.SET, val);
                }
                case RdbType.ZSET -> {
                    RedisZSet val = decoder.readZSet();
                    yield new RedisData(RedisDataType.ZSET, val);
                }
                case RdbType.HASH -> {
                    RedisHash val = decoder.readHash();
                    yield new RedisData(RedisDataType.HASH, val);
                }
                default -> throw new IOException("ERR DUMP payload version or checksum are wrong");
            };
            if (ttl > 0) {
                redisData.setExpireAt(System.currentTimeMillis() + ttl);
            }
            store().getStore().put(key, redisData);
            return ok();
        } catch (IOException e) {
            return error("ERR " + e.getMessage());
        }
    }

    public RedisMessage handleRandomKey(List<RedisMessage> args) {
        if (!args.isEmpty()) {
            return error("wrong number of arguments for 'randomkey' command");
        }
        String key = store().randomKey();
        return key != null ? RedisMessage.bulkString(key.getBytes()) : nullBulkString();
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("DEL", this::handleDel);
        registry.put("EXISTS", this::handleExists);
        registry.put("KEYS", this::handleKeys);
        registry.put("TYPE", this::handleType);
        registry.put("EXPIRE", this::handleExpire);
        registry.put("PEXPIRE", this::handlePExpire);
        registry.put("EXPIREAT", this::handleExpireAt);
        registry.put("PEXPIREAT", this::handlePExpireAt);
        registry.put("TTL", this::handleTTL);
        registry.put("PTTL", this::handlePTTL);
        registry.put("PERSIST", this::handlePersist);
        registry.put("RENAME", this::handleRename);
        registry.put("RENAMENX", this::handleRenameNx);
        registry.put("RANDOMKEY", this::handleRandomKey);
        registry.put("COPY", this::handleCopy);
        registry.put("MOVE", this::handleMove);
        registry.put("TOUCH", this::handleTouch);
        registry.put("SCAN", this::handleScan);
        registry.put("SSCAN", this::handleSScan);
        registry.put("HSCAN", this::handleHScan);
        registry.put("ZSCAN", this::handleZScan);
        registry.put("SORT", this::handleSort);
        registry.put("DUMP", this::handleDump);
        registry.put("RESTORE", this::handleRestore);
    }
}
