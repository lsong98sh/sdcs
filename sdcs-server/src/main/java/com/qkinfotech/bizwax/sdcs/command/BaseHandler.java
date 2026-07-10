package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;

import java.util.List;

public abstract class BaseHandler {

    protected final DatabaseManager databaseManager;
    protected final PersistenceManager persistenceManager;

    protected BaseHandler(DatabaseManager databaseManager, PersistenceManager persistenceManager) {
        this.databaseManager = databaseManager;
        this.persistenceManager = persistenceManager;
    }

    protected MemoryStore store() {
        return databaseManager.getStore();
    }

    protected RedisMessage error(String msg) {
        return RedisMessage.error(msg);
    }

    protected RedisMessage ok() {
        return RedisMessage.simpleString("OK");
    }

    protected RedisMessage integer(long value) {
        return RedisMessage.integer(value);
    }

    protected RedisMessage bulkString(byte[] data) {
        return RedisMessage.bulkString(data);
    }

    protected RedisMessage nullBulkString() {
        return RedisMessage.bulkString((byte[]) null);
    }

    protected RedisMessage array(RedisMessage... elements) {
        return RedisMessage.array(elements);
    }

    protected String arg(List<RedisMessage> args, int index) {
        return args.get(index).asString();
    }

    protected byte[] argData(List<RedisMessage> args, int index) {
        return args.get(index).getData();
    }

    /**
     * Validate argument count. Returns error message if invalid, null if valid.
     */
    protected String validateArgCount(String cmd, List<RedisMessage> args, int expected) {
        if (args.size() != expected) {
            return "wrong number of arguments for '" + cmd + "' command";
        }
        return null;
    }

    protected String validateMinArgCount(String cmd, List<RedisMessage> args, int min) {
        if (args.size() < min) {
            return "wrong number of arguments for '" + cmd + "' command";
        }
        return null;
    }

    protected boolean requiresForceConfirmation(List<RedisMessage> args) {
        String requirepass = com.qkinfotech.bizwax.sdcs.config.SDCSConfig.getInstance().getRequirepass();
        if (requirepass == null) {
            return false;
        }
        if (args.isEmpty()) {
            return true;
        }
        return !"FORCE".equalsIgnoreCase(args.get(0).asString());
    }
}
