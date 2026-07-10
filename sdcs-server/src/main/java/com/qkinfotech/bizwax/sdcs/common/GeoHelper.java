package com.qkinfotech.bizwax.sdcs.common;

public class GeoHelper {
    private static final double EARTH_RADIUS = 6378137;

    public static double encode(double longitude, double latitude) {
        return geohashEncode(longitude, latitude);
    }

    public static double[] decode(double geohash) {
        return geohashDecode(geohash);
    }

    public static double dist(double lon1, double lat1, double lon2, double lat2) {
        double dlon = Math.toRadians(lon2 - lon1);
        double dlat = Math.toRadians(lat2 - lat1);
        double a = Math.sin(dlat / 2) * Math.sin(dlat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dlon / 2) * Math.sin(dlon / 2);
        return EARTH_RADIUS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static double[] getBoxRange(double lon, double lat, double radius) {
        double latR = Math.toRadians(lat);
        double lonR = Math.toRadians(lon);
        double radDist = radius / EARTH_RADIUS;

        double minLat = latR - radDist;
        double maxLat = latR + radDist;
        double minLon, maxLon;
        if (minLat > -Math.PI / 2 && maxLat < Math.PI / 2) {
            double deltaLon = Math.asin(Math.sin(radDist) / Math.cos(latR));
            minLon = lonR - deltaLon;
            maxLon = lonR + deltaLon;
        } else {
            minLat = Math.max(minLat, -Math.PI / 2);
            maxLat = Math.min(maxLat, Math.PI / 2);
            minLon = -Math.PI;
            maxLon = Math.PI;
        }
        return new double[]{Math.toDegrees(minLon), Math.toDegrees(maxLon),
                Math.toDegrees(minLat), Math.toDegrees(maxLat)};
    }

    private static double geohashEncode(double lon, double lat) {
        double encoded = 0;
        double minLon = -180, maxLon = 180;
        double minLat = -90, maxLat = 90;
        for (int i = 0; i < 26; i++) {
            double midLon = (minLon + maxLon) / 2;
            double midLat = (minLat + maxLat) / 2;
            if (i % 2 == 0) {
                if (lon > midLon) {
                    encoded = encoded * 2 + 1;
                    minLon = midLon;
                } else {
                    encoded = encoded * 2;
                    maxLon = midLon;
                }
            } else {
                if (lat > midLat) {
                    encoded = encoded * 2 + 1;
                    minLat = midLat;
                } else {
                    encoded = encoded * 2;
                    maxLat = midLat;
                }
            }
        }
        return encoded;
    }

    private static double[] geohashDecode(double hash) {
        double minLon = -180, maxLon = 180;
        double minLat = -90, maxLat = 90;
        for (int i = 25; i >= 0; i--) {
            if (i % 2 == 0) {
                if (((long) hash & (1L << i)) != 0) {
                    minLon = (minLon + maxLon) / 2;
                } else {
                    maxLon = (minLon + maxLon) / 2;
                }
            } else {
                if (((long) hash & (1L << i)) != 0) {
                    minLat = (minLat + maxLat) / 2;
                } else {
                    maxLat = (minLat + maxLat) / 2;
                }
            }
        }
        return new double[]{(minLon + maxLon) / 2, (minLat + maxLat) / 2};
    }
}
