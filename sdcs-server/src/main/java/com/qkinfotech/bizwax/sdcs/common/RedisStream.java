package com.qkinfotech.bizwax.sdcs.common;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public class RedisStream {
    
    public static class StreamEntry {
        private final String id;
        private final Map<String, byte[]> fields;
        
        public StreamEntry(String id, Map<String, byte[]> fields) {
            this.id = id;
            this.fields = new LinkedHashMap<>(fields);
        }
        
        public String getId() { return id; }
        public Map<String, byte[]> getFields() { return fields; }
        
        public long getTimestamp() {
            int dash = id.indexOf('-');
            return Long.parseLong(dash > 0 ? id.substring(0, dash) : id);
        }
        
        public long getSeq() {
            int dash = id.indexOf('-');
            return dash > 0 ? Long.parseLong(id.substring(dash + 1)) : 0;
        }
    }
    
    private final ConcurrentSkipListMap<String, StreamEntry> entries = new ConcurrentSkipListMap<>();
    private final AtomicLong lastTimestamp = new AtomicLong(0);
    private final AtomicLong lastSeq = new AtomicLong(0);
    private long maxLength = 0;
    private boolean approxTrimming = true;
    
    public String xadd(String id, Map<String, byte[]> fields) {
        if ("*".equals(id)) {
            long now = System.currentTimeMillis();
            long ts = now;
            long seq = 0;
            while (true) {
                long currentTs = lastTimestamp.get();
                long currentSeq = lastSeq.get();
                if (ts < currentTs) ts = currentTs;
                else if (ts == currentTs) seq = currentSeq + 1;
                break;
            }
            if (ts == lastTimestamp.get()) {
                seq = lastSeq.incrementAndGet();
            } else {
                lastTimestamp.set(ts);
                lastSeq.set(0);
                seq = 0;
            }
            id = ts + "-" + seq;
            lastTimestamp.set(ts);
            lastSeq.set(seq);
        } else {
            int dash = id.indexOf('-');
            if (dash <= 0) throw new IllegalArgumentException("ERR Invalid stream ID format");
            long ts = Long.parseLong(id.substring(0, dash));
            long seq = Long.parseLong(id.substring(dash + 1));
            String lastId = entries.isEmpty() ? null : entries.lastKey();
            if (lastId != null) {
                String[] parts = lastId.split("-");
                long lastTs = Long.parseLong(parts[0]);
                long lastSeqVal = Long.parseLong(parts[1]);
                if (ts < lastTs || (ts == lastTs && seq <= lastSeqVal)) {
                    throw new IllegalArgumentException("ERR The ID specified in XADD is equal or smaller than the target stream top item");
                }
            }
            if (id.equals("0-0") || (ts == 0 && seq == 0)) {
                throw new IllegalArgumentException("ERR Invalid stream ID specified as stream command argument");
            }
        }
        
        StreamEntry entry = new StreamEntry(id, fields);
        entries.put(id, entry);
        
        if (maxLength > 0) {
            trim();
        }
        
        return id;
    }
    
    public long xlen() { return entries.size(); }
    
    public List<StreamEntry> xrange(String start, String end, long count) {
        String from = start != null && !start.isEmpty() ? start : "-";
        String to = end != null && !end.isEmpty() ? end : "+";
        
        if ("-".equals(from)) from = entries.firstKey();
        if ("+".equals(to)) to = entries.lastKey();
        
        if (from == null || to == null) return List.of();
        
        Map<String, StreamEntry> subMap = entries.subMap(from, true, to, true);
        List<StreamEntry> result = new ArrayList<>(subMap.values());
        
        if (count > 0 && result.size() > count) {
            result = result.subList(0, (int) count);
        }
        return result;
    }
    
    public List<StreamEntry> xrevrange(String end, String start, long count) {
        List<StreamEntry> result = xrange(start, end, count);
        Collections.reverse(result);
        return result;
    }
    
    public List<StreamEntry> xread(String startId, long count) {
        String from = startId;
        if (from == null || from.isEmpty()) from = "0-0";
        if ("$".equals(from)) from = entries.lastKey();
        
        if (from == null) return List.of();
        
        Map<String, StreamEntry> subMap = entries.tailMap(from, false);
        List<StreamEntry> result = new ArrayList<>(subMap.values());
        
        if (count > 0 && result.size() > count) {
            result = result.subList(0, (int) count);
        }
        return result;
    }
    
    public long xtrim(long maxLen, boolean approx) {
        this.maxLength = maxLen;
        this.approxTrimming = approx;
        return trim();
    }
    
    private long trim() {
        if (maxLength <= 0) return 0;
        long removed = 0;
        while (entries.size() > maxLength) {
            String firstKey = entries.firstKey();
            entries.remove(firstKey);
            removed++;
        }
        return removed;
    }
    
    public long xdel(String... ids) {
        long count = 0;
        for (String id : ids) {
            if (entries.remove(id) != null) count++;
        }
        return count;
    }
    
    public List<StreamEntry> xpending(String group, String start, String end, long count, String consumer) {
        return List.of();
    }
    
    public boolean isEmpty() { return entries.isEmpty(); }
}
