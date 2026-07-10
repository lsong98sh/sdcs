package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.common.RedisData;

public interface StoreListener {
    void onPut(String key, RedisData value);
    void onRemove(String key);
}
