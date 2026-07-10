package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.common.RedisZSet;
import com.qkinfotech.bizwax.sdcs.common.RedisZSet.ZSetEntry;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class ZSetHandlers extends BaseHandler {

    public ZSetHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public RedisMessage handleZAdd(List<RedisMessage> args) {
        if (args.size() < 3 || (args.size() - 1) % 2 != 0) {
            return RedisMessage.error("wrong number of arguments for 'zadd' command");
        }
        String key = args.get(0).asString();
        long added = 0;
        for (int i = 1; i < args.size(); i += 2) {
            double score = Double.parseDouble(args.get(i).asString());
            byte[] member = args.get(i + 1).getData();
            long result = store().zadd(key, score, member);
            if (result == -1) {
                return RedisMessage.error("WRONGTYPE Operation against a key holding the wrong kind of value");
            }
            added += result;
        }
        return RedisMessage.integer(added);
    }

    public RedisMessage handleZIncrBy(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'zincrby' command");
        }
        String key = args.get(0).asString();
        double delta = Double.parseDouble(args.get(1).asString());
        byte[] member = args.get(2).getData();
        try {
            return RedisMessage.bulkString(String.valueOf(store().zincrby(key, delta, member)));
        } catch (IllegalArgumentException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public RedisMessage handleZRem(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'zrem' command");
        }
        String key = args.get(0).asString();
        byte[][] members = new byte[args.size() - 1][];
        for (int i = 1; i < args.size(); i++) {
            members[i - 1] = args.get(i).getData();
        }
        return RedisMessage.integer(store().zrem(key, members));
    }

    public RedisMessage handleZScore(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'zscore' command");
        }
        String key = args.get(0).asString();
        byte[] member = args.get(1).getData();
        Double score = store().zscore(key, member);
        return score != null ? RedisMessage.bulkString(String.valueOf(score).getBytes()) : RedisMessage.bulkString((byte[]) null);
    }

    public RedisMessage handleZMScore(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'zmscore' command");
        }
        String key = args.get(0).asString();
        byte[][] members = new byte[args.size() - 1][];
        for (int i = 1; i < args.size(); i++) {
            members[i - 1] = args.get(i).getData();
        }
        List<Double> scores = store().zmscore(key, members);
        RedisMessage[] elements = new RedisMessage[scores.size()];
        for (int i = 0; i < scores.size(); i++) {
            Double s = scores.get(i);
            elements[i] = s != null ? RedisMessage.bulkString(String.valueOf(s).getBytes()) : RedisMessage.bulkString((byte[]) null);
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleZRank(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'zrank' command");
        }
        String key = args.get(0).asString();
        byte[] member = args.get(1).getData();
        Long rank = store().zrank(key, member);
        return rank != null ? RedisMessage.integer(rank) : RedisMessage.bulkString((byte[]) null);
    }

    public RedisMessage handleZRevRank(List<RedisMessage> args) {
        if (args.size() != 2) {
            return RedisMessage.error("wrong number of arguments for 'zrevrank' command");
        }
        String key = args.get(0).asString();
        byte[] member = args.get(1).getData();
        Long rank = store().zrevrank(key, member);
        return rank != null ? RedisMessage.integer(rank) : RedisMessage.bulkString((byte[]) null);
    }

    public RedisMessage handleZCard(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'zcard' command");
        }
        return RedisMessage.integer(store().zcard(args.get(0).asString()));
    }

    public RedisMessage handleZCount(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'zcount' command");
        }
        String key = args.get(0).asString();
        double min = Double.parseDouble(args.get(1).asString());
        double max = Double.parseDouble(args.get(2).asString());
        return RedisMessage.integer(store().zcount(key, min, max));
    }

    public RedisMessage handleZRange(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("wrong number of arguments for 'zrange' command");
        }
        String key = args.get(0).asString();
        long start = Long.parseLong(args.get(1).asString());
        long stop = Long.parseLong(args.get(2).asString());
        boolean withScores = args.size() > 3 && args.get(3).asString().equalsIgnoreCase("WITHSCORES");

        List<ZSetEntry> entries = store().zrange(key, start, stop);
        List<RedisMessage> elements = new ArrayList<>();
        for (ZSetEntry entry : entries) {
            elements.add(RedisMessage.bulkString(entry.member()));
            if (withScores) {
                elements.add(RedisMessage.bulkString(String.valueOf(entry.score()).getBytes()));
            }
        }
        return RedisMessage.array(elements.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleZRevRange(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("wrong number of arguments for 'zrevrange' command");
        }
        String key = args.get(0).asString();
        long start = Long.parseLong(args.get(1).asString());
        long stop = Long.parseLong(args.get(2).asString());
        boolean withScores = args.size() > 3 && args.get(3).asString().equalsIgnoreCase("WITHSCORES");

        List<ZSetEntry> entries = store().zrevrange(key, start, stop);
        List<RedisMessage> elements = new ArrayList<>();
        for (ZSetEntry entry : entries) {
            elements.add(RedisMessage.bulkString(entry.member()));
            if (withScores) {
                elements.add(RedisMessage.bulkString(String.valueOf(entry.score()).getBytes()));
            }
        }
        return RedisMessage.array(elements.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleZRangeByScore(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("wrong number of arguments for 'zrangebyscore' command");
        }
        String key = args.get(0).asString();
        double min = Double.parseDouble(args.get(1).asString());
        double max = Double.parseDouble(args.get(2).asString());
        long offset = 0, count = -1;
        boolean withScores = false;

        for (int i = 3; i < args.size(); i++) {
            String opt = args.get(i).asString().toUpperCase();
            if ("WITHSCORES".equals(opt)) {
                withScores = true;
            } else if ("LIMIT".equals(opt) && i + 2 < args.size()) {
                offset = Long.parseLong(args.get(++i).asString());
                count = Long.parseLong(args.get(++i).asString());
            }
        }

        List<ZSetEntry> entries = store().zrangebyscore(key, min, max, offset, count);
        List<RedisMessage> elements = new ArrayList<>();
        for (ZSetEntry entry : entries) {
            elements.add(RedisMessage.bulkString(entry.member()));
            if (withScores) {
                elements.add(RedisMessage.bulkString(String.valueOf(entry.score()).getBytes()));
            }
        }
        return RedisMessage.array(elements.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleZRevRangeByScore(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("wrong number of arguments for 'zrevrangebyscore' command");
        }
        String key = args.get(0).asString();
        double max = Double.parseDouble(args.get(1).asString());
        double min = Double.parseDouble(args.get(2).asString());
        long offset = 0, count = -1;
        boolean withScores = false;
        for (int i = 3; i < args.size(); i++) {
            String opt = args.get(i).asString().toUpperCase();
            if ("WITHSCORES".equals(opt)) {
                withScores = true;
            } else if ("LIMIT".equals(opt) && i + 2 < args.size()) {
                offset = Long.parseLong(args.get(++i).asString());
                count = Long.parseLong(args.get(++i).asString());
            }
        }
        List<ZSetEntry> entries = store().zrevrangebyscore(key, max, min, offset, count);
        List<RedisMessage> elements = new ArrayList<>();
        for (ZSetEntry entry : entries) {
            elements.add(RedisMessage.bulkString(entry.member()));
            if (withScores) {
                elements.add(RedisMessage.bulkString(String.valueOf(entry.score()).getBytes()));
            }
        }
        return RedisMessage.array(elements.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleZRemRangeByRank(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'zremrangebyrank' command");
        }
        String key = args.get(0).asString();
        long start = Long.parseLong(args.get(1).asString());
        long stop = Long.parseLong(args.get(2).asString());
        return RedisMessage.integer(store().zremrangebyrank(key, start, stop));
    }

    public RedisMessage handleZRemRangeByScore(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'zremrangebyscore' command");
        }
        String key = args.get(0).asString();
        double min = Double.parseDouble(args.get(1).asString());
        double max = Double.parseDouble(args.get(2).asString());
        return RedisMessage.integer(store().zremrangebyscore(key, min, max));
    }

    public RedisMessage handleZPopMin(List<RedisMessage> args) {
        if (args.size() < 1 || args.size() > 2) {
            return RedisMessage.error("wrong number of arguments for 'zpopmin' command");
        }
        String key = args.get(0).asString();
        long count = args.size() > 1 ? Long.parseLong(args.get(1).asString()) : 1;
        List<ZSetEntry> entries = store().zpopmin(key, count);
        List<RedisMessage> elements = new ArrayList<>();
        for (ZSetEntry entry : entries) {
            elements.add(RedisMessage.bulkString(entry.member()));
            elements.add(RedisMessage.bulkString(String.valueOf(entry.score()).getBytes()));
        }
        return RedisMessage.array(elements.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleZPopMax(List<RedisMessage> args) {
        if (args.size() < 1 || args.size() > 2) {
            return RedisMessage.error("wrong number of arguments for 'zpopmax' command");
        }
        String key = args.get(0).asString();
        long count = args.size() > 1 ? Long.parseLong(args.get(1).asString()) : 1;
        List<ZSetEntry> entries = store().zpopmax(key, count);
        List<RedisMessage> elements = new ArrayList<>();
        for (ZSetEntry entry : entries) {
            elements.add(RedisMessage.bulkString(entry.member()));
            elements.add(RedisMessage.bulkString(String.valueOf(entry.score()).getBytes()));
        }
        return RedisMessage.array(elements.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleZRandMember(List<RedisMessage> args) {
        if (args.size() < 1 || args.size() > 2) {
            return RedisMessage.error("wrong number of arguments for 'zrandmember' command");
        }
        String key = args.get(0).asString();
        long count = args.size() > 1 ? Long.parseLong(args.get(1).asString()) : 1;
        boolean withScores = false;
        List<byte[]> members = store().zrandmember(key, count);
        if (count == 1 && args.size() == 1) {
            return members.isEmpty() ? RedisMessage.bulkString((byte[]) null) : RedisMessage.bulkString(members.get(0));
        }
        RedisMessage[] elements = new RedisMessage[members.size()];
        for (int i = 0; i < members.size(); i++) {
            elements[i] = RedisMessage.bulkString(members.get(i));
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleZDiff(List<RedisMessage> args) {
        if (args.size() < 1) {
            return RedisMessage.error("wrong number of arguments for 'zdiff' command");
        }
        int numkeys = Integer.parseInt(args.get(0).asString());
        String[] keys = new String[numkeys];
        for (int i = 0; i < numkeys; i++) {
            keys[i] = args.get(i + 1).asString();
        }
        boolean withScores = false;
        for (int i = numkeys + 1; i < args.size(); i++) {
            if (args.get(i).asString().equalsIgnoreCase("WITHSCORES")) {
                withScores = true;
            }
        }
        List<ZSetEntry> entries = store().zdiff(keys);
        List<RedisMessage> elements = new ArrayList<>();
        for (ZSetEntry entry : entries) {
            elements.add(RedisMessage.bulkString(entry.member()));
            if (withScores) {
                elements.add(RedisMessage.bulkString(String.valueOf(entry.score()).getBytes()));
            }
        }
        return RedisMessage.array(elements.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleZInter(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'zinter' command");
        }
        int numkeys = Integer.parseInt(args.get(0).asString());
        String[] keys = new String[numkeys];
        for (int i = 0; i < numkeys; i++) {
            keys[i] = args.get(i + 1).asString();
        }
        List<Double> weights = new ArrayList<>();
        String aggregate = "SUM";
        boolean withScores = false;
        int idx = numkeys + 1;
        while (idx < args.size()) {
            String opt = args.get(idx).asString().toUpperCase();
            if ("WEIGHTS".equals(opt)) {
                idx++;
                while (idx < args.size() && !args.get(idx).asString().toUpperCase().matches("WEIGHTS|AGGREGATE|WITHSCORES")) {
                    weights.add(Double.parseDouble(args.get(idx).asString()));
                    idx++;
                }
            } else if ("AGGREGATE".equals(opt)) {
                if (++idx < args.size()) aggregate = args.get(idx).asString().toUpperCase();
                idx++;
            } else if ("WITHSCORES".equals(opt)) {
                withScores = true;
                idx++;
            } else {
                idx++;
            }
        }
        List<ZSetEntry> entries = store().zinter(keys, weights.isEmpty() ? null : weights, aggregate);
        List<RedisMessage> elements = new ArrayList<>();
        for (ZSetEntry entry : entries) {
            elements.add(RedisMessage.bulkString(entry.member()));
            if (withScores) {
                elements.add(RedisMessage.bulkString(String.valueOf(entry.score()).getBytes()));
            }
        }
        return RedisMessage.array(elements.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleZUnion(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'zunion' command");
        }
        int numkeys = Integer.parseInt(args.get(0).asString());
        String[] keys = new String[numkeys];
        for (int i = 0; i < numkeys; i++) {
            keys[i] = args.get(i + 1).asString();
        }
        List<Double> weights = new ArrayList<>();
        String aggregate = "SUM";
        boolean withScores = false;
        int idx = numkeys + 1;
        while (idx < args.size()) {
            String opt = args.get(idx).asString().toUpperCase();
            if ("WEIGHTS".equals(opt)) {
                idx++;
                while (idx < args.size() && !args.get(idx).asString().toUpperCase().matches("WEIGHTS|AGGREGATE|WITHSCORES")) {
                    weights.add(Double.parseDouble(args.get(idx).asString()));
                    idx++;
                }
            } else if ("AGGREGATE".equals(opt)) {
                if (++idx < args.size()) aggregate = args.get(idx).asString().toUpperCase();
                idx++;
            } else if ("WITHSCORES".equals(opt)) {
                withScores = true;
                idx++;
            } else {
                idx++;
            }
        }
        List<ZSetEntry> entries = store().zunion(keys, weights.isEmpty() ? null : weights, aggregate);
        List<RedisMessage> elements = new ArrayList<>();
        for (ZSetEntry entry : entries) {
            elements.add(RedisMessage.bulkString(entry.member()));
            if (withScores) {
                elements.add(RedisMessage.bulkString(String.valueOf(entry.score()).getBytes()));
            }
        }
        return RedisMessage.array(elements.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleZDiffStore(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'zdiffstore' command");
        }
        String destination = args.get(0).asString();
        int numkeys = Integer.parseInt(args.get(1).asString());
        String[] keys = new String[numkeys];
        for (int i = 0; i < numkeys; i++) {
            keys[i] = args.get(i + 2).asString();
        }
        return RedisMessage.integer(store().zdiffstore(destination, keys));
    }

    public RedisMessage handleZInterStore(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'zinterstore' command");
        }
        String destination = args.get(0).asString();
        int numkeys = Integer.parseInt(args.get(1).asString());
        String[] keys = new String[numkeys];
        for (int i = 0; i < numkeys; i++) {
            keys[i] = args.get(i + 2).asString();
        }
        List<Double> weights = new ArrayList<>();
        String aggregate = "SUM";
        int idx = numkeys + 2;
        while (idx < args.size()) {
            String opt = args.get(idx).asString().toUpperCase();
            if ("WEIGHTS".equals(opt)) {
                idx++;
                while (idx < args.size() && !args.get(idx).asString().toUpperCase().matches("WEIGHTS|AGGREGATE")) {
                    weights.add(Double.parseDouble(args.get(idx).asString()));
                    idx++;
                }
            } else if ("AGGREGATE".equals(opt)) {
                if (++idx < args.size()) aggregate = args.get(idx).asString().toUpperCase();
                idx++;
            } else {
                idx++;
            }
        }
        return RedisMessage.integer(store().zinterstore(destination, keys, weights.isEmpty() ? null : weights, aggregate));
    }

    public RedisMessage handleZUnionStore(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'zunionstore' command");
        }
        String destination = args.get(0).asString();
        int numkeys = Integer.parseInt(args.get(1).asString());
        String[] keys = new String[numkeys];
        for (int i = 0; i < numkeys; i++) {
            keys[i] = args.get(i + 2).asString();
        }
        List<Double> weights = new ArrayList<>();
        String aggregate = "SUM";
        int idx = numkeys + 2;
        while (idx < args.size()) {
            String opt = args.get(idx).asString().toUpperCase();
            if ("WEIGHTS".equals(opt)) {
                idx++;
                while (idx < args.size() && !args.get(idx).asString().toUpperCase().matches("WEIGHTS|AGGREGATE")) {
                    weights.add(Double.parseDouble(args.get(idx).asString()));
                    idx++;
                }
            } else if ("AGGREGATE".equals(opt)) {
                if (++idx < args.size()) aggregate = args.get(idx).asString().toUpperCase();
                idx++;
            } else {
                idx++;
            }
        }
        return RedisMessage.integer(store().zunionstore(destination, keys, weights.isEmpty() ? null : weights, aggregate));
    }

    public RedisMessage handleZRangeStore(List<RedisMessage> args) {
        if (args.size() != 4) {
            return RedisMessage.error("wrong number of arguments for 'zrangestore' command");
        }
        String destination = args.get(0).asString();
        String source = args.get(1).asString();
        long start = Long.parseLong(args.get(2).asString());
        long stop = Long.parseLong(args.get(3).asString());
        return RedisMessage.integer(store().zrangestore(destination, source, start, stop));
    }

    public RedisMessage handleZLexCount(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'zlexcount' command");
        }
        String key = args.get(0).asString();
        byte[] min = args.get(1).getData();
        byte[] max = args.get(2).getData();
        return RedisMessage.integer(store().zlexcount(key, min, max));
    }

    public RedisMessage handleZRangeByLex(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("wrong number of arguments for 'zrangebylex' command");
        }
        String key = args.get(0).asString();
        byte[] min = args.get(1).getData();
        byte[] max = args.get(2).getData();
        long offset = 0, count = -1;
        for (int i = 3; i < args.size(); i++) {
            String opt = args.get(i).asString().toUpperCase();
            if ("LIMIT".equals(opt) && i + 2 < args.size()) {
                offset = Long.parseLong(args.get(++i).asString());
                count = Long.parseLong(args.get(++i).asString());
            }
        }
        List<byte[]> members = store().zrangebylex(key, min, max, offset, count);
        RedisMessage[] elements = new RedisMessage[members.size()];
        for (int i = 0; i < members.size(); i++) {
            elements[i] = RedisMessage.bulkString(members.get(i));
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleZRevRangeByLex(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("wrong number of arguments for 'zrevrangebylex' command");
        }
        String key = args.get(0).asString();
        byte[] max = args.get(1).getData();
        byte[] min = args.get(2).getData();
        long offset = 0, count = -1;
        for (int i = 3; i < args.size(); i++) {
            String opt = args.get(i).asString().toUpperCase();
            if ("LIMIT".equals(opt) && i + 2 < args.size()) {
                offset = Long.parseLong(args.get(++i).asString());
                count = Long.parseLong(args.get(++i).asString());
            }
        }
        List<byte[]> members = store().zrevrangebylex(key, max, min, offset, count);
        RedisMessage[] elements = new RedisMessage[members.size()];
        for (int i = 0; i < members.size(); i++) {
            elements[i] = RedisMessage.bulkString(members.get(i));
        }
        return RedisMessage.array(elements);
    }

    public RedisMessage handleZRemRangeByLex(List<RedisMessage> args) {
        if (args.size() != 3) {
            return RedisMessage.error("wrong number of arguments for 'zremrangebylex' command");
        }
        String key = args.get(0).asString();
        byte[] min = args.get(1).getData();
        byte[] max = args.get(2).getData();
        return RedisMessage.integer(store().zremrangebylex(key, min, max));
    }

    public RedisMessage handleBZPopMin(List<RedisMessage> args) {
        return RedisMessage.error("ERR BZPOPMIN not yet supported");
    }

    public RedisMessage handleBZPopMax(List<RedisMessage> args) {
        return RedisMessage.error("ERR BZPOPMAX not yet supported");
    }

    public RedisMessage handleZInterCard(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'zintercard' command");
        }
        int numkeys = Integer.parseInt(args.get(0).asString());
        String[] keys = new String[numkeys];
        for (int i = 0; i < numkeys; i++) {
            keys[i] = args.get(i + 1).asString();
        }
        long limit = 0;
        for (int i = numkeys + 1; i < args.size(); i++) {
            if (args.get(i).asString().equalsIgnoreCase("LIMIT") && i + 1 < args.size()) {
                limit = Long.parseLong(args.get(++i).asString());
                break;
            }
        }
        return RedisMessage.integer(store().zintercard(keys, limit));
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("ZADD", this::handleZAdd);
        registry.put("ZINCRBY", this::handleZIncrBy);
        registry.put("ZREM", this::handleZRem);
        registry.put("ZSCORE", this::handleZScore);
        registry.put("ZMSCORE", this::handleZMScore);
        registry.put("ZRANK", this::handleZRank);
        registry.put("ZREVRANK", this::handleZRevRank);
        registry.put("ZCARD", this::handleZCard);
        registry.put("ZCOUNT", this::handleZCount);
        registry.put("ZRANGE", this::handleZRange);
        registry.put("ZREVRANGE", this::handleZRevRange);
        registry.put("ZRANGEBYSCORE", this::handleZRangeByScore);
        registry.put("ZREVRANGEBYSCORE", this::handleZRevRangeByScore);
        registry.put("ZREMRANGEBYRANK", this::handleZRemRangeByRank);
        registry.put("ZREMRANGEBYSCORE", this::handleZRemRangeByScore);
        registry.put("ZPOPMIN", this::handleZPopMin);
        registry.put("ZPOPMAX", this::handleZPopMax);
        registry.put("ZRANDMEMBER", this::handleZRandMember);
        registry.put("ZDIFF", this::handleZDiff);
        registry.put("ZINTER", this::handleZInter);
        registry.put("ZUNION", this::handleZUnion);
        registry.put("ZDIFFSTORE", this::handleZDiffStore);
        registry.put("ZINTERSTORE", this::handleZInterStore);
        registry.put("ZUNIONSTORE", this::handleZUnionStore);
        registry.put("ZRANGESTORE", this::handleZRangeStore);
        registry.put("ZLEXCOUNT", this::handleZLexCount);
        registry.put("ZRANGEBYLEX", this::handleZRangeByLex);
        registry.put("ZREVRANGEBYLEX", this::handleZRevRangeByLex);
        registry.put("ZREMRANGEBYLEX", this::handleZRemRangeByLex);
        registry.put("BZPOPMIN", this::handleBZPopMin);
        registry.put("BZPOPMAX", this::handleBZPopMax);
        registry.put("ZINTERCARD", this::handleZInterCard);
    }
}
