package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.scripting.LuaScriptEngine;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Function;

public class ScriptingHandlers extends BaseHandler {

    public ScriptingHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public RedisMessage handleEval(List<RedisMessage> args, CommandDispatcher dispatcher) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'eval' command");
        }
        String script = args.get(0).asString();
        int numkeys;
        try {
            numkeys = Integer.parseInt(args.get(1).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR value is not an integer or out of range");
        }
        if (numkeys < 0) {
            return RedisMessage.error("ERR Number of keys can't be negative");
        }
        if (args.size() < 2 + numkeys) {
            return RedisMessage.error("wrong number of arguments for 'eval' command");
        }
        List<String> keys = new ArrayList<>();
        List<String> evalArgs = new ArrayList<>();
        for (int i = 0; i < numkeys; i++) {
            keys.add(args.get(2 + i).asString());
        }
        for (int i = 2 + numkeys; i < args.size(); i++) {
            evalArgs.add(args.get(i).asString());
        }
        return LuaScriptEngine.getInstance().eval(script, keys, evalArgs, dispatcher);
    }

    public RedisMessage handleEvalSha(List<RedisMessage> args, CommandDispatcher dispatcher) {
        if (args.size() < 2) {
            return RedisMessage.error("wrong number of arguments for 'evalsha' command");
        }
        String sha1 = args.get(0).asString();
        int numkeys;
        try {
            numkeys = Integer.parseInt(args.get(1).asString());
        } catch (NumberFormatException e) {
            return RedisMessage.error("ERR value is not an integer or out of range");
        }
        if (numkeys < 0) {
            return RedisMessage.error("ERR Number of keys can't be negative");
        }
        if (args.size() < 2 + numkeys) {
            return RedisMessage.error("wrong number of arguments for 'evalsha' command");
        }
        List<String> keys = new ArrayList<>();
        List<String> evalArgs = new ArrayList<>();
        for (int i = 0; i < numkeys; i++) {
            keys.add(args.get(2 + i).asString());
        }
        for (int i = 2 + numkeys; i < args.size(); i++) {
            evalArgs.add(args.get(i).asString());
        }
        return LuaScriptEngine.getInstance().evalsha(sha1, keys, evalArgs, dispatcher);
    }

    public RedisMessage handleScript(List<RedisMessage> args) {
        if (args.isEmpty()) {
            return RedisMessage.error("wrong number of arguments for 'script' command");
        }
        String subcommand = args.get(0).asString().toUpperCase();
        switch (subcommand) {
            case "LOAD" -> {
                if (args.size() != 2) {
                    return RedisMessage.error("wrong number of arguments for 'script load' command");
                }
                String script = args.get(1).asString();
                String sha1 = LuaScriptEngine.getInstance().load(script);
                return RedisMessage.bulkString(sha1.getBytes());
            }
            case "EXISTS" -> {
                if (args.size() < 2) {
                    return RedisMessage.error("wrong number of arguments for 'script exists' command");
                }
                String[] sha1s = new String[args.size() - 1];
                for (int i = 0; i < sha1s.length; i++) {
                    sha1s[i] = args.get(i + 1).asString();
                }
                List<Boolean> exists = LuaScriptEngine.getInstance().scriptExists(sha1s);
                RedisMessage[] results = new RedisMessage[exists.size()];
                for (int i = 0; i < exists.size(); i++) {
                    results[i] = RedisMessage.integer(exists.get(i) ? 1 : 0);
                }
                return RedisMessage.array(results);
            }
            case "FLUSH" -> {
                LuaScriptEngine.getInstance().scriptFlush();
                return RedisMessage.simpleString("OK");
            }
            default -> {
                return RedisMessage.error("ERR unknown subcommand '" + subcommand + "'");
            }
        }
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("SCRIPT", this::handleScript);
    }
}
