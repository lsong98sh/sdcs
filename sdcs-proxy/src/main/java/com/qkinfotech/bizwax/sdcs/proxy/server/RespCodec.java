package com.qkinfotech.bizwax.sdcs.proxy.server;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RespCodec {

    private static final byte[] EMPTY_BYTES = new byte[0];
    public static final RedisMessage OK = RedisMessage.simpleString("OK");
    public static final RedisMessage ERR_UNKNOWN =
            RedisMessage.error("ERR unknown command");
    public static final RedisMessage ERR_NO_HASH =
            RedisMessage.error("ERR Command not supported in sharded mode (no all-slot node)");

    /**
     * Decode a server RedisMessage (from client) into a list of string arguments.
     * Returns null if the message cannot be decoded as a command.
     */
    public static List<String> decode(RedisMessage msg) {
        if (msg.getType() == RedisMessage.Type.ARRAY) {
            List<RedisMessage> children = msg.getElements();
            if (children == null || children.isEmpty()) return null;
            List<String> args = new ArrayList<>(children.size());
            for (RedisMessage child : children) {
                args.add(decodeBulkString(child));
            }
            return args;
        }
        // Inline commands are decoded by RespDecoder as ARRAY already, but handle
        // simple case where we get a single bulk string (inline without array wrapper)
        if (msg.getType() == RedisMessage.Type.BULK_STRING) {
            String s = msg.asString();
            if (s == null) return null;
            // Split by spaces simulating inline command parsing
            List<String> parts = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean inQuote = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') {
                    inQuote = !inQuote;
                } else if (c == ' ' && !inQuote) {
                    if (!sb.isEmpty()) {
                        parts.add(sb.toString());
                        sb.setLength(0);
                    }
                } else {
                    sb.append(c);
                }
            }
            if (!sb.isEmpty()) parts.add(sb.toString());
            return parts.isEmpty() ? null : parts;
        }
        return null;
    }

    /**
     * Decode a BULK_STRING message to a String.
     */
    public static String decodeBulkString(RedisMessage msg) {
        if (msg.getType() == RedisMessage.Type.BULK_STRING) {
            byte[] data = msg.getData();
            if (data == null) return "";
            return new String(data, StandardCharsets.UTF_8);
        }
        return "";
    }

    /**
     * Build a RESP ARRAY command message for forwarding to backend.
     */
    public static RedisMessage encodeCommand(String command, List<String> args) {
        List<RedisMessage> parts = new ArrayList<>(1 + (args != null ? args.size() : 0));
        parts.add(RedisMessage.bulkString(command));
        if (args != null) {
            for (String arg : args) {
                parts.add(RedisMessage.bulkString(arg));
            }
        }
        return RedisMessage.array(parts);
    }

    public static RedisMessage simpleString(String content) {
        return RedisMessage.simpleString(content);
    }

    public static RedisMessage error(String message) {
        return RedisMessage.error(message);
    }

    public static RedisMessage integer(long value) {
        return RedisMessage.integer(value);
    }
}
