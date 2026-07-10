package com.qkinfotech.bizwax.sdcs.metrics;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.stream.Collectors;

public class SDCSMonitor implements SDCSMonitorMXBean {

    private static final SDCSMonitor INSTANCE = new SDCSMonitor();
    private final MetricsCollector mc = MetricsCollector.getInstance();

    private SDCSMonitor() {
        registerMBean();
    }

    public static SDCSMonitor getInstance() {
        return INSTANCE;
    }

    private void registerMBean() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.qkinfotech.bizwax.sdcs:type=SDCSMonitor");
            if (!mbs.isRegistered(name)) {
                mbs.registerMBean(this, name);
            }
        } catch (Exception e) {
            System.err.println("Failed to register SDCS MBean: " + e.getMessage());
        }
    }

    @Override
    public long getTotalCommands() {
        return mc.getTotalCommands();
    }

    @Override
    public long getTotalConnections() {
        return mc.getTotalConnections();
    }

    @Override
    public long getCurrentConnections() {
        return mc.getCurrentConnections();
    }

    @Override
    public long getTotalErrors() {
        return mc.getTotalErrors();
    }

    @Override
    public long getTotalBytesRead() {
        return mc.getTotalBytesRead();
    }

    @Override
    public long getTotalBytesWritten() {
        return mc.getTotalBytesWritten();
    }

    @Override
    public long getUptimeSeconds() {
        return mc.getUptimeSeconds();
    }

    @Override
    public long getUsedMemoryBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    @Override
    public long getDbSize() {
        try {
            var field = mc.getClass().getDeclaredField("store");
            if (field != null) return -1;
        } catch (Exception e) {
            // Not available — return -1
        }
        return -1;
    }

    @Override
    public double getOpsPerSecond() {
        long uptime = mc.getUptimeSeconds();
        return uptime > 0 ? (double) mc.getTotalCommands() / uptime : 0;
    }

    @Override
    public double getHitRatePercent() {
        long total = mc.getTotalCommands();
        return total > 0 ? (1.0 - (double) mc.getTotalErrors() / total) * 100 : 100;
    }

    @Override
    public String[] getTopCommands(int n) {
        return mc.getCommandStats().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().getCount(), a.getValue().getCount()))
                .limit(n)
                .map(e -> e.getKey() + "=" + e.getValue().getCount())
                .toArray(String[]::new);
    }

    @Override
    public String getCommandStatsJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : mc.getCommandStats().entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":{");
            sb.append("\"calls\":").append(entry.getValue().getCount()).append(",");
            sb.append("\"avgMs\":").append(String.format("%.3f", entry.getValue().getAvgDurationMs())).append(",");
            sb.append("\"maxMs\":").append(String.format("%.3f", entry.getValue().getMaxDurationMs()));
            sb.append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public void resetStats() {
        var stats = mc.getCommandStats();
        stats.clear();
    }
}
