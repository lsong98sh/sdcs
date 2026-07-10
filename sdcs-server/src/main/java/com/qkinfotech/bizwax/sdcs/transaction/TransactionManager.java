package com.qkinfotech.bizwax.sdcs.transaction;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TransactionManager {

    private static final Map<Long, TransactionState> transactions = new ConcurrentHashMap<>();

    private TransactionManager() {}

    public static boolean isInTransaction() {
        return transactions.containsKey(Thread.currentThread().threadId());
    }

    public static void multi() {
        long tid = Thread.currentThread().threadId();
        TransactionState state = transactions.computeIfAbsent(tid, k -> new TransactionState());
        if (state.hasQueuedCommands()) {
            throw new IllegalStateException("ERR MULTI calls can not be nested");
        }
    }

    public static boolean queueCommand(RedisMessage command) {
        long tid = Thread.currentThread().threadId();
        TransactionState state = transactions.get(tid);
        if (state == null) return false;
        state.queueCommand(command);
        return true;
    }

    public static List<RedisMessage> exec() {
        long tid = Thread.currentThread().threadId();
        TransactionState state = transactions.remove(tid);
        if (state == null) return null;
        return state.getQueuedCommands();
    }

    public static void discard() {
        long tid = Thread.currentThread().threadId();
        transactions.remove(tid);
    }

    public static void cleanup() {
        long tid = Thread.currentThread().threadId();
        TransactionState state = transactions.remove(tid);
        if (state != null) {
            state.clear();
        }
    }

    public static boolean watch(String... keys) {
        long tid = Thread.currentThread().threadId();
        TransactionState state = transactions.computeIfAbsent(tid, k -> new TransactionState());
        state.watch(keys);
        return true;
    }

    public static boolean isWatchedKeyDirty(String key) {
        long tid = Thread.currentThread().threadId();
        TransactionState state = transactions.get(tid);
        return state != null && state.isWatchedKeyDirty(key);
    }

    public static void markDirty(String key) {
        for (TransactionState state : transactions.values()) {
            state.markKeyDirty(key);
        }
    }

    private static class TransactionState {
        private final List<RedisMessage> queuedCommands = new ArrayList<>();
        private final Set<String> watchedKeys = new HashSet<>();
        private boolean dirty = false;

        void clear() {
            queuedCommands.clear();
            watchedKeys.clear();
            dirty = false;
        }

        boolean hasQueuedCommands() {
            return !queuedCommands.isEmpty();
        }

        void queueCommand(RedisMessage command) {
            if (!dirty) {
                queuedCommands.add(command);
            }
        }

        List<RedisMessage> getQueuedCommands() {
            return dirty ? List.of() : new ArrayList<>(queuedCommands);
        }

        void watch(String... keys) {
            watchedKeys.addAll(Arrays.asList(keys));
        }

        boolean isWatchedKeyDirty(String key) {
            return watchedKeys.contains(key) && dirty;
        }

        void markKeyDirty(String key) {
            if (watchedKeys.contains(key)) {
                dirty = true;
            }
        }
    }
}
