package com.qkinfotech.bizwax.sdcs.monitor.registry;

import com.qkinfotech.bizwax.sdcs.monitor.config.MonitorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RegistryReader {

    private static final Logger log = LoggerFactory.getLogger(RegistryReader.class);

    private final MonitorConfig config;

    public RegistryReader(MonitorConfig config) {
        this.config = config;
    }

    public void init() {
        if (config.getRegistryJdbcUrl().isBlank()) {
            log.warn("No registry JDBC URL configured; cluster data unavailable");
            return;
        }
        // Load JDBC driver if needed (visible for classpath-less drivers like SQLite)
        try {
            String url = config.getRegistryJdbcUrl();
            if (url.startsWith("jdbc:sqlite:")) {
                Class.forName("org.sqlite.JDBC");
            } else if (url.startsWith("jdbc:mysql:")) {
                Class.forName("com.mysql.cj.jdbc.Driver");
            }
        } catch (ClassNotFoundException e) {
            log.warn("JDBC driver not found in classpath, will rely on service loader", e);
        }
        log.info("Registry reader initialized: {}", config.getRegistryJdbcUrl());
    }

    public List<NodeInfo> getClusterNodes() {
        if (config.getRegistryJdbcUrl().isBlank()) {
            return Collections.emptyList();
        }

        List<NodeInfo> nodes = new ArrayList<>();
        String sql = "SELECT addr, hash_start, hash_end, status, last_heartbeat " +
                     "FROM sdcs_routes ORDER BY addr, hash_start";

        try (Connection conn = DriverManager.getConnection(
                config.getRegistryJdbcUrl(),
                config.getRegistryUsername(),
                config.getRegistryPassword());
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                NodeInfo node = new NodeInfo(
                    rs.getString("addr"),
                    rs.getInt("hash_start"),
                    rs.getInt("hash_end"),
                    rs.getInt("status"),
                    rs.getTimestamp("last_heartbeat")
                );
                nodes.add(node);
            }
        } catch (Exception e) {
            log.error("Failed to query cluster nodes", e);
        }
        return nodes;
    }

    public List<ProxyInfo> getProxyNodes() {
        if (config.getRegistryJdbcUrl().isBlank()) {
            return Collections.emptyList();
        }

        List<ProxyInfo> proxies = new ArrayList<>();
        String sql = "SELECT addr, version, started_at, last_heartbeat " +
                     "FROM sdcs_proxies ORDER BY addr";

        try (Connection conn = DriverManager.getConnection(
                config.getRegistryJdbcUrl(),
                config.getRegistryUsername(),
                config.getRegistryPassword());
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                proxies.add(new ProxyInfo(
                    rs.getString("addr"),
                    rs.getString("version"),
                    rs.getTimestamp("started_at"),
                    rs.getTimestamp("last_heartbeat")
                ));
            }
        } catch (Exception e) {
            log.error("Failed to query proxy nodes", e);
        }
        return proxies;
    }

    public void close() {
        // No pool to close; connections are short-lived and auto-closed via try-with-resources
        log.debug("Registry reader closed");
    }

    public static class NodeInfo {
        private final String addr;
        private final int hashStart;
        private final int hashEnd;
        private final int status;
        private final java.sql.Timestamp lastHeartbeat;

        public NodeInfo(String addr, int hashStart, int hashEnd, int status, java.sql.Timestamp lastHeartbeat) {
            this.addr = addr;
            this.hashStart = hashStart;
            this.hashEnd = hashEnd;
            this.status = status;
            this.lastHeartbeat = lastHeartbeat;
        }

        public String getAddr() { return addr; }
        public int getHashStart() { return hashStart; }
        public int getHashEnd() { return hashEnd; }
        public int getStatus() { return status; }
        public boolean isOnline() { return status == 1; }
        public java.sql.Timestamp getLastHeartbeat() { return lastHeartbeat; }
    }

    public static class ProxyInfo {
        private final String addr;
        private final String version;
        private final java.sql.Timestamp startedAt;
        private final java.sql.Timestamp lastHeartbeat;

        public ProxyInfo(String addr, String version, java.sql.Timestamp startedAt, java.sql.Timestamp lastHeartbeat) {
            this.addr = addr;
            this.version = version;
            this.startedAt = startedAt;
            this.lastHeartbeat = lastHeartbeat;
        }

        public String getAddr() { return addr; }
        public String getVersion() { return version; }
        public java.sql.Timestamp getStartedAt() { return startedAt; }
        public java.sql.Timestamp getLastHeartbeat() { return lastHeartbeat; }
    }
}
