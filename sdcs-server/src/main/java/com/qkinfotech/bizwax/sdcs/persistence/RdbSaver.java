package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.common.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class RdbSaver {

    public void save(Map<String, RedisData> store, File file, int dbIndex) throws IOException {
        File tempFile = new File(file.getParent(), "temp-" + file.getName());

        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            RdbEncoder encoder = new RdbEncoder(bos);

            encoder.writeByte(dbIndex);

            encoder.writeBytes(RdbConstants.MAGIC);
            encoder.writeBytes(RdbConstants.VERSION.getBytes(StandardCharsets.UTF_8));

            encoder.writeByte(RdbConstants.OP_SELECTDB);
            encoder.writeLength(0);

            for (Map.Entry<String, RedisData> entry : store.entrySet()) {
                String key = entry.getKey();
                RedisData data = entry.getValue();
                if (data == null || data.isExpired()) continue;

                long expireAt = data.getExpireAtMs();
                if (expireAt >= 0) {
                    encoder.writeByte(RdbConstants.OP_EXPIRETIME_MS);
                    encoder.writeLong(expireAt);
                }

                int type = switch (data.getType()) {
                    case STRING -> RdbType.STRING;
                    case LIST -> RdbType.LIST;
                    case SET -> RdbType.SET;
                    case ZSET -> RdbType.ZSET;
                    case HASH -> RdbType.HASH;
                    case STREAM -> RdbType.STREAM;
                    default -> RdbType.STRING;
                };
                encoder.writeByte(type);

                encoder.writeString(key);

                switch (data.getType()) {
                    case STRING -> encoder.writeString(data.getValue(byte[].class));
                    case LIST -> encoder.writeList(data.getValue(RedisList.class));
                    case SET -> encoder.writeSet(data.getValue(RedisSet.class));
                    case ZSET -> encoder.writeZSet(data.getValue(RedisZSet.class));
                    case HASH -> encoder.writeHash(data.getValue(RedisHash.class));
                    case STREAM -> {}
                }
            }

            encoder.writeByte(RdbConstants.OP_EOF);
            bos.flush();
        }

        if (file.exists()) file.delete();
        if (!tempFile.renameTo(file)) {
            throw new IOException("Failed to rename temp RDB file");
        }
    }
}
