package com.qkinfotech.bizwax.sdcs.common;

import java.util.*;

public class HyperLogLog {
    private static final int REGISTER_COUNT = 1024;
    private static final int REGISTER_BITS = 6;
    private final byte[] registers;

    public HyperLogLog() {
        registers = new byte[REGISTER_COUNT];
    }

    public HyperLogLog(byte[] data) {
        registers = new byte[REGISTER_COUNT];
        if (data != null && data.length >= REGISTER_COUNT) {
            System.arraycopy(data, 0, registers, 0, REGISTER_COUNT);
        }
    }

    public void add(byte[] value) {
        int hash = Arrays.hashCode(value);
        int idx = hash & (REGISTER_COUNT - 1);
        int leadingZeros = Integer.numberOfLeadingZeros(hash | (REGISTER_COUNT - 1)) + 1;
        if (leadingZeros > (registers[idx] & 0x3F)) {
            registers[idx] = (byte) Math.min(leadingZeros, 63);
        }
    }

    public long count() {
        double sum = 0;
        int zeroCount = 0;
        for (int i = 0; i < REGISTER_COUNT; i++) {
            int val = registers[i] & 0x3F;
            sum += 1.0 / (1 << val);
            if (val == 0) zeroCount++;
        }
        double estimate = 0.79402 * REGISTER_COUNT * REGISTER_COUNT / sum;
        if (estimate < 2.5 * REGISTER_COUNT) {
            if (zeroCount > 0) {
                estimate = REGISTER_COUNT * Math.log((double) REGISTER_COUNT / zeroCount);
            }
        }
        return Math.round(estimate);
    }

    public void merge(HyperLogLog other) {
        for (int i = 0; i < REGISTER_COUNT; i++) {
            if ((other.registers[i] & 0x3F) > (registers[i] & 0x3F)) {
                registers[i] = (byte) (other.registers[i] & 0x3F);
            }
        }
    }

    public byte[] toBytes() {
        byte[] result = new byte[REGISTER_COUNT];
        System.arraycopy(registers, 0, result, 0, REGISTER_COUNT);
        return result;
    }
}
