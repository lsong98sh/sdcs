package com.qkinfotech.bizwax.sdcs.registry;

import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

public class RegistryClient {

    private static final Logger log = LoggerFactory.getLogger(RegistryClient.class);

    private final SDCSConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private DataSource dataSource;
    private Thread heartbeatThread;
    private String[] hashParts;

    public RegistryClient(SDCSConfig config) {
        this.config = config;
    }

    public void start() {
        String jdbcUrl = config.getRegistryJdbcUrl();
        String hash = config.getRegisterHash();
        String addr = config.getRegisterAddr();

        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            log.info("Registry JDBC URL not configured; skipping registration");
            return;
        }
        if (hash == null || hash.isBlank()) {
            log.warn("register-hash not configured; skipping registration");
            return;
        }
        if (addr == null || addr.isBlank()) {
            log.warn("register-addr not configured; skipping registration");
            return;
        }

        // Parse hash range
        String[] parts = hash.split("-");
        if (parts.length == 1) {
            int s = Integer.parseInt(parts[0].trim());
            hashParts = new String[]{String.valueOf(s), String.valueOf(s)};
        } else {
            hashParts = new String[]{parts[0].trim(), parts[1].trim()};
        }

        // Init data source
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        String user = config.getRegistryUsername();
        String pwd = config.getRegistryPassword();
        if (user != null) hikariConfig.setUsername(user);
        if (pwd != null) hikariConfig.setPassword(pwd);
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(5000);
        hikariConfig.setIdleTimeout(300_000);
        hikariConfig.setMaxLifetime(600_000);

        this.dataSource = new HikariDataSource(hikariConfig);

        // Ensure table exists
        ensureTable();

        // Register
        register();

        // Start heartbeat
        running.set(true);
        heartbeatThread = Thread.ofVirtual().start(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    heartbeat();
                } catch (Exception e) {
                    log.warn("Registry heartbeat failed: {}", e.getMessage());
                }
            }
        });
        heartbeatThread.setName("registry-heartbeat");

        log.info("Registry client started: addr={}, hash={}-{}", addr, hashParts[0], hashParts[1]);
    }

    private void ensureTable() {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "CREATE TABLE IF NOT EXISTS sdcs_routes (\n" +
                    "    addr VARCHAR(64) NOT NULL,\n" +
                    "    hash_start INT NOT NULL,\n" +
                    "    hash_end INT NOT NULL,\n" +
                    "    status TINYINT DEFAULT 1,\n" +
                    "    last_heartbeat DATETIME NOT NULL,\n" +
                    "    created_at DATETIME NOT NULL,\n" +
                    "    updated_at DATETIME NOT NULL,\n" +
                    "    PRIMARY KEY (addr)\n" +
                    ")";
            conn.createStatement().execute(sql);
        } catch (Exception e) {
            log.error("Failed to ensure sdcs_routes table", e);
        }
    }

    private void register() {
        String addr = config.getRegisterAddr();
        String now = nowString();
        // Try INSERT first; if duplicate key (addr exists), fall back to UPDATE.
        // This works across MySQL, SQLite, and other databases without vendor-specific syntax.
        String insertSql = "INSERT INTO sdcs_routes (addr, hash_start, hash_end, status, last_heartbeat, created_at, updated_at)\n" +
                "VALUES (?, ?, ?, 1, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql)) {
            ps.setString(1, addr);
            ps.setInt(2, Integer.parseInt(hashParts[0]));
            ps.setInt(3, Integer.parseInt(hashParts[1]));
            ps.setString(4, now);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
            log.info("Registered to registry: {} hash={}-{}", addr, hashParts[0], hashParts[1]);
        } catch (Exception e) {
            // Duplicate key - update existing row
            String updateSql = "UPDATE sdcs_routes SET status=1, hash_start=?, hash_end=?, last_heartbeat=?, updated_at=? WHERE addr=?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setInt(1, Integer.parseInt(hashParts[0]));
                ps.setInt(2, Integer.parseInt(hashParts[1]));
                ps.setString(3, now);
                ps.setString(4, now);
                ps.setString(5, addr);
                ps.executeUpdate();
                log.info("Registered (updated) to registry: {} hash={}-{}", addr, hashParts[0], hashParts[1]);
            } catch (Exception e2) {
                log.error("Failed to register to registry (update also failed)", e2);
            }
        }
    }

    private void heartbeat() {
        String sql = "UPDATE sdcs_routes SET last_heartbeat=? WHERE addr=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nowString());
            ps.setString(2, config.getRegisterAddr());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Heartbeat update failed: {}", e.getMessage());
        }
    }

    public void stop() {
        log.info("Stopping registry client...");
        running.set(false);
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }

        // Unregister: set status=0
        if (config.getRegisterAddr() != null && dataSource != null) {
            String sql = "UPDATE sdcs_routes SET status=0, updated_at=? WHERE addr=?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, nowString());
                ps.setString(2, config.getRegisterAddr());
                ps.executeUpdate();
                log.info("Unregistered from registry: {}", config.getRegisterAddr());
            } catch (Exception e) {
                log.warn("Failed to unregister: {}", e.getMessage());
            }
        }

        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
        log.info("Registry client stopped.");
    }

    private static String nowString() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
