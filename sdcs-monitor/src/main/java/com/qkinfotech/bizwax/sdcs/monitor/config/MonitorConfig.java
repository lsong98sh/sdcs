package com.qkinfotech.bizwax.sdcs.monitor.config;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class MonitorConfig {

    private static final int DEFAULT_HTTP_PORT = 8080;
    private static final int DEFAULT_REFRESH_INTERVAL_SECONDS = 10;
    private static final int DEFAULT_HEARTBEAT_TIMEOUT_SECONDS = 60;

    private final int httpPort;
    private final String httpBind;
    private final String registryJdbcUrl;
    private final String registryUsername;
    private final String registryPassword;
    private final int refreshIntervalSeconds;
    private final int heartbeatTimeoutSeconds;

    public MonitorConfig(String path) {
        Properties props = new Properties();
        loadProperties(props, path);

        this.httpPort = parseInt(props.getProperty("monitor.http.port"), DEFAULT_HTTP_PORT);
        this.httpBind = props.getProperty("monitor.http.bind", "0.0.0.0");
        this.registryJdbcUrl = props.getProperty("registry.jdbc-url", "");
        this.registryUsername = props.getProperty("registry.username", "");
        this.registryPassword = props.getProperty("registry.password", "");
        this.refreshIntervalSeconds = parseInt(props.getProperty("registry.refresh-interval-seconds"),
                DEFAULT_REFRESH_INTERVAL_SECONDS);
        this.heartbeatTimeoutSeconds = parseInt(props.getProperty("registry.heartbeat-timeout-seconds"),
                DEFAULT_HEARTBEAT_TIMEOUT_SECONDS);
    }

    private void loadProperties(Properties props, String path) {
        Path filePath = Paths.get(path);
        if (Files.exists(filePath)) {
            try (InputStream in = new FileInputStream(filePath.toFile())) {
                props.load(in);
                return;
            } catch (Exception e) {
                throw new RuntimeException("Failed to load config from file: " + path, e);
            }
        }
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

    public int getHttpPort() { return httpPort; }
    public String getHttpBind() { return httpBind; }
    public String getRegistryJdbcUrl() { return registryJdbcUrl; }
    public String getRegistryUsername() { return registryUsername; }
    public String getRegistryPassword() { return registryPassword; }
    public int getRefreshIntervalSeconds() { return refreshIntervalSeconds; }
    public int getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
}
