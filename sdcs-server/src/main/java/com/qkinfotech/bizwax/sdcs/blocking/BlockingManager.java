package com.qkinfotech.bizwax.sdcs.blocking;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.server.SDCSCommandExecutor;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

public class BlockingManager {

    private static final Logger log = LoggerFactory.getLogger(BlockingManager.class);
    private static final BlockingManager INSTANCE = new BlockingManager();
    private static final int MAX_WAITERS_PER_KEY = 1000;

    private final Map<String, List<BlockingContext>> waiting = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "SDCS-BlockingTimeout"); t.setDaemon(true); return t; }
    );

    public static BlockingManager getInstance() {
        return INSTANCE;
    }

    public void block(String key, BlockingContext ctx) {
        synchronized (this) {
            List<BlockingContext> contexts = waiting.computeIfAbsent(key, k -> new ArrayList<>());
            if (contexts.size() >= MAX_WAITERS_PER_KEY) {
                ctx.callback.accept(RedisMessage.error("ERR too many blocking clients for key '" + key + "'"));
                return;
            }
            contexts.add(ctx);
        }
        if (ctx.timeoutMs > 0) {
            timeoutExecutor.schedule(() -> {
                try {
                    synchronized (this) {
                        List<BlockingContext> contexts = waiting.get(key);
                        if (contexts != null) {
                            contexts.remove(ctx);
                            if (contexts.isEmpty()) waiting.remove(key);
                        }
                    }
                    ctx.callback.accept(RedisMessage.array((java.util.List<RedisMessage>) null));
                } catch (Exception e) {
                    log.warn("Error in blocking timeout callback for key '{}': {}", key, e.getMessage(), e);
                }
            }, ctx.timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    public void notifyKey(String key) {
        synchronized (this) {
            List<BlockingContext> contexts = waiting.remove(key);
            if (contexts == null || contexts.isEmpty()) return;

            for (BlockingContext ctx : contexts) {
                try {
                    MemoryStore store = SDCSCommandExecutor.getStore();
                    RedisMessage result = ctx.handler.handle(store, key);
                    if (result != null) {
                        ctx.callback.accept(result);
                    }
                } catch (Exception e) {
                    log.warn("Error notifying blocking context for key '{}': {}", key, e.getMessage(), e);
                }
            }
        }
    }

    public record BlockingContext(
            long timeoutMs,
            BlockingHandler handler,
            java.util.function.Consumer<RedisMessage> callback
    ) {}
}
