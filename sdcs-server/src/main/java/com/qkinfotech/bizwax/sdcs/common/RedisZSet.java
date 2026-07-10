package com.qkinfotech.bizwax.sdcs.common;

import java.util.*;
import java.util.stream.Collectors;

public class RedisZSet {

    private final TreeMap<Double, List<byte[]>> scoreToMembers = new TreeMap<>();
    private final Map<ByteArrayKey, Double> memberToScore = new HashMap<>();

    public long zadd(double score, byte[] member) {
        ByteArrayKey key = new ByteArrayKey(member);
        Double oldScore = memberToScore.get(key);
        if (oldScore != null && Double.compare(oldScore, score) == 0) {
            return 0;
        }

        if (oldScore != null) {
            zrem(member);
        }

        scoreToMembers.computeIfAbsent(score, k -> new ArrayList<>()).add(member);
        memberToScore.put(key, score);
        return oldScore == null ? 1 : 0;
    }

    public double zincrby(double delta, byte[] member) {
        ByteArrayKey key = new ByteArrayKey(member);
        Double oldScore = memberToScore.get(key);
        double newScore = (oldScore == null ? 0 : oldScore) + delta;
        if (oldScore != null) {
            zrem(member);
        }
        scoreToMembers.computeIfAbsent(newScore, k -> new ArrayList<>()).add(member);
        memberToScore.put(key, newScore);
        return newScore;
    }

    public long zrem(byte[]... members) {
        long removed = 0;
        for (byte[] member : members) {
            ByteArrayKey key = new ByteArrayKey(member);
            Double score = memberToScore.remove(key);
            if (score != null) {
                List<byte[]> list = scoreToMembers.get(score);
                if (list != null) {
                    list.removeIf(m -> Arrays.equals(m, member));
                    if (list.isEmpty()) {
                        scoreToMembers.remove(score);
                    }
                }
                removed++;
            }
        }
        return removed;
    }

    public Double zscore(byte[] member) {
        return memberToScore.get(new ByteArrayKey(member));
    }

    public Long zrank(byte[] member) {
        Double score = memberToScore.get(new ByteArrayKey(member));
        if (score == null) return null;

        long rank = 0;
        for (Map.Entry<Double, List<byte[]>> entry : scoreToMembers.entrySet()) {
            if (entry.getKey() < score) {
                rank += entry.getValue().size();
            } else if (entry.getKey().equals(score)) {
                for (byte[] m : entry.getValue()) {
                    if (Arrays.equals(m, member)) {
                        return rank;
                    }
                    rank++;
                }
            }
        }
        return null;
    }

    public Long zrevrank(byte[] member) {
        Double score = memberToScore.get(new ByteArrayKey(member));
        if (score == null) return null;

        long rank = 0;
        for (Map.Entry<Double, List<byte[]>> entry : scoreToMembers.descendingMap().entrySet()) {
            if (entry.getKey() > score) {
                rank += entry.getValue().size();
            } else if (entry.getKey().equals(score)) {
                for (byte[] m : entry.getValue()) {
                    if (Arrays.equals(m, member)) {
                        return rank;
                    }
                    rank++;
                }
            }
        }
        return null;
    }

    public long zcard() {
        return memberToScore.size();
    }

    public long zcount(double min, double max) {
        long count = 0;
        for (Map.Entry<Double, List<byte[]>> entry : scoreToMembers.entrySet()) {
            if (entry.getKey() >= min && entry.getKey() <= max) {
                count += entry.getValue().size();
            }
        }
        return count;
    }

    public List<ZSetEntry> zrange(long start, long stop) {
        List<ZSetEntry> all = getAllEntries();
        int len = all.size();
        int s = (int) (start < 0 ? len + start : start);
        int e = (int) (stop < 0 ? len + stop : stop);

        if (s < 0) s = 0;
        if (e >= len) e = len - 1;
        if (s > e) return List.of();

        return all.subList(s, e + 1);
    }

    public List<ZSetEntry> zrevrange(long start, long stop) {
        List<ZSetEntry> all = getAllEntries();
        Collections.reverse(all);
        int len = all.size();
        int s = (int) (start < 0 ? len + start : start);
        int e = (int) (stop < 0 ? len + stop : stop);

        if (s < 0) s = 0;
        if (e >= len) e = len - 1;
        if (s > e) return List.of();

        return all.subList(s, e + 1);
    }

    public List<ZSetEntry> zrangebyscore(double min, double max, long offset, long count) {
        List<ZSetEntry> result = new ArrayList<>();
        for (Map.Entry<Double, List<byte[]>> entry : scoreToMembers.entrySet()) {
            if (entry.getKey() >= min && entry.getKey() <= max) {
                for (byte[] member : entry.getValue()) {
                    result.add(new ZSetEntry(member, entry.getKey()));
                }
            }
        }
        if (offset > 0 || count >= 0) {
            int start = (int) offset;
            int end = count < 0 ? result.size() : Math.min(start + (int) count, result.size());
            return start < result.size() ? result.subList(start, end) : List.of();
        }
        return result;
    }

    public List<ZSetEntry> zrevrangebyscore(double max, double min, long offset, long count) {
        List<ZSetEntry> result = new ArrayList<>();
        for (Map.Entry<Double, List<byte[]>> entry : scoreToMembers.descendingMap().entrySet()) {
            if (entry.getKey() <= max && entry.getKey() >= min) {
                for (byte[] member : entry.getValue()) {
                    result.add(new ZSetEntry(member, entry.getKey()));
                }
            }
        }
        if (offset > 0 || count >= 0) {
            int start = (int) offset;
            int end = count < 0 ? result.size() : Math.min(start + (int) count, result.size());
            return start < result.size() ? result.subList(start, end) : List.of();
        }
        return result;
    }

    public long zremrangebyrank(long start, long stop) {
        List<ZSetEntry> range = zrange(start, stop);
        byte[][] members = range.stream().map(ZSetEntry::member).toArray(byte[][]::new);
        return zrem(members);
    }

    public long zremrangebyscore(double min, double max) {
        List<ZSetEntry> all = getAllEntries();
        List<byte[]> toRemove = new ArrayList<>();
        for (ZSetEntry entry : all) {
            if (entry.score() >= min && entry.score() <= max) {
                toRemove.add(entry.member());
            }
        }
        return zrem(toRemove.toArray(new byte[0][]));
    }

    public List<ZSetEntry> zpopmin(long count) {
        List<ZSetEntry> result = new ArrayList<>();
        long toPop = Math.min(count, zcard());
        Iterator<Map.Entry<Double, List<byte[]>>> it = scoreToMembers.entrySet().iterator();
        while (it.hasNext() && result.size() < toPop) {
            Map.Entry<Double, List<byte[]>> entry = it.next();
            double score = entry.getKey();
            for (byte[] member : new ArrayList<>(entry.getValue())) {
                if (result.size() >= toPop) break;
                result.add(new ZSetEntry(member, score));
                memberToScore.remove(new ByteArrayKey(member));
            }
            it.remove();
        }
        return result;
    }

    public List<ZSetEntry> zpopmax(long count) {
        List<ZSetEntry> result = new ArrayList<>();
        long toPop = Math.min(count, zcard());
        Iterator<Map.Entry<Double, List<byte[]>>> it = scoreToMembers.descendingMap().entrySet().iterator();
        while (it.hasNext() && result.size() < toPop) {
            Map.Entry<Double, List<byte[]>> entry = it.next();
            double score = entry.getKey();
            for (byte[] member : new ArrayList<>(entry.getValue())) {
                if (result.size() >= toPop) break;
                result.add(new ZSetEntry(member, score));
                memberToScore.remove(new ByteArrayKey(member));
            }
            it.remove();
        }
        return result;
    }

    public List<Double> zmscore(byte[]... members) {
        List<Double> result = new ArrayList<>();
        for (byte[] member : members) {
            Double score = memberToScore.get(new ByteArrayKey(member));
            result.add(score);
        }
        return result;
    }

    public List<byte[]> zrandmember(long count) {
        List<byte[]> allMembers = getAllEntries().stream().map(ZSetEntry::member).toList();
        if (allMembers.isEmpty()) return List.of();
        if (count >= 0) {
            count = Math.min(count, allMembers.size());
            List<byte[]> copy = new ArrayList<>(allMembers);
            Collections.shuffle(copy);
            return copy.subList(0, (int) count);
        } else {
            count = -count;
            List<byte[]> result = new ArrayList<>();
            Random random = new Random();
            for (int i = 0; i < count; i++) {
                result.add(allMembers.get(random.nextInt(allMembers.size())));
            }
            return result;
        }
    }

    public static List<ZSetEntry> zdiff(List<RedisZSet> sets) {
        if (sets.isEmpty()) return List.of();
        RedisZSet first = sets.get(0);
        List<ZSetEntry> result = new ArrayList<>();
        for (Map.Entry<ByteArrayKey, Double> entry : first.memberToScore.entrySet()) {
            boolean inOthers = false;
            for (int i = 1; i < sets.size(); i++) {
                if (sets.get(i).memberToScore.containsKey(entry.getKey())) {
                    inOthers = true;
                    break;
                }
            }
            if (!inOthers) {
                result.add(new ZSetEntry(entry.getKey().getData(), entry.getValue()));
            }
        }
        return result;
    }

    public static List<ZSetEntry> zinter(List<RedisZSet> sets, List<Double> weights, String aggregate) {
        if (sets.isEmpty()) return List.of();
        RedisZSet first = sets.get(0);
        Map<ByteArrayKey, double[]> scores = new HashMap<>();

        for (Map.Entry<ByteArrayKey, Double> entry : first.memberToScore.entrySet()) {
            double w = weights != null && weights.size() > 0 ? weights.get(0) : 1.0;
            scores.put(entry.getKey(), new double[]{entry.getValue() * w});
        }

        for (int i = 1; i < sets.size(); i++) {
            double w = weights != null && weights.size() > i ? weights.get(i) : 1.0;
            Iterator<Map.Entry<ByteArrayKey, double[]>> it = scores.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<ByteArrayKey, double[]> entry = it.next();
                Double score = sets.get(i).memberToScore.get(entry.getKey());
                if (score == null) {
                    it.remove();
                } else {
                    entry.getValue()[0] = combineScores(entry.getValue()[0], score * w, aggregate);
                }
            }
        }

        List<ZSetEntry> result = new ArrayList<>();
        for (Map.Entry<ByteArrayKey, double[]> entry : scores.entrySet()) {
            result.add(new ZSetEntry(entry.getKey().getData(), entry.getValue()[0]));
        }
        result.sort(Comparator.comparingDouble(ZSetEntry::score));
        return result;
    }

    public static List<ZSetEntry> zunion(List<RedisZSet> sets, List<Double> weights, String aggregate) {
        if (sets.isEmpty()) return List.of();
        Map<ByteArrayKey, Double> merged = new HashMap<>();

        for (int i = 0; i < sets.size(); i++) {
            double w = weights != null && weights.size() > i ? weights.get(i) : 1.0;
            for (Map.Entry<ByteArrayKey, Double> entry : sets.get(i).memberToScore.entrySet()) {
                Double existing = merged.get(entry.getKey());
                double newScore = entry.getValue() * w;
                if (existing == null) {
                    merged.put(entry.getKey(), newScore);
                } else {
                    merged.put(entry.getKey(), combineScores(existing, newScore, aggregate));
                }
            }
        }

        List<ZSetEntry> result = new ArrayList<>();
        for (Map.Entry<ByteArrayKey, Double> entry : merged.entrySet()) {
            result.add(new ZSetEntry(entry.getKey().getData(), entry.getValue()));
        }
        result.sort(Comparator.comparingDouble(ZSetEntry::score));
        return result;
    }

    private static double combineScores(double a, double b, String aggregate) {
        return switch (aggregate.toUpperCase()) {
            case "MIN" -> Math.min(a, b);
            case "MAX" -> Math.max(a, b);
            default -> a + b;
        };
    }

    public long zlexcount(byte[] min, byte[] max) {
        List<byte[]> allMembers = getAllMembersSorted();
        LexRange minRange = LexRange.parse(min);
        LexRange maxRange = LexRange.parse(max);
        long count = 0;
        for (byte[] m : allMembers) {
            if (minRange.compare(m) >= 0 && maxRange.compare(m) <= 0) {
                count++;
            }
        }
        return count;
    }

    public List<byte[]> zrangebylex(byte[] min, byte[] max, long offset, long count) {
        List<byte[]> allMembers = getAllMembersSorted();
        LexRange minRange = LexRange.parse(min);
        LexRange maxRange = LexRange.parse(max);
        List<byte[]> result = new ArrayList<>();
        for (byte[] m : allMembers) {
            if (minRange.compare(m) >= 0 && maxRange.compare(m) <= 0) {
                result.add(m);
            }
        }
        if (offset > 0 || count >= 0) {
            int start = (int) Math.min(offset, result.size());
            int end = count < 0 ? result.size() : (int) Math.min(start + count, result.size());
            return start < result.size() ? result.subList(start, end) : List.of();
        }
        return result;
    }

    public List<byte[]> zrevrangebylex(byte[] max, byte[] min, long offset, long count) {
        List<byte[]> all = zrangebylex(min, max, 0, -1);
        Collections.reverse(all);
        if (offset > 0 || count >= 0) {
            int start = (int) Math.min(offset, all.size());
            int end = count < 0 ? all.size() : (int) Math.min(start + count, all.size());
            return start < all.size() ? all.subList(start, end) : List.of();
        }
        return all;
    }

    public long zremrangebylex(byte[] min, byte[] max) {
        LexRange minRange = LexRange.parse(min);
        LexRange maxRange = LexRange.parse(max);
        List<byte[]> toRemove = new ArrayList<>();
        for (Map.Entry<ByteArrayKey, Double> entry : memberToScore.entrySet()) {
            byte[] m = entry.getKey().getData();
            if (minRange.compare(m) >= 0 && maxRange.compare(m) <= 0) {
                toRemove.add(m);
            }
        }
        return zrem(toRemove.toArray(new byte[0][]));
    }

    /**
     * Helper to parse Redis lexicographic range syntax:
     * {@code [value} → inclusive (>= or <=), {@code (value} → exclusive (&gt; or &lt;),
     * {@code -} → negative infinity, {@code +} → positive infinity.
     */
    private static class LexRange {
        enum Bound { INF_NEG, INF_POS, OPEN, CLOSED }
        final Bound bound;
        final byte[] value;

        LexRange(Bound bound, byte[] value) {
            this.bound = bound;
            this.value = value;
        }

        static LexRange parse(byte[] raw) {
            if (raw == null || raw.length == 0) {
                return new LexRange(Bound.CLOSED, new byte[0]);
            }
            String s = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            if ("-".equals(s)) return new LexRange(Bound.INF_NEG, new byte[0]);
            if ("+".equals(s)) return new LexRange(Bound.INF_POS, new byte[0]);
            if (s.startsWith("[")) {
                return new LexRange(Bound.CLOSED, s.substring(1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (s.startsWith("(")) {
                return new LexRange(Bound.OPEN, s.substring(1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            // no prefix — treat as closed for backward compatibility
            return new LexRange(Bound.CLOSED, raw);
        }

        /** Returns &lt;0 if member is less than this bound, 0 if equal (and inclusive), &gt;0 if greater. */
        int compare(byte[] member) {
            return switch (bound) {
                case INF_NEG -> -1; // member is always greater than -inf
                case INF_POS -> 1;  // member is always less than +inf
                case CLOSED -> compareBytes(member, value);
                case OPEN -> {
                    int cmp = compareBytes(member, value);
                    yield cmp <= 0 ? cmp - 1 : cmp; // shift to ensure exclusive
                }
            };
        }
    }

    private List<byte[]> getAllMembersSorted() {
        List<byte[]> all = new ArrayList<>();
        for (Map.Entry<Double, List<byte[]>> entry : scoreToMembers.entrySet()) {
            all.addAll(entry.getValue());
        }
        all.sort(RedisZSet::compareBytes);
        return all;
    }

    private static int compareBytes(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int cmp = Byte.toUnsignedInt(a[i]) - Byte.toUnsignedInt(b[i]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.length, b.length);
    }

    public List<Object> scan(long cursor, long count) {
        List<Map.Entry<ByteArrayKey, Double>> allEntries = new ArrayList<>(memberToScore.entrySet());
        long totalEntries = allEntries.size();

        if (cursor >= totalEntries) {
            return List.of(0L, new byte[0][]);
        }

        long endIndex = Math.min(cursor + count, totalEntries);
        List<byte[]> elements = new ArrayList<>();

        for (long i = cursor; i < endIndex; i++) {
            Map.Entry<ByteArrayKey, Double> entry = allEntries.get((int) i);
            elements.add(entry.getKey().getData());
            elements.add(String.valueOf(entry.getValue()).getBytes());
        }

        long nextCursor = endIndex >= totalEntries ? 0 : endIndex;
        return List.of(nextCursor, elements.toArray(new byte[0][]));
    }

    public boolean isEmpty() {
        return memberToScore.isEmpty();
    }

    private List<ZSetEntry> getAllEntries() {
        List<ZSetEntry> result = new ArrayList<>();
        for (Map.Entry<Double, List<byte[]>> entry : scoreToMembers.entrySet()) {
            for (byte[] member : entry.getValue()) {
                result.add(new ZSetEntry(member, entry.getKey()));
            }
        }
        return result;
    }

    public record ZSetEntry(byte[] member, double score) {}
}
