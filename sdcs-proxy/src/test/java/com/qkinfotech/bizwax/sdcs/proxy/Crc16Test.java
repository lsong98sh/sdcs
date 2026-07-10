package com.qkinfotech.bizwax.sdcs.proxy;

import com.qkinfotech.bizwax.sdcs.proxy.registry.RegistryManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Crc16Test {

    @Test
    void testCrc16Deterministic() {
        assertEquals(
                RegistryManager.crc16("test-key"),
                RegistryManager.crc16("test-key"));
        assertEquals(
                RegistryManager.crc16("123456789"),
                RegistryManager.crc16("123456789"));
    }

    @Test
    void testCrc16Empty() {
        assertEquals(0, RegistryManager.crc16(""));
    }

    @Test
    void testCrc16SingleChar() {
        int hash = RegistryManager.crc16("a");
        assertTrue(hash >= 0);
        assertTrue(hash <= 65535);
    }

    @Test
    void testCrc16SlotModulo() {
        // Keys should distribute across 1024 slots
        int slot = RegistryManager.crc16("mykey") % 1024;
        assertTrue(slot >= 0);
        assertTrue(slot < 1024);
    }

    @Test
    void testCrc16AllAscii() {
        StringBuilder sb = new StringBuilder();
        for (int i = 32; i < 127; i++) {
            sb.append((char) i);
        }
        String allAscii = sb.toString();
        int hash = RegistryManager.crc16(allAscii);
        assertTrue(hash >= 0);
        assertTrue(hash <= 65535);
        int slot = hash % 1024;
        assertTrue(slot >= 0);
        assertTrue(slot < 1024);
    }
}
