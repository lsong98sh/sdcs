package com.qkinfotech.bizwax.sdcs.proxy.registry;

import com.qkinfotech.bizwax.sdcs.proxy.backend.BackendConnectionPool;
import com.qkinfotech.bizwax.sdcs.proxy.backend.BackendRequestManager;
import com.qkinfotech.bizwax.sdcs.proxy.config.ProxyConfig;
import com.qkinfotech.bizwax.sdcs.proxy.server.ClientWriter;
import com.qkinfotech.bizwax.sdcs.proxy.server.CommandType;
import com.qkinfotech.bizwax.sdcs.proxy.server.RespCodec;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class RegistryManager {

    private static final Logger log = LoggerFactory.getLogger(RegistryManager.class);

    private final ProxyConfig config;
    private final BackendConnectionPool connectionPool;
    private final String jdbcUrl;
    private final String jdbcUsername;
    private final String jdbcPassword;

    // Route table: slot → [addr, ...]
    private volatile Map<Integer, List<String>> routeTable = Collections.emptyMap();
    private volatile List<String> allAddrs = Collections.emptyList();
    private volatile boolean hasAllSlotNode = false;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Write command table
    private static final Map<String, Boolean> WRITE_COMMANDS = new ConcurrentHashMap<>();

    static {
        String[] writes = {
                "SET", "SETNX", "SETEX", "PSETEX", "SETRANGE", "MSET", "MSETNX", "APPEND",
                "DEL", "EXPIRE", "EXPIREAT", "PEXPIRE", "PEXPIREAT", "PERSIST", "RENAME",
                "RENAMENX", "MOVE", "COPY",
                "GETDEL", "GETSET",
                "INCR", "DECR", "INCRBY", "DECRBY", "INCRBYFLOAT",
                "HDEL", "HSET", "HMSET", "HSETNX", "HINCRBY", "HINCRBYFLOAT",
                "LINSERT", "LPUSH", "LPUSHX", "LREM", "LSET", "LTRIM", "RPOP",
                "RPUSH", "RPUSHX", "BLPOP", "BRPOP", "BLMOVE", "BRPOPLPUSH",
                "BZPOPMIN", "BZPOPMAX",
                "SADD", "SDIFFSTORE", "SINTERSTORE", "SMOVE", "SPOP", "SREM",
                "SUNIONSTORE", "SINTER", "SDIFF", "SUNION",
                "ZADD", "ZINCRBY", "ZINTERSTORE", "ZUNIONSTORE", "ZDIFFSTORE",
                "ZPOPMAX", "ZPOPMIN", "ZRANGESTORE", "ZREM",
                "ZREMRANGEBYLEX", "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE",
                "XADD", "XDEL", "XTRIM", "XACK", "XGROUP", "XREADGROUP", "XCLAIM",
                "PFADD", "PFMERGE",
                "GEOADD",
                "SETBIT", "BITOP",
                "FLUSHDB", "FLUSHALL", "SAVE", "BGSAVE", "BGREWRITEAOF",
                "SHUTDOWN", "SLAVEOF", "REPLICAOF", "CLIENT", "DEBUG",
                "RESTORE", "DUMP"
        };
        for (String cmd : writes) {
            WRITE_COMMANDS.put(cmd, true);
        }
    }

    public RegistryManager(ProxyConfig config) {
        this.config = config;
        int minIdle = (config != null) ? config.getBackendMinIdle() : 2;
        int maxTotal = (config != null) ? config.getBackendMaxTotal() : 16;
        this.connectionPool = new BackendConnectionPool(minIdle, maxTotal);
        this.jdbcUrl = (config != null) ? config.getRegistryJdbcUrl() : "";
        this.jdbcUsername = (config != null) ? config.getRegistryUsername() : "";
        this.jdbcPassword = (config != null) ? config.getRegistryPassword() : "";
    }

    // Test injection
    public void injectTestRouteTable(Map<Integer, List<String>> table) {
        this.routeTable = table;
        this.allAddrs = table.values().stream()
                .flatMap(List::stream)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
        this.hasAllSlotNode = table.containsKey(0) && table.containsKey(1023);
    }

    public void start() {
        if (!jdbcUrl.isBlank()) {
            initJdbc();
            ensureTable();
            writeProxyHeartbeat();
            refreshRouteTable();
        } else {
            log.warn("No registry JDBC URL configured; route table will be empty");
        }

        connectionPool.start();

        // Start refresh thread
        running.set(true);
        Thread refreshThread = Thread.ofVirtual().start(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(config.getRefreshIntervalSeconds() * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    refreshRouteTable();
                    writeProxyHeartbeat();
                } catch (Exception e) {
                    log.error("Failed to refresh route table", e);
                }
            }
        });
        refreshThread.setName("route-table-refresher");
    }

    private void initJdbc() {
        try {
            if (jdbcUrl.startsWith("jdbc:sqlite:")) {
                Class.forName("org.sqlite.JDBC");
            } else if (jdbcUrl.startsWith("jdbc:mysql:")) {
                Class.forName("com.mysql.cj.jdbc.Driver");
            }
            log.info("Registry JDBC initialized: {}", jdbcUrl);
        } catch (ClassNotFoundException e) {
            log.warn("JDBC driver not found in classpath, will rely on service loader", e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, jdbcUsername, jdbcPassword);
    }

    private void ensureTable() {
        try (Connection conn = getConnection()) {
            String sql = "CREATE TABLE IF NOT EXISTS sdcs_routes (\n" +
                    "    addr VARCHAR(64) NOT NULL,\n" +
                    "    hash_start INT NOT NULL,\n" +
                    "    hash_end INT NOT NULL,\n" +
                    "    status TINYINT DEFAULT 1,\n" +
                    "    last_heartbeat DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                    "    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                    "    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                    "    PRIMARY KEY (addr)\n" +
                    ")";
            conn.createStatement().execute(sql);
            log.info("Ensured sdcs_routes table exists");

            String proxySql = "CREATE TABLE IF NOT EXISTS sdcs_proxies (\n" +
                    "    addr VARCHAR(64) NOT NULL,\n" +
                    "    version VARCHAR(32) DEFAULT '',\n" +
                    "    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                    "    last_heartbeat DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                    "    PRIMARY KEY (addr)\n" +
                    ")";
            conn.createStatement().execute(proxySql);
            log.info("Ensured sdcs_proxies table exists");
        } catch (Exception e) {
            log.error("Failed to ensure tables", e);
        }
    }

    private void writeProxyHeartbeat() {
        String addr = config != null ? config.getProxyAddr() : "";
        if (addr.isBlank()) return;
        try (Connection conn = getConnection()) {
            // Upsert: try INSERT first, then UPDATE on duplicate
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sdcs_proxies (addr, version, last_heartbeat) " +
                    "VALUES (?, ?, CURRENT_TIMESTAMP)")) {
                ps.setString(1, addr);
                ps.setString(2, "1.0.0");
                ps.executeUpdate();
            }
        } catch (Exception e) {
            // Duplicate key - do UPDATE instead
            if (e.getMessage() != null && (e.getMessage().contains("UNIQUE") ||
                    e.getMessage().contains("PRIMARY") || e.getMessage().contains("duplicate"))) {
                try (Connection conn = getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                             "UPDATE sdcs_proxies SET last_heartbeat=CURRENT_TIMESTAMP, version=? WHERE addr=?")) {
                    ps.setString(1, "1.0.0");
                    ps.setString(2, addr);
                    ps.executeUpdate();
                } catch (Exception e2) {
                    log.error("Failed to update proxy heartbeat for {}", addr, e2);
                }
            } else {
                log.error("Failed to insert proxy heartbeat for {}", addr, e);
            }
        }
    }

    private void refreshRouteTable() {
        try (Connection conn = getConnection()) {
            String sql = "SELECT addr, hash_start, hash_end, last_heartbeat " +
                    "FROM sdcs_routes WHERE status=1";
            int timeoutSecs = config.getHeartbeatTimeoutSeconds();

            Map<Integer, List<String>> newTable = new ConcurrentHashMap<>();
            List<String> newAddrs = new ArrayList<>();
            boolean foundAllSlot = false;

            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String addr = rs.getString("addr");
                    int hashStart = rs.getInt("hash_start");
                    int hashEnd = rs.getInt("hash_end");
                    Timestamp heartbeat = rs.getTimestamp("last_heartbeat");

                    // Check heartbeat freshness
                    if (heartbeat != null) {
                        long elapsed = System.currentTimeMillis() - heartbeat.getTime();
                        if (elapsed > timeoutSecs * 1000L) {
                            log.debug("Skipping stale node {} (last heartbeat {}s ago)", addr, elapsed / 1000);
                            continue;
                        }
                    }

                    if (hashStart == 0 && hashEnd == 1023) {
                        foundAllSlot = true;
                    }

                    for (int slot = hashStart; slot <= hashEnd; slot++) {
                        newTable.computeIfAbsent(slot, k -> new CopyOnWriteArrayList<>())
                                .add(addr);
                    }

                    if (!newAddrs.contains(addr)) {
                        newAddrs.add(addr);
                    }
                }
            }

            boolean changed = !newAddrs.equals(allAddrs) || foundAllSlot != hasAllSlotNode;
            if (changed) {
                log.info("Route table refreshed: {} nodes, {} slots covered, all-slot={}",
                        newAddrs.size(), newTable.size(), foundAllSlot);
            }

            this.routeTable = newTable;
            this.allAddrs = newAddrs;
            this.hasAllSlotNode = foundAllSlot;

        } catch (Exception e) {
            log.error("Failed to refresh route table from database", e);
        }
    }

    public List<String> lookup(int slot) {
        List<String> addrs = routeTable.get(slot);
        return addrs != null ? addrs : Collections.emptyList();
    }

    public List<String> getAnyAddrs() {
        return allAddrs;
    }

    public boolean hasAllSlotNode() {
        return hasAllSlotNode;
    }

    public List<String> getAllSlotAddrs() {
        List<String> result = new ArrayList<>();
        for (int slot = 0; slot <= 1023; slot++) {
            List<String> addrs = routeTable.get(slot);
            if (addrs != null) {
                for (String addr : addrs) {
                    if (!result.contains(addr)) {
                        result.add(addr);
                    }
                }
            }
        }
        return result;
    }

    public void forwardToBackend(ClientWriter writer, String command,
                                  List<String> args, List<String> targets, CommandType type) {
        if (targets.isEmpty()) {
            writer.write(RespCodec.error("ERR no available backend"));
            return;
        }

        if (type == CommandType.READ || targets.size() == 1) {
            String target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
            BackendRequestManager.forwardRead(writer, command, args, target, connectionPool);
        } else {
            BackendRequestManager.forwardWrite(writer, command, args, targets, connectionPool);
        }
    }

    public boolean isWriteCommand(String command) {
        return WRITE_COMMANDS.containsKey(command);
    }

    public BackendConnectionPool getConnectionPool() {
        return connectionPool;
    }

    public void stop() {
        running.set(false);
        connectionPool.stop();
        log.info("Registry manager stopped.");
    }

    /**
     * CRC16 implementation compatible with Redis cluster hash slot calculation.
     */
    public static int crc16(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        return crc16(bytes) & 0xFFFF;
    }

    public static int crc16(byte[] bytes) {
        int crc = 0;
        for (byte b : bytes) {
            crc = (crc << 8) ^ CRC16_TABLE[((crc >>> 8) ^ (b & 0xFF)) & 0xFF];
        }
        return crc;
    }

    private static final int[] CRC16_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc = crc >>> 1;
                }
            }
            CRC16_TABLE[i] = crc;
        }
    }
}
