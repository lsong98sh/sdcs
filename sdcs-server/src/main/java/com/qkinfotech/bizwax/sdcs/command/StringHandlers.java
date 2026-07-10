package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class StringHandlers extends BaseHandler {

    public StringHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public RedisMessage handlePing(List<RedisMessage> args) {
        if (args.size() > 1) {
            return RedisMessage.error("ERR wrong number of arguments for 'ping' command");
        }
        if (args.isEmpty()) {
            return RedisMessage.simpleString("PONG");
        }
        return args.get(0);
    }

    public RedisMessage handleEcho(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'echo' command");
        }
        return args.get(0);
    }

    public RedisMessage handleSet(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'set' command");
        }
        String key = args.get(0).asString();
        byte[] value = args.get(1).getData();

        boolean nx = false, xx = false;
        long expireAtMs = -1;

        for (int i = 2; i < args.size(); i++) {
            String opt = args.get(i).asString().toUpperCase();
            switch (opt) {
                case "NX":
                    nx = true;
                    break;
                case "XX":
                    xx = true;
                    break;
                case "EX":
                    if (i + 1 < args.size()) {
                        long seconds = Long.parseLong(args.get(++i).asString());
                        expireAtMs = System.currentTimeMillis() + seconds * 1000;
                    }
                    break;
                case "PX":
                    if (i + 1 < args.size()) {
                        long milliseconds = Long.parseLong(args.get(++i).asString());
                        expireAtMs = System.currentTimeMillis() + milliseconds;
                    }
                    break;
                case "EXAT":
                    if (i + 1 < args.size()) {
                        long unixTimeSeconds = Long.parseLong(args.get(++i).asString());
                        expireAtMs = unixTimeSeconds * 1000;
                    }
                    break;
                case "PXAT":
                    if (i + 1 < args.size()) {
                        long unixTimeMs = Long.parseLong(args.get(++i).asString());
                        expireAtMs = unixTimeMs;
                    }
                    break;
                case "KEEPTTL":
                    break;
                default:
                    return RedisMessage.error("syntax error");
            }
        }

        if (nx && xx) {
            return RedisMessage.error("ERR NX and XX options at the same time are not compatible");
        }

        boolean success;
        if (nx) {
            success = store().setNX(key, value);
        } else if (xx) {
            success = store().setXX(key, value);
        } else {
            if (expireAtMs > 0) {
                store().put(key, value, expireAtMs);
            } else {
                store().put(key, value);
            }
            success = true;
        }

        if (nx || xx) {
            if (success) {
                if (expireAtMs > 0) {
                    store().put(key, value, expireAtMs);
                }
                return RedisMessage.simpleString("OK");
            } else {
                return RedisMessage.bulkString((byte[]) null);
            }
        }

        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handleGet(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'get' command");
        }
        String key = args.get(0).asString();
        byte[] value = store().get(key);
        if (value == null) {
            return RedisMessage.bulkString((byte[]) null);
        }
        return RedisMessage.bulkString(value);
    }

    public RedisMessage handleGetSet(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'getset' command");
        }
        String key = args.get(0).asString();
        byte[] value = args.get(1).getData();
        try {
            byte[] old = store().getSet(key, value);
            if (old == null) {
                return RedisMessage.bulkString((byte[]) null);
            }
            return RedisMessage.bulkString(old);
        } catch (IllegalArgumentException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public RedisMessage handleGetEx(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'getex' command");
        }
        String key = args.get(0).asString();
        long expireAtMs = -1;
        for (int i = 1; i < args.size(); i++) {
            String opt = args.get(i).asString().toUpperCase();
            switch (opt) {
                case "EX" -> {
                    if (i + 1 >= args.size()) return RedisMessage.error("ERR syntax error");
                    expireAtMs = System.currentTimeMillis() + Long.parseLong(args.get(++i).asString()) * 1000;
                }
                case "PX" -> {
                    if (i + 1 >= args.size()) return RedisMessage.error("ERR syntax error");
                    expireAtMs = System.currentTimeMillis() + Long.parseLong(args.get(++i).asString());
                }
                case "EXAT" -> {
                    if (i + 1 >= args.size()) return RedisMessage.error("ERR syntax error");
                    expireAtMs = Long.parseLong(args.get(++i).asString()) * 1000;
                }
                case "PXAT" -> {
                    if (i + 1 >= args.size()) return RedisMessage.error("ERR syntax error");
                    expireAtMs = Long.parseLong(args.get(++i).asString());
                }
                case "PERSIST" -> expireAtMs = -1;
            }
        }
        if (expireAtMs >= 0) {
            store().getEx(key, expireAtMs);
        }
        byte[] value = store().get(key);
        if (value == null) {
            return RedisMessage.bulkString((byte[]) null);
        }
        return RedisMessage.bulkString(value);
    }

    public RedisMessage handleGetDel(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'getdel' command");
        }
        String key = args.get(0).asString();
        byte[] value = store().getDel(key);
        if (value == null) {
            return RedisMessage.bulkString((byte[]) null);
        }
        return RedisMessage.bulkString(value);
    }

    public RedisMessage handleSetEx(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'setex' command");
        }
        String key = args.get(0).asString();
        long seconds;
        try {
            seconds = Long.parseLong(args.get(1).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR value is not an integer or out of range");
        }
        byte[] value = args.get(2).getData();
        store().set(key, value, seconds);
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handlePSetEx(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'psetex' command");
        }
        String key = args.get(0).asString();
        long millis;
        try {
            millis = Long.parseLong(args.get(1).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR value is not an integer or out of range");
        }
        byte[] value = args.get(2).getData();
        store().psetex(key, value, millis);
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handleMGet(List<RedisMessage> args) {
        if (args.isEmpty()) {
            return RedisMessage.error("wrong number of arguments for 'mget' command");
        }
        RedisMessage[] results = new RedisMessage[args.size()];
        for (int i = 0; i < args.size(); i++) {
            byte[] value = store().get(args.get(i).asString());
            results[i] = value != null ? RedisMessage.bulkString(value) : RedisMessage.bulkString((byte[]) null);
        }
        return RedisMessage.array(results);
    }

    public RedisMessage handleMSet(List<RedisMessage> args) {
        if (args.size() % 2 != 0) {
            return RedisMessage.error("wrong number of arguments for 'mset' command");
        }
        for (int i = 0; i < args.size(); i += 2) {
            store().put(args.get(i).asString(), args.get(i + 1).getData());
        }
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handleMSetNx(List<RedisMessage> args) {
        if (args.size() % 2 != 0 || args.isEmpty()) {
            return RedisMessage.error("wrong number of arguments for 'msetnx' command");
        }
        byte[][] keyValues = new byte[args.size()][];
        for (int i = 0; i < args.size(); i++) {
            keyValues[i] = args.get(i).getData();
        }
        boolean result = store().msetNx(keyValues);
        return RedisMessage.integer(result ? 1 : 0);
    }

    public RedisMessage handleIncr(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'incr' command");
        }
        try {
            long result = store().incr(args.get(0).asString());
            return RedisMessage.integer(result);
        } catch (IllegalArgumentException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public RedisMessage handleIncrBy(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'incrby' command");
        }
        try {
            long delta = Long.parseLong(args.get(1).asString());
            long result = store().incrBy(args.get(0).asString(), delta);
            return RedisMessage.integer(result);
        } catch (IllegalArgumentException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public RedisMessage handleIncrByFloat(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'incrbyfloat' command");
        }
        try {
            double delta = Double.parseDouble(args.get(1).asString());
            double result = store().incrByFloat(args.get(0).asString(), delta);
            String resultStr = (result % 1 == 0) ? String.valueOf((long) result) : String.valueOf(result);
            return RedisMessage.bulkString(resultStr.getBytes());
        } catch (IllegalArgumentException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public RedisMessage handleDecr(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'decr' command");
        }
        try {
            long result = store().decr(args.get(0).asString());
            return RedisMessage.integer(result);
        } catch (IllegalArgumentException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public RedisMessage handleDecrBy(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'decrby' command");
        }
        try {
            long delta = Long.parseLong(args.get(1).asString());
            long result = store().decrBy(args.get(0).asString(), delta);
            return RedisMessage.integer(result);
        } catch (IllegalArgumentException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public RedisMessage handleAppend(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'append' command");
        }
        long newLen = store().append(args.get(0).asString(), args.get(1).getData());
        return RedisMessage.integer(newLen);
    }

    public RedisMessage handleStrLen(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'strlen' command");
        }
        return RedisMessage.integer(store().strLen(args.get(0).asString()));
    }

    public RedisMessage handleGetRange(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'getrange' command");
        }
        String key = args.get(0).asString();
        long start = Long.parseLong(args.get(1).asString());
        long end = Long.parseLong(args.get(2).asString());
        byte[] result = store().getRange(key, start, end);
        if (result == null) {
            return RedisMessage.bulkString((byte[]) null);
        }
        return RedisMessage.bulkString(result);
    }

    public RedisMessage handleSetRange(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'setrange' command");
        }
        String key = args.get(0).asString();
        long offset = Long.parseLong(args.get(1).asString());
        byte[] value = args.get(2).getData();
        return RedisMessage.integer(store().setRange(key, offset, value));
    }

    public RedisMessage handlePFAdd(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'pfadd' command");
        }
        String key = args.get(0).asString();
        byte[][] elements = new byte[args.size() - 1][];
        for (int i = 1; i < args.size(); i++) {
            elements[i - 1] = args.get(i).getData();
        }
        return RedisMessage.integer(store().pfadd(key, elements));
    }

    public RedisMessage handlePFCount(List<RedisMessage> args) {
        if (args.size() < 1) {
            return RedisMessage.error("wrong number of arguments for 'pfcount' command");
        }
        String[] keys = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            keys[i] = args.get(i).asString();
        }
        return RedisMessage.integer(store().pfcount(keys));
    }

    public RedisMessage handlePFMerge(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'pfmerge' command");
        }
        String destKey = args.get(0).asString();
        String[] srcKeys = new String[args.size() - 1];
        for (int i = 1; i < args.size(); i++) {
            srcKeys[i - 1] = args.get(i).asString();
        }
        return RedisMessage.simpleString(store().pfmerge(destKey, srcKeys));
    }

    public RedisMessage handleSetBit(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'setbit' command");
        }
        String key = args.get(0).asString();
        long offset;
        int value;
        try {
            offset = Long.parseLong(args.get(1).asString());
            value = Integer.parseInt(args.get(2).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR bit is not an integer or out of range");
        }
        if (value != 0 && value != 1) {
            return RedisMessage.error("ERR bit is not an integer or out of range");
        }
        if (offset < 0) {
            return RedisMessage.error("ERR bit offset is not an integer or out of range");
        }
        int oldBit = store().setbit(key, offset, value);
        return RedisMessage.integer(oldBit);
    }

    public RedisMessage handleGetBit(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'getbit' command");
        }
        String key = args.get(0).asString();
        long offset;
        try {
            offset = Long.parseLong(args.get(1).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR bit offset is not an integer or out of range");
        }
        if (offset < 0) {
            return RedisMessage.error("ERR bit offset is not an integer or out of range");
        }
        return RedisMessage.integer(store().getbit(key, offset));
    }

    public RedisMessage handleBitCount(List<RedisMessage> args) {
        if (args.size() < 1 || args.size() > 3) {
            return RedisMessage.error("wrong number of arguments for 'bitcount' command");
        }
        String key = args.get(0).asString();
        int start = 0;
        int end = -1;
        if (args.size() >= 3) {
            try {
                start = Integer.parseInt(args.get(1).asString());
                end = Integer.parseInt(args.get(2).asString());
            } catch (NumberFormatException e) {
                return RedisMessage.error("ERR value is not an integer or out of range");
            }
        }
        return RedisMessage.integer(store().bitcount(key, start, end));
    }

    public RedisMessage handleBitPos(List<RedisMessage> args) {
        if (args.size() < 2 || args.size() > 4) {
            return RedisMessage.error("wrong number of arguments for 'bitpos' command");
        }
        String key = args.get(0).asString();
        int bit;
        try {
            bit = Integer.parseInt(args.get(1).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR bit is not an integer or out of range");
        }
        if (bit != 0 && bit != 1) {
            return RedisMessage.error("ERR bit is not an integer or out of range");
        }
        long start = 0;
        long end = -1;
        if (args.size() >= 3) {
            try {
                start = Long.parseLong(args.get(2).asString());
            } catch (NumberFormatException e) {
                return RedisMessage.error("ERR value is not an integer or out of range");
            }
        }
        if (args.size() >= 4) {
            try {
                end = Long.parseLong(args.get(3).asString());
            } catch (NumberFormatException e) {
                return RedisMessage.error("ERR value is not an integer or out of range");
            }
        }
        return RedisMessage.integer(store().bitpos(key, bit, start, end));
    }

    public RedisMessage handleBitOp(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("wrong number of arguments for 'bitop' command");
        }
        String operation = args.get(0).asString();
        String destKey = args.get(1).asString();
        String[] srcKeys = new String[args.size() - 2];
        for (int i = 2; i < args.size(); i++) {
            srcKeys[i - 2] = args.get(i).asString();
        }
        try {
            long resultLen = store().bitop(operation, destKey, srcKeys);
            return RedisMessage.integer(resultLen);
        } catch (IllegalArgumentException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("PING", this::handlePing);
        registry.put("ECHO", this::handleEcho);
        registry.put("SET", this::handleSet);
        registry.put("GET", this::handleGet);
        registry.put("GETSET", this::handleGetSet);
        registry.put("GETEX", this::handleGetEx);
        registry.put("GETDEL", this::handleGetDel);
        registry.put("SETEX", this::handleSetEx);
        registry.put("PSETEX", this::handlePSetEx);
        registry.put("MGET", this::handleMGet);
        registry.put("MSET", this::handleMSet);
        registry.put("MSETNX", this::handleMSetNx);
        registry.put("INCR", this::handleIncr);
        registry.put("INCRBY", this::handleIncrBy);
        registry.put("INCRBYFLOAT", this::handleIncrByFloat);
        registry.put("DECR", this::handleDecr);
        registry.put("DECRBY", this::handleDecrBy);
        registry.put("APPEND", this::handleAppend);
        registry.put("STRLEN", this::handleStrLen);
        registry.put("GETRANGE", this::handleGetRange);
        registry.put("SETRANGE", this::handleSetRange);
        registry.put("PFADD", this::handlePFAdd);
        registry.put("PFCOUNT", this::handlePFCount);
        registry.put("PFMERGE", this::handlePFMerge);
        registry.put("SETBIT", this::handleSetBit);
        registry.put("GETBIT", this::handleGetBit);
        registry.put("BITCOUNT", this::handleBitCount);
        registry.put("BITPOS", this::handleBitPos);
        registry.put("BITOP", this::handleBitOp);
    }
}
