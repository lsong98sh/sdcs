package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.transaction.TransactionManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class CommandDispatcher {

    private final StringHandlers stringHandlers;
    private final ListHandlers listHandlers;
    private final SetHandlers setHandlers;
    private final HashHandlers hashHandlers;
    private final ZSetHandlers zsetHandlers;
    private final StreamHandlers streamHandlers;
    private final GeoHandlers geoHandlers;
    private final KeyHandlers keyHandlers;
    private final ConnectionHandlers connectionHandlers;
    private final ServerHandlers serverHandlers;
    private final PubSubHandlers pubSubHandlers;
    private final TransactionHandlers transactionHandlers;
    private final ScriptingHandlers scriptingHandlers;

    private final Map<String, Function<List<RedisMessage>, RedisMessage>> dispatchMap = new HashMap<>();

    public CommandDispatcher(DatabaseManager databaseManager) {
        this(databaseManager, null);
    }

    public CommandDispatcher(DatabaseManager databaseManager, PersistenceManager persistenceManager) {
        this.stringHandlers = new StringHandlers(databaseManager, persistenceManager);
        this.listHandlers = new ListHandlers(databaseManager, persistenceManager);
        this.setHandlers = new SetHandlers(databaseManager, persistenceManager);
        this.hashHandlers = new HashHandlers(databaseManager, persistenceManager);
        this.zsetHandlers = new ZSetHandlers(databaseManager, persistenceManager);
        this.streamHandlers = new StreamHandlers(databaseManager, persistenceManager);
        this.geoHandlers = new GeoHandlers(databaseManager, persistenceManager);
        this.keyHandlers = new KeyHandlers(databaseManager, persistenceManager);
        this.connectionHandlers = new ConnectionHandlers(databaseManager, persistenceManager);
        this.serverHandlers = new ServerHandlers(databaseManager, persistenceManager);
        this.pubSubHandlers = new PubSubHandlers(databaseManager, persistenceManager);
        this.transactionHandlers = new TransactionHandlers(databaseManager, persistenceManager);
        this.scriptingHandlers = new ScriptingHandlers(databaseManager, persistenceManager);

        // Each handler registers its own commands
        stringHandlers.registerCommands(dispatchMap);
        listHandlers.registerCommands(dispatchMap);
        setHandlers.registerCommands(dispatchMap);
        hashHandlers.registerCommands(dispatchMap);
        zsetHandlers.registerCommands(dispatchMap);
        streamHandlers.registerCommands(dispatchMap);
        geoHandlers.registerCommands(dispatchMap);
        keyHandlers.registerCommands(dispatchMap);
        connectionHandlers.registerCommands(dispatchMap);
        serverHandlers.registerCommands(dispatchMap);
        pubSubHandlers.registerCommands(dispatchMap);
        transactionHandlers.registerCommands(dispatchMap);
        scriptingHandlers.registerCommands(dispatchMap);

        // Special cases that need CommandDispatcher reference
        dispatchMap.put("QUIT", args -> null);
        dispatchMap.put("EXEC", args -> transactionHandlers.handleExec(this, args));
        dispatchMap.put("EVAL", args -> scriptingHandlers.handleEval(args, this));
        dispatchMap.put("EVALSHA", args -> scriptingHandlers.handleEvalSha(args, this));
    }

    public RedisMessage dispatch(String commandName, List<RedisMessage> args) {
        if (TransactionManager.isInTransaction() && !transactionHandlers.isTransactionCommand(commandName)) {
            RedisMessage[] cmdElements = new RedisMessage[args.size() + 1];
            cmdElements[0] = RedisMessage.bulkString(commandName.getBytes());
            for (int i = 0; i < args.size(); i++) {
                cmdElements[i + 1] = args.get(i);
            }
            TransactionManager.queueCommand(RedisMessage.array(cmdElements));
            return RedisMessage.simpleString("QUEUED");
        }

        Function<List<RedisMessage>, RedisMessage> handler = dispatchMap.get(commandName);
        if (handler != null) {
            return handler.apply(args);
        }
        return RedisMessage.error("unknown command '" + commandName.toLowerCase() + "'");
    }
}
