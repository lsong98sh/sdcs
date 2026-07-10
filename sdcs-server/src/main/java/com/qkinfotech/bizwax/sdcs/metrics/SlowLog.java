package com.qkinfotech.bizwax.sdcs.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SlowLog {

    private static final SlowLog INSTANCE = new SlowLog();

    private final List<SlowLogEntry> entries;
    private final int maxSize;
    private long slowLogThresholdUs = 10_000;

    private SlowLog() {
        this(128);
    }

    public SlowLog(int maxSize) {
        this.maxSize = maxSize;
        this.entries = Collections.synchronizedList(new ArrayList<>(maxSize));
    }

    public static SlowLog getInstance() {
        return INSTANCE;
    }

    public void setSlowLogThresholdUs(long thresholdUs) {
        this.slowLogThresholdUs = thresholdUs;
    }

    public long getSlowLogThresholdUs() {
        return slowLogThresholdUs;
    }

    public void recordIfSlow(String commandName, List<String> args, long durationNanos) {
        long durationUs = durationNanos / 1000;
        if (durationUs >= slowLogThresholdUs) {
            SlowLogEntry entry = new SlowLogEntry(
                    System.currentTimeMillis(),
                    durationUs,
                    commandName,
                    List.copyOf(args.size() > 10 ? args.subList(0, 10) : args)
            );
            synchronized (entries) {
                entries.add(entry);
                if (entries.size() > maxSize) {
                    entries.remove(0);
                }
            }
        }
    }

    public List<SlowLogEntry> getEntries(long count) {
        synchronized (entries) {
            long actualCount = Math.min(count, entries.size());
            List<SlowLogEntry> result = new ArrayList<>((int) actualCount);
            for (int i = entries.size() - (int) actualCount; i < entries.size(); i++) {
                result.add(entries.get(i));
            }
            return result;
        }
    }

    public long getCount() {
        return entries.size();
    }

    public void reset() {
        entries.clear();
    }

    public record SlowLogEntry(
            long timestamp,
            long durationUs,
            String command,
            List<String> args
    ) {}
}
