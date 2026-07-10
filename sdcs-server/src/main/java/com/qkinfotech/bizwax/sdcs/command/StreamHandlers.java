package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.common.RedisStream.StreamEntry;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Function;

public class StreamHandlers extends BaseHandler {

    public StreamHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public RedisMessage handleXAdd(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("wrong number of arguments for 'xadd' command");
        }
        String key = args.get(0).asString();
        String id = args.get(1).asString();
        Map<String, byte[]> fields = new HashMap<>();
        if ((args.size() - 2) % 2 != 0) {
            return RedisMessage.error("ERR wrong number of arguments for 'xadd' command");
        }
        for (int i = 2; i < args.size(); i += 2) {
            fields.put(args.get(i).asString(), args.get(i + 1).getData());
        }
        try {
            String generatedId = store().xadd(key, id, fields);
            return RedisMessage.bulkString(generatedId.getBytes());
        } catch (IllegalArgumentException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public RedisMessage handleXLen(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'xlen' command");
        }
        return RedisMessage.integer(store().xlen(args.get(0).asString()));
    }

    public RedisMessage handleXRange(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("wrong number of arguments for 'xrange' command");
        }
        String key = args.get(0).asString();
        String start = args.get(1).asString();
        String end = args.get(2).asString();
        long count = 0;
        for (int i = 3; i < args.size(); i++) {
            if ("COUNT".equalsIgnoreCase(args.get(i).asString()) && i + 1 < args.size()) {
                count = Long.parseLong(args.get(++i).asString());
            }
        }
        List<StreamEntry> entries = store().xrange(key, start, end, count);
        List<RedisMessage> result = new ArrayList<>();
        for (StreamEntry entry : entries) {
            List<RedisMessage> entryParts = new ArrayList<>();
            entryParts.add(RedisMessage.bulkString(entry.getId().getBytes()));
            List<RedisMessage> fieldValues = new ArrayList<>();
            for (Map.Entry<String, byte[]> fv : entry.getFields().entrySet()) {
                fieldValues.add(RedisMessage.bulkString(fv.getKey().getBytes()));
                fieldValues.add(RedisMessage.bulkString(fv.getValue()));
            }
            entryParts.add(RedisMessage.array(fieldValues.toArray(new RedisMessage[0])));
            result.add(RedisMessage.array(entryParts.toArray(new RedisMessage[0])));
        }
        return RedisMessage.array(result.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleXRevRange(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("wrong number of arguments for 'xrevrange' command");
        }
        String key = args.get(0).asString();
        String end = args.get(1).asString();
        String start = args.get(2).asString();
        long count = 0;
        for (int i = 3; i < args.size(); i++) {
            if ("COUNT".equalsIgnoreCase(args.get(i).asString()) && i + 1 < args.size()) {
                count = Long.parseLong(args.get(++i).asString());
            }
        }
        List<StreamEntry> entries = store().xrevrange(key, end, start, count);
        List<RedisMessage> result = new ArrayList<>();
        for (StreamEntry entry : entries) {
            List<RedisMessage> entryParts = new ArrayList<>();
            entryParts.add(RedisMessage.bulkString(entry.getId().getBytes()));
            List<RedisMessage> fieldValues = new ArrayList<>();
            for (Map.Entry<String, byte[]> fv : entry.getFields().entrySet()) {
                fieldValues.add(RedisMessage.bulkString(fv.getKey().getBytes()));
                fieldValues.add(RedisMessage.bulkString(fv.getValue()));
            }
            entryParts.add(RedisMessage.array(fieldValues.toArray(new RedisMessage[0])));
            result.add(RedisMessage.array(entryParts.toArray(new RedisMessage[0])));
        }
        return RedisMessage.array(result.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleXRead(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'xread' command");
        }
        long count = 0;
        int argIndex = 0;
        if ("COUNT".equalsIgnoreCase(args.get(argIndex).asString())) {
            if (argIndex + 1 >= args.size()) {
                return RedisMessage.error("ERR wrong number of arguments for 'xread' command");
            }
            count = Long.parseLong(args.get(++argIndex).asString());
            argIndex++;
        }
        if (argIndex >= args.size() || !"STREAMS".equalsIgnoreCase(args.get(argIndex).asString())) {
            return RedisMessage.error("ERR wrong number of arguments for 'xread' command");
        }
        argIndex++;
        int remaining = args.size() - argIndex;
        if (remaining % 2 != 0 || remaining == 0) {
            return RedisMessage.error("ERR wrong number of arguments for 'xread' command");
        }
        int keyCount = remaining / 2;
        List<RedisMessage> result = new ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            String key = args.get(argIndex + i).asString();
            String startId = args.get(argIndex + keyCount + i).asString();
            List<StreamEntry> entries = store().xread(key, startId, count);
            if (!entries.isEmpty()) {
                List<RedisMessage> streamParts = new ArrayList<>();
                streamParts.add(RedisMessage.bulkString(key.getBytes()));
                List<RedisMessage> entryList = new ArrayList<>();
                for (StreamEntry entry : entries) {
                    List<RedisMessage> entryParts = new ArrayList<>();
                    entryParts.add(RedisMessage.bulkString(entry.getId().getBytes()));
                    List<RedisMessage> fieldValues = new ArrayList<>();
                    for (Map.Entry<String, byte[]> fv : entry.getFields().entrySet()) {
                        fieldValues.add(RedisMessage.bulkString(fv.getKey().getBytes()));
                        fieldValues.add(RedisMessage.bulkString(fv.getValue()));
                    }
                    entryParts.add(RedisMessage.array(fieldValues.toArray(new RedisMessage[0])));
                    entryList.add(RedisMessage.array(entryParts.toArray(new RedisMessage[0])));
                }
                streamParts.add(RedisMessage.array(entryList.toArray(new RedisMessage[0])));
                result.add(RedisMessage.array(streamParts.toArray(new RedisMessage[0])));
            }
        }
        return result.isEmpty() ? RedisMessage.bulkString((byte[]) null) : RedisMessage.array(result.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleXTrim(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("wrong number of arguments for 'xtrim' command");
        }
        String key = args.get(0).asString();
        if (!"MAXLEN".equalsIgnoreCase(args.get(1).asString())) {
            return RedisMessage.error("ERR wrong number of arguments for 'xtrim' command");
        }
        boolean approx = false;
        int idx = 2;
        if ("~".equals(args.get(idx).asString())) {
            approx = true;
            idx++;
        }
        if (idx >= args.size()) {
            return RedisMessage.error("ERR wrong number of arguments for 'xtrim' command");
        }
        long maxLen;
        try {
            maxLen = Long.parseLong(args.get(idx).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR value is not an integer or out of range");
        }
        return RedisMessage.integer(store().xtrim(key, maxLen, approx));
    }

    public RedisMessage handleXDel(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'xdel' command");
        }
        String key = args.get(0).asString();
        String[] ids = new String[args.size() - 1];
        for (int i = 1; i < args.size(); i++) {
            ids[i - 1] = args.get(i).asString();
        }
        return RedisMessage.integer(store().xdel(key, ids));
    }

    public RedisMessage handleXPending(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'xpending' command");
        }
        return RedisMessage.array(new RedisMessage[0]);
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("XADD", this::handleXAdd);
        registry.put("XLEN", this::handleXLen);
        registry.put("XRANGE", this::handleXRange);
        registry.put("XREVRANGE", this::handleXRevRange);
        registry.put("XREAD", this::handleXRead);
        registry.put("XTRIM", this::handleXTrim);
        registry.put("XDEL", this::handleXDel);
        registry.put("XPENDING", this::handleXPending);
        registry.put("XGROUP", args -> RedisMessage.error("ERR XGROUP is not supported"));
        registry.put("XREADGROUP", args -> RedisMessage.error("ERR XREADGROUP is not supported"));
    }
}
