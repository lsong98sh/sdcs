package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.common.RedisHash;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.Random;
import java.util.ArrayList;
import java.util.function.Function;

public class HashHandlers extends BaseHandler {

    public HashHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public RedisMessage handleHSet(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'hset' command");
        }
        String key = args.get(0).asString();
        String field = args.get(1).asString();
        byte[] value = args.get(2).getData();
        long result = store().hset(key, field, value);
        if (result == -1) {
            return RedisMessage.error("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return RedisMessage.integer(result);
    }

    public RedisMessage handleHGet(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'hget' command");
        }
        String key = args.get(0).asString();
        String field = args.get(1).asString();
        byte[] value = store().hget(key, field);
        return value != null ? RedisMessage.bulkString(value) : RedisMessage.bulkString((byte[]) null);
    }

    public RedisMessage handleHMSet(List<RedisMessage> args) {
        if (args.size() < 3 || args.size() % 2 == 0) {
            return RedisMessage.error("wrong number of arguments for 'hmset' command");
        }
        String key = args.get(0).asString();
        Map<String, byte[]> fieldValues = new HashMap<>();
        for (int i = 1; i < args.size(); i += 2) {
            fieldValues.put(args.get(i).asString(), args.get(i + 1).getData());
        }
        store().hmset(key, fieldValues);
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handleHMGet(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'hmget' command");
        }
        String key = args.get(0).asString();
        String[] fields = new String[args.size() - 1];
        for (int i = 1; i < args.size(); i++) {
            fields[i - 1] = args.get(i).asString();
        }
        List<byte[]> values = store().hmget(key, fields);
        RedisMessage[] elements = new RedisMessage[values.size()];
        for (int i = 0; i < values.size(); i++) {
            byte[] v = values.get(i);
            elements[i] = v != null ? RedisMessage.bulkString(v) : RedisMessage.bulkString((byte[]) null);
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleHGetAll(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'hgetall' command");
        }
        Map<String, byte[]> map = store().hgetAll(args.get(0).asString());
        List<RedisMessage> elements = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : map.entrySet()) {
            elements.add(RedisMessage.bulkString(entry.getKey().getBytes()));
            elements.add(RedisMessage.bulkString(entry.getValue()));
        }
        return RedisMessage.array(elements.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleHDel(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'hdel' command");
        }
        String key = args.get(0).asString();
        String[] fields = new String[args.size() - 1];
        for (int i = 1; i < args.size(); i++) {
            fields[i - 1] = args.get(i).asString();
        }
        return RedisMessage.integer(store().hdel(key, fields));
    }

    public RedisMessage handleHLen(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'hlen' command");
        }
        return RedisMessage.integer(store().hlen(args.get(0).asString()));
    }

    public RedisMessage handleHExists(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'hexists' command");
        }
        String key = args.get(0).asString();
        String field = args.get(1).asString();
        return RedisMessage.integer(store().hexists(key, field) ? 1 : 0);
    }

    public RedisMessage handleHKeys(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'hkeys' command");
        }
        Set<String> keys = store().hkeys(args.get(0).asString());
        RedisMessage[] elements = new RedisMessage[keys.size()];
        int i = 0;
        for (String key : keys) {
            elements[i++] = RedisMessage.bulkString(key.getBytes());
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleHVals(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'hvals' command");
        }
        List<byte[]> values = store().hvals(args.get(0).asString());
        RedisMessage[] elements = new RedisMessage[values.size()];
        for (int i = 0; i < values.size(); i++) {
            elements[i] = RedisMessage.bulkString(values.get(i));
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleHSetNx(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'hsetnx' command");
        }
        String key = args.get(0).asString();
        String field = args.get(1).asString();
        byte[] value = args.get(2).getData();
        long result = store().hsetnx(key, field, value);
        if (result == -1) {
            return RedisMessage.error("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return RedisMessage.integer(result);
    }

    public RedisMessage handleHRandField(List<RedisMessage> args) {
        if (args.size() < 1 || args.size() > 3) {
            return RedisMessage.error("wrong number of arguments for 'hrandfield' command");
        }
        String key = args.get(0).asString();
        boolean withValues = false;
        long count = 1;
        if (args.size() >= 2) {
            String second = args.get(1).asString().toUpperCase();
            if ("WITHVALUES".equals(second)) {
                withValues = true;
            } else {
                count = Long.parseLong(second);
                if (args.size() == 3) {
                    if (!args.get(2).asString().equalsIgnoreCase("WITHVALUES")) {
                        return RedisMessage.error("ERR syntax error");
                    }
                    withValues = true;
                }
            }
        }
        if (count == 1 && !withValues && args.size() == 1) {
            byte[] value = store().hrandfield(key, 1).isEmpty() ? null : store().hrandfield(key, 1).get(0);
            return value != null ? RedisMessage.bulkString(value) : RedisMessage.bulkString((byte[]) null);
        }
        if (withValues) {
            RedisHash hash = store().getHash(key);
            if (hash == null) {
                return RedisMessage.array(new RedisMessage[0]);
            }
            List<String> keys = new ArrayList<>(hash.hkeys());
            long limit = count >= 0 ? Math.min(count, keys.size()) : Math.min(-count, keys.size());
            if (limit == 0) {
                return RedisMessage.array(new RedisMessage[0]);
            }
            Collections.shuffle(keys);
            List<RedisMessage> result = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                String f = count >= 0 ? keys.get(i) : keys.get(new Random().nextInt(keys.size()));
                result.add(RedisMessage.bulkString(f.getBytes()));
                result.add(RedisMessage.bulkString(hash.hget(f)));
            }
            return RedisMessage.array(result.toArray(new RedisMessage[0]));
        }
        List<byte[]> values = store().hrandfield(key, count);
        RedisMessage[] elements = new RedisMessage[values.size()];
        for (int i = 0; i < values.size(); i++) {
            elements[i] = RedisMessage.bulkString(values.get(i));
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleHIncrBy(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'hincrby' command");
        }
        String key = args.get(0).asString();
        String field = args.get(1).asString();
        long delta = Long.parseLong(args.get(2).asString());
        try {
            return RedisMessage.integer(store().hincrBy(key, field, delta));
        } catch (IllegalArgumentException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public RedisMessage handleHIncrByFloat(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'hincrbyfloat' command");
        }
        String key = args.get(0).asString();
        String field = args.get(1).asString();
        double delta = Double.parseDouble(args.get(2).asString());
        try {
            return RedisMessage.bulkString(String.valueOf(store().hincrByFloat(key, field, delta)));
        } catch (IllegalArgumentException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public RedisMessage handleHStrLen(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'hstrlen' command");
        }
        String key = args.get(0).asString();
        String field = args.get(1).asString();
        return RedisMessage.integer(store().hstrlen(key, field));
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("HSET", this::handleHSet);
        registry.put("HGET", this::handleHGet);
        registry.put("HMSET", this::handleHMSet);
        registry.put("HMGET", this::handleHMGet);
        registry.put("HGETALL", this::handleHGetAll);
        registry.put("HDEL", this::handleHDel);
        registry.put("HLEN", this::handleHLen);
        registry.put("HEXISTS", this::handleHExists);
        registry.put("HKEYS", this::handleHKeys);
        registry.put("HVALS", this::handleHVals);
        registry.put("HSETNX", this::handleHSetNx);
        registry.put("HRANDFIELD", this::handleHRandField);
        registry.put("HINCRBY", this::handleHIncrBy);
        registry.put("HINCRBYFLOAT", this::handleHIncrByFloat);
        registry.put("HSTRLEN", this::handleHStrLen);
    }
}
