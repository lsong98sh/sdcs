package com.qkinfotech.bizwax.sdcs.pubsub;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class PubSubManager {

    private static final PubSubManager INSTANCE = new PubSubManager();
    private static final int MAX_CHANNELS = 100000;
    private static final int MAX_SUBSCRIBERS_PER_CHANNEL = 10000;
    private static final int MAX_PATTERN_SUBS = 10000;

    private static class PatternSub {
        final String pattern;
        final Pattern regex;
        final Consumer<RedisMessage> callback;

        PatternSub(String pattern, Consumer<RedisMessage> callback) {
            this.pattern = pattern;
            this.regex = Pattern.compile(globToRegex(pattern));
            this.callback = callback;
        }
    }

    private final Map<String, List<Consumer<RedisMessage>>> channels = new ConcurrentHashMap<>();
    private final List<PatternSub> patternSubs = new CopyOnWriteArrayList<>();

    private PubSubManager() {}

    public static PubSubManager getInstance() {
        return INSTANCE;
    }

    public long publish(String channel, RedisMessage message) {
        long count = 0;

        List<Consumer<RedisMessage>> subscribers = channels.get(channel);
        if (subscribers != null) {
            for (Consumer<RedisMessage> subscriber : subscribers) {
                subscriber.accept(message);
                count++;
            }
        }

        for (PatternSub ps : patternSubs) {
            if (ps.regex.matcher(channel).matches()) {
                RedisMessage pmessage = RedisMessage.array(
                        RedisMessage.bulkString("pmessage".getBytes()),
                        RedisMessage.bulkString(ps.pattern.getBytes()),
                        RedisMessage.bulkString(channel.getBytes()),
                        message
                );
                ps.callback.accept(pmessage);
                count++;
            }
        }

        return count;
    }

    public void subscribe(String channel, Consumer<RedisMessage> callback) {
        if (channels.size() >= MAX_CHANNELS && !channels.containsKey(channel)) {
            return;
        }
        List<Consumer<RedisMessage>> subscribers = channels.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>());
        if (subscribers.size() >= MAX_SUBSCRIBERS_PER_CHANNEL) {
            return;
        }
        subscribers.add(callback);
    }

    public void unsubscribe(String channel, Consumer<RedisMessage> callback) {
        List<Consumer<RedisMessage>> subscribers = channels.get(channel);
        if (subscribers != null) {
            subscribers.remove(callback);
            if (subscribers.isEmpty()) {
                channels.remove(channel);
            }
        }
    }

    public void psubscribe(String pattern, Consumer<RedisMessage> callback) {
        if (patternSubs.size() >= MAX_PATTERN_SUBS) {
            return;
        }
        patternSubs.add(new PatternSub(pattern, callback));
    }

    public void punsubscribe(String pattern, Consumer<RedisMessage> callback) {
        patternSubs.removeIf(ps -> ps.pattern.equals(pattern) && ps.callback.equals(callback));
    }

    public Set<String> getChannels() {
        return channels.keySet();
    }

    public long numSub(String channel) {
        List<Consumer<RedisMessage>> subscribers = channels.get(channel);
        return subscribers == null ? 0 : subscribers.size();
    }

    public long numPat() {
        return patternSubs.size();
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append(".");
                case '.' -> sb.append("\\.");
                case '\\' -> sb.append("\\\\");
                default -> sb.append(c);
            }
        }
        sb.append('$');
        return sb.toString();
    }
}
