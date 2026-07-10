package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;

public class ListHandlers extends BaseHandler {

    public ListHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public RedisMessage handleLPush(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'lpush' command");
        }
        String key = args.get(0).asString();
        byte[][] values = new byte[args.size() - 1][];
        for (int i = 1; i < args.size(); i++) {
            values[i - 1] = args.get(i).getData();
        }
        long result = store().lpush(key, values);
        if (result == -1) {
            return RedisMessage.error("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return RedisMessage.integer(result);
    }

    public RedisMessage handleRPush(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'rpush' command");
        }
        String key = args.get(0).asString();
        byte[][] values = new byte[args.size() - 1][];
        for (int i = 1; i < args.size(); i++) {
            values[i - 1] = args.get(i).getData();
        }
        long result = store().rpush(key, values);
        if (result == -1) {
            return RedisMessage.error("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return RedisMessage.integer(result);
    }

    public RedisMessage handleLPushX(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'lpushx' command");
        }
        String key = args.get(0).asString();
        long total = 0;
        for (int i = 1; i < args.size(); i++) {
            total = store().lpushX(key, args.get(i).getData());
        }
        return RedisMessage.integer(total);
    }

    public RedisMessage handleRPushX(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'rpushx' command");
        }
        String key = args.get(0).asString();
        long total = 0;
        for (int i = 1; i < args.size(); i++) {
            total = store().rpushX(key, args.get(i).getData());
        }
        return RedisMessage.integer(total);
    }

    public RedisMessage handleLPop(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'lpop' command");
        }
        byte[] value = store().lpop(args.get(0).asString());
        return value != null ? RedisMessage.bulkString(value) : RedisMessage.bulkString((byte[]) null);
    }

    public RedisMessage handleRPop(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'rpop' command");
        }
        byte[] value = store().rpop(args.get(0).asString());
        return value != null ? RedisMessage.bulkString(value) : RedisMessage.bulkString((byte[]) null);
    }

    public RedisMessage handleBLPop(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'blpop' command");
        }
        String key = args.get(0).asString();
        long timeout;
        try {
            timeout = Long.parseLong(args.get(args.size() - 1).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR timeout is not a float or integer");
        }
        byte[] value = store().lpop(key);
        if (value != null) {
            return RedisMessage.array(
                    RedisMessage.bulkString(key.getBytes()),
                    RedisMessage.bulkString(value)
            );
        }
        return RedisMessage.array((java.util.List<RedisMessage>) null);
    }

    public RedisMessage handleBRPop(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'brpop' command");
        }
        String key = args.get(0).asString();
        byte[] value = store().rpop(key);
        if (value != null) {
            return RedisMessage.array(
                    RedisMessage.bulkString(key.getBytes()),
                    RedisMessage.bulkString(value)
            );
        }
        return RedisMessage.array((java.util.List<RedisMessage>) null);
    }

    public RedisMessage handleLLen(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'llen' command");
        }
        return RedisMessage.integer(store().llen(args.get(0).asString()));
    }

    public RedisMessage handleLIndex(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'lindex' command");
        }
        String key = args.get(0).asString();
        long index = Long.parseLong(args.get(1).asString());
        byte[] value = store().lindex(key, index);
        return value != null ? RedisMessage.bulkString(value) : RedisMessage.bulkString((byte[]) null);
    }

    public RedisMessage handleLRange(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'lrange' command");
        }
        String key = args.get(0).asString();
        long start = Long.parseLong(args.get(1).asString());
        long stop = Long.parseLong(args.get(2).asString());
        List<byte[]> values = store().lrange(key, start, stop);
        RedisMessage[] elements = new RedisMessage[values.size()];
        for (int i = 0; i < values.size(); i++) {
            elements[i] = RedisMessage.bulkString(values.get(i));
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleLSet(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'lset' command");
        }
        String key = args.get(0).asString();
        long index = Long.parseLong(args.get(1).asString());
        byte[] value = args.get(2).getData();
        long result = store().lset(key, index, value);
        if (result == 0) {
            return RedisMessage.error("ERR index out of range");
        }
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handleLTrim(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'ltrim' command");
        }
        String key = args.get(0).asString();
        long start = Long.parseLong(args.get(1).asString());
        long stop = Long.parseLong(args.get(2).asString());
        store().ltrim(key, start, stop);
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handleLRem(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'lrem' command");
        }
        String key = args.get(0).asString();
        long count = Long.parseLong(args.get(1).asString());
        byte[] value = args.get(2).getData();
        return RedisMessage.integer(store().lrem(key, count, value));
    }

    public RedisMessage handleLInsert(List<RedisMessage> args) {
        if (args.size() != 4) {
            return RedisMessage.error("wrong number of arguments for 'linsert' command");
        }
        String key = args.get(0).asString();
        String where = args.get(1).asString().toUpperCase();
        boolean before = "BEFORE".equals(where);
        if (!"BEFORE".equals(where) && !"AFTER".equals(where)) {
            return RedisMessage.error("ERR syntax error");
        }
        byte[] pivot = args.get(2).getData();
        byte[] value = args.get(3).getData();
        return RedisMessage.integer(store().linsert(key, before, pivot, value));
    }

    public RedisMessage handleLPOS(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'lpos' command");
        }
        String key = args.get(0).asString();
        byte[] value = args.get(1).getData();
        int rank = 1, count = 0, maxlen = 0;
        for (int i = 2; i < args.size(); i++) {
            String opt = args.get(i).asString().toUpperCase();
            switch (opt) {
                case "RANK" -> { if (++i < args.size()) rank = Integer.parseInt(args.get(i).asString()); }
                case "COUNT" -> { if (++i < args.size()) count = Integer.parseInt(args.get(i).asString()); }
                case "MAXLEN" -> { if (++i < args.size()) maxlen = Integer.parseInt(args.get(i).asString()); }
            }
        }
        List<Integer> positions = store().lpos(key, value, rank, count, maxlen);
        if (count > 0) {
            RedisMessage[] result = new RedisMessage[positions.size()];
            for (int j = 0; j < positions.size(); j++) {
                result[j] = RedisMessage.integer(positions.get(j));
            }
            return RedisMessage.array(result);
        }
        return positions.isEmpty() ? RedisMessage.bulkString((byte[]) null) : RedisMessage.integer(positions.get(0));
    }

    public RedisMessage handleLMove(List<RedisMessage> args) {
        if (args.size() != 4) {
            return RedisMessage.error("wrong number of arguments for 'lmove' command");
        }
        String source = args.get(0).asString();
        String dest = args.get(1).asString();
        boolean fromLeft = args.get(2).asString().equalsIgnoreCase("LEFT");
        boolean toLeft = args.get(3).asString().equalsIgnoreCase("LEFT");
        byte[] result = store().lmove(source, dest, fromLeft, toLeft);
        return result != null ? RedisMessage.bulkString(result) : RedisMessage.bulkString((byte[]) null);
    }

    public RedisMessage handleRPopLPush(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'rpoplpush' command");
        }
        String source = args.get(0).asString();
        String destination = args.get(1).asString();
        byte[] value = store().rpoplpush(source, destination);
        return value != null ? RedisMessage.bulkString(value) : RedisMessage.bulkString((byte[]) null);
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("LPUSH", this::handleLPush);
        registry.put("RPUSH", this::handleRPush);
        registry.put("LPUSHX", this::handleLPushX);
        registry.put("RPUSHX", this::handleRPushX);
        registry.put("LPOP", this::handleLPop);
        registry.put("RPOP", this::handleRPop);
        registry.put("BLPOP", this::handleBLPop);
        registry.put("BRPOP", this::handleBRPop);
        registry.put("LLEN", this::handleLLen);
        registry.put("LINDEX", this::handleLIndex);
        registry.put("LRANGE", this::handleLRange);
        registry.put("LSET", this::handleLSet);
        registry.put("LTRIM", this::handleLTrim);
        registry.put("LREM", this::handleLRem);
        registry.put("LINSERT", this::handleLInsert);
        registry.put("LPOS", this::handleLPOS);
        registry.put("LMOVE", this::handleLMove);
        registry.put("RPOPLPUSH", this::handleRPopLPush);
    }
}
