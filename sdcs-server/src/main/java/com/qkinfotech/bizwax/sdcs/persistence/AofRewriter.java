package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.common.*;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.protocol.RespEncoder;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class AofRewriter {

    private static final Logger log = LoggerFactory.getLogger(AofRewriter.class);
    private static final int DB_COUNT = 16;

    private final DatabaseManager databaseManager;

    public AofRewriter(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void rewrite(File newBaseFile) throws IOException {
        long count = 0;

        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(newBaseFile))) {
            RespEncoder encoder = new RespEncoder(8192);

            for (int dbIdx = 0; dbIdx < DB_COUNT; dbIdx++) {
                MemoryStore store = databaseManager.getStore(dbIdx);
                if (store == null || store.size() == 0) continue;

                RedisMessage selectCmd = buildSelectCmd(dbIdx);
                ByteBuffer sbuf = ByteBuffer.allocate(64);
                encoder.encode(selectCmd, sbuf);
                sbuf.flip();
                byte[] sbytes = new byte[sbuf.remaining()];
                sbuf.get(sbytes);
                bos.write(sbytes);

                for (Map.Entry<String, RedisData> entry : store.getStore().entrySet()) {
                    String key = entry.getKey();
                    RedisData data = entry.getValue();
                    if (data == null || data.isExpired()) continue;

                    RedisMessage cmd = objectToCommand(key, data);
                    if (cmd != null) {
                        ByteBuffer buf = ByteBuffer.allocate(8192);
                        encoder.encode(cmd, buf);
                        buf.flip();
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        bos.write(bytes);
                        count++;

                        if (data.getExpireAtMs() >= 0) {
                            RedisMessage expireCmd = buildExpireCmd(key, data.getExpireAtMs());
                            ByteBuffer ebuf = ByteBuffer.allocate(8192);
                            encoder.encode(expireCmd, ebuf);
                            ebuf.flip();
                            byte[] ebytes = new byte[ebuf.remaining()];
                            ebuf.get(ebytes);
                            bos.write(ebytes);
                        }
                    }
                }
            }
            bos.flush();
        }

        log.info("AOF rewrite finished. Keys: {}, Size: {}", count, newBaseFile.length());
    }

    private RedisMessage buildSelectCmd(int dbIndex) {
        return RedisMessage.array(
                RedisMessage.bulkString("SELECT".getBytes()),
                RedisMessage.bulkString(String.valueOf(dbIndex).getBytes())
        );
    }

    private RedisMessage objectToCommand(String key, RedisData data) {
        return switch (data.getType()) {
            case STRING -> {
                byte[] val = data.getValue(byte[].class);
                yield RedisMessage.array(
                        RedisMessage.bulkString("SET".getBytes()),
                        RedisMessage.bulkString(key.getBytes()),
                        RedisMessage.bulkString(val)
                );
            }
            case LIST -> {
                RedisList list = data.getValue(RedisList.class);
                List<byte[]> items = list.lrange(0, -1);
                if (items.isEmpty()) yield null;
                RedisMessage[] msgs = new RedisMessage[items.size() + 2];
                msgs[0] = RedisMessage.bulkString("RPUSH".getBytes());
                msgs[1] = RedisMessage.bulkString(key.getBytes());
                for (int i = 0; i < items.size(); i++) {
                    msgs[i + 2] = RedisMessage.bulkString(items.get(i));
                }
                yield RedisMessage.array(msgs);
            }
            case HASH -> {
                RedisHash hash = data.getValue(RedisHash.class);
                Map<String, byte[]> map = hash.hgetAll();
                if (map.isEmpty()) yield null;
                RedisMessage[] msgs = new RedisMessage[map.size() * 2 + 2];
                msgs[0] = RedisMessage.bulkString("HSET".getBytes());
                msgs[1] = RedisMessage.bulkString(key.getBytes());
                int i = 2;
                for (Map.Entry<String, byte[]> field : map.entrySet()) {
                    msgs[i++] = RedisMessage.bulkString(field.getKey().getBytes());
                    msgs[i++] = RedisMessage.bulkString(field.getValue());
                }
                yield RedisMessage.array(msgs);
            }
            case SET -> {
                RedisSet set = data.getValue(RedisSet.class);
                List<byte[]> members = new java.util.ArrayList<>(set.smembers());
                if (members.isEmpty()) yield null;
                RedisMessage[] msgs = new RedisMessage[members.size() + 2];
                msgs[0] = RedisMessage.bulkString("SADD".getBytes());
                msgs[1] = RedisMessage.bulkString(key.getBytes());
                for (int i = 0; i < members.size(); i++) {
                    msgs[i + 2] = RedisMessage.bulkString(members.get(i));
                }
                yield RedisMessage.array(msgs);
            }
            case ZSET -> {
                RedisZSet zset = data.getValue(RedisZSet.class);
                List<RedisZSet.ZSetEntry> entries = zset.zrange(0, -1);
                if (entries.isEmpty()) yield null;
                RedisMessage[] msgs = new RedisMessage[entries.size() * 2 + 2];
                msgs[0] = RedisMessage.bulkString("ZADD".getBytes());
                msgs[1] = RedisMessage.bulkString(key.getBytes());
                int i = 2;
                for (RedisZSet.ZSetEntry entry : entries) {
                    String scoreStr = (entry.score() % 1 == 0) ?
                            String.valueOf((long) entry.score()) : String.valueOf(entry.score());
                    msgs[i++] = RedisMessage.bulkString(scoreStr.getBytes());
                    msgs[i++] = RedisMessage.bulkString(entry.member());
                }
                yield RedisMessage.array(msgs);
            }
            default -> null;
        };
    }

    private RedisMessage buildExpireCmd(String key, long expireAtMs) {
        return RedisMessage.array(
                RedisMessage.bulkString("PEXPIREAT".getBytes()),
                RedisMessage.bulkString(key.getBytes()),
                RedisMessage.bulkString(String.valueOf(expireAtMs).getBytes())
        );
    }
}
