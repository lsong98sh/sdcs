package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.pubsub.PubSubManager;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.function.Function;

public class PubSubHandlers extends BaseHandler {

    public PubSubHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public RedisMessage handlePublish(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("ERR wrong number of arguments for 'publish' command");
        }
        String channel = args.get(0).asString();
        RedisMessage message = args.get(1);
        long count = PubSubManager.getInstance().publish(channel, message);
        return RedisMessage.integer(count);
    }

    public RedisMessage handleSubscribe(List<RedisMessage> args) {
        return RedisMessage.error("ERR SUBSCRIBE is not supported in command dispatcher (use dedicated subscriber)");
    }

    public RedisMessage handleUnsubscribe(List<RedisMessage> args) {
        return RedisMessage.error("ERR UNSUBSCRIBE is not supported in command dispatcher (use dedicated subscriber)");
    }

    public RedisMessage handlePSubscribe(List<RedisMessage> args) {
        return RedisMessage.error("ERR PSUBSCRIBE is not supported in command dispatcher (use dedicated subscriber)");
    }

    public RedisMessage handlePUnsubscribe(List<RedisMessage> args) {
        return RedisMessage.error("ERR PUNSUBSCRIBE is not supported in command dispatcher (use dedicated subscriber)");
    }

    public RedisMessage handlePubSub(List<RedisMessage> args) {
        if (args.isEmpty()) {
            return RedisMessage.error("ERR wrong number of arguments for 'pubsub' command");
        }
        String subCommand = args.get(0).asString().toUpperCase();
        switch (subCommand) {
            case "CHANNELS" -> {
                String pattern = args.size() > 1 ? args.get(1).asString() : "*";
                Set<String> channels = PubSubManager.getInstance().getChannels();
                List<RedisMessage> result = new ArrayList<>();
                for (String ch : channels) {
                    if (MemoryStore.matchPattern(ch, pattern)) {
                        result.add(RedisMessage.bulkString(ch.getBytes()));
                    }
                }
                return RedisMessage.array(result.toArray(new RedisMessage[0]));
            }
            case "NUMSUB" -> {
                if (args.size() < 2) {
                    return RedisMessage.error("ERR wrong number of arguments for 'pubsub numsub' command");
                }
                List<RedisMessage> result = new ArrayList<>();
                for (int i = 1; i < args.size(); i++) {
                    String channel = args.get(i).asString();
                    result.add(RedisMessage.bulkString(channel.getBytes()));
                    result.add(RedisMessage.integer(PubSubManager.getInstance().numSub(channel)));
                }
                return RedisMessage.array(result.toArray(new RedisMessage[0]));
            }
            case "NUMPAT" -> {
                return RedisMessage.integer(PubSubManager.getInstance().numPat());
            }
            default -> {
                return RedisMessage.error("ERR Unknown subcommand '" + subCommand + "'");
            }
        }
    }

    public RedisMessage handleMonitor(List<RedisMessage> args) {
        return RedisMessage.error("ERR MONITOR is not supported in command dispatcher (use dedicated monitor connection)");
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("PUBLISH", this::handlePublish);
        registry.put("SUBSCRIBE", this::handleSubscribe);
        registry.put("UNSUBSCRIBE", this::handleUnsubscribe);
        registry.put("PSUBSCRIBE", this::handlePSubscribe);
        registry.put("PUNSUBSCRIBE", this::handlePUnsubscribe);
        registry.put("PUBSUB", this::handlePubSub);
        registry.put("MONITOR", this::handleMonitor);
    }
}
