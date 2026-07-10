package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Function;

public class SetHandlers extends BaseHandler {

    public SetHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public RedisMessage handleSAdd(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'sadd' command");
        }
        String key = args.get(0).asString();
        byte[][] members = new byte[args.size() - 1][];
        for (int i = 1; i < args.size(); i++) {
            members[i - 1] = args.get(i).getData();
        }
        long result = store().sadd(key, members);
        if (result == -1) {
            return RedisMessage.error("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        return RedisMessage.integer(result);
    }

    public RedisMessage handleSRem(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'srem' command");
        }
        String key = args.get(0).asString();
        byte[][] members = new byte[args.size() - 1][];
        for (int i = 1; i < args.size(); i++) {
            members[i - 1] = args.get(i).getData();
        }
        return RedisMessage.integer(store().srem(key, members));
    }

    public RedisMessage handleSMembers(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'smembers' command");
        }
        Set<byte[]> members = store().smembers(args.get(0).asString());
        RedisMessage[] elements = new RedisMessage[members.size()];
        int i = 0;
        for (byte[] member : members) {
            elements[i++] = RedisMessage.bulkString(member);
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleSIsMember(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'sismember' command");
        }
        String key = args.get(0).asString();
        byte[] member = args.get(1).getData();
        return RedisMessage.integer(store().sismember(key, member) ? 1 : 0);
    }

    public RedisMessage handleSCard(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'scard' command");
        }
        return RedisMessage.integer(store().scard(args.get(0).asString()));
    }

    public RedisMessage handleSPop(List<RedisMessage> args) {
        if (args.isEmpty() || args.size() > 2) {
            return RedisMessage.error("wrong number of arguments for 'spop' command");
        }
        String key = args.get(0).asString();
        if (args.size() == 1) {
            byte[] value = store().spop(key);
            return value != null ? RedisMessage.bulkString(value) : RedisMessage.bulkString((byte[]) null);
        } else {
            int count = Integer.parseInt(args.get(1).asString());
            List<byte[]> values = store().spop(key, count);
            RedisMessage[] elements = new RedisMessage[values.size()];
            for (int i = 0; i < values.size(); i++) {
                elements[i] = RedisMessage.bulkString(values.get(i));
            }
            return RedisMessage.array(elements);
        }
    }

    public RedisMessage handleSRandMember(List<RedisMessage> args) {
        if (args.isEmpty() || args.size() > 2) {
            return RedisMessage.error("wrong number of arguments for 'srandmember' command");
        }
        String key = args.get(0).asString();
        if (args.size() == 1) {
            byte[] value = store().srandmember(key);
            return value != null ? RedisMessage.bulkString(value) : RedisMessage.bulkString((byte[]) null);
        } else {
            int count = Integer.parseInt(args.get(1).asString());
            List<byte[]> values = store().srandmember(key, count);
            RedisMessage[] elements = new RedisMessage[values.size()];
            for (int i = 0; i < values.size(); i++) {
                elements[i] = RedisMessage.bulkString(values.get(i));
            }
            return RedisMessage.array(elements);
        }
    }

    public RedisMessage handleSMove(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'smove' command");
        }
        String source = args.get(0).asString();
        String destination = args.get(1).asString();
        byte[] member = args.get(2).getData();
        return RedisMessage.integer(store().smove(source, destination, member));
    }

    public RedisMessage handleSUnion(List<RedisMessage> args) {
        if (args.size() < 1) {
            return RedisMessage.error("wrong number of arguments for 'sunion' command");
        }
        String[] keys = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            keys[i] = args.get(i).asString();
        }
        Set<byte[]> result = store().sunion(keys);
        RedisMessage[] elements = new RedisMessage[result.size()];
        int i = 0;
        for (byte[] member : result) {
            elements[i++] = RedisMessage.bulkString(member);
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleSUnionStore(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'sunionstore' command");
        }
        String destination = args.get(0).asString();
        String[] keys = new String[args.size() - 1];
        for (int i = 1; i < args.size(); i++) {
            keys[i - 1] = args.get(i).asString();
        }
        long count = store().sunionstore(destination, keys);
        return RedisMessage.integer(count);
    }

    public RedisMessage handleSInter(List<RedisMessage> args) {
        if (args.size() < 1) {
            return RedisMessage.error("wrong number of arguments for 'sinter' command");
        }
        String[] keys = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            keys[i] = args.get(i).asString();
        }
        Set<byte[]> result = store().sinter(keys);
        RedisMessage[] elements = new RedisMessage[result.size()];
        int i = 0;
        for (byte[] member : result) {
            elements[i++] = RedisMessage.bulkString(member);
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleSInterStore(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'sinterstore' command");
        }
        String destination = args.get(0).asString();
        String[] keys = new String[args.size() - 1];
        for (int i = 1; i < args.size(); i++) {
            keys[i - 1] = args.get(i).asString();
        }
        long count = store().sinterstore(destination, keys);
        return RedisMessage.integer(count);
    }

    public RedisMessage handleSInterCard(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'sintercard' command");
        }
        int numkeys = Integer.parseInt(args.get(0).asString());
        String[] keys = new String[numkeys];
        for (int i = 0; i < numkeys; i++) {
            keys[i] = args.get(i + 1).asString();
        }
        long limit = Long.MAX_VALUE;
        for (int i = numkeys + 1; i < args.size(); i++) {
            if (args.get(i).asString().equalsIgnoreCase("LIMIT") && i + 1 < args.size()) {
                limit = Long.parseLong(args.get(++i).asString());
            }
        }
        Set<byte[]> intersected = store().sinter(keys);
        long count = Math.min(intersected.size(), limit);
        return RedisMessage.integer(count);
    }

    public RedisMessage handleSDiff(List<RedisMessage> args) {
        if (args.size() < 1) {
            return RedisMessage.error("wrong number of arguments for 'sdiff' command");
        }
        String[] keys = new String[args.size()];
        for (int i = 0; i < args.size(); i++) {
            keys[i] = args.get(i).asString();
        }
        Set<byte[]> result = store().sdiff(keys);
        RedisMessage[] elements = new RedisMessage[result.size()];
        int i = 0;
        for (byte[] member : result) {
            elements[i++] = RedisMessage.bulkString(member);
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleSDiffStore(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'sdiffstore' command");
        }
        String destination = args.get(0).asString();
        String[] keys = new String[args.size() - 1];
        for (int i = 1; i < args.size(); i++) {
            keys[i - 1] = args.get(i).asString();
        }
        long count = store().sdiffstore(destination, keys);
        return RedisMessage.integer(count);
    }

    public RedisMessage handleSMIsMember(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'smismember' command");
        }
        String key = args.get(0).asString();
        byte[][] members = new byte[args.size() - 1][];
        for (int i = 1; i < args.size(); i++) {
            members[i - 1] = args.get(i).getData();
        }
        List<Long> result = store().smismember(key, members);
        RedisMessage[] elements = new RedisMessage[result.size()];
        for (int i = 0; i < result.size(); i++) {
            elements[i] = RedisMessage.integer(result.get(i));
        }
        return RedisMessage.array(elements);
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("SADD", this::handleSAdd);
        registry.put("SREM", this::handleSRem);
        registry.put("SMEMBERS", this::handleSMembers);
        registry.put("SISMEMBER", this::handleSIsMember);
        registry.put("SCARD", this::handleSCard);
        registry.put("SPOP", this::handleSPop);
        registry.put("SRANDMEMBER", this::handleSRandMember);
        registry.put("SMOVE", this::handleSMove);
        registry.put("SUNION", this::handleSUnion);
        registry.put("SUNIONSTORE", this::handleSUnionStore);
        registry.put("SINTER", this::handleSInter);
        registry.put("SINTERSTORE", this::handleSInterStore);
        registry.put("SINTERCARD", this::handleSInterCard);
        registry.put("SDIFF", this::handleSDiff);
        registry.put("SDIFFSTORE", this::handleSDiffStore);
        registry.put("SMISMEMBER", this::handleSMIsMember);
    }
}
