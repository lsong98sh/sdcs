package com.qkinfotech.bizwax.sdcs.metrics;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class PrometheusExporter {

    private static final Logger log = LoggerFactory.getLogger(PrometheusExporter.class);

    private final HttpServer server;
    private final int port;

    public PrometheusExporter(int port) throws IOException {
        this.port = port;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", exchange -> {
            String body = buildMetrics();
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.createContext("/", exchange -> {
            String body = "SDCS Prometheus Exporter " + port + "\n";
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "SDCS-Prometheus");
            t.setDaemon(true);
            return t;
        }));
    }

    public void start() {
        server.start();
        log.info("Prometheus metrics endpoint started on http://0.0.0.0:{}/metrics", port);
    }

    public void stop() {
        server.stop(0);
        log.info("Prometheus metrics endpoint stopped");
    }

    private String buildMetrics() {
        MetricsCollector mc = MetricsCollector.getInstance();
        Runtime rt = Runtime.getRuntime();
        var memBean = java.lang.management.ManagementFactory.getMemoryMXBean();
        var heapUsage = memBean.getHeapMemoryUsage();

        StringBuilder sb = new StringBuilder();

        sb.append("# HELP sdcs_uptime_seconds Total uptime\n");
        sb.append("# TYPE sdcs_uptime_seconds counter\n");
        sb.append("sdcs_uptime_seconds ").append(mc.getUptimeSeconds()).append("\n\n");

        sb.append("# HELP sdcs_commands_total Total commands processed\n");
        sb.append("# TYPE sdcs_commands_total counter\n");
        sb.append("sdcs_commands_total ").append(mc.getTotalCommands()).append("\n\n");

        sb.append("# HELP sdcs_commands_errors_total Total command errors\n");
        sb.append("# TYPE sdcs_commands_errors_total counter\n");
        sb.append("sdcs_commands_errors_total ").append(mc.getTotalErrors()).append("\n\n");

        sb.append("# HELP sdcs_connections_total Total connections received\n");
        sb.append("# TYPE sdcs_connections_total counter\n");
        sb.append("sdcs_connections_total ").append(mc.getTotalConnections()).append("\n\n");

        sb.append("# HELP sdcs_connections_current Current connected clients\n");
        sb.append("# TYPE sdcs_connections_current gauge\n");
        sb.append("sdcs_connections_current ").append(mc.getCurrentConnections()).append("\n\n");

        sb.append("# HELP sdcs_net_input_bytes_total Total bytes read\n");
        sb.append("# TYPE sdcs_net_input_bytes_total counter\n");
        sb.append("sdcs_net_input_bytes_total ").append(mc.getTotalBytesRead()).append("\n\n");

        sb.append("# HELP sdcs_net_output_bytes_total Total bytes written\n");
        sb.append("# TYPE sdcs_net_output_bytes_total counter\n");
        sb.append("sdcs_net_output_bytes_total ").append(mc.getTotalBytesWritten()).append("\n\n");

        long usedMem = rt.totalMemory() - rt.freeMemory();
        sb.append("# HELP sdcs_memory_used_bytes JVM used heap memory\n");
        sb.append("# TYPE sdcs_memory_used_bytes gauge\n");
        sb.append("sdcs_memory_used_bytes ").append(usedMem).append("\n\n");

        sb.append("# HELP sdcs_memory_max_bytes JVM max heap memory\n");
        sb.append("# TYPE sdcs_memory_max_bytes gauge\n");
        sb.append("sdcs_memory_max_bytes ").append(rt.maxMemory()).append("\n\n");

        sb.append("# HELP sdcs_memory_heap_used_bytes JVM heap used\n");
        sb.append("# TYPE sdcs_memory_heap_used_bytes gauge\n");
        sb.append("sdcs_memory_heap_used_bytes ").append(heapUsage.getUsed()).append("\n\n");

        sb.append("# HELP sdcs_memory_heap_committed_bytes JVM heap committed\n");
        sb.append("# TYPE sdcs_memory_heap_committed_bytes gauge\n");
        sb.append("sdcs_memory_heap_committed_bytes ").append(heapUsage.getCommitted()).append("\n\n");

        sb.append("# HELP sdcs_db_keys Total keys in database\n");
        sb.append("# TYPE sdcs_db_keys gauge\n");
        long dbSize = 0;
        try {
            var executorClass = Class.forName("com.qkinfotech.bizwax.sdcs.server.SDCSCommandExecutor");
            var getStore = executorClass.getMethod("getStore");
            var store = getStore.invoke(null);
            var sizeMethod = store.getClass().getMethod("size");
            dbSize = ((Number) sizeMethod.invoke(store)).longValue();
        } catch (Exception e) {
            log.warn("Failed to get DB keys for metrics: {}", e.getMessage());
        }
        sb.append("sdcs_db_keys ").append(dbSize).append("\n\n");

        sb.append("# HELP sdcs_commands_duration_ms Command duration histogram\n");
        sb.append("# TYPE sdcs_commands_duration_ms summary\n");
        for (var entry : mc.getCommandStats().entrySet()) {
            String cmd = entry.getKey().toLowerCase().replaceAll("[^a-z0-9]", "_");
            var stats = entry.getValue();
            sb.append("sdcs_commands_duration_ms{command=\"").append(cmd)
                    .append("\",quantile=\"avg\"} ")
                    .append(String.format("%.3f", stats.getAvgDurationMs())).append("\n");
            sb.append("sdcs_commands_duration_ms{command=\"").append(cmd)
                    .append("\",quantile=\"max\"} ")
                    .append(String.format("%.3f", stats.getMaxDurationMs())).append("\n");
            sb.append("sdcs_commands_total{command=\"").append(cmd)
                    .append("\"} ").append(stats.getCount()).append("\n");
        }
        sb.append("\n");

        sb.append("# HELP sdcs_ops_per_second Instantaneous operations per second\n");
        sb.append("# TYPE sdcs_ops_per_second gauge\n");
        long uptime = mc.getUptimeSeconds();
        double ops = uptime > 0 ? (double) mc.getTotalCommands() / uptime : 0;
        sb.append("sdcs_ops_per_second ").append(String.format("%.2f", ops)).append("\n\n");

        sb.append("# HELP sdcs_hit_rate_percent Command success rate\n");
        sb.append("# TYPE sdcs_hit_rate_percent gauge\n");
        long total = mc.getTotalCommands();
        double hitRate = total > 0 ? (1.0 - (double) mc.getTotalErrors() / total) * 100 : 100;
        sb.append("sdcs_hit_rate_percent ").append(String.format("%.2f", hitRate)).append("\n\n");

        sb.append("# HELP sdcs_gc_info JVM garbage collection info\n");
        sb.append("# TYPE sdcs_gc_info gauge\n");
        for (var gcBean : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = gcBean.getName().replaceAll("[^a-zA-Z0-9]", "_").toLowerCase();
            sb.append("sdcs_gc_collection_count{gc=\"").append(name)
                    .append("\"} ").append(gcBean.getCollectionCount()).append("\n");
            sb.append("sdcs_gc_collection_time_ms{gc=\"").append(name)
                    .append("\"} ").append(gcBean.getCollectionTime()).append("\n");
        }
        sb.append("\n");

        return sb.toString();
    }
}
