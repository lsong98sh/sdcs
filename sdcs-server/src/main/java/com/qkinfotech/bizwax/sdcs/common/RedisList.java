package com.qkinfotech.bizwax.sdcs.common;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class RedisList {

    private final LinkedList<byte[]> elements = new LinkedList<>();

    public void lpush(byte[]... values) {
        for (byte[] value : values) {
            elements.addFirst(value);
        }
    }

    public void rpush(byte[]... values) {
        for (byte[] value : values) {
            elements.addLast(value);
        }
    }

    public byte[] lpop() {
        return elements.isEmpty() ? null : elements.removeFirst();
    }

    public byte[] rpop() {
        return elements.isEmpty() ? null : elements.removeLast();
    }

    public long llen() {
        return elements.size();
    }

    public byte[] lindex(long index) {
        int idx = normalizeIndex(index);
        if (idx < 0 || idx >= elements.size()) return null;
        return elements.get(idx);
    }

    public List<byte[]> lrange(long start, long stop) {
        int len = elements.size();
        int s = normalizeIndex(start);
        int e = normalizeIndex(stop);

        if (s < 0) s = 0;
        if (e >= len) e = len - 1;
        if (s > e) return List.of();

        return elements.subList(s, e + 1).stream().toList();
    }

    public long lset(long index, byte[] value) {
        int idx = normalizeIndex(index);
        if (idx < 0 || idx >= elements.size()) {
            return 0;
        }
        elements.set(idx, value);
        return 1;
    }

    public long ltrim(long start, long stop) {
        int len = elements.size();
        int s = normalizeIndex(start);
        int e = normalizeIndex(stop);

        if (s < 0) s = 0;
        if (e >= len) e = len - 1;

        if (s > e) {
            elements.clear();
        } else {
            List<byte[]> toKeep = new LinkedList<>(elements.subList(s, e + 1));
            elements.retainAll(toKeep);
        }
        return 1;
    }

    public long lrem(long count, byte[] value) {
        long removed = 0;
        if (count == 0) {
            int before = elements.size();
            elements.removeIf(v -> Arrays.equals(v, value));
            removed = before - elements.size();
        } else if (count > 0) {
            int toRemove = (int) count;
            var it = elements.iterator();
            while (it.hasNext() && toRemove > 0) {
                if (Arrays.equals(it.next(), value)) {
                    it.remove();
                    removed++;
                    toRemove--;
                }
            }
        } else {
            int toRemove = (int) -count;
            List<byte[]> reversed = new LinkedList<>(elements);
            java.util.Collections.reverse(reversed);
            var it = reversed.iterator();
            while (it.hasNext() && toRemove > 0) {
                if (Arrays.equals(it.next(), value)) {
                    it.remove();
                    removed++;
                    toRemove--;
                }
            }
            elements.clear();
            elements.addAll(reversed);
            java.util.Collections.reverse(elements);
        }
        return removed;
    }

    public long linsert(boolean before, byte[] pivot, byte[] value) {
        int idx = indexOf(pivot);
        if (idx < 0) return -1;
        elements.add(before ? idx : idx + 1, value);
        return elements.size();
    }

    public byte[] rpoplpush(RedisList dest) {
        byte[] value = rpop();
        if (value == null) return null;
        dest.lpush(value);
        return value;
    }

    public void lpushX(byte[]... values) {
        if (elements.isEmpty()) return;
        lpush(values);
    }

    public void rpushX(byte[]... values) {
        if (elements.isEmpty()) return;
        rpush(values);
    }

    public List<Integer> lpos(byte[] value, int rank, int count, int maxlen) {
        List<Integer> positions = new java.util.ArrayList<>();
        int occurrences = 0;
        int searchLimit = maxlen > 0 ? Math.min(maxlen, elements.size()) : elements.size();
        int absRank = Math.abs(rank);

        if (rank >= 0) {
            for (int i = 0; i < searchLimit; i++) {
                if (Arrays.equals(elements.get(i), value)) {
                    occurrences++;
                    if (count > 0 && positions.size() >= count) break;
                    if (absRank == 0 || occurrences >= absRank) {
                        positions.add(i);
                        if (count > 0 && positions.size() >= count) break;
                        if (absRank > 0) occurrences = 0;
                    }
                }
            }
        } else {
            for (int i = searchLimit - 1; i >= 0; i--) {
                if (Arrays.equals(elements.get(i), value)) {
                    occurrences++;
                    if (count > 0 && positions.size() >= count) break;
                    if (occurrences >= absRank) {
                        positions.add(i);
                        if (count > 0 && positions.size() >= count) break;
                        occurrences = 0;
                    }
                }
            }
        }
        return positions;
    }

    public byte[] lmove(RedisList dest, boolean fromLeft, boolean toLeft) {
        byte[] value = fromLeft ? lpop() : rpop();
        if (value == null) return null;
        if (toLeft) {
            dest.lpush(value);
        } else {
            dest.rpush(value);
        }
        return value;
    }

    private int normalizeIndex(long index) {
        int len = elements.size();
        if (index < 0) {
            return len + (int) index;
        }
        return (int) index;
    }

    private int indexOf(byte[] value) {
        for (int i = 0; i < elements.size(); i++) {
            if (Arrays.equals(elements.get(i), value)) {
                return i;
            }
        }
        return -1;
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }
}
