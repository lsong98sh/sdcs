package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.transaction.TransactionManager;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;

public class TransactionHandlers extends BaseHandler {

    public TransactionHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public static boolean isTransactionCommand(String cmd) {
        return switch (cmd) {
            case "MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH", "SUBSCRIBE", "UNSUBSCRIBE", "QUIT" -> true;
            default -> false;
        };
    }

    public RedisMessage handleMulti(List<RedisMessage> args) {
        try {
            TransactionManager.multi();
            return RedisMessage.simpleString("OK");
        } catch (IllegalStateException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public RedisMessage handleExec(CommandDispatcher dispatcher, List<RedisMessage> args) {
        if (!TransactionManager.isInTransaction()) {
            return RedisMessage.error("ERR EXEC without MULTI");
        }
        List<RedisMessage> commands = TransactionManager.exec();
        if (commands == null) {
            return RedisMessage.array((List<RedisMessage>) null);
        }
        List<RedisMessage> results = new ArrayList<>();
        for (RedisMessage cmd : commands) {
            var elements = cmd.getElements();
            if (elements == null || elements.isEmpty()) continue;
            String cmdName = elements.get(0).asString().toUpperCase();
            var cmdArgs = elements.subList(1, elements.size());
            results.add(dispatcher.dispatch(cmdName, cmdArgs));
        }
        return RedisMessage.array(results.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleDiscard(List<RedisMessage> args) {
        if (!TransactionManager.isInTransaction()) {
            return RedisMessage.error("ERR DISCARD without MULTI");
        }
        TransactionManager.discard();
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handleWatch(List<RedisMessage> args) {
        if (args.isEmpty()) {
            return RedisMessage.error("ERR wrong number of arguments for 'watch' command");
        }
        String[] keys = args.stream().map(RedisMessage::asString).toArray(String[]::new);
        TransactionManager.watch(keys);
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handleUnwatch(List<RedisMessage> args) {
        TransactionManager.discard();
        return RedisMessage.simpleString("OK");
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("MULTI", this::handleMulti);
        registry.put("DISCARD", this::handleDiscard);
        registry.put("WATCH", this::handleWatch);
        registry.put("UNWATCH", this::handleUnwatch);
    }
}
