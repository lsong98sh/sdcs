package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.common.RedisStream.StreamEntry;
import com.qkinfotech.bizwax.sdcs.common.GeoHelper;
import com.qkinfotech.bizwax.sdcs.common.RedisZSet.ZSetEntry;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.function.Function;

public class GeoHandlers extends BaseHandler {

    public GeoHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public RedisMessage handleGeoAdd(List<RedisMessage> args) {
        if (args.size() < 3 || (args.size() - 1) % 3 != 0) {
            return RedisMessage.error("ERR wrong number of arguments for 'geoadd' command");
        }
        String key = args.get(0).asString();
        long added = 0;
        for (int i = 1; i + 2 < args.size(); i += 3) {
            double lon = Double.parseDouble(args.get(i).asString());
            double lat = Double.parseDouble(args.get(i + 1).asString());
            byte[] member = args.get(i + 2).getData();
            double geohash = GeoHelper.encode(lon, lat);
            long result = store().zadd(key, geohash, member);
            if (result == -1) {
                return RedisMessage.error("WRONGTYPE Operation against a key holding the wrong kind of value");
            }
            added += result;
        }
        return RedisMessage.integer(added);
    }

    public RedisMessage handleGeoDist(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("ERR wrong number of arguments for 'geodist' command");
        }
        String key = args.get(0).asString();
        byte[] m1 = args.get(1).getData();
        byte[] m2 = args.get(2).getData();
        String unit = args.size() > 3 ? args.get(3).asString() : "m";

        Double score1 = store().zscore(key, m1);
        Double score2 = store().zscore(key, m2);
        if (score1 == null || score2 == null) {
            return RedisMessage.bulkString((byte[]) null);
        }

        double[] pos1 = GeoHelper.decode(score1);
        double[] pos2 = GeoHelper.decode(score2);
        double distance = GeoHelper.dist(pos1[0], pos1[1], pos2[0], pos2[1]);
        distance = convertUnit(distance, unit);
        return RedisMessage.bulkString(String.valueOf(distance).getBytes());
    }

    public RedisMessage handleGeoPos(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("ERR wrong number of arguments for 'geopos' command");
        }
        String key = args.get(0).asString();
        RedisMessage[] results = new RedisMessage[args.size() - 1];
        for (int i = 1; i < args.size(); i++) {
            byte[] member = args.get(i).getData();
            Double score = store().zscore(key, member);
            if (score == null) {
                results[i - 1] = RedisMessage.bulkString((byte[]) null);
            } else {
                double[] pos = GeoHelper.decode(score);
                results[i - 1] = RedisMessage.array(
                        RedisMessage.bulkString(String.valueOf(pos[0]).getBytes()),
                        RedisMessage.bulkString(String.valueOf(pos[1]).getBytes())
                );
            }
        }
        return RedisMessage.array(results);
    }

    public RedisMessage handleGeoRadius(List<RedisMessage> args) {
        if (args.size() < 4) {
            return RedisMessage.error("ERR wrong number of arguments for 'georadius' command");
        }
        String key = args.get(0).asString();
        double lon = Double.parseDouble(args.get(1).asString());
        double lat = Double.parseDouble(args.get(2).asString());
        double radius = Double.parseDouble(args.get(3).asString());
        String unit = args.get(4).asString();

        radius = convertToMeters(radius, unit);

        boolean withCoord = false;
        boolean withDist = false;
        long count = -1;

        for (int i = 5; i < args.size(); i++) {
            String opt = args.get(i).asString().toUpperCase();
            switch (opt) {
                case "WITHCOORD" -> withCoord = true;
                case "WITHDIST" -> withDist = true;
                case "COUNT" -> {
                    if (i + 1 < args.size()) {
                        count = Long.parseLong(args.get(++i).asString());
                    }
                }
            }
        }

        double[] box = GeoHelper.getBoxRange(lon, lat, radius);
        double minLon = box[0], maxLon = box[1];
        double minLat = box[2], maxLat = box[3];

        double minGeohash = GeoHelper.encode(minLon, minLat);
        double maxGeohash = GeoHelper.encode(maxLon, maxLat);

        List<ZSetEntry> candidates = store().zrangebyscore(key, minGeohash, maxGeohash, 0, -1);

        List<RedisMessage> resultList = new ArrayList<>();
        for (ZSetEntry entry : candidates) {
            double[] pos = GeoHelper.decode(entry.score());
            double d = GeoHelper.dist(lon, lat, pos[0], pos[1]);
            if (d <= radius) {
                List<RedisMessage> entryElements = new ArrayList<>();
                entryElements.add(RedisMessage.bulkString(entry.member()));
                if (withDist) {
                    entryElements.add(RedisMessage.bulkString(String.valueOf(convertUnit(d, unit)).getBytes()));
                }
                if (withCoord) {
                    entryElements.add(RedisMessage.array(
                            RedisMessage.bulkString(String.valueOf(pos[0]).getBytes()),
                            RedisMessage.bulkString(String.valueOf(pos[1]).getBytes())
                    ));
                }
                resultList.add(RedisMessage.array(entryElements.toArray(new RedisMessage[0])));
                if (count > 0 && resultList.size() >= count) {
                    break;
                }
            }
        }
        return RedisMessage.array(resultList.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleGeoRadiusByMember(List<RedisMessage> args) {
        if (args.size() < 3) {
            return RedisMessage.error("ERR wrong number of arguments for 'georadiusbymember' command");
        }
        String key = args.get(0).asString();
        byte[] member = args.get(1).getData();
        double radius = Double.parseDouble(args.get(2).asString());
        String unit = args.get(3).asString();

        Double score = store().zscore(key, member);
        if (score == null) {
            return RedisMessage.array(new RedisMessage[0]);
        }

        double[] center = GeoHelper.decode(score);
        double lon = center[0];
        double lat = center[1];

        radius = convertToMeters(radius, unit);

        boolean withCoord = false;
        boolean withDist = false;
        long count = -1;

        for (int i = 4; i < args.size(); i++) {
            String opt = args.get(i).asString().toUpperCase();
            switch (opt) {
                case "WITHCOORD" -> withCoord = true;
                case "WITHDIST" -> withDist = true;
                case "COUNT" -> {
                    if (i + 1 < args.size()) {
                        count = Long.parseLong(args.get(++i).asString());
                    }
                }
            }
        }

        double[] box = GeoHelper.getBoxRange(lon, lat, radius);
        double minGeohash = GeoHelper.encode(box[0], box[2]);
        double maxGeohash = GeoHelper.encode(box[1], box[3]);

        List<ZSetEntry> candidates = store().zrangebyscore(key, minGeohash, maxGeohash, 0, -1);

        List<RedisMessage> resultList = new ArrayList<>();
        for (ZSetEntry entry : candidates) {
            if (Arrays.equals(entry.member(), member)) {
                continue;
            }
            double[] pos = GeoHelper.decode(entry.score());
            double d = GeoHelper.dist(lon, lat, pos[0], pos[1]);
            if (d <= radius) {
                List<RedisMessage> entryElements = new ArrayList<>();
                entryElements.add(RedisMessage.bulkString(entry.member()));
                if (withDist) {
                    entryElements.add(RedisMessage.bulkString(String.valueOf(convertUnit(d, unit)).getBytes()));
                }
                if (withCoord) {
                    entryElements.add(RedisMessage.array(
                            RedisMessage.bulkString(String.valueOf(pos[0]).getBytes()),
                            RedisMessage.bulkString(String.valueOf(pos[1]).getBytes())
                    ));
                }
                resultList.add(RedisMessage.array(entryElements.toArray(new RedisMessage[0])));
                if (count > 0 && resultList.size() >= count) {
                    break;
                }
            }
        }
        return RedisMessage.array(resultList.toArray(new RedisMessage[0]));
    }

    public RedisMessage handleGeoHash(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("ERR wrong number of arguments for 'geohash' command");
        }
        String key = args.get(0).asString();
        RedisMessage[] results = new RedisMessage[args.size() - 1];
        for (int i = 1; i < args.size(); i++) {
            byte[] member = args.get(i).getData();
            Double score = store().zscore(key, member);
            if (score == null) {
                results[i - 1] = RedisMessage.bulkString((byte[]) null);
            } else {
                results[i - 1] = RedisMessage.bulkString(String.valueOf(score.longValue()).getBytes());
            }
        }
        return RedisMessage.array(results);
    }

    private static double convertUnit(double meters, String unit) {
        return switch (unit.toLowerCase()) {
            case "km" -> meters / 1000.0;
            case "mi" -> meters / 1609.344;
            case "ft" -> meters / 0.3048;
            default -> meters;
        };
    }

    private static double convertToMeters(double value, String unit) {
        return switch (unit.toLowerCase()) {
            case "km" -> value * 1000.0;
            case "mi" -> value * 1609.344;
            case "ft" -> value * 0.3048;
            default -> value;
        };
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("GEOADD", this::handleGeoAdd);
        registry.put("GEODIST", this::handleGeoDist);
        registry.put("GEOPOS", this::handleGeoPos);
        registry.put("GEORADIUS", this::handleGeoRadius);
        registry.put("GEORADIUSBYMEMBER", this::handleGeoRadiusByMember);
        registry.put("GEOHASH", this::handleGeoHash);
    }
}
