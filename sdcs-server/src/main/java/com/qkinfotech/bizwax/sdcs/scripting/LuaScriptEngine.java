package com.qkinfotech.bizwax.sdcs.scripting;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LuaScriptEngine {

    private static final LuaScriptEngine INSTANCE = new LuaScriptEngine();
    private final Map<String, String> scriptCache = new ConcurrentHashMap<>();

    public static LuaScriptEngine getInstance() { return INSTANCE; }

    public String load(String script) {
        String sha1 = sha1Hex(script);
        scriptCache.put(sha1, script);
        return sha1;
    }

    public boolean exists(String sha1) {
        return scriptCache.containsKey(sha1);
    }

    public RedisMessage eval(String script, List<String> keys, List<String> args, CommandDispatcher dispatcher) {
        String sha1 = sha1Hex(script);
        scriptCache.put(sha1, script);
        return executeInterpretedScript(script, keys, args, dispatcher);
    }

    public RedisMessage evalsha(String sha1, List<String> keys, List<String> args, CommandDispatcher dispatcher) {
        String script = scriptCache.get(sha1);
        if (script == null) {
            return RedisMessage.error("NOSCRIPT No matching script. Please use EVAL.");
        }
        return eval(script, keys, args, dispatcher);
    }

    private RedisMessage executeInterpretedScript(String script, List<String> keys, List<String> args, CommandDispatcher dispatcher) {
        String trimmed = script.trim();

        if (trimmed.startsWith("return redis.call(") || trimmed.startsWith("return redis.pcall(")) {
            String inner = trimmed.substring(trimmed.indexOf('(') + 1, trimmed.lastIndexOf(')'));
            String[] parts = parseArgs(inner);
            if (parts.length < 1) {
                return RedisMessage.error("ERR Error compiling script");
            }

            String cmd = parts[0].replace("'", "").replace("\"", "").toUpperCase();

            List<RedisMessage> cmdArgs = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String arg = parts[i].replace("'", "").replace("\"", "");
                if (arg.startsWith("KEYS[")) {
                    int idx = Integer.parseInt(arg.substring(5, arg.indexOf(']'))) - 1;
                    if (idx >= 0 && idx < keys.size()) {
                        cmdArgs.add(RedisMessage.bulkString(keys.get(idx).getBytes()));
                    }
                } else if (arg.startsWith("ARGV[")) {
                    int idx = Integer.parseInt(arg.substring(5, arg.indexOf(']'))) - 1;
                    if (idx >= 0 && idx < args.size()) {
                        cmdArgs.add(RedisMessage.bulkString(args.get(idx).getBytes()));
                    }
                } else {
                    cmdArgs.add(RedisMessage.bulkString(arg.getBytes()));
                }
            }

            return dispatcher.dispatch(cmd, cmdArgs);
        }

        if (trimmed.startsWith("redis.call(") || trimmed.startsWith("redis.pcall(")) {
            String inner = trimmed.substring(trimmed.indexOf('(') + 1, trimmed.lastIndexOf(')'));
            String[] parts = parseArgs(inner);
            if (parts.length < 1) {
                return RedisMessage.error("ERR Error compiling script");
            }
            String cmd = parts[0].replace("'", "").replace("\"", "").toUpperCase();
            List<RedisMessage> cmdArgs = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String arg = parts[i].replace("'", "").replace("\"", "");
                if (arg.startsWith("KEYS[")) {
                    int idx = Integer.parseInt(arg.substring(5, arg.indexOf(']'))) - 1;
                    if (idx >= 0 && idx < keys.size()) {
                        cmdArgs.add(RedisMessage.bulkString(keys.get(idx).getBytes()));
                    }
                } else if (arg.startsWith("ARGV[")) {
                    int idx = Integer.parseInt(arg.substring(5, arg.indexOf(']'))) - 1;
                    if (idx >= 0 && idx < args.size()) {
                        cmdArgs.add(RedisMessage.bulkString(args.get(idx).getBytes()));
                    }
                } else {
                    cmdArgs.add(RedisMessage.bulkString(arg.getBytes()));
                }
            }
            dispatcher.dispatch(cmd, cmdArgs);
            return RedisMessage.simpleString("OK");
        }

        if (trimmed.startsWith("return ")) {
            String val = trimmed.substring(7).trim();
            val = val.replace("'", "").replace("\"", "");
            return RedisMessage.bulkString(val.getBytes());
        }

        return RedisMessage.error("ERR Error compiling script (unsupported script pattern)");
    }

    private String[] parseArgs(String s) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        int depth = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                } else {
                    current.append(c);
                }
            } else if (c == '\'' || c == '"') {
                inQuote = true;
                quoteChar = c;
            } else if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0 && !inQuote) {
                result.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString().trim());
        }
        return result.toArray(new String[0]);
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Boolean> scriptExists(String... sha1s) {
        List<Boolean> result = new ArrayList<>();
        for (String sha : sha1s) {
            result.add(scriptCache.containsKey(sha));
        }
        return result;
    }

    public void scriptFlush() {
        scriptCache.clear();
    }
}
