package com.qkinfotech.bizwax.sdcs.common;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class StripedLock {

    private static final int NUM_STRIPES = 64;
    private final ReentrantLock[] locks = new ReentrantLock[NUM_STRIPES];

    public StripedLock() {
        for (int i = 0; i < NUM_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    private int stripe(String key) {
        return (key.hashCode() & Integer.MAX_VALUE) % NUM_STRIPES;
    }

    public void lock(String key) {
        locks[stripe(key)].lock();
    }

    public void unlock(String key) {
        locks[stripe(key)].unlock();
    }

    public void lockAll() {
        for (ReentrantLock lock : locks) {
            lock.lock();
        }
    }

    public void unlockAll() {
        for (int i = locks.length - 1; i >= 0; i--) {
            locks[i].unlock();
        }
    }

    public void lockKeys(String... keys) {
        if (keys.length == 0) return;
        int[] stripes = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            stripes[i] = stripe(keys[i]);
        }
        java.util.Arrays.sort(stripes);
        int prev = -1;
        for (int s : stripes) {
            if (s != prev) {
                locks[s].lock();
                prev = s;
            }
        }
    }

    public void unlockKeys(String... keys) {
        if (keys.length == 0) return;
        int[] stripes = new int[keys.length];
        for (int i = 0; i < keys.length; i++) {
            stripes[i] = stripe(keys[i]);
        }
        java.util.Arrays.sort(stripes);
        int prev = -1;
        for (int i = stripes.length - 1; i >= 0; i--) {
            if (stripes[i] != prev) {
                locks[stripes[i]].unlock();
                prev = stripes[i];
            }
        }
    }

    public static String extractKey(String commandName, List<RedisMessage> args) {
        if (args.isEmpty()) return null;
        if ("RENAME".equals(commandName) || "RENAMENX".equals(commandName)) {
            return args.get(0).asString() + "\0" + args.get(1).asString();
        }
        if ("SMOVE".equals(commandName)) {
            return args.get(0).asString() + "\0" + args.get(1).asString();
        }
        if ("LMOVE".equals(commandName) || "RPOPLPUSH".equals(commandName)) {
            return args.get(0).asString() + "\0" + args.get(1).asString();
        }
        if ("BITOP".equals(commandName)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.size(); i++) {
                if (sb.length() > 0) sb.append('\0');
                sb.append(args.get(i).asString());
            }
            return sb.toString();
        }
        if ("MSET".equals(commandName) || "MSETNX".equals(commandName)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i += 2) {
                if (sb.length() > 0) sb.append('\0');
                sb.append(args.get(i).asString());
            }
            return sb.toString();
        }
        if ("DEL".equals(commandName) || "EXISTS".equals(commandName) || "MGET".equals(commandName)) {
            StringBuilder sb = new StringBuilder();
            for (RedisMessage arg : args) {
                if (sb.length() > 0) sb.append('\0');
                sb.append(arg.asString());
            }
            return sb.toString();
        }
        if ("SUNIONSTORE".equals(commandName) || "SINTERSTORE".equals(commandName) || "SDIFFSTORE".equals(commandName)
                || "ZDIFFSTORE".equals(commandName) || "ZINTERSTORE".equals(commandName) || "ZUNIONSTORE".equals(commandName)
                || "ZRANGESTORE".equals(commandName) || "PFMERGE".equals(commandName)) {
            StringBuilder sb = new StringBuilder();
            for (RedisMessage arg : args) {
                if (sb.length() > 0) sb.append('\0');
                sb.append(arg.asString());
            }
            return sb.toString();
        }
        if ("SUNION".equals(commandName) || "SINTER".equals(commandName) || "SDIFF".equals(commandName)
                || "ZDIFF".equals(commandName) || "ZINTER".equals(commandName) || "ZUNION".equals(commandName)
                || "ZINTERCARD".equals(commandName)) {
            StringBuilder sb = new StringBuilder();
            for (RedisMessage arg : args) {
                if (sb.length() > 0) sb.append('\0');
                sb.append(arg.asString());
            }
            return sb.toString();
        }
        if ("SORT".equals(commandName)) {
            return args.get(0).asString();
        }
        if ("TOUCH".equals(commandName)) {
            StringBuilder sb = new StringBuilder();
            for (RedisMessage arg : args) {
                if (sb.length() > 0) sb.append('\0');
                sb.append(arg.asString());
            }
            return sb.toString();
        }
        if ("COPY".equals(commandName)) {
            return args.get(0).asString() + "\0" + args.get(1).asString();
        }
        if ("MOVE".equals(commandName)) {
            return args.get(0).asString();
        }
        return args.get(0).asString();
    }

    public static boolean isGlobalWrite(String commandName) {
        return "FLUSHDB".equals(commandName) || "FLUSHALL".equals(commandName)
                || "SAVE".equals(commandName) || "BGSAVE".equals(commandName) || "BGREWRITEAOF".equals(commandName);
    }

    public static boolean isSingleKeyWrite(String commandName) {
        return switch (commandName) {
            case "SET", "SETNX", "GETSET", "GETEX", "GETDEL", "SETEX", "PSETEX",
                 "INCR", "INCRBY", "INCRBYFLOAT", "DECR", "DECRBY", "APPEND", "SETRANGE",
                 "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT", "PERSIST", "TYPE",
                 "LPUSH", "RPUSH", "LPUSHX", "RPUSHX", "LPOP", "RPOP", "LSET", "LTRIM", "LREM",
                 "LINSERT", "LPOS", "SORT",
                 "HSET", "HMSET", "HDEL", "HSETNX", "HINCRBY", "HINCRBYFLOAT",
                 "SADD", "SREM", "SPOP",
                 "ZADD", "ZINCRBY", "ZREM", "ZPOPMIN", "ZPOPMAX",
                 "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE", "ZREMRANGEBYLEX",
                 "SETBIT", "BITOP",
                 "PFADD",
                 "GEOADD",
                 "XADD", "XTRIM", "XDEL",
                 "DUMP", "RESTORE",
                 "MOVE",
                 "STRLEN", "GETRANGE", "BITCOUNT", "BITPOS", "GETBIT",
                 "LLEN", "LINDEX", "LRANGE",
                 "HGET", "HMGET", "HGETALL", "HLEN", "HEXISTS", "HKEYS", "HVALS", "HSTRLEN", "HRANDFIELD",
                 "SMEMBERS", "SISMEMBER", "SCARD", "SRANDMEMBER", "SMISMEMBER",
                 "ZSCORE", "ZMSCORE", "ZRANK", "ZREVRANK", "ZCARD", "ZCOUNT",
                 "ZRANGE", "ZREVRANGE", "ZRANGEBYSCORE", "ZREVRANGEBYSCORE",
                 "ZRANDMEMBER", "ZLEXCOUNT", "ZRANGEBYLEX", "ZREVRANGEBYLEX",
                 "XLEN", "XRANGE", "XREVRANGE", "XREAD", "XPENDING",
                 "PFCOUNT",
                 "GEODIST", "GEOPOS", "GEORADIUS", "GEORADIUSBYMEMBER", "GEOHASH",
                 "TTL", "PTTL" -> true;
            default -> false;
        };
    }
}
