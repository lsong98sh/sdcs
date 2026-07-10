package com.qkinfotech.bizwax.sdcs.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Function;

/**
 * Simple pooled object reuse for {@link SDCSNode}.
 * Uses a single lock for both acquire and release.
 */
public class ObjectPool<T> {

    private final Deque<T> pool;
    private final int initialSize;
    private final int capacity;
    private final Function<ObjectPool<T>, T> factory;
    private int poolSize;

    public ObjectPool(int initialSize, int capacity, Function<ObjectPool<T>, T> factory) {
        this.initialSize = initialSize;
        this.capacity = capacity;
        this.factory = factory;
        this.pool = new ArrayDeque<>(initialSize);
        this.poolSize = 0;

        for (int i = 0; i < initialSize; i++) {
            T obj = factory.apply(this);
            pool.add(obj);
            poolSize++;
        }
    }

    public synchronized T acquire() {
        if (pool.isEmpty()) {
            if (poolSize >= capacity) {
                return null;
            }
            T obj = factory.apply(this);
            poolSize++;
            return obj;
        }
        return pool.removeFirst();
    }

    public synchronized void release(T obj) {
        pool.add(obj);
    }

    public synchronized int getPoolSize() {
        return poolSize;
    }

    public synchronized int getAvailableCount() {
        return pool.size();
    }
}
