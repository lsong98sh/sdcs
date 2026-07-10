package com.qkinfotech.bizwax.sdcs.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SDCSConfigTest {

    private SDCSConfig config;

    @BeforeEach
    void setUp() {
        config = new SDCSConfig();
    }

    @Test
    void defaultValues() {
        assertEquals(6379, config.getPort());
        assertEquals("0.0.0.0", config.getBindAddress());
        assertEquals(SDCSConfig.PersistenceType.RDB_AOF, config.getPersistenceType());
        assertEquals("data", config.getDataDir());
        assertNull(config.getRequirepass());
        assertFalse(config.isHelpRequested());
    }

    @Test
    void parseArgsSetsPort() {
        config.parseArgs(new String[]{"--port", "12345"});
        assertEquals(12345, config.getPort());
    }

    @Test
    void parseArgsSetsBind() {
        config.parseArgs(new String[]{"--bind", "127.0.0.1"});
        assertEquals("127.0.0.1", config.getBindAddress());
    }

    @Test
    void parseArgsSetsPersistence() {
        config.parseArgs(new String[]{"--persistence", "rocksdb"});
        assertEquals(SDCSConfig.PersistenceType.ROCKSDB, config.getPersistenceType());
    }

    @Test
    void parseArgsSetsRequirepass() {
        config.parseArgs(new String[]{"--requirepass", "secret123"});
        assertEquals("secret123", config.getRequirepass());
    }

    @Test
    void parseArgsSetsDataDir() {
        config.parseArgs(new String[]{"--data-dir", "/var/sdcs/data"});
        assertEquals("/var/sdcs/data", config.getDataDir());
    }

    @Test
    void parseArgsMultipleOptions() {
        config.parseArgs(new String[]{
                "--port", "6380",
                "--bind", "192.168.1.1",
                "--persistence", "none",
                "--requirepass", "pass",
                "--data-dir", "/sdcs/data"
        });
        assertEquals(6380, config.getPort());
        assertEquals("192.168.1.1", config.getBindAddress());
        assertEquals(SDCSConfig.PersistenceType.NONE, config.getPersistenceType());
        assertEquals("pass", config.getRequirepass());
        assertEquals("/sdcs/data", config.getDataDir());
    }

    @Test
    void parseArgsMissingValueAfterPortKeepsDefault() {
        config.parseArgs(new String[]{"--port"});
        assertEquals(6379, config.getPort(), "port should remain default when value is missing");
    }

    @Test
    void parseArgsInvalidPortThrowsNumberFormatException() {
        assertThrows(NumberFormatException.class,
                () -> config.parseArgs(new String[]{"--port", "not-a-number"}));
    }

    @Test
    void parseArgsHelpSetsHelpRequested() {
        config.parseArgs(new String[]{"--help"});
        assertTrue(config.isHelpRequested());
    }

    @Test
    void getConfigReturnsCorrectValue() {
        config.setPort(9999);
        assertEquals("9999", config.getConfig("port"));
        assertEquals("0.0.0.0", config.getConfig("bind"));
    }

    @Test
    void getConfigReturnsNullForUnknownKey() {
        assertNull(config.getConfig("nonexistent-key"));
    }

    @Test
    void setConfigRequirepass() {
        assertTrue(config.setConfig("requirepass", "newpass"));
        assertEquals("newpass", config.getRequirepass());
    }

    @Test
    void setConfigPersistence() {
        assertTrue(config.setConfig("persistence", "NONE"));
        assertEquals(SDCSConfig.PersistenceType.NONE, config.getPersistenceType());
    }

    @Test
    void setConfigDataDir() {
        assertTrue(config.setConfig("data-dir", "/new/data"));
        assertEquals("/new/data", config.getDataDir());
    }

    @Test
    void setConfigHotCacheSize() {
        assertTrue(config.setConfig("hot-cache-size", "50000"));
        assertEquals(50000, config.getHotCacheSize());
    }

    @Test
    void setConfigUnknownKeyReturnsFalse() {
        assertFalse(config.setConfig("unknown-key", "value"));
    }

    @Test
    void getAllConfigsContainsAllKeys() {
        Map<String, String> all = config.getAllConfigs();
        assertNotNull(all);
        assertEquals("6379", all.get("port"));
        assertEquals("0.0.0.0", all.get("bind"));
        assertEquals("rdb_aof", all.get("persistence"));
        assertEquals("data", all.get("data-dir"));
        assertTrue(all.containsKey("requirepass"));
        assertTrue(all.containsKey("maxclients"));
    }

    @Test
    void persistenceSetToNone() {
        config.setPersistenceType(SDCSConfig.PersistenceType.NONE);
        assertEquals(SDCSConfig.PersistenceType.NONE, config.getPersistenceType());
    }

    @Test
    void persistenceSetToRdbAof() {
        config.setPersistenceType(SDCSConfig.PersistenceType.RDB_AOF);
        assertEquals(SDCSConfig.PersistenceType.RDB_AOF, config.getPersistenceType());
    }

    @Test
    void persistenceSetToRocksdb() {
        config.setPersistenceType(SDCSConfig.PersistenceType.ROCKSDB);
        assertEquals(SDCSConfig.PersistenceType.ROCKSDB, config.getPersistenceType());
    }

    @Test
    void isHelpRequestedInitiallyFalse() {
        assertFalse(config.isHelpRequested());
    }

    @Test
    void getConfigRequirepassMasksValue() {
        config.setRequirepass("mypassword");
        assertEquals("****", config.getConfig("requirepass"));
    }

    @Test
    void getConfigAofEnabled() {
        assertFalse(config.isAofEnabled());
        config.setAofEnabled(true);
        assertEquals("yes", config.getConfig("aof"));
    }
}
