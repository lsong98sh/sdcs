package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Map;
import java.util.function.Function;

public class ConnectionHandlers extends BaseHandler {

    public ConnectionHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public RedisMessage handleAuth(List<RedisMessage> args) {
        if (args.size() < 1) {
            return RedisMessage.error("ERR wrong number of arguments for 'auth' command");
        }
        return RedisMessage.error("ERR AUTH is handled at connection level");
    }

    public RedisMessage handleHello(List<RedisMessage> args) {
        if (args.size() > 0) {
            String protoVersion = args.get(0).asString();
            if (!"3".equals(protoVersion)) {
                return RedisMessage.error("ERR wrong number of arguments for 'hello' command");
            }
        }
        List<RedisMessage> fields = new ArrayList<>();
        fields.add(RedisMessage.bulkString("proto".getBytes()));
        fields.add(RedisMessage.integer(3));
        fields.add(RedisMessage.bulkString("id".getBytes()));
        fields.add(RedisMessage.bulkString(UUID.randomUUID().toString().getBytes()));
        fields.add(RedisMessage.bulkString("mode".getBytes()));
        fields.add(RedisMessage.bulkString("standalone".getBytes()));
        fields.add(RedisMessage.bulkString("role".getBytes()));
        fields.add(RedisMessage.bulkString("master".getBytes()));
        fields.add(RedisMessage.bulkString("modules".getBytes()));
        fields.add(RedisMessage.array(new RedisMessage[0]));
        fields.add(RedisMessage.bulkString("version".getBytes()));
        fields.add(RedisMessage.bulkString("6.2.0".getBytes()));
        fields.add(RedisMessage.bulkString("sdcs_version".getBytes()));
        fields.add(RedisMessage.bulkString("1.0.0".getBytes()));
        return RedisMessage.array(fields);
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("AUTH", this::handleAuth);
        registry.put("HELLO", this::handleHello);
    }
}
