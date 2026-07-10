package com.qkinfotech.bizwax.sdcs.monitor.http;

import com.qkinfotech.bizwax.sdcs.monitor.registry.RegistryReader;
import com.qkinfotech.bizwax.sdcs.monitor.config.MonitorConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ClusterHandler implements HttpHandler {

    private final RegistryReader registryReader;
    private final MonitorConfig config;

    public ClusterHandler(RegistryReader registryReader, MonitorConfig config) {
        this.registryReader = registryReader;
        this.config = config;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            List<RegistryReader.NodeInfo> nodes = registryReader.getClusterNodes();
            String json = toJson(nodes);
            byte[] response = json.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        } catch (Exception e) {
            byte[] error = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, error.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(error);
            }
        }
    }

    private String toJson(List<RegistryReader.NodeInfo> nodes) {
        long now = System.currentTimeMillis();
        int timeoutSecs = config.getHeartbeatTimeoutSeconds();

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"nodes\": [\n");
        for (int i = 0; i < nodes.size(); i++) {
            RegistryReader.NodeInfo node = nodes.get(i);
            long elapsed = node.getLastHeartbeat() != null
                    ? (now - node.getLastHeartbeat().getTime()) / 1000 : -1;
            boolean stale = elapsed > timeoutSecs;

            sb.append("    {");
            sb.append("\"addr\":\"").append(jsonEscape(node.getAddr())).append("\",");
            sb.append("\"hash_range\":\"").append(node.getHashStart()).append("-").append(node.getHashEnd()).append("\",");
            sb.append("\"status\":").append(node.getStatus()).append(",");
            sb.append("\"online\":").append(node.isOnline()).append(",");
            sb.append("\"heartbeat_age_secs\":").append(elapsed).append(",");
            sb.append("\"stale\":").append(stale);
            sb.append("}");
            if (i < nodes.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"node_count\": ").append(nodes.size()).append(",\n");
        sb.append("  \"heartbeat_timeout_secs\": ").append(timeoutSecs).append("\n");
        sb.append("}");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "{\"status\":\"UP\",\"service\":\"sdcs-monitor\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    public static class ProxyHandler implements HttpHandler {
        private final RegistryReader registryReader;
        public ProxyHandler(RegistryReader registryReader) { this.registryReader = registryReader; }
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                List<RegistryReader.ProxyInfo> proxies = registryReader.getProxyNodes();
                StringBuilder sb = new StringBuilder();
                long now = System.currentTimeMillis();
                sb.append("{\"proxies\":[");
                for (int i = 0; i < proxies.size(); i++) {
                    RegistryReader.ProxyInfo p = proxies.get(i);
                    long hbAge = p.getLastHeartbeat() != null
                            ? (now - p.getLastHeartbeat().getTime()) / 1000 : -1;
                    long upTime = p.getStartedAt() != null
                            ? (now - p.getStartedAt().getTime()) / 1000 : -1;
                    if (i > 0) sb.append(",");
                    sb.append("{\"addr\":\"").append(jsonEscape(p.getAddr())).append("\"");
                    sb.append(",\"version\":\"").append(jsonEscape(p.getVersion())).append("\"");
                    sb.append(",\"uptime_secs\":").append(upTime);
                    sb.append(",\"heartbeat_age_secs\":").append(hbAge);
                    sb.append("}");
                }
                sb.append("],\"proxy_count\":").append(proxies.size()).append("}");
                byte[] response = sb.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(response); }
            } catch (Exception e) {
                byte[] error = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, error.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(error); }
            }
        }
    }
}
