package com.qkinfotech.bizwax.sdcs.metrics;

public interface SDCSMonitorMXBean {
    long getTotalCommands();
    long getTotalConnections();
    long getCurrentConnections();
    long getTotalErrors();
    long getTotalBytesRead();
    long getTotalBytesWritten();
    long getUptimeSeconds();
    long getUsedMemoryBytes();
    long getDbSize();
    double getOpsPerSecond();
    double getHitRatePercent();
    String[] getTopCommands(int n);
    String getCommandStatsJson();
    void resetStats();
}
