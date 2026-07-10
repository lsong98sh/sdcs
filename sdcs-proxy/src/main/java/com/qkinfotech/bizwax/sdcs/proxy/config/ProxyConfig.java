package com.qkinfotech.bizwax.sdcs.proxy.config;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class ProxyConfig {

    private static final int DEFAULT_PROXY_PORT = 16379;
    private static final int DEFAULT_MAX_CONNECTIONS = 10000;
    private static final int DEFAULT_REFRESH_INTERVAL_SECONDS = 10;
    private static final int DEFAULT_HEARTBEAT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_BACKEND_MIN_IDLE = 2;
    private static final int DEFAULT_BACKEND_MAX_TOTAL = 16;

    private final int proxyPort;
    private final String proxyBind;
    private final String proxyAddr;
    private final int maxConnections;
    private final String registryJdbcUrl;
    private final String registryUsername;
    private final String registryPassword;
    private final int refreshIntervalSeconds;
    private final int heartbeatTimeoutSeconds;
    private final int backendMinIdle;
    private final int backendMaxTotal;

    public ProxyConfig(String path) {
        Properties props = new Properties();
        loadProperties(props, path);

        this.proxyPort = parseInt(props.getProperty("proxy.port"), DEFAULT_PROXY_PORT);
        this.proxyBind = props.getProperty("proxy.bind", "0.0.0.0");
        this.proxyAddr = props.getProperty("proxy.addr", this.proxyBind + ":" + this.proxyPort);
        // If bind is 0.0.0.0, proxy.addr must be explicitly set for external connectivity
        this.maxConnections = parseInt(props.getProperty("proxy.max-connections"), DEFAULT_MAX_CONNECTIONS);
        this.registryJdbcUrl = props.getProperty("registry.jdbc-url", "");
        this.registryUsername = props.getProperty("registry.username", "");
        this.registryPassword = props.getProperty("registry.password", "");
        this.refreshIntervalSeconds = parseInt(props.getProperty("registry.refresh-interval-seconds"),
                DEFAULT_REFRESH_INTERVAL_SECONDS);
        this.heartbeatTimeoutSeconds = parseInt(props.getProperty("registry.heartbeat-timeout-seconds"),
                DEFAULT_HEARTBEAT_TIMEOUT_SECONDS);
        this.backendMinIdle = parseInt(props.getProperty("backend.min-idle"), DEFAULT_BACKEND_MIN_IDLE);
        this.backendMaxTotal = parseInt(props.getProperty("backend.max-total"), DEFAULT_BACKEND_MAX_TOTAL);
    }

    private void loadProperties(Properties props, String path) {
        // Try filesystem first (for test/prod outside classpath)
        Path filePath = Paths.get(path);
        if (Files.exists(filePath)) {
            try (InputStream in = new FileInputStream(filePath.toFile())) {
                props.load(in);
                return;
            } catch (Exception e) {
                throw new RuntimeException("Failed to load config from file: " + path, e);
            }
        }

        // Fallback to classpath
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config from classpath: " + path, e);
        }
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public int getProxyPort() { return proxyPort; }
    public String getProxyBind() { return proxyBind; }
    public String getProxyAddr() { return proxyAddr; }
    public int getMaxConnections() { return maxConnections; }
    public String getRegistryJdbcUrl() { return registryJdbcUrl; }
    public String getRegistryUsername() { return registryUsername; }
    public String getRegistryPassword() { return registryPassword; }
    public int getRefreshIntervalSeconds() { return refreshIntervalSeconds; }
    public int getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public int getBackendMinIdle() { return backendMinIdle; }
    public int getBackendMaxTotal() { return backendMaxTotal; }
}
