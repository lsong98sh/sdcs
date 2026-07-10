package com.qkinfotech.bizwax.sdcs.store;

import java.util.Map;

public class DatabaseManager {

    private static final int DB_COUNT = 16;
    private final MemoryStore[] stores;
    private int currentDb;

    public DatabaseManager() {
        stores = new MemoryStore[DB_COUNT];
        for (int i = 0; i < DB_COUNT; i++) {
            stores[i] = new MemoryStore();
        }
    }

    public MemoryStore getStore() {
        return stores[currentDb];
    }

    public MemoryStore getStore(int db) {
        if (db < 0 || db >= DB_COUNT) return null;
        return stores[db];
    }

    public int getCurrentDb() {
        return currentDb;
    }

    public void select(int db) {
        if (db < 0 || db >= DB_COUNT) {
            throw new IllegalArgumentException("ERR DB index is out of range");
        }
        this.currentDb = db;
    }

    public void flushAll() {
        for (MemoryStore store : stores) {
            store.clear();
        }
    }

    public long size() {
        long total = 0;
        for (MemoryStore store : stores) {
            total += store.size();
        }
        return total;
    }

    public Map<String, ?> getAllStores() {
        Map<String, Map<String, ?>> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < DB_COUNT; i++) {
            if (stores[i].size() > 0) {
                result.put(String.valueOf(i), stores[i].getStore());
            }
        }
        return result;
    }
}
