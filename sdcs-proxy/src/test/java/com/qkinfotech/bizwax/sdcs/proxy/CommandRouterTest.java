package com.qkinfotech.bizwax.sdcs.proxy;

import com.qkinfotech.bizwax.sdcs.proxy.registry.RegistryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandRouterTest {

    private RegistryManager registryManager;
    private static final int SLOTS = 1024;

    @BeforeEach
    void setUp() {
        registryManager = new RegistryManager(null);
        registryManager.injectTestRouteTable(generateRouteTable());
    }

    @Test
    void testRouteKnownCommand() {
        String key = "mykey";
        int slot = RegistryManager.crc16(key) % SLOTS;
        List<String> targets = registryManager.lookup(slot);
        assertNotNull(targets);
        assertFalse(targets.isEmpty());
        assertEquals("192.168.1.1:6379", targets.get(0));
    }

    @Test
    void testSlotCoverage() {
        // All 1024 slots should have at least one backend
        for (int slot = 0; slot < SLOTS; slot++) {
            List<String> targets = registryManager.lookup(slot);
            assertNotNull(targets, "Slot " + slot + " should be covered");
            assertFalse(targets.isEmpty(), "Slot " + slot + " should have targets");
        }
    }

    @Test
    void testCrc16SlotConsistency() {
        String key = "test:{hash_tag}:key";
        int slot1 = RegistryManager.crc16(key) % SLOTS;
        int slot2 = RegistryManager.crc16(key) % SLOTS;
        assertEquals(slot1, slot2);
    }

    @Test
    void testWriteCommand() {
        assertTrue(registryManager.isWriteCommand("SET"));
        assertTrue(registryManager.isWriteCommand("DEL"));
        assertTrue(registryManager.isWriteCommand("EXPIRE"));
    }

    @Test
    void testReadCommand() {
        assertFalse(registryManager.isWriteCommand("GET"));
        assertFalse(registryManager.isWriteCommand("EXISTS"));
        assertFalse(registryManager.isWriteCommand("PING"));
    }

    private static Map<Integer, List<String>> generateRouteTable() {
        Map<Integer, List<String>> table = new java.util.concurrent.ConcurrentHashMap<>();
        // One backend covering all slots
        for (int slot = 0; slot < SLOTS; slot++) {
            table.put(slot, List.of("192.168.1.1:6379"));
        }
        return table;
    }
}
