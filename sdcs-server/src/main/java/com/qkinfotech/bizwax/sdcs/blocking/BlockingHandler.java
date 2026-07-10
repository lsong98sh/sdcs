package com.qkinfotech.bizwax.sdcs.blocking;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;

@FunctionalInterface
public interface BlockingHandler {
    RedisMessage handle(MemoryStore store, String key);
}
