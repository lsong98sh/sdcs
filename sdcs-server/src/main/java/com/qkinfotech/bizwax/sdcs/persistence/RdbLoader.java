package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.common.RedisData;
import com.qkinfotech.bizwax.sdcs.common.RedisDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public class RdbLoader {

    private static final Logger log = LoggerFactory.getLogger(RdbLoader.class);

    public static class LoadResult {
        private final int dbIndex;
        private final Map<String, RedisData> data;

        public LoadResult(int dbIndex, Map<String, RedisData> data) {
            this.dbIndex = dbIndex;
            this.data = data;
        }

        public int getDbIndex() {
            return dbIndex;
        }

        public Map<String, RedisData> getData() {
            return data;
        }
    }

    public LoadResult load(File file) throws IOException {
        if (!file.exists()) {
            return null;
        }

        Map<String, RedisData> store = new HashMap<>();

        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
            int dbIndex = bis.read();
            if (dbIndex < 0) {
                throw new IOException("Unexpected EOF reading db index");
            }

            RdbDecoder decoder = new RdbDecoder(bis);

            byte[] magic = decoder.readBytes(5);
            if (!Arrays.equals(magic, RdbConstants.MAGIC)) {
                throw new IOException("Invalid RDB file: Bad Magic");
            }

            byte[] version = decoder.readBytes(4);

            long expireAt = -1;

            while (true) {
                int type = decoder.readByte();

                if (type == RdbConstants.OP_EOF) {
                    break;
                } else if (type == RdbConstants.OP_SELECTDB) {
                    decoder.readLength();
                    continue;
                } else if (type == RdbConstants.OP_EXPIRETIME_MS) {
                    expireAt = decoder.readLong();
                    continue;
                } else if (type == RdbConstants.OP_EXPIRETIME) {
                    expireAt = (long) decoder.readInt() * 1000;
                    continue;
                } else if (type == RdbConstants.OP_RESIZEDB || type == RdbConstants.OP_AUX) {
                    continue;
                }

                String key = decoder.readStringUtf8();
                RedisData data = readValue(decoder, type, expireAt);
                if (data != null && !data.isExpired()) {
                    store.put(key, data);
                }
                expireAt = -1;
            }

            log.info("RDB loaded: {} keys from DB {} in {}", store.size(), dbIndex, file.getName());
            return new LoadResult(dbIndex, store);
        }
    }

    private RedisData readValue(RdbDecoder decoder, int type, long expireAt) throws IOException {
        return switch (type) {
            case RdbType.STRING -> {
                byte[] val = decoder.readString();
                yield expireAt >= 0 ? new RedisData(RedisDataType.STRING, val, expireAt)
                        : new RedisData(RedisDataType.STRING, val);
            }
            case RdbType.LIST -> {
                var val = decoder.readList();
                yield expireAt >= 0 ? new RedisData(RedisDataType.LIST, val, expireAt)
                        : new RedisData(RedisDataType.LIST, val);
            }
            case RdbType.SET -> {
                var val = decoder.readSet();
                yield expireAt >= 0 ? new RedisData(RedisDataType.SET, val, expireAt)
                        : new RedisData(RedisDataType.SET, val);
            }
            case RdbType.ZSET -> {
                var val = decoder.readZSet();
                yield expireAt >= 0 ? new RedisData(RedisDataType.ZSET, val, expireAt)
                        : new RedisData(RedisDataType.ZSET, val);
            }
            case RdbType.HASH -> {
                var val = decoder.readHash();
                yield expireAt >= 0 ? new RedisData(RedisDataType.HASH, val, expireAt)
                        : new RedisData(RedisDataType.HASH, val);
            }
            default -> throw new IOException("Unknown value type: " + type);
        };
    }
}
