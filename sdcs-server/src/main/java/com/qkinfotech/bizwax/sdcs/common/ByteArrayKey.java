package com.qkinfotech.bizwax.sdcs.common;

import java.util.Arrays;

public final class ByteArrayKey {

    private final byte[] data;
    private final int hashCode;

    public ByteArrayKey(byte[] data) {
        this.data = data;
        this.hashCode = Arrays.hashCode(data);
    }

    public byte[] getData() {
        return data;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ByteArrayKey that = (ByteArrayKey) o;
        return Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
