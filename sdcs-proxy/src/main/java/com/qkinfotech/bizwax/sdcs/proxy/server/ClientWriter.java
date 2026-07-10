package com.qkinfotech.bizwax.sdcs.proxy.server;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;

@FunctionalInterface
public interface ClientWriter {
    void write(RedisMessage msg);
}
