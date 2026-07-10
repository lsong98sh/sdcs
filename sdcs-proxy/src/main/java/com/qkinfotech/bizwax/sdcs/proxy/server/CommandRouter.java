package com.qkinfotech.bizwax.sdcs.proxy.server;

import com.qkinfotech.bizwax.sdcs.proxy.registry.RegistryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    public static void dispatch(ClientWriter writer, String command,
                                 List<String> args, RegistryManager registryManager) {
        CommandType type = getCommandType(command);

        if (type == CommandType.UNSUPPORTED_COMPLEX) {
            if (registryManager.hasAllSlotNode()) {
                registryManager.forwardToBackend(writer, command, args, registryManager.getAllSlotAddrs(), type);
            } else {
                writer.write(RespCodec.ERR_NO_HASH);
            }
            return;
        }

        // Extract key from args
        String key = extractKey(command, args);
        if (key == null) {
            // Commands like PING, INFO, etc. - forward to any backend
            registryManager.forwardToBackend(writer, command, args, registryManager.getAnyAddrs(), type);
            return;
        }

        // Route by key hash
        int slot = RegistryManager.crc16(key) % 1024;
        List<String> targets = registryManager.lookup(slot);

        if (targets == null || targets.isEmpty()) {
            writer.write(RespCodec.error("ERR SLOT " + slot + " not covered"));
            return;
        }

        registryManager.forwardToBackend(writer, command, args, targets, type);
    }

    private static String extractKey(String command, List<String> args) {
        if (args.isEmpty()) return null;

        return switch (command) {
            case "PING", "QUIT", "INFO", "TIME", "FLUSHALL", "FLUSHDB",
                 "DBSIZE", "CLIENT", "CONFIG", "COMMAND", "SLOWLOG", "SHUTDOWN",
                 "BGSAVE", "BGREWRITEAOF", "LASTSAVE", "SAVE" -> null;

            case "GET", "SET", "DEL", "EXISTS", "EXPIRE", "EXPIREAT", "TTL", "PTTL",
                 "PERSIST", "TYPE", "RENAME", "RENAMENX", "MOVE", "COPY",
                 "DUMP", "RESTORE", "SORT",
                 "GETSET", "GETEX", "GETDEL", "SETEX", "PSETEX", "SETNX", "SETRANGE",
                 "APPEND", "STRLEN", "INCR", "DECR", "INCRBY", "DECRBY", "INCRBYFLOAT",
                 "GETRANGE", "MSET", "MGET", "MSETNX",
                 "HDEL", "HEXISTS", "HGET", "HGETALL", "HINCRBY", "HINCRBYFLOAT",
                 "HKEYS", "HLEN", "HMGET", "HMSET", "HSET", "HSETNX", "HVALS",
                 "HRANDFIELD", "HSTRLEN",
                 "LINDEX", "LINSERT", "LLEN", "LPOP", "LPUSH", "LPUSHX", "LRANGE",
                 "LREM", "LSET", "LTRIM", "RPOP", "RPUSH", "RPUSHX", "LPOS",
                 "SADD", "SCARD", "SDIFF", "SINTER", "SISMEMBER", "SMEMBERS",
                 "SMISMEMBER", "SMOVE", "SPOP", "SRANDMEMBER", "SREM", "SUNION",
                 "SSCAN", "ZADD", "ZCARD", "ZCOUNT", "ZINCRBY", "ZPOPMAX", "ZPOPMIN",
                 "ZRANGE", "ZRANK", "ZREM", "ZREMRANGEBYLEX", "ZREMRANGEBYRANK",
                 "ZREMRANGEBYSCORE", "ZREVRANK", "ZSCORE", "ZRANDMEMBER",
                 "ZDIFF", "ZINTER", "ZUNION", "ZINTERSTORE", "ZUNIONSTORE",
                 "ZDIFFSTORE", "ZINTERCARD", "ZRANGESTORE", "ZMSCORE",
                 "ZLEXCOUNT", "ZREVRANGE", "ZREVRANGEBYLEX", "ZREVRANGEBYSCORE",
                 "XADD", "XLEN", "XRANGE", "XREVRANGE", "XREAD", "XTRIM",
                 "XDEL", "XACK", "XGROUP", "XREADGROUP", "XCLAIM", "XPENDING",
                 "XINFO",
                 "PFADD", "PFCOUNT", "PFMERGE",
                 "GEOADD", "GEODIST", "GEOPOS", "GEORADIUS", "GEORADIUSBYMEMBER",
                 "GEOHASH", "GEOSEARCH",
                 "SETBIT", "GETBIT", "BITCOUNT", "BITPOS",
                 "SUBSCRIBE", "PUBLISH", "PUBSUB",
                 "EVAL", "EVALSHA", "EVAL_RO",
                 "WAIT" -> args.get(0);

            case "BLPOP", "BRPOP", "BLMOVE", "BRPOPLPUSH", "BZPOPMIN", "BZPOPMAX" -> args.get(0);

            case "LCS" -> args.size() > 0 ? args.get(0) : null;
            case "BITOP" -> args.size() > 1 ? args.get(1) : null;
            case "OBJECT" -> args.size() > 1 ? args.get(1) : null;
            case "SORT_RO" -> args.get(0);
            case "EXPIRETIME", "PEXPIRETIME" -> args.get(0);
            case "HEXPIRETIME", "HPEXPIRETIME" -> args.get(0);

            default -> {
                log.debug("Unknown command {}, treating first arg as key", command);
                yield args.get(0);
            }
        };
    }

    private static CommandType getCommandType(String command) {
        if (isUnsupportedComplex(command)) return CommandType.UNSUPPORTED_COMPLEX;
        if (isReadCommand(command)) return CommandType.READ;
        return CommandType.WRITE;
    }

    private static boolean isReadCommand(String cmd) {
        return switch (cmd) {
            case "GET", "MGET", "EXISTS", "TYPE", "TTL", "PTTL", "DUMP",
                 "STRLEN", "GETRANGE", "GETSET", "GETEX", "GETDEL",
                 "HGET", "HEXISTS", "HGETALL", "HKEYS", "HLEN", "HMGET", "HVALS",
                 "HSTRLEN", "HRANDFIELD",
                 "LINDEX", "LLEN", "LRANGE", "LPOS",
                 "SCARD", "SISMEMBER", "SMEMBERS", "SMISMEMBER", "SRANDMEMBER",
                 "SSCAN",
                 "ZCARD", "ZCOUNT", "ZRANGE", "ZRANK", "ZREVRANK", "ZSCORE",
                 "ZLEXCOUNT", "ZREVRANGE", "ZREVRANGEBYLEX", "ZREVRANGEBYSCORE",
                 "ZMSCORE", "ZRANDMEMBER", "ZINTERCARD",
                 "XLEN", "XRANGE", "XREVRANGE", "XREAD", "XPENDING", "XINFO",
                 "PFCOUNT",
                 "GEODIST", "GEOPOS", "GEORADIUS", "GEORADIUSBYMEMBER",
                 "GEOHASH", "GEOSEARCH",
                 "GETBIT", "BITCOUNT", "BITPOS",
                 "SCAN", "DBSIZE", "INFO", "TIME", "COMMAND",
                 "PING", "ECHO", "HELLO", "AUTH",
                 "CLIENT", "CONFIG", "SLOWLOG", "LASTSAVE",
                 "MEMORY", "OBJECT",
                 "PUBSUB", "SORT_RO",
                 "EVAL_RO" -> true;
            default -> false;
        };
    }

    private static boolean isUnsupportedComplex(String cmd) {
        return switch (cmd) {
            case "PUBLISH", "SUBSCRIBE", "PSUBSCRIBE", "UNSUBSCRIBE", "PUNSUBSCRIBE",
                 "SCAN", "SSCAN", "HSCAN", "ZSCAN",
                 "SELECT",
                 "MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH",
                 "SORT", "SORT_RO",
                 "EVAL", "EVALSHA", "EVAL_RO", "SCRIPT",
                 "KEYS" -> true;
            default -> false;
        };
    }
}
